import { describe, it, expect } from "vitest";
import { decideAutoScan, type ScanState } from "./scan-store";

const base: ScanState = {
  status: "idle",
  progress: null,
  result: null,
  validation: null,
  error: null,
  scannedAt: null,
  changePageToken: null,
  hydrated: false,
};

describe("decideAutoScan", () => {
  it("waits while the cache hydration attempt is still pending", () => {
    // Regression: the library page used to see status "idle" before the
    // IndexedDB cache had loaded and kick off a full rescan on every load.
    expect(decideAutoScan({ ...base, status: "idle", hydrated: false })).toBe("wait");
  });

  it("full-scans when hydration settled with no cached library", () => {
    expect(decideAutoScan({ ...base, status: "idle", hydrated: true })).toBe("full-scan");
  });

  it("incrementally syncs when a cached library with a change token was hydrated", () => {
    expect(
      decideAutoScan({ ...base, status: "done", changePageToken: "token", hydrated: true }),
    ).toBe("sync");
  });

  it("does nothing for a cached library without a change token", () => {
    expect(decideAutoScan({ ...base, status: "done", hydrated: true })).toBe("none");
  });

  it("does nothing while a scan is already running", () => {
    expect(decideAutoScan({ ...base, status: "scanning", hydrated: true })).toBe("none");
  });
});
