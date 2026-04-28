/**
 * Fetches and parses playback history from the .cloudamp/playback_history.ndjson
 * file on Google Drive (written by the Android app).
 */

import { fetchAllPaginated, fetchFileText } from "./drive-api";

export interface PlayEvent {
  type: "play";
  trackId: string;
  trackName: string;
  albumId: string;
  albumName: string;
  artistName: string;
  playedAt: string;
}

export interface AlbumAddedEvent {
  type: "album_added";
  albumId: string;
  albumName: string;
  artistName: string;
  addedAt: string;
}

export type HistoryEvent = PlayEvent | AlbumAddedEvent;

/**
 * Find the .cloudamp folder in the user's Drive root,
 * then find playback_history.ndjson inside it,
 * download its contents, and parse the NDJSON lines.
 * Returns events in reverse chronological order (most recent first).
 */
export async function fetchPlaybackHistory(): Promise<HistoryEvent[]> {
  // 1. Find the .cloudamp folder in root
  const folders = await fetchAllPaginated(
    "name = '.cloudamp' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false",
    "id,name",
  );
  if (folders.length === 0) {
    return [];
  }
  const folderId = folders[0]!.id;

  // 2. Find the playback_history.ndjson file
  const files = await fetchAllPaginated(
    `name = 'playback_history.ndjson' and '${folderId}' in parents and trashed = false`,
    "id,name",
  );
  if (files.length === 0) {
    return [];
  }
  const fileId = files[0]!.id;

  // 3. Download and parse
  const text = await fetchFileText(fileId);
  const events: HistoryEvent[] = [];
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    try {
      const obj = JSON.parse(line);
      if (obj.type === "play" || obj.type === "album_added") {
        events.push(obj as HistoryEvent);
      }
    } catch {
      // Skip malformed lines
    }
  }

  // Return most recent first
  events.reverse();
  return events;
}
