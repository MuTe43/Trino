import type {
  CourseResumeDTO,
  DepartGareDTO,
  DesserteDTO,
  ErreurApi,
  Gare,
  LigneDTO,
  PageDTO,
  PassageDTO,
  StatutCourse,
} from "./types";

// Base URL of the Spring Boot API. Exported so src/lib/auth.ts (and any other
// module that talks to the API outside the apiGet/apiPost helpers) shares the
// exact same source instead of each reading process.env on its own.
//
// TWO addresses, because the same code runs on two sides of a network boundary
// and there is no single value that is correct for both.
//
// NEXT_PUBLIC_API_BASE_URL is the address the BROWSER uses. Under docker compose
// that is a published host port, http://localhost:8081. A Server Component runs
// inside the `web` container, where `localhost` is the container itself and
// nothing answers on 8081 — so every server-rendered page that fetched through
// this constant returned 500 while `/` and `/carte` were fine. Measured on the
// containerised stack: /trains/{id}, /gares/{id} and /affichage/{gareId} all
// 500, with `Impossible de joindre l'API (http://localhost:8081/...)` in the
// container log and nothing at all in the browser console.
//
// TRINO_API_BASE_URL_SERVEUR carries the server-side address (http://api:8080 in
// compose). No NEXT_PUBLIC_ prefix, so it is never inlined into the client
// bundle; the `typeof window` branch makes the whole lookup dead code there.
// It falls back to the public value, which is what keeps `npm run dev` — where
// both sides really are the same host — working unchanged.
const SUR_LE_SERVEUR = typeof window === "undefined";

export const API_BASE_URL = SUR_LE_SERVEUR
  ? (process.env.TRINO_API_BASE_URL_SERVEUR
    ?? process.env.NEXT_PUBLIC_API_BASE_URL
    ?? "http://localhost:8080")
  : (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080");

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

/**
 * Pages through a listing endpoint until every row is collected. Lignes and
 * gares are both small, fixed référentiel tables (~5 and ~40 rows), but
 * pagination is clamped server-side (`PageableUtils.TAILLE_MAX`), so a single
 * oversized `taille` is not guaranteed to return everything in one page.
 * Shared by every caller that needs "all of a small table" rather than a page
 * of it -- the network map, the incident declaration form and the incidents
 * console filters all need the same full gare/ligne lists.
 */
export async function chargerToutesPages<T>(
  page: (p: number, taille: number) => Promise<PageDTO<T>>,
  taille = 100,
): Promise<T[]> {
  const resultat: T[] = [];
  let p = 0;
  for (;;) {
    const reponse = await page(p, taille);
    resultat.push(...reponse.contenu);
    if (reponse.contenu.length === 0 || resultat.length >= reponse.total) {
      break;
    }
    p += 1;
  }
  return resultat;
}

/** Builds a query string from a params object, dropping null/undefined/"" entries. */
function requete(params: object): string {
  const entrees = Object.entries(params as Record<string, unknown>).filter(
    ([, valeur]) => valeur !== undefined && valeur !== null && valeur !== "",
  );
  if (entrees.length === 0) {
    return "";
  }
  const recherche = new URLSearchParams();
  for (const [cle, valeur] of entrees) {
    if (Array.isArray(valeur)) {
      // The API binds repeatable params as a comma-separated list
      // (`?statut=EN_CIRCULATION,RETARDE`), not as repeated keys.
      if (valeur.length > 0) {
        recherche.set(cle, valeur.join(","));
      }
      continue;
    }
    recherche.set(cle, String(valeur));
  }
  return `?${recherche.toString()}`;
}

/** Filters accepted by GET /courses and GET /recherche. All optional. */
export interface FiltresCourses {
  date?: string;
  ligneId?: number;
  gareId?: number;
  /** One status, or several — several serialise to `?statut=A,B`. */
  statut?: StatutCourse | StatutCourse[];
  q?: string;
  page?: number;
  taille?: number;
}

/** GET /courses — the paged, filterable course list behind the map and the search views. */
export function listerCourses(filtres: FiltresCourses = {}): Promise<PageDTO<CourseResumeDTO>> {
  return apiGet<PageDTO<CourseResumeDTO>>(`/courses${requete(filtres)}`);
}

/** GET /courses/{id} — a single course's résumé. */
export function trouverCourse(id: number): Promise<CourseResumeDTO> {
  return apiGet<CourseResumeDTO>(`/courses/${id}`);
}

/** GET /courses/{id}/passages — the full stop list of one course. */
export function listerPassages(courseId: number): Promise<PassageDTO[]> {
  return apiGet<PassageDTO[]>(`/courses/${courseId}/passages`);
}

/** GET /recherche — unified search over train number/name, ligne, gare and destination. */
export function rechercherCourses(
  q: string,
  filtres: Pick<FiltresCourses, "date" | "page" | "taille"> = {},
): Promise<PageDTO<CourseResumeDTO>> {
  return apiGet<PageDTO<CourseResumeDTO>>(`/recherche${requete({ q, ...filtres })}`);
}

/** GET /gares/{id}/departs — the station departure board. */
export function listerDeparts(gareId: number, limite?: number): Promise<DepartGareDTO[]> {
  return apiGet<DepartGareDTO[]>(`/gares/${gareId}/departs${requete({ limite })}`);
}

/** GET /gares/{id} — one gare's référentiel record. */
export function trouverGare(id: number): Promise<Gare> {
  return apiGet<Gare>(`/gares/${id}`);
}

/** GET /gares — the paged gare list behind the network map and any other
 * full-listing view. */
export function listerGares(page = 0, taille = 20): Promise<PageDTO<Gare>> {
  return apiGet<PageDTO<Gare>>(`/gares${requete({ page, taille })}`);
}

/** GET /lignes — the paged ligne list. */
export function listerLignes(page = 0, taille = 20): Promise<PageDTO<LigneDTO>> {
  return apiGet<PageDTO<LigneDTO>>(`/lignes${requete({ page, taille })}`);
}

/** GET /lignes/{id}/desserte — the theoretical stop pattern of one ligne. */
export function trouverDesserte(ligneId: number): Promise<DesserteDTO[]> {
  return apiGet<DesserteDTO[]>(`/lignes/${ligneId}/desserte`);
}
