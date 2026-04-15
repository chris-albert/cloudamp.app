/**
 * MusicBrainz API client for looking up album metadata and cover art.
 * Rate-limited to 1 request per second per their API policy.
 */

const BASE_URL = "https://musicbrainz.org/ws/2";
const COVER_ART_URL = "https://coverartarchive.org";
const USER_AGENT = "CloudAmp/1.0 (https://cloudamp.app)";

export interface MusicBrainzRelease {
  id: string;
  title: string;
  date: string | null;
  country: string | null;
  status: string | null;
  artistCredit: string;
}

interface RawRelease {
  id: string;
  title: string;
  date?: string;
  country?: string;
  status?: string;
  "artist-credit"?: Array<{ name: string }>;
}

/**
 * Search MusicBrainz for releases matching an artist + album name.
 * Returns up to `limit` results sorted by relevance.
 */
export async function searchAlbumYear(
  artistName: string,
  albumName: string,
  limit = 5,
): Promise<MusicBrainzRelease[]> {
  const query = `artist:"${escapeLucene(artistName)}" AND release:"${escapeLucene(albumName)}"`;
  const params = new URLSearchParams({
    query,
    limit: String(limit),
    fmt: "json",
  });

  const res = await fetch(`${BASE_URL}/release?${params}`, {
    headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
  });

  if (!res.ok) {
    throw new Error(`MusicBrainz API error: ${res.status}`);
  }

  const data: { releases: RawRelease[] } = await res.json();

  return data.releases
    .filter((r) => r.date)
    .map((r) => ({
      id: r.id,
      title: r.title,
      date: r.date ?? null,
      country: r.country ?? null,
      status: r.status ?? null,
      artistCredit: r["artist-credit"]?.map((ac) => ac.name).join(", ") ?? "",
    }));
}

/**
 * Extract a 4-digit year from a MusicBrainz date string.
 * Dates can be "2020", "2020-03", or "2020-03-15".
 */
export function extractYear(date: string): number | null {
  const match = date.match(/^(\d{4})/);
  return match ? Number(match[1]) : null;
}

export interface CoverArtResult {
  releaseId: string;
  releaseTitle: string;
  artistCredit: string;
  date: string | null;
  /** URL to the front cover image (500px thumbnail) */
  imageUrl: string;
}

/**
 * Search MusicBrainz for releases and check which ones have cover art
 * in the Cover Art Archive. Returns releases with available front covers.
 */
export async function searchCoverArt(
  artistName: string,
  albumName: string,
  limit = 5,
): Promise<CoverArtResult[]> {
  const query = `artist:"${escapeLucene(artistName)}" AND release:"${escapeLucene(albumName)}"`;
  const params = new URLSearchParams({
    query,
    limit: String(limit),
    fmt: "json",
  });

  const res = await fetch(`${BASE_URL}/release?${params}`, {
    headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
  });

  if (!res.ok) {
    throw new Error(`MusicBrainz API error: ${res.status}`);
  }

  const data: { releases: RawRelease[] } = await res.json();
  const results: CoverArtResult[] = [];

  for (const r of data.releases) {
    const imageUrl = await getCoverArtUrl(r.id);
    if (imageUrl) {
      results.push({
        releaseId: r.id,
        releaseTitle: r.title,
        artistCredit: r["artist-credit"]?.map((ac) => ac.name).join(", ") ?? "",
        date: r.date ?? null,
        imageUrl,
      });
      if (results.length >= 3) break;
    }
  }

  return results;
}

/**
 * Get the front cover art URL for a MusicBrainz release.
 * Returns the 500px thumbnail URL, or null if no cover art exists.
 */
async function getCoverArtUrl(releaseId: string): Promise<string | null> {
  try {
    const res = await fetch(`${COVER_ART_URL}/release/${releaseId}`, {
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return null;

    const data: { images: Array<{ front: boolean; thumbnails: { 500?: string; large?: string } }> } = await res.json();
    const front = data.images.find((img) => img.front);
    if (!front) return null;

    return front.thumbnails["500"] ?? front.thumbnails.large ?? null;
  } catch {
    return null;
  }
}

/**
 * Download an image from a URL and return it as a Blob.
 */
export async function fetchImageBlob(url: string): Promise<Blob> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Failed to fetch image: ${res.status}`);
  return res.blob();
}

/**
 * Escape special Lucene query characters.
 */
function escapeLucene(s: string): string {
  return s.replace(/[+\-&|!(){}[\]^"~*?:\\/]/g, "\\$&");
}
