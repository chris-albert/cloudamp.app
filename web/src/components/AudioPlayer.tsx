import { useSyncExternalStore, useCallback, useRef, useState } from "react";
import {
  getPlayerState,
  subscribePlayerState,
  getCurrentTrack,
  togglePlayPause,
  playNext,
  playPrevious,
  seekTo,
  setVolume,
  playTrackAtIndex,
} from "@/lib/player-store";
import { DriveImage } from "@/lib/drive-image";

export function AudioPlayer() {
  const state = useSyncExternalStore(subscribePlayerState, getPlayerState);
  const currentTrack = getCurrentTrack();

  if (!currentTrack) return null;

  return (
    <div className="fixed bottom-0 left-0 right-0 z-50 border-t border-zinc-800/60 bg-zinc-950/95 backdrop-blur-md">
      <ProgressBar
        currentTime={state.currentTime}
        duration={state.duration}
        onSeek={seekTo}
      />
      <div className="max-w-7xl mx-auto px-4 py-2 flex items-center gap-4">
        {/* Track info */}
        <div className="flex items-center gap-3 min-w-0 flex-1">
          {currentTrack.albumCoverFileId ? (
            <DriveImage
              fileId={currentTrack.albumCoverFileId}
              alt=""
              className="w-10 h-10 rounded border border-zinc-700 shrink-0 object-cover"
            />
          ) : (
            <div className="w-10 h-10 rounded border border-zinc-700 bg-zinc-800 shrink-0 flex items-center justify-center">
              <span className="text-zinc-600 text-sm">♪</span>
            </div>
          )}
          <div className="min-w-0">
            <div className="text-sm font-medium text-zinc-200 truncate">
              {currentTrack.track.trackName}
            </div>
            <div className="text-xs text-zinc-500 truncate">
              {currentTrack.track.artistName}
              {state.album && <span> — {state.album.name}</span>}
            </div>
          </div>
        </div>

        {/* Controls */}
        <div className="flex items-center gap-2">
          <button
            onClick={playPrevious}
            className="p-1.5 text-zinc-400 hover:text-white transition-colors"
            title="Previous"
          >
            <PreviousIcon />
          </button>
          <button
            onClick={togglePlayPause}
            className="p-2 rounded-full bg-white text-black hover:bg-zinc-200 transition-colors"
            title={state.isPlaying ? "Pause" : "Play"}
          >
            {state.isLoading ? (
              <LoadingIcon />
            ) : state.isPlaying ? (
              <PauseIcon />
            ) : (
              <PlayIcon />
            )}
          </button>
          <button
            onClick={playNext}
            className="p-1.5 text-zinc-400 hover:text-white transition-colors"
            title="Next"
          >
            <NextIcon />
          </button>
        </div>

        {/* Time + Volume */}
        <div className="flex items-center gap-3 flex-1 justify-end">
          <span className="text-xs text-zinc-500 tabular-nums">
            {formatTime(state.currentTime)} / {formatTime(state.duration)}
          </span>
          <VolumeControl volume={state.volume} onChange={setVolume} />
          <QueueButton
            queue={state.queue.map((q) => q.track)}
            currentIndex={state.currentIndex}
            onSelect={playTrackAtIndex}
          />
        </div>
      </div>
    </div>
  );
}

function ProgressBar({
  currentTime,
  duration,
  onSeek,
}: {
  currentTime: number;
  duration: number;
  onSeek: (time: number) => void;
}) {
  const barRef = useRef<HTMLDivElement>(null);
  const [isDragging, setIsDragging] = useState(false);

  const fraction = duration > 0 ? currentTime / duration : 0;

  const handleSeek = useCallback(
    (e: React.MouseEvent | MouseEvent) => {
      if (!barRef.current || duration <= 0) return;
      const rect = barRef.current.getBoundingClientRect();
      const x = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      onSeek(x * duration);
    },
    [duration, onSeek],
  );

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      setIsDragging(true);
      handleSeek(e);

      const onMove = (me: MouseEvent) => handleSeek(me);
      const onUp = () => {
        setIsDragging(false);
        window.removeEventListener("mousemove", onMove);
        window.removeEventListener("mouseup", onUp);
      };
      window.addEventListener("mousemove", onMove);
      window.addEventListener("mouseup", onUp);
    },
    [handleSeek],
  );

  return (
    <div
      ref={barRef}
      onMouseDown={handleMouseDown}
      className={`w-full cursor-pointer group ${isDragging ? "h-1.5" : "h-1 hover:h-1.5"} transition-all`}
    >
      <div className="relative w-full h-full bg-zinc-800">
        <div
          className="absolute left-0 top-0 h-full bg-white/80 group-hover:bg-white transition-colors"
          style={{ width: `${fraction * 100}%` }}
        />
      </div>
    </div>
  );
}

