import {
  createRootRoute,
  createRoute,
  Link,
  Outlet,
} from "@tanstack/react-router";
import { useSyncExternalStore } from "react";
import { isAuthenticated } from "@/lib/google-auth";
import { getScanState, subscribeScanState } from "@/lib/scan-store";
import { SettingsPage } from "@/routes/settings";
import { CallbackPage } from "@/routes/callback";
import { DashboardPage } from "@/routes/dashboard";
import { LibraryPage } from "@/routes/library";

// ── Root layout ───────────────────────────────────────────────────────

function RootLayout() {
  const authed = isAuthenticated();
  const scanState = useSyncExternalStore(subscribeScanState, getScanState);

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100">
      <header className="border-b border-zinc-800 bg-zinc-900/80 backdrop-blur-sm sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 h-14 flex items-center gap-6">
          <Link to="/" className="text-lg font-semibold text-white tracking-tight">
            CloudAmp
          </Link>
          {(authed || scanState.status === "done") && (
            <nav className="flex items-center gap-1">
              <NavLink to="/">Dashboard</NavLink>
              <NavLink to="/library">Library</NavLink>
              <NavLink to="/settings">Settings</NavLink>
            </nav>
          )}
          {scanState.status === "scanning" && (
            <div className="ml-auto flex items-center gap-2 text-sm text-zinc-400">
              <div className="h-2 w-2 rounded-full bg-blue-500 animate-pulse" />
              {scanState.progress?.stage}
              {scanState.progress?.detail && (
                <span className="text-zinc-500">({scanState.progress.detail})</span>
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

function NavLink({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <Link
      to={to}
      className="px-3 py-1.5 rounded-md text-sm font-medium text-zinc-400 hover:text-white hover:bg-zinc-800 transition-colors"
      activeProps={{ className: "px-3 py-1.5 rounded-md text-sm font-medium text-white bg-zinc-800" }}
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
]);

export { rootRoute };
