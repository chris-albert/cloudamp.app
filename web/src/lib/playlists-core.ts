/**
 * Pure playlists logic — no I/O.
 * Parses/serializes the Playlists File (.cloudamp/playlists.json) and replays
 * playlist ops onto a freshly fetched file (see docs/adr/0002-playlists-json-op-merge.md).
 * Mirrors android/.../cache/PlaylistsCore.kt so both platforms agree.
 */

import type { DriveFile } from "./drive-api";

export const PLAYLISTS_SCHEMA_VERSION = 1;

export interface PlaylistTrack {
  fileId: string;
  name: string;
  mimeType?: string;
  size?: string;
  /** Album folder id, when known — lets clients resolve library metadata. */
  parentId?: string;
  addedAt: string;
}

export interface Playlist {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
  tracks: PlaylistTrack[];
}

export interface PlaylistsFile {
  schemaVersion: number;
  playlists: Playlist[];
}

export type PlaylistOp =
  | { op: "create"; playlist: Playlist }
  | { op: "rename"; playlistId: string; name: string; at: string }
  | { op: "delete"; playlistId: string }
  | { op: "add-tracks"; playlistId: string; tracks: PlaylistTrack[]; at: string }
  | { op: "remove-track"; playlistId: string; fileId: string; at: string }
  | { op: "set-order"; playlistId: string; fileIds: string[]; at: string };

export type ParsePlaylistsResult =
  | { ok: true; file: PlaylistsFile }
  | { ok: false; reason: "unknown-version"; schemaVersion: unknown };

export function emptyPlaylistsFile(): PlaylistsFile {
  return { schemaVersion: PLAYLISTS_SCHEMA_VERSION, playlists: [] };
}

export function playlistTrackFromDriveFile(file: DriveFile, addedAt: string): PlaylistTrack {
  const track: PlaylistTrack = { fileId: file.id, name: file.name, addedAt };
  if (file.mimeType) track.mimeType = file.mimeType;
  if (file.size) track.size = file.size;
  if (file.parents?.[0]) track.parentId = file.parents[0];
  return track;
}

/** Rebuild the DriveFile shape the player expects. `parents` carries the album folder. */
export function playlistTrackToDriveFile(track: PlaylistTrack): DriveFile {
  return {
    id: track.fileId,
    name: track.name,
    mimeType: track.mimeType ?? "application/octet-stream",
    size: track.size,
    parents: track.parentId ? [track.parentId] : undefined,
  };
}

// ── Parse ─────────────────────────────────────────────────────────────

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function parseTrack(raw: unknown): PlaylistTrack | null {
  if (typeof raw !== "object" || raw === null) return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.fileId !== "string") return null;
  const track: PlaylistTrack = {
    fileId: r.fileId,
    name: optionalString(r.name) ?? "",
    addedAt: optionalString(r.addedAt) ?? "",
  };
  const mimeType = optionalString(r.mimeType);
  const size = optionalString(r.size);
  const parentId = optionalString(r.parentId);
  if (mimeType !== undefined) track.mimeType = mimeType;
  if (size !== undefined) track.size = size;
  if (parentId !== undefined) track.parentId = parentId;
  return track;
}

function parsePlaylist(raw: unknown): Playlist | null {
  if (typeof raw !== "object" || raw === null) return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== "string" || typeof r.name !== "string") return null;
  const rawTracks = Array.isArray(r.tracks) ? r.tracks : [];
  return {
    id: r.id,
    name: r.name,
    createdAt: optionalString(r.createdAt) ?? "",
    updatedAt: optionalString(r.updatedAt) ?? "",
    tracks: rawTracks.flatMap((t) => {
      const track = parseTrack(t);
      return track ? [track] : [];
    }),
  };
}

/**
 * Parse the Playlists File's text content.
 * - Empty or malformed content bootstraps an empty file (first-write case).
 * - A schemaVersion we don't understand refuses to parse so callers never
 *   rewrite (and thereby destroy) data written by a newer client.
 * - Playlists without a string id/name and tracks without a string fileId
 *   are dropped; other missing strings are coerced to "".
 */
