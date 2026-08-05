// Shared SSE-delta application for anywhere a station's departure board is
// rendered live: the public gare page (src/app/gares/[id]) and the kiosk
// board (src/app/affichage/[gareId]). Both consume the same `gare:{gareId}`
// channel and the same DepartGareDTO shape, so the reducer and the sort order
// live here once instead of drifting between the two call sites.
import type { DepartGareDTO, EvenementRetard, EvenementStatut } from "./types";

export type EtatDeparts = Map<number, DepartGareDTO>;

export type ActionDeparts =
  | { type: "INIT"; departs: DepartGareDTO[] }
  | { type: "STATUT"; gareId: number; evenement: EvenementStatut }
  | { type: "RETARD"; gareId: number; evenement: EvenementRetard };

/**
 * `statut` restyles a row (and carries a cancellation). `retard` recolours it
 * and, when this station is among the revised stops, updates its estimated
 * departure -- `PassageRevise.gareId` is how a channel-wide delta narrows down
 * to the one stop this board cares about. A delta for a `courseId` the
 * snapshot never loaded (a course outside this gare's next 20 departures) is
 * dropped: deltas patch the snapshot, never grow it.
 */
export function reducerDeparts(etat: EtatDeparts, action: ActionDeparts): EtatDeparts {
  switch (action.type) {
    case "INIT": {
      const suivant: EtatDeparts = new Map();
      action.departs.forEach((depart) => suivant.set(depart.courseId, depart));
      return suivant;
    }
    case "STATUT": {
      const depart = etat.get(action.evenement.courseId);
      if (!depart) return etat;
      const suivant = new Map(etat);
      suivant.set(depart.courseId, {
        ...depart,
        statut: action.evenement.statut,
        retardMin: action.evenement.retardMin,
        classeRetard: action.evenement.classeRetard,
      });
      return suivant;
    }
    case "RETARD": {
      const depart = etat.get(action.evenement.courseId);
      if (!depart) return etat;
      const revision = action.evenement.passagesRevises.find(
        (passage) => passage.gareId === action.gareId,
      );
      const suivant = new Map(etat);
      suivant.set(depart.courseId, {
        ...depart,
        retardMin: action.evenement.retardMin,
        classeRetard: action.evenement.classeRetard,
        departEstime: revision?.departEstime ?? depart.departEstime,
      });
      return suivant;
    }
    default:
      return etat;
  }
}

/** Sorted by `departEstime`, never `departTheorique` -- a delayed train falls
 * down the board instead of holding its scheduled slot. */
export function trierDeparts(etat: EtatDeparts): DepartGareDTO[] {
  return [...etat.values()].sort(
    (a, b) => new Date(a.departEstime).getTime() - new Date(b.departEstime).getTime(),
  );
}
