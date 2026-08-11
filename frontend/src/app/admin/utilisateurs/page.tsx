"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import DialogueEdition, { type ChampFormulaire } from "@/components/admin/DialogueEdition";
import TableauEditable, { type ColonneTableau } from "@/components/admin/TableauEditable";
import {
  creerUtilisateur,
  listerUtilisateurs,
  modifierUtilisateur,
  reinitialiserMotDePasse,
} from "@/lib/admin";
import { chargerUtilisateurCourant } from "@/lib/auth";
import type { Role, Utilisateur, UtilisateurCree } from "@/lib/types";

const TAILLE = 20;

const LIBELLES_ROLE: Record<Role, string> = {
  VOYAGEUR: "Voyageur",
  AGENT_CIRCULATION: "Agent de circulation",
  RESPONSABLE_EXPLOITATION: "Responsable d'exploitation",
  ADMINISTRATEUR: "Administrateur",
};

/**
 * One literal class string per role. Never assembled from the role name:
 * Tailwind 4 generates only the utilities it can literally see in the source,
 * so `` text-role-${role} `` builds green and paints nothing (invariant 8).
 */
const CLASSES_ROLE: Record<Role, string> = {
  VOYAGEUR: "text-ardoise-400",
  AGENT_CIRCULATION: "text-statut-a-lheure",
  RESPONSABLE_EXPLOITATION: "text-statut-r10",
  ADMINISTRATEUR: "text-sncft-bleu",
};

const ROLES = Object.keys(LIBELLES_ROLE) as Role[];

const CHAMPS_CREATION: ChampFormulaire[] = [
  { nom: "email", libelle: "Email", type: "texte", obligatoire: true },
  { nom: "nom", libelle: "Nom", type: "texte", obligatoire: true },
  {
    nom: "role",
    libelle: "Rôle",
    type: "selection",
    obligatoire: true,
    options: ROLES.map((r) => ({ valeur: r, libelle: LIBELLES_ROLE[r] })),
    aide: "Le mot de passe est généré par le serveur et affiché une seule fois.",
  },
];

/**
 * Editing someone else: any role. Editing yourself: `ADMINISTRATEUR` is the only
 * option offered, because the server refuses anything else with 409 and there is
 * no reason to let the form propose a change it knows will be rejected. The
 * server remains the authority — this only stops the console from disagreeing
 * with it out loud.
 */
function champsModification(soiMeme: boolean): ChampFormulaire[] {
  return [
    { nom: "email", libelle: "Email", type: "lecture", aide: "L'email n'est pas modifiable." },
    { nom: "nom", libelle: "Nom", type: "texte", obligatoire: true },
    {
      nom: "role",
      libelle: "Rôle",
      type: "selection",
      obligatoire: true,
      options: (soiMeme ? (["ADMINISTRATEUR"] as Role[]) : ROLES).map((r) => ({
        valeur: r,
        libelle: LIBELLES_ROLE[r],
      })),
      aide: soiMeme
        ? "Vous ne pouvez pas retirer son rôle ADMINISTRATEUR à votre propre compte."
        : undefined,
    },
  ];
}

