interface Env {
  GOOGLE_CLIENT_ID: string;
  GOOGLE_CLIENT_SECRET: string;
}

interface TokenRequestBody {
  grant_type: "authorization_code" | "refresh_token";
  code?: string;
  code_verifier?: string;
  redirect_uri?: string;
  refresh_token?: string;
}

const TOKEN_URL = "https://oauth2.googleapis.com/token";

const ALLOWED_ORIGINS = [
  "https://cloudamp.io",
  "https://www.cloudamp.io",
];

const ALLOWED_REDIRECT_URIS = [
  "https://cloudamp.io/callback",
  "https://www.cloudamp.io/callback",
  "https://cloudamp.io/api/android-callback",
];

function isAllowedOrigin(origin: string | null): boolean {
  // No Origin header = non-browser client (e.g. Android app) — allow
  if (!origin) return true;
  return ALLOWED_ORIGINS.includes(origin);
}

function corsHeaders(origin: string): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
  };
}

export const onRequestOptions: PagesFunction<Env> = async (context) => {
  const origin = context.request.headers.get("Origin");
  if (!origin || !ALLOWED_ORIGINS.includes(origin)) {
    return new Response(null, { status: 403 });
  }
  return new Response(null, { status: 204, headers: corsHeaders(origin) });
};

export const onRequestPost: PagesFunction<Env> = async (context) => {
  const origin = context.request.headers.get("Origin");
  if (!isAllowedOrigin(origin)) {
    return Response.json(
      { error: "forbidden", error_description: "Origin not allowed" },
      { status: 403 },
    );
  }

  const { GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET } = context.env;

  if (!GOOGLE_CLIENT_ID || !GOOGLE_CLIENT_SECRET) {
    return Response.json(
      { error: "server_config", error_description: "OAuth credentials not configured" },
      { status: 500 },
    );
  }

  let body: TokenRequestBody;
  try {
    body = await context.request.json();
  } catch {
    return Response.json(
      { error: "invalid_request", error_description: "Invalid JSON body" },
      { status: 400 },
    );
  }

  const params = new URLSearchParams({
    client_id: GOOGLE_CLIENT_ID,
    client_secret: GOOGLE_CLIENT_SECRET,
    grant_type: body.grant_type,
  });

  if (body.grant_type === "authorization_code") {
    if (!body.code || !body.code_verifier || !body.redirect_uri) {
      return Response.json(
        { error: "invalid_request", error_description: "Missing code, code_verifier, or redirect_uri" },
        { status: 400 },
      );
    }
    if (!ALLOWED_REDIRECT_URIS.includes(body.redirect_uri)) {
      return Response.json(
        { error: "invalid_request", error_description: "Invalid redirect_uri" },
        { status: 400 },
      );
    }
    params.set("code", body.code);
    params.set("code_verifier", body.code_verifier);
    params.set("redirect_uri", body.redirect_uri);
  } else if (body.grant_type === "refresh_token") {
    if (!body.refresh_token) {
      return Response.json(
        { error: "invalid_request", error_description: "Missing refresh_token" },
        { status: 400 },
      );
    }
    params.set("refresh_token", body.refresh_token);
  } else {
    return Response.json(
      { error: "unsupported_grant_type", error_description: "Only authorization_code and refresh_token are supported" },
      { status: 400 },
    );
  }

  const googleResponse = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: params,
  });

  const headers: Record<string, string> = {
    "Cache-Control": "no-store",
    "Pragma": "no-cache",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
  };
  if (origin) Object.assign(headers, corsHeaders(origin));

  if (googleResponse.ok) {
    const data = await googleResponse.json();
    return Response.json(data, { status: 200, headers });
  }

  // Sanitize error responses — don't proxy Google's internal error details
  const status = googleResponse.status;
  const errorMap: Record<number, string> = {
    400: "invalid_grant",
    401: "invalid_client",
    403: "access_denied",
  };
  return Response.json(
    { error: errorMap[status] ?? "server_error", error_description: "Token request failed" },
    { status, headers },
  );
};
