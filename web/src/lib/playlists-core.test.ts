import { describe, it, expect } from "vitest";
import {
  applyPlaylistOp,
  parsePlaylistsFile,
  serializePlaylistsFile,
  playlistTrackFromDriveFile,
  playlistTrackToDriveFile,
  type Playlist,
  type PlaylistTrack,
  type PlaylistsFile,
} from "./playlists-core";

function track(id: string): PlaylistTrack {
  return { fileId: id, name: `${id}.mp3`, mimeType: "audio/mpeg", parentId: "album-1", addedAt: "2026-08-01T00:00:00Z" };
}

function playlist(id: string, ...trackIds: string[]): Playlist {
  return {
    id,
    name: `Playlist ${id}`,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    tracks: trackIds.map(track),
  };
}

function file(...playlists: Playlist[]): PlaylistsFile {
  return { schemaVersion: 1, playlists };
}

const ids = (f: PlaylistsFile, playlistIndex = 0) => f.playlists[playlistIndex]!.tracks.map((t) => t.fileId);

describe("parsePlaylistsFile", () => {
  it("bootstraps an empty file from empty or malformed content", () => {
    for (const text of ["", "not json", "[]", "42"]) {
      const result = parsePlaylistsFile(text);
      expect(result.ok && result.file.playlists).toEqual([]);
    }
  });

  it("refuses an unknown schemaVersion", () => {
    expect(parsePlaylistsFile(JSON.stringify({ schemaVersion: 2, playlists: [] })).ok).toBe(false);
    expect(parsePlaylistsFile(JSON.stringify({ playlists: [] })).ok).toBe(false);
  });

  it("drops entries missing required ids and coerces missing strings", () => {
    const result = parsePlaylistsFile(
      JSON.stringify({
        schemaVersion: 1,
        playlists: [
          { id: "p1", name: "Mix", tracks: [{ fileId: "f1", name: "one.mp3", parentId: "a1", addedAt: "t" }, { name: "no-id" }, { fileId: "f2" }] },
          { name: "no id" },
          { id: "p2", name: "Empty" },
        ],
      }),
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.file.playlists.map((p) => p.id)).toEqual(["p1", "p2"]);
    const p1 = result.file.playlists[0]!;
    expect(p1.createdAt).toBe("");
    expect(ids(result.file)).toEqual(["f1", "f2"]);
    expect(p1.tracks[0]!.parentId).toBe("a1");
    expect(p1.tracks[1]!.name).toBe("");
    expect(p1.tracks[1]!.parentId).toBeUndefined();
    expect(result.file.playlists[1]!.tracks).toEqual([]);
  });

  it("round-trips through serialize with exactly the documented fields", () => {
    const original = file(playlist("p1", "f1", "f2"));
    const json = serializePlaylistsFile(original);
    const raw = JSON.parse(json);
    expect(raw.schemaVersion).toBe(1);
    expect(Object.keys(raw.playlists[0].tracks[0])).toEqual(["fileId", "name", "mimeType", "parentId", "addedAt"]);
    const parsed = parsePlaylistsFile(json);
    expect(parsed.ok && parsed.file).toEqual(original);
  });
});

describe("applyPlaylistOp", () => {
  it("create appends and is idempotent on retry", () => {
    const once = applyPlaylistOp(file(playlist("p1")), { op: "create", playlist: playlist("p2") });
    expect(once.playlists.map((p) => p.id)).toEqual(["p1", "p2"]);
    expect(applyPlaylistOp(once, { op: "create", playlist: playlist("p2") })).toBe(once);
  });

  it("rename and delete only touch the targeted playlist", () => {
    const renamed = applyPlaylistOp(file(playlist("p1"), playlist("p2")), { op: "rename", playlistId: "p2", name: "New", at: "t2" });
    expect(renamed.playlists[0]!.name).toBe("Playlist p1");
    expect(renamed.playlists[1]).toMatchObject({ name: "New", updatedAt: "t2" });
    const deleted = applyPlaylistOp(renamed, { op: "delete", playlistId: "p1" });
    expect(deleted.playlists.map((p) => p.id)).toEqual(["p2"]);
  });

  it("drops ops targeting a playlist deleted elsewhere", () => {
    const start = file(playlist("p1"));
    expect(applyPlaylistOp(start, { op: "rename", playlistId: "gone", name: "x", at: "t" })).toBe(start);
    expect(applyPlaylistOp(start, { op: "add-tracks", playlistId: "gone", tracks: [track("f9")], at: "t" })).toBe(start);
    expect(applyPlaylistOp(start, { op: "remove-track", playlistId: "gone", fileId: "f1", at: "t" })).toBe(start);
    expect(applyPlaylistOp(start, { op: "set-order", playlistId: "gone", fileIds: ["f1"], at: "t" })).toBe(start);
  });

  it("add-tracks skips files already present and keeps remote additions", () => {
    const remote = file(playlist("p1", "f1", "f2", "f3"));
    const merged = applyPlaylistOp(remote, { op: "add-tracks", playlistId: "p1", tracks: [track("f2"), track("f4")], at: "t2" });
    expect(ids(merged)).toEqual(["f1", "f2", "f3", "f4"]);
    expect(merged.playlists[0]!.updatedAt).toBe("t2");
    expect(applyPlaylistOp(remote, { op: "add-tracks", playlistId: "p1", tracks: [track("f1")], at: "t3" })).toEqual(remote);
  });

  it("remove-track removes by file id", () => {
    const merged = applyPlaylistOp(file(playlist("p1", "f1", "f2")), { op: "remove-track", playlistId: "p1", fileId: "f1", at: "t" });
    expect(ids(merged)).toEqual(["f2"]);
  });

  it("set-order reorders known tracks and appends ones added elsewhere", () => {
    const remote = file(playlist("p1", "f1", "f2", "f3", "f4"));
    const merged = applyPlaylistOp(remote, { op: "set-order", playlistId: "p1", fileIds: ["f3", "f9", "f1"], at: "t" });
    expect(ids(merged)).toEqual(["f3", "f1", "f2", "f4"]);
  });
});

describe("DriveFile conversion", () => {
  it("keeps the album folder as parentId and restores it as parents", () => {
    const t = playlistTrackFromDriveFile(
      { id: "f1", name: "song.mp3", mimeType: "audio/mpeg", size: "123", parents: ["album-1"] },
      "t",
    );
    expect(t).toEqual({ fileId: "f1", name: "song.mp3", mimeType: "audio/mpeg", size: "123", parentId: "album-1", addedAt: "t" });
    expect(playlistTrackToDriveFile(t)).toEqual({ id: "f1", name: "song.mp3", mimeType: "audio/mpeg", size: "123", parents: ["album-1"] });
  });
});
