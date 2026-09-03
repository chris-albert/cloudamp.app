import {
  createRootRoute,
  createRoute,
  Link,
  Navigate,
  Outlet,
} from "@tanstack/react-router";
import { useSyncExternalStore } from "react";
import { isAuthenticated } from "@/lib/google-auth";
import { getScanState, subscribeScanState } from "@/lib/scan-store";
import { getPlayerState, subscribePlayerState, getCurrentTrack } from "@/lib/player-store";
import { CloudAmpLogo } from "@/components/CloudAmpLogo";
import { AudioPlayer } from "@/components/AudioPlayer";
import { SettingsPage } from "@/routes/settings";
import { CallbackPage } from "@/routes/callback";
import { LibraryPage } from "@/routes/library";
import { HistoryPage } from "@/routes/history";
import { PlaylistsPage } from "@/routes/playlists";
import { VisualizerPage } from "@/routes/visualizer";
import { LandingPage } from "@/routes/landing";

// ── Root layout (pass-through) ───────────────────────────────────────
function RootShell() {
  return <Outlet />;
}

// ── App layout (header + nav + player) ───────────────────────────────
function AppLayout() {
  const authed = isAuthenticated();
  const scanState = useSyncExternalStore(subscribeScanState, getScanState);
  // Subscribe to player state changes so the layout re-renders when the player opens/closes
  useSyncExternalStore(subscribePlayerState, getPlayerState);
  const hasPlayer = getCurrentTrack() !== null;

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 antialiased">
      <header className="sticky top-0 z-50 border-b border-zinc-800/60 bg-zinc-950/70 backdrop-blur-md supports-[backdrop-filter]:bg-zinc-950/60">
        <div className="max-w-7xl mx-auto px-4 h-11 flex items-center gap-3">
          <Link
            to="/app/library"
            search={{ artistId: undefined, albumId: undefined }}
            className="flex items-center gap-2 text-white -mx-1.5 px-1.5 py-1 rounded-md hover:bg-zinc-800/40 transition-colors"
          >
            <CloudAmpLogo className="h-6 w-6 shrink-0" />
            <span className="text-[15px] font-semibold tracking-tight">CloudAmp</span>
          </Link>
          {(authed || scanState.status === "done" || scanState.status === "syncing") && (
            <nav className="flex items-center gap-0.5 ml-2">
              <NavLink to="/app/library">Library</NavLink>
              <NavLink to="/app/playlists">Playlists</NavLink>
              <NavLink to="/app/visualizer">Visualizer</NavLink>
              <NavLink to="/app/history">History</NavLink>
              <NavLink to="/app/settings">Settings</NavLink>
            </nav>
          )}
          {(scanState.status === "scanning" || scanState.status === "syncing") && (
            <div className="ml-auto flex items-center gap-2 text-xs text-zinc-400 truncate">
              <span className="relative flex h-1.5 w-1.5 shrink-0">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-500 opacity-75" />
                <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-blue-500" />
              </span>
              <span className="truncate">{scanState.progress?.stage}</span>
              {scanState.progress?.detail && (
                <span className="text-zinc-500 truncate hidden sm:inline">({scanState.progress.detail})</span>
              )}
            </div>
          )}
        </div>
      </header>
      <main className={`max-w-7xl mx-auto px-4 py-6 ${hasPlayer ? "pb-24" : ""}`}>
        <Outlet />
      </main>
      <AudioPlayer />
    </div>
  );
}

const navLinkBase =
  "px-2.5 py-1 rounded-md text-[13px] font-medium transition-colors";

function NavLink({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <Link
      to={to}
      className={`${navLinkBase} text-zinc-400 hover:text-white hover:bg-zinc-800/60`}
      activeProps={{ className: `${navLinkBase} text-white bg-zinc-800/80 shadow-sm shadow-black/20` }}
    >
      {children}
    </Link>
  );
}

// ── Redirect: /app → /app/library ────────────────────────────────────
function AppIndexRedirect() {
  return <Navigate to="/app/library" search={{ artistId: undefined, albumId: undefined }} />;
}

// ── Route tree ────────────────────────────────────────────────────────

const rootRoute = createRootRoute({
  component: RootShell,
});

// Landing page at /
const landingRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  component: LandingPage,
});

// OAuth callback stays at root level (used by Google OAuth flow)
const callbackRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/callback",
  component: CallbackPage,
});

// App layout route at /app
const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/app",
  component: AppLayout,
});

const appIndexRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/",
  component: AppIndexRedirect,
});

const settingsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/settings",
  component: SettingsPage,
});

const historyRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/history",
  component: HistoryPage,
});

const libraryRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/library",
  component: LibraryPage,
  validateSearch: (search: Record<string, unknown>) => ({
    artistId: (search.artistId as string) || undefined,
    albumId: (search.albumId as string) || undefined,
  }),
});

const playlistsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/playlists",
  component: PlaylistsPage,
  validateSearch: (search: Record<string, unknown>) => ({
    playlistId: (search.playlistId as string) || undefined,
  }),
});

const visualizerRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/visualizer",
  component: VisualizerPage,
});

// Legacy redirects: /library → /app/library, etc.
function RedirectToAppLibrary() {
  return <Navigate to="/app/library" search={{ artistId: undefined, albumId: undefined }} />;
}
function RedirectToAppSettings() {
  return <Navigate to="/app/settings" />;
}
function RedirectToAppHistory() {
  return <Navigate to="/app/history" />;
}
function RedirectToAppVisualizer() {
  return <Navigate to="/app/visualizer" />;
}

const legacyLibraryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/library",
  component: RedirectToAppLibrary,
});
const legacySettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/settings",
  component: RedirectToAppSettings,
});
const legacyHistoryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/history",
  component: RedirectToAppHistory,
});
const legacyVisualizerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/visualizer",
  component: RedirectToAppVisualizer,
});

export const routeTree = rootRoute.addChildren([
  landingRoute,
  callbackRoute,
  appRoute.addChildren([
    appIndexRoute,
    settingsRoute,
    libraryRoute,
    playlistsRoute,
    historyRoute,
    visualizerRoute,
  ]),
  legacyLibraryRoute,
  legacySettingsRoute,
  legacyHistoryRoute,
  legacyVisualizerRoute,
]);

export { rootRoute };
