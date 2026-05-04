import { useSyncExternalStore, useState, useEffect, useCallback, useRef } from "react";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { getScanState, subscribeScanState, setScanProgress, setScanError, removeArtistFromState, removeAlbumFromState, flattenSubfolderInState, removeSubfolderFromState, renameFileInState, removeTrackFromState, renameAlbumFolderInState, renameArtistFolderInState, setCoverInState } from "@/lib/scan-store";
import { formatFileSize, trashFile, moveFile, renameFile, uploadFile } from "@/lib/drive-api";
import { isAuthenticated, getRootFolderId } from "@/lib/google-auth";
import { syncLibrary, doFullScan } from "@/lib/incremental-sync";
import type { Artist, Album, Track, AlbumSubfolder } from "@/lib/library-scanner";
import { parseTrackFilename, buildCanonicalFilename, isCanonicalAlbumFolder } from "@/lib/filename-parser";
import type { LibraryIssue } from "@/lib/library-validator";
import { searchAlbumYear, extractYear, searchCoverArt, fetchImageBlob, type MusicBrainzRelease, type CoverArtResult } from "@/lib/musicbrainz";
import { DriveImage, ArtistImage } from "@/lib/drive-image";
import { playAlbum } from "@/lib/player-store";

export function LibraryPage() {
  const scanState = useSyncExternalStore(subscribeScanState, getScanState);
  const didAutoScan = useRef(false);

  // Auto-trigger scan/sync when library page loads with no data
  useEffect(() => {
    if (didAutoScan.current) return;
    if (!isAuthenticated()) return;
    const rootId = getRootFolderId();
    if (!rootId) return;

    const s = getScanState();
    if (s.status === "idle") {
      didAutoScan.current = true;
      doFullScan(rootId, setScanProgress).catch((err) => {
        setScanError(err instanceof Error ? err.message : String(err));
      });
    } else if (s.status === "done" && s.changePageToken) {
      didAutoScan.current = true;
      syncLibrary().catch((err) => {
        setScanError(err instanceof Error ? err.message : String(err));
      });
    }
  }, [scanState.status]);

  if (!isAuthenticated()) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Library</h1>
        <p className="text-zinc-400">
          Connect your Google Drive in{" "}
          <Link to="/settings" className="underline hover:text-white">
            Settings
          </Link>{" "}
          to browse your music library.
        </p>
      </div>
    );
  }

  if (scanState.status === "scanning") {
    return (
      <div className="mt-24 text-center space-y-4">
        <div className="h-8 w-8 mx-auto border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
        <div className="text-lg font-medium">{scanState.progress?.stage ?? "Scanning library..."}</div>
        {scanState.progress?.detail && (
          <div className="text-sm text-zinc-500">{scanState.progress.detail}</div>
        )}
      </div>
    );
  }

  if (scanState.status === "error") {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Library</h1>
        <div className="rounded-lg border border-red-900/50 bg-red-950/30 p-4 space-y-2">
          <div className="text-red-400 font-medium">Failed to load library</div>
          <p className="text-sm text-zinc-400">{scanState.error}</p>
        </div>
        <button
          onClick={() => {
            const rootId = getRootFolderId();
            if (rootId) {
              doFullScan(rootId, setScanProgress).catch((err) => {
                setScanError(err instanceof Error ? err.message : String(err));
              });
            }
          }}
          className="px-4 py-2 rounded-md bg-zinc-800 text-sm hover:bg-zinc-700 transition-colors"
        >
          Retry
        </button>
      </div>
    );
  }

  if (scanState.status === "idle" || !scanState.result) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Library</h1>
        {!getRootFolderId() ? (
          <p className="text-zinc-400">
            Choose a music folder in{" "}
            <Link to="/settings" className="underline hover:text-white">
              Settings
            </Link>{" "}
            to get started.
          </p>
        ) : (
          <div className="mt-12 text-center space-y-4">
            <div className="h-8 w-8 mx-auto border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
            <div className="text-lg font-medium">Loading library...</div>
          </div>
        )}
      </div>
    );
  }

  return <LibraryBrowser />;
}

