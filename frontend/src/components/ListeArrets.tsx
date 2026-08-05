// Pure presentational: a Server Component can render this directly from a
// `PassageDTO[]` it fetched, no browser API involved (Intl works server-side
// too). Deliberately no 'use client'.
import type { ClasseRetard, PassageDTO } from "@/lib/types";
import { styleStatut } from "@/lib/couleurs";
import { formaterHeure } from "@/lib/temps";

interface Cellule {
  theorique: string;
  affichee: string;
  differe: boolean;
}

/**
 * Never adds `retardMin` to a theoretical time. `reel` and `estime` are read
 * exactly as the engine wrote them; the only fallback is `estime` standing in
 * when `reel` has not happened yet (e.g. a franchi stop the train hasn't left
 * yet keeps its estimated departure). `theorique === null` means this cell is
 * genuinely absent (an origin has no arrival, a terminus has no departure).
 */
function calculerCellule(theorique: string | null, reel: string | null, estime: string | null): Cellule | null {
  if (theorique === null) {
    return null;
  }
  const affichee = reel ?? estime ?? theorique;
  return { theorique, affichee, differe: affichee !== theorique };
}

function CelluleTemps({
  theorique,
  reel,
  estime,
  classeRetard,
}: {
  theorique: string | null;
  reel: string | null;
  estime: string | null;
  classeRetard: ClasseRetard;
}) {
  const cellule = calculerCellule(theorique, reel, estime);
  if (cellule === null) {
    return <span aria-hidden="true" />;
  }
  // Any non-cancelled, non-frozen statut routes through the classeRetard
  // ramp here -- a passage carries no statut of its own, only a delay class.
  const style = styleStatut("EN_CIRCULATION", classeRetard);

  return (
    <span className="chiffres inline-flex items-baseline gap-1.5 whitespace-nowrap">
      {cellule.differe && (
        <span className="text-ardoise-400 line-through">{formaterHeure(cellule.theorique)}</span>
      )}
      <span className={cellule.differe ? style.texte : "text-encre"}>
        {formaterHeure(cellule.affichee)}
      </span>
    </span>
  );
}

type Entree =
  | { genre: "arret"; passage: PassageDTO }
  | { genre: "position"; cle: string };

function construireEntrees(passages: PassageDTO[]): Entree[] {
  const entrees: Entree[] = passages.map((passage) => ({ genre: "arret", passage }));
  let dernierFranchi = -1;
  passages.forEach((passage, index) => {
    if (passage.franchi) {
      dernierFranchi = index;
    }
  });
  // Mark the boundary between "franchi" and "ahead" -- only meaningful when
  // the train is mid-run, not sitting at the very first or very last stop.
  if (dernierFranchi >= 0 && dernierFranchi < passages.length - 1) {
    entrees.splice(dernierFranchi + 1, 0, { genre: "position", cle: "position-actuelle" });
  }
  return entrees;
}

export interface ListeArretsProps {
  passages: PassageDTO[];
  className?: string;
}

/**
 * Vertical timetable strip: a rule down the left with a node per gare,
 * filled for stops already franchi and hollow for those still ahead. Each
 * row makes the prévue / estimée / réelle distinction visible -- the
 * scheduled time struck through beside the revised one, in the status
 * colour, whenever the two differ.
 */
export function ListeArrets({ passages, className }: ListeArretsProps) {
  if (passages.length === 0) {
    return (
      <p className={["text-sm text-ardoise-400", className ?? ""].join(" ")}>
        Aucun arrêt à afficher pour cette course.
      </p>
    );
  }

  const entrees = construireEntrees(passages);

  return (
    <ol className={["font-ui", className ?? ""].join(" ")}>
      {entrees.map((entree, index) => {
        if (entree.genre === "position") {
          return (
            <li key={entree.cle} className="grid grid-cols-[16px_1fr] gap-x-3">
              <div className="relative flex justify-center">
                <span className="absolute top-0 bottom-0 w-px bg-filet" />
                <span className="relative z-10 mt-1 h-2 w-2 rounded-full bg-sncft-bleu" />
              </div>
              <p className="pb-3 text-xs text-sncft-bleu">Position actuelle du train</p>
            </li>
          );
        }

        const { passage } = entree;
        const dernier = index === entrees.length - 1;
        const style = styleStatut("EN_CIRCULATION", passage.classeRetard);
        const estOrigine = passage.arriveeTheorique === null;
        const estTerminus = passage.departTheorique === null;

        return (
          <li key={passage.ordre} className="grid grid-cols-[16px_1fr] gap-x-3">
            <div className="relative flex justify-center">
              {index > 0 && <span className="absolute top-0 left-1/2 h-1 w-px -translate-x-1/2 bg-filet" />}
              {!dernier && (
                <span className="absolute top-1 bottom-0 left-1/2 w-px -translate-x-1/2 bg-filet" />
              )}
              <span
                className="relative z-10 mt-1 h-2.5 w-2.5 rounded-full border-2"
                style={{
                  borderColor: style.var,
                  backgroundColor: passage.franchi ? style.var : "var(--papier)",
                }}
                aria-hidden="true"
              />
            </div>

            <div className="pb-4">
              <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
                <p className="font-condensee text-[15px] leading-tight">{passage.gare.nom}</p>
                <div className="flex items-center gap-2">
                  {passage.quai && (
                    <span className="chiffres rounded-controle border border-filet px-1 text-[11px] text-ardoise-700">
                      Quai {passage.quai}
                    </span>
                  )}
                  {passage.retardMin > 0 && (
                    <span className={["chiffres text-[11px]", style.texte].join(" ")}>
                      +{passage.retardMin} min
                    </span>
                  )}
                </div>
              </div>

              <div className="mt-0.5 flex flex-wrap gap-x-4 gap-y-0.5 text-[13px]">
                {!estOrigine && (
                  <span className="inline-flex items-baseline gap-1">
                    <span className="text-[11px] text-ardoise-400">Arr.</span>
                    <CelluleTemps
                      theorique={passage.arriveeTheorique}
                      reel={passage.arriveeReelle}
                      estime={passage.arriveeEstimee}
                      classeRetard={passage.classeRetard}
                    />
                  </span>
                )}
                {!estTerminus && (
                  <span className="inline-flex items-baseline gap-1">
                    <span className="text-[11px] text-ardoise-400">Dép.</span>
                    <CelluleTemps
                      theorique={passage.departTheorique}
                      reel={passage.departReel}
                      estime={passage.departEstime}
                      classeRetard={passage.classeRetard}
                    />
                  </span>
                )}
              </div>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
