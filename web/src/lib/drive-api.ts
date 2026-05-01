/**
 * Google Drive API v3 client.
 * Mirrors the Android app's GoogleDriveApiService but for browser use.
 */

import { getAccessToken, refreshAccessToken } from "./google-auth";

const BASE_URL = "https://www.googleapis.com/drive/v3";

export interface DriveFile {
  id: string;
  name: string;
  mimeType: string;
  size?: string;
  parents?: string[];
  modifiedTime?: string;
  trashed?: boolean;
}

interface DriveFileListResponse {
  files: DriveFile[];
  nextPageToken?: string;
}

export interface DriveAbout {
  user: { displayName?: string; emailAddress?: string };
}

const AUDIO_EXTENSIONS = new Set([
  "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "aiff", "ape", "wv",
]);

export function isAudioFile(file: DriveFile): boolean {
  if (file.mimeType.startsWith("audio/")) return true;
  const ext = file.name.split(".").pop()?.toLowerCase() ?? "";
  return AUDIO_EXTENSIONS.has(ext);
}

export function isFolder(file: DriveFile): boolean {
  return file.mimeType === "application/vnd.google-apps.folder";
}

export function formatFileSize(sizeStr?: string): string {
  const bytes = Number(sizeStr);
  if (!sizeStr || isNaN(bytes)) return "";
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

async function fetchWithAuth(url: string): Promise<Response> {
  let token = getAccessToken();
  if (!token) {
    token = await refreshAccessToken();
  }

  let response = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (response.status === 401) {
    token = await refreshAccessToken();
    response = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
    });
  }

  return response;
}

async function fetchWithAuthMutate(
  url: string,
  init: RequestInit,
): Promise<Response> {
  let token = getAccessToken();
  if (!token) {
    token = await refreshAccessToken();
  }

  let response = await fetch(url, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });

  if (response.status === 401) {
    token = await refreshAccessToken();
    response = await fetch(url, {
      ...init,
      headers: { ...init.headers, Authorization: `Bearer ${token}` },
    });
  }

  return response;
}

/**
 * Move a file or folder to the trash in Google Drive.
 * Trashing a folder also trashes all its children.
 */
export async function trashFile(fileId: string): Promise<void> {
  const url = `${BASE_URL}/files/${encodeURIComponent(fileId)}`;
  const res = await fetchWithAuthMutate(url, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ trashed: true }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Failed to trash file: ${res.status} ${body}`);
  }
}

/**
 * Rename a file in Google Drive.
 */
export async function renameFile(fileId: string, newName: string): Promise<void> {
  const url = `${BASE_URL}/files/${encodeURIComponent(fileId)}`;
  const res = await fetchWithAuthMutate(url, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: newName }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Failed to rename file: ${res.status} ${body}`);
  }
}

/**
 * Move a file from one parent folder to another in Google Drive.
 */
export async function moveFile(
  fileId: string,
  fromParentId: string,
  toParentId: string,
): Promise<void> {
  const params = new URLSearchParams({
    addParents: toParentId,
    removeParents: fromParentId,
  });
  const url = `${BASE_URL}/files/${encodeURIComponent(fileId)}?${params}`;
  const res = await fetchWithAuthMutate(url, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({}),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Failed to move file: ${res.status} ${body}`);
  }
}

/**
 * Upload a file to Google Drive using the multipart upload API.
 * Returns the created file's ID.
 */
export async function uploadFile(
  name: string,
  parentId: string,
  blob: Blob,
): Promise<string> {
  const metadata = JSON.stringify({ name, parents: [parentId] });
  const form = new FormData();
  form.append("metadata", new Blob([metadata], { type: "application/json" }));
  form.append("file", blob);

  let token = getAccessToken();
  if (!token) token = await refreshAccessToken();

  let res = await fetch(
    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
    { method: "POST", headers: { Authorization: `Bearer ${token}` }, body: form },
  );

  if (res.status === 401) {
    token = await refreshAccessToken();
    res = await fetch(
      "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
      { method: "POST", headers: { Authorization: `Bearer ${token}` }, body: form },
    );
  }

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Failed to upload file: ${res.status} ${body}`);
  }

  const data: { id: string } = await res.json();
  return data.id;
}

/**
 * Fetch a file's content as a blob URL (for displaying images with auth).
 * Caller must revoke the URL when done via URL.revokeObjectURL().
 */
export async function fetchFileBlobUrl(fileId: string): Promise<string> {
  const url = `${BASE_URL}/files/${encodeURIComponent(fileId)}?alt=media`;
  const res = await fetchWithAuth(url);
  if (!res.ok) throw new Error(`Failed to fetch file: ${res.status}`);
  const blob = await res.blob();
  return URL.createObjectURL(blob);
}

