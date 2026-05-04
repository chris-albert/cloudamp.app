import { useState, useEffect, useCallback, useRef, useSyncExternalStore } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import {
  getStoredCredentials,
  saveCredentials,
  isAuthenticated,
  getRootFolderId,
  startAuthFlow,
  logout,
} from "@/lib/google-auth";
import { getAbout, type DriveAbout, renameFile } from "@/lib/drive-api";
import { FolderPicker } from "@/components/FolderPicker";
import { type IssueSeverity, type LibraryIssue } from "@/lib/library-validator";
import {
  getScanState,
  subscribeScanState,
  setScanProgress,
  setScanError,
  resetScan,
  renameFileInState,
} from "@/lib/scan-store";
import { resetHistory } from "@/lib/history-store";
import { syncLibrary, doFullScan, type SyncOutcome } from "@/lib/incremental-sync";

const FOLDER_NAME_KEY = "cloudamp_root_folder_name";

export function SettingsPage() {
  const navigate = useNavigate();
  const [rootFolderId, setRootFolderId] = useState("");
  const [folderName, setFolderName] = useState("");
  const [showPicker, setShowPicker] = useState(false);
  const [userInfo, setUserInfo] = useState<DriveAbout | null>(null);
  const authed = isAuthenticated();

  useEffect(() => {
    const creds = getStoredCredentials();
    setRootFolderId(creds.rootFolderId);
    setFolderName(localStorage.getItem(FOLDER_NAME_KEY) ?? "");
  }, []);

  useEffect(() => {
    if (authed) {
      getAbout().then(setUserInfo).catch(() => {});
    }
  }, [authed]);

  function handleConnect() {
    startAuthFlow();
  }

  function handleLogout() {
    logout();
    setUserInfo(null);
    navigate({ to: "/settings" });
  }

  function handleFolderSelect(folderId: string, name: string) {
    setRootFolderId(folderId);
    setFolderName(name);
    saveCredentials(folderId);
    localStorage.setItem(FOLDER_NAME_KEY, name);
    setShowPicker(false);
  }

  return (
    <div className="max-w-4xl space-y-8">
      <div>
        <h1 className="text-2xl font-bold">Settings</h1>
        <p className="mt-1 text-sm text-zinc-400">
          Connect your Google Drive to stream your music library.
        </p>
      </div>

      {/* Connection status */}
      {authed && userInfo && (
        <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4 flex items-center justify-between">
          <div>
            <div className="text-sm font-medium text-green-400">Connected</div>
            <div className="text-sm text-zinc-300">{userInfo.user.displayName}</div>
            <div className="text-xs text-zinc-500">{userInfo.user.emailAddress}</div>
          </div>
          <button
            onClick={handleLogout}
            className="px-3 py-1.5 text-sm rounded-md border border-zinc-700 text-zinc-400 hover:text-white hover:border-zinc-500 transition-colors"
          >
            Disconnect
          </button>
        </div>
      )}

      {/* Connect button when not authenticated */}
      {!authed && (
        <button
          onClick={handleConnect}
          className="px-4 py-2 rounded-md bg-blue-600 text-sm font-medium hover:bg-blue-500 transition-colors"
        >
          Connect to Google Drive
        </button>
      )}

      {/* Music folder selection (only show when authenticated) */}
      {authed && (
        <div className="space-y-3">
          <div>
            <label className="block text-sm font-medium text-zinc-300 mb-1">
              Music Folder
            </label>
            {rootFolderId ? (
              <div className="flex items-center gap-3">
                <div className="flex items-center gap-2 px-3 py-2 rounded-md bg-zinc-900 border border-zinc-700 text-sm text-zinc-100 flex-1">
                  <svg className="w-4 h-4 text-zinc-500 shrink-0" fill="currentColor" viewBox="0 0 20 20">
                    <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
                  </svg>
                  {folderName || rootFolderId}
                </div>
                <button
                  onClick={() => setShowPicker(true)}
                  className="px-3 py-2 text-sm rounded-md border border-zinc-700 text-zinc-400 hover:text-white hover:border-zinc-500 transition-colors shrink-0"
                >
                  Change
                </button>
              </div>
            ) : (
              <button
                onClick={() => setShowPicker(true)}
                className="px-4 py-2 rounded-md bg-zinc-800 text-sm font-medium hover:bg-zinc-700 transition-colors"
              >
                Choose Music Folder
              </button>
            )}
            <p className="mt-1 text-xs text-zinc-500">
              Select the Google Drive folder containing your music files.
            </p>
          </div>

          {showPicker && (
            <FolderPicker
              onSelect={handleFolderSelect}
              onCancel={() => setShowPicker(false)}
            />
          )}
        </div>
      )}

      {/* Library Scan section */}
      {authed && (
        <>
          <div className="border-t border-zinc-800" />
          <ScanDashboard />
        </>
      )}
    </div>
  );
}

