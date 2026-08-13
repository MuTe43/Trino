"use client";

import { useCallback, useEffect, useState } from "react";
import DialogueEdition, {
  type ChampFormulaire,
  type ValeursFormulaire,
} from "@/components/admin/DialogueEdition";
import TableauEditable, { type ColonneTableau } from "@/components/admin/TableauEditable";
import { creerRegleAlerte, listerReglesAlerte, modifierRegleAlerte } from "@/lib/admin";
import { ApiError } from "@/lib/api";
import type { CanalType, EvenementAlerte, Gravite, RegleAlerteDTO } from "@/lib/types";

/**
 * <em>Gérer les alertes</em> — the administrator's screen for deciding what is
 * worth notifying about, and on which channels.
 *
 * The rule says what an event is <b>allowed</b> to use; the passenger's
 * subscription says what they <b>want</b>. The engine emits on the intersection,
 * so unticking a channel here silences it for everyone, whatever they asked for.
 * The screen says so rather than leaving it to be discovered.
 */

/**
 * Every class name written out in full — invariant 8. Tailwind 4 only emits a
 * utility whose name it can see in the source, so a template literal like
 * `bg-${etat}` compiles, lints and builds green and simply has no style. That
 * cost nine of ten status colours in phase 4, and a status-to-colour lookup is
 * exactly the shape it happens to.
 */
const CLASSES_ETAT: Record<"actif" | "inactif", string> = {
  actif: "text-statut-a-lheure",
  inactif: "text-ardoise-400",
};

const LIBELLES_EVENEMENT: Record<EvenementAlerte, string> = {
  RETARD_SEUIL: "Retard au-delà d'un seuil",
  COURSE_ANNULEE: "Course annulée",
  INCIDENT_DECLARE: "Incident déclaré",
  INCIDENT_RESOLU: "Incident résolu",
};

const LIBELLES_CANAL: Record<CanalType, string> = {
  IN_APP: "Sur le site",
  EMAIL: "Email",
  SMS: "SMS (stub)",
  AFFICHAGE: "Affichage gare",
};

const GRAVITES: Gravite[] = ["MINEURE", "MOYENNE", "MAJEURE", "CRITIQUE"];

const OPTIONS_CANAUX = (Object.keys(LIBELLES_CANAL) as CanalType[]).map((canal) => ({
  valeur: canal,
  libelle: LIBELLES_CANAL[canal],
}));

interface EditionEnCours {
  regle: RegleAlerteDTO | null;
}

