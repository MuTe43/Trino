"use client";

import type { CaseHeatmapDTO } from "@/lib/types";

/**
 * Average delay by gare and hour, as a plain CSS grid.
 *
 * Not a charting library: this is a table with background colours, and a chart
 * component would only get in the way of making the cells addressable and the
 * row headers sticky.
 *
 * `heure` arrives already converted to Africa/Tunis by the API. Nothing here
 * does time arithmetic — invariant 6, and the same rule the station board follows.
 */

/**
 * Intensity ramp, keyed by average delay in minutes.
 *
 * Every class name is a complete literal string, and that is not stylistic.
 * Tailwind 4 generates a utility only when it can see the name in source, so a
 * class assembled at runtime (`bg-retard-${niveau}`) is never emitted: the code
 * compiles, lints and builds green, and the colour simply does not exist. That
 * cost nine of ten status colours in phase 4. If you add a level here, spell
 * its classes out in full.
 */
interface NiveauHeatmap {
  fond: string;
  texte: string;
  seuil: number;
  etiquette: string;
}

const NIVEAUX: NiveauHeatmap[] = [
  { seuil: 0, fond: "bg-statut-a-lheure/10", texte: "text-encre", etiquette: "moins de 2 min" },
  { seuil: 2, fond: "bg-statut-a-lheure/30", texte: "text-encre", etiquette: "2 à 5 min" },
  { seuil: 5, fond: "bg-statut-r10/30", texte: "text-encre", etiquette: "5 à 10 min" },
  { seuil: 10, fond: "bg-statut-r10/60", texte: "text-encre", etiquette: "10 à 20 min" },
  { seuil: 20, fond: "bg-statut-r30/70", texte: "text-white", etiquette: "20 à 40 min" },
  { seuil: 40, fond: "bg-statut-r60/80", texte: "text-white", etiquette: "40 min et plus" },
];

function niveauPour(retardMoyenMin: number): NiveauHeatmap {
  let choisi = NIVEAUX[0];
  for (const niveau of NIVEAUX) {
    if (retardMoyenMin >= niveau.seuil) {
      choisi = niveau;
    }
  }
  return choisi;
}

/** Empty cell: no train called there in that hour. Distinct from "0 min late". */
const CASE_VIDE = "bg-papier";

interface Props {
  cases: CaseHeatmapDTO[];
}

export default function HeatmapRetards({ cases }: Props) {
  if (cases.length === 0) {
    return (
      <p className="p-4 text-sm text-ardoise-400">
        Aucun passage desservi sur la période.
      </p>
    );
  }

  // Only the hours that actually carry traffic, so the grid does not waste
  // half its width on an empty night. Long-distance runs do arrive after
  // midnight, so hours 0-3 are real and must not be filtered out.
  const heures = [...new Set(cases.map((c) => c.heure))].sort((a, b) => a - b);

  const parGare = new Map<number, { nom: string; parHeure: Map<number, CaseHeatmapDTO> }>();
  for (const cellule of cases) {
    let gare = parGare.get(cellule.gareId);
    if (!gare) {
      gare = { nom: cellule.gareNom, parHeure: new Map() };
      parGare.set(cellule.gareId, gare);
    }
    gare.parHeure.set(cellule.heure, cellule);
  }
  const gares = [...parGare.entries()].sort((a, b) => a[1].nom.localeCompare(b[1].nom, "fr"));

  return (
    <div>
      {/* The grid scrolls inside its own box; the page never scrolls sideways. */}
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-xs">
          <caption className="sr-only">
            Retard moyen par gare et par heure, heures locales Africa/Tunis
          </caption>
          <thead>
            <tr>
              <th
                scope="col"
                className="sticky left-0 z-10 bg-white p-2 text-left font-medium text-ardoise-400"
              >
                Gare
              </th>
              {heures.map((heure) => (
                <th
                  key={heure}
                  scope="col"
                  className="chiffres p-1 text-center font-medium text-ardoise-400"
                >
                  {String(heure).padStart(2, "0")}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {gares.map(([gareId, gare]) => (
              <tr key={gareId}>
                <th
                  scope="row"
                  className="sticky left-0 z-10 max-w-40 truncate bg-white p-2 text-left font-normal text-encre"
                >
                  {gare.nom}
                </th>
                {heures.map((heure) => {
                  const cellule = gare.parHeure.get(heure);
                  if (!cellule) {
                    return (
                      <td
                        key={heure}
                        className={`${CASE_VIDE} border border-filet p-1 text-center`}
                      >
                        <span className="sr-only">Aucun passage</span>
                      </td>
                    );
                  }
                  const niveau = niveauPour(cellule.retardMoyenMin);
                  return (
                    <td
                      key={heure}
                      className={`${niveau.fond} ${niveau.texte} chiffres border border-filet p-1 text-center`}
                      title={`${gare.nom} — ${String(heure).padStart(2, "0")}h : ${cellule.retardMoyenMin.toFixed(
                        1,
                      )} min de retard moyen sur ${cellule.passages} passage${
                        cellule.passages > 1 ? "s" : ""
                      }`}
                    >
                      {Math.round(cellule.retardMoyenMin)}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ul className="mt-3 flex flex-wrap items-center gap-3 text-xs text-ardoise-400">
        <li>Retard moyen :</li>
        {NIVEAUX.map((niveau) => (
          <li key={niveau.seuil} className="flex items-center gap-1.5">
            <span className={`${niveau.fond} inline-block size-3 rounded-xs border border-filet`} />
            {niveau.etiquette}
          </li>
        ))}
      </ul>
    </div>
  );
}
