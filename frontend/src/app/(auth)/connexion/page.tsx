"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { login } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import type { Role } from "@/lib/types";

/**
 * Restyled in phase 5 against the tokens in `globals.css`.
 *
 * What was here before was a centred card on `bg-blue-600` in `font-semibold` —
 * three things the rest of the app does not do. `layout.tsx` loads IBM Plex at
 * weights 400 and 500 only, so `font-semibold` (600) had no face behind it and
 * the browser was synthesising it. Everything below stays within the loaded
 * weights and the declared palette.
 *
 * Surfaces separate by border and tone, never by shadow — the same rule the
 * board and the map panels follow.
 */

/**
 * Where each role lands after signing in.
 *
 * `/exploitation` has no page — only `/exploitation/tableau-bord` exists — so
 * sending a responsable there produced a 404 on the exact path the phase-5 jury
 * demo takes: sign in, look at the dashboard. An agent has no screen of their
 * own until phase 6 and would be refused the dashboard (403,
 * RESPONSABLE_EXPLOITATION only), so they go to the public map, which is at
 * least a working page showing live traffic.
 */
function pageApresConnexion(role: Role): string {
  if (role === "ADMINISTRATEUR") return "/admin";
  if (role === "RESPONSABLE_EXPLOITATION") return "/exploitation/tableau-bord";
  // phase 6: give AGENT_CIRCULATION its own console and route it there.
  return "/";
}

const CHAMP =
  "w-full rounded-controle border border-filet bg-white p-2.5 text-encre " +
  "placeholder:text-ardoise-200 focus:border-sncft-bleu";

export default function ConnexionPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [motDePasse, setMotDePasse] = useState("");
  const [erreur, setErreur] = useState<string | null>(null);
  const [enCours, setEnCours] = useState(false);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setErreur(null);
    setEnCours(true);
    try {
      const utilisateur = await login(email, motDePasse);
      router.push(pageApresConnexion(utilisateur.role));
    } catch (err) {
      if (err instanceof ApiError && err.statut === 401) {
        setErreur("Email ou mot de passe incorrect.");
      } else {
        setErreur("Une erreur est survenue. Veuillez réessayer.");
      }
    } finally {
      setEnCours(false);
    }
  }

  return (
    <main className="grid min-h-screen lg:grid-cols-[1fr_1.1fr]">
      {/* Brand panel. Dropped below lg rather than stacked: on a phone it would
          be a screenful of blue between the user and the form. */}
      <aside className="hidden flex-col justify-between bg-sncft-bleu p-10 text-white lg:flex">
        <p className="font-condensee text-2xl tracking-tight">TRINO</p>
        <div>
          <p className="font-condensee text-4xl leading-tight">
            Suivi en temps réel
            <br />
            du réseau SNCFT
          </p>
          <p className="mt-4 max-w-sm text-sm text-ardoise-200">
            Position des trains, retards et ponctualité, à la minute.
          </p>
        </div>
        <p className="text-xs text-ardoise-200">Société Nationale des Chemins de Fer Tunisiens</p>
      </aside>

      <div className="flex items-center justify-center p-6">
        <form onSubmit={onSubmit} className="w-full max-w-sm" noValidate={false}>
          <p className="font-condensee text-xl text-sncft-bleu lg:hidden">TRINO</p>
          <h1 className="mt-2 font-condensee text-3xl text-encre lg:mt-0">Connexion</h1>
          <p className="mt-2 mb-8 text-sm text-ardoise-400">
            Accès réservé au personnel d&apos;exploitation.
          </p>

          <label htmlFor="email" className="mb-1.5 block text-sm font-medium text-encre">
            Adresse e-mail
          </label>
          <input
            id="email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className={`${CHAMP} mb-5`}
            placeholder="prenom.nom@sncft.tn"
          />

          <label htmlFor="motDePasse" className="mb-1.5 block text-sm font-medium text-encre">
            Mot de passe
          </label>
          <input
            id="motDePasse"
            type="password"
            autoComplete="current-password"
            required
            value={motDePasse}
            onChange={(e) => setMotDePasse(e.target.value)}
            className={`${CHAMP} mb-5`}
          />

          {erreur && (
            // role="alert" so the message is announced, not just shown: a
            // failed login otherwise passes silently for a screen reader.
            <p
              role="alert"
              className="mb-5 rounded-controle border-l-2 border-statut-r60 bg-white p-3 text-sm text-statut-r60"
            >
              {erreur}
            </p>
          )}

          <button
            type="submit"
            disabled={enCours}
            className="w-full rounded-controle bg-sncft-bleu p-2.5 font-medium text-white disabled:opacity-60"
          >
            {enCours ? "Connexion…" : "Se connecter"}
          </button>
        </form>
      </div>
    </main>
  );
}
