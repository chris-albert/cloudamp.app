/**
 * IndexedDB persistence for the favorites store: the last known favorites
 * list plus any toggles that couldn't reach Drive (offline), kept for retry
 * at next launch. Same resilience pattern as history-store's cache — storage
 * failures degrade to "no cache", never throw.
 */

import { openDB, FAVORITES_CACHE_STORE } from "./db";
import type { FavoriteEntry } from "./favorites-core";

const CACHE_KEY = "latest";

/** A toggle that hasn't reached Drive yet. The latest toggle per album wins. */
export interface PendingToggle {
  albumId: string;
  favorite: boolean;
  favoritedAt: string;
}

export interface PersistedFavorites {
  favorites: FavoriteEntry[];
  pending: PendingToggle[];
}

export async function loadFavoritesCache(): Promise<PersistedFavorites | null> {
  try {
    const db = await openDB();
    return new Promise((resolve) => {
      const tx = db.transaction(FAVORITES_CACHE_STORE, "readonly");
      const store = tx.objectStore(FAVORITES_CACHE_STORE);
      const request = store.get(CACHE_KEY);
      request.onsuccess = () => resolve(request.result ?? null);
      request.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

export async function saveFavoritesCache(data: PersistedFavorites): Promise<void> {
  try {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(FAVORITES_CACHE_STORE, "readwrite");
      const store = tx.objectStore(FAVORITES_CACHE_STORE);
      const request = store.put(data, CACHE_KEY);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  } catch {
    // Storage unavailable — ignore
  }
}

export async function clearFavoritesCache(): Promise<void> {
  try {
    const db = await openDB();
    return new Promise((resolve) => {
      const tx = db.transaction(FAVORITES_CACHE_STORE, "readwrite");
      const store = tx.objectStore(FAVORITES_CACHE_STORE);
      const request = store.delete(CACHE_KEY);
      request.onsuccess = () => resolve();
      request.onerror = () => resolve();
    });
  } catch {
    // ignore
  }
}
