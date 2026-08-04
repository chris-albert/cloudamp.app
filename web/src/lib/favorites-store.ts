/**
 * Reactive store for Favorite Albums, persisted to the Favorites File
 * (<music library>/.cloudamp/favorites.json) on Google Drive.
 * Follows the same useSyncExternalStore pattern as history-store.ts.
 *
 * Every toggle is read-merge-write per ADR-0001: GET the file fresh, apply
 * only that toggle on top of the remote list, PUT the result.
 *
 * The list is also cached in IndexedDB (favorites-cache.ts) so hearts render
 * at app start without waiting on Drive; a fresh Drive read then reconciles
 * the cache. Toggles whose write fails (offline) are kept locally, marked
 * dirty, and pushed through the same read-merge-write flow at next launch or
 * next successful toggle.
 */

import {
  fetchAllPaginated,
  fetchFileText,
  createFolder,
  uploadFile,
  updateFileContent,
} from "./drive-api";
import { getRootFolderId } from "./google-auth";
import {
  applyToggle,
  emptyFavoritesFile,
  parseFavoritesFile,
  serializeFavoritesFile,
  type FavoriteEntry,
  type FavoritesFile,
} from "./favorites-core";
import {
  loadFavoritesCache,
  saveFavoritesCache,
  clearFavoritesCache,
  type PendingToggle,
} from "./favorites-cache";

const CLOUDAMP_FOLDER_NAME = ".cloudamp";
const FAVORITES_FILE_NAME = "favorites.json";

export interface FavoritesState {
  status: "idle" | "loading" | "done" | "error";
  favorites: FavoriteEntry[];
  error: string | null;
}

// ── State ─────────────────────────────────────────────────────────────

let state: FavoritesState = { status: "idle", favorites: [], error: null };

const listeners = new Set<() => void>();

function emit() {
  for (const fn of listeners) fn();
}

function setState(next: FavoritesState) {
  state = next;
  emit();
}

export function getFavoritesState(): FavoritesState {
  return state;
}

