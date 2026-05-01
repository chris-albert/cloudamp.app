import { useState, useEffect } from "react";
import { useNavigate } from "@tanstack/react-router";
import {
  getStoredCredentials,
  saveCredentials,
  isAuthenticated,
  startAuthFlow,
  logout,
} from "@/lib/google-auth";
import { getAbout, type DriveAbout } from "@/lib/drive-api";
import { FolderPicker } from "@/components/FolderPicker";

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
    <div className="max-w-xl space-y-8">
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
    </div>
  );
}
