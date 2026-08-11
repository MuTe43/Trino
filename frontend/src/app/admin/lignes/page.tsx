"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import DialogueEdition, {
  type ChampFormulaire,
  type ValeursFormulaire,
} from "@/components/admin/DialogueEdition";
import TableauEditable, { type ColonneTableau } from "@/components/admin/TableauEditable";
import { listerDesserteAdmin, listerLignesAdmin, modifierLigne, supprimerLigne } from "@/lib/admin";
import type { DesserteDTO, LigneDTO } from "@/lib/types";

const TAILLE = 20;

/**
 * `trace` is deliberately absent from this list and shown read-only below: a
 * textarea of coordinate pairs is a loaded gun pointed at the map, and phase 5
 * measured how much of the product hangs off that polyline. `pointsTrace` is a
 * read-only field for the same reason.
 */
const CHAMPS: ChampFormulaire[] = [
  { nom: "code", libelle: "Code", type: "texte", obligatoire: true },
  { nom: "nom", libelle: "Nom", type: "texte", obligatoire: true },
  { nom: "distanceKm", libelle: "Distance (km)", type: "nombre", pas: "0.01" },
  { nom: "vitesseMaxKmh", libelle: "Vitesse max (km/h)", type: "nombre" },
  { nom: "tempsTheoriqueMin", libelle: "Temps théorique (min)", type: "nombre" },
  {
    nom: "pointsTrace",
    libelle: "Tracé",
    type: "lecture",
    aide: "Le tracé n'est pas modifiable ici : la carte, le simulateur et le moteur de retards en dépendent.",
  },
  { nom: "actif", libelle: "Active", type: "booleen" },
];

