import { useState, useEffect } from "react";
import { listFolders, searchFolders, type DriveFile, type SearchResult } from "@/lib/drive-api";

interface BreadcrumbItem {
  id: string | undefined;
  name: string;
}

interface FolderPickerProps {
  onSelect: (folderId: string, folderName: string) => void;
  onCancel: () => void;
}

type Mode = "choose" | "browse" | "search";

export function FolderPicker({ onSelect, onCancel }: FolderPickerProps) {
  const [mode, setMode] = useState<Mode>("choose");

  return (
    <div className="rounded-lg border border-zinc-700 bg-zinc-900 overflow-hidden">
      {mode === "choose" && (
        <ModeChooser
          onBrowse={() => setMode("browse")}
          onSearch={() => setMode("search")}
          onCancel={onCancel}
        />
      )}
      {mode === "browse" && (
        <BrowseMode
          onSelect={onSelect}
          onBack={() => setMode("choose")}
          onCancel={onCancel}
        />
      )}
      {mode === "search" && (
        <SearchMode
          onSelect={onSelect}
          onBack={() => setMode("choose")}
          onCancel={onCancel}
        />
      )}
    </div>
  );
}

function ModeChooser({
  onBrowse,
  onSearch,
  onCancel,
}: {
  onBrowse: () => void;
  onSearch: () => void;
  onCancel: () => void;
}) {
  return (
    <>
      <div className="px-3 py-2 border-b border-zinc-800 text-sm font-medium text-zinc-100">
        Find music folder
      </div>
      <div className="p-2 space-y-1">
        <button
          onClick={onBrowse}
          className="w-full text-left px-3 py-2.5 text-sm text-zinc-300 hover:bg-zinc-800 rounded-md flex items-center gap-3 transition-colors"
        >
          <svg className="w-4 h-4 text-zinc-500 shrink-0" fill="currentColor" viewBox="0 0 20 20">
            <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
          </svg>
          <div>
            <div>Browse My Drive</div>
            <div className="text-xs text-zinc-500">Navigate folder by folder</div>
          </div>
        </button>
        <button
          onClick={onSearch}
          className="w-full text-left px-3 py-2.5 text-sm text-zinc-300 hover:bg-zinc-800 rounded-md flex items-center gap-3 transition-colors"
        >
          <svg className="w-4 h-4 text-zinc-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          <div>
            <div>Search All Drive</div>
            <div className="text-xs text-zinc-500">Find folders across My Drive, Computers, etc.</div>
          </div>
        </button>
      </div>
      <div className="flex items-center justify-end px-3 py-2 border-t border-zinc-800">
        <button
          onClick={onCancel}
          className="px-3 py-1.5 text-sm rounded-md border border-zinc-700 text-zinc-400 hover:text-white hover:border-zinc-500 transition-colors"
        >
          Cancel
        </button>
      </div>
    </>
  );
}

