import { useState, useCallback, useEffect, useMemo, useRef, useSyncExternalStore } from "react";
import { isAuthenticated } from "@/lib/google-auth";
import { fetchPlaybackHistory, type PlayEvent } from "@/lib/playback-history";
import { getScanState, subscribeScanState } from "@/lib/scan-store";
import {
  getHistoryState,
  subscribeHistoryState,
  setHistoryLoading,
  setHistoryEvents,
  setHistoryError,
} from "@/lib/history-store";
import { DriveImage } from "@/lib/drive-image";

type ViewMode = "recent" | "all";

export function HistoryPage() {
  const authed = isAuthenticated();
  const historyState = useSyncExternalStore(subscribeHistoryState, getHistoryState);
  const [viewMode, setViewMode] = useState<ViewMode>("recent");
  const [search, setSearch] = useState("");
  const [selectedAlbum, setSelectedAlbum] = useState<RecentAlbum | null>(null);
  const scanState = useSyncExternalStore(subscribeScanState, getScanState);
  const didAutoLoad = useRef(false);

  const coverByAlbumId = useMemo(() => {
    const map = new Map<string, string>();
    const albumsByArtist = scanState.result?.albumsByArtist;
    if (!albumsByArtist) return map;
    for (const albums of Object.values(albumsByArtist)) {
      for (const album of albums) {
        if (album.coverFileId) map.set(album.id, album.coverFileId);
      }
    }
    return map;
  }, [scanState.result]);

  const loadHistory = useCallback(async () => {
    setHistoryLoading();
    try {
      const history = await fetchPlaybackHistory();
      setHistoryEvents(history);
    } catch (err) {
      setHistoryError(err instanceof Error ? err.message : String(err));
    }
  }, []);

  // Auto-load history on mount when authenticated
  useEffect(() => {
    if (didAutoLoad.current) return;
    if (!authed) return;

    const s = getHistoryState();
    if (s.status === "idle") {
      didAutoLoad.current = true;
      loadHistory();
    } else if (s.status === "done") {
      // Have cached data — refresh in background
      didAutoLoad.current = true;
      fetchPlaybackHistory()
        .then((events) => setHistoryEvents(events))
        .catch(() => {}); // silently fail background refresh
    }
  }, [authed, loadHistory]);

  if (!authed) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">History</h1>
        <p className="text-zinc-400">
          Connect your Google Drive in Settings to view playback history.
        </p>
      </div>
    );
  }

  if (historyState.status === "idle" || (historyState.status === "loading" && historyState.events.length === 0)) {
    return (
      <div className="mt-24 text-center space-y-4">
        <div className="h-8 w-8 mx-auto border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
        <div className="text-lg font-medium">Loading history...</div>
      </div>
    );
  }

  if (historyState.status === "error" && historyState.events.length === 0) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">History</h1>
        <div className="rounded-lg border border-red-900/50 bg-red-950/30 p-4 space-y-2">
          <div className="text-red-400 font-medium">Failed to load history</div>
          <p className="text-sm text-zinc-400">{historyState.error}</p>
        </div>
        <button
          onClick={loadHistory}
          className="px-4 py-2 rounded-md bg-zinc-800 text-sm hover:bg-zinc-700 transition-colors"
        >
          Retry
        </button>
      </div>
    );
  }

  // Done (or loading with cached data) — show results
  const events = historyState.events;
  const playEvents = events.filter((e): e is PlayEvent => e.type === "play");

  const filteredEvents = playEvents.filter((e) => {
    if (!search) return true;
    const q = search.toLowerCase();
    return (
      e.trackName.toLowerCase().includes(q) ||
      e.albumName.toLowerCase().includes(q) ||
      e.artistName.toLowerCase().includes(q)
    );
  });

  // Group by date for the "all" view
  const grouped = groupByDate(filteredEvents);

  // Recent: unique albums, most recent first
  const recentAlbums = getRecentAlbums(filteredEvents);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">History</h1>
          <p className="mt-1 text-sm text-zinc-400">
            {playEvents.length} play{playEvents.length !== 1 ? "s" : ""} recorded
            {historyState.fetchedAt && (
              <span className="ml-2 text-zinc-500">
                &middot; Updated {formatTimeAgo(historyState.fetchedAt)}
              </span>
            )}
          </p>
        </div>
        <button
          onClick={loadHistory}
          disabled={historyState.status === "loading"}
          className="px-4 py-2 rounded-md bg-zinc-800 text-sm font-medium hover:bg-zinc-700 disabled:opacity-50 transition-colors"
        >
          {historyState.status === "loading" ? "Refreshing..." : "Refresh"}
        </button>
      </div>

      {playEvents.length === 0 ? (
        <div className="rounded-lg border border-zinc-800 bg-zinc-900/50 p-6 text-center">
          <div className="text-zinc-400 text-lg font-medium">No playback history</div>
          <p className="text-sm text-zinc-500 mt-1">
            Play some music in the CloudAmp Android app to see your history here.
          </p>
        </div>
      ) : (
        <>
          {/* View mode tabs + search */}
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex rounded-md border border-zinc-700 overflow-hidden">
              <button
                onClick={() => setViewMode("recent")}
                className={`px-3 py-1.5 text-sm font-medium transition-colors ${
                  viewMode === "recent"
                    ? "bg-zinc-700 text-white"
                    : "bg-zinc-900 text-zinc-400 hover:text-white"
                }`}
              >
                Recent Albums
              </button>
              <button
                onClick={() => setViewMode("all")}
                className={`px-3 py-1.5 text-sm font-medium transition-colors ${
                  viewMode === "all"
                    ? "bg-zinc-700 text-white"
                    : "bg-zinc-900 text-zinc-400 hover:text-white"
                }`}
              >
                All Plays
              </button>
            </div>
            <input
              type="text"
              placeholder="Search..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="rounded-md bg-zinc-900 border border-zinc-700 px-3 py-1.5 text-sm text-zinc-100 placeholder-zinc-600 focus:outline-none focus:ring-2 focus:ring-blue-500 w-64"
            />
          </div>

          {viewMode === "recent" ? (
            <RecentAlbumsView
              albums={recentAlbums}
              coverByAlbumId={coverByAlbumId}
              onSelect={setSelectedAlbum}
            />
          ) : (
            <AllPlaysView grouped={grouped} coverByAlbumId={coverByAlbumId} />
          )}
        </>
      )}

      {selectedAlbum && (
        <AlbumPlaysModal
          album={selectedAlbum}
          plays={playEvents.filter(
            (e) => (e.albumId || `${e.artistName}/${e.albumName}`) ===
              (selectedAlbum.albumId || `${selectedAlbum.artistName}/${selectedAlbum.albumName}`),
          )}
          coverFileId={coverByAlbumId.get(selectedAlbum.albumId) ?? null}
          onClose={() => setSelectedAlbum(null)}
        />
      )}
    </div>
  );
}

