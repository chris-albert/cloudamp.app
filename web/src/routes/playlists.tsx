import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { isAuthenticated } from "@/lib/google-auth";
import { getScanState, subscribeScanState } from "@/lib/scan-store";
import { DriveImage } from "@/lib/drive-image";
import { formatFileSize } from "@/lib/drive-api";
import { parseTrackFilename } from "@/lib/filename-parser";
import type { Track } from "@/lib/library-scanner";
import { playQueue, type PlayerTrack } from "@/lib/player-store";
import {
  getPlaylistsState,
  subscribePlaylistsState,
  ensurePlaylistsLoaded,
  refreshPlaylists,
  createPlaylist,
  renamePlaylist,
  deletePlaylist,
  removeTrackFromPlaylist,
  setPlaylistOrder,
} from "@/lib/playlists-store";
import { playlistTrackToDriveFile, type Playlist, type PlaylistTrack } from "@/lib/playlists-core";

export function PlaylistsPage() {
  const authed = isAuthenticated();
  const playlistsState = useSyncExternalStore(subscribePlaylistsState, getPlaylistsState);
  const { playlistId } = useSearch({ from: "/app/playlists" });
  const didRefresh = useRef(false);

  useEffect(() => {
    if (!authed) return;
    ensurePlaylistsLoaded();
    if (didRefresh.current) return;
    didRefresh.current = true;
    // Pick up edits made on other devices since the last visit
    if (getPlaylistsState().status === "done") void refreshPlaylists();
  }, [authed]);

  if (!authed) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Playlists</h1>
        <p className="text-zinc-400">
          Connect your Google Drive in{" "}
          <Link to="/app/settings" className="underline hover:text-white">
            Settings
          </Link>{" "}
          to manage playlists.
        </p>
      </div>
    );
  }

  if (playlistsState.status === "idle" || (playlistsState.status === "loading" && playlistsState.playlists.length === 0)) {
    return (
      <div className="mt-24 text-center space-y-4">
        <div className="h-8 w-8 mx-auto border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
        <div className="text-lg font-medium">Loading playlists...</div>
      </div>
    );
  }

  if (playlistsState.status === "error") {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Playlists</h1>
        <div className="rounded-lg border border-red-900/50 bg-red-950/30 p-4 space-y-2">
          <div className="text-red-400 font-medium">Failed to load playlists</div>
          <p className="text-sm text-zinc-400">{playlistsState.error}</p>
        </div>
        <button
          onClick={() => void refreshPlaylists()}
          className="px-4 py-2 rounded-md bg-zinc-800 text-sm hover:bg-zinc-700 transition-colors"
        >
          Retry
        </button>
      </div>
    );
  }

  const selected = playlistId ? playlistsState.playlists.find((p) => p.id === playlistId) ?? null : null;

  return (
    <div className="space-y-4">
      {playlistsState.error && (
        <div className="rounded-md border border-amber-900/50 bg-amber-950/20 px-3 py-2 text-xs text-amber-400">
          Couldn't sync with Drive: {playlistsState.error}. Changes are kept locally and retried.
        </div>
      )}
      {selected ? (
        <PlaylistDetail key={selected.id} playlist={selected} />
      ) : (
        <PlaylistList playlists={playlistsState.playlists} missingId={playlistId} />
      )}
    </div>
  );
}

// ── List ──────────────────────────────────────────────────────────────