function BrowseMode({
  onSelect,
  onBack,
  onCancel,
}: {
  onSelect: (folderId: string, folderName: string) => void;
  onBack: () => void;
  onCancel: () => void;
}) {
  const [folders, setFolders] = useState<DriveFile[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [breadcrumbs, setBreadcrumbs] = useState<BreadcrumbItem[]>([
    { id: undefined, name: "My Drive" },
  ]);

  const currentCrumb = breadcrumbs[breadcrumbs.length - 1]!;
  const currentParentId = currentCrumb.id;

  useEffect(() => {
    setLoading(true);
    setError(null);
    listFolders(currentParentId)
      .then(setFolders)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [currentParentId]);

  function navigateInto(folder: DriveFile) {
    setBreadcrumbs((prev) => [...prev, { id: folder.id, name: folder.name }]);
  }

  function navigateTo(index: number) {
    setBreadcrumbs((prev) => prev.slice(0, index + 1));
  }

  function handleSelect() {
    if (currentCrumb.id) {
      onSelect(currentCrumb.id, currentCrumb.name);
    }
  }

  return (
    <>
      {/* Breadcrumbs */}
      <div className="flex items-center gap-1 px-3 py-2 border-b border-zinc-800 text-sm overflow-x-auto">
        <button
          onClick={onBack}
          className="text-zinc-500 hover:text-white transition-colors shrink-0 mr-1"
          title="Back"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
        </button>
        {breadcrumbs.map((crumb, i) => (
          <span key={i} className="flex items-center gap-1 shrink-0">
            {i > 0 && <span className="text-zinc-600">/</span>}
            <button
              onClick={() => navigateTo(i)}
              className={`hover:text-white transition-colors ${
                i === breadcrumbs.length - 1 ? "text-zinc-100 font-medium" : "text-zinc-400"
              }`}
            >
              {crumb.name}
            </button>
          </span>
        ))}
      </div>

      {/* Folder list */}
      <div className="max-h-64 overflow-y-auto">
        {loading && (
          <div className="px-3 py-8 text-center text-sm text-zinc-500">Loading...</div>
        )}
        {error && (
          <div className="px-3 py-8 text-center text-sm text-red-400">{error}</div>
        )}
        {!loading && !error && folders.length === 0 && (
          <div className="px-3 py-8 text-center text-sm text-zinc-500">No subfolders</div>
        )}
        {!loading && !error && folders.map((folder) => (
          <button
            key={folder.id}
            onClick={() => navigateInto(folder)}
            className="w-full text-left px-3 py-2 text-sm text-zinc-300 hover:bg-zinc-800 flex items-center gap-2 transition-colors"
          >
            <svg className="w-4 h-4 text-zinc-500 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
            </svg>
            {folder.name}
          </button>
        ))}
      </div>

      {/* Actions */}
      <div className="flex items-center justify-between px-3 py-2 border-t border-zinc-800">
        <button
          onClick={onCancel}
          className="px-3 py-1.5 text-sm rounded-md border border-zinc-700 text-zinc-400 hover:text-white hover:border-zinc-500 transition-colors"
        >
          Cancel
        </button>
        <button
          onClick={handleSelect}
          disabled={!currentParentId}
          className="px-3 py-1.5 text-sm rounded-md bg-blue-600 text-white font-medium hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          Select this folder
        </button>
      </div>
    </>
  );
}

function SearchMode({
  onSelect,
  onBack,
  onCancel,
}: {
  onSelect: (folderId: string, folderName: string) => void;
  onBack: () => void;
  onCancel: () => void;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searched, setSearched] = useState(false);

  function handleSearch() {
    const trimmed = query.trim();
    if (!trimmed) return;
    setLoading(true);
    setError(null);
    setSearched(true);
    searchFolders(trimmed)
      .then(setResults)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }

  return (
    <>
      {/* Search header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-zinc-800">
        <button
          onClick={onBack}
          className="text-zinc-500 hover:text-white transition-colors shrink-0"
          title="Back"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
        </button>
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && handleSearch()}
          placeholder="Search folder name, e.g. Music"
          autoFocus
          className="flex-1 bg-transparent text-sm text-zinc-100 placeholder-zinc-600 outline-none"
        />
        <button
          onClick={handleSearch}
          disabled={!query.trim() || loading}
          className="px-2.5 py-1 text-sm rounded-md bg-blue-600 text-white font-medium hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          Search
        </button>
      </div>

      {/* Results */}
      <div className="max-h-64 overflow-y-auto">
        {loading && (
          <div className="px-3 py-8 text-center text-sm text-zinc-500">Searching...</div>
        )}
        {error && (
          <div className="px-3 py-8 text-center text-sm text-red-400">{error}</div>
        )}
        {!loading && !error && searched && results.length === 0 && (
          <div className="px-3 py-8 text-center text-sm text-zinc-500">No folders found</div>
        )}
        {!loading && !error && results.map((folder) => (
          <button
            key={folder.id}
            onClick={() => onSelect(folder.id, folder.name)}
            className="w-full text-left px-3 py-2 text-sm text-zinc-300 hover:bg-zinc-800 flex items-center gap-2 transition-colors"
          >
            <svg className="w-4 h-4 text-zinc-500 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z" />
            </svg>
            <div className="min-w-0">
              <div className="truncate">{folder.name}</div>
              {folder.parentName && (
                <div className="text-xs text-zinc-500 truncate">in {folder.parentName}</div>
              )}
            </div>
          </button>
        ))}
      </div>

      {/* Actions */}
      <div className="flex items-center justify-end px-3 py-2 border-t border-zinc-800">
        <button
          onClick={onCancel}
          className="px-3 py-1.5 text-sm rounded-md border border-zinc-700 text-zinc-400 hover:text-white hover:border-zinc-500 transition-colors"
        >
          Cancel
        </button>
      </div>
    </>
  );
}
