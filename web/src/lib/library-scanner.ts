/**
 * Scans Google Drive and reconstructs the Artist→Album→Track tree.
 * Mirrors GDriveLibraryScanner.kt logic.
 */

import { DriveFile, fetchAllPaginated, isAudioFile } from "./drive-api";
import { parseAlbumFolderName, parseTrackFilename } from "./filename-parser";

export interface Artist {
  id: string;
  name: string;
  folderName: string;
  albumCount: number;
  imageFileId: string | null;
}

export interface AlbumSubfolder {
  id: string;
  name: string;
  albumId: string;
  fileCount: number;
  files: DriveFile[];
}

export interface Album {
  id: string;
  name: string;
  folderName: string;
  artistId: string;
  artistName: string;
  year: number | null;
  trackCount: number;
  coverFileId: string | null;
  /** Subfolders like CD1, Disc 1, etc. that contain audio files */
  subfolders: AlbumSubfolder[];
}

export interface Track {
  file: DriveFile;
  artistId: string;
  artistName: string;
  albumId: string;
  albumName: string;
  trackNumber: number | null;
  discNumber: number | null;
  trackName: string;
  year: number | null;
}

export interface ScanResult {
  artists: Artist[];
  albumsByArtist: Record<string, Album[]>;
  tracksByAlbum: Record<string, Track[]>;
  allFolders: DriveFile[];
  allAudioFiles: DriveFile[];
  orphanedFiles: DriveFile[];
  orphanedFolders: DriveFile[];
}

const COVER_NAMES = new Set([
  "cover.jpg", "cover.png", "cover.jpeg",
  "folder.jpg", "folder.png", "folder.jpeg",
  "front.jpg", "front.png", "front.jpeg",
  "album.jpg", "album.png", "album.jpeg",
]);

export type ScanProgress = {
  stage: string;
  detail?: string;
};

