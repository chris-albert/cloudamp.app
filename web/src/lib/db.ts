/**
 * Shared IndexedDB database for the CloudAmp web app.
 * All stores live in a single "cloudamp" database.
 */

const DB_NAME = "cloudamp";
const DB_VERSION = 3;

export const SCAN_CACHE_STORE = "scan_cache";
export const HISTORY_CACHE_STORE = "history_cache";
export const FAVORITES_CACHE_STORE = "favorites_cache";

let dbPromise: Promise<IDBDatabase> | null = null;

export function openDB(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise;

  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(SCAN_CACHE_STORE)) {
        db.createObjectStore(SCAN_CACHE_STORE);
      }
      if (!db.objectStoreNames.contains(HISTORY_CACHE_STORE)) {
        db.createObjectStore(HISTORY_CACHE_STORE);
      }
      if (!db.objectStoreNames.contains(FAVORITES_CACHE_STORE)) {
        db.createObjectStore(FAVORITES_CACHE_STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => {
      dbPromise = null;
      reject(request.error);
    };
  });

  return dbPromise;
}
