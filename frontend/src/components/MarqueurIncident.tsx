"use client";

import { styleGravite } from "@/lib/couleurs";
import type { Gravite, StatutIncident, TypeIncident } from "@/lib/types";

/**
 * Client-side composite the supervision map renders from. A snapshot row
 * (`GET /incidents/ouverts`, `IncidentDTO`) and an `incident` SSE delta
 * (`EvenementIncident`) name the id field differently (`id` vs `incidentId`)
 * and only the delta carries coordinates, so `CarteReseau` normalises both to
 * this shape before either reaches state or a marker. See the two
 * `incidentCarteDepuis*` functions in `CarteReseau.tsx`.
 */
export interface IncidentCarte {
  id: number;
  type: TypeIncident;
  gravite: Gravite;
  statut: StatutIncident;
  description: string;
  survenuAt: string;
  ligneId: number | null;
  gareId: number | null;
  courseId: number | null;
  /** Server-computed at event time (from the gare, or the course's last known
   * position). Null on a snapshot row -- `CarteReseau` derives a fallback
   * position itself from `gareId`/`courseId` when this is null; see
   * `positionIncident`. */
  latitude: number | null;
  longitude: number | null;
}

export interface MarqueurIncidentProps {
  incident: IncidentCarte;
  selectionne: boolean;
  onSelectionner: (incidentId: number) => void;
}

/**
 * A gravité-coloured pin. Deliberately a different shape (rounded square,
 * exclamation mark) from `MarqueurTrain`'s pill so the two families are never
 * confused at a glance on the supervision map -- no interpolation here, an
 * incident marker is repositioned outright on the next render, never eased.
 */
export function MarqueurIncident({ incident, selectionne, onSelectionner }: MarqueurIncidentProps) {
  const style = styleGravite(incident.gravite);

  return (
    <button
      type="button"
      onClick={() => onSelectionner(incident.id)}
      aria-label={`Incident, gravité ${style.etiquette.toLowerCase()} : ${incident.description}`}
      aria-pressed={selectionne}
      className={[
        "flex h-6 w-6 items-center justify-center rounded-controle border-2 font-ui text-xs font-medium text-white",
        style.fond,
        style.bordure,
        selectionne ? "ring-2 ring-encre ring-offset-1 ring-offset-papier" : "",
      ].join(" ")}
    >
      <span aria-hidden="true">!</span>
    </button>
  );
}
