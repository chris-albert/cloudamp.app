import { useEffect, useState } from "react";
import { fetchFileBlobUrl } from "./drive-api";

/** Cache of fileId → blob URL so we don't re-fetch the same image. */
const blobUrlCache = new Map<string, string>();

export function useDriveImage(fileId: string | null): string | null {
  const [url, setUrl] = useState<string | null>(() =>
    fileId ? blobUrlCache.get(fileId) ?? null : null,
  );

  useEffect(() => {
    if (!fileId) { setUrl(null); return; }
    if (blobUrlCache.has(fileId)) { setUrl(blobUrlCache.get(fileId)!); return; }

    let cancelled = false;
    fetchFileBlobUrl(fileId).then((blobUrl) => {
      if (cancelled) { URL.revokeObjectURL(blobUrl); return; }
      blobUrlCache.set(fileId, blobUrl);
      setUrl(blobUrl);
    }).catch(() => {
      if (!cancelled) setUrl(null);
    });

    return () => { cancelled = true; };
  }, [fileId]);

  return url;
}

export function DriveImage({ fileId, alt, className }: { fileId: string; alt: string; className?: string }) {
  const url = useDriveImage(fileId);
  if (!url) return <div className={`${className ?? ""} bg-zinc-800 animate-pulse`} />;
  return <img src={url} alt={alt} className={className} />;
}
