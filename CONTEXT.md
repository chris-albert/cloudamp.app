# CloudAmp

A Google Drive–backed music player (web + Android) that treats a user's Drive folder hierarchy as the music library. Drive is both the media store and the sync backend: shared state between clients lives in a `.cloudamp/` folder at the music root.

## Language

**Library**:
The artist → album → track structure built by scanning the music root folder in Drive.

**Music Root**:
The Drive folder the user picked as the top of their library; all scans start here.

**Favorite Album**:
An album the user has explicitly marked as a favorite; identified by its Drive folder id.

**Favorites File**:
`.cloudamp/favorites.json` in Drive — the shared snapshot of all Favorite Albums (`{ schemaVersion, favorites: [{ albumId, favoritedAt }] }`), last-write-wins on conflict.

**Favorite Artist**:
Any artist with at least one Favorite Album — derived at read time, never stored.
_Avoid_: storing artist favorites; "starred", "liked"

## Relationships

- A **Favorite Artist** is computed from **Favorite Albums** (artist has ≥ 1 favorited album)
- A **Favorite Album** references an album by Drive folder id
- The **Favorites File** is updated read-merge-write per toggle; entries referencing albums missing from the **Library** are hidden in views but never deleted (see ADR-0001)

## Example dialogue

> **Dev:** "Do we write a record when someone's artist becomes a favorite?"
> **Domain expert:** "No — only **Favorite Albums** are stored. **Favorite Artist** is just a filter over them."

## Flagged ambiguities

- "favorite or rank" — resolved: v1 is a boolean **Favorite Album** only; ranking is a possible later concept, not part of favorites.
- "discover" was proposed for the all-favorites album grid — resolved: it's a plain browse view, so it is named **Favorites**; "discover" is reserved for a possible future serendipity feature.
