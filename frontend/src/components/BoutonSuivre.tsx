"use client";

import { useCallback, useEffect, useId, useState } from "react";
import { ApiError } from "@/lib/api";
// `suivre` and `nePlusSuivre` fire the window event the header's bell listens
// for, so this component does not have to know the bell exists.
import { listerMesAbonnements, nePlusSuivre, suivre } from "@/lib/abonnement";
import type { AbonnementDTO, CanalType, CibleType } from "@/lib/types";

/**
 * "Suivre ce train" — and unfollow, and pick the channels.
 *
 * No login anywhere in this flow, which is the point of the phase: a passenger
 * following their train has no account, and putting a sign-in in front of this
 * button would deliver the notification use case to nobody who wants it. The
 * identity is a cookie the server mints on the first subscribe.
 *
 * Only the two channels that genuinely work are offered. `SMS` is a logging stub
 * with no account behind it and no phone number anywhere in the model, and
 * `AFFICHAGE` is the station board, which already shows this without anybody
 * subscribing. Offering either would be a control that promises a delivery the
 * system does not make.
 */

/** Every class spelled out — invariant 8. Tailwind only emits what it can see. */
const CLASSES_CANAL: Record<"actif" | "inactif", string> = {
  actif: "border-sncft-bleu bg-sncft-bleu text-papier",
  inactif: "border-filet bg-white text-ardoise-700",
};

interface ProprietesBoutonSuivre {
  cibleType: CibleType;
  cibleId: number;
  /** What is being followed, for the button's own label: "ce train", "cette ligne". */
  libelleCible: string;
}

export function BoutonSuivre({ cibleType, cibleId, libelleCible }: ProprietesBoutonSuivre) {
  const [abonnement, setAbonnement] = useState<AbonnementDTO | null>(null);
  const [ouvert, setOuvert] = useState(false);
  const [canaux, setCanaux] = useState<CanalType[]>(["IN_APP"]);
  const [email, setEmail] = useState("");
  const [erreur, setErreur] = useState<string | null>(null);
  const [envoi, setEnvoi] = useState(false);
  const idEmail = useId();

  const recharger = useCallback(async () => {
    try {
      const abonnements = await listerMesAbonnements();
      const existant = abonnements.find(
        (a) => a.cibleType === cibleType && a.cibleId === cibleId,
      );
      setAbonnement(existant ?? null);
      if (existant) {
        setCanaux(existant.canaux);
        setEmail(existant.email ?? "");
      }
    } catch {
      // Never followed anything, or the API is down. Either way the button
      // offers to subscribe, and the attempt will report its own failure.
      setAbonnement(null);
    }
  }, [cibleType, cibleId]);

  useEffect(() => {
    void recharger();
  }, [recharger]);

  function basculerCanal(canal: CanalType) {
    setCanaux((precedents) =>
      precedents.includes(canal)
        ? precedents.filter((c) => c !== canal)
        : [...precedents, canal],
    );
  }

  async function enregistrer(evenement: React.FormEvent) {
    evenement.preventDefault();
    setErreur(null);
    setEnvoi(true);
    try {
      const cree = await suivre({
        cibleType,
        cibleId,
        canaux,
        email: canaux.includes("EMAIL") ? email.trim() : undefined,
      });
      setAbonnement(cree);
      setOuvert(false);
    } catch (e) {
      // 429 carries the only message a caller can act on ("réessayez dans une
      // minute"), so it is shown as sent rather than replaced with a generic one.
      setErreur(e instanceof ApiError ? e.message : "Impossible d'enregistrer l'abonnement.");
    } finally {
      setEnvoi(false);
    }
  }

  async function arreter() {
    if (!abonnement) return;
    setErreur(null);
    setEnvoi(true);
    try {
      await nePlusSuivre(abonnement.id);
      setAbonnement(null);
      setOuvert(false);
      setCanaux(["IN_APP"]);
    } catch (e) {
      setErreur(e instanceof ApiError ? e.message : "Impossible de supprimer l'abonnement.");
    } finally {
      setEnvoi(false);
    }
  }

  if (abonnement && !ouvert) {
    return (
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-sm text-ardoise-700">
          Vous suivez {libelleCible} ({abonnement.canaux.join(", ").toLowerCase()}).
        </p>
        <button
          type="button"
          onClick={() => setOuvert(true)}
          className="rounded-controle border border-filet px-3 py-1.5 text-sm text-ardoise-700"
        >
          Modifier
        </button>
        <button
          type="button"
          onClick={arreter}
          disabled={envoi}
          className="rounded-controle border border-filet px-3 py-1.5 text-sm text-ardoise-700 disabled:opacity-50"
        >
          Ne plus suivre
        </button>
        {erreur ? (
          <p role="alert" className="text-sm text-statut-r60">
            {erreur}
          </p>
        ) : null}
      </div>
    );
  }

  if (!ouvert) {
    return (
      <button
        type="button"
        onClick={() => setOuvert(true)}
        className="rounded-controle bg-sncft-bleu px-3 py-1.5 text-sm font-medium text-papier hover:opacity-90"
      >
        Suivre {libelleCible}
      </button>
    );
  }

  return (
    <form
      onSubmit={enregistrer}
      className="flex flex-col gap-3 rounded-carte border border-filet bg-white p-3"
    >
      <p className="text-xs font-medium text-ardoise-700">Me prévenir par</p>

      <div className="flex flex-wrap gap-2">
        {(["IN_APP", "EMAIL"] as const).map((canal) => {
          const actif = canaux.includes(canal);
          return (
            <button
              key={canal}
              type="button"
              onClick={() => basculerCanal(canal)}
              aria-pressed={actif}
              className={[
                "rounded-controle border px-3 py-1.5 text-sm",
                actif ? CLASSES_CANAL.actif : CLASSES_CANAL.inactif,
              ].join(" ")}
            >
              {canal === "IN_APP" ? "Sur le site" : "Par email"}
            </button>
          );
        })}
      </div>

      {canaux.includes("EMAIL") ? (
        <div className="flex flex-col gap-1">
          <label htmlFor={idEmail} className="text-xs font-medium text-ardoise-700">
            Adresse email <span aria-hidden="true">*</span>
          </label>
          <input
            id={idEmail}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            // `required`, not just the asterisk: the server refuses EMAIL
            // without an address, and it reports that on a synthetic field name
            // this form has no input for — so the check belongs here first.
            required
            className="rounded-controle border border-filet px-2 py-1.5 text-sm"
          />
        </div>
      ) : null}

      {erreur ? (
        <p role="alert" className="text-sm text-statut-r60">
          {erreur}
        </p>
      ) : null}

      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={() => setOuvert(false)}
          className="rounded-controle border border-filet px-3 py-1.5 text-sm"
        >
          Annuler
        </button>
        <button
          type="submit"
          disabled={envoi || canaux.length === 0}
          className="rounded-controle bg-sncft-bleu px-3 py-1.5 text-sm font-medium text-papier disabled:opacity-50"
        >
          {envoi ? "Enregistrement…" : "Valider"}
        </button>
      </div>
    </form>
  );
}
