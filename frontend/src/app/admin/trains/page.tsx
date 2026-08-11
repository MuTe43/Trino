"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import DialogueEdition, {
  type ChampFormulaire,
  type ValeursFormulaire,
} from "@/components/admin/DialogueEdition";
import TableauEditable, { type ColonneTableau } from "@/components/admin/TableauEditable";
import {
  creerTrain,
  listerLignesAdmin,
  listerTrainsAdmin,
  modifierTrain,
  supprimerTrain,
} from "@/lib/admin";
import type { LigneDTO, Train, TypeTrain } from "@/lib/types";

const TAILLE = 20;

/** Every member spelled out, so adding one to the backend enum is a compile
 * error here rather than a silently missing option. */
const LIBELLES_TYPE: Record<TypeTrain, string> = {
  EXPRESS: "Express",
  BANLIEUE: "Banlieue",
  GRANDES_LIGNES: "Grandes lignes",
  FRET: "Fret",
};

const TYPES = Object.keys(LIBELLES_TYPE) as TypeTrain[];

export default function AdminTrainsPage() {
  const [trains, setTrains] = useState<Train[]>([]);
  const [lignes, setLignes] = useState<LigneDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [type, setType] = useState<TypeTrain | "">("");
  const [ligneId, setLigneId] = useState<string>("");
  const [chargement, setChargement] = useState(true);
  const [erreur, setErreur] = useState<string | null>(null);
  const [edition, setEdition] = useState<Train | "nouveau" | null>(null);

  const charger = useCallback(async () => {
    setChargement(true);
    setErreur(null);
    try {
      const reponse = await listerTrainsAdmin({
        type: type || undefined,
        ligneId: ligneId ? Number(ligneId) : undefined,
        page,
        taille: TAILLE,
      });
      setTrains(reponse.contenu);
      setTotal(reponse.total);
    } catch (e: unknown) {
      setErreur(e instanceof Error ? e.message : "Chargement impossible.");
    } finally {
      setChargement(false);
    }
  }, [type, ligneId, page]);

  useEffect(() => {
    void charger();
  }, [charger]);

  useEffect(() => {
    listerLignesAdmin(0, 200)
      .then((reponse) => setLignes(reponse.contenu))
      .catch(() => setLignes([]));
  }, []);

  const nomLigne = useCallback(
    (id: number | null) => (id == null ? "—" : (lignes.find((l) => l.id === id)?.nom ?? `#${id}`)),
    [lignes],
  );

  const colonnes = useMemo<ColonneTableau<Train>[]>(
    () => [
      { cle: "numero", entete: "Numéro", valeur: (t) => t.numero },
      { cle: "nom", entete: "Nom", valeur: (t) => t.nom || "—" },
      { cle: "type", entete: "Type", valeur: (t) => LIBELLES_TYPE[t.type] },
      { cle: "ligne", entete: "Ligne", valeur: (t) => nomLigne(t.ligneId) },
      { cle: "capacite", entete: "Capacité", numerique: true, valeur: (t) => t.capacite ?? "—" },
      { cle: "vitesse", entete: "V. max", numerique: true, valeur: (t) => t.vitesseMaxKmh ?? "—" },
      {
        cle: "actif",
        entete: "Actif",
        valeur: (t) =>
          t.actif ? (
            <span className="text-statut-a-lheure">Oui</span>
          ) : (
            <span className="text-ardoise-400">Non</span>
          ),
      },
    ],
    [nomLigne],
  );

  const champs = useMemo<ChampFormulaire[]>(
    () => [
      { nom: "numero", libelle: "Numéro", type: "texte", obligatoire: true },
      { nom: "nom", libelle: "Nom", type: "texte" },
      {
        nom: "type",
        libelle: "Type",
        type: "selection",
        obligatoire: true,
        options: TYPES.map((t) => ({ valeur: t, libelle: LIBELLES_TYPE[t] })),
      },
      {
        nom: "ligneId",
        libelle: "Ligne",
        type: "selection",
        // A trainset need not be assigned: ligne_id is nullable.
        options: [
          { valeur: "", libelle: "— aucune —" },
          ...lignes.map((l) => ({ valeur: String(l.id), libelle: `${l.code} — ${l.nom}` })),
        ],
      },
      { nom: "capacite", libelle: "Capacité", type: "nombre" },
      { nom: "vitesseMaxKmh", libelle: "Vitesse max (km/h)", type: "nombre" },
      { nom: "actif", libelle: "Actif", type: "booleen" },
    ],
    [lignes],
  );

  function versCorps(valeurs: ValeursFormulaire): Omit<Train, "id"> {
    return {
      numero: String(valeurs.numero ?? ""),
      nom: valeurs.nom ? String(valeurs.nom) : null,
      type: String(valeurs.type ?? "EXPRESS") as TypeTrain,
      ligneId: valeurs.ligneId ? Number(valeurs.ligneId) : null,
      capacite: valeurs.capacite === null ? null : Number(valeurs.capacite),
      vitesseMaxKmh: valeurs.vitesseMaxKmh === null ? null : Number(valeurs.vitesseMaxKmh),
      actif: Boolean(valeurs.actif),
    };
  }

  async function supprimer(train: Train) {
    if (!window.confirm(`Supprimer le train ${train.numero} ?`)) return;
    try {
      await supprimerTrain(train.id);
      await charger();
    } catch (e: unknown) {
      setErreur(e instanceof Error ? e.message : "Suppression impossible.");
    }
  }

  return (
    <>
      <TableauEditable
        titre="Trains"
        colonnes={colonnes}
        lignes={trains}
        cleLigne={(t) => t.id}
        page={page}
        taille={TAILLE}
        total={total}
        onChangerPage={setPage}
        chargement={chargement}
        erreur={erreur}
        onNouveau={() => setEdition("nouveau")}
        libelleNouveau="Nouveau train"
        messageVide="Aucun train ne correspond à ces filtres."
        filtres={
          <>
            <label className="flex flex-col gap-1 text-xs text-ardoise-700">
              Type
              <select
                value={type}
                onChange={(e) => {
                  setType(e.target.value as TypeTrain | "");
                  setPage(0);
                }}
                className="rounded-controle border border-filet px-2 py-1.5 text-sm"
              >
                <option value="">Tous</option>
                {TYPES.map((t) => (
                  <option key={t} value={t}>
                    {LIBELLES_TYPE[t]}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-xs text-ardoise-700">
              Ligne
              <select
                value={ligneId}
                onChange={(e) => {
                  setLigneId(e.target.value);
                  setPage(0);
                }}
                className="rounded-controle border border-filet px-2 py-1.5 text-sm"
              >
                <option value="">Toutes</option>
                {lignes.map((l) => (
                  <option key={l.id} value={String(l.id)}>
                    {l.code} — {l.nom}
                  </option>
                ))}
              </select>
            </label>
          </>
        }
        actions={(train) => (
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setEdition(train)}
              className="text-sncft-bleu hover:underline"
            >
              Modifier
            </button>
            <button
              type="button"
              onClick={() => void supprimer(train)}
              className="text-statut-r60 hover:underline"
            >
              Supprimer
            </button>
          </div>
        )}
      />

      {edition ? (
        <DialogueEdition
          titre={edition === "nouveau" ? "Nouveau train" : `Modifier ${edition.numero}`}
          champs={champs}
          valeursInitiales={
            edition === "nouveau"
              ? {
                  numero: "",
                  nom: "",
                  type: "EXPRESS",
                  ligneId: "",
                  capacite: null,
                  vitesseMaxKmh: null,
                  actif: true,
                }
              : {
                  numero: edition.numero,
                  nom: edition.nom ?? "",
                  type: edition.type,
                  ligneId: edition.ligneId == null ? "" : String(edition.ligneId),
                  capacite: edition.capacite,
                  vitesseMaxKmh: edition.vitesseMaxKmh,
                  actif: edition.actif,
                }
          }
          onFermer={() => setEdition(null)}
          onEnregistrer={async (valeurs) => {
            const corps = versCorps(valeurs);
            if (edition === "nouveau") {
              await creerTrain(corps);
            } else {
              await modifierTrain(edition.id, corps);
            }
            setEdition(null);
            await charger();
          }}
        />
      ) : null}
    </>
  );
}
