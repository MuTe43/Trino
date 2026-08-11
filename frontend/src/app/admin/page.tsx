"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import IndicateurSucces from "@/components/admin/IndicateurSucces";
import {
  listerGaresAdmin,
  listerJournal,
  listerLignesAdmin,
  listerTrainsAdmin,
  listerUtilisateurs,
} from "@/lib/admin";
import { formaterDateHeure } from "@/lib/temps";
import type { JournalConnexion } from "@/lib/types";

/**
 * Landing page of the console: how big each table is, and who has been trying
 * to log in.
 *
 * The counts are read from each list endpoint's `total` with `taille=1` — the
 * envelope carries the count, so downloading forty gares to call `.length` on
 * them would be four wasted round trips of payload for four numbers.
 */

interface Compteurs {
  gares: number;
  lignes: number;
  trains: number;
  utilisateurs: number;
}

export default function AdminAccueilPage() {
  const [compteurs, setCompteurs] = useState<Compteurs | null>(null);
  const [connexions, setConnexions] = useState<JournalConnexion[]>([]);
  const [erreur, setErreur] = useState<string | null>(null);

  useEffect(() => {
    let annule = false;
    Promise.all([
      listerGaresAdmin({ page: 0, taille: 1 }),
      listerLignesAdmin(0, 1),
      listerTrainsAdmin({ page: 0, taille: 1 }),
      listerUtilisateurs(0, 1),
      listerJournal({ page: 0, taille: 10 }),
    ])
      .then(([gares, lignes, trains, utilisateurs, journal]) => {
        if (annule) return;
        setCompteurs({
          gares: gares.total,
          lignes: lignes.total,
          trains: trains.total,
          utilisateurs: utilisateurs.total,
        });
        setConnexions(journal.contenu);
      })
      .catch((e: unknown) => {
        if (annule) return;
        setErreur(e instanceof Error ? e.message : "Chargement impossible.");
      });
    return () => {
      annule = true;
    };
  }, []);

  const tuiles = [
    { href: "/admin/gares", libelle: "Gares", valeur: compteurs?.gares },
    { href: "/admin/lignes", libelle: "Lignes", valeur: compteurs?.lignes },
    { href: "/admin/trains", libelle: "Trains", valeur: compteurs?.trains },
    { href: "/admin/utilisateurs", libelle: "Utilisateurs", valeur: compteurs?.utilisateurs },
  ];

  return (
    <div className="flex flex-col gap-6 p-4 sm:p-6">
      <h1 className="font-condensee text-lg font-medium text-encre">Vue d&apos;ensemble</h1>

      {erreur ? (
        <p
          role="alert"
          className="rounded-controle border border-statut-r60 px-3 py-2 text-sm text-statut-r60"
        >
          {erreur}
        </p>
      ) : null}

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {tuiles.map((tuile) => (
          <Link
            key={tuile.href}
            href={tuile.href}
            className="rounded-carte border border-filet bg-white px-4 py-3 hover:border-ardoise-400"
          >
            <p className="text-xs text-ardoise-400">{tuile.libelle}</p>
            <p className="chiffres mt-1 text-2xl text-encre">
              {tuile.valeur === undefined ? "—" : tuile.valeur}
            </p>
          </Link>
        ))}
      </div>

      <section className="flex flex-col gap-2">
        <div className="flex items-baseline justify-between">
          <h2 className="font-condensee text-base font-medium text-encre">
            Dernières tentatives de connexion
          </h2>
          <Link href="/admin/journal" className="text-xs text-sncft-bleu hover:underline">
            Tout le journal
          </Link>
        </div>

        <div className="overflow-x-auto rounded-carte border border-filet bg-white">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="border-b border-filet bg-papier text-left">
                <th scope="col" className="px-3 py-2 font-medium text-ardoise-700">
                  Horodatage
                </th>
                <th scope="col" className="px-3 py-2 font-medium text-ardoise-700">
                  Email tenté
                </th>
                <th scope="col" className="px-3 py-2 font-medium text-ardoise-700">
                  Adresse IP
                </th>
                <th scope="col" className="px-3 py-2 font-medium text-ardoise-700">
                  Résultat
                </th>
              </tr>
            </thead>
            <tbody>
              {connexions.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-3 py-6 text-center text-ardoise-400">
                    Aucune tentative enregistrée.
                  </td>
                </tr>
              ) : null}
              {connexions.map((connexion) => (
                <tr key={connexion.id} className="border-b border-filet last:border-b-0">
                  <td className="chiffres whitespace-nowrap px-3 py-2 text-encre">
                    {formaterDateHeure(connexion.horodatage)}
                  </td>
                  <td className="px-3 py-2 text-encre">{connexion.emailTente}</td>
                  <td className="chiffres px-3 py-2 text-ardoise-400">
                    {connexion.adresseIp ?? "—"}
                  </td>
                  <td className="px-3 py-2">
                    <IndicateurSucces succes={connexion.succes} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
