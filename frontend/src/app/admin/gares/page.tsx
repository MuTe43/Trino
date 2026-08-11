"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import DialogueEdition, {
  type ChampFormulaire,
  type ValeursFormulaire,
} from "@/components/admin/DialogueEdition";
import TableauEditable, { type ColonneTableau } from "@/components/admin/TableauEditable";
import {
  type CorpsGare,
  creerGare,
  listerGaresAdmin,
  modifierGare,
  supprimerGare,
} from "@/lib/admin";
import type { Gare } from "@/lib/types";

const TAILLE = 20;

const CHAMPS: ChampFormulaire[] = [
  { nom: "code", libelle: "Code", type: "texte", obligatoire: true },
  { nom: "nom", libelle: "Nom", type: "texte", obligatoire: true },
  { nom: "region", libelle: "Région", type: "texte" },
  { nom: "latitude", libelle: "Latitude", type: "nombre", obligatoire: true, pas: "0.000001" },
  { nom: "longitude", libelle: "Longitude", type: "nombre", obligatoire: true, pas: "0.000001" },
  { nom: "nbQuais", libelle: "Nombre de quais", type: "nombre" },
  { nom: "responsable", libelle: "Responsable", type: "texte" },
  { nom: "actif", libelle: "Active", type: "booleen" },
];

/**
 * `PUT /gares/{id}` replaces the whole resource, so the payload carries every
 * field — a diff would blank out whatever it omitted.
 *
 * An empty field becomes `null`, never `0` or `""`. A blank coordinate coerced
 * to `0` is accepted by the server (`@NotNull` with no range check) and puts the
 * station off West Africa, which the public map then fits its bounds around; a
 * blank `responsable` coerced to `""` rewrites the NULL that all 39 seeded gares
 * carry, on every unrelated rename.
 */
function versCorps(valeurs: ValeursFormulaire): CorpsGare {
  const texte = (v: string | number | boolean | null) => {
    const s = v === null || v === undefined ? "" : String(v).trim();
    return s === "" ? null : s;
  };
  const nombre = (v: string | number | boolean | null) =>
    v === null || v === undefined || v === "" ? null : Number(v);
  return {
    code: String(valeurs.code ?? "").trim(),
    nom: String(valeurs.nom ?? "").trim(),
    region: texte(valeurs.region),
    latitude: nombre(valeurs.latitude),
    longitude: nombre(valeurs.longitude),
    nbQuais: nombre(valeurs.nbQuais),
    responsable: texte(valeurs.responsable),
    actif: Boolean(valeurs.actif),
  };
}

