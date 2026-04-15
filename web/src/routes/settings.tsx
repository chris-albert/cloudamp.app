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

export function SettingsPage() {
  const navigate = useNavigate();
  const [clientId, setClientId] = useState("");
  const [clientSecret, setClientSecret] = useState("");
  const [rootFolderId, setRootFolderId] = useState("");
  const [saved, setSaved] = useState(false);
  const [userInfo, setUserInfo] = useState<DriveAbout | null>(null);
  const authed = isAuthenticated();

  useEffect(() => {
    const creds = getStoredCredentials();
    setClientId(creds.clientId);
    setClientSecret(creds.clientSecret);
    setRootFolderId(creds.rootFolderId);
  }, []);

  useEffect(() => {
    if (authed) {
      getAbout().then(setUserInfo).catch(() => {});
    }
  }, [authed]);

  function handleSave() {
    saveCredentials(clientId, clientSecret, rootFolderId);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  }

  function handleConnect() {
    saveCredentials(clientId, clientSecret, rootFolderId);
    startAuthFlow();
  }

  function handleLogout() {
    logout();
    setUserInfo(null);
    navigate({ to: "/settings" });
  }

  return (
    <div className="max-w-xl space-y-8">
      <div>
        <h1 className="text-2xl font-bold">Settings</h1>
        <p className="mt-1 text-sm text-zinc-400">
          Configure your Google Drive connection. You need a Google Cloud OAuth
          client ID with the Drive API enabled.
        </p>
      </div>

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

      <div className="space-y-4">
        <Field label="Client ID" value={clientId} onChange={setClientId} placeholder="xxxx.apps.googleusercontent.com" />
        <Field label="Client Secret" value={clientSecret} onChange={setClientSecret} placeholder="GOCSPX-..." type="password" />
        <Field
          label="Root Folder ID"
          value={rootFolderId}
          onChange={setRootFolderId}
          placeholder="The Google Drive folder ID containing your music"
          hint="Open the folder in Google Drive and copy the ID from the URL."
        />
      </div>

      <div className="flex items-center gap-3">
        <button
          onClick={handleSave}
          className="px-4 py-2 rounded-md bg-zinc-800 text-sm font-medium hover:bg-zinc-700 transition-colors"
        >
          {saved ? "Saved!" : "Save"}
        </button>
        {!authed && clientId && clientSecret && (
          <button
            onClick={handleConnect}
            className="px-4 py-2 rounded-md bg-blue-600 text-sm font-medium hover:bg-blue-500 transition-colors"
          >
            Connect to Google Drive
          </button>
        )}
      </div>
    </div>
  );
}

function Field({
  label,
  value,
  onChange,
  placeholder,
  type = "text",
  hint,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  type?: string;
  hint?: string;
}) {
  return (
    <div>
      <label className="block text-sm font-medium text-zinc-300 mb-1">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded-md bg-zinc-900 border border-zinc-700 px-3 py-2 text-sm text-zinc-100 placeholder-zinc-600 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
      />
      {hint && <p className="mt-1 text-xs text-zinc-500">{hint}</p>}
    </div>
  );
}