// ── Scan Dashboard (moved from dashboard.tsx) ─────────────────────────

function ScanDashboard() {
  const scanState = useSyncExternalStore(subscribeScanState, getScanState);
  const [severityFilter, setSeverityFilter] = useState<IssueSeverity | "all">("all");
  const [categoryFilter, setCategoryFilter] = useState<string>("all");
  const [searchQuery, setSearchQuery] = useState("");
  const [syncOutcome, setSyncOutcome] = useState<SyncOutcome | null>(null);
  const [fixAllState, setFixAllState] = useState<{
    running: boolean;
    fixed: number;
    failed: number;
    total: number;
    current: string | null;
  } | null>(null);

  // Auto-sync on mount when we have cached results with a change token
  const didAutoSync = useRef(false);
  useEffect(() => {
    if (didAutoSync.current) return;
    const s = getScanState();
    if (s.status === "done" && s.changePageToken && isAuthenticated()) {
      didAutoSync.current = true;
      syncLibrary()
        .then((outcome) => setSyncOutcome(outcome))
        .catch((err) => {
          setScanError(err instanceof Error ? err.message : String(err));
        });
    }
  }, [scanState.status]);

  // Full rescan (always fetches everything fresh)
  const startScan = useCallback(async () => {
    const rootId = getRootFolderId();
    if (!rootId) return;

    try {
      await doFullScan(rootId, setScanProgress);
    } catch (err) {
      setScanError(err instanceof Error ? err.message : String(err));
    }
  }, []);

  // Clear cached library metadata and start fresh
  const handleClearCache = useCallback(() => {
    resetScan();
    resetHistory();
    setSyncOutcome(null);
    didAutoSync.current = false;
  }, []);

  if (scanState.status === "idle") {
    return (
      <div className="space-y-4">
        <div>
          <h2 className="text-lg font-semibold">Library Scan</h2>
          <p className="mt-1 text-sm text-zinc-400">
            Scan your Google Drive music library to find naming issues and structural problems.
          </p>
        </div>
        {!getRootFolderId() ? (
          <div className="rounded-lg border border-amber-900/50 bg-amber-950/30 p-4">
            <p className="text-sm text-amber-200">
              Choose a music folder above first.
            </p>
          </div>
        ) : (
          <button
            onClick={startScan}
            className="px-5 py-2.5 rounded-md bg-blue-600 text-sm font-medium hover:bg-blue-500 transition-colors"
          >
            Scan Library
          </button>
        )}
      </div>
    );
  }

  if (scanState.status === "scanning") {
    return (
      <div className="space-y-4">
        <h2 className="text-lg font-semibold">Library Scan</h2>
        <div className="flex items-center gap-3 py-4">
          <div className="h-6 w-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin shrink-0" />
          <div>
            <div className="text-sm font-medium">{scanState.progress?.stage}</div>
            {scanState.progress?.detail && (
              <div className="text-xs text-zinc-500">{scanState.progress.detail}</div>
            )}
          </div>
        </div>
      </div>
    );
  }

  if (scanState.status === "error") {
    return (
      <div className="space-y-4">
        <h2 className="text-lg font-semibold">Library Scan</h2>
        <div className="rounded-lg border border-red-900/50 bg-red-950/30 p-4 space-y-2">
          <div className="text-red-400 font-medium">Scan failed</div>
          <p className="text-sm text-zinc-400">{scanState.error}</p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={startScan}
            className="px-4 py-2 rounded-md bg-zinc-800 text-sm hover:bg-zinc-700 transition-colors"
          >
            Retry
          </button>
          <button
            onClick={handleClearCache}
            className="px-4 py-2 rounded-md bg-zinc-800 text-sm text-red-400 hover:bg-zinc-700 transition-colors"
          >
            Clear Cache
          </button>
        </div>
      </div>
    );
  }

  // Done or syncing — show results
  const { result, validation } = scanState;
  if (!result || !validation) return null;

  const { stats, issues } = validation;
  const isSyncing = scanState.status === "syncing";

  const filteredIssues = issues.filter((issue) => {
    if (severityFilter !== "all" && issue.severity !== severityFilter) return false;
    if (categoryFilter !== "all" && issue.category !== categoryFilter) return false;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      const haystack = `${issue.message} ${issue.artistName ?? ""} ${issue.albumName ?? ""} ${issue.fileName ?? ""}`.toLowerCase();
      if (!haystack.includes(q)) return false;
    }
    return true;
  });

  const fixableIssues = filteredIssues.filter(
    (i) => i.category === "non_canonical_filename" && i.fileId != null && i.suggestion != null,
  );
  const showFixAll = categoryFilter === "non_canonical_filename" && fixableIssues.length > 0;

  async function handleFixAll() {
    const toFix = fixableIssues.filter((i) => i.fileId && i.suggestion);
    setFixAllState({ running: true, fixed: 0, failed: 0, total: toFix.length, current: null });
    let fixed = 0;
    let failed = 0;
    for (const issue of toFix) {
      setFixAllState({ running: true, fixed, failed, total: toFix.length, current: issue.fileName ?? issue.fileId! });
      try {
        await renameFile(issue.fileId!, issue.suggestion!);
        renameFileInState(issue.fileId!, issue.suggestion!);
        fixed++;
      } catch {
        failed++;
      }
      setFixAllState({ running: true, fixed, failed, total: toFix.length, current: null });
    }
    setFixAllState({ running: false, fixed, failed, total: toFix.length, current: null });
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold">Library Scan</h2>
          <p className="mt-1 text-sm text-zinc-400">
            {stats.totalArtists} artists, {stats.totalAlbums} albums, {stats.totalTracks} tracks
            {scanState.scannedAt && (
              <span className="ml-2 text-zinc-500">
                &middot; Scanned {formatTimeAgo(scanState.scannedAt)}
              </span>
            )}
          </p>
          {isSyncing && (
            <p className="mt-1 flex items-center gap-1.5 text-xs text-blue-400">
              <span className="h-1.5 w-1.5 rounded-full bg-blue-500 animate-pulse" />
              {scanState.progress?.stage ?? "Syncing..."}
              {scanState.progress?.detail && (
                <span className="text-blue-500">({scanState.progress.detail})</span>
              )}
            </p>
          )}
          {!isSyncing && syncOutcome && (
            <p className="mt-1 text-xs text-zinc-500">
              {syncOutcome.type === "no-changes" && "Library is up to date"}
              {syncOutcome.type === "incremental" && `${syncOutcome.changesApplied} change${syncOutcome.changesApplied !== 1 ? "s" : ""} applied`}
              {syncOutcome.type === "full" && "Full rescan completed"}
            </p>
          )}
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleClearCache}
            className="px-4 py-2 rounded-md bg-zinc-800 text-sm text-red-400 font-medium hover:bg-zinc-700 transition-colors"
            title="Clear cached library metadata and start fresh"
          >
            Clear Cache
          </button>
          <button
            onClick={startScan}
            disabled={isSyncing}
            className="px-4 py-2 rounded-md bg-zinc-800 text-sm font-medium hover:bg-zinc-700 disabled:opacity-50 transition-colors"
          >
            Rescan
          </button>
        </div>
      </div>

      {/* Stats cards */}
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Errors" value={stats.issuesBySeverity.error} color="red" />
        <StatCard label="Warnings" value={stats.issuesBySeverity.warning} color="amber" />
        <StatCard label="Info" value={stats.issuesBySeverity.info} color="blue" />
      </div>

      {issues.length === 0 ? (
        <div className="rounded-lg border border-green-900/50 bg-green-950/30 p-6 text-center">
          <div className="text-green-400 text-lg font-medium">Library looks clean!</div>
          <p className="text-sm text-zinc-400 mt-1">No issues found in your music library structure.</p>
        </div>
      ) : (
        <>
          {/* Filters */}
          <div className="flex flex-wrap items-center gap-3">
            <input
              type="text"
              placeholder="Search issues..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="rounded-md bg-zinc-900 border border-zinc-700 px-3 py-1.5 text-sm text-zinc-100 placeholder-zinc-600 focus:outline-none focus:ring-2 focus:ring-blue-500 w-64"
            />
            <select
              value={severityFilter}
              onChange={(e) => setSeverityFilter(e.target.value as IssueSeverity | "all")}
              className="rounded-md bg-zinc-900 border border-zinc-700 px-3 py-1.5 text-sm text-zinc-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="all">All severities</option>
              <option value="error">Errors ({stats.issuesBySeverity.error})</option>
              <option value="warning">Warnings ({stats.issuesBySeverity.warning})</option>
              <option value="info">Info ({stats.issuesBySeverity.info})</option>
            </select>
            <select
              value={categoryFilter}
              onChange={(e) => setCategoryFilter(e.target.value)}
              className="rounded-md bg-zinc-900 border border-zinc-700 px-3 py-1.5 text-sm text-zinc-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="all">All categories</option>
              {Object.entries(stats.issuesByCategory)
                .sort(([, a], [, b]) => b - a)
                .map(([cat, count]) => (
                  <option key={cat} value={cat}>
                    {formatCategory(cat)} ({count})
                  </option>
                ))}
            </select>
            <span className="text-sm text-zinc-500">
              {filteredIssues.length} of {issues.length} issues
            </span>
            {showFixAll && (
              <button
                onClick={handleFixAll}
                disabled={fixAllState?.running}
                className="ml-auto px-4 py-1.5 rounded-md bg-green-700 text-xs font-medium text-white hover:bg-green-600 disabled:opacity-50 transition-colors"
              >
                {fixAllState?.running
                  ? `Fixing ${fixAllState.fixed + fixAllState.failed}/${fixAllState.total}...`
                  : `Fix All (${fixableIssues.length})`}
              </button>
            )}
          </div>

          {fixAllState && !fixAllState.running && (fixAllState.fixed > 0 || fixAllState.failed > 0) && (
            <div className={`rounded-lg border p-3 text-sm ${
              fixAllState.failed === 0
                ? "border-green-900/50 bg-green-950/30 text-green-400"
                : "border-amber-900/50 bg-amber-950/30 text-amber-400"
            }`}>
              Fixed {fixAllState.fixed} of {fixAllState.total} files
              {fixAllState.failed > 0 && ` (${fixAllState.failed} failed)`}
              <button
                onClick={() => setFixAllState(null)}
                className="ml-3 text-xs text-zinc-500 hover:text-zinc-300"
              >
                Dismiss
              </button>
            </div>
          )}

          {fixAllState?.running && fixAllState.current && (
            <div className="text-xs text-zinc-400 truncate">
              Renaming: {fixAllState.current}
            </div>
          )}

          {/* Issues list */}
          <div className="space-y-2">
            {filteredIssues.slice(0, 200).map((issue) => (
              <IssueRow key={issue.id} issue={issue} />
            ))}
            {filteredIssues.length > 200 && (
              <p className="text-sm text-zinc-500 py-2">
                Showing first 200 of {filteredIssues.length} issues. Use filters to narrow down.
              </p>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function StatCard({ label, value, color }: { label: string; value: number; color: string }) {
  const colorMap: Record<string, string> = {
    red: "border-red-900/50 bg-red-950/30 text-red-400",
    amber: "border-amber-900/50 bg-amber-950/30 text-amber-400",
    blue: "border-blue-900/50 bg-blue-950/30 text-blue-400",
  };
  return (
    <div className={`rounded-lg border p-4 ${colorMap[color]}`}>
      <div className="text-3xl font-bold">{value}</div>
      <div className="text-sm mt-1 opacity-80">{label}</div>
    </div>
  );
}

function IssueRow({ issue }: { issue: LibraryIssue }) {
  const [fixing, setFixing] = useState(false);
  const [fixError, setFixError] = useState<string | null>(null);

  const severityStyles: Record<IssueSeverity, string> = {
    error: "bg-red-500",
    warning: "bg-amber-500",
    info: "bg-blue-500",
  };

  const canFix =
    issue.category === "non_canonical_filename" &&
    issue.fileId != null &&
    issue.suggestion != null;

  async function handleFix(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (!issue.fileId || !issue.suggestion) return;
    setFixing(true);
    setFixError(null);
    try {
      await renameFile(issue.fileId, issue.suggestion);
      renameFileInState(issue.fileId, issue.suggestion);
    } catch (err) {
      setFixError(err instanceof Error ? err.message : "Rename failed");
    } finally {
      setFixing(false);
    }
  }

  const hasLink = issue.artistId != null;

  const content = (
    <>
      <div className={`mt-1 h-2 w-2 shrink-0 rounded-full ${severityStyles[issue.severity]}`} />
      <div className="min-w-0 flex-1">
        <div className="text-sm text-zinc-200">{issue.message}</div>
        {issue.suggestion && (
          <div className="mt-1 text-xs text-green-400 font-mono truncate">
            Rename to: {issue.suggestion}
          </div>
        )}
        {fixError && (
          <div className="mt-1 text-xs text-red-400">{fixError}</div>
        )}
        <div className="mt-1 flex flex-wrap gap-2 text-xs text-zinc-500">
          <span className="rounded bg-zinc-800 px-1.5 py-0.5">{formatCategory(issue.category)}</span>
          {issue.artistName && <span>{issue.artistName}</span>}
          {issue.albumName && <span>/ {issue.albumName}</span>}
        </div>
      </div>
      {canFix && (
        <button
          onClick={handleFix}
          disabled={fixing}
          className="shrink-0 self-center rounded-md bg-green-700 px-3 py-1 text-xs font-medium text-white hover:bg-green-600 disabled:opacity-50 transition-colors"
        >
          {fixing ? "Fixing..." : "Fix"}
        </button>
      )}
    </>
  );

  if (hasLink) {
    return (
      <Link
        to="/library"
        search={{ artistId: issue.artistId!, albumId: issue.albumId }}
        className="flex items-start gap-3 rounded-lg border border-zinc-800 bg-zinc-900/50 px-4 py-3 hover:bg-zinc-800/80 hover:border-zinc-700 transition-colors cursor-pointer"
      >
        {content}
      </Link>
    );
  }

  return (
    <div className="flex items-start gap-3 rounded-lg border border-zinc-800 bg-zinc-900/50 px-4 py-3">
      {content}
    </div>
  );
}

function formatCategory(cat: string): string {
  return cat
    .split("_")
    .map((w) => w[0]!.toUpperCase() + w.slice(1))
    .join(" ");
}

function formatTimeAgo(timestamp: number): string {
  const seconds = Math.floor((Date.now() - timestamp) / 1000);
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}