export default function PageAlertes() {
  const [regles, setRegles] = useState<RegleAlerteDTO[]>([]);
  const [chargement, setChargement] = useState(true);
  const [erreur, setErreur] = useState<string | null>(null);
  const [edition, setEdition] = useState<EditionEnCours | null>(null);

  const charger = useCallback(async () => {
    setChargement(true);
    setErreur(null);
    try {
      setRegles(await listerReglesAlerte());
    } catch (e) {
      setErreur(e instanceof ApiError ? e.message : "Chargement impossible.");
    } finally {
      setChargement(false);
    }
  }, []);

  useEffect(() => {
    void charger();
  }, [charger]);

  const colonnes: ColonneTableau<RegleAlerteDTO>[] = [
    {
      cle: "evenement",
      entete: "Événement",
      valeur: (regle) => LIBELLES_EVENEMENT[regle.evenement],
    },
    {
      cle: "seuil",
      entete: "Seuil",
      numerique: true,
      valeur: (regle) => (regle.seuilMin === null ? "—" : `${regle.seuilMin} min`),
    },
    {
      cle: "gravite",
      entete: "Gravité min.",
      valeur: (regle) => regle.graviteMin ?? "Toutes",
    },
    {
      cle: "canaux",
      entete: "Canaux",
      valeur: (regle) => regle.canaux.map((canal) => LIBELLES_CANAL[canal]).join(", "),
    },
    {
      cle: "actif",
      entete: "État",
      valeur: (regle) => (
        <span className={regle.actif ? CLASSES_ETAT.actif : CLASSES_ETAT.inactif}>
          {regle.actif ? "Active" : "Inactive"}
        </span>
      ),
    },
    {
      cle: "modifiePar",
      entete: "Modifiée par",
      valeur: (regle) => regle.modifieParNom ?? "—",
    },
  ];

  const champs: ChampFormulaire[] = [
    ...(edition?.regle
      ? ([
          {
            nom: "evenement",
            libelle: "Événement",
            type: "lecture",
            aide: "Non modifiable : changer l'événement ferait une autre règle, pas une modification de celle-ci.",
          },
        ] as ChampFormulaire[])
      : ([
          {
            nom: "evenement",
            libelle: "Événement",
            type: "selection",
            obligatoire: true,
            options: (Object.keys(LIBELLES_EVENEMENT) as EvenementAlerte[]).map((evenement) => ({
              valeur: evenement,
              libelle: LIBELLES_EVENEMENT[evenement],
            })),
          },
        ] as ChampFormulaire[])),
    {
      nom: "seuilMin",
      libelle: "Seuil de retard (minutes)",
      type: "nombre",
      aide: "Obligatoire pour « Retard au-delà d'un seuil », et refusé pour les autres événements.",
    },
    {
      nom: "graviteMin",
      libelle: "Gravité minimale",
      type: "selection",
      options: [
        { valeur: "", libelle: "Toutes" },
        ...GRAVITES.map((gravite) => ({ valeur: gravite, libelle: gravite })),
      ],
      aide: "Ne s'applique qu'aux événements d'incident.",
    },
    {
      nom: "canaux",
      libelle: "Canaux autorisés",
      type: "multi",
      aide: "Un canal décoché ici n'est plus émis, même pour un voyageur qui l'a demandé. SMS journalise seulement ; Affichage est déjà porté par le tableau de gare.",
    },
    { nom: "actif", libelle: "Active", type: "booleen" },
  ].map((champ) =>
    champ.nom === "canaux" ? { ...champ, options: OPTIONS_CANAUX } : champ,
  ) as ChampFormulaire[];

  function valeursInitiales(regle: RegleAlerteDTO | null): ValeursFormulaire {
    return {
      evenement: regle ? LIBELLES_EVENEMENT[regle.evenement] : "RETARD_SEUIL",
      seuilMin: regle?.seuilMin ?? null,
      graviteMin: regle?.graviteMin ?? "",
      canaux: (regle?.canaux ?? ["IN_APP"]).join(","),
      actif: regle ? regle.actif : true,
    };
  }

  async function enregistrer(valeurs: ValeursFormulaire) {
    const canaux = String(valeurs.canaux ?? "")
      .split(",")
      .filter((v) => v !== "") as CanalType[];
    const seuilMin = valeurs.seuilMin === null || valeurs.seuilMin === "" ? null : Number(valeurs.seuilMin);
    const graviteMin = valeurs.graviteMin === "" || valeurs.graviteMin === null
      ? null
      : (String(valeurs.graviteMin) as Gravite);

    if (edition?.regle) {
      await modifierRegleAlerte(edition.regle.id, {
        seuilMin,
        graviteMin,
        // "Toutes" is a null severity, and on a PATCH a null is indistinguishable
        // from an absent field. Without the flag the choice was silently
        // discarded: 200, dialog closed, old value still there.
        effacerGraviteMin: graviteMin === null,
        canaux,
        actif: Boolean(valeurs.actif),
      });
    } else {
      await creerRegleAlerte({
        evenement: String(valeurs.evenement) as EvenementAlerte,
        seuilMin,
        graviteMin,
        canaux,
        actif: Boolean(valeurs.actif),
      });
    }
    setEdition(null);
    await charger();
  }

  return (
    <>
      <TableauEditable
        titre="Alertes"
        note="Une règle décide si un événement mérite une notification et sur quels canaux. Le voyageur choisit ensuite ce qu'il veut recevoir : l'envoi a lieu à l'intersection des deux."
        colonnes={colonnes}
        lignes={regles}
        cleLigne={(regle) => regle.id}
        // Unpaginated endpoint: one page holding everything, and paging
        // controls that can never move. Passing the real count keeps the
        // footer's "n éléments" honest.
        page={0}
        taille={Math.max(regles.length, 1)}
        total={regles.length}
        onChangerPage={() => undefined}
        chargement={chargement}
        erreur={erreur}
        onNouveau={() => setEdition({ regle: null })}
        libelleNouveau="Nouvelle règle"
        messageVide="Aucune règle d'alerte."
        actions={(regle) => (
          <button
            type="button"
            onClick={() => setEdition({ regle })}
            className="rounded-controle border border-filet px-2 py-1 text-xs text-ardoise-700"
          >
            Modifier
          </button>
        )}
      />

      {edition ? (
        <DialogueEdition
          titre={edition.regle ? "Modifier la règle" : "Nouvelle règle d'alerte"}
          champs={champs}
          valeursInitiales={valeursInitiales(edition.regle)}
          onEnregistrer={enregistrer}
          onFermer={() => setEdition(null)}
        />
      ) : null}
    </>
  );
}
