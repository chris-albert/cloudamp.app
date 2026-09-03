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

vi.mock("./playlists-cache", () => ({
  loadPlaylistsCache: vi.fn(),
  savePlaylistsCache: vi.fn(),
  clearPlaylistsCache: vi.fn(),
}));

const FOLDER = { id: "folder-id", name: ".cloudamp", mimeType: "application/vnd.google-apps.folder" };
const FILE = { id: "file-id", name: "playlists.json", mimeType: "application/json" };

let store: typeof import("./playlists-store");
let drive: typeof import("./drive-api");
let auth: typeof import("./google-auth");
let cache: typeof import("./playlists-cache");

// The store keeps module-level state, so each test gets a fresh module graph.
beforeEach(async () => {
  vi.resetModules();
  vi.resetAllMocks();
  drive = await import("./drive-api");
  auth = await import("./google-auth");
  cache = await import("./playlists-cache");
  store = await import("./playlists-store");
  vi.mocked(auth.getRootFolderId).mockReturnValue("root-id");
  vi.mocked(cache.loadPlaylistsCache).mockResolvedValue(null);
});

function mockDriveLookups({ folder, file }: { folder: boolean; file: boolean }) {
  vi.mocked(drive.fetchAllPaginated).mockImplementation(async (query: string) => {
    if (query.includes(".cloudamp")) return folder ? [FOLDER] : [];
    if (query.includes("playlists.json")) return file ? [FILE] : [];
    return [];
  });
}

function remotePlaylist(id: string, name: string, ...fileIds: string[]) {
  return {
    id,
    name,
    createdAt: "t",
    updatedAt: "t",
    tracks: fileIds.map((f) => ({ fileId: f, name: `${f}.mp3`, addedAt: "t" })),
  };
}

function remoteFile(...playlists: ReturnType<typeof remotePlaylist>[]): string {
  return JSON.stringify({ schemaVersion: 1, playlists });
}

async function writtenPlaylists(blob: Blob): Promise<Record<string, string[]>> {
  const parsed = JSON.parse(await blob.text()) as { playlists: { id: string; tracks: { fileId: string }[] }[] };
  return Object.fromEntries(parsed.playlists.map((p) => [p.id, p.tracks.map((t) => t.fileId)]));
}

function lastSavedCache() {
  const calls = vi.mocked(cache.savePlaylistsCache).mock.calls;
  return calls[calls.length - 1]![0];
}

const driveFile = (id: string) => ({ id, name: `${id}.mp3`, mimeType: "audio/mpeg", parents: ["album-1"] });

describe("edits with an existing Playlists File", () => {
  it("GETs the file fresh, replays the op onto the remote list, then PUTs", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile(remotePlaylist("p-remote", "Theirs", "r1")));

    const created = store.createPlaylist("Mine", [driveFile("m1")]);
    // Local state reflects the op before any Drive I/O
    expect(store.getPlaylistsState().playlists.map((p) => p.name)).toEqual(["Mine"]);
    await store.awaitPlaylistWrites();

    expect(drive.createFolder).not.toHaveBeenCalled();
    expect(drive.uploadFile).not.toHaveBeenCalled();
    const getOrder = vi.mocked(drive.fetchFileText).mock.invocationCallOrder[0]!;
    const putOrder = vi.mocked(drive.updateFileContent).mock.invocationCallOrder[0]!;
    expect(getOrder).toBeLessThan(putOrder);

    const [fileId, blob] = vi.mocked(drive.updateFileContent).mock.calls[0]!;
    expect(fileId).toBe("file-id");
    expect(await writtenPlaylists(blob)).toEqual({ "p-remote": ["r1"], [created.id]: ["m1"] });
    expect(store.getPlaylistsState().playlists.map((p) => p.name)).toEqual(["Theirs", "Mine"]);
  });

  it("preserves an edit made by another client between load and write", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile(remotePlaylist("p1", "Mix", "f1")));
    await store.loadPlaylists();

    // Other client adds f2 after our load
    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile(remotePlaylist("p1", "Mix", "f1", "f2")));

    expect(store.addTracksToPlaylist("p1", [driveFile("f3")])).toBe(1);
    await store.awaitPlaylistWrites();

    const [, blob] = vi.mocked(drive.updateFileContent).mock.calls[0]!;
    expect(await writtenPlaylists(blob)).toEqual({ p1: ["f1", "f2", "f3"] });
    expect(store.getPlaylist("p1")!.tracks.map((t) => t.fileId)).toEqual(["f1", "f2", "f3"]);
  });

  it("skips files already in the playlist and writes nothing", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile(remotePlaylist("p1", "Mix", "f1")));
    await store.loadPlaylists();

    expect(store.addTracksToPlaylist("p1", [driveFile("f1")])).toBe(0);
    await store.awaitPlaylistWrites();
    expect(drive.updateFileContent).not.toHaveBeenCalled();
  });

  it("refuses to write over an unknown schemaVersion and drops the queued op", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(JSON.stringify({ schemaVersion: 2, playlists: [] }));

    await expect(store.renamePlaylist("p1", "x")).rejects.toThrow(/schemaVersion/);
    expect(drive.updateFileContent).not.toHaveBeenCalled();
    expect(drive.uploadFile).not.toHaveBeenCalled();
    const persisted = lastSavedCache();
    expect(persisted.pending).toEqual([]);
  });
});

