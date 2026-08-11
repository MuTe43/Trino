"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import IndicateurSucces from "@/components/admin/IndicateurSucces";
import TableauEditable, { type ColonneTableau } from "@/components/admin/TableauEditable";
import { listerJournal, listerUtilisateurs } from "@/lib/admin";
import { chargerToutesPages } from "@/lib/api";
import { formaterDateHeure } from "@/lib/temps";
import type { JournalConnexion, Utilisateur } from "@/lib/types";

const TAILLE = 20;

/**
 * The login audit trail, readable for the first time in phase 7 — `/auth/login`
 * has been writing it on every attempt, successful or not, since phase 1.
 *
 * `du`/`au` are sent as plain dates and bucketed server-side in Africa/Tunis
 * (invariant 6); nothing here converts a timezone by hand.
 */
export default function AdminJournalPage() {
  const [entrees, setEntrees] = useState<JournalConnexion[]>([]);
  const [utilisateurs, setUtilisateurs] = useState<Utilisateur[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [succes, setSucces] = useState<"" | "true" | "false">("");
  const [utilisateurId, setUtilisateurId] = useState("");
  const [du, setDu] = useState("");
  const [au, setAu] = useState("");
  const [chargement, setChargement] = useState(true);
  const [erreur, setErreur] = useState<string | null>(null);

  const charger = useCallback(async () => {
    setChargement(true);
    setErreur(null);
    try {
      const reponse = await listerJournal({
        succes: succes === "" ? undefined : succes === "true",
        utilisateurId: utilisateurId ? Number(utilisateurId) : undefined,
        du: du || undefined,
        au: au || undefined,
        page,
        taille: TAILLE,
      });
      setEntrees(reponse.contenu);
      setTotal(reponse.total);
    } catch (e: unknown) {
      setErreur(e instanceof Error ? e.message : "Chargement impossible.");
    } finally {
      setChargement(false);
    }
  }, [succes, utilisateurId, du, au, page]);

  useEffect(() => {
    void charger();
  }, [charger]);

  useEffect(() => {
    chargerToutesPages<Utilisateur>((p, taille) => listerUtilisateurs(p, taille))
      .then(setUtilisateurs)
      .catch(() => setUtilisateurs([]));
  }, []);

  const colonnes = useMemo<ColonneTableau<JournalConnexion>[]>(
    () => [
      {
        cle: "horodatage",
        entete: "Horodatage",
        // `chiffres` here as on the overview's copy of this table: two views of
        // one table must not disagree about whether its digits line up.
        valeur: (e) => (
          <span className="chiffres whitespace-nowrap">{formaterDateHeure(e.horodatage)}</span>
        ),
      },
      { cle: "emailTente", entete: "Email tenté", valeur: (e) => e.emailTente },
      {
        cle: "utilisateur",
        entete: "Compte",
        // Null when the attempted email matches no account — which is most of
        // what a failed-login list is for.
        valeur: (e) => e.utilisateurNom ?? <span className="text-ardoise-400">inconnu</span>,
      },
      {
        cle: "ip",
        entete: "Adresse IP",
        valeur: (e) => <span className="chiffres">{e.adresseIp ?? "—"}</span>,
      },
      {
        cle: "userAgent",
        entete: "Client",
        valeur: (e) => (
          <span className="block max-w-xs truncate" title={e.userAgent ?? undefined}>
            {e.userAgent ?? "—"}
          </span>
        ),
      },
      { cle: "succes", entete: "Résultat", valeur: (e) => <IndicateurSucces succes={e.succes} /> },
    ],
    [],
  );

  function surFiltre(action: () => void) {
    action();
    setPage(0);
  }

  return (
    <TableauEditable
      titre="Journal de connexions"
      note="Toutes les tentatives d'authentification, réussies comme échouées. Les dates sont bornées en heure de Tunis."
      colonnes={colonnes}
      lignes={entrees}
      cleLigne={(e) => e.id}
      page={page}
      taille={TAILLE}
      total={total}
      onChangerPage={setPage}
      chargement={chargement}
      erreur={erreur}
      messageVide="Aucune tentative ne correspond à ces filtres."
      filtres={
        <>
          <label className="flex flex-col gap-1 text-xs text-ardoise-700">
            Résultat
            <select
              value={succes}
              onChange={(e) => surFiltre(() => setSucces(e.target.value as "" | "true" | "false"))}
              className="rounded-controle border border-filet px-2 py-1.5 text-sm"
            >
              <option value="">Toutes</option>
              <option value="true">Réussies</option>
              <option value="false">Échouées</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-xs text-ardoise-700">
            Compte
            <select
              value={utilisateurId}
              onChange={(e) => surFiltre(() => setUtilisateurId(e.target.value))}
              className="rounded-controle border border-filet px-2 py-1.5 text-sm"
            >
              <option value="">Tous</option>
              {utilisateurs.map((u) => (
                <option key={u.id} value={String(u.id)}>
                  {u.nom} — {u.email}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-xs text-ardoise-700">
            Du
            <input
              type="date"
              value={du}
              onChange={(e) => surFiltre(() => setDu(e.target.value))}
              className="chiffres rounded-controle border border-filet px-2 py-1.5 text-sm"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-ardoise-700">
            Au
            <input
              type="date"
              value={au}
              onChange={(e) => surFiltre(() => setAu(e.target.value))}
              className="chiffres rounded-controle border border-filet px-2 py-1.5 text-sm"
            />
          </label>
        </>
      }
    />
  );
}
