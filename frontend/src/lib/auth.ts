import { ApiError, API_BASE_URL } from "./api";
import type { ErreurApi, LoginResponse, Utilisateur } from "./types";

// Access token lives in memory only (never localStorage, never a JS-readable
// cookie) — lost on page refresh, which is expected for this phase.
let accessToken: string | null = null;

/** Current in-memory access token, or null if not authenticated. */
export function getAccessToken(): string | null {
  return accessToken;
}

async function parseErreur(response: Response): Promise<ErreurApi | undefined> {
  try {
    return (await response.json()) as ErreurApi;
  } catch {
    return undefined;
  }
}

/** Logs in, stores the access token in memory, returns the authenticated user. */
export async function login(
  email: string,
  motDePasse: string,
): Promise<Utilisateur> {
  const url = `${API_BASE_URL}/api/v1/auth/login`;

  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, motDePasse }),
    });
  } catch {
    throw new ApiError(0, `Impossible de joindre l'API (${url}).`);
  }

  if (!response.ok) {
    const erreur = await parseErreur(response);
    throw new ApiError(
      response.status,
      erreur?.message ?? `Erreur API ${response.status} sur ${url}.`,
      erreur,
    );
  }

  const data = (await response.json()) as LoginResponse;
  accessToken = data.accessToken;
  return data.utilisateur;
}

/** Revokes the session server-side, then clears the in-memory token regardless. */
export async function logout(): Promise<void> {
  try {
    await fetch(`${API_BASE_URL}/api/v1/auth/logout`, {
      method: "POST",
      credentials: "include",
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    });
  } finally {
    accessToken = null;
  }
}

/**
 * Refreshes the access token using the httpOnly refreshToken cookie.
 * Returns null on failure instead of throwing — callers treat null as
 * "not authenticated".
 */
export async function refreshAccessToken(): Promise<string | null> {
  try {
    const response = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    });
    if (!response.ok) {
      accessToken = null;
      return null;
    }
    const data = (await response.json()) as LoginResponse;
    accessToken = data.accessToken;
    return accessToken;
  } catch {
    accessToken = null;
    return null;
  }
}

/**
 * Authenticated fetch against the Trino API. Attaches the in-memory access
 * token and retries once via refreshAccessToken() on a 401.
 */
export async function authFetch(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const url = `${API_BASE_URL}/api/v1${path}`;

  const doFetch = (token: string | null) =>
    fetch(url, {
      ...init,
      credentials: "include",
      headers: {
        ...init.headers,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });

  let response = await doFetch(accessToken);

  if (response.status === 401) {
    const nouveauToken = await refreshAccessToken();
    if (nouveauToken) {
      response = await doFetch(nouveauToken);
    }
  }

  return response;
}

/**
 * `authFetch` plus the error envelope, for endpoints that return JSON.
 *
 * `incidents.ts` and `tableauBord.ts` each grew a private copy of exactly this
 * before it lived anywhere shared; they are deliberately left alone here
 * (phase 7 does not touch the incident or dashboard screens), but a third copy
 * in `admin.ts` was one too many. Throws `ApiError` carrying the parsed
 * `ErreurApi` so a caller can read `details[].champ` and map a field error onto
 * its input.
 */
export async function requeteAuthJson<T>(
  chemin: string,
  init: RequestInit = {},
): Promise<T> {
  let reponse: Response;
  try {
    reponse = await authFetch(chemin, init);
  } catch {
    throw new ApiError(0, `Impossible de joindre l'API (${API_BASE_URL}${chemin}).`);
  }
  if (!reponse.ok) {
    const erreur = await parseErreur(reponse);
    throw new ApiError(
      reponse.status,
      erreur?.message ?? `Erreur API ${reponse.status} sur ${chemin}.`,
      erreur,
    );
  }
  // 204 No Content: a DELETE has nothing to parse, and JSON.parse("") throws.
  if (reponse.status === 204) {
    return undefined as T;
  }
  return (await reponse.json()) as T;
}

/**
 * `GET /auth/me` -- who the in-memory token (or, after a refresh, the
 * `refreshToken` cookie) belongs to. Used by `/exploitation/layout.tsx` to
 * decide whether the visitor may be here at all, and which role: the access
 * token itself is lost on a page refresh (kept in memory only, invariant-
 * adjacent choice noted above), so a role check cannot just decode a claim
 * client-side -- it has to ask the server. Returns `null` rather than
 * throwing on any failure (401 included): the caller treats that as "not
 * authenticated" and redirects, same convention as `refreshAccessToken`.
 */
export async function chargerUtilisateurCourant(): Promise<Utilisateur | null> {
  try {
    const response = await authFetch("/auth/me");
    if (!response.ok) {
      return null;
    }
    return (await response.json()) as Utilisateur;
  } catch {
    return null;
  }
}
