/**
 * Reactive store for Playlists, persisted to the Playlists File
 * (<music library>/.cloudamp/playlists.json) on Google Drive.
 * Follows the same useSyncExternalStore pattern as favorites-store.ts.
 *
 * Every edit is an op applied locally at once and queued; the flush GETs the
 * file fresh, replays the queued ops on top of the remote content, and PUTs
 * the result — never a blind write of in-memory state (ADR-0002).
 *
 * The list is also cached in IndexedDB (playlists-cache.ts) so playlists
 * render at app start without waiting on Drive; a fresh Drive read then
 * reconciles the cache. Ops whose write fails (offline) stay queued and are
 * replayed at next launch or next successful edit.
 */

import {
  fetchAllPaginated,
  fetchFileText,
  createFolder,
  uploadFile,
  updateFileContent,
  type DriveFile,
} from "./drive-api";
import { getRootFolderId } from "./google-auth";
import {
  applyPlaylistOp,
  emptyPlaylistsFile,
  parsePlaylistsFile,
  serializePlaylistsFile,
  playlistTrackFromDriveFile,
  type Playlist,
  type PlaylistOp,
  type PlaylistsFile,
} from "./playlists-core";
import { loadPlaylistsCache, savePlaylistsCache, clearPlaylistsCache } from "./playlists-cache";

const CLOUDAMP_FOLDER_NAME = ".cloudamp";
const PLAYLISTS_FILE_NAME = "playlists.json";

export interface PlaylistsState {
  status: "idle" | "loading" | "done" | "error";
  playlists: Playlist[];
  error: string | null;
}

// ── State ─────────────────────────────────────────────────────────────

let state: PlaylistsState = { status: "idle", playlists: [], error: null };

const listeners = new Set<() => void>();

function emit() {
  for (const fn of listeners) fn();
}

function setState(next: PlaylistsState) {
  state = next;
  emit();
}

export function getPlaylistsState(): PlaylistsState {
  return state;
}