describe("first write", () => {
  it("creates .cloudamp and the Playlists File under the library root", async () => {
    mockDriveLookups({ folder: false, file: false });
    vi.mocked(drive.createFolder).mockResolvedValue("new-folder-id");
    vi.mocked(drive.uploadFile).mockResolvedValue("new-file-id");

    const created = store.createPlaylist("Mine");
    await store.awaitPlaylistWrites();

    expect(drive.createFolder).toHaveBeenCalledWith(".cloudamp", "root-id");
    expect(drive.fetchFileText).not.toHaveBeenCalled();
    expect(drive.updateFileContent).not.toHaveBeenCalled();
    const [name, parentId, blob] = vi.mocked(drive.uploadFile).mock.calls[0]!;
    expect(name).toBe("playlists.json");
    expect(parentId).toBe("new-folder-id");
    expect(await writtenPlaylists(blob)).toEqual({ [created.id]: [] });
  });

  it("a plain load with nothing queued reads without creating anything", async () => {
    mockDriveLookups({ folder: false, file: false });
    await store.loadPlaylists();
    expect(store.getPlaylistsState()).toEqual({ status: "done", playlists: [], error: null });
    expect(drive.createFolder).not.toHaveBeenCalled();
    expect(drive.uploadFile).not.toHaveBeenCalled();
  });
});

describe("cache and offline retry", () => {
  it("hydrates from the cache before Drive answers", async () => {
    vi.mocked(cache.loadPlaylistsCache).mockResolvedValue({
      playlists: [remotePlaylist("p1", "Cached", "f1")],
      pending: [],
    });
    mockDriveLookups({ folder: true, file: true });
    let resolveRemote!: (text: string) => void;
    vi.mocked(drive.fetchFileText).mockReturnValue(new Promise((r) => (resolveRemote = r)));

    const load = store.loadPlaylists();
    await vi.waitFor(() => expect(store.getPlaylistsState().playlists.map((p) => p.name)).toEqual(["Cached"]));

    resolveRemote(remoteFile(remotePlaylist("p1", "Remote", "f1")));
    await load;
    expect(store.getPlaylistsState().playlists.map((p) => p.name)).toEqual(["Remote"]);
  });

  it("keeps a failed op queued and replays it at the next launch", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile(remotePlaylist("p1", "Mix", "f1")));
    await store.loadPlaylists();

    vi.mocked(drive.updateFileContent).mockRejectedValue(new Error("offline"));
    await store.renamePlaylist("p1", "Renamed");

    expect(store.getPlaylist("p1")!.name).toBe("Renamed");
    expect(store.getPlaylistsState().error).toMatch(/offline/);
    const persisted = lastSavedCache();
    expect(persisted.pending).toHaveLength(1);
    expect(persisted.playlists[0]!.name).toBe("Renamed");

    // Next launch: cache holds the queued op, Drive is reachable again
    vi.resetModules();
    vi.resetAllMocks();
    drive = await import("./drive-api");
    auth = await import("./google-auth");
    cache = await import("./playlists-cache");
    store = await import("./playlists-store");
    vi.mocked(auth.getRootFolderId).mockReturnValue("root-id");
    vi.mocked(cache.loadPlaylistsCache).mockResolvedValue(persisted);
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile(remotePlaylist("p1", "Mix", "f1")));

    await store.loadPlaylists();

    const [, blob] = vi.mocked(drive.updateFileContent).mock.calls[0]!;
    const written = JSON.parse(await blob.text()) as { playlists: { name: string }[] };
    expect(written.playlists[0]!.name).toBe("Renamed");
    expect(lastSavedCache().pending).toEqual([]);
  });

  it("a playlist deleted on another device disappears and its queued edits are dropped", async () => {
    mockDriveLookups({ folder: true, file: true });
    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile(remotePlaylist("p1", "Mix", "f1")));
    await store.loadPlaylists();

    vi.mocked(drive.fetchFileText).mockResolvedValue(remoteFile());
    store.addTracksToPlaylist("p1", [driveFile("f2")]);
    await store.awaitPlaylistWrites();

    expect(store.getPlaylistsState().playlists).toEqual([]);
    const [, blob] = vi.mocked(drive.updateFileContent).mock.calls[0]!;
    expect(await writtenPlaylists(blob)).toEqual({});
  });
});
