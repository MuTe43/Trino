import { API_BASE_URL } from "./api";
import type { CourseResumeDTO, Gare, PageDTO } from "./types";

/**
 * Reads the accueil makes while server-rendering.
 *
 * The address comes from `api.ts`, which resolves the server side and the
 * browser side separately — see the comment on `API_BASE_URL` there. This module
 * used to carry its own copy of that resolution, which is exactly the drift the
 * single constant exists to prevent.
 *
 * What is different here is the error policy, not the address: these reads
 * decorate a page that must render regardless.
 */

/**
 * One GET, on the server, that never throws.
 *
 * The accueil is the first page a visitor sees and the API being briefly
 * unreachable must degrade it, not blank it: a caller that gets `null` renders
 * the section without its live link rather than a 500. Every use here is
 * decoration around content that stands on its own.
 */
async function lireServeur<T>(chemin: string): Promise<T | null> {
  try {
    const reponse = await fetch(`${API_BASE_URL}/api/v1${chemin}`, { cache: "no-store" });
    if (!reponse.ok) {
      return null;
    }
    return (await reponse.json()) as T;
  } catch {
    return null;
  }
}

/**
 * A course that is actually moving right now, for the "suivre un train" link.
 *
 * Derived at request time and never hardcoded. Phase 9 opens by listing three
 * acceptance commands across this project that measured nothing because they
 * named a subject that could not exercise the behaviour — `DR201`, a train that
 * was never seeded, and `cibleId: 1`, a course already at `TERMINUS_ATTEINT`. A
 * front page whose "suivre ce train" link points at a run that finished last
 * week is the same mistake, in front of a visitor rather than in a log.
 *
 * Falls back to any course of the day when nothing is in motion — at 04:00, or
 * with the simulator stopped, an accueil with a dead link is worse than one
 * pointing at a train that has not left yet.
 */
export async function courseEnVedette(): Promise<CourseResumeDTO | null> {
  const enMouvement = await lireServeur<PageDTO<CourseResumeDTO>>(
    "/courses?statut=EN_CIRCULATION,RETARDE&taille=1",
  );
  if (enMouvement?.contenu?.length) {
    return enMouvement.contenu[0];
  }
  const nImporteLaquelle = await lireServeur<PageDTO<CourseResumeDTO>>("/courses?taille=1");
  return nImporteLaquelle?.contenu?.[0] ?? null;
}

/**
 * A station worth linking to: the one the timetable serves most.
 *
 * Ordered by how many lignes call there rather than by id, so the two station
 * links on the accueil land somewhere a visitor recognises instead of on
 * whichever row the seed happened to insert first.
 */
export async function gareEnVedette(): Promise<Gare | null> {
  const gares = await lireServeur<PageDTO<Gare>>("/gares?taille=100");
  if (!gares?.contenu?.length) {
    return null;
  }
  const actives = gares.contenu.filter((gare) => gare.actif !== false);
  const candidates = actives.length > 0 ? actives : gares.contenu;
  // nbQuais is the only proxy the référentiel carries for "big station", and it
  // is the one an SNCFT reader would agree with.
  return [...candidates].sort((a, b) => (b.nbQuais ?? 0) - (a.nbQuais ?? 0))[0];
}
