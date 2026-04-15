/**
 * Validates a scanned library and flags issues with file/folder naming,
 * structure, and missing data.
 *
 * Canonical formats enforced:
 *   Track file: "{Artist} - {Album} - {NN} - {Title}.ext"
 *   Album folder: "{Album Name} ({year})"
 */

import type { ScanResult, Track } from "./library-scanner";
import {
  parseTrackFilename,
  isCanonicalTrackFilename,
  isCanonicalAlbumFolder,
  buildCanonicalFilename,
} from "./filename-parser";

export type IssueSeverity = "error" | "warning" | "info";

export type IssueCategory =
  | "non_canonical_filename"
  | "non_canonical_album_folder"
  | "missing_track_number"
  | "missing_year"
  | "missing_cover"
  | "artist_mismatch"
  | "album_mismatch"
  | "duplicate_track_number"
  | "orphaned_file"
  | "orphaned_folder"
  | "empty_album"
  | "empty_artist"
  | "gap_in_track_numbers"
  | "mixed_extensions"
  | "album_subfolder";

export interface LibraryIssue {
  id: string;
  severity: IssueSeverity;
  category: IssueCategory;
  message: string;
  artistId?: string;
  artistName?: string;
  albumId?: string;
  albumName?: string;
  fileName?: string;
  fileId?: string;
  /** Suggested corrected value, if applicable */
  suggestion?: string;
}

let issueCounter = 0;

function makeIssue(
  severity: IssueSeverity,
  category: IssueCategory,
  message: string,
  context?: Partial<Pick<LibraryIssue, "artistId" | "artistName" | "albumId" | "albumName" | "fileName" | "fileId" | "suggestion">>,
): LibraryIssue {
  return {
    id: `issue-${++issueCounter}`,
    severity,
    category,
    message,
    ...context,
  };
}

export interface ValidationResult {
  issues: LibraryIssue[];
  stats: {
    totalArtists: number;
    totalAlbums: number;
    totalTracks: number;
    issuesByCategory: Record<string, number>;
    issuesBySeverity: Record<IssueSeverity, number>;
  };
}

