import { describe, it, expect, vi, beforeEach } from "vitest";

vi.mock("./drive-api", () => ({
  fetchAllPaginated: vi.fn(),
  fetchFileText: vi.fn(),
  createFolder: vi.fn(),
  uploadFile: vi.fn(),
  updateFileContent: vi.fn(),
}));

vi.mock("./google-auth", () => ({
  getRootFolderId: vi.fn(),
}));

const FOLDER = { id: "folder-id", name: ".cloudamp", mimeType: "application/vnd.google-apps.folder" };
const FILE = { id: "file-id", name: "favorites.json", mimeType: "application/json" };

let store: typeof import("./favorites-store");
let drive: typeof import("./drive-api");
let auth: typeof import("./google-auth");

// The store keeps module-level state, so each test gets a fresh module graph.
// Mock instances survive resetModules, so their calls/implementations need
// an explicit reset too.
beforeEach(async () => {
  vi.resetModules();
  vi.resetAllMocks();
  drive = await import("./drive-api");
  auth = await import("./google-auth");
  store = await import("./favorites-store");
  vi.mocked(auth.getRootFolderId).mockReturnValue("root-id");
});

function mockDriveLookups({ folder, file }: { folder: boolean; file: boolean }) {
  vi.mocked(drive.fetchAllPaginated).mockImplementation(async (query: string) => {
    if (query.includes(".cloudamp")) return folder ? [FOLDER] : [];
    if (query.includes("favorites.json")) return file ? [FILE] : [];
    return [];
  });
}

function remoteFile(...favorites: { albumId: string; favoritedAt: string }[]): string {
  return JSON.stringify({ schemaVersion: 1, favorites });
}

async function writtenContent(blob: Blob): Promise<unknown> {
  return JSON.parse(await blob.text());
}

describe("toggleFavorite with an existing Favorites File", () => {
  it("GETs the file fresh, merges the toggle onto the remote list, then PUTs", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(
      remoteFile({ albumId: "album-a", favoritedAt: "2026-07-01T00:00:00Z" }),
    );

    await store.toggleFavorite("album-b");

    expect(drive.createFolder).not.toHaveBeenCalled();
    expect(drive.uploadFile).not.toHaveBeenCalled();
    expect(drive.fetchFileText).toHaveBeenCalledWith("file-id");

    // GET before PUT
    const getOrder = vi.mocked(drive.fetchFileText).mock.invocationCallOrder[0]!;
    const putOrder = vi.mocked(drive.updateFileContent).mock.invocationCallOrder[0]!;
    expect(getOrder).toBeLessThan(putOrder);

    const [fileId, blob] = vi.mocked(drive.updateFileContent).mock.calls[0]!;
    expect(fileId).toBe("file-id");
    const written = (await writtenContent(blob)) as { favorites: { albumId: string }[] };
    expect(written.favorites.map((f) => f.albumId)).toEqual(["album-a", "album-b"]);
    expect(store.isAlbumFavorite("album-b")).toBe(true);
  });

  it("preserves a favorite added by another client between app load and the toggle", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(
      remoteFile({ albumId: "album-a", favoritedAt: "2026-07-01T00:00:00Z" }),
    );
    await store.loadFavorites();
    expect(store.getFavoritesState().favorites.map((f) => f.albumId)).toEqual(["album-a"]);

    // Another client favorites album-b after our load
    vi.mocked(drive.fetchFileText).mockResolvedValue(
      remoteFile(
        { albumId: "album-a", favoritedAt: "2026-07-01T00:00:00Z" },
        { albumId: "album-b", favoritedAt: "2026-07-20T00:00:00Z" },
      ),
    );

    await store.toggleFavorite("album-c");

    const [, blob] = vi.mocked(drive.updateFileContent).mock.calls[0]!;
    const written = (await writtenContent(blob)) as { favorites: { albumId: string }[] };
    expect(written.favorites.map((f) => f.albumId)).toEqual(["album-a", "album-b", "album-c"]);
    expect(store.getFavoritesState().favorites.map((f) => f.albumId)).toEqual([
      "album-a",
      "album-b",
      "album-c",
    ]);
  });

  it("refuses to write over an unknown schemaVersion and reverts the optimistic state", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(JSON.stringify({ schemaVersion: 2, favorites: [] }));

    await expect(store.toggleFavorite("album-a")).rejects.toThrow(/schemaVersion/);

    expect(drive.updateFileContent).not.toHaveBeenCalled();
    expect(drive.uploadFile).not.toHaveBeenCalled();
    expect(store.isAlbumFavorite("album-a")).toBe(false);
  });

  it("reverts the optimistic state when the PUT fails", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile());
    vi.mocked(drive.updateFileContent).mockRejectedValue(new Error("network down"));

    await expect(store.toggleFavorite("album-a")).rejects.toThrow("network down");

    expect(store.isAlbumFavorite("album-a")).toBe(false);
    expect(store.getFavoritesState().error).toBe("network down");
  });
});

describe("toggleFavorite when .cloudamp and the Favorites File don't exist", () => {
  it("creates the folder and file, writing the bootstrapped list", async () => {
    mockDriveLookups({ folder: false, file: false });
    vi.mocked(drive.createFolder).mockResolvedValue("new-folder-id");
    vi.mocked(drive.uploadFile).mockResolvedValue("new-file-id");

    await store.toggleFavorite("album-a");

    expect(drive.createFolder).toHaveBeenCalledWith(".cloudamp", "root-id");
    expect(drive.fetchFileText).not.toHaveBeenCalled();
    expect(drive.updateFileContent).not.toHaveBeenCalled();

    const [name, parentId, blob] = vi.mocked(drive.uploadFile).mock.calls[0]!;
    expect(name).toBe("favorites.json");
    expect(parentId).toBe("new-folder-id");
    const written = (await writtenContent(blob)) as { schemaVersion: number; favorites: { albumId: string }[] };
    expect(written.schemaVersion).toBe(1);
    expect(written.favorites.map((f) => f.albumId)).toEqual(["album-a"]);
  });

  it("reuses the existing folder when only the file is missing", async () => {
    mockDriveLookups({ folder: true, file: false });
    vi.mocked(drive.uploadFile).mockResolvedValue("new-file-id");

    await store.toggleFavorite("album-a");

    expect(drive.createFolder).not.toHaveBeenCalled();
    expect(vi.mocked(drive.uploadFile).mock.calls[0]![1]).toBe("folder-id");
  });
});

describe("loadFavorites", () => {
  it("resolves to an empty done state when the Favorites File doesn't exist yet", async () => {
    mockDriveLookups({ folder: false, file: false });

    await store.loadFavorites();

    expect(store.getFavoritesState()).toEqual({ status: "done", favorites: [], error: null });
  });

  it("loads the remote list so hearts reflect Drive state after a reload", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(
      remoteFile({ albumId: "album-a", favoritedAt: "2026-07-01T00:00:00Z" }),
    );

    await store.loadFavorites();

    expect(store.isAlbumFavorite("album-a")).toBe(true);
    expect(store.isAlbumFavorite("album-b")).toBe(false);
  });
});
