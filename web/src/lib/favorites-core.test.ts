import { describe, it, expect } from "vitest";
import {
  applyToggle,
  emptyFavoritesFile,
  isFavorite,
  parseFavoritesFile,
  serializeFavoritesFile,
  type FavoritesFile,
} from "./favorites-core";

function file(...favorites: { albumId: string; favoritedAt: string }[]): FavoritesFile {
  return { schemaVersion: 1, favorites };
}

describe("applyToggle", () => {
  it("adds an entry with favoritedAt when favoriting", () => {
    const result = applyToggle(emptyFavoritesFile(), "album-1", true, "2026-07-21T10:00:00Z");
    expect(result.favorites).toEqual([{ albumId: "album-1", favoritedAt: "2026-07-21T10:00:00Z" }]);
    expect(isFavorite(result, "album-1")).toBe(true);
  });

  it("removes the entry when unfavoriting", () => {
    const result = applyToggle(
      file({ albumId: "album-1", favoritedAt: "2026-07-21T10:00:00Z" }),
      "album-1",
      false,
      "2026-07-21T11:00:00Z",
    );
    expect(result.favorites).toEqual([]);
    expect(isFavorite(result, "album-1")).toBe(false);
  });

  it("keeps the original favoritedAt when favoriting an already-favorited album", () => {
    const original = file({ albumId: "album-1", favoritedAt: "2026-07-01T00:00:00Z" });
    const result = applyToggle(original, "album-1", true, "2026-07-21T10:00:00Z");
    expect(result.favorites).toEqual(original.favorites);
  });

  it("applies a toggle on top of a stale remote list without losing remote-added entries", () => {
    // Another client favorited album-2 after this client loaded; the toggle
    // is applied to the freshly fetched remote list, so album-2 survives.
    const remote = file(
      { albumId: "album-1", favoritedAt: "2026-07-01T00:00:00Z" },
      { albumId: "album-2", favoritedAt: "2026-07-20T00:00:00Z" },
    );
    const result = applyToggle(remote, "album-3", true, "2026-07-21T10:00:00Z");
    expect(result.favorites.map((f) => f.albumId)).toEqual(["album-1", "album-2", "album-3"]);
  });
});

describe("orphan preservation", () => {
  it("carries entries not in any library through toggle and serialize", () => {
    const remote = file(
      { albumId: "orphaned-album", favoritedAt: "2025-01-01T00:00:00Z" },
      { albumId: "album-1", favoritedAt: "2026-07-01T00:00:00Z" },
    );
    const result = applyToggle(remote, "album-1", false, "2026-07-21T10:00:00Z");
    const roundTripped = parseFavoritesFile(serializeFavoritesFile(result));
    expect(roundTripped).toEqual({
      ok: true,
      file: file({ albumId: "orphaned-album", favoritedAt: "2025-01-01T00:00:00Z" }),
    });
  });
});

describe("parseFavoritesFile", () => {
  it("bootstraps an empty file from empty content (first write)", () => {
    expect(parseFavoritesFile("")).toEqual({ ok: true, file: emptyFavoritesFile() });
  });

  it("bootstraps an empty file from malformed JSON", () => {
    expect(parseFavoritesFile("not json {")).toEqual({ ok: true, file: emptyFavoritesFile() });
  });

  it("parses a valid schemaVersion 1 file", () => {
    const text = serializeFavoritesFile(file({ albumId: "album-1", favoritedAt: "2026-07-21T10:00:00Z" }));
    expect(parseFavoritesFile(text)).toEqual({
      ok: true,
      file: file({ albumId: "album-1", favoritedAt: "2026-07-21T10:00:00Z" }),
    });
  });

  it("refuses to parse an unknown schemaVersion so callers cannot overwrite it", () => {
    const text = JSON.stringify({ schemaVersion: 2, favorites: [], somethingNew: true });
    expect(parseFavoritesFile(text)).toEqual({ ok: false, reason: "unknown-version", schemaVersion: 2 });
  });

  it("skips malformed entries but keeps well-formed ones", () => {
    const text = JSON.stringify({
      schemaVersion: 1,
      favorites: [{ albumId: "album-1", favoritedAt: "2026-07-21T10:00:00Z" }, { notAnAlbum: true }, "junk"],
    });
    expect(parseFavoritesFile(text)).toEqual({
      ok: true,
      file: file({ albumId: "album-1", favoritedAt: "2026-07-21T10:00:00Z" }),
    });
  });
});

describe("serializeFavoritesFile", () => {
  it("writes the documented schema shape", () => {
    const text = serializeFavoritesFile(file({ albumId: "album-1", favoritedAt: "2026-07-21T10:00:00Z" }));
    expect(JSON.parse(text)).toEqual({
      schemaVersion: 1,
      favorites: [{ albumId: "album-1", favoritedAt: "2026-07-21T10:00:00Z" }],
    });
  });
});