function PlaylistList({ playlists, missingId }: { playlists: Playlist[]; missingId?: string }) {
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");

  function openPlaylist(id: string) {
    navigate({ to: "/app/playlists", search: { playlistId: id } });
  }

  function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    const name = newName.trim();
    if (!name) return;
    const playlist = createPlaylist(name);
    setNewName("");
    setCreating(false);
    openPlaylist(playlist.id);
  }

  return (
    <>
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">Playlists</h1>
        {creating ? (
          <form onSubmit={handleCreate} className="flex items-center gap-2">
            <input
              autoFocus
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  setCreating(false);
                  setNewName("");
                }
              }}
              placeholder="Playlist name"
              className="rounded bg-zinc-900 border border-zinc-600 px-2 py-1 text-sm text-zinc-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <button
              type="submit"
              disabled={!newName.trim()}
              className="px-3 py-1 rounded bg-green-700 text-sm text-white hover:bg-green-600 disabled:opacity-50 transition-colors"
            >
              Create
            </button>
            <button
              type="button"
              onClick={() => {
                setCreating(false);
                setNewName("");
              }}
              className="px-3 py-1 rounded bg-zinc-800 text-sm text-zinc-300 hover:bg-zinc-700 transition-colors"
            >
              Cancel
            </button>
          </form>
        ) : (
          <button
            onClick={() => setCreating(true)}
            className="px-3 py-1.5 rounded-full bg-white text-black text-xs font-semibold hover:bg-zinc-200 transition-colors"
          >
            + New playlist
          </button>
        )}
      </div>

      {missingId && (
        <p className="text-sm text-zinc-500">That playlist no longer exists.</p>
      )}

      {playlists.length === 0 ? (
        <p className="text-sm text-zinc-500">
          No playlists yet. Create one here, or use the{" "}
          <span className="text-zinc-300">add to playlist</span> button next to any track in the Library.
        </p>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {playlists.map((p) => (
            <PlaylistCard key={p.id} playlist={p} onOpen={() => openPlaylist(p.id)} />
          ))}
        </div>
      )}
    </>
  );
}

function PlaylistCard({ playlist, onOpen }: { playlist: Playlist; onOpen: () => void }) {
  const [confirmDelete, setConfirmDelete] = useState(false);
  const count = playlist.tracks.length;

  return (
    <div className="flex items-center gap-3 rounded-lg border border-zinc-800 bg-zinc-900/50 px-4 py-3 hover:bg-zinc-800/60 hover:border-zinc-700 transition-colors">
      <button type="button" onClick={onOpen} className="min-w-0 flex-1 text-left focus:outline-none">
        <div className="text-sm font-medium text-zinc-200 truncate">{playlist.name}</div>
        <div className="text-xs text-zinc-500">
          {count} track{count !== 1 ? "s" : ""}
          {playlist.updatedAt && <span className="text-zinc-600"> · updated {formatDate(playlist.updatedAt)}</span>}
        </div>
      </button>
      {confirmDelete ? (
        <div className="flex gap-1 items-center shrink-0">
          <button
            onClick={() => void deletePlaylist(playlist.id)}
            className="px-2 py-0.5 rounded bg-red-700 text-[10px] text-white hover:bg-red-600 transition-colors"
          >
            Delete
          </button>
          <button
            onClick={() => setConfirmDelete(false)}
            className="px-2 py-0.5 rounded text-[10px] text-zinc-500 hover:text-zinc-300 transition-colors"
          >
            Cancel
          </button>
        </div>
      ) : (
        <button
          onClick={() => setConfirmDelete(true)}
          title="Delete playlist"
          className="p-1 text-zinc-600 hover:text-red-400 transition-colors shrink-0"
        >
          <TrashIcon />
        </button>
      )}
    </div>
  );
}

// ── Detail ────────────────────────────────────────────────────────────

interface ResolvedTrack {
  entry: PlaylistTrack;
  player: PlayerTrack;
  /** false when the file isn't in the scanned library (e.g. a raw Drive file) */
  inLibrary: boolean;
}

