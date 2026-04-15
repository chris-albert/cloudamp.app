/**
 * Google OAuth 2.0 PKCE flow for single-page apps.
 * Uses Google's authorization endpoint with code_verifier/code_challenge.
 */

const SCOPES = "https://www.googleapis.com/auth/drive";
const AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN_URL = "https://oauth2.googleapis.com/token";

const STORAGE_KEYS = {
  accessToken: "cloudamp_access_token",
  refreshToken: "cloudamp_refresh_token",
  expiresAt: "cloudamp_token_expires_at",
  clientId: "cloudamp_client_id",
  clientSecret: "cloudamp_client_secret",
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
    clientId: localStorage.getItem(STORAGE_KEYS.clientId) ?? "",
    clientSecret: localStorage.getItem(STORAGE_KEYS.clientSecret) ?? "",
    rootFolderId: localStorage.getItem(STORAGE_KEYS.rootFolderId) ?? "",
  };
}

export function saveCredentials(clientId: string, clientSecret: string, rootFolderId: string) {
  localStorage.setItem(STORAGE_KEYS.clientId, clientId);
  localStorage.setItem(STORAGE_KEYS.clientSecret, clientSecret);
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
  const { clientId } = getStoredCredentials();
  if (!clientId) throw new Error("Client ID not configured");

  const codeVerifier = generateRandomString(64);
  localStorage.setItem(STORAGE_KEYS.codeVerifier, codeVerifier);

  const challengeBuffer = await sha256(codeVerifier);
  const codeChallenge = base64UrlEncode(challengeBuffer);

  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: `${window.location.origin}/callback`,
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
  const { clientId, clientSecret } = getStoredCredentials();
  const codeVerifier = localStorage.getItem(STORAGE_KEYS.codeVerifier);
  if (!codeVerifier) throw new Error("No code verifier found");

  const body = new URLSearchParams({
    client_id: clientId,
    client_secret: clientSecret,
    code,
    code_verifier: codeVerifier,
    grant_type: "authorization_code",
    redirect_uri: `${window.location.origin}/callback`,
  });

  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
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
  const { clientId, clientSecret } = getStoredCredentials();
  const refreshToken = localStorage.getItem(STORAGE_KEYS.refreshToken);
  if (!refreshToken) throw new Error("No refresh token available");

  const body = new URLSearchParams({
    client_id: clientId,
    client_secret: clientSecret,
    refresh_token: refreshToken,
    grant_type: "refresh_token",
  });

  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });

  if (!response.ok) throw new Error("Token refresh failed");

  const data = await response.json();
  localStorage.setItem(STORAGE_KEYS.accessToken, data.access_token);
  const expiresAt = Date.now() + data.expires_in * 1000;
  localStorage.setItem(STORAGE_KEYS.expiresAt, String(expiresAt));
  return data.access_token;
}
