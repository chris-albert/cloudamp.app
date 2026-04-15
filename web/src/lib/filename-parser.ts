/**
 * Parses track filenames and album folder names into structured data.
 *
 * Canonical formats:
 *   Track file: "{Artist} - {Album} - {NN} - {Title}.ext"
 *   Album folder: "{Album Name} ({year})"
 */

export interface ParsedTrack {
  trackNumber: number | null;
  discNumber: number | null;
  title: string;
  /** The artist segment parsed from the filename, if present */
  artist: string | null;
  /** The album segment parsed from the filename, if present */
  album: string | null;
}

export interface ParsedAlbum {
  name: string;
  year: number | null;
}

/**
 * Canonical format: "Artist - Album - NN - Title.ext"
 * Also handles non-canonical formats for best-effort parsing.
 */
export function parseTrackFilename(filename: string): ParsedTrack {
  const nameWithoutExt = filename.replace(/\.[^.]+$/, "");

  // Canonical: "Artist - Album - NN - Title"
  const canonicalMatch = nameWithoutExt.match(
    /^(.+?)\s-\s(.+?)\s-\s(\d{1,3})\s-\s(.+)$/,
  );
  if (canonicalMatch) {
    return {
      artist: canonicalMatch[1]!.trim(),
      album: canonicalMatch[2]!.trim(),
      trackNumber: Number(canonicalMatch[3]),
      discNumber: null,
      title: canonicalMatch[4]!.trim(),
    };
  }

  // Fallback: disc-track at start (e.g. "1-05 - Title")
  const discTrackMatch = nameWithoutExt.match(/^(\d{1,2})-(\d{1,3})\s*[.\-]?\s*(.+)$/);
  if (discTrackMatch) {
    return {
      artist: null,
      album: null,
      trackNumber: Number(discTrackMatch[2]),
      discNumber: Number(discTrackMatch[1]),
      title: discTrackMatch[3]!.trim(),
    };
  }

  // Fallback: track number at start — "01 - Title"
  const trackMatch = nameWithoutExt.match(/^(\d{1,3})\s*[.\-]?\s+(.+)$/);
  if (trackMatch) {
    return {
      artist: null,
      album: null,
      trackNumber: Number(trackMatch[1]),
      discNumber: null,
      title: trackMatch[2]!.trim(),
    };
  }

  // Fallback: track number somewhere after prefix — "Something - 03 - Title"
  const midTrackMatch = nameWithoutExt.match(/^.+\s-\s(\d{1,3})\s*[.\-]\s*(.+)$/);
  if (midTrackMatch) {
    return {
      artist: null,
      album: null,
      trackNumber: Number(midTrackMatch[1]),
      discNumber: null,
      title: midTrackMatch[2]!.trim(),
    };
  }

  // No track number found
  return { artist: null, album: null, trackNumber: null, discNumber: null, title: nameWithoutExt.trim() };
}

/**
 * Check if a filename matches the canonical format:
 * "{Artist} - {Album} - {NN} - {Title}.ext"
 */
export function isCanonicalTrackFilename(filename: string): boolean {
  const nameWithoutExt = filename.replace(/\.[^.]+$/, "");
  return /^.+?\s-\s.+?\s-\s\d{1,3}\s-\s.+$/.test(nameWithoutExt);
}

/**
 * Build the expected canonical filename for a track.
 */
export function buildCanonicalFilename(
  artistName: string,
  albumName: string,
  trackNumber: number,
  title: string,
  ext: string,
): string {
  const num = String(trackNumber).padStart(2, "0");
  return `${artistName} - ${albumName} - ${num} - ${title}.${ext}`;
}

/**
 * Canonical album folder format: "Album Name (year)"
 */
export function isCanonicalAlbumFolder(folderName: string): boolean {
  return /^.+\s\(\d{4}\)$/.test(folderName);
}

/**
 * Parse an album folder name — supports multiple formats for extraction,
 * but only "Album Name (year)" is considered canonical.
 */
export function parseAlbumFolderName(folderName: string): ParsedAlbum {
  // Canonical: "Album Name (2020)"
  const suffixParen = folderName.match(/^(.+)\s\((\d{4})\)$/);
  if (suffixParen) {
    const name = suffixParen[1]!.trim();
    if (name) return { name, year: Number(suffixParen[2]) };
  }

  // Non-canonical fallbacks (still extract data, but validator will flag them)

  // "(2020) Album Name"
  const prefixParen = folderName.match(/^\((\d{4})\)\s*(.+)$/);
  if (prefixParen) {
    const name = prefixParen[2]!.trim();
    if (name) return { name, year: Number(prefixParen[1]) };
  }

  // "[2020] Album Name"
  const prefixBracket = folderName.match(/^\[(\d{4})]\s*(.+)$/);
  if (prefixBracket) {
    const name = prefixBracket[2]!.trim();
    if (name) return { name, year: Number(prefixBracket[1]) };
  }

  // "2020 - Album Name"
  const prefixDash = folderName.match(/^(\d{4})\s*-\s*(.+)$/);
  if (prefixDash) {
    const name = prefixDash[2]!.trim();
    if (name) return { name, year: Number(prefixDash[1]) };
  }

  // "Album Name [2020]"
  const suffixBracket = folderName.match(/^(.+)\s*\[(\d{4})]\s*$/);
  if (suffixBracket) {
    const name = suffixBracket[1]!.trim();
    if (name) return { name, year: Number(suffixBracket[2]) };
  }

  return { name: folderName.trim(), year: null };
}