interface RecentAlbum {
  albumId: string;
  albumName: string;
  artistName: string;
  lastPlayed: string;
  playCount: number;
}

function getRecentAlbums(events: PlayEvent[]): RecentAlbum[] {
  const map = new Map<string, RecentAlbum>();
  for (const e of events) {
    const key = e.albumId || `${e.artistName}/${e.albumName}`;
    const existing = map.get(key);
    if (existing) {
      existing.playCount++;
    } else {
      map.set(key, {
        albumId: e.albumId,
        albumName: e.albumName,
        artistName: e.artistName,
        lastPlayed: e.playedAt,
        playCount: 1,
      });
    }
  }
  return Array.from(map.values());
}

function groupByDate(events: PlayEvent[]): [string, PlayEvent[]][] {
  const map = new Map<string, PlayEvent[]>();
  for (const e of events) {
    const date = formatDate(e.playedAt);
    const group = map.get(date);
    if (group) {
      group.push(e);
    } else {
      map.set(date, [e]);
    }
  }
  return Array.from(map.entries());
}

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const eventDate = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    const diffDays = Math.floor((today.getTime() - eventDate.getTime()) / (1000 * 60 * 60 * 24));

    if (diffDays === 0) return "Today";
    if (diffDays === 1) return "Yesterday";
    if (diffDays < 7) return d.toLocaleDateString(undefined, { weekday: "long" });
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
  } catch {
    return "Unknown date";
  }
}

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
  } catch {
    return "";
  }
}

function formatRelativeDate(iso: string): string {
  try {
    const d = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return "just now";
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHours = Math.floor(diffMin / 60);
    if (diffHours < 24) return `${diffHours}h ago`;
    const diffDays = Math.floor(diffHours / 24);
    if (diffDays < 7) return `${diffDays}d ago`;
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  } catch {
    return "";
  }
}

