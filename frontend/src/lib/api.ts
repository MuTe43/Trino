import type { ErreurApi } from "./types";

// Base URL of the Spring Boot API. Defaults to local dev; override with
// NEXT_PUBLIC_API_URL in production/staging environments.
const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/** Raised when the API responds with a non-OK status. Carries the parsed
 * error envelope (see ApiExceptionHandler) when the body could be parsed. */
export class ApiError extends Error {
  readonly statut: number;
  readonly erreur?: ErreurApi;

  constructor(statut: number, message: string, erreur?: ErreurApi) {
    super(message);
    this.name = "ApiError";
    this.statut = statut;
    this.erreur = erreur;
  }
}

/**
 * Typed GET against the Trino API (paths are relative to `/api/v1`).
 * Throws `ApiError` on a non-OK response and on network failures.
 */
export async function apiGet<T>(path: string): Promise<T> {
  const url = `${API_BASE_URL}/api/v1${path}`;

  let response: Response;
  try {
    response = await fetch(url, {
      // Phase 0 placeholder: no caching semantics decided yet, always fresh.
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, `Impossible de joindre l'API (${url}).`);
  }

  if (!response.ok) {
    let erreur: ErreurApi | undefined;
    try {
      erreur = (await response.json()) as ErreurApi;
    } catch {
      erreur = undefined;
    }
    throw new ApiError(
      response.status,
      erreur?.message ?? `Erreur API ${response.status} sur ${url}.`,
      erreur,
    );
  }

  return (await response.json()) as T;
}