export function subscribePlaylistsState(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getPlaylist(playlistId: string): Playlist | null {
  return state.playlists.find((p) => p.id === playlistId) ?? null;
}

// Drive ids are stable for the session; content is always re-fetched per write.
let cachedFolderId: string | null = null;
let cachedFileId: string | null = null;

// Ops whose Drive write hasn't succeeded yet, in application order.
let pendingOps: PlaylistOp[] = [];

function persistCache() {
  savePlaylistsCache({ playlists: state.playlists, pending: pendingOps });
}

export function resetPlaylists() {
  state = { status: "idle", playlists: [], error: null };
  cachedFolderId = null;
  cachedFileId = null;
  pendingOps = [];
  loadStarted = false;
  clearPlaylistsCache();
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

async function findPlaylistsFileId(folderId: string): Promise<string | null> {
  if (cachedFileId) return cachedFileId;
  const files = await fetchAllPaginated(
    `name = '${PLAYLISTS_FILE_NAME}' and '${folderId}' in parents and trashed = false`,
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
// the file) — retrying later can't help, so it must not stay on the queue.
class UnsupportedSchemaError extends Error {}

async function fetchRemotePlaylists(fileId: string): Promise<PlaylistsFile> {
  const text = await fetchFileText(fileId);
  const parsed = parsePlaylistsFile(text);
  if (!parsed.ok) {
    throw new UnsupportedSchemaError(
      `Playlists file has unsupported schemaVersion ${String(parsed.schemaVersion)}; not overwriting it`,
    );
  }
  return parsed.file;
}

// ── Load ──────────────────────────────────────────────────────────────

let loadStarted = false;

/** Load playlists once (safe to call from every mount). */
export function ensurePlaylistsLoaded(): void {
  if (loadStarted) return;
  loadStarted = true;
  void loadPlaylists();
}

/**
 * Hydrate from the IndexedDB cache so playlists render before any Drive
 * request completes, then reconcile against Drive — replaying queued ops
 * from a previous session when there are any.
 */
export async function loadPlaylists(): Promise<void> {
  loadStarted = true;
  const cached = await loadPlaylistsCache();
  if (cached) {
    pendingOps = [...cached.pending];
    setState({ status: "done", playlists: cached.playlists, error: null });
  } else {
    setState({ ...state, status: "loading", error: null });
  }

  if (pendingOps.length > 0) {
    await flushPendingOps();
    return;
  }

  try {
    const rootId = requireRootFolderId();
    const folderId = await findCloudampFolderId(rootId);
    const fileId = folderId ? await findPlaylistsFileId(folderId) : null;
    const file = fileId ? await fetchRemotePlaylists(fileId) : emptyPlaylistsFile();
    // Ops applied while the read was in flight ride on top of the fresh remote state
    const merged = pendingOps.reduce(applyPlaylistOp, file);
    setState({ status: "done", playlists: merged.playlists, error: null });
    persistCache();
  } catch (err) {
    setState({
      ...state,
      status: cached ? "done" : "error",
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

/** Refresh from Drive (used by the Playlists page on mount after the initial load). */
export function refreshPlaylists(): Promise<void> {
  const run = writeQueue.then(async () => {
    if (pendingOps.length > 0) {
      await flushBatch();
      return;
    }
    const rootId = requireRootFolderId();
    const folderId = await findCloudampFolderId(rootId);
    const fileId = folderId ? await findPlaylistsFileId(folderId) : null;
    const file = fileId ? await fetchRemotePlaylists(fileId) : emptyPlaylistsFile();
    // Ops applied while the read was in flight ride on top of the fresh remote state
    const merged = pendingOps.reduce(applyPlaylistOp, file);
    setState({ status: "done", playlists: merged.playlists, error: null });
    persistCache();
  });
  writeQueue = run.catch(() => {});
  return run.catch((err) => {
    setState({ ...state, error: err instanceof Error ? err.message : String(err) });
  });
}

/** Resolves once every queued write so far has been attempted (success or failure). */
export function awaitPlaylistWrites(): Promise<void> {
  return writeQueue;
}

// ── Edits ─────────────────────────────────────────────────────────────

// Writes run one at a time so each read-merge-write sees the previous one's result.
let writeQueue: Promise<void> = Promise.resolve();

/**
 * Apply an op locally at once, queue it, and push the queue through
 * read-merge-write. If the write fails the local state is kept and the op
 * stays queued for retry — except on an unsupported schemaVersion, where the
 * queued ops are dropped and the error rethrown.
 */
export function applyPlaylistOpLocally(op: PlaylistOp): Promise<void> {
  const optimistic = applyPlaylistOp(
    { schemaVersion: 1, playlists: state.playlists },
    op,
  );
  pendingOps = [...pendingOps, op];
  setState({ ...state, status: state.status === "idle" ? "done" : state.status, playlists: optimistic.playlists });
  persistCache();
  return flushPendingOps();
}

async function flushPendingOps(): Promise<void> {
  const run = writeQueue.then(flushBatch);
  writeQueue = run.catch(() => {});
  try {
    await run;
  } catch (err) {
    if (err instanceof UnsupportedSchemaError) throw err;
    // Offline or Drive error: keep the local state and queued ops for next time
    setState({ ...state, error: err instanceof Error ? err.message : String(err) });
  }
}

/** Replay every queued op onto the fresh remote file and PUT it. */
async function flushBatch(): Promise<void> {
  const batch = [...pendingOps];
  if (batch.length === 0) return;
  let merged: PlaylistsFile;
  try {
    merged = await writeOps(batch);
  } catch (err) {
    if (err instanceof UnsupportedSchemaError) {
      pendingOps = pendingOps.slice(batch.length);
      setState({ ...state, error: err.message });
      persistCache();
    }
    throw err;
  }
  pendingOps = pendingOps.slice(batch.length);
  // Ops applied while the write was in flight ride on top of the fresh remote state
  const current = pendingOps.reduce(applyPlaylistOp, merged);
  setState({ status: "done", playlists: current.playlists, error: null });
  persistCache();
}

/**
 * Read-merge-write a batch of ops: GET the file fresh, replay the ops on top
 * of the remote content, PUT the result. Creates .cloudamp and the Playlists
 * File on first write. Returns the merged file that was written.
 */
async function writeOps(ops: PlaylistOp[]): Promise<PlaylistsFile> {
  const rootId = requireRootFolderId();
  const folderId = await findOrCreateCloudampFolderId(rootId);
  const fileId = await findPlaylistsFileId(folderId);

  const remote = fileId ? await fetchRemotePlaylists(fileId) : emptyPlaylistsFile();
  const merged = ops.reduce(applyPlaylistOp, remote);
  const blob = new Blob([serializePlaylistsFile(merged)], { type: "application/json" });

  if (fileId) {
    await updateFileContent(fileId, blob);
  } else {
    cachedFileId = await uploadFile(PLAYLISTS_FILE_NAME, folderId, blob);
  }
  return merged;
}

// ── Convenience wrappers ──────────────────────────────────────────────

function now(): string {
  return new Date().toISOString();
}

export function createPlaylist(name: string, files: DriveFile[] = []): Playlist {
  const at = now();
  const playlist: Playlist = {
    id: crypto.randomUUID(),
    name,
    createdAt: at,
    updatedAt: at,
    tracks: files.map((f) => playlistTrackFromDriveFile(f, at)),
  };
  void applyPlaylistOpLocally({ op: "create", playlist });
  return playlist;
}

export function renamePlaylist(playlistId: string, name: string): Promise<void> {
  return applyPlaylistOpLocally({ op: "rename", playlistId, name, at: now() });
}

export function deletePlaylist(playlistId: string): Promise<void> {
  return applyPlaylistOpLocally({ op: "delete", playlistId });
}

/** Add files not already in the playlist. Returns how many were actually added. */
export function addTracksToPlaylist(playlistId: string, files: DriveFile[]): number {
  const playlist = getPlaylist(playlistId);
  if (!playlist) return 0;
  const existing = new Set(playlist.tracks.map((t) => t.fileId));
  const at = now();
  const tracks = files
    .filter((f) => {
      if (existing.has(f.id)) return false;
      existing.add(f.id);
      return true;
    })
    .map((f) => playlistTrackFromDriveFile(f, at));
  if (tracks.length === 0) return 0;
  void applyPlaylistOpLocally({ op: "add-tracks", playlistId, tracks, at });
  return tracks.length;
}

export function removeTrackFromPlaylist(playlistId: string, fileId: string): Promise<void> {
  return applyPlaylistOpLocally({ op: "remove-track", playlistId, fileId, at: now() });
}

export function setPlaylistOrder(playlistId: string, fileIds: string[]): Promise<void> {
  return applyPlaylistOpLocally({ op: "set-order", playlistId, fileIds, at: now() });
}
