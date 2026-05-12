import { useEffect, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { handleCallback } from "@/lib/google-auth";

export function CallbackPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const state = params.get("state");
    const errorParam = params.get("error");

    if (errorParam) {
      setError(`Google returned an error: ${errorParam}`);
      return;
    }

    if (!code) {
      setError("No authorization code received");
      return;
    }

    handleCallback(code, state)
      .then(() => navigate({ to: "/" }))
      .catch((err) => setError(err instanceof Error ? err.message : String(err)));
  }, [navigate]);

  if (error) {
    return (
      <div className="max-w-md mx-auto mt-24 text-center space-y-4">
        <div className="text-red-400 text-lg font-medium">Authentication Failed</div>
        <p className="text-sm text-zinc-400">{error}</p>
        <button
          onClick={() => navigate({ to: "/settings" })}
          className="px-4 py-2 rounded-md bg-zinc-800 text-sm hover:bg-zinc-700 transition-colors"
        >
          Back to Settings
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-md mx-auto mt-24 text-center space-y-4">
      <div className="h-6 w-6 mx-auto border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
      <p className="text-sm text-zinc-400">Completing authentication...</p>
    </div>
  );
}
