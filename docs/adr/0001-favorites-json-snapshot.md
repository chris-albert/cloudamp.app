# Favorites stored as a JSON snapshot with read-merge-write, not an NDJSON event log

Favorites sync across web and Android via a shared file in Drive, like playback history — but where playback history is an append-only NDJSON event log, favorites is a single snapshot, `.cloudamp/favorites.json` (`{ schemaVersion, favorites: [{ albumId, favoritedAt }] }`). Favorites are low-frequency, trivially re-doable toggles, so the snapshot's last-write-wins risk was judged acceptable in exchange for a file both clients can read in one GET and reason about trivially; the NDJSON event-log alternative was considered and rejected as over-engineering for this data.

Two rules keep the snapshot safe:

1. **Read-merge-write per toggle.** A toggle GETs the file fresh, applies only that toggle on top of the remote list, and PUTs the result — never a blind write of in-memory state. This shrinks the stale-client clobber window from "since app launch" to ~one request round-trip.
2. **Hide, never delete, orphans.** Entries whose `albumId` (a Drive folder id) isn't in the current library are filtered out of views at read time but always preserved on write. A client with a stale or partial library must never prune favorites it can't resolve.
