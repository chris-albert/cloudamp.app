/**
 * Simple reactive store for scan state.
 * Uses useSyncExternalStore pattern.
 * Persists results to IndexedDB so they survive page refreshes.
 * (localStorage is too small for large libraries)
 */

import type { ScanResult, ScanProgress, Track, Album } from "./library-scanner";
import { parseTrackFilename, parseAlbumFolderName } from "./filename-parser";
import type { ValidationResult } from "./library-validator";
import { validateLibrary } from "./library-validator";

const DB_NAME = "cloudamp";
const DB_VERSION = 1;
const STORE_NAME = "scan_cache";
const CACHE_KEY = "latest";

interface PersistedData {
  result: ScanResult;
  validation: ValidationResult;
  scannedAt: number;
  changePageToken?: string;
}

export interface ScanState {
  status: "idle" | "scanning" | "syncing" | "done" | "error";
  progress: ScanProgress | null;
  result: ScanResult | null;
  validation: ValidationResult | null;
  error: string | null;
  /** Timestamp of when the scan completed */
  scannedAt: number | null;
  /** Token for the Drive Changes API to detect incremental changes */
  changePageToken: string | null;
}

// ── IndexedDB helpers ─────────────────────────────────────────────────

function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function loadFromDB(): Promise<PersistedData | null> {
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

async function saveToDB(data: PersistedData): Promise<void> {
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

let state: ScanState = {
  status: "idle",
  progress: null,
  result: null,
  validation: null,
  error: null,
  scannedAt: null,
  changePageToken: null,
};

const listeners = new Set<() => void>();

function emit() {
  for (const fn of listeners) fn();
}

// Hydrate from IndexedDB on startup
loadFromDB().then((data) => {
  if (data?.result && data.validation) {
    state = {
      ...state,
      status: "done",
      result: data.result,
      validation: data.validation,
      scannedAt: data.scannedAt,
      changePageToken: data.changePageToken ?? null,
    };
    emit();
  }
});

export function getScanState(): ScanState {
  return state;
}

export function subscribeScanState(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function setScanProgress(progress: ScanProgress) {
  state = { ...state, status: "scanning", progress };
  emit();
}

export function setSyncProgress(progress: ScanProgress) {
  state = { ...state, status: "syncing", progress };
  emit();
}

export function setScanResult(
  result: ScanResult,
  validation: ValidationResult,
  changePageToken?: string,
) {
  const scannedAt = Date.now();
  const token = changePageToken ?? state.changePageToken;
  state = { ...state, status: "done", progress: null, result, validation, scannedAt, changePageToken: token };
  saveToDB({ result, validation, scannedAt, changePageToken: token ?? undefined });
  emit();
}

export function setScanError(error: string) {
  state = { ...state, status: "error", progress: null, error };
  emit();
}

export function removeArtistFromState(artistId: string) {
  if (!state.result) return;

  const result = { ...state.result };

  // Remove all tracks for all albums by this artist
  const albums = result.albumsByArtist[artistId] ?? [];
  for (const album of albums) {
    const { [album.id]: _removed, ...rest } = result.tracksByAlbum;
    result.tracksByAlbum = rest;
  }

  // Remove albums for this artist
  const { [artistId]: _removedAlbums, ...remainingAlbumsByArtist } = result.albumsByArtist;
  result.albumsByArtist = remainingAlbumsByArtist;

  // Remove the artist
  result.artists = result.artists.filter((a) => a.id !== artistId);

  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

export function removeAlbumFromState(albumId: string, artistId: string) {
  if (!state.result) return;

  const result = { ...state.result };

  // Remove tracks for this album
  const { [albumId]: _removedTracks, ...remainingTracksByAlbum } = result.tracksByAlbum;
  result.tracksByAlbum = remainingTracksByAlbum;

  // Remove album from artist's album list
  const artistAlbums = (result.albumsByArtist[artistId] ?? []).filter((a) => a.id !== albumId);
  result.albumsByArtist = { ...result.albumsByArtist, [artistId]: artistAlbums };

  // Update artist album count
  result.artists = result.artists.map((a) =>
    a.id === artistId ? { ...a, albumCount: artistAlbums.length } : a,
  );

  // Re-validate to update issue counts
  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

/**
 * Update local state after a subfolder's files have been moved into the album folder.
 * Removes the subfolder from the album, adds its files as tracks in the album.
 * @param renames - Map of fileId → new filename (after renumbering/renaming on Drive)
 */
export function flattenSubfolderInState(
  albumId: string,
  subfolderId: string,
  renames?: Map<string, string>,
) {
  if (!state.result) return;

  const result = { ...state.result };

  // Find the album
  let album: Album | null = null;
  let artistId: string | null = null;
  for (const [aid, albums] of Object.entries(result.albumsByArtist)) {
    const found = albums.find((a) => a.id === albumId);
    if (found) {
      album = found;
      artistId = aid;
      break;
    }
  }
  if (!album || !artistId) return;

  // Find the subfolder being flattened
  const subfolder = (album.subfolders ?? []).find((sf) => sf.id === subfolderId);
  if (!subfolder) return;

  // Convert subfolder files to tracks, using renamed filenames if provided
  const parsedAlbum = parseAlbumFolderName(album.folderName);
  const albumRef = album;
  const artistIdRef = artistId;
  const newTracks: Track[] = subfolder.files.map((file) => {
    const effectiveName = renames?.get(file.id) ?? file.name;
    const parsed = parseTrackFilename(effectiveName);
    return {
      file: { ...file, name: effectiveName, parents: [albumId] },
      artistId: artistIdRef,
      artistName: albumRef.artistName,
      albumId,
      albumName: parsedAlbum.name,
      trackNumber: parsed.trackNumber,
      discNumber: parsed.discNumber,
      trackName: parsed.title,
      year: parsedAlbum.year,
    };
  });

  const existingTracks = result.tracksByAlbum[albumId] ?? [];
  const allTracks = [...existingTracks, ...newTracks].sort((a, b) => {
    const discA = a.discNumber ?? 0;
    const discB = b.discNumber ?? 0;
    if (discA !== discB) return discA - discB;
    return (a.trackNumber ?? 0) - (b.trackNumber ?? 0);
  });
  result.tracksByAlbum = { ...result.tracksByAlbum, [albumId]: allTracks };

  // Remove the subfolder from album and update track count
  const updatedAlbum = {
    ...album,
    subfolders: (album.subfolders ?? []).filter((sf) => sf.id !== subfolderId),
    trackCount: allTracks.length,
  };
  result.albumsByArtist = {
    ...result.albumsByArtist,
    [artistId]: (result.albumsByArtist[artistId] ?? []).map((a) =>
      a.id === albumId ? updatedAlbum : a,
    ),
  };

  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

/**
 * Remove a subfolder from state without adding its files (i.e. it was deleted).
 */
export function removeSubfolderFromState(albumId: string, subfolderId: string) {
  if (!state.result) return;

  const result = { ...state.result };

  let album: Album | null = null;
  let artistId: string | null = null;
  for (const [aid, albums] of Object.entries(result.albumsByArtist)) {
    const found = albums.find((a) => a.id === albumId);
    if (found) {
      album = found;
      artistId = aid;
      break;
    }
  }
  if (!album || !artistId) return;

  const updatedAlbum = {
    ...album,
    subfolders: (album.subfolders ?? []).filter((sf) => sf.id !== subfolderId),
  };
  result.albumsByArtist = {
    ...result.albumsByArtist,
    [artistId]: (result.albumsByArtist[artistId] ?? []).map((a) =>
      a.id === albumId ? updatedAlbum : a,
    ),
  };

  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

/**
 * Update a file's name in the cached state after it was renamed on Drive.
 * Re-parses the track info from the new filename and re-validates.
 */
export function renameFileInState(fileId: string, newName: string) {
  if (!state.result) return;

  const result = { ...state.result };

  // Update in tracksByAlbum
  let found = false;
  for (const albumId of Object.keys(result.tracksByAlbum)) {
    const tracks = result.tracksByAlbum[albumId];
    if (!tracks) continue;
    const idx = tracks.findIndex((t) => t.file.id === fileId);
    if (idx === -1) continue;

    const oldTrack = tracks[idx]!;
    const parsed = parseTrackFilename(newName);
    const updatedTrack: Track = {
      ...oldTrack,
      file: { ...oldTrack.file, name: newName },
      trackNumber: parsed.trackNumber,
      discNumber: parsed.discNumber,
      trackName: parsed.title,
    };
    const newTracks = [...tracks];
    newTracks[idx] = updatedTrack;
    result.tracksByAlbum = { ...result.tracksByAlbum, [albumId]: newTracks };
    found = true;
    break;
  }
  if (!found) return;

  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

/**
 * Remove a track from state after it was trashed on Drive.
 * Updates the album's track count and re-validates.
 */
export function removeTrackFromState(fileId: string) {
  if (!state.result) return;

  const result = { ...state.result };

  let found = false;
  for (const albumId of Object.keys(result.tracksByAlbum)) {
    const tracks = result.tracksByAlbum[albumId];
    if (!tracks) continue;
    const idx = tracks.findIndex((t) => t.file.id === fileId);
    if (idx === -1) continue;

    const newTracks = tracks.filter((t) => t.file.id !== fileId);
    result.tracksByAlbum = { ...result.tracksByAlbum, [albumId]: newTracks };

    // Update album track count
    for (const artistId of Object.keys(result.albumsByArtist)) {
      const albums = result.albumsByArtist[artistId];
      if (!albums) continue;
      const aIdx = albums.findIndex((a) => a.id === albumId);
      if (aIdx === -1) continue;
      const updatedAlbum = { ...albums[aIdx]!, trackCount: newTracks.length };
      const newAlbums = [...albums];
      newAlbums[aIdx] = updatedAlbum;
      result.albumsByArtist = { ...result.albumsByArtist, [artistId]: newAlbums };
      break;
    }

    found = true;
    break;
  }
  if (!found) return;

  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

/**
 * Set the cover art file ID for an album after uploading cover art.
 * Re-validates the library.
 */
export function setCoverInState(albumId: string, coverFileId: string) {
  if (!state.result) return;

  const result = { ...state.result };

  let found = false;
  for (const artistId of Object.keys(result.albumsByArtist)) {
    const albums = result.albumsByArtist[artistId];
    if (!albums) continue;
    const idx = albums.findIndex((a) => a.id === albumId);
    if (idx === -1) continue;

    const updatedAlbum = { ...albums[idx]!, coverFileId };
    const newAlbums = [...albums];
    newAlbums[idx] = updatedAlbum;
    result.albumsByArtist = { ...result.albumsByArtist, [artistId]: newAlbums };
    found = true;
    break;
  }
  if (!found) return;

  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

/**
 * Update an album folder's name in the cached state after it was renamed on Drive.
 * Re-parses the album name/year from the new folder name and re-validates.
 */
export function renameAlbumFolderInState(albumId: string, newFolderName: string) {
  if (!state.result) return;

  const result = { ...state.result };

  let found = false;
  for (const artistId of Object.keys(result.albumsByArtist)) {
    const albums = result.albumsByArtist[artistId];
    if (!albums) continue;
    const idx = albums.findIndex((a) => a.id === albumId);
    if (idx === -1) continue;

    const oldAlbum = albums[idx]!;
    const parsed = parseAlbumFolderName(newFolderName);
    const updatedAlbum: Album = {
      ...oldAlbum,
      folderName: newFolderName,
      name: parsed.name,
      year: parsed.year,
    };
    const newAlbums = [...albums];
    newAlbums[idx] = updatedAlbum;
    result.albumsByArtist = { ...result.albumsByArtist, [artistId]: newAlbums };
    found = true;
    break;
  }
  if (!found) return;

  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

/**
 * Update an artist folder's name in the cached state after it was renamed on Drive.
 * Re-validates the library.
 */
export function renameArtistFolderInState(artistId: string, newFolderName: string) {
  if (!state.result) return;

  const result = { ...state.result };

  const idx = result.artists.findIndex((a) => a.id === artistId);
  if (idx === -1) return;

  const oldArtist = result.artists[idx]!;
  const updatedArtist = { ...oldArtist, name: newFolderName, folderName: newFolderName };
  const newArtists = [...result.artists];
  newArtists[idx] = updatedArtist;
  result.artists = newArtists;

  // Also update artistName on all albums for this artist
  const albums = result.albumsByArtist[artistId];
  if (albums) {
    result.albumsByArtist = {
      ...result.albumsByArtist,
      [artistId]: albums.map((a) => ({ ...a, artistName: newFolderName })),
    };
  }

  const validation = validateLibrary(result);
  const scannedAt = state.scannedAt ?? Date.now();

  state = { ...state, result, validation };
  saveToDB({ result, validation, scannedAt, changePageToken: state.changePageToken ?? undefined });
  emit();
}

export function resetScan() {
  state = { status: "idle", progress: null, result: null, validation: null, error: null, scannedAt: null, changePageToken: null };
  clearDB();
  emit();
}
