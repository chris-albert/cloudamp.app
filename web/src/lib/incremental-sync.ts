/**
 * Incremental library sync using the Google Drive Changes API.
 *
 * Instead of re-fetching all folders/files/covers on every load, we:
 * 1. Store a "change page token" after each scan
 * 2. On next load, fetch only changes since that token
 * 3. Apply additions/modifications/deletions to cached raw file lists
 * 4. Rebuild the library tree from the updated lists
 *
 * Falls back to a full scan when the token is missing, expired, or the
 * cached data is in an old format.
 */

import {
  type DriveFile,
  isAudioFile,
  isFolder,
  fetchChanges,
  getChangesStartPageToken,
  PageTokenExpiredError,
} from "./drive-api";
import {
  COVER_NAMES,
  buildLibraryTree,
  scanLibrary,
  type ScanResult,
  type ScanProgress,
} from "./library-scanner";
import { validateLibrary } from "./library-validator";
import {
  getScanState,
  setSyncProgress,
  setScanProgress,
  setScanResult,
} from "./scan-store";
import { getRootFolderId } from "./google-auth";

export interface SyncOutcome {
  type: "incremental" | "full" | "no-changes";
  changesApplied?: number;
}

/**
 * Attempt incremental sync. Falls back to full scan if needed.
 */
export async function syncLibrary(
  onProgress?: (progress: ScanProgress) => void,
): Promise<SyncOutcome> {
  const rootId = getRootFolderId();
  if (!rootId) throw new Error("Root folder ID not configured");

  const currentState = getScanState();
  const pageToken = currentState.changePageToken;
  const cachedResult = currentState.result;

  // No cache, no token, or old format without allCoverFiles → full scan
  if (!pageToken || !cachedResult || !cachedResult.allCoverFiles) {
    return doFullScan(rootId, onProgress);
  }

  try {
    return await doIncrementalSync(rootId, pageToken, cachedResult, onProgress);
  } catch (err) {
    if (err instanceof PageTokenExpiredError) {
      onProgress?.({ stage: "Change token expired, doing full scan..." });
      return doFullScan(rootId, onProgress);
    }
    throw err;
  }
}

/**
 * Perform a full scan and capture a change token for future incremental syncs.
 * The token is obtained *before* the scan so changes during the scan aren't lost.
 */
export async function doFullScan(
  rootId: string,
  onProgress?: (progress: ScanProgress) => void,
): Promise<SyncOutcome> {
  const report = onProgress ?? setScanProgress;

  // Get token before scan so we don't miss changes that happen during it
  const tokenBeforeScan = await getChangesStartPageToken();

  const result = await scanLibrary(rootId, report);
  const validation = validateLibrary(result);
  setScanResult(result, validation, tokenBeforeScan);

  return { type: "full" };
}

async function doIncrementalSync(
  rootId: string,
  pageToken: string,
  cachedResult: ScanResult,
  onProgress?: (progress: ScanProgress) => void,
): Promise<SyncOutcome> {
  const report = onProgress ?? setSyncProgress;

  report({ stage: "Checking for changes..." });

  const { changes, newStartPageToken } = await fetchChanges(
    pageToken,
    (n) => report({ stage: "Checking for changes...", detail: `${n} found` }),
  );

  if (changes.length === 0) {
    // Update token even with no changes so subsequent polls are efficient
    const validation = validateLibrary(cachedResult);
    setScanResult(cachedResult, validation, newStartPageToken);
    return { type: "no-changes" };
  }

  report({
    stage: "Applying changes...",
    detail: `${changes.length} change${changes.length !== 1 ? "s" : ""}`,
  });

  // Build mutable maps from cached raw file lists for O(1) upsert/delete
  const foldersById = new Map(cachedResult.allFolders.map((f) => [f.id, f]));
  const audioById = new Map(cachedResult.allAudioFiles.map((f) => [f.id, f]));
  const coversById = new Map(cachedResult.allCoverFiles.map((f) => [f.id, f]));

  let changesApplied = 0;

  for (const change of changes) {
    const { fileId, removed, file } = change;

    if (removed || !file || file.trashed) {
      // File was removed/trashed — delete from whichever list it's in
      if (foldersById.delete(fileId)) changesApplied++;
      if (audioById.delete(fileId)) changesApplied++;
      if (coversById.delete(fileId)) changesApplied++;
      continue;
    }

    // File exists — classify and upsert
    if (isFolder(file)) {
      foldersById.set(fileId, file);
      changesApplied++;
    } else if (isAudioFile(file)) {
      audioById.set(fileId, file);
      coversById.delete(fileId);
      changesApplied++;
    } else if (isCoverFile(file)) {
      coversById.set(fileId, file);
      changesApplied++;
    }
    // Other file types are irrelevant to the library
  }

  // Convert maps back to arrays
  const allFolders = Array.from(foldersById.values());
  const allAudioFiles = Array.from(audioById.values());
  const allCoverFiles = Array.from(coversById.values());

  // Rebuild the full library tree from updated raw lists
  report({ stage: "Rebuilding library tree..." });
  const tree = buildLibraryTree(rootId, allFolders, allAudioFiles, allCoverFiles);

  const result: ScanResult = {
    ...tree,
    allFolders,
    allAudioFiles,
    allCoverFiles,
  };

  const validation = validateLibrary(result);
  setScanResult(result, validation, newStartPageToken);

  report({ stage: "Sync complete!" });

  return { type: "incremental", changesApplied };
}

function isCoverFile(file: DriveFile): boolean {
  if (!file.mimeType.startsWith("image/")) return false;
  return COVER_NAMES.has(file.name.toLowerCase());
}
