/**
 * Reactive store for playback history with IndexedDB persistence.
 * Follows the same useSyncExternalStore pattern as scan-store.ts.
 */

import type { HistoryEvent } from "./playback-history";
import { openDB, HISTORY_CACHE_STORE } from "./db";

const STORE_NAME = HISTORY_CACHE_STORE;
const CACHE_KEY = "latest";

interface PersistedHistory {
  events: HistoryEvent[];
  fetchedAt: number;
}

export interface HistoryState {
  status: "idle" | "loading" | "done" | "error";
  events: HistoryEvent[];
  error: string | null;
  fetchedAt: number | null;
}

// ── IndexedDB helpers ─────────────────────────────────────────────────

async function loadFromDB(): Promise<PersistedHistory | null> {
  try {
    const db = await openDB();
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, "readonly");
      const store = tx.objectStore(STORE_NAME);
      const request = store.get(CACHE_KEY);
      request.onsuccess = () => resolve(request.result ?? null);
      request.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

async function saveToDB(data: PersistedHistory): Promise<void> {
  try {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, "readwrite");
      const store = tx.objectStore(STORE_NAME);
      const request = store.put(data, CACHE_KEY);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  } catch {
    // Storage unavailable — ignore
  }
}

async function clearDB(): Promise<void> {
  try {
    const db = await openDB();
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, "readwrite");
      const store = tx.objectStore(STORE_NAME);
      const request = store.delete(CACHE_KEY);
      request.onsuccess = () => resolve();
      request.onerror = () => resolve();
    });
  } catch {
    // ignore
  }
}

// ── State ─────────────────────────────────────────────────────────────

let state: HistoryState = {
  status: "idle",
  events: [],
  error: null,
  fetchedAt: null,
};

const listeners = new Set<() => void>();

function emit() {
  for (const fn of listeners) fn();
}

// Hydrate from IndexedDB on startup
loadFromDB().then((data) => {
  if (data?.events?.length) {
    state = {
      ...state,
      status: "done",
      events: data.events,
      fetchedAt: data.fetchedAt,
    };
    emit();
  }
});

export function getHistoryState(): HistoryState {
  return state;
}

export function subscribeHistoryState(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function setHistoryLoading() {
  state = { ...state, status: "loading" };
  emit();
}

export function setHistoryEvents(events: HistoryEvent[]) {
  const fetchedAt = Date.now();
  state = { ...state, status: "done", events, error: null, fetchedAt };
  saveToDB({ events, fetchedAt });
  emit();
}

export function setHistoryError(error: string) {
  state = { ...state, status: "error", error };
  emit();
}

export function resetHistory() {
  state = { status: "idle", events: [], error: null, fetchedAt: null };
  clearDB();
  emit();
}