function LibraryBrowser() {
  const scanState = useSyncExternalStore(subscribeScanState, getScanState);
  const result = scanState.result!;
  const validation = scanState.validation;
  const navigate = useNavigate();

  const searchParams = useSearch({ from: "/library" });
  const initialArtistId = searchParams.artistId;
  const initialAlbumId = searchParams.albumId;

  const [selectedArtist, setSelectedArtist] = useState<Artist | null>(null);
  const [selectedAlbum, setSelectedAlbum] = useState<Album | null>(null);
  const [search, setSearch] = useState("");
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [showDeleteArtistConfirm, setShowDeleteArtistConfirm] = useState(false);
  const [isDeletingArtist, setIsDeletingArtist] = useState(false);
  const [deleteArtistError, setDeleteArtistError] = useState<string | null>(null);

  // Resolve initial selection from search params
  useEffect(() => {
    if (initialArtistId && !selectedArtist) {
      const artist = result.artists.find((a) => a.id === initialArtistId);
      if (artist) {
        setSelectedArtist(artist);
        if (initialAlbumId) {
          const albums = result.albumsByArtist[artist.id] ?? [];
          const album = albums.find((a) => a.id === initialAlbumId);
          if (album) setSelectedAlbum(album);
        }
      }
    }
  }, [initialArtistId, initialAlbumId, result, selectedArtist]);

  // Update URL search params when selection changes (without re-triggering the effect)
  function selectArtist(artist: Artist | null) {
    setSelectedArtist(artist);
    setSelectedAlbum(null);
    navigate({
      to: "/library",
      search: artist ? { artistId: artist.id, albumId: undefined } : { artistId: undefined, albumId: undefined },
      replace: true,
    });
  }

  function selectAlbum(album: Album | null) {
    setSelectedAlbum(album);
    if (album && selectedArtist) {
      navigate({
        to: "/library",
        search: { artistId: selectedArtist.id, albumId: album.id },
        replace: true,
      });
    } else if (selectedArtist) {
      navigate({
        to: "/library",
        search: { artistId: selectedArtist.id, albumId: undefined },
        replace: true,
      });
    }
  }

  const handleDeleteAlbum = useCallback(async () => {
    if (!selectedAlbum || !selectedArtist) return;
    setIsDeleting(true);
    setDeleteError(null);
    try {
      await trashFile(selectedAlbum.id);
      removeAlbumFromState(selectedAlbum.id, selectedArtist.id);
      setShowDeleteConfirm(false);
      setSelectedAlbum(null);
      navigate({
        to: "/library",
        search: { artistId: selectedArtist.id, albumId: undefined },
        replace: true,
      });
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : String(err));
    } finally {
      setIsDeleting(false);
    }
  }, [selectedAlbum, selectedArtist, navigate]);

  const handleDeleteArtist = useCallback(async () => {
    if (!selectedArtist) return;
    setIsDeletingArtist(true);
    setDeleteArtistError(null);
    try {
      await trashFile(selectedArtist.id);
      removeArtistFromState(selectedArtist.id);
      setShowDeleteArtistConfirm(false);
      setSelectedArtist(null);
      setSelectedAlbum(null);
      navigate({
        to: "/library",
        search: { artistId: undefined, albumId: undefined },
        replace: true,
      });
    } catch (err) {
      setDeleteArtistError(err instanceof Error ? err.message : String(err));
    } finally {
      setIsDeletingArtist(false);
    }
  }, [selectedArtist, navigate]);

  // Build a set of IDs with issues for highlighting
  const issueArtistIds = new Set<string>();
  const issueAlbumIds = new Set<string>();
  if (validation) {
    for (const issue of validation.issues) {
      if (issue.artistId) issueArtistIds.add(issue.artistId);
      if (issue.albumId) issueAlbumIds.add(issue.albumId);
    }
  }

  const filteredArtists = result.artists.filter((a) =>
    a.name.toLowerCase().includes(search.toLowerCase()),
  );

  const albums = selectedArtist ? result.albumsByArtist[selectedArtist.id] ?? [] : [];

  // Keep selectedAlbum in sync with state (e.g. after flatten updates subfolders/trackCount)
  useEffect(() => {
    if (selectedAlbum && selectedArtist) {
      const fresh = (result.albumsByArtist[selectedArtist.id] ?? []).find((a) => a.id === selectedAlbum.id);
      if (fresh && fresh !== selectedAlbum) setSelectedAlbum(fresh);
    }
  }, [result, selectedArtist, selectedAlbum]);

  const tracks = selectedAlbum ? result.tracksByAlbum[selectedAlbum.id] ?? [] : [];

  // Issues for the selected album
  const albumIssues = validation?.issues.filter(
    (i) => selectedAlbum && i.albumId === selectedAlbum.id,
  ) ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Library Browser</h1>
        <div className="text-sm text-zinc-500">
          {result.artists.length} artists
        </div>
      </div>

      {/* Breadcrumb */}
      <div className="flex items-center gap-2 text-sm">
        <button
          onClick={() => { selectArtist(null); }}
          className="text-zinc-400 hover:text-white"
        >
          All Artists
        </button>
        {selectedArtist && (
          <>
            <span className="text-zinc-600">/</span>
            <button
              onClick={() => selectAlbum(null)}
              className="text-zinc-400 hover:text-white"
            >
              {selectedArtist.name}
            </button>
          </>
        )}
        {selectedAlbum && (
          <>
            <span className="text-zinc-600">/</span>
            <span className="text-zinc-200">{selectedAlbum.name}</span>
          </>
        )}
      </div>

      {/* Search */}
      {!selectedArtist && (
        <input
          type="text"
          placeholder="Search artists..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="rounded-md bg-zinc-900 border border-zinc-700 px-3 py-1.5 text-sm text-zinc-100 placeholder-zinc-600 focus:outline-none focus:ring-2 focus:ring-blue-500 w-full max-w-sm"
        />
      )}

      {/* Artist list */}
      {!selectedArtist && (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          {filteredArtists.map((artist) => {
            const artistAlbums = result.albumsByArtist[artist.id] ?? [];
            const fallbackFileId = artistAlbums.find((a) => a.coverFileId)?.coverFileId ?? null;
            return (
              <button
                key={artist.id}
                onClick={() => selectArtist(artist)}
                className={`flex items-center gap-3 rounded-lg border px-4 py-3 text-left transition-colors hover:bg-zinc-800 ${
                  issueArtistIds.has(artist.id)
                    ? "border-amber-900/50 bg-amber-950/20"
                    : "border-zinc-800 bg-zinc-900/50"
                }`}
              >
                <ArtistImage
                  imageFileId={artist.imageFileId}
                  fallbackFileId={fallbackFileId}
                  name={artist.name}
                  className="w-10 h-10 shrink-0 rounded border border-zinc-700"
                />
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium text-zinc-200 truncate">{artist.name}</div>
                  <div className="text-xs text-zinc-500">{artist.albumCount} album{artist.albumCount !== 1 ? "s" : ""}</div>
                </div>
                {issueArtistIds.has(artist.id) && (
                  <div className="h-2 w-2 rounded-full bg-amber-500 shrink-0" />
                )}
              </button>
            );
          })}
        </div>
      )}

      {/* Album list */}
      {selectedArtist && !selectedAlbum && (
        <div className="space-y-4">
          <ArtistFolderName artist={selectedArtist} onRenamed={(updated) => setSelectedArtist(updated)} />

          <div className="space-y-2">
          {albums.map((album) => (
            <button
              key={album.id}
              onClick={() => selectAlbum(album)}
              className={`group flex items-center gap-3 w-full rounded-lg border px-4 py-3 text-left transition-colors hover:bg-zinc-800 ${
                issueAlbumIds.has(album.id)
                  ? "border-amber-900/50 bg-amber-950/20"
                  : "border-zinc-800 bg-zinc-900/50"
              }`}
            >
              {album.coverFileId ? (
                <DriveImage
                  fileId={album.coverFileId}
                  alt=""
                  className="w-12 h-12 object-cover rounded border border-zinc-700 shrink-0"
                />
              ) : (
                <div className="w-12 h-12 rounded border border-zinc-700 bg-zinc-800 shrink-0 flex items-center justify-center">
                  <span className="text-zinc-600 text-lg">♪</span>
                </div>
              )}
              <div className="flex-1 min-w-0">
                <div className="text-sm font-medium text-zinc-200">
                  {album.year && <span className="text-zinc-500 mr-2">{album.year}</span>}
                  {album.name}
                </div>
                <div className="text-xs text-zinc-500 mt-0.5">
                  {album.trackCount} track{album.trackCount !== 1 ? "s" : ""}
                  {!album.coverFileId && <span className="ml-2 text-amber-500">No cover</span>}
                </div>
                <div className="text-xs text-zinc-600 mt-0.5 truncate">
                  Folder: {album.folderName}
                </div>
              </div>
              {(result.tracksByAlbum[album.id] ?? []).length > 0 && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    playAlbum(album, result.tracksByAlbum[album.id] ?? []);
                  }}
                  className="shrink-0 w-8 h-8 rounded-full bg-white text-black flex items-center justify-center hover:bg-zinc-200 transition-colors opacity-0 group-hover:opacity-100"
                  title="Play album"
                >
                  <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor"><path d="M4 2.5v11l9-5.5L4 2.5z" /></svg>
                </button>
              )}
              {issueAlbumIds.has(album.id) && (
                <div className="h-2 w-2 rounded-full bg-amber-500 shrink-0" />
              )}
            </button>
          ))}
          {albums.length === 0 && (
            <p className="text-sm text-zinc-500">No albums found for this artist.</p>
          )}
          </div>

          {/* Delete artist */}
          <div className="border-t border-zinc-800 pt-4">
            {!showDeleteArtistConfirm ? (
              <button
                onClick={() => { setShowDeleteArtistConfirm(true); setDeleteArtistError(null); }}
                className="px-4 py-2 rounded-md bg-zinc-800 text-sm text-red-400 hover:bg-red-950/50 hover:border-red-900/50 border border-zinc-700 transition-colors"
              >
                Delete Artist from Drive
              </button>
            ) : (
              <div className="rounded-lg border border-red-900/50 bg-red-950/20 p-4 space-y-3">
                <div className="text-sm font-medium text-red-400">
                  Delete "{selectedArtist.name}"?
                </div>
                <p className="text-xs text-zinc-400">
                  This will move the artist folder and all {albums.length} album{albums.length !== 1 ? "s" : ""} to
                  the Google Drive trash. You can recover them from trash within 30 days.
                </p>
                {deleteArtistError && (
                  <div className="text-xs text-red-400">{deleteArtistError}</div>
                )}
                <div className="flex gap-2">
                  <button
                    onClick={handleDeleteArtist}
                    disabled={isDeletingArtist}
                    className="px-4 py-2 rounded-md bg-red-600 text-sm font-medium text-white hover:bg-red-500 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    {isDeletingArtist ? "Deleting..." : "Delete Artist"}
                  </button>
                  <button
                    onClick={() => { setShowDeleteArtistConfirm(false); setDeleteArtistError(null); }}
                    disabled={isDeletingArtist}
                    className="px-4 py-2 rounded-md bg-zinc-800 text-sm text-zinc-300 hover:bg-zinc-700 transition-colors disabled:opacity-40"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Track list + album issues */}
      {selectedAlbum && (
        <div className="space-y-4">
          <AlbumFolderName album={selectedAlbum} />

          {selectedAlbum.coverFileId ? (
            <div className="flex items-start gap-4">
              <DriveImage
                fileId={selectedAlbum.coverFileId}
                alt={`${selectedAlbum.name} cover`}
                className="w-32 h-32 object-cover rounded-lg border border-zinc-700 shrink-0"
              />
              <div className="text-sm text-zinc-400 space-y-1 min-w-0">
                <div className="text-lg font-medium text-zinc-200">{selectedAlbum.name}</div>
                <div className="text-zinc-500">{selectedAlbum.artistName}</div>
                {selectedAlbum.year && <div className="text-zinc-500">{selectedAlbum.year}</div>}
                <div className="text-zinc-600 text-xs">{tracks.length} track{tracks.length !== 1 ? "s" : ""}</div>
                {tracks.length > 0 && (
                  <button
                    onClick={() => playAlbum(selectedAlbum, tracks)}
                    className="mt-2 px-4 py-1.5 rounded-full bg-white text-black text-xs font-semibold hover:bg-zinc-200 transition-colors inline-flex items-center gap-1.5"
                  >
                    <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor"><path d="M4 2.5v11l9-5.5L4 2.5z" /></svg>
                    Play Album
                  </button>
                )}
              </div>
            </div>
          ) : (
            <div className="space-y-2">
              <AlbumCoverArt album={selectedAlbum} />
              {tracks.length > 0 && (
                <button
                  onClick={() => playAlbum(selectedAlbum, tracks)}
                  className="px-4 py-1.5 rounded-full bg-white text-black text-xs font-semibold hover:bg-zinc-200 transition-colors inline-flex items-center gap-1.5"
                >
                  <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor"><path d="M4 2.5v11l9-5.5L4 2.5z" /></svg>
                  Play Album
                </button>
              )}
            </div>
          )}

          {albumIssues.length > 0 && (
            <div className="rounded-lg border border-amber-900/50 bg-amber-950/20 p-4 space-y-2">
              <div className="text-sm font-medium text-amber-400">
                Issues ({albumIssues.length})
              </div>
              {albumIssues.map((issue) => (
                <AlbumIssueRow key={issue.id} issue={issue} />
              ))}
            </div>
          )}

          {/* Subfolder flatten panels */}
          {(selectedAlbum.subfolders ?? []).length > 0 && (
            <div className="space-y-2">
              {(selectedAlbum.subfolders ?? []).map((sf) => (
                <SubfolderPanel
                  key={sf.id}
                  subfolder={sf}
                  albumId={selectedAlbum.id}
                  artistName={selectedAlbum.artistName}
                  albumName={selectedAlbum.name}
                  existingTracks={tracks}
                />
              ))}
            </div>
          )}

          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-zinc-500 border-b border-zinc-800">
                <th className="pb-2 w-12">#</th>
                <th className="pb-2">Title</th>
                <th className="pb-2 w-20 text-right">Size</th>
                <th className="pb-2 w-16 text-right">Type</th>
              </tr>
            </thead>
            <tbody>
              {tracks.map((track, trackIndex) => (
                <TrackRow
                  key={track.file.id}
                  track={track}
                  artistName={selectedAlbum.artistName}
                  albumName={selectedAlbum.name}
                  onPlay={() => playAlbum(selectedAlbum, tracks, trackIndex)}
                />
              ))}
            </tbody>
          </table>
          {tracks.length === 0 && (
            <p className="text-sm text-zinc-500">No tracks found in this album.</p>
          )}

          {/* Delete album */}
          <div className="border-t border-zinc-800 pt-4">
            {!showDeleteConfirm ? (
              <button
                onClick={() => { setShowDeleteConfirm(true); setDeleteError(null); }}
                className="px-4 py-2 rounded-md bg-zinc-800 text-sm text-red-400 hover:bg-red-950/50 hover:border-red-900/50 border border-zinc-700 transition-colors"
              >
                Delete Album from Drive
              </button>
            ) : (
              <div className="rounded-lg border border-red-900/50 bg-red-950/20 p-4 space-y-3">
                <div className="text-sm font-medium text-red-400">
                  Delete "{selectedAlbum.name}"?
                </div>
                <p className="text-xs text-zinc-400">
                  This will move the album folder and all {tracks.length} track{tracks.length !== 1 ? "s" : ""} to
                  the Google Drive trash. You can recover them from trash within 30 days.
                </p>
                {deleteError && (
                  <div className="text-xs text-red-400">{deleteError}</div>
                )}
                <div className="flex gap-2">
                  <button
                    onClick={handleDeleteAlbum}
                    disabled={isDeleting}
                    className="px-4 py-2 rounded-md bg-red-600 text-sm font-medium text-white hover:bg-red-500 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    {isDeleting ? "Deleting..." : "Delete Album"}
                  </button>
                  <button
                    onClick={() => { setShowDeleteConfirm(false); setDeleteError(null); }}
                    disabled={isDeleting}
                    className="px-4 py-2 rounded-md bg-zinc-800 text-sm text-zinc-300 hover:bg-zinc-700 transition-colors disabled:opacity-40"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function ArtistFolderName({ artist, onRenamed }: { artist: Artist; onRenamed: (updated: Artist) => void }) {
  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function startEditing() {
    setEditValue(artist.folderName);
    setError(null);
    setEditing(true);
  }

  function cancelEditing() {
    setEditing(false);
    setError(null);
  }

  async function saveEdit() {
    const newName = editValue.trim();
    if (!newName) {
      setError("Name cannot be empty");
      return;
    }
    if (newName === artist.folderName) {
      setEditing(false);
      return;
    }

    setSaving(true);
    setError(null);
    try {
      await renameFile(artist.id, newName);
      renameArtistFolderInState(artist.id, newName);
      onRenamed({ ...artist, name: newName, folderName: newName });
      setEditing(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Rename failed");
    } finally {
      setSaving(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Enter") {
      e.preventDefault();
      saveEdit();
    } else if (e.key === "Escape") {
      cancelEditing();
    }
  }

  if (editing) {
    return (
      <div className="space-y-2">
        <div className="text-xs text-zinc-500">Artist Folder</div>
        <div className="flex items-center gap-2">
          <input
            type="text"
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            onKeyDown={handleKeyDown}
            autoFocus
            className="flex-1 rounded-md bg-zinc-900 border border-zinc-600 px-3 py-1.5 text-sm text-zinc-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <button
            onClick={saveEdit}
            disabled={saving}
            className="px-3 py-1.5 rounded-md bg-green-700 text-xs font-medium text-white hover:bg-green-600 disabled:opacity-50 transition-colors"
          >
            {saving ? "..." : "Save"}
          </button>
          <button
            onClick={cancelEditing}
            disabled={saving}
            className="px-3 py-1.5 rounded-md bg-zinc-800 text-xs text-zinc-300 hover:bg-zinc-700 disabled:opacity-50 transition-colors"
          >
            Cancel
          </button>
        </div>
        {error && <div className="text-xs text-red-400">{error}</div>}
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3">
      <div className="text-xs text-zinc-500">Folder:</div>
      <div className="text-sm font-mono text-zinc-400">{artist.folderName}</div>
      <button
        onClick={startEditing}
        className="px-2 py-0.5 rounded text-[10px] text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 transition-colors"
      >
        Rename
      </button>
    </div>
  );
}

function AlbumFolderName({ album }: { album: Album }) {
  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // MusicBrainz year lookup
  const [lookingUp, setLookingUp] = useState(false);
  const [mbResults, setMbResults] = useState<MusicBrainzRelease[] | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [applyingSuggestion, setApplyingSuggestion] = useState(false);

  const isCanonical = isCanonicalAlbumFolder(album.folderName);

  function startEditing() {
    setEditValue(album.folderName);
    setError(null);
    setEditing(true);
  }

  function cancelEditing() {
    setEditing(false);
    setError(null);
  }

  async function saveEdit() {
    const newName = editValue.trim();
    if (!newName) {
      setError("Folder name cannot be empty");
      return;
    }
    if (newName === album.folderName) {
      setEditing(false);
      return;
    }

    setSaving(true);
    setError(null);
    try {
      await renameFile(album.id, newName);
      renameAlbumFolderInState(album.id, newName);
      setEditing(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Rename failed");
    } finally {
      setSaving(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Enter") {
      e.preventDefault();
      saveEdit();
    } else if (e.key === "Escape") {
      cancelEditing();
    }
  }

  async function lookupYear() {
    setLookingUp(true);
    setLookupError(null);
    setMbResults(null);
    try {
      const results = await searchAlbumYear(album.artistName, album.name);
      if (results.length === 0) {
        setLookupError("No results found on MusicBrainz");
      } else {
        setMbResults(results);
      }
    } catch (err) {
      setLookupError(err instanceof Error ? err.message : "Lookup failed");
    } finally {
      setLookingUp(false);
    }
  }

  async function applySuggestion(year: number) {
    const newName = `${album.name} (${year})`;
    setApplyingSuggestion(true);
    setError(null);
    try {
      await renameFile(album.id, newName);
      renameAlbumFolderInState(album.id, newName);
      setMbResults(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Rename failed");
    } finally {
      setApplyingSuggestion(false);
    }
  }

  if (editing) {
    return (
      <div className="space-y-2">
        <div className="text-xs text-zinc-500">Album Folder</div>
        <div className="flex items-center gap-2">
          <input
            type="text"
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            onKeyDown={handleKeyDown}
            autoFocus
            className="flex-1 rounded-md bg-zinc-900 border border-zinc-600 px-3 py-1.5 text-sm text-zinc-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <button
            onClick={saveEdit}
            disabled={saving}
            className="px-3 py-1.5 rounded-md bg-green-700 text-xs font-medium text-white hover:bg-green-600 disabled:opacity-50 transition-colors"
          >
            {saving ? "..." : "Save"}
          </button>
          <button
            onClick={cancelEditing}
            disabled={saving}
            className="px-3 py-1.5 rounded-md bg-zinc-800 text-xs text-zinc-300 hover:bg-zinc-700 disabled:opacity-50 transition-colors"
          >
            Cancel
          </button>
        </div>
        {!isCanonicalAlbumFolder(editValue.trim()) && editValue.trim() && (
          <div className="text-xs text-amber-400">
            Not canonical format. Expected: "{album.name} ({album.year ?? "YYYY"})"
          </div>
        )}
        {error && <div className="text-xs text-red-400">{error}</div>}
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-3">
        <div className="text-xs text-zinc-500">Folder:</div>
        <div className={`text-sm font-mono ${isCanonical ? "text-zinc-400" : "text-amber-400"}`}>
          {album.folderName}
        </div>
        <button
          onClick={startEditing}
          className="px-2 py-0.5 rounded text-[10px] text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 transition-colors"
        >
          Rename
        </button>
        {album.year === null && (
          <button
            onClick={lookupYear}
            disabled={lookingUp}
            className="px-2 py-0.5 rounded text-[10px] text-blue-400 hover:text-blue-300 hover:bg-zinc-800 disabled:opacity-50 transition-colors"
          >
            {lookingUp ? "Looking up..." : "Lookup Year"}
          </button>
        )}
      </div>

      {lookupError && (
        <div className="text-xs text-red-400">{lookupError}</div>
      )}

      {error && (
        <div className="text-xs text-red-400">{error}</div>
      )}

      {mbResults && (
        <div className="rounded-lg border border-blue-900/50 bg-blue-950/20 p-3 space-y-2">
          <div className="text-xs font-medium text-blue-400">MusicBrainz Results</div>
          <div className="space-y-1">
            {mbResults.map((r) => {
              const year = r.date ? extractYear(r.date) : null;
              return (
                <div key={r.id} className="flex items-center gap-2 text-xs">
                  <span className="text-zinc-300 truncate flex-1">
                    {r.artistCredit} — {r.title}
                    {r.date && <span className="text-zinc-500"> ({r.date})</span>}
                    {r.country && <span className="text-zinc-600"> [{r.country}]</span>}
                    {r.status && <span className="text-zinc-600"> {r.status}</span>}
                  </span>
                  {year && (
                    <button
                      onClick={() => applySuggestion(year)}
                      disabled={applyingSuggestion}
                      className="shrink-0 px-2 py-0.5 rounded bg-blue-700 text-white hover:bg-blue-600 disabled:opacity-50 transition-colors"
                    >
                      {applyingSuggestion ? "..." : `Use ${year}`}
                    </button>
                  )}
                </div>
              );
            })}
          </div>
          <button
            onClick={() => setMbResults(null)}
            className="text-[10px] text-zinc-500 hover:text-zinc-300"
          >
            Dismiss
          </button>
        </div>
      )}
    </div>
  );
}

function AlbumCoverArt({ album }: { album: Album }) {
  const [searching, setSearching] = useState(false);
  const [results, setResults] = useState<CoverArtResult[] | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [showUrlInput, setShowUrlInput] = useState(false);
  const [urlValue, setUrlValue] = useState("");
  const fileInputRef = useRef<HTMLInputElement>(null);

  async function handleSearch() {
    setSearching(true);
    setSearchError(null);
    setResults(null);
    try {
      const found = await searchCoverArt(album.artistName, album.name);
      if (found.length === 0) {
        setSearchError("No cover art found on MusicBrainz");
      } else {
        setResults(found);
      }
    } catch (err) {
      setSearchError(err instanceof Error ? err.message : "Search failed");
    } finally {
      setSearching(false);
    }
  }

  async function handleUse(result: CoverArtResult) {
    setUploading(true);
    setUploadError(null);
    try {
      const blob = await fetchImageBlob(result.imageUrl);
      const ext = blob.type === "image/png" ? "png" : "jpg";
      const fileId = await uploadFile(`cover.${ext}`, album.id, blob);
      setCoverInState(album.id, fileId);
      setResults(null);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  }

  async function handleUrlUpload() {
    const url = urlValue.trim();
    if (!url) return;
    setUploading(true);
    setUploadError(null);
    try {
      const blob = await fetchImageBlob(url);
      const ext = blob.type === "image/png" ? "png" : "jpg";
      const fileId = await uploadFile(`cover.${ext}`, album.id, blob);
      setCoverInState(album.id, fileId);
      setShowUrlInput(false);
      setUrlValue("");
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  }

  async function handleFileUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    try {
      const ext = file.type === "image/png" ? "png" : "jpg";
      const fileId = await uploadFile(`cover.${ext}`, album.id, file);
      setCoverInState(album.id, fileId);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-3">
        <div className="text-xs text-amber-400">No cover art</div>
        <button
          onClick={handleSearch}
          disabled={searching}
          className="px-2 py-0.5 rounded text-[10px] text-blue-400 hover:text-blue-300 hover:bg-zinc-800 disabled:opacity-50 transition-colors"
        >
          {searching ? "Searching..." : "Find Cover Art"}
        </button>
        <button
          onClick={() => { setShowUrlInput(!showUrlInput); setUploadError(null); }}
          className="px-2 py-0.5 rounded text-[10px] text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 transition-colors"
        >
          Paste URL
        </button>
        <button
          onClick={() => fileInputRef.current?.click()}
          disabled={uploading}
          className="px-2 py-0.5 rounded text-[10px] text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800 disabled:opacity-50 transition-colors"
        >
          Upload File
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          onChange={handleFileUpload}
          className="hidden"
        />
      </div>

      {searchError && <div className="text-xs text-red-400">{searchError}</div>}
      {uploadError && <div className="text-xs text-red-400">{uploadError}</div>}

      {showUrlInput && (
        <div className="flex items-center gap-2">
          <input
            type="text"
            value={urlValue}
            onChange={(e) => setUrlValue(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") handleUrlUpload(); if (e.key === "Escape") { setShowUrlInput(false); setUrlValue(""); } }}
            placeholder="https://example.com/cover.jpg"
            autoFocus
            className="flex-1 rounded-md bg-zinc-900 border border-zinc-600 px-3 py-1.5 text-sm text-zinc-100 placeholder-zinc-600 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <button
            onClick={handleUrlUpload}
            disabled={uploading || !urlValue.trim()}
            className="px-3 py-1.5 rounded-md bg-blue-700 text-xs font-medium text-white hover:bg-blue-600 disabled:opacity-50 transition-colors"
          >
            {uploading ? "..." : "Upload"}
          </button>
          <button
            onClick={() => { setShowUrlInput(false); setUrlValue(""); }}
            disabled={uploading}
            className="px-3 py-1.5 rounded-md bg-zinc-800 text-xs text-zinc-300 hover:bg-zinc-700 disabled:opacity-50 transition-colors"
          >
            Cancel
          </button>
        </div>
      )}

      {results && (
        <div className="rounded-lg border border-blue-900/50 bg-blue-950/20 p-3 space-y-3">
          <div className="text-xs font-medium text-blue-400">Cover Art Results</div>
          <div className="flex gap-3 flex-wrap">
            {results.map((r) => (
              <div key={r.releaseId} className="space-y-1.5 w-32">
                <img
                  src={r.imageUrl}
                  alt={`${r.releaseTitle} cover`}
                  className="w-32 h-32 object-cover rounded border border-zinc-700"
                />
                <div className="text-[10px] text-zinc-400 truncate" title={`${r.artistCredit} — ${r.releaseTitle}`}>
                  {r.artistCredit} — {r.releaseTitle}
                </div>
                {r.date && <div className="text-[10px] text-zinc-500">{r.date}</div>}
                <button
                  onClick={() => handleUse(r)}
                  disabled={uploading}
                  className="w-full px-2 py-0.5 rounded bg-blue-700 text-[10px] text-white hover:bg-blue-600 disabled:opacity-50 transition-colors"
                >
                  {uploading ? "Uploading..." : "Use This"}
                </button>
              </div>
            ))}
          </div>
          <button
            onClick={() => setResults(null)}
            className="text-[10px] text-zinc-500 hover:text-zinc-300"
          >
            Dismiss
          </button>
        </div>
      )}
    </div>
  );
}

function AlbumIssueRow({ issue }: { issue: LibraryIssue }) {
  return (
    <div className="flex items-start gap-2 text-xs">
      <span className={`mt-0.5 h-1.5 w-1.5 rounded-full shrink-0 ${
        issue.severity === "error" ? "bg-red-500" :
        issue.severity === "warning" ? "bg-amber-500" : "bg-blue-500"
      }`} />
      <span className="text-zinc-300">{issue.message}</span>
    </div>
  );
}

interface SubfolderPanelProps {
  subfolder: AlbumSubfolder;
  albumId: string;
  artistName: string;
  albumName: string;
  existingTracks: Track[];
}

function SubfolderPanel({ subfolder, albumId, artistName, albumName, existingTracks }: SubfolderPanelProps) {
  const [showConfirm, setShowConfirm] = useState<"move" | "delete" | null>(null);
  const [isFlattening, setIsFlattening] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Compute the track number offset: highest existing track number in the album
  const maxExistingTrack = existingTracks.reduce(
    (max, t) => Math.max(max, t.trackNumber ?? 0),
    0,
  );

  // Sort subfolder files by their current track number so ordering is preserved
  const sortedFiles = [...subfolder.files].sort((a, b) => {
    const aNum = parseTrackFilename(a.name).trackNumber ?? 0;
    const bNum = parseTrackFilename(b.name).trackNumber ?? 0;
    return aNum - bNum;
  });

  // Compute rename preview: what each file will be renamed to
  const renamePreview = sortedFiles.map((file, i) => {
    const parsed = parseTrackFilename(file.name);
    const newTrackNum = maxExistingTrack + i + 1;
    const ext = file.name.split(".").pop()?.toLowerCase() ?? "mp3";
    const title = parsed.title;
    const newName = buildCanonicalFilename(artistName, albumName, newTrackNum, title, ext);
    return { file, newTrackNum, newName, oldName: file.name };
  });

  const handleFlatten = useCallback(async () => {
    setIsFlattening(true);
    setError(null);
    try {
      for (let i = 0; i < renamePreview.length; i++) {
        const item = renamePreview[i]!;
        setProgress(`Moving & renaming ${i + 1} of ${renamePreview.length}: ${item.oldName}`);
        // Move file to album folder
        await moveFile(item.file.id, subfolder.id, albumId);
        // Rename to canonical format with renumbered track
        if (item.newName !== item.oldName) {
          await renameFile(item.file.id, item.newName);
        }
      }
      // Trash the now-empty subfolder
      setProgress("Removing empty subfolder...");
      await trashFile(subfolder.id);
      // Update local state with rename map
      const renames = new Map(renamePreview.map((item) => [item.file.id, item.newName]));
      flattenSubfolderInState(albumId, subfolder.id, renames);
      setShowConfirm(null);
      setProgress(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setIsFlattening(false);
    }
  }, [renamePreview, subfolder, albumId]);

  const handleDelete = useCallback(async () => {
    setIsFlattening(true);
    setError(null);
    try {
      setProgress("Trashing subfolder and all its contents...");
      await trashFile(subfolder.id);
      removeSubfolderFromState(albumId, subfolder.id);
      setShowConfirm(null);
      setProgress(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setIsFlattening(false);
    }
  }, [subfolder, albumId]);

  return (
    <div className="rounded-lg border border-amber-900/50 bg-amber-950/20 p-4 space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <div className="text-sm font-medium text-amber-400">
            Subfolder: "{subfolder.name}"
          </div>
          <div className="text-xs text-zinc-400 mt-0.5">
            Contains {subfolder.fileCount} audio file{subfolder.fileCount !== 1 ? "s" : ""} that should be in the album folder
          </div>
        </div>
        {!showConfirm && !isFlattening && (
          <div className="flex gap-2 shrink-0">
            <button
              onClick={() => setShowConfirm("move")}
              className="px-3 py-1.5 rounded-md bg-amber-600 text-xs font-medium text-white hover:bg-amber-500 transition-colors"
            >
              Move Files Out
            </button>
            <button
              onClick={() => setShowConfirm("delete")}
              className="px-3 py-1.5 rounded-md bg-zinc-800 text-xs text-red-400 hover:bg-red-950/50 border border-zinc-700 hover:border-red-900/50 transition-colors"
            >
              Delete
            </button>
          </div>
        )}
      </div>

      {/* Rename preview table */}
      <div className="max-h-60 overflow-y-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-zinc-500 border-b border-zinc-800/50">
              <th className="pb-1 pr-2 w-8">#</th>
              <th className="pb-1 pr-2">Current Name</th>
              <th className="pb-1">Renamed To</th>
            </tr>
          </thead>
          <tbody>
            {renamePreview.map((item) => (
              <tr key={item.file.id} className="border-b border-zinc-800/30">
                <td className="py-1 pr-2 text-zinc-500 font-mono">{String(item.newTrackNum).padStart(2, "0")}</td>
                <td className="py-1 pr-2 text-zinc-400 font-mono truncate max-w-[200px]">{item.oldName}</td>
                <td className="py-1 text-green-400 font-mono truncate max-w-[300px]">{item.newName}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {showConfirm === "move" && !isFlattening && (
        <div className="rounded-md border border-amber-800/50 bg-amber-950/30 p-3 space-y-2">
          <p className="text-xs text-zinc-300">
            This will move all {subfolder.fileCount} file{subfolder.fileCount !== 1 ? "s" : ""} from
            "{subfolder.name}" into the album folder, renumber tracks starting at {maxExistingTrack + 1},
            and rename them to canonical format. The empty subfolder will be trashed.
          </p>
          <div className="flex gap-2">
            <button
              onClick={handleFlatten}
              className="px-3 py-1.5 rounded-md bg-amber-600 text-xs font-medium text-white hover:bg-amber-500 transition-colors"
            >
              Confirm Move & Rename
            </button>
            <button
              onClick={() => setShowConfirm(null)}
              className="px-3 py-1.5 rounded-md bg-zinc-800 text-xs text-zinc-300 hover:bg-zinc-700 transition-colors"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {showConfirm === "delete" && !isFlattening && (
        <div className="rounded-md border border-red-800/50 bg-red-950/30 p-3 space-y-2">
          <p className="text-xs text-zinc-300">
            This will trash the "{subfolder.name}" subfolder and all {subfolder.fileCount} file{subfolder.fileCount !== 1 ? "s" : ""} inside it.
            You can recover them from Google Drive trash within 30 days.
          </p>
          <div className="flex gap-2">
            <button
              onClick={handleDelete}
              className="px-3 py-1.5 rounded-md bg-red-600 text-xs font-medium text-white hover:bg-red-500 transition-colors"
            >
              Confirm Delete
            </button>
            <button
              onClick={() => setShowConfirm(null)}
              className="px-3 py-1.5 rounded-md bg-zinc-800 text-xs text-zinc-300 hover:bg-zinc-700 transition-colors"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {isFlattening && progress && (
        <div className="text-xs text-zinc-400">{progress}</div>
      )}

      {error && (
        <div className="text-xs text-red-400">{error}</div>
      )}
    </div>
  );
}

function TrackRow({ track, artistName, albumName, onPlay }: { track: Track; artistName: string; albumName: string; onPlay: () => void }) {
  const [editing, setEditing] = useState(false);
  const [editNum, setEditNum] = useState("");
  const [editTitle, setEditTitle] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const ext = track.file.name.split(".").pop()?.toLowerCase() ?? "mp3";
  const extDisplay = ext.toUpperCase();

  const num = track.discNumber && track.discNumber > 0
    ? `${track.discNumber}-${String(track.trackNumber ?? "?").padStart(2, "0")}`
    : track.trackNumber != null
      ? String(track.trackNumber).padStart(2, "0")
      : "--";

  function startEditing() {
    setEditNum(track.trackNumber != null ? String(track.trackNumber) : "");
    setEditTitle(track.trackName);
    setError(null);
    setEditing(true);
  }

  function cancelEditing() {
    setEditing(false);
    setError(null);
  }

  async function saveEdit() {
    const newTrackNum = parseInt(editNum, 10);
    const newTitle = editTitle.trim();
    if (!newTitle) {
      setError("Title cannot be empty");
      return;
    }
    if (isNaN(newTrackNum) || newTrackNum < 1) {
      setError("Track number must be a positive number");
      return;
    }

    const newName = buildCanonicalFilename(artistName, albumName, newTrackNum, newTitle, ext);
    if (newName === track.file.name) {
      setEditing(false);
      return;
    }

    setSaving(true);
    setError(null);
    try {
      await renameFile(track.file.id, newName);
      renameFileInState(track.file.id, newName);
      setEditing(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Rename failed");
    } finally {
      setSaving(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Enter") {
      e.preventDefault();
      saveEdit();
    } else if (e.key === "Escape") {
      cancelEditing();
    }
  }

  async function handleDelete() {
    setDeleting(true);
    setError(null);
    try {
      await trashFile(track.file.id);
      removeTrackFromState(track.file.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Delete failed");
      setDeleting(false);
      setConfirmDelete(false);
    }
  }

  if (editing) {
    return (
      <tr className="border-b border-zinc-800/50 bg-zinc-800/30">
        <td className="py-2 px-1">
          <input
            type="number"
            min={1}
            value={editNum}
            onChange={(e) => setEditNum(e.target.value)}
            onKeyDown={handleKeyDown}
            className="w-10 rounded bg-zinc-900 border border-zinc-600 px-1.5 py-0.5 text-xs font-mono text-zinc-100 focus:outline-none focus:ring-1 focus:ring-blue-500 [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
            autoFocus
          />
        </td>
        <td className="py-2" colSpan={2}>
          <input
            type="text"
            value={editTitle}
            onChange={(e) => setEditTitle(e.target.value)}
            onKeyDown={handleKeyDown}
            className="w-full rounded bg-zinc-900 border border-zinc-600 px-2 py-0.5 text-sm text-zinc-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          {error && <div className="text-xs text-red-400 mt-1">{error}</div>}
          <div className="mt-1 text-xs text-zinc-500 font-mono truncate">
            {editNum && editTitle.trim()
              ? buildCanonicalFilename(artistName, albumName, parseInt(editNum, 10) || 0, editTitle.trim(), ext)
              : "..."}
          </div>
        </td>
        <td className="py-2 text-right align-top">
          <div className="flex flex-col gap-1 items-end">
            <div className="flex gap-1">
              <button
                onClick={saveEdit}
                disabled={saving || deleting}
                className="px-2 py-0.5 rounded bg-green-700 text-xs text-white hover:bg-green-600 disabled:opacity-50 transition-colors"
              >
                {saving ? "..." : "Save"}
              </button>
              <button
                onClick={cancelEditing}
                disabled={saving || deleting}
                className="px-2 py-0.5 rounded bg-zinc-700 text-xs text-zinc-300 hover:bg-zinc-600 disabled:opacity-50 transition-colors"
              >
                Cancel
              </button>
            </div>
            {!confirmDelete ? (
              <button
                onClick={() => setConfirmDelete(true)}
                disabled={saving || deleting}
                className="px-2 py-0.5 rounded text-[10px] text-red-400 hover:text-red-300 hover:bg-red-950/30 disabled:opacity-50 transition-colors"
              >
                Delete
              </button>
            ) : (
              <div className="flex gap-1 items-center">
                <button
                  onClick={handleDelete}
                  disabled={deleting}
                  className="px-2 py-0.5 rounded bg-red-700 text-[10px] text-white hover:bg-red-600 disabled:opacity-50 transition-colors"
                >
                  {deleting ? "..." : "Confirm"}
                </button>
                <button
                  onClick={() => setConfirmDelete(false)}
                  disabled={deleting}
                  className="px-2 py-0.5 rounded text-[10px] text-zinc-500 hover:text-zinc-300 disabled:opacity-50 transition-colors"
                >
                  No
                </button>
              </div>
            )}
          </div>
        </td>
      </tr>
    );
  }

  function handlePlayClick(e: React.MouseEvent) {
    e.stopPropagation();
    onPlay();
  }

  return (
    <tr
      className="border-b border-zinc-800/50 hover:bg-zinc-900/50 cursor-pointer group"
      onClick={startEditing}
    >
      <td className="py-2 text-zinc-500 font-mono text-xs relative">
        <span className="group-hover:invisible">{num}</span>
        <button
          onClick={handlePlayClick}
          className="absolute inset-0 flex items-center justify-center invisible group-hover:visible text-white hover:text-green-400 transition-colors"
          title="Play from here"
        >
          <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor"><path d="M4 2.5v11l9-5.5L4 2.5z" /></svg>
        </button>
      </td>
      <td className="py-2">
        <div className="text-zinc-200">{track.trackName}</div>
        <div className="text-xs text-zinc-600">{track.file.name}</div>
      </td>
      <td className="py-2 text-right text-zinc-500 text-xs">{formatFileSize(track.file.size)}</td>
      <td className="py-2 text-right text-zinc-500 text-xs">
        {extDisplay}
        <span className="ml-2 text-zinc-700 opacity-0 group-hover:opacity-100 transition-opacity text-[10px]">
          Edit
        </span>
      </td>
    </tr>
  );
}
