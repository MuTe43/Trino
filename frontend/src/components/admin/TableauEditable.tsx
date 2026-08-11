"use client";

import type { ReactNode } from "react";

/**
 * The one table the administration console uses, for gares, lignes, trains and
 * users alike. Four bespoke tables would be four times the surface for the same
 * job, and they drift: the phase-6 console already showed what happens when two
 * views of the same data disagree about a filter.
 *
 * Callers supply the columns; this owns the chrome, the paging, the empty and
 * error states. Dense by design, per the phase-4 direction: hairline rules, no
 * shadows, no zebra, tabular numerals on anything numeric so digits do not
 * jitter between pages.
 */

export interface ColonneTableau<T> {
  /** Stable key — also the React key of the cell. */
  cle: string;
  entete: string;
  valeur: (ligne: T) => ReactNode;
  /** Right-aligned with tabular figures. Use for every number. */
  numerique?: boolean;
}

interface ProprietesTableauEditable<T> {
  titre: string;
  colonnes: ColonneTableau<T>[];
  lignes: T[];
  cleLigne: (ligne: T) => string | number;
  page: number;
  taille: number;
  total: number;
  onChangerPage: (page: number) => void;
  chargement?: boolean;
  /** Message from a failed load. Field-level errors belong in the dialog. */
  erreur?: string | null;
  /** Row actions, rendered in a trailing column. */
  actions?: (ligne: T) => ReactNode;
  /** Filter controls, rendered in the header bar above the table. */
  filtres?: ReactNode;
  onNouveau?: () => void;
  libelleNouveau?: string;
  messageVide?: string;
  /** Shown under the title — used where a screen has a rule worth stating. */
  note?: string;
}

export default function TableauEditable<T>({
  titre,
  colonnes,
  lignes,
  cleLigne,
  page,
  taille,
  total,
  onChangerPage,
  chargement = false,
  erreur = null,
  actions,
  filtres,
  onNouveau,
  libelleNouveau = "Nouveau",
  messageVide = "Aucun élément.",
  note,
}: ProprietesTableauEditable<T>) {
  const premier = total === 0 ? 0 : page * taille + 1;
  const dernier = Math.min(total, page * taille + lignes.length);
  const dernierePage = Math.max(0, Math.ceil(total / Math.max(taille, 1)) - 1);

  return (
    <section className="flex min-h-0 flex-col gap-3 p-4 sm:p-6">
      <div className="flex flex-wrap items-baseline justify-between gap-3">
        <div>
          <h1 className="font-condensee text-lg font-medium text-encre">{titre}</h1>
          {note ? <p className="mt-1 max-w-2xl text-xs text-ardoise-400">{note}</p> : null}
        </div>
        {onNouveau ? (
          <button
            type="button"
            onClick={onNouveau}
            className="rounded-controle bg-sncft-bleu px-3 py-1.5 text-sm font-medium text-papier hover:opacity-90"
          >
            {libelleNouveau}
          </button>
        ) : null}
      </div>

      {filtres ? <div className="flex flex-wrap items-end gap-3">{filtres}</div> : null}

      {erreur ? (
        <p
          role="alert"
          className="rounded-controle border border-statut-r60 px-3 py-2 text-sm text-statut-r60"
        >
          {erreur}
        </p>
      ) : null}

      {/* The table scrolls inside its own box so the page body never scrolls
          sideways -- these rows are wide on a laptop and much wider on a phone. */}
      <div className="overflow-x-auto rounded-carte border border-filet bg-white">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="border-b border-filet bg-papier text-left">
              {colonnes.map((colonne) => (
                <th
                  key={colonne.cle}
                  scope="col"
                  className={[
                    "whitespace-nowrap px-3 py-2 font-medium text-ardoise-700",
                    colonne.numerique ? "text-right" : "text-left",
                  ].join(" ")}
                >
                  {colonne.entete}
                </th>
              ))}
              {actions ? (
                <th scope="col" className="px-3 py-2 text-right font-medium text-ardoise-700">
                  Actions
                </th>
              ) : null}
            </tr>
          </thead>
          <tbody>
            {chargement && lignes.length === 0 ? (
              <tr>
                <td
                  colSpan={colonnes.length + (actions ? 1 : 0)}
                  className="px-3 py-6 text-center text-ardoise-400"
                >
                  Chargement…
                </td>
              </tr>
            ) : null}

            {!chargement && lignes.length === 0 ? (
              <tr>
                <td
                  colSpan={colonnes.length + (actions ? 1 : 0)}
                  className="px-3 py-6 text-center text-ardoise-400"
                >
                  {messageVide}
                </td>
              </tr>
            ) : null}

            {lignes.map((ligne) => (
              <tr key={cleLigne(ligne)} className="border-b border-filet last:border-b-0">
                {colonnes.map((colonne) => (
                  <td
                    key={colonne.cle}
                    className={[
                      "px-3 py-2 align-top text-encre",
                      colonne.numerique ? "chiffres text-right" : "text-left",
                    ].join(" ")}
                  >
                    {colonne.valeur(ligne)}
                  </td>
                ))}
                {actions ? (
                  <td className="whitespace-nowrap px-3 py-2 text-right">{actions(ligne)}</td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between gap-3 text-xs text-ardoise-400">
        <p className="chiffres">
          {total === 0 ? "0 élément" : `${premier}–${dernier} sur ${total}`}
        </p>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => onChangerPage(page - 1)}
            disabled={page <= 0 || chargement}
            className="rounded-controle border border-filet px-2 py-1 disabled:opacity-40"
          >
            Précédent
          </button>
          <span className="chiffres">
            Page {page + 1} / {dernierePage + 1}
          </span>
          <button
            type="button"
            onClick={() => onChangerPage(page + 1)}
            disabled={page >= dernierePage || chargement}
            className="rounded-controle border border-filet px-2 py-1 disabled:opacity-40"
          >
            Suivant
          </button>
        </div>
      </div>
    </section>
  );
}