function formatTimeAgo(timestamp: number): string {
  const seconds = Math.floor((Date.now() - timestamp) / 1000);
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function RecentAlbumsView({
  albums,
  coverByAlbumId,
  onSelect,
}: {
  albums: RecentAlbum[];
  coverByAlbumId: Map<string, string>;
  onSelect: (album: RecentAlbum) => void;
}) {
  if (albums.length === 0) {
    return <p className="text-sm text-zinc-500">No matching albums found.</p>;
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
      {albums.map((album) => {
        const coverFileId = coverByAlbumId.get(album.albumId);
        return (
          <button
            key={album.albumId || `${album.artistName}/${album.albumName}`}
            type="button"
            onClick={() => onSelect(album)}
            className="flex items-center gap-3 rounded-lg border border-zinc-800 bg-zinc-900/50 px-4 py-3 text-left hover:bg-zinc-800/60 hover:border-zinc-700 focus:outline-none focus:ring-2 focus:ring-blue-500 transition-colors"
          >
            {coverFileId ? (
              <DriveImage
                fileId={coverFileId}
                alt={`${album.albumName} cover`}
                className="w-10 h-10 object-cover rounded border border-zinc-700 shrink-0"
              />
            ) : (
              <div className="w-10 h-10 rounded border border-zinc-700 bg-zinc-800 shrink-0 flex items-center justify-center">
                <span className="text-zinc-600 text-lg">&#9835;</span>
              </div>
            )}
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium text-zinc-200 truncate">{album.albumName || "Unknown Album"}</div>
              <div className="text-xs text-zinc-500 truncate">{album.artistName || "Unknown Artist"}</div>
              <div className="text-xs text-zinc-600 mt-0.5">
                {album.playCount} play{album.playCount !== 1 ? "s" : ""}
                {" \u00b7 "}
                {formatRelativeDate(album.lastPlayed)}
              </div>
            </div>
          </button>
        );
      })}
    </div>
  );
}

function AlbumPlaysModal({
  album,
  plays,
  coverFileId,
  onClose,
}: {
  album: RecentAlbum;
  plays: PlayEvent[];
  coverFileId: string | null;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [onClose]);

  // Sort plays most-recent first
  const sortedPlays = [...plays].sort((a, b) =>
    a.playedAt < b.playedAt ? 1 : a.playedAt > b.playedAt ? -1 : 0,
  );

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg max-h-[85vh] flex flex-col rounded-lg border border-zinc-700 bg-zinc-900 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start gap-4 p-5 border-b border-zinc-800">
          {coverFileId ? (
            <DriveImage
              fileId={coverFileId}
              alt={`${album.albumName} cover`}
              className="w-16 h-16 object-cover rounded border border-zinc-700 shrink-0"
            />
          ) : (
            <div className="w-16 h-16 rounded border border-zinc-700 bg-zinc-800 shrink-0 flex items-center justify-center">
              <span className="text-zinc-600 text-2xl">&#9835;</span>
            </div>
          )}
          <div className="min-w-0 flex-1">
            <div className="text-base font-semibold text-zinc-100 truncate">
              {album.albumName || "Unknown Album"}
            </div>
            <div className="text-sm text-zinc-400 truncate">
              {album.artistName || "Unknown Artist"}
            </div>
            <div className="text-xs text-zinc-500 mt-1">
              {album.playCount} play{album.playCount !== 1 ? "s" : ""}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-zinc-500 hover:text-zinc-200 text-xl leading-none px-2"
            aria-label="Close"
          >
            &times;
          </button>
        </div>

        <div className="overflow-y-auto p-5">
          {sortedPlays.length === 0 ? (
            <p className="text-sm text-zinc-500">No plays found.</p>
          ) : (
            <div className="space-y-1">
              {sortedPlays.map((play, i) => (
                <div
                  key={`${play.trackId}-${play.playedAt}-${i}`}
                  className="flex items-center gap-3 rounded-md border border-zinc-800/70 bg-zinc-900/50 px-3 py-2"
                >
                  <div className="min-w-0 flex-1">
                    <div className="text-sm text-zinc-200 truncate">{play.trackName}</div>
                    <div className="text-xs text-zinc-500">
                      {formatDate(play.playedAt)}{" \u00b7 "}{formatTime(play.playedAt)}
                    </div>
                  </div>
                  <div className="text-xs text-zinc-600 shrink-0">
                    {formatRelativeDate(play.playedAt)}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function AllPlaysView({
  grouped,
  coverByAlbumId,
}: {
  grouped: [string, PlayEvent[]][];
  coverByAlbumId: Map<string, string>;
}) {
  if (grouped.length === 0) {
    return <p className="text-sm text-zinc-500">No matching plays found.</p>;
  }

  return (
    <div className="space-y-6">
      {grouped.map(([date, plays]) => (
        <div key={date}>
          <div className="text-sm font-medium text-zinc-400 mb-2">{date}</div>
          <div className="space-y-1">
            {plays.map((play, i) => {
              const coverFileId = coverByAlbumId.get(play.albumId);
              return (
                <div
                  key={`${play.trackId}-${play.playedAt}-${i}`}
                  className="flex items-center gap-3 rounded-lg border border-zinc-800/50 bg-zinc-900/30 px-4 py-2.5"
                >
                  <div className="text-xs text-zinc-600 w-16 shrink-0">
                    {formatTime(play.playedAt)}
                  </div>
                  {coverFileId ? (
                    <DriveImage
                      fileId={coverFileId}
                      alt={`${play.albumName} cover`}
                      className="w-8 h-8 object-cover rounded border border-zinc-700 shrink-0"
                    />
                  ) : (
                    <div className="w-8 h-8 rounded border border-zinc-700 bg-zinc-800 shrink-0 flex items-center justify-center">
                      <span className="text-zinc-600 text-sm">&#9835;</span>
                    </div>
                  )}
                  <div className="min-w-0 flex-1">
                    <div className="text-sm text-zinc-200 truncate">{play.trackName}</div>
                    <div className="text-xs text-zinc-500 truncate">
                      {play.artistName}{play.albumName ? ` \u2014 ${play.albumName}` : ""}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}