export function subscribeFavoritesState(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function isAlbumFavorite(albumId: string): boolean {
  return state.favorites.some((f) => f.albumId === albumId);
}

// Drive ids are stable for the session; content is always re-fetched per toggle.
let cachedFolderId: string | null = null;
let cachedFileId: string | null = null;

// Toggles whose Drive write failed, keyed by albumId so the latest toggle per
// album wins. Persisted alongside the list and retried at next launch/toggle.
let dirtyToggles = new Map<string, PendingToggle>();

function persistCache() {
  saveFavoritesCache({
    favorites: state.favorites,
    pending: [...dirtyToggles.values()],
  });
}

export function resetFavorites() {
  state = { status: "idle", favorites: [], error: null };
  cachedFolderId = null;
  cachedFileId = null;
  dirtyToggles = new Map();
  loadStarted = false;
  clearFavoritesCache();
  emit();
}

// ── Drive I/O ─────────────────────────────────────────────────────────

function requireRootFolderId(): string {
  const rootId = getRootFolderId();
  if (!rootId) {
    throw new Error("Music library folder is not configured. Set it in Settings.");
  }
  return rootId;
}

async function findCloudampFolderId(rootId: string): Promise<string | null> {
  if (cachedFolderId) return cachedFolderId;
  const folders = await fetchAllPaginated(
    `name = '${CLOUDAMP_FOLDER_NAME}' and mimeType = 'application/vnd.google-apps.folder' and '${rootId}' in parents and trashed = false`,
    "id,name",
  );
  cachedFolderId = folders[0]?.id ?? null;
  return cachedFolderId;
}

async function findFavoritesFileId(folderId: string): Promise<string | null> {
  if (cachedFileId) return cachedFileId;
  const files = await fetchAllPaginated(
    `name = '${FAVORITES_FILE_NAME}' and '${folderId}' in parents and trashed = false`,
    "id,name",
  );
  cachedFileId = files[0]?.id ?? null;
  return cachedFileId;
}

async function findOrCreateCloudampFolderId(rootId: string): Promise<string> {
  const existing = await findCloudampFolderId(rootId);
  if (existing) return existing;
  cachedFolderId = await createFolder(CLOUDAMP_FOLDER_NAME, rootId);
  return cachedFolderId;
}

// Unlike a network failure, this is a deliberate refusal (a newer client owns
// the file) — retrying later can't help, so it must not go on the dirty queue.
class UnsupportedSchemaError extends Error {}

/** Fetch and parse the remote Favorites File. Throws on an unknown schemaVersion. */
async function fetchRemoteFavorites(fileId: string): Promise<FavoritesFile> {
  const text = await fetchFileText(fileId);
  const parsed = parseFavoritesFile(text);
  if (!parsed.ok) {
    throw new UnsupportedSchemaError(
      `Favorites file has unsupported schemaVersion ${String(parsed.schemaVersion)}; not overwriting it`,
    );
  }
  return parsed.file;
}

// ── Load ──────────────────────────────────────────────────────────────

let loadStarted = false;

/**
 * Load favorites once (safe to call from every mount).
 */
export function ensureFavoritesLoaded(): void {
  if (loadStarted) return;
  loadStarted = true;
  void loadFavorites();
}

/**
 * Hydrate from the IndexedDB cache so hearts render before any Drive request
 * completes, then reconcile against Drive — pushing dirty toggles from a
 * previous session via read-merge-write when there are any.
 */
export async function loadFavorites(): Promise<void> {
  loadStarted = true;
  const cached = await loadFavoritesCache();
  if (cached) {
    dirtyToggles = new Map(cached.pending.map((t) => [t.albumId, t]));
    setState({ status: "done", favorites: cached.favorites, error: null });
  } else {
    setState({ ...state, status: "loading", error: null });
  }

  if (dirtyToggles.size > 0) {
    await flushDirtyToggles();
    return;
  }

  try {
    const rootId = requireRootFolderId();
    const folderId = await findCloudampFolderId(rootId);
    const fileId = folderId ? await findFavoritesFileId(folderId) : null;
    const file = fileId ? await fetchRemoteFavorites(fileId) : emptyFavoritesFile();
    setState({ status: "done", favorites: file.favorites, error: null });
    persistCache();
  } catch (err) {
    // With a cache the hydrated state stays usable; without one there's
    // nothing to show but the error.
    setState({
      ...state,
      status: cached ? "done" : "error",
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

/** Push dirty toggles from a previous session through read-merge-write. */
async function flushDirtyToggles(): Promise<void> {
  const run = toggleQueue.then(async () => {
    const pending = [...dirtyToggles.values()];
    if (pending.length === 0) return; // already drained by an earlier toggle
    const merged = await writeToggles(pending);
    for (const t of pending) dirtyToggles.delete(t.albumId);
    setState({ status: "done", favorites: merged.favorites, error: null });
    persistCache();
  });
  toggleQueue = run.catch(() => {});
  try {
    await run;
  } catch (err) {
    // Still offline — keep the cached state and dirty toggles for next time
    setState({
      ...state,
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

// ── Toggle ────────────────────────────────────────────────────────────

// Toggles run one at a time so each write merges onto the previous one's result.
let toggleQueue: Promise<void> = Promise.resolve();

/**
 * Toggle an album's Favorite state. Updates local state optimistically, then
 * persists via read-merge-write. If the write fails the toggled state is kept
 * and marked dirty for retry at next launch/toggle — except on an unsupported
 * schemaVersion, where the optimistic change is reverted and the error rethrown.
 */
export function toggleFavorite(albumId: string): Promise<void> {
  const favorite = !isAlbumFavorite(albumId);
  const favoritedAt = new Date().toISOString();

  // Optimistic local update
  const optimistic = applyToggle(
    { schemaVersion: 1, favorites: state.favorites },
    albumId,
    favorite,
    favoritedAt,
  );
  setState({ ...state, favorites: optimistic.favorites });

  const run = toggleQueue.then(async () => {
    const toggle: PendingToggle = { albumId, favorite, favoritedAt };
    // Dirty toggles from earlier failures ride along in the same write
    const pending = [...dirtyToggles.values()].filter((t) => t.albumId !== albumId);
    try {
      const merged = await writeToggles([...pending, toggle]);
      for (const t of pending) dirtyToggles.delete(t.albumId);
      dirtyToggles.delete(albumId);
      setState({ ...state, favorites: merged.favorites, error: null });
      persistCache();
    } catch (err) {
      if (err instanceof UnsupportedSchemaError) {
        // Revert the optimistic change — this write is refused, not deferred
        const reverted = applyToggle(
          { schemaVersion: 1, favorites: state.favorites },
          albumId,
          !favorite,
          favoritedAt,
        );
        setState({ ...state, favorites: reverted.favorites, error: err.message });
        throw err;
      }
      // Offline or Drive error: keep the toggled state and retry later
      dirtyToggles.set(albumId, toggle);
      setState({
        ...state,
        error: err instanceof Error ? err.message : String(err),
      });
      persistCache();
    }
  });
  toggleQueue = run.catch(() => {});
  return run;
}

/**
 * Read-merge-write a batch of toggles: GET the file fresh, apply only these
 * toggles on top of the remote list, PUT the result. Creates .cloudamp and
 * the Favorites File on first write. Returns the merged file that was written.
 */
async function writeToggles(toggles: PendingToggle[]): Promise<FavoritesFile> {
  const rootId = requireRootFolderId();
  const folderId = await findOrCreateCloudampFolderId(rootId);
  const fileId = await findFavoritesFileId(folderId);

  const remote = fileId ? await fetchRemoteFavorites(fileId) : emptyFavoritesFile();
  let merged = remote;
  for (const t of toggles) {
    merged = applyToggle(merged, t.albumId, t.favorite, t.favoritedAt);
  }
  const blob = new Blob([serializeFavoritesFile(merged)], { type: "application/json" });

  if (fileId) {
    await updateFileContent(fileId, blob);
  } else {
    cachedFileId = await uploadFile(FAVORITES_FILE_NAME, folderId, blob);
  }
  return merged;
}
