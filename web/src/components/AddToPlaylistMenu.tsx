import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { DriveFile } from "@/lib/drive-api";
import {
  getPlaylistsState,
  subscribePlaylistsState,
  ensurePlaylistsLoaded,
  createPlaylist,
  addTracksToPlaylist,
} from "@/lib/playlists-store";

/**
 * A small "+" button that opens a menu of playlists (plus "New playlist…")
 * and adds the given files to the chosen one. Files already in the playlist
 * are skipped, and the result is shown briefly in place of the button.
 */
export function AddToPlaylistMenu({
  files,
  label = "Add to playlist",
  className = "",
}: {
  files: DriveFile[];
  label?: string;
  className?: string;
}) {
  const playlistsState = useSyncExternalStore(subscribePlaylistsState, getPlaylistsState);
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const [feedback, setFeedback] = useState<string | null>(null);
  const containerRef = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (open) ensurePlaylistsLoaded();
  }, [open]);

  // Close on outside click / Escape
  useEffect(() => {
    if (!open) return;
    function onPointerDown(e: MouseEvent) {
      if (!containerRef.current?.contains(e.target as Node)) close();
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") close();
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (!feedback) return;
    const t = setTimeout(() => setFeedback(null), 1800);
    return () => clearTimeout(t);
  }, [feedback]);

  function close() {
    setOpen(false);
    setCreating(false);
    setNewName("");
  }

  function showResult(added: number, playlistName: string) {
    setFeedback(
      added === 0 ? `Already in ${playlistName}` : added === 1 ? `Added to ${playlistName}` : `Added ${added} to ${playlistName}`,
    );
  }

  function addToExisting(playlistId: string, playlistName: string) {
    const added = addTracksToPlaylist(playlistId, files);
    close();
    showResult(added, playlistName);
  }

  function createAndAdd() {
    const name = newName.trim();
    if (!name) return;
    const playlist = createPlaylist(name, files);
    close();
    showResult(files.length, playlist.name);
  }

  if (feedback) {
    return <span className={`text-[11px] text-green-400 whitespace-nowrap ${className}`}>{feedback}</span>;
  }

  return (
    <span ref={containerRef} className={`relative inline-flex ${className}`} onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        title={label}
        aria-haspopup="menu"
        aria-expanded={open}
        className="p-1 rounded text-zinc-500 hover:text-white hover:bg-zinc-800 transition-colors"
      >
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
          <path d="M2 4h8M2 8h8M2 12h5M12 9v5M9.5 11.5h5" />
        </svg>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 top-full mt-1 z-40 w-56 rounded-md border border-zinc-700 bg-zinc-900 shadow-lg shadow-black/40 py-1 text-left"
        >
          {creating ? (
            <form
              className="px-2 py-1.5 flex gap-1"
              onSubmit={(e) => {
                e.preventDefault();
                createAndAdd();
              }}
            >
              <input
                autoFocus
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="Playlist name"
                className="min-w-0 flex-1 rounded bg-zinc-950 border border-zinc-600 px-2 py-0.5 text-xs text-zinc-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <button
                type="submit"
                disabled={!newName.trim()}
                className="px-2 py-0.5 rounded bg-green-700 text-xs text-white hover:bg-green-600 disabled:opacity-50 transition-colors"
              >
                Add
              </button>
            </form>
          ) : (
            <button
              type="button"
              role="menuitem"
              onClick={() => setCreating(true)}
              className="w-full px-3 py-1.5 text-left text-xs text-zinc-200 hover:bg-zinc-800 transition-colors"
            >
              + New playlist…
            </button>
          )}

          {playlistsState.playlists.length > 0 && <div className="my-1 border-t border-zinc-800" />}

          <div className="max-h-64 overflow-y-auto">
            {playlistsState.playlists.map((p) => (
              <button
                key={p.id}
                type="button"
                role="menuitem"
                onClick={() => addToExisting(p.id, p.name)}
                className="w-full px-3 py-1.5 text-left text-xs text-zinc-300 hover:bg-zinc-800 hover:text-white transition-colors flex justify-between gap-2"
              >
                <span className="truncate">{p.name}</span>
                <span className="text-zinc-600 shrink-0">{p.tracks.length}</span>
              </button>
            ))}
          </div>

          {playlistsState.status === "loading" && playlistsState.playlists.length === 0 && (
            <div className="px-3 py-1.5 text-xs text-zinc-500">Loading…</div>
          )}
        </div>
      )}
    </span>
  );
}