export function parsePlaylistsFile(text: string): ParsePlaylistsResult {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    return { ok: true, file: emptyPlaylistsFile() };
  }
  if (typeof data !== "object" || data === null || Array.isArray(data)) {
    return { ok: true, file: emptyPlaylistsFile() };
  }

  const obj = data as { schemaVersion?: unknown; playlists?: unknown };
  if (obj.schemaVersion !== PLAYLISTS_SCHEMA_VERSION) {
    return { ok: false, reason: "unknown-version", schemaVersion: obj.schemaVersion };
  }

  const rawList = Array.isArray(obj.playlists) ? obj.playlists : [];
  const playlists = rawList.flatMap((p) => {
    const playlist = parsePlaylist(p);
    return playlist ? [playlist] : [];
  });
  return { ok: true, file: { schemaVersion: PLAYLISTS_SCHEMA_VERSION, playlists } };
}

// ── Apply ─────────────────────────────────────────────────────────────

function updatePlaylist(
  file: PlaylistsFile,
  playlistId: string,
  transform: (playlist: Playlist) => Playlist,
): PlaylistsFile {
  if (!file.playlists.some((p) => p.id === playlistId)) return file;
  return {
    ...file,
    playlists: file.playlists.map((p) => (p.id === playlistId ? transform(p) : p)),
  };
}

/**
 * Apply one op on top of a (freshly fetched) remote file. Ops targeting a
 * playlist that no longer exists remotely are dropped; a create whose id
 * already exists is a no-op (so retries are idempotent). All other
 * playlists pass through untouched.
 */
export function applyPlaylistOp(file: PlaylistsFile, op: PlaylistOp): PlaylistsFile {
  switch (op.op) {
    case "create":
      if (file.playlists.some((p) => p.id === op.playlist.id)) return file;
      return { ...file, playlists: [...file.playlists, op.playlist] };

    case "delete":
      return { ...file, playlists: file.playlists.filter((p) => p.id !== op.playlistId) };

    case "rename":
      return updatePlaylist(file, op.playlistId, (p) => ({ ...p, name: op.name, updatedAt: op.at }));

    case "add-tracks":
      return updatePlaylist(file, op.playlistId, (p) => {
        const existing = new Set(p.tracks.map((t) => t.fileId));
        const added = op.tracks.filter((t) => {
          if (existing.has(t.fileId)) return false;
          existing.add(t.fileId);
          return true;
        });
        if (added.length === 0) return p;
        return { ...p, tracks: [...p.tracks, ...added], updatedAt: op.at };
      });

    case "remove-track":
      return updatePlaylist(file, op.playlistId, (p) => ({
        ...p,
        tracks: p.tracks.filter((t) => t.fileId !== op.fileId),
        updatedAt: op.at,
      }));

    case "set-order":
      return updatePlaylist(file, op.playlistId, (p) => {
        const byId = new Map(p.tracks.map((t) => [t.fileId, t]));
        const ordered = op.fileIds.flatMap((id) => {
          const t = byId.get(id);
          return t ? [t] : [];
        });
        const orderedIds = new Set(ordered.map((t) => t.fileId));
        // Tracks added by another client since this order was captured keep their place at the end
        const remaining = p.tracks.filter((t) => !orderedIds.has(t.fileId));
        return { ...p, tracks: [...ordered, ...remaining], updatedAt: op.at };
      });
  }
}

// ── Serialize ─────────────────────────────────────────────────────────

function trackToJson(t: PlaylistTrack): Record<string, string> {
  const out: Record<string, string> = { fileId: t.fileId, name: t.name };
  if (t.mimeType !== undefined) out.mimeType = t.mimeType;
  if (t.size !== undefined) out.size = t.size;
  if (t.parentId !== undefined) out.parentId = t.parentId;
  out.addedAt = t.addedAt;
  return out;
}

/**
 * Serialize the full file with exactly the documented fields.
 */
export function serializePlaylistsFile(file: PlaylistsFile): string {
  return JSON.stringify(
    {
      schemaVersion: file.schemaVersion,
      playlists: file.playlists.map((p) => ({
        id: p.id,
        name: p.name,
        createdAt: p.createdAt,
        updatedAt: p.updatedAt,
        tracks: p.tracks.map(trackToJson),
      })),
    },
    null,
    2,
  );
}