export async function scanLibrary(
  rootId: string,
  onProgress?: (progress: ScanProgress) => void,
): Promise<ScanResult> {
  // ── Bulk fetch: 3 parallel queries ──────────────────────────────────

  onProgress?.({ stage: "Fetching folders..." });
  const foldersPromise = fetchAllPaginated(
    "mimeType = 'application/vnd.google-apps.folder' and trashed = false",
    "id,name,parents,modifiedTime",
    (n) => onProgress?.({ stage: "Fetching folders...", detail: `${n} found` }),
  );

  onProgress?.({ stage: "Fetching audio files..." });
  const audioQuery =
    "(mimeType contains 'audio/'" +
    " or name contains '.flac' or name contains '.m4a'" +
    " or name contains '.ogg' or name contains '.opus'" +
    " or name contains '.wav' or name contains '.aac'" +
    " or name contains '.wma' or name contains '.alac'" +
    " or name contains '.aiff' or name contains '.ape'" +
    ") and trashed = false";
  const audioPromise = fetchAllPaginated(
    audioQuery,
    "id,name,mimeType,size,parents,modifiedTime",
    (n) => onProgress?.({ stage: "Fetching audio files...", detail: `${n} found` }),
  );

  onProgress?.({ stage: "Fetching cover art..." });
  const coverQuery =
    "(name='cover.jpg' or name='cover.png' or name='cover.jpeg'" +
    " or name='folder.jpg' or name='folder.png' or name='folder.jpeg'" +
    " or name='front.jpg' or name='front.png' or name='front.jpeg'" +
    " or name='album.jpg' or name='album.png' or name='album.jpeg')" +
    " and mimeType contains 'image/' and trashed = false";
  const coversPromise = fetchAllPaginated(
    coverQuery,
    "id,name,parents",
    (n) => onProgress?.({ stage: "Fetching cover art...", detail: `${n} found` }),
  );

  const [allFolders, allAudioFilesRaw, allCoverFiles] = await Promise.all([
    foldersPromise,
    audioPromise,
    coversPromise,
  ]);

  // ── Reconstruct tree client-side ────────────────────────────────────

  onProgress?.({ stage: "Building library tree..." });

  // Folders to ignore: default metadata folders that aren't artist folders
  const IGNORED_FOLDER_NAMES = new Set(["music", ".cloudamp"]);

  // Artist folders: direct children of root (excluding known metadata folders)
  const artistFolders = allFolders
    .filter(
      (f) => f.parents?.includes(rootId) && !IGNORED_FOLDER_NAMES.has(f.name.toLowerCase()),
    )
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
  const artistIds = new Set(artistFolders.map((f) => f.id));

  // Album folders: direct children of any artist folder
  const albumFolders = allFolders.filter((f) =>
    f.parents?.some((p) => artistIds.has(p)),
  );
  const albumIds = new Set(albumFolders.map((f) => f.id));

  // Subfolders inside album folders (e.g. CD1, Disc 1, etc.)
  const albumSubfolderList = allFolders.filter((f) =>
    f.parents?.some((p) => albumIds.has(p)),
  );
  const albumSubfolderIds = new Set(albumSubfolderList.map((f) => f.id));

  // Filter to actual audio files
  const allAudioFiles = allAudioFilesRaw.filter((f) => isAudioFile(f));

  // Audio files directly in album folders
  const musicAudioFiles = allAudioFiles.filter((f) =>
    f.parents?.some((p) => albumIds.has(p)),
  );

  // Audio files inside album subfolders (e.g. inside CD1/)
  const subfolderAudioFiles = allAudioFiles.filter((f) =>
    f.parents?.some((p) => albumSubfolderIds.has(p)),
  );

  // Build subfolder map: albumId → AlbumSubfolder[]
  const subfoldersByAlbum = new Map<string, AlbumSubfolder[]>();
  for (const sf of albumSubfolderList) {
    const albumId = sf.parents?.find((p) => albumIds.has(p));
    if (!albumId) continue;
    const filesInSf = subfolderAudioFiles.filter((f) =>
      f.parents?.includes(sf.id),
    );
    if (filesInSf.length === 0) continue; // Only track subfolders that actually have audio
    const list = subfoldersByAlbum.get(albumId) ?? [];
    list.push({
      id: sf.id,
      name: sf.name,
      albumId,
      fileCount: filesInSf.length,
      files: filesInSf,
    });
    subfoldersByAlbum.set(albumId, list);
  }

  // Orphaned: audio files in the root tree but not in album folders or album subfolders
  const allKnownFolderIds = new Set([rootId, ...artistIds, ...albumIds, ...albumSubfolderIds]);
  const orphanedFiles = allAudioFiles.filter(
    (f) =>
      !f.parents?.some((p) => albumIds.has(p)) &&
      !f.parents?.some((p) => albumSubfolderIds.has(p)) &&
      f.parents?.some((p) => allKnownFolderIds.has(p)),
  );

  // Orphaned folders: folders under root that are not artist, album, or album-subfolder level
  const orphanedFolders = allFolders.filter((f) => {
    if (f.id === rootId) return false;
    if (artistIds.has(f.id)) return false;
    if (albumIds.has(f.id)) return false;
    if (albumSubfolderIds.has(f.id)) return false;
    // Check if it's somewhere under the root tree
    const isUnderArtist = f.parents?.some((p) => artistIds.has(p));
    const isUnderAlbum = f.parents?.some((p) => albumIds.has(p));
    return isUnderArtist || isUnderAlbum;
  });

  // Cover maps
  const coverByAlbum = new Map<string, string>();
  const coverByArtist = new Map<string, string>();
  for (const cover of allCoverFiles) {
    const parentId = cover.parents?.[0];
    if (!parentId) continue;
    if (!COVER_NAMES.has(cover.name.toLowerCase())) continue;
    if (albumIds.has(parentId)) coverByAlbum.set(parentId, cover.id);
    if (artistIds.has(parentId)) coverByArtist.set(parentId, cover.id);
  }

  // ── Build structured data ───────────────────────────────────────────

  onProgress?.({ stage: "Organizing library..." });

  const albumFoldersByArtist = new Map<string, DriveFile[]>();
  for (const folder of albumFolders) {
    const artistId = folder.parents?.find((p) => artistIds.has(p));
    if (!artistId) continue;
    const list = albumFoldersByArtist.get(artistId) ?? [];
    list.push(folder);
    albumFoldersByArtist.set(artistId, list);
  }

  const audioFilesByAlbum = new Map<string, DriveFile[]>();
  for (const file of musicAudioFiles) {
    const albumId = file.parents?.find((p) => albumIds.has(p));
    if (!albumId) continue;
    const list = audioFilesByAlbum.get(albumId) ?? [];
    list.push(file);
    audioFilesByAlbum.set(albumId, list);
  }

  const artists: Artist[] = [];
  const albumsByArtist: Record<string, Album[]> = {};
  const tracksByAlbum: Record<string, Track[]> = {};

  for (const artistFolder of artistFolders) {
    const artistAlbumFolders = albumFoldersByArtist.get(artistFolder.id) ?? [];
    const albums: Album[] = [];

    for (const albumFolder of artistAlbumFolders) {
      const parsed = parseAlbumFolderName(albumFolder.name);
      const audioFiles = audioFilesByAlbum.get(albumFolder.id) ?? [];

      albums.push({
        id: albumFolder.id,
        name: parsed.name,
        folderName: albumFolder.name,
        artistId: artistFolder.id,
        artistName: artistFolder.name,
        year: parsed.year,
        trackCount: audioFiles.length,
        coverFileId: coverByAlbum.get(albumFolder.id) ?? null,
        subfolders: subfoldersByAlbum.get(albumFolder.id) ?? [],
      });

      const tracks: Track[] = audioFiles
        .map((file) => {
          const parsedTrack = parseTrackFilename(file.name);
          return {
            file,
            artistId: artistFolder.id,
            artistName: artistFolder.name,
            albumId: albumFolder.id,
            albumName: parsed.name,
            trackNumber: parsedTrack.trackNumber,
            discNumber: parsedTrack.discNumber,
            trackName: parsedTrack.title,
            year: parsed.year,
          };
        })
        .sort((a, b) => {
          const discA = a.discNumber ?? 0;
          const discB = b.discNumber ?? 0;
          if (discA !== discB) return discA - discB;
          return (a.trackNumber ?? 0) - (b.trackNumber ?? 0);
        });

      tracksByAlbum[albumFolder.id] = tracks;
    }

    const sortedAlbums = albums.sort((a, b) => {
      const yearA = a.year ?? Infinity;
      const yearB = b.year ?? Infinity;
      if (yearA !== yearB) return yearA - yearB;
      return a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
    });

    artists.push({
      id: artistFolder.id,
      name: artistFolder.name,
      folderName: artistFolder.name,
      albumCount: sortedAlbums.length,
      imageFileId: coverByArtist.get(artistFolder.id) ?? null,
    });

    albumsByArtist[artistFolder.id] = sortedAlbums;
  }

  onProgress?.({ stage: "Scan complete!" });

  return {
    artists,
    albumsByArtist,
    tracksByAlbum,
    allFolders,
    allAudioFiles,
    orphanedFiles,
    orphanedFolders,
  };
}
