/**
 * Google OAuth 2.0 PKCE flow for single-page apps.
 * Uses Google's authorization endpoint with code_verifier/code_challenge.
 * Client ID is baked in at build time. Token exchange goes through a
 * server-side proxy (/api/token) which holds the client secret.
 */

const CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string;
const SCOPES = "https://www.googleapis.com/auth/drive";
const AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";

const STORAGE_KEYS = {
  accessToken: "cloudamp_access_token",
  refreshToken: "cloudamp_refresh_token",
  expiresAt: "cloudamp_token_expires_at",
  codeVerifier: "cloudamp_code_verifier",
  rootFolderId: "cloudamp_root_folder_id",
} as const;

function generateRandomString(length: number): string {
  const array = new Uint8Array(length);
  crypto.getRandomValues(array);
  return Array.from(array, (b) => b.toString(36).padStart(2, "0"))
    .join("")
    .slice(0, length);
}

async function sha256(plain: string): Promise<ArrayBuffer> {
  const encoder = new TextEncoder();
  return crypto.subtle.digest("SHA-256", encoder.encode(plain));
}

function base64UrlEncode(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function getStoredCredentials() {
  return {
    rootFolderId: localStorage.getItem(STORAGE_KEYS.rootFolderId) ?? "",
  };
}

export function saveCredentials(rootFolderId: string) {
  localStorage.setItem(STORAGE_KEYS.rootFolderId, rootFolderId);
}

export function getRootFolderId(): string | null {
  return localStorage.getItem(STORAGE_KEYS.rootFolderId);
}

export function getAccessToken(): string | null {
  const token = localStorage.getItem(STORAGE_KEYS.accessToken);
  const expiresAt = localStorage.getItem(STORAGE_KEYS.expiresAt);
  if (!token || !expiresAt) return null;
  if (Date.now() > Number(expiresAt)) return null;
  return token;
}

export function isAuthenticated(): boolean {
  // Consider authenticated if we have a valid access token OR a refresh token
  if (getAccessToken() !== null) return true;
  return localStorage.getItem(STORAGE_KEYS.refreshToken) !== null;
}

export function logout() {
  localStorage.removeItem(STORAGE_KEYS.accessToken);
  localStorage.removeItem(STORAGE_KEYS.refreshToken);
  localStorage.removeItem(STORAGE_KEYS.expiresAt);
}

export async function startAuthFlow() {
  if (!CLIENT_ID) throw new Error("VITE_GOOGLE_CLIENT_ID not configured");

  const codeVerifier = generateRandomString(64);
  localStorage.setItem(STORAGE_KEYS.codeVerifier, codeVerifier);

  const challengeBuffer = await sha256(codeVerifier);
  const codeChallenge = base64UrlEncode(challengeBuffer);

  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: `${window.location.origin}${import.meta.env.BASE_URL}callback`,
    response_type: "code",
    scope: SCOPES,
    code_challenge: codeChallenge,
    code_challenge_method: "S256",
    access_type: "offline",
    prompt: "consent",
  });

  window.location.href = `${AUTH_URL}?${params.toString()}`;
}

export async function handleCallback(code: string): Promise<void> {
  const codeVerifier = localStorage.getItem(STORAGE_KEYS.codeVerifier);
  if (!codeVerifier) throw new Error("No code verifier found");

  const response = await fetch("/api/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      grant_type: "authorization_code",
      code,
      code_verifier: codeVerifier,
      redirect_uri: `${window.location.origin}${import.meta.env.BASE_URL}callback`,
    }),
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`Token exchange failed: ${err}`);
  }

  const data = await response.json();
  localStorage.setItem(STORAGE_KEYS.accessToken, data.access_token);
  if (data.refresh_token) {
    localStorage.setItem(STORAGE_KEYS.refreshToken, data.refresh_token);
  }
  const expiresAt = Date.now() + data.expires_in * 1000;
  localStorage.setItem(STORAGE_KEYS.expiresAt, String(expiresAt));
  localStorage.removeItem(STORAGE_KEYS.codeVerifier);
}

export async function refreshAccessToken(): Promise<string> {
  const refreshToken = localStorage.getItem(STORAGE_KEYS.refreshToken);
  if (!refreshToken) throw new Error("No refresh token available");

  const response = await fetch("/api/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      grant_type: "refresh_token",
      refresh_token: refreshToken,
    }),
  });

  if (!response.ok) throw new Error("Token refresh failed");

  const data = await response.json();
  localStorage.setItem(STORAGE_KEYS.accessToken, data.access_token);
  const expiresAt = Date.now() + data.expires_in * 1000;
  localStorage.setItem(STORAGE_KEYS.expiresAt, String(expiresAt));
  return data.access_token;
}
