/// <reference lib="webworker" />

const DRIVE_BASE = "https://www.googleapis.com/drive/v3/files";
const STREAM_PATH = "/audio-stream/";

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);

  // Only intercept /audio-stream/{fileId}?token=...
  if (!url.pathname.startsWith(STREAM_PATH)) return;

  const fileId = decodeURIComponent(url.pathname.slice(STREAM_PATH.length));
  const token = url.searchParams.get("token");

  if (!fileId || !token) {
    event.respondWith(new Response("Missing fileId or token", { status: 400 }));
    return;
  }

  event.respondWith(streamFromDrive(event.request, fileId, token));
});

async function streamFromDrive(request, fileId, token) {
  const driveUrl = `${DRIVE_BASE}/${encodeURIComponent(fileId)}?alt=media`;

  const headers = {
    Authorization: `Bearer ${token}`,
  };

  // Forward Range header for seeking support
  const range = request.headers.get("Range");
  if (range) {
    headers["Range"] = range;
  }

  try {
    const driveResponse = await fetch(driveUrl, { headers });

    if (!driveResponse.ok && driveResponse.status !== 206) {
      return new Response(`Drive fetch failed: ${driveResponse.status}`, {
        status: driveResponse.status,
      });
    }

    // Return the drive response directly — the browser can read CORS
    // responses from the SW. We just need to ensure Accept-Ranges is set.
    // Creating a new Response from the body can lose the stream in some
    // browsers, so we return the original response object.
    return driveResponse;
  } catch (err) {
    return new Response(`SW fetch error: ${err.message}`, { status: 502 });
  }
}