function VolumeControl({ volume, onChange }: { volume: number; onChange: (v: number) => void }) {
  return (
    <div className="flex items-center gap-1.5 group">
      <button
        onClick={() => onChange(volume > 0 ? 0 : 1)}
        className="text-zinc-400 hover:text-white transition-colors"
        title={volume > 0 ? "Mute" : "Unmute"}
      >
        {volume === 0 ? <VolumeMuteIcon /> : volume < 0.5 ? <VolumeLowIcon /> : <VolumeHighIcon />}
      </button>
      <input
        type="range"
        min={0}
        max={1}
        step={0.01}
        value={volume}
        onChange={(e) => onChange(parseFloat(e.target.value))}
        className="w-16 h-1 appearance-none bg-zinc-700 rounded-full cursor-pointer [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-2.5 [&::-webkit-slider-thumb]:h-2.5 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-white [&::-webkit-slider-thumb]:opacity-0 [&::-webkit-slider-thumb]:group-hover:opacity-100 [&::-webkit-slider-thumb]:transition-opacity"
      />
    </div>
  );
}

function QueueButton({
  queue,
  currentIndex,
  onSelect,
}: {
  queue: { trackName: string; trackNumber: number | null }[];
  currentIndex: number;
  onSelect: (index: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);

  if (queue.length <= 1) return null;

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(!open)}
        className={`p-1 rounded text-xs transition-colors ${open ? "text-white bg-zinc-800" : "text-zinc-500 hover:text-zinc-300"}`}
        title="Queue"
      >
        <QueueIcon />
      </button>
      {open && (
        <>
          <div
            className="fixed inset-0 z-40"
            onClick={() => setOpen(false)}
          />
          <div
            ref={panelRef}
            className="absolute bottom-full right-0 mb-2 w-72 max-h-64 overflow-y-auto rounded-lg border border-zinc-700 bg-zinc-900 shadow-xl z-50"
          >
            <div className="px-3 py-2 border-b border-zinc-800 text-xs font-medium text-zinc-400">
              Queue ({queue.length} tracks)
            </div>
            {queue.map((track, i) => (
              <button
                key={i}
                onClick={() => {
                  onSelect(i);
                  setOpen(false);
                }}
                className={`w-full text-left px-3 py-1.5 text-xs flex items-center gap-2 hover:bg-zinc-800 transition-colors ${
                  i === currentIndex ? "bg-zinc-800/60 text-white" : "text-zinc-400"
                }`}
              >
                <span className="w-5 text-right text-zinc-600 font-mono shrink-0">
                  {i === currentIndex ? (
                    <span className="text-white">▶</span>
                  ) : (
                    track.trackNumber ?? i + 1
                  )}
                </span>
                <span className="truncate">{track.trackName}</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function formatTime(seconds: number): string {
  if (!seconds || !isFinite(seconds)) return "0:00";
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${String(secs).padStart(2, "0")}`;
}

// ── Icons ──────────────────────────────────────────────────────────────

function PlayIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
      <path d="M4 2.5v11l9-5.5L4 2.5z" />
    </svg>
  );
}

function PauseIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
      <rect x="3" y="2" width="4" height="12" rx="0.5" />
      <rect x="9" y="2" width="4" height="12" rx="0.5" />
    </svg>
  );
}

function LoadingIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" className="animate-spin">
      <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="2" strokeDasharray="28 10" />
    </svg>
  );
}

function PreviousIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
      <rect x="2" y="3" width="2" height="10" rx="0.5" />
      <path d="M14 3v10L5 8l9-5z" />
    </svg>
  );
}

function NextIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
      <path d="M2 3v10l9-5L2 3z" />
      <rect x="12" y="3" width="2" height="10" rx="0.5" />
    </svg>
  );
}

function VolumeMuteIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M11 5L6 9H2v6h4l5 4V5z" />
      <line x1="23" y1="9" x2="17" y2="15" />
      <line x1="17" y1="9" x2="23" y2="15" />
    </svg>
  );
}

function VolumeLowIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M11 5L6 9H2v6h4l5 4V5z" />
      <path d="M15.54 8.46a5 5 0 010 7.07" />
    </svg>
  );
}

function VolumeHighIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M11 5L6 9H2v6h4l5 4V5z" />
      <path d="M19.07 4.93a10 10 0 010 14.14M15.54 8.46a5 5 0 010 7.07" />
    </svg>
  );
}

function QueueIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
      <rect x="2" y="3" width="12" height="1.5" rx="0.5" />
      <rect x="2" y="7" width="12" height="1.5" rx="0.5" />
      <rect x="2" y="11" width="8" height="1.5" rx="0.5" />
    </svg>
  );
}