function PlaylistDetail({ playlist }: { playlist: Playlist }) {
  const scanState = useSyncExternalStore(subscribeScanState, getScanState);
  const navigate = useNavigate();
  const [editingName, setEditingName] = useState(false);
  const [nameDraft, setNameDraft] = useState(playlist.name);

  // fileId → library track (+ cover) so playlist entries get real metadata
  const libraryIndex = useMemo(() => {
    const map = new Map<string, PlayerTrack>();
    const result = scanState.result;
    if (!result) return map;
    for (const albums of Object.values(result.albumsByArtist)) {
      for (const album of albums) {
        for (const track of result.tracksByAlbum[album.id] ?? []) {
          map.set(track.file.id, { track, albumCoverFileId: album.coverFileId });
        }
      }
    }
    return map;
  }, [scanState.result]);

  const resolved: ResolvedTrack[] = useMemo(
    () =>
      playlist.tracks.map((entry) => {
        const fromLibrary = libraryIndex.get(entry.fileId);
        if (fromLibrary) return { entry, player: fromLibrary, inLibrary: true };
        return { entry, player: { track: trackFromEntry(entry), albumCoverFileId: null }, inLibrary: false };
      }),
    [playlist.tracks, libraryIndex],
  );

  function play(startIndex = 0) {
    if (resolved.length === 0) return;
    playQueue(resolved.map((r) => r.player), null, startIndex);
  }

  function shuffle() {
    if (resolved.length === 0) return;
    const queue = resolved.map((r) => r.player);
    for (let i = queue.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [queue[i], queue[j]] = [queue[j]!, queue[i]!];
    }
    playQueue(queue, null, 0);
  }

  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= playlist.tracks.length) return;
    const ids = playlist.tracks.map((t) => t.fileId);
    [ids[index], ids[target]] = [ids[target]!, ids[index]!];
    void setPlaylistOrder(playlist.id, ids);
  }

  function saveName() {
    const name = nameDraft.trim();
    setEditingName(false);
    if (!name || name === playlist.name) {
      setNameDraft(playlist.name);
      return;
    }
    void renamePlaylist(playlist.id, name);
  }

  const count = playlist.tracks.length;

  return (
    <div className="space-y-4">
      <button
        onClick={() => navigate({ to: "/app/playlists", search: { playlistId: undefined } })}
        className="text-sm text-zinc-400 hover:text-white transition-colors"
      >
        ← All playlists
      </button>

      <div className="flex flex-wrap items-center gap-3">
        {editingName ? (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              saveName();
            }}
            className="flex items-center gap-2"
          >
            <input
              autoFocus
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  setNameDraft(playlist.name);
                  setEditingName(false);
                }
              }}
              className="rounded bg-zinc-900 border border-zinc-600 px-2 py-1 text-xl font-bold text-zinc-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <button type="submit" className="px-3 py-1 rounded bg-green-700 text-sm text-white hover:bg-green-600 transition-colors">
              Save
            </button>
          </form>
        ) : (
          <button
            type="button"
            onClick={() => {
              setNameDraft(playlist.name);
              setEditingName(true);
            }}
            title="Rename playlist"
            className="text-2xl font-bold text-left hover:text-zinc-300 transition-colors"
          >
            {playlist.name}
          </button>
        )}
        <span className="text-xs text-zinc-500">
          {count} track{count !== 1 ? "s" : ""}
        </span>
        {count > 0 && (
          <div className="flex items-center gap-2 ml-auto">
            <button
              onClick={() => play(0)}
              className="px-4 py-1.5 rounded-full bg-white text-black text-xs font-semibold hover:bg-zinc-200 transition-colors inline-flex items-center gap-1.5"
            >
              <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor"><path d="M4 2.5v11l9-5.5L4 2.5z" /></svg>
              Play
            </button>
            <button
              onClick={shuffle}
              className="px-4 py-1.5 rounded-full bg-zinc-800 text-zinc-200 text-xs font-semibold hover:bg-zinc-700 transition-colors"
            >
              Shuffle
            </button>
          </div>
        )}
      </div>

      {count === 0 ? (
        <p className="text-sm text-zinc-500">
          This playlist is empty. Use the add to playlist button next to any track in the{" "}
          <Link to="/app/library" search={{ artistId: undefined, albumId: undefined }} className="underline hover:text-white">
            Library
          </Link>
          .
        </p>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-zinc-500 border-b border-zinc-800">
              <th className="pb-2 w-12">#</th>
              <th className="pb-2">Title</th>
              <th className="pb-2 hidden sm:table-cell">Album</th>
              <th className="pb-2 w-20 text-right hidden sm:table-cell">Size</th>
              <th className="pb-2 w-28 text-right">&nbsp;</th>
            </tr>
          </thead>
          <tbody>
            {resolved.map((r, index) => (
              <PlaylistTrackRow
                key={r.entry.fileId}
                item={r}
                index={index}
                isFirst={index === 0}
                isLast={index === resolved.length - 1}
                onPlay={() => play(index)}
                onMoveUp={() => move(index, -1)}
                onMoveDown={() => move(index, 1)}
                onRemove={() => void removeTrackFromPlaylist(playlist.id, r.entry.fileId)}
              />
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function PlaylistTrackRow({
  item,
  index,
  isFirst,
  isLast,
  onPlay,
  onMoveUp,
  onMoveDown,
  onRemove,
}: {
  item: ResolvedTrack;
  index: number;
  isFirst: boolean;
  isLast: boolean;
  onPlay: () => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onRemove: () => void;
}) {
  const { track } = item.player;
  return (
    <tr className="border-b border-zinc-800/50 hover:bg-zinc-900/50 group">
      <td className="py-2 text-zinc-500 font-mono text-xs relative">
        <span className="group-hover:invisible">{String(index + 1).padStart(2, "0")}</span>
        <button
          onClick={onPlay}
          className="absolute inset-0 flex items-center justify-center invisible group-hover:visible text-white hover:text-green-400 transition-colors"
          title="Play from here"
        >
          <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor"><path d="M4 2.5v11l9-5.5L4 2.5z" /></svg>
        </button>
      </td>
      <td className="py-2">
        <div className="flex items-center gap-3 min-w-0">
          {item.player.albumCoverFileId ? (
            <DriveImage
              fileId={item.player.albumCoverFileId}
              alt=""
              className="w-8 h-8 object-cover rounded border border-zinc-700 shrink-0"
            />
          ) : (
            <div className="w-8 h-8 rounded border border-zinc-700 bg-zinc-800 shrink-0 flex items-center justify-center">
              <span className="text-zinc-600 text-xs">♪</span>
            </div>
          )}
          <div className="min-w-0">
            <div className="text-zinc-200 truncate">{track.trackName}</div>
            <div className="text-xs text-zinc-500 truncate">
              {track.artistName}
              {!item.inLibrary && <span className="text-zinc-600"> · not in library</span>}
            </div>
          </div>
        </div>
      </td>
      <td className="py-2 text-zinc-500 text-xs truncate hidden sm:table-cell">{track.albumName}</td>
      <td className="py-2 text-right text-zinc-500 text-xs hidden sm:table-cell">{formatFileSize(track.file.size)}</td>
      <td className="py-2 text-right">
        <div className="inline-flex items-center gap-0.5 opacity-40 group-hover:opacity-100 transition-opacity">
          <button
            onClick={onMoveUp}
            disabled={isFirst}
            title="Move up"
            className="p-1 text-zinc-400 hover:text-white disabled:opacity-30 disabled:hover:text-zinc-400 transition-colors"
          >
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M8 13V3M3.5 7.5L8 3l4.5 4.5" /></svg>
          </button>
          <button
            onClick={onMoveDown}
            disabled={isLast}
            title="Move down"
            className="p-1 text-zinc-400 hover:text-white disabled:opacity-30 disabled:hover:text-zinc-400 transition-colors"
          >
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M8 3v10M3.5 8.5L8 13l4.5-4.5" /></svg>
          </button>
          <button onClick={onRemove} title="Remove from playlist" className="p-1 text-zinc-400 hover:text-red-400 transition-colors">
            <TrashIcon />
          </button>
        </div>
      </td>
    </tr>
  );
}

// ── Helpers ───────────────────────────────────────────────────────────

/** Best-effort Track for a playlist entry that isn't in the scanned library. */
function trackFromEntry(entry: PlaylistTrack): Track {
  const parsed = parseTrackFilename(entry.name);
  return {
    file: playlistTrackToDriveFile(entry),
    artistId: "",
    artistName: parsed.artist ?? "Google Drive",
    albumId: entry.parentId ?? "",
    albumName: parsed.album ?? "",
    trackNumber: parsed.trackNumber,
    discNumber: parsed.discNumber,
    trackName: parsed.title,
    year: null,
  };
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  if (isNaN(date.getTime())) return "";
  return date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function TrashIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2.5 4h11M6 4V2.5h4V4M4 4l.7 9.5h6.6L12 4M6.5 7v4M9.5 7v4" />
    </svg>
  );
}
