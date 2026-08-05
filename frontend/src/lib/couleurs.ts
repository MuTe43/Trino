// Single source of truth mapping a course's state to a colour + label.
// Every component that paints a course by status/delay imports from here --
// nothing else hardcodes a hex or duplicates the ClasseRetard -> ramp switch.
import type { ClasseRetard, StatutCourse } from "./types";

/** Light mode is the default surface; "sombre" is reserved for the board. */
export type ModeCouleur = "clair" | "sombre";

type CleRampe = "a-lheure" | "r10" | "r30" | "r60" | "annule";

/** Colour bound to one ramp bucket, expressed both ways call sites need it. */
export interface JetonCouleur {
  /** CSS var() reference -- for inline styles (SVG fill, marker background). */
  var: string;
  /** Tailwind utility classes bound to the same custom property. */
  texte: string;
  fond: string;
  bordure: string;
}

/**
 * Every class name is spelled out in full, on purpose. Tailwind 4 scans source
 * text for literal class strings; a name assembled at runtime (`text-${nom}`)
 * is never seen by the scanner, so the utility is never generated and the
 * element silently falls back to its inherited colour. That failure is
 * invisible to TypeScript, to the build, and to the linter -- it only shows up
 * as a board whose statuses are all the same colour. Keep these literal.
 */
const JETONS: Record<ModeCouleur, Record<CleRampe, JetonCouleur>> = {
  clair: {
    "a-lheure": {
      var: "var(--statut-a-lheure)",
      texte: "text-statut-a-lheure",
      fond: "bg-statut-a-lheure",
      bordure: "border-statut-a-lheure",
    },
    r10: {
      var: "var(--statut-r10)",
      texte: "text-statut-r10",
      fond: "bg-statut-r10",
      bordure: "border-statut-r10",
    },
    r30: {
      var: "var(--statut-r30)",
      texte: "text-statut-r30",
      fond: "bg-statut-r30",
      bordure: "border-statut-r30",
    },
    r60: {
      var: "var(--statut-r60)",
      texte: "text-statut-r60",
      fond: "bg-statut-r60",
      bordure: "border-statut-r60",
    },
    annule: {
      var: "var(--statut-annule)",
      texte: "text-statut-annule",
      fond: "bg-statut-annule",
      bordure: "border-statut-annule",
    },
  },
  sombre: {
    "a-lheure": {
      var: "var(--statut-a-lheure-sombre)",
      texte: "text-statut-a-lheure-sombre",
      fond: "bg-statut-a-lheure-sombre",
      bordure: "border-statut-a-lheure-sombre",
    },
    r10: {
      var: "var(--statut-r10-sombre)",
      texte: "text-statut-r10-sombre",
      fond: "bg-statut-r10-sombre",
      bordure: "border-statut-r10-sombre",
    },
    r30: {
      var: "var(--statut-r30-sombre)",
      texte: "text-statut-r30-sombre",
      fond: "bg-statut-r30-sombre",
      bordure: "border-statut-r30-sombre",
    },
    r60: {
      var: "var(--statut-r60-sombre)",
      texte: "text-statut-r60-sombre",
      fond: "bg-statut-r60-sombre",
      bordure: "border-statut-r60-sombre",
    },
    annule: {
      var: "var(--statut-annule-sombre)",
      texte: "text-statut-annule-sombre",
      fond: "bg-statut-annule-sombre",
      bordure: "border-statut-annule-sombre",
    },
  },
};

function jeton(cle: CleRampe, mode: ModeCouleur): JetonCouleur {
  return JETONS[mode][cle];
}

/**
 * R5 and R10 share a bucket, R15 and R30 share a bucket -- mirrors the ramp
 * as named in globals.css. `ANNULE` here is kept only so the switch stays
 * exhaustive over the TS union; a cancelled course is never routed through
 * this function (see `styleStatut` below), because the backend's
 * `ClasseRetard.de()` never emits `ANNULE` on its own.
 */
function cleDeClasseRetard(classe: ClasseRetard): CleRampe {
  switch (classe) {
    case "A_L_HEURE":
      return "a-lheure";
    case "R5":
    case "R10":
      return "r10";
    case "R15":
    case "R30":
      return "r30";
    case "R60_PLUS":
      return "r60";
    case "ANNULE":
      return "annule";
  }
}

/**
 * Mirrors the backend's `ClasseRetard.de(int)` thresholds exactly.
 *
 * <p>Needed because a `retard` delta carries one course-level `classeRetard`
 * but a per-stop `retardMin` for each revised passage: a course at +40 min can
 * have a downstream stop that has recovered to +6 min, and that stop must keep
 * its own bucket. Reusing the course's bucket there paints "+6 min" in the
 * colour of a 40-minute delay.
 *
 * <p>A negative delay (a train running early) is on time, as on the server.
 */
export function classeDeRetard(retardMin: number): ClasseRetard {
  if (retardMin < 5) return "A_L_HEURE";
  if (retardMin < 10) return "R5";
  if (retardMin < 15) return "R10";
  if (retardMin < 30) return "R15";
  if (retardMin < 60) return "R30";
  return "R60_PLUS";
}

/** French label per StatutCourse, sentence case, register of a platform announcement. */
const LIBELLES_STATUT: Record<StatutCourse, string> = {
  A_QUAI: "À quai",
  EN_CIRCULATION: "En circulation",
  RETARDE: "Retardé",
  ARRET_EXCEPTIONNEL: "Signal perdu",
  ANNULE: "Supprimé",
  TERMINUS_ATTEINT: "Terminus atteint",
};

export function libelleStatut(statut: StatutCourse): string {
  return LIBELLES_STATUT[statut];
}

/** Full paint for one course: colour, label, and whether to render it as stale. */
export interface StyleStatut extends JetonCouleur {
  etiquette: string;
  /**
   * True only for ARRET_EXCEPTIONNEL: the feed went silent, the train was not
   * cancelled. Callers must render this as stale (hollow/ringed marker, "Signal
   * perdu") rather than dead, and must stop interpolating its position.
   */
  perime: boolean;
}

/**
 * The one function every consumer calls. Precedence is deliberate:
 * `statut === 'ANNULE'` always wins over `classeRetard`, because a cancelled
 * course still carries whatever delay bucket its `retardMin` maps to (the
 * backend never emits `ClasseRetard.ANNULE`). `ARRET_EXCEPTIONNEL` borrows the
 * cancelled tone but is flagged `perime` so it reads as "signal lost", not
 * "train gone".
 */
export function styleStatut(
  statut: StatutCourse,
  classeRetard: ClasseRetard,
  mode: ModeCouleur = "clair",
): StyleStatut {
  if (statut === "ANNULE") {
    return { ...jeton("annule", mode), etiquette: libelleStatut(statut), perime: false };
  }
  if (statut === "ARRET_EXCEPTIONNEL") {
    return { ...jeton("annule", mode), etiquette: libelleStatut(statut), perime: true };
  }
  return {
    ...jeton(cleDeClasseRetard(classeRetard), mode),
    etiquette: libelleStatut(statut),
    perime: false,
  };
}
