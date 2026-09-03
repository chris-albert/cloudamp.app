# Playlists stored as a JSON snapshot, edited by replaying ops onto a fresh read

Playlists sync across web and Android via `.cloudamp/playlists.json` under the library root, next to `favorites.json` (ADR-0001). The file is a single snapshot:

```
{ "schemaVersion": 1,
  "playlists": [{ "id", "name", "createdAt", "updatedAt",
                  "tracks": [{ "fileId", "name", "mimeType"?, "size"?, "parentId"?, "addedAt" }] }] }
```

A playlist entry is a Drive file id plus enough of the file's metadata (name, mime type, size, album folder id) for a client to play it and resolve library metadata without a Drive lookup. Entries may come from the structured library or from raw Drive browsing; both are just files, so a playlist can mix them.

Favorites' per-toggle read-merge-write doesn't transfer directly: a playlist edit is not a set toggle, and a blind "GET, replace whole playlist, PUT" would let two devices editing the same playlist erase each other's tracks. So every edit is expressed as a small **op** — `create`, `rename`, `delete`, `add-tracks`, `remove-track`, `set-order` — applied to local state at once and queued. A write GETs the file fresh, replays the queued ops on top of the remote content, and PUTs the result. Op semantics are chosen to merge sensibly against concurrent edits:

- `add-tracks` skips file ids already present, so the same song added on two devices appears once.
- `remove-track` targets a file id, not an index.
- `set-order` reorders the tracks it knows about and appends any it doesn't (added elsewhere) at the end.
- Ops targeting a playlist that was deleted elsewhere are dropped; a `create` whose id already exists is a no-op, so retries are idempotent.

Two devices reordering the same playlist at once is last-write-wins, which was judged acceptable for the same reasons as favorites: low frequency, trivially re-doable. An unknown `schemaVersion` is never overwritten. Ops that fail to reach Drive stay queued in the local cache (IndexedDB on web, SharedPreferences on Android) and are replayed at the next launch or next successful edit. Playlists are not exposed on Android Auto.