export default function AdminUtilisateursPage() {
  const [utilisateurs, setUtilisateurs] = useState<Utilisateur[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [chargement, setChargement] = useState(true);
  const [erreur, setErreur] = useState<string | null>(null);
  const [edition, setEdition] = useState<Utilisateur | "nouveau" | null>(null);
  const [motDePasse, setMotDePasse] = useState<UtilisateurCree | null>(null);
  const [copie, setCopie] = useState(false);
  const [moi, setMoi] = useState<Utilisateur | null>(null);

  const charger = useCallback(async () => {
    setChargement(true);
    setErreur(null);
    try {
      const reponse = await listerUtilisateurs(page, TAILLE);
      setUtilisateurs(reponse.contenu);
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
    chargerUtilisateurCourant().then(setMoi);
  }, []);

  const estMoi = useCallback((u: Utilisateur) => moi != null && u.id === moi.id, [moi]);

  async function basculerActivation(utilisateur: Utilisateur) {
    setErreur(null);
    try {
      await modifierUtilisateur(utilisateur.id, { actif: !utilisateur.actif });
      await charger();
    } catch (e: unknown) {
      // The server refuses self-deactivation with 409 and a message saying so;
      // it is the authority here, the disabled button below is only a courtesy.
      setErreur(e instanceof Error ? e.message : "Modification impossible.");
    }
  }

  async function reinitialiser(utilisateur: Utilisateur) {
    if (
      !window.confirm(
        `Générer un nouveau mot de passe pour ${utilisateur.nom} ? L'ancien cessera immédiatement de fonctionner.`,
      )
    ) {
      return;
    }
    setErreur(null);
    try {
      const cree = await reinitialiserMotDePasse(utilisateur.id);
      setCopie(false);
      setMotDePasse(cree);
    } catch (e: unknown) {
      setErreur(e instanceof Error ? e.message : "Réinitialisation impossible.");
    }
  }

  const colonnes = useMemo<ColonneTableau<Utilisateur>[]>(
    () => [
      { cle: "nom", entete: "Nom", valeur: (u) => u.nom },
      { cle: "email", entete: "Email", valeur: (u) => u.email },
      {
        cle: "role",
        entete: "Rôle",
        valeur: (u) => <span className={CLASSES_ROLE[u.role]}>{LIBELLES_ROLE[u.role]}</span>,
      },
      {
        cle: "actif",
        entete: "Actif",
        valeur: (u) =>
          u.actif ? (
            <span className="text-statut-a-lheure">Oui</span>
          ) : (
            <span className="text-ardoise-400">Non</span>
          ),
      },
    ],
    [],
  );

  return (
    <>
      {motDePasse ? (
        <div className="mx-4 mt-4 rounded-carte border border-statut-r10 bg-white p-4 sm:mx-6">
          <h2 className="font-condensee text-base font-medium text-encre">
            Mot de passe initial de {motDePasse.nom}
          </h2>
          <p className="mt-1 text-sm text-ardoise-700">
            Notez-le maintenant et transmettez-le à la personne concernée.{" "}
            {/* font-medium, not <strong>: only weights 400 and 500 of IBM Plex
                are loaded, and preflight's `bolder` would synthesise a fake bold. */}
            <span className="font-medium">Il ne sera plus jamais affiché</span> : le serveur
            n&apos;en conserve que l&apos;empreinte. En cas de perte, générez-en un nouveau.
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <code className="chiffres rounded-controle border border-filet bg-papier px-3 py-2 text-base text-encre">
              {motDePasse.motDePasseInitial}
            </code>
            <button
              type="button"
              onClick={() => {
                void navigator.clipboard
                  .writeText(motDePasse.motDePasseInitial)
                  .then(() => setCopie(true))
                  .catch(() => setCopie(false));
              }}
              className="rounded-controle border border-filet px-3 py-1.5 text-sm"
            >
              {copie ? "Copié" : "Copier"}
            </button>
            <button
              type="button"
              onClick={() => setMotDePasse(null)}
              className="text-sm text-ardoise-400 hover:underline"
            >
              J&apos;ai noté ce mot de passe
            </button>
          </div>
        </div>
      ) : null}

      <TableauEditable
        titre="Utilisateurs"
        note="Désactiver un compte ne le supprime pas : le journal de connexions le référence, et un journal d'audit ne se troue pas."
        colonnes={colonnes}
        lignes={utilisateurs}
        cleLigne={(u) => u.id}
        page={page}
        taille={TAILLE}
        total={total}
        onChangerPage={setPage}
        chargement={chargement}
        erreur={erreur}
        onNouveau={() => setEdition("nouveau")}
        libelleNouveau="Nouvel utilisateur"
        messageVide="Aucun utilisateur."
        actions={(utilisateur) => (
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setEdition(utilisateur)}
              className="text-sncft-bleu hover:underline"
            >
              Modifier
            </button>
            <button
              type="button"
              onClick={() => void reinitialiser(utilisateur)}
              className="text-sncft-bleu hover:underline"
            >
              Mot de passe
            </button>
            <button
              type="button"
              onClick={() => void basculerActivation(utilisateur)}
              disabled={estMoi(utilisateur) && utilisateur.actif}
              title={
                estMoi(utilisateur) && utilisateur.actif
                  ? "Vous ne pouvez pas désactiver votre propre compte."
                  : undefined
              }
              className="text-statut-r60 hover:underline disabled:cursor-not-allowed disabled:text-ardoise-400 disabled:no-underline"
            >
              {utilisateur.actif ? "Désactiver" : "Activer"}
            </button>
          </div>
        )}
      />

      {edition ? (
        <DialogueEdition
          titre={edition === "nouveau" ? "Nouvel utilisateur" : `Modifier ${edition.nom}`}
          champs={
            edition === "nouveau" ? CHAMPS_CREATION : champsModification(estMoi(edition))
          }
          valeursInitiales={
            edition === "nouveau"
              ? { email: "", nom: "", role: "AGENT_CIRCULATION" }
              : { email: edition.email, nom: edition.nom, role: edition.role }
          }
          onFermer={() => setEdition(null)}
          onEnregistrer={async (valeurs) => {
            if (edition === "nouveau") {
              const cree = await creerUtilisateur({
                email: String(valeurs.email ?? "").trim(),
                nom: String(valeurs.nom ?? ""),
                role: String(valeurs.role ?? "VOYAGEUR") as Role,
              });
              setCopie(false);
              setMotDePasse(cree);
            } else {
              await modifierUtilisateur(edition.id, {
                nom: String(valeurs.nom ?? ""),
                role: String(valeurs.role ?? edition.role) as Role,
              });
            }
            setEdition(null);
            await charger();
          }}
        />
      ) : null}
    </>
  );
}
