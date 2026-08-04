/**
 * Pure favorites logic — no I/O.
 * Parses/serializes the Favorites File (.cloudamp/favorites.json) and applies
 * a single toggle onto a remote list (see docs/adr/0001-favorites-json-snapshot.md).
 * The full loaded list is always carried through to serialization so entries
 * for albums missing from the current library are never destroyed.
 */

export const FAVORITES_SCHEMA_VERSION = 1;

export interface FavoriteEntry {
  albumId: string;
  favoritedAt: string;
}

export interface FavoritesFile {
  schemaVersion: number;
  favorites: FavoriteEntry[];
}

export type ParseFavoritesResult =
  | { ok: true; file: FavoritesFile }
  | { ok: false; reason: "unknown-version"; schemaVersion: unknown };

export function emptyFavoritesFile(): FavoritesFile {
  return { schemaVersion: FAVORITES_SCHEMA_VERSION, favorites: [] };
}

/**
 * Parse the Favorites File's text content.
 * - Empty or malformed content bootstraps an empty file (first-write case).
 * - A schemaVersion we don't understand refuses to parse so callers never
 *   rewrite (and thereby destroy) data written by a newer client.
 */
export function parseFavoritesFile(text: string): ParseFavoritesResult {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    return { ok: true, file: emptyFavoritesFile() };
  }
  if (typeof data !== "object" || data === null || Array.isArray(data)) {
    return { ok: true, file: emptyFavoritesFile() };
  }

  const obj = data as { schemaVersion?: unknown; favorites?: unknown };
  if (obj.schemaVersion !== FAVORITES_SCHEMA_VERSION) {
    return { ok: false, reason: "unknown-version", schemaVersion: obj.schemaVersion };
  }

  const rawList = Array.isArray(obj.favorites) ? obj.favorites : [];
  const favorites: FavoriteEntry[] = [];
  for (const entry of rawList) {
    if (typeof entry === "object" && entry !== null && typeof (entry as { albumId?: unknown }).albumId === "string") {
      const e = entry as { albumId: string; favoritedAt?: unknown };
      favorites.push({
        albumId: e.albumId,
        favoritedAt: typeof e.favoritedAt === "string" ? e.favoritedAt : "",
      });
    }
  }
  return { ok: true, file: { schemaVersion: FAVORITES_SCHEMA_VERSION, favorites } };
}

/**
 * Apply one toggle on top of a (freshly fetched) remote list.
 * All other entries pass through untouched, whether or not they resolve
 * against the current library.
 */
export function applyToggle(
  file: FavoritesFile,
  albumId: string,
  favorite: boolean,
  favoritedAt: string,
): FavoritesFile {
  if (favorite) {
    if (file.favorites.some((f) => f.albumId === albumId)) return file;
    return { ...file, favorites: [...file.favorites, { albumId, favoritedAt }] };
  }
  return { ...file, favorites: file.favorites.filter((f) => f.albumId !== albumId) };
}

export function isFavorite(file: FavoritesFile, albumId: string): boolean {
  return file.favorites.some((f) => f.albumId === albumId);
}

/**
 * Resolve favorite entries against the current library for display.
 * Entries whose albumId doesn't resolve are hidden — not removed — so they
 * reappear automatically when the album returns to the library (ADR-0001).
 * Most recently favorited first.
 */
export function resolveFavoriteAlbums<A>(
  favorites: FavoriteEntry[],
  albumById: Map<string, A>,
): A[] {
  return [...favorites]
    .sort((a, b) => b.favoritedAt.localeCompare(a.favoritedAt))
    .flatMap((f) => {
      const album = albumById.get(f.albumId);
      return album === undefined ? [] : [album];
    });
}

/**
 * Serialize the full list — never a view-filtered one.
 */
export function serializeFavoritesFile(file: FavoritesFile): string {
  return JSON.stringify(
    {
      schemaVersion: file.schemaVersion,
      favorites: file.favorites.map((f) => ({ albumId: f.albumId, favoritedAt: f.favoritedAt })),
    },
    null,
    2,
  );
}