export function validateLibrary(scan: ScanResult): ValidationResult {
  issueCounter = 0;
  const issues: LibraryIssue[] = [];

  // ── Artist-level checks ─────────────────────────────────────────────

  for (const artist of scan.artists) {
    const albums = scan.albumsByArtist[artist.id] ?? [];

    if (albums.length === 0) {
      issues.push(
        makeIssue("warning", "empty_artist", `Artist "${artist.name}" has no albums`, {
          artistId: artist.id,
          artistName: artist.name,
        }),
      );
      continue;
    }

    // ── Album-level checks ──────────────────────────────────────────

    for (const album of albums) {
      const tracks = scan.tracksByAlbum[album.id] ?? [];
      const ctx = {
        artistId: artist.id,
        artistName: artist.name,
        albumId: album.id,
        albumName: album.name,
      };

      // Non-canonical album folder format — must be "Album Name (year)"
      if (!isCanonicalAlbumFolder(album.folderName)) {
        const expected = album.year
          ? `${album.name} (${album.year})`
          : `${album.name} (YYYY)`;
        issues.push(
          makeIssue("error", "non_canonical_album_folder",
            `Album folder "${album.folderName}" should be "${expected}"`,
            { ...ctx, suggestion: expected }),
        );
      }

      // Missing year (no year could be extracted at all)
      if (album.year === null) {
        issues.push(
          makeIssue("warning", "missing_year", `Album folder "${album.folderName}" has no year`, ctx),
        );
      }

      // Missing cover art
      if (album.coverFileId === null) {
        issues.push(
          makeIssue("warning", "missing_cover", `Album "${album.name}" by ${artist.name} has no cover art`, ctx),
        );
      }

      // Subfolders with audio files (e.g. CD1, Disc 1)
      const subfolders = album.subfolders ?? [];
      if (subfolders.length > 0) {
        const totalSubfolderFiles = subfolders.reduce((sum, sf) => sum + sf.fileCount, 0);
        const folderNames = subfolders.map((sf) => `"${sf.name}"`).join(", ");
        issues.push(
          makeIssue("warning", "album_subfolder",
            `Album "${album.name}" has ${subfolders.length} subfolder${subfolders.length !== 1 ? "s" : ""} (${folderNames}) containing ${totalSubfolderFiles} track${totalSubfolderFiles !== 1 ? "s" : ""} — files should be moved to the album folder`,
            ctx),
        );
      }

      // Empty album
      if (tracks.length === 0 && subfolders.length === 0) {
        issues.push(
          makeIssue("warning", "empty_album", `Album "${album.name}" by ${artist.name} has no tracks`, ctx),
        );
        continue;
      }

      // ── Track-level checks ──────────────────────────────────────

      const trackNumbers = new Map<string, Track[]>(); // "disc-track" → tracks
      const extensions = new Set<string>();

      for (const track of tracks) {
        const parsedTrack = parseTrackFilename(track.file.name);
        const ext = track.file.name.split(".").pop()?.toLowerCase() ?? "";

        // Non-canonical filename
        if (!isCanonicalTrackFilename(track.file.name)) {
          const suggestion = parsedTrack.trackNumber != null
            ? buildCanonicalFilename(artist.name, album.name, parsedTrack.trackNumber, parsedTrack.title, ext)
            : `${artist.name} - ${album.name} - NN - ${parsedTrack.title}.${ext}`;
          issues.push(
            makeIssue("error", "non_canonical_filename",
              `"${track.file.name}" should be "${suggestion}"`,
              { ...ctx, fileName: track.file.name, fileId: track.file.id, suggestion }),
          );
        } else {
          // File is canonical — check that artist/album segments match the folder structure
          if (parsedTrack.artist && parsedTrack.artist !== artist.name) {
            issues.push(
              makeIssue("warning", "artist_mismatch",
                `"${track.file.name}" has artist "${parsedTrack.artist}" but is in artist folder "${artist.name}"`,
                { ...ctx, fileName: track.file.name, fileId: track.file.id }),
            );
          }
          if (parsedTrack.album && parsedTrack.album !== album.name) {
            issues.push(
              makeIssue("warning", "album_mismatch",
                `"${track.file.name}" has album "${parsedTrack.album}" but is in album folder "${album.name}"`,
                { ...ctx, fileName: track.file.name, fileId: track.file.id }),
            );
          }
        }

        // Missing track number
        if (parsedTrack.trackNumber === null) {
          issues.push(
            makeIssue("error", "missing_track_number",
              `Track "${track.file.name}" has no track number`,
              { ...ctx, fileName: track.file.name, fileId: track.file.id }),
          );
        }

        // Track duplicate detection
        if (parsedTrack.trackNumber !== null) {
          const key = `${parsedTrack.discNumber ?? 0}-${parsedTrack.trackNumber}`;
          const existing = trackNumbers.get(key) ?? [];
          existing.push(track);
          trackNumbers.set(key, existing);
        }

        // Collect extensions
        if (ext) extensions.add(ext);
      }

      // Duplicate track numbers
      for (const [key, dupes] of trackNumbers) {
        if (dupes.length > 1) {
          const names = dupes.map((t) => t.file.name).join(", ");
          issues.push(
            makeIssue("error", "duplicate_track_number",
              `Duplicate track number ${key} in "${album.name}": ${names}`,
              ctx),
          );
        }
      }

      // Gap in track numbers
      const tracksWithNumbers = tracks.filter((t) => t.trackNumber !== null);
      if (tracksWithNumbers.length > 0) {
        const byDisc = new Map<number, number[]>();
        for (const t of tracksWithNumbers) {
          const disc = t.discNumber ?? 0;
          const list = byDisc.get(disc) ?? [];
          list.push(t.trackNumber!);
          byDisc.set(disc, list);
        }

        for (const [disc, nums] of byDisc) {
          const sorted = [...new Set(nums)].sort((a, b) => a - b);
          const max = sorted[sorted.length - 1]!;
          const expected = Array.from({ length: max }, (_, i) => i + 1);
          const missing = expected.filter((n) => !sorted.includes(n));
          if (missing.length > 0) {
            const discLabel = disc > 0 ? ` (disc ${disc})` : "";
            issues.push(
              makeIssue("warning", "gap_in_track_numbers",
                `Album "${album.name}"${discLabel} is missing track numbers: ${missing.join(", ")}`,
                ctx),
            );
          }
        }
      }

      // Mixed file extensions
      if (extensions.size > 1) {
        issues.push(
          makeIssue("info", "mixed_extensions",
            `Album "${album.name}" has mixed file types: ${[...extensions].join(", ")}`,
            ctx),
        );
      }
    }
  }

  // ── Orphaned files ──────────────────────────────────────────────────

  for (const file of scan.orphanedFiles) {
    issues.push(
      makeIssue("error", "orphaned_file",
        `Audio file "${file.name}" is not in an album folder (expected Artist/Album/Track structure)`,
        { fileName: file.name, fileId: file.id }),
    );
  }

  for (const folder of scan.orphanedFolders) {
    issues.push(
      makeIssue("warning", "orphaned_folder",
        `Subfolder "${folder.name}" is nested too deep (only Artist/Album levels expected)`,
        { fileName: folder.name, fileId: folder.id }),
    );
  }

  // ── Build stats ─────────────────────────────────────────────────────

  const issuesByCategory: Record<string, number> = {};
  const issuesBySeverity: Record<IssueSeverity, number> = { error: 0, warning: 0, info: 0 };

  for (const issue of issues) {
    issuesByCategory[issue.category] = (issuesByCategory[issue.category] ?? 0) + 1;
    issuesBySeverity[issue.severity]++;
  }

  const totalAlbums = Object.values(scan.albumsByArtist).reduce((sum, a) => sum + a.length, 0);
  const totalTracks = Object.values(scan.tracksByAlbum).reduce((sum, t) => sum + t.length, 0);

  return {
    issues,
    stats: {
      totalArtists: scan.artists.length,
      totalAlbums,
      totalTracks,
      issuesByCategory,
      issuesBySeverity,
    },
  };
}
