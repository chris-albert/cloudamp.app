import { useState, useEffect } from "react";
import { listFolders, type DriveFile } from "@/lib/drive-api";

interface BreadcrumbItem {
  id: string | undefined;
  name: string;
}

interface FolderPickerProps {
  onSelect: (folderId: string, folderName: string) => void;
  onCancel: () => void;
}

export function FolderPicker({ onSelect, onCancel }: FolderPickerProps) {
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
    <div className="rounded-lg border border-zinc-700 bg-zinc-900 overflow-hidden">
      {/* Breadcrumbs */}
      <div className="flex items-center gap-1 px-3 py-2 border-b border-zinc-800 text-sm overflow-x-auto">
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
    </div>
  );
}
