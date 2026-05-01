/**
 * Reactive store for audio player state.
 * Uses the same useSyncExternalStore pattern as scan-store.
 */

import type { Track, Album } from "./library-scanner";
import { fetchAudioBlobUrl } from "./drive-api";

export interface PlayerTrack {
  track: Track;
  albumCoverFileId: string | null;
}

export interface PlayerState {
  /** Current queue of tracks */
  queue: PlayerTrack[];
  /** Index of the currently playing track in the queue */
  currentIndex: number;
  /** Album being played */
  album: Album | null;
  /** Whether audio is currently playing */
  isPlaying: boolean;
  /** Current playback position in seconds */
  currentTime: number;
  /** Duration of current track in seconds */
  duration: number;
  /** Volume 0-1 */
  volume: number;
  /** Whether the current track's audio is loading */
  isLoading: boolean;
  /** Blob URL of the currently loaded audio */
  currentBlobUrl: string | null;
}

let state: PlayerState = {
  queue: [],
  currentIndex: -1,
  album: null,
  isPlaying: false,
  currentTime: 0,
  duration: 0,
  volume: parseFloat(localStorage.getItem("cloudamp_volume") ?? "1"),
  isLoading: false,
  currentBlobUrl: null,
};

const listeners = new Set<() => void>();

function emit() {
  for (const fn of listeners) fn();
}

export function getPlayerState(): PlayerState {
  return state;
}

export function subscribePlayerState(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// ── Audio element ──────────────────────────────────────────────────────

let audio: HTMLAudioElement | null = null;

function getAudio(): HTMLAudioElement {
  if (!audio) {
    audio = new Audio();
    audio.addEventListener("timeupdate", () => {
      state = { ...state, currentTime: audio!.currentTime };
      emit();
    });
    audio.addEventListener("durationchange", () => {
      state = { ...state, duration: audio!.duration || 0 };
      emit();
    });
    audio.addEventListener("ended", () => {
      playNext();
    });
    audio.addEventListener("playing", () => {
      state = { ...state, isPlaying: true, isLoading: false };
      emit();
    });
    audio.addEventListener("pause", () => {
      state = { ...state, isPlaying: false };
      emit();
    });
    audio.addEventListener("waiting", () => {
      state = { ...state, isLoading: true };
      emit();
    });
    audio.addEventListener("canplay", () => {
      state = { ...state, isLoading: false };
      emit();
    });
  }
  return audio;
}

// ── Web Audio API (for visualizations) ────────────────────────────────

let audioContext: AudioContext | null = null;
let analyserNode: AnalyserNode | null = null;
let sourceNode: MediaElementAudioSourceNode | null = null;

/**
 * Get the AnalyserNode for reading frequency/waveform data.
 * Lazily creates the AudioContext and connects the audio element.
 * Returns null if no audio element exists yet.
 */
export function getAnalyserNode(): AnalyserNode | null {
  if (!audio) return null;
  if (!audioContext) {
    audioContext = new AudioContext();
    analyserNode = audioContext.createAnalyser();
    analyserNode.fftSize = 2048;
    analyserNode.smoothingTimeConstant = 0.8;
    sourceNode = audioContext.createMediaElementSource(audio);
    sourceNode.connect(analyserNode);
    analyserNode.connect(audioContext.destination);
  }
  if (audioContext.state === "suspended") {
    audioContext.resume();
  }
  return analyserNode;
}

// ── Cache for preloaded blob URLs ──────────────────────────────────────

const blobUrlCache = new Map<string, string>();
const preloadingSet = new Set<string>();

async function getBlobUrl(fileId: string): Promise<string> {
  const cached = blobUrlCache.get(fileId);
  if (cached) return cached;
  const blobUrl = await fetchAudioBlobUrl(fileId);
  blobUrlCache.set(fileId, blobUrl);
  return blobUrl;
}

function preloadTrack(fileId: string) {
  if (blobUrlCache.has(fileId) || preloadingSet.has(fileId)) return;
  preloadingSet.add(fileId);
  fetchAudioBlobUrl(fileId)
    .then((url) => {
      blobUrlCache.set(fileId, url);
    })
    .finally(() => {
      preloadingSet.delete(fileId);
    });
}

// ── Playback control ──────────────────────────────────────────────────

async function loadAndPlay(index: number) {
  if (index < 0 || index >= state.queue.length) return;

  const playerTrack = state.queue[index]!;
  const fileId = playerTrack.track.file.id;
  const el = getAudio();

  // Revoke old blob URL if it's not cached
  if (state.currentBlobUrl && !blobUrlCache.has(fileId)) {
    // Don't revoke - the cache manages lifetime
  }

  state = { ...state, currentIndex: index, isLoading: true, currentTime: 0, duration: 0, currentBlobUrl: null };
  emit();

  try {
    const blobUrl = await getBlobUrl(fileId);
    // Check we haven't navigated away during the fetch
    if (state.currentIndex !== index) return;

    el.src = blobUrl;
    el.volume = state.volume;
    state = { ...state, currentBlobUrl: blobUrl };
    emit();
    await el.play();

    // Preload next track
    if (index + 1 < state.queue.length) {
      preloadTrack(state.queue[index + 1]!.track.file.id);
    }
  } catch (err) {
    console.error("Playback error:", err);
    state = { ...state, isLoading: false, isPlaying: false };
    emit();
  }
}

/**
 * Play an album's tracks starting from a given index (default 0).
 */
export function playAlbum(album: Album, tracks: Track[], startIndex = 0) {
  const queue: PlayerTrack[] = tracks.map((track) => ({
    track,
    albumCoverFileId: album.coverFileId,
  }));

  state = { ...state, queue, album, currentIndex: -1 };
  emit();

  loadAndPlay(startIndex);
}

/**
 * Toggle play/pause.
 */
export function togglePlayPause() {
  const el = getAudio();
  if (!el.src) return;
  if (el.paused) {
    el.play();
  } else {
    el.pause();
  }
}

/**
 * Play next track in queue.
 */
export function playNext() {
  if (state.currentIndex + 1 < state.queue.length) {
    loadAndPlay(state.currentIndex + 1);
  } else {
    // End of queue
    state = { ...state, isPlaying: false };
    emit();
  }
}

/**
 * Play previous track in queue, or restart current if past 3 seconds.
 */
export function playPrevious() {
  const el = getAudio();
  if (el.currentTime > 3 && state.currentIndex >= 0) {
    el.currentTime = 0;
    return;
  }
  if (state.currentIndex > 0) {
    loadAndPlay(state.currentIndex - 1);
  } else if (state.currentIndex === 0) {
    el.currentTime = 0;
  }
}

/**
 * Seek to a position in seconds.
 */
export function seekTo(time: number) {
  const el = getAudio();
  if (!el.src) return;
  el.currentTime = time;
}

/**
 * Set volume (0-1).
 */
export function setVolume(v: number) {
  const clamped = Math.max(0, Math.min(1, v));
  state = { ...state, volume: clamped };
  localStorage.setItem("cloudamp_volume", String(clamped));
  const el = getAudio();
  el.volume = clamped;
  emit();
}

/**
 * Play a specific track in the current queue by its index.
 */
export function playTrackAtIndex(index: number) {
  if (index >= 0 && index < state.queue.length) {
    loadAndPlay(index);
  }
}

/**
 * Get the currently playing track info, or null if nothing is playing.
 */
export function getCurrentTrack(): PlayerTrack | null {
  if (state.currentIndex < 0 || state.currentIndex >= state.queue.length) return null;
  return state.queue[state.currentIndex] ?? null;
}