export default function AdminGaresPage() {
  const [gares, setGares] = useState<Gare[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [region, setRegion] = useState("");
  const [q, setQ] = useState("");
  const [qApplique, setQApplique] = useState("");
  const [chargement, setChargement] = useState(true);
  const [erreur, setErreur] = useState<string | null>(null);
  const [edition, setEdition] = useState<Gare | "nouvelle" | null>(null);
  // Every region seen so far, for the select. The référentiel is ~40 rows, so
  // one unfiltered page of 200 covers it without a dedicated endpoint.
  const [regions, setRegions] = useState<string[]>([]);

  const charger = useCallback(async () => {
    setChargement(true);
    setErreur(null);
    try {
      const reponse = await listerGaresAdmin({
        region: region || undefined,
        q: qApplique || undefined,
        page,
        taille: TAILLE,
      });
      setGares(reponse.contenu);
      setTotal(reponse.total);
    } catch (e: unknown) {
      setErreur(e instanceof Error ? e.message : "Chargement impossible.");
    } finally {
      setChargement(false);
    }
  }, [region, qApplique, page]);

  useEffect(() => {
    void charger();
  }, [charger]);

  useEffect(() => {
    listerGaresAdmin({ page: 0, taille: 200 })
      .then((reponse) => {
        const uniques = [...new Set(reponse.contenu.map((g) => g.region).filter(Boolean))];
        setRegions(uniques.sort((a, b) => a.localeCompare(b, "fr")));
      })
      .catch(() => setRegions([]));
  }, []);

  // `q` is typed, not submitted: debounce so a five-letter search is one
  // request, not five, and reset to the first page when the filter changes.
  useEffect(() => {
    const minuteur = setTimeout(() => {
      setQApplique(q.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(minuteur);
  }, [q]);

  const colonnes = useMemo<ColonneTableau<Gare>[]>(
    () => [
      { cle: "code", entete: "Code", valeur: (g) => g.code },
      { cle: "nom", entete: "Nom", valeur: (g) => g.nom },
      { cle: "region", entete: "Région", valeur: (g) => g.region || "—" },
      { cle: "latitude", entete: "Latitude", numerique: true, valeur: (g) => g.latitude },
      { cle: "longitude", entete: "Longitude", numerique: true, valeur: (g) => g.longitude },
      { cle: "nbQuais", entete: "Quais", numerique: true, valeur: (g) => g.nbQuais ?? "—" },
      { cle: "responsable", entete: "Responsable", valeur: (g) => g.responsable || "—" },
      {
        cle: "actif",
        entete: "Active",
        valeur: (g) =>
          g.actif ? (
            <span className="text-statut-a-lheure">Oui</span>
          ) : (
            <span className="text-ardoise-400">Non</span>
          ),
      },
    ],
    [],
  );

  async function supprimer(gare: Gare) {
    if (!window.confirm(`Supprimer la gare « ${gare.nom} » ?`)) return;
    try {
      await supprimerGare(gare.id);
      await charger();
    } catch (e: unknown) {
      // A gare a course or a desserte still points at answers 409 with a
      // readable message; show it rather than a bare "échec".
      setErreur(e instanceof Error ? e.message : "Suppression impossible.");
    }
  }

  return (
    <>
      <TableauEditable
        titre="Gares"
        colonnes={colonnes}
        lignes={gares}
        cleLigne={(g) => g.id}
        page={page}
        taille={TAILLE}
        total={total}
        onChangerPage={setPage}
        chargement={chargement}
        erreur={erreur}
        onNouveau={() => setEdition("nouvelle")}
        libelleNouveau="Nouvelle gare"
        messageVide="Aucune gare ne correspond à ces filtres."
        filtres={
          <>
            <label className="flex flex-col gap-1 text-xs text-ardoise-700">
              Région
              <select
                value={region}
                onChange={(e) => {
                  setRegion(e.target.value);
                  setPage(0);
                }}
                className="rounded-controle border border-filet px-2 py-1.5 text-sm"
              >
                <option value="">Toutes</option>
                {regions.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-xs text-ardoise-700">
              Recherche (nom ou code)
              <input
                type="search"
                value={q}
                onChange={(e) => setQ(e.target.value)}
                placeholder="sous…"
                className="rounded-controle border border-filet px-2 py-1.5 text-sm"
              />
            </label>
          </>
        }
        actions={(gare) => (
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setEdition(gare)}
              className="text-sncft-bleu hover:underline"
            >
              Modifier
            </button>
            <button
              type="button"
              onClick={() => void supprimer(gare)}
              className="text-statut-r60 hover:underline"
            >
              Supprimer
            </button>
          </div>
        )}
      />

      {edition ? (
        <DialogueEdition
          titre={edition === "nouvelle" ? "Nouvelle gare" : `Modifier ${edition.nom}`}
          champs={CHAMPS}
          valeursInitiales={
            edition === "nouvelle"
              ? {
                  code: "",
                  nom: "",
                  region: "",
                  latitude: null,
                  longitude: null,
                  nbQuais: null,
                  responsable: "",
                  actif: true,
                }
              : {
                  code: edition.code,
                  nom: edition.nom,
                  region: edition.region ?? "",
                  latitude: edition.latitude,
                  longitude: edition.longitude,
                  nbQuais: edition.nbQuais,
                  responsable: edition.responsable ?? "",
                  actif: edition.actif,
                }
          }
          onFermer={() => setEdition(null)}
          onEnregistrer={async (valeurs) => {
            const corps = versCorps(valeurs);
            if (edition === "nouvelle") {
              await creerGare(corps);
            } else {
              await modifierGare(edition.id, corps);
            }
            setEdition(null);
            await charger();
          }}
        />
      ) : null}
    </>
  );
}
