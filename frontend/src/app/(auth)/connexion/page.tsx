"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { login } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import type { Role } from "@/lib/types";

function pageApresConnexion(role: Role): string {
  if (role === "ADMINISTRATEUR") return "/admin";
  if (role === "AGENT_CIRCULATION" || role === "RESPONSABLE_EXPLOITATION")
    return "/exploitation";
  return "/";
}

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
    <main className="flex min-h-screen items-center justify-center p-6">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded border border-gray-300 p-6"
      >
        <h1 className="mb-6 text-xl font-semibold">Connexion</h1>

        <label htmlFor="email" className="mb-1 block text-sm font-medium">
          Email
        </label>
        <input
          id="email"
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="mb-4 w-full rounded border border-gray-300 p-2"
        />

        <label
          htmlFor="motDePasse"
          className="mb-1 block text-sm font-medium"
        >
          Mot de passe
        </label>
        <input
          id="motDePasse"
          type="password"
          required
          value={motDePasse}
          onChange={(e) => setMotDePasse(e.target.value)}
          className="mb-4 w-full rounded border border-gray-300 p-2"
        />

        {erreur && (
          <p className="mb-4 rounded border border-red-300 bg-red-50 p-2 text-sm text-red-800">
            {erreur}
          </p>
        )}

        <button
          type="submit"
          disabled={enCours}
          className="w-full rounded bg-blue-600 p-2 text-white disabled:opacity-50"
        >
          {enCours ? "Connexion..." : "Se connecter"}
        </button>
      </form>
    </main>
  );
}
