import { styleStatut } from "@/lib/couleurs";
import type { ClasseRetard } from "@/lib/types";

/**
 * What the colours on the map mean.
 *
 * This is the highest-value part of the accueil and it was missing from the
 * product entirely: a first-time visitor landed on a field of coloured markers
 * with nothing anywhere saying that orange is a quarter of an hour late. A map
 * whose colours are unexplained is decoration.
 *
 * Every swatch is painted by `styleStatut`, the same function the markers, the
 * departure rows and the station board call. There is deliberately no colour
 * table here — a second one would drift from the first, and the drift would be
 * silent (invariant 8).
 *
 * **One row per colour, not one row per `ClasseRetard`.** The enum has six delay
 * values; the palette phase 4 fixed has four delay tones plus cancelled, because
 * `R5`/`R10` share a swatch and `R15`/`R30` share a swatch. The addendum's table
 * lists six rows, which assumes six colours — and rendering six rows over five
 * colours produced two pairs that were identical in both swatch and wording,
 * asserting a distinction the map does not draw. A legend answers "what does
 * this colour mean", so there is exactly one row per colour and the interval
 * names the whole band that colour covers. Adding two more colours to get six
 * distinct rows would mean a second colour table, which is the one thing this
 * component exists to avoid (invariant 8).
 */

interface EntreeLegende {
  /** Any class in the band; only its colour is read. */
  classe: ClasseRetard;
  libelle: string;
  intervalle: string;
}

const ENTREES: EntreeLegende[] = [
  { classe: "A_L_HEURE", libelle: "À l'heure", intervalle: "moins de 5 min" },
  { classe: "R5", libelle: "Retard léger", intervalle: "5 à 14 min" },
  { classe: "R15", libelle: "Retard marqué", intervalle: "15 à 59 min" },
  { classe: "R60_PLUS", libelle: "Retard important", intervalle: "1 heure ou plus" },
];

export interface LegendeRetardsProps {
  /**
   * Column count, as a whole literal class rather than an override.
   *
   * Passing `sm:grid-cols-1` in a `className` alongside a base of
   * `sm:grid-cols-2` does not reliably win: the two utilities have identical
   * specificity, so which one applies is decided by their order in the
   * generated stylesheet, not by their order in the attribute. On `/carte` that
   * silently left the panel two columns wide and wrapped every label.
   */
  colonnes?: 1 | 2;
  /** Extra classes on the list wrapper -- spacing only, never layout. */
  className?: string;
}

/** Literal, both of them. Tailwind only emits a class it can see (invariant 8). */
const CLASSES_COLONNES: Record<1 | 2, string> = {
  1: "grid-cols-1",
  2: "grid-cols-1 sm:grid-cols-2",
};

/**
 * Server Component: pure presentation over a static table, so it costs the
 * client bundle nothing on a page whose whole point is to load fast.
 */
export function LegendeRetards({ colonnes = 2, className = "" }: LegendeRetardsProps) {
  // EN_CIRCULATION for the delay bands: styleStatut gives ANNULE and
  // ARRET_EXCEPTIONNEL precedence over the band, which is right on a marker and
  // wrong here, where the band is the subject.
  const annule = styleStatut("ANNULE", "ANNULE");

  return (
    <ul className={`grid gap-x-6 gap-y-2 ${CLASSES_COLONNES[colonnes]} ${className}`}>
      {ENTREES.map((entree) => {
        const style = styleStatut("EN_CIRCULATION", entree.classe);
        return (
          <li key={entree.classe} className="flex items-baseline gap-2.5">
            <span
              aria-hidden="true"
              className={`mt-0.5 inline-block size-2.5 shrink-0 self-center rounded-[2px] ${style.fond}`}
            />
            <span className="whitespace-nowrap text-sm text-encre">{entree.libelle}</span>
            <span className="chiffres ml-auto whitespace-nowrap text-xs text-ardoise-400">
              {entree.intervalle}
            </span>
          </li>
        );
      })}
      <li className="flex items-baseline gap-2.5">
        <span
          aria-hidden="true"
          className={`mt-0.5 inline-block size-2.5 shrink-0 self-center rounded-[2px] ${annule.fond}`}
        />
        <span className="whitespace-nowrap text-sm text-encre">Supprimé</span>
        <span className="ml-auto whitespace-nowrap text-xs text-ardoise-400">train annulé</span>
      </li>
    </ul>
  );
}
