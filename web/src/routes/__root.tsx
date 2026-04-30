import {
  createRootRoute,
  createRoute,
  Link,
  Outlet,
} from "@tanstack/react-router";
import { useSyncExternalStore } from "react";
import { isAuthenticated } from "@/lib/google-auth";
import { getScanState, subscribeScanState } from "@/lib/scan-store";
import { CloudAmpLogo } from "@/components/CloudAmpLogo";
import { SettingsPage } from "@/routes/settings";
import { CallbackPage } from "@/routes/callback";
import { DashboardPage } from "@/routes/dashboard";
import { LibraryPage } from "@/routes/library";
import { HistoryPage } from "@/routes/history";

// ── Root layout ───────────────────────────────────────────────────────

function RootLayout() {
  const authed = isAuthenticated();
  const scanState = useSyncExternalStore(subscribeScanState, getScanState);

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 antialiased">
      <header className="sticky top-0 z-50 border-b border-zinc-800/60 bg-zinc-950/70 backdrop-blur-md supports-[backdrop-filter]:bg-zinc-950/60">
        <div className="max-w-7xl mx-auto px-4 h-11 flex items-center gap-3">
          <Link
            to="/"
            className="flex items-center gap-2 text-white -mx-1.5 px-1.5 py-1 rounded-md hover:bg-zinc-800/40 transition-colors"
          >
            <CloudAmpLogo className="h-6 w-6 shrink-0" />
            <span className="text-[15px] font-semibold tracking-tight">CloudAmp</span>
          </Link>
          {(authed || scanState.status === "done" || scanState.status === "syncing") && (
            <nav className="flex items-center gap-0.5 ml-2">
              <NavLink to="/">Dashboard</NavLink>
              <NavLink to="/library">Library</NavLink>
              <NavLink to="/history">History</NavLink>
              <NavLink to="/settings">Settings</NavLink>
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
      <main className="max-w-7xl mx-auto px-4 py-6">
        <Outlet />
      </main>
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
      activeOptions={{ exact: to === "/" }}
    >
      {children}
    </Link>
  );
}

// ── Route tree ────────────────────────────────────────────────────────

const rootRoute = createRootRoute({
  component: RootLayout,
});

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  component: DashboardPage,
});

const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/settings",
  component: SettingsPage,
});

const callbackRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/callback",
  component: CallbackPage,
});

const historyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/history",
  component: HistoryPage,
});

const libraryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/library",
  component: LibraryPage,
  validateSearch: (search: Record<string, unknown>) => ({
    artistId: (search.artistId as string) || undefined,
    albumId: (search.albumId as string) || undefined,
  }),
});

export const routeTree = rootRoute.addChildren([
  indexRoute,
  settingsRoute,
  callbackRoute,
  libraryRoute,
  historyRoute,
]);

export { rootRoute };