export default function AdminLignesPage() {
  const [lignes, setLignes] = useState<LigneDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [chargement, setChargement] = useState(true);
  const [erreur, setErreur] = useState<string | null>(null);
  const [edition, setEdition] = useState<LigneDTO | null>(null);
  const [desserteOuverte, setDesserteOuverte] = useState<LigneDTO | null>(null);
  const [desserte, setDesserte] = useState<DesserteDTO[]>([]);

  const charger = useCallback(async () => {
    setChargement(true);
    setErreur(null);
    try {
      const reponse = await listerLignesAdmin(page, TAILLE);
      setLignes(reponse.contenu);
      setTotal(reponse.total);
    } catch (e: unknown) {
      setErreur(e instanceof Error ? e.message : "Chargement impossible.");
    } finally {
      setChargement(false);
    }
  }, [page]);

  useEffect(() => {
    void charger();
  }, [charger]);

  useEffect(() => {
    if (!desserteOuverte) {
      setDesserte([]);
      return;
    }
    let annule = false;
    listerDesserteAdmin(desserteOuverte.id)
      .then((arrets) => {
        if (!annule) setDesserte(arrets);
      })
      .catch(() => {
        if (!annule) setDesserte([]);
      });
    return () => {
      annule = true;
    };
  }, [desserteOuverte]);

  const colonnes = useMemo<ColonneTableau<LigneDTO>[]>(
    () => [
      { cle: "code", entete: "Code", valeur: (l) => l.code },
      { cle: "nom", entete: "Nom", valeur: (l) => l.nom },
      { cle: "distanceKm", entete: "Distance (km)", numerique: true, valeur: (l) => l.distanceKm ?? "—" },
      { cle: "vitesse", entete: "V. max", numerique: true, valeur: (l) => l.vitesseMaxKmh ?? "—" },
      { cle: "temps", entete: "Temps (min)", numerique: true, valeur: (l) => l.tempsTheoriqueMin ?? "—" },
      { cle: "points", entete: "Points du tracé", numerique: true, valeur: (l) => l.trace?.length ?? 0 },
      {
        cle: "actif",
        entete: "Active",
        valeur: (l) =>
          l.actif ? (
            <span className="text-statut-a-lheure">Oui</span>
          ) : (
            <span className="text-ardoise-400">Non</span>
          ),
      },
    ],
    [],
  );

  async function supprimer(ligne: LigneDTO) {
    if (!window.confirm(`Supprimer la ligne « ${ligne.nom} » ?`)) return;
    try {
      await supprimerLigne(ligne.id);
      await charger();
    } catch (e: unknown) {
      setErreur(e instanceof Error ? e.message : "Suppression impossible.");
    }
  }

  function apercuTrace(ligne: LigneDTO): string {
    const trace = ligne.trace ?? [];
    if (trace.length === 0) return "aucun point";
    const point = (p: [number, number]) => `[${p[0].toFixed(4)}, ${p[1].toFixed(4)}]`;
    return `${trace.length} points — de ${point(trace[0])} à ${point(trace[trace.length - 1])}`;
  }

  return (
    <>
      <TableauEditable
        titre="Lignes"
        note="Le tracé et la desserte sont consultables mais non modifiables : réordonner les arrêts casse la monotonie des PK dont dépend le moteur de retards, et créer une ligne suppose de dessiner une polyligne, hors périmètre de cette console."
        colonnes={colonnes}
        lignes={lignes}
        cleLigne={(l) => l.id}
        page={page}
        taille={TAILLE}
        total={total}
        onChangerPage={setPage}
        chargement={chargement}
        erreur={erreur}
        messageVide="Aucune ligne."
        actions={(ligne) => (
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setDesserteOuverte(desserteOuverte?.id === ligne.id ? null : ligne)}
              className="text-sncft-bleu hover:underline"
            >
              Desserte
            </button>
            <button
              type="button"
              onClick={() => setEdition(ligne)}
              className="text-sncft-bleu hover:underline"
            >
              Modifier
            </button>
            <button
              type="button"
              onClick={() => void supprimer(ligne)}
              className="text-statut-r60 hover:underline"
            >
              Supprimer
            </button>
          </div>
        )}
      />

      {desserteOuverte ? (
        <section className="px-4 pb-6 sm:px-6">
          <div className="rounded-carte border border-filet bg-white">
            <div className="flex items-baseline justify-between border-b border-filet px-3 py-2">
              <h2 className="font-condensee text-sm font-medium text-encre">
                Desserte — {desserteOuverte.nom}
              </h2>
              <button
                type="button"
                onClick={() => setDesserteOuverte(null)}
                className="text-xs text-ardoise-400 hover:underline"
              >
                Fermer
              </button>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full border-collapse text-sm">
                <thead>
                  <tr className="border-b border-filet bg-papier text-left">
                    <th scope="col" className="px-3 py-2 font-medium text-ardoise-700">Ordre</th>
                    <th scope="col" className="px-3 py-2 font-medium text-ardoise-700">Gare</th>
                    <th scope="col" className="px-3 py-2 text-right font-medium text-ardoise-700">PK (km)</th>
                    <th scope="col" className="px-3 py-2 text-right font-medium text-ardoise-700">Arrivée (min)</th>
                    <th scope="col" className="px-3 py-2 text-right font-medium text-ardoise-700">Départ (min)</th>
                  </tr>
                </thead>
                <tbody>
                  {desserte.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="px-3 py-4 text-center text-ardoise-400">
                        Aucun arrêt.
                      </td>
                    </tr>
                  ) : null}
                  {desserte.map((arret) => (
                    <tr key={arret.id} className="border-b border-filet last:border-b-0">
                      <td className="chiffres px-3 py-1.5 text-encre">{arret.ordre}</td>
                      <td className="px-3 py-1.5 text-encre">{arret.gareNom}</td>
                      <td className="chiffres px-3 py-1.5 text-right text-encre">{arret.pkKm ?? "—"}</td>
                      <td className="chiffres px-3 py-1.5 text-right text-encre">
                        {arret.offsetArriveeMin ?? "—"}
                      </td>
                      <td className="chiffres px-3 py-1.5 text-right text-encre">
                        {arret.offsetDepartMin ?? "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </section>
      ) : null}

      {edition ? (
        <DialogueEdition
          titre={`Modifier ${edition.nom}`}
          champs={CHAMPS}
          valeursInitiales={{
            code: edition.code,
            nom: edition.nom,
            distanceKm: edition.distanceKm,
            vitesseMaxKmh: edition.vitesseMaxKmh,
            tempsTheoriqueMin: edition.tempsTheoriqueMin,
            pointsTrace: apercuTrace(edition),
            actif: edition.actif,
          }}
          onFermer={() => setEdition(null)}
          onEnregistrer={async (valeurs: ValeursFormulaire) => {
            // The trace is handed back exactly as it was loaded. PUT replaces
            // the whole resource and the server requires at least two [lon,lat]
            // points, so omitting it turns a rename into a 400 on a field this
            // form deliberately does not show.
            const nombre = (v: string | number | boolean | null) =>
              v === null || v === undefined || v === "" ? null : Number(v);
            await modifierLigne(edition.id, {
              code: String(valeurs.code ?? "").trim(),
              nom: String(valeurs.nom ?? "").trim(),
              // null, not 0: an emptied optional field must clear the column,
              // not overwrite a real distance with a wrong one.
              distanceKm: nombre(valeurs.distanceKm),
              vitesseMaxKmh: nombre(valeurs.vitesseMaxKmh),
              tempsTheoriqueMin: nombre(valeurs.tempsTheoriqueMin),
              trace: edition.trace,
              actif: Boolean(valeurs.actif),
            });
            setEdition(null);
            await charger();
          }}
        />
      ) : null}
    </>
  );
}
