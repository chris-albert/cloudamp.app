/**
 * Fetches and parses playback history from
 * <music library>/.cloudamp/playback_history.ndjson on Google Drive
 * (written by the Android app).
 */

import { fetchAllPaginated, fetchFileText } from "./drive-api";
import { getRootFolderId } from "./google-auth";

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
 * Find <music library>/.cloudamp/playback_history.ndjson, download its
 * contents, and parse the NDJSON lines.
 * Returns events in reverse chronological order (most recent first).
 */
export async function fetchPlaybackHistory(): Promise<HistoryEvent[]> {
  const rootFolderId = getRootFolderId();
  if (!rootFolderId) {
    throw new Error("Music library folder is not configured. Set it in Settings.");
  }

  // 1. Find the .cloudamp subfolder inside the library root.
  const subfolders = await fetchAllPaginated(
    `name = '.cloudamp' and mimeType = 'application/vnd.google-apps.folder' and '${rootFolderId}' in parents and trashed = false`,
    "id,name",
  );
  if (subfolders.length === 0) {
    return [];
  }
  const subfolderId = subfolders[0]!.id;

  // 2. Find the history file inside it.
  const files = await fetchAllPaginated(
    `name = 'playback_history.ndjson' and '${subfolderId}' in parents and trashed = false`,
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
