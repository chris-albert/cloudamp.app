import { useEffect, useState } from "react";
import { fetchFileBlobUrl } from "./drive-api";

/** Cache of fileId → blob URL so we don't re-fetch the same image. */
const blobUrlCache = new Map<string, string>();
/** Set of fileIds that failed to load. */
const failedIds = new Set<string>();

export function useDriveImage(fileId: string | null): { url: string | null; failed: boolean } {
  const [url, setUrl] = useState<string | null>(() =>
    fileId ? blobUrlCache.get(fileId) ?? null : null,
  );
  const [failed, setFailed] = useState(() => fileId ? failedIds.has(fileId) : false);

  useEffect(() => {
    if (!fileId) { setUrl(null); setFailed(false); return; }
    if (blobUrlCache.has(fileId)) { setUrl(blobUrlCache.get(fileId)!); setFailed(false); return; }
    if (failedIds.has(fileId)) { setUrl(null); setFailed(true); return; }

    let cancelled = false;
    fetchFileBlobUrl(fileId).then((blobUrl) => {
      if (cancelled) { URL.revokeObjectURL(blobUrl); return; }
      blobUrlCache.set(fileId, blobUrl);
      setUrl(blobUrl);
    }).catch(() => {
      if (!cancelled) { failedIds.add(fileId); setUrl(null); setFailed(true); }
    });

    return () => { cancelled = true; };
  }, [fileId]);

  return { url, failed };
}

export function DriveImage({ fileId, alt, className }: { fileId: string; alt: string; className?: string }) {
  const { url } = useDriveImage(fileId);
  if (!url) return <div className={`${className ?? ""} bg-zinc-800 animate-pulse`} />;
  return <img src={url} alt={alt} className={className} />;
}

/** Artist image with fallback: artist imageFileId -> first album cover -> letter avatar. */
export function ArtistImage({ imageFileId, fallbackFileId, name, className }: {
  imageFileId: string | null;
  fallbackFileId: string | null;
  name: string;
  className?: string;
}) {
  const fileId = imageFileId ?? fallbackFileId;
  const { url, failed } = useDriveImage(fileId);

  if (!fileId || failed) {
    const letter = name.charAt(0).toUpperCase() || "?";
    return (
      <div className={`${className ?? ""} bg-zinc-800 flex items-center justify-center`}>
        <span className="text-zinc-500 text-sm font-medium">{letter}</span>
      </div>
    );
  }

  if (!url) {
    return <div className={`${className ?? ""} bg-zinc-800 animate-pulse`} />;
  }

  return <img src={url} alt={name} className={`${className ?? ""} object-cover`} />;
}