/**
 * Get a valid access token, refreshing if necessary.
 */
export async function getValidAccessToken(): Promise<string> {
  let token = getAccessToken();
  if (!token) {
    token = await refreshAccessToken();
  }
  return token;
}

/**
 * Fetch an audio file's content as a blob URL for streaming playback.
 * Returns a blob URL that can be used as an <audio> src.
 * Caller must revoke the URL when done via URL.revokeObjectURL().
 */
export async function fetchAudioBlobUrl(fileId: string): Promise<string> {
  const url = `${BASE_URL}/files/${encodeURIComponent(fileId)}?alt=media`;
  const res = await fetchWithAuth(url);
  if (!res.ok) throw new Error(`Failed to fetch audio: ${res.status}`);
  const blob = await res.blob();
  return URL.createObjectURL(blob);
}

/**
 * Fetch a file's content as text.
 */
export async function fetchFileText(fileId: string): Promise<string> {
  const url = `${BASE_URL}/files/${encodeURIComponent(fileId)}?alt=media`;
  const res = await fetchWithAuth(url);
  if (!res.ok) throw new Error(`Failed to fetch file: ${res.status}`);
  return res.text();
}

export async function getAbout(): Promise<DriveAbout> {
  const url = `${BASE_URL}/about?fields=user(displayName,emailAddress)`;
  const res = await fetchWithAuth(url);
  if (!res.ok) throw new Error(`Drive API error: ${res.status}`);
  return res.json();
}

async function listFiles(query: string, fields: string, pageToken?: string): Promise<DriveFileListResponse> {
  const params = new URLSearchParams({
    q: query,
    fields: `files(${fields}),nextPageToken`,
    orderBy: "name",
    pageSize: "1000",
  });
  if (pageToken) params.set("pageToken", pageToken);

  const res = await fetchWithAuth(`${BASE_URL}/files?${params}`);
  if (!res.ok) throw new Error(`Drive API error: ${res.status}`);
  return res.json();
}

export async function fetchAllPaginated(
  query: string,
  fields: string,
  onProgress?: (count: number) => void,
): Promise<DriveFile[]> {
  const all: DriveFile[] = [];
  let pageToken: string | undefined;

  do {
    const response = await listFiles(query, fields, pageToken);
    all.push(...response.files);
    pageToken = response.nextPageToken;
    onProgress?.(all.length);
  } while (pageToken);

  return all;
}

// ── Changes API ───────────────────────────────────────────────────────

export class PageTokenExpiredError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PageTokenExpiredError";
  }
}

export async function getChangesStartPageToken(): Promise<string> {
  const url = `${BASE_URL}/changes/startPageToken`;
  const res = await fetchWithAuth(url);
  if (!res.ok) throw new Error(`Drive API error: ${res.status}`);
  const data: { startPageToken: string } = await res.json();
  return data.startPageToken;
}

export interface DriveChange {
  fileId: string;
  removed: boolean;
  file?: DriveFile;
}

interface ChangesListResponse {
  changes: DriveChange[];
  nextPageToken?: string;
  newStartPageToken?: string;
}

export async function fetchChanges(
  pageToken: string,
  onProgress?: (count: number) => void,
): Promise<{ changes: DriveChange[]; newStartPageToken: string }> {
  const allChanges: DriveChange[] = [];
  let currentPageToken: string | undefined = pageToken;
  let newStartPageToken: string | undefined;

  do {
    const params = new URLSearchParams({
      pageToken: currentPageToken!,
      fields:
        "changes(fileId,removed,file(id,name,mimeType,size,parents,modifiedTime,trashed)),nextPageToken,newStartPageToken",
      pageSize: "1000",
      spaces: "drive",
      includeRemoved: "true",
    });

    const res = await fetchWithAuth(`${BASE_URL}/changes?${params}`);
    if (!res.ok) {
      if (res.status === 404) {
        throw new PageTokenExpiredError("Change page token expired");
      }
      throw new Error(`Drive API error: ${res.status}`);
    }

    const data: ChangesListResponse = await res.json();
    allChanges.push(...data.changes);
    currentPageToken = data.nextPageToken;
    if (data.newStartPageToken) {
      newStartPageToken = data.newStartPageToken;
    }
    onProgress?.(allChanges.length);
  } while (currentPageToken);

  if (!newStartPageToken) {
    throw new Error("No newStartPageToken in changes response");
  }

  return { changes: allChanges, newStartPageToken };
}
