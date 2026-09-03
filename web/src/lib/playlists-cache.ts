/**
 * IndexedDB persistence for the playlists store: the last known playlists
 * plus any ops that couldn't reach Drive (offline), kept in order for retry
 * at next launch. Storage failures degrade to "no cache", never throw.
 */

import { openDB, PLAYLISTS_CACHE_STORE } from "./db";
import type { Playlist, PlaylistOp } from "./playlists-core";

const CACHE_KEY = "latest";

export interface PersistedPlaylists {
  playlists: Playlist[];
  pending: PlaylistOp[];
}

export async function loadPlaylistsCache(): Promise<PersistedPlaylists | null> {
  try {
    const db = await openDB();
    return new Promise((resolve) => {
      const tx = db.transaction(PLAYLISTS_CACHE_STORE, "readonly");
      const store = tx.objectStore(PLAYLISTS_CACHE_STORE);
      const request = store.get(CACHE_KEY);
      request.onsuccess = () => resolve(request.result ?? null);
      request.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

export async function savePlaylistsCache(data: PersistedPlaylists): Promise<void> {
  try {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(PLAYLISTS_CACHE_STORE, "readwrite");
      const store = tx.objectStore(PLAYLISTS_CACHE_STORE);
      const request = store.put(data, CACHE_KEY);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  } catch {
    // Storage unavailable — ignore
  }
}

export async function clearPlaylistsCache(): Promise<void> {
  try {
    const db = await openDB();
    return new Promise((resolve) => {
      const tx = db.transaction(PLAYLISTS_CACHE_STORE, "readwrite");
      const store = tx.objectStore(PLAYLISTS_CACHE_STORE);
      const request = store.delete(CACHE_KEY);
      request.onsuccess = () => resolve();
      request.onerror = () => resolve();
    });
  } catch {
    // ignore
  }
}
