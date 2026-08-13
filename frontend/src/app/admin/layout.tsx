"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { chargerUtilisateurCourant } from "@/lib/auth";
import type { Utilisateur } from "@/lib/types";

/**
 * Role-gated shell for the administration console.
 *
 * `middleware.ts` already matches `/admin/:path*` and keeps a fully anonymous
 * visitor out at the edge, but it gates on the presence of the `refreshToken`
 * cookie and cannot read a role: the access token lives in memory only, never in
 * a cookie. So the same split as `/exploitation` applies — the edge answers "is
 * anyone signed in", this layout asks `/auth/me` "is it an administrator", and
 * the server answers the only question that matters on every write.
 */

const ONGLETS = [
  { href: "/admin", libelle: "Vue d'ensemble", exact: true },
  { href: "/admin/gares", libelle: "Gares", exact: false },
  { href: "/admin/lignes", libelle: "Lignes", exact: false },
  { href: "/admin/trains", libelle: "Trains", exact: false },
  { href: "/admin/utilisateurs", libelle: "Utilisateurs", exact: false },
  { href: "/admin/alertes", libelle: "Alertes", exact: false },
  { href: "/admin/journal", libelle: "Journal", exact: false },
] as const;

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [utilisateur, setUtilisateur] = useState<Utilisateur | null>(null);
  const [verification, setVerification] = useState(true);

  useEffect(() => {
    let annule = false;
    chargerUtilisateurCourant().then((u) => {
      if (annule) return;
      if (!u || u.role !== "ADMINISTRATEUR") {
        router.replace("/");
        return;
      }
      setUtilisateur(u);
      setVerification(false);
    });
    return () => {
      annule = true;
    };
  }, [router]);

  if (verification || !utilisateur) {
    // Both conditions render the same thing on purpose: someone about to be
    // redirected must never glimpse the console chrome first.
    return <p className="p-6 text-sm text-ardoise-400">Vérification des droits d&apos;accès…</p>;
  }

  return (
    <div className="flex h-screen flex-col overflow-hidden">
      <header className="flex h-12 shrink-0 items-center justify-between gap-2 border-b border-filet bg-sncft-bleu px-3 sm:px-4">
        <div className="flex min-w-0 items-center gap-3 sm:gap-6">
          <Link href="/" className="shrink-0 font-condensee text-base font-medium text-papier">
            Trino
          </Link>
          <nav className="flex items-center gap-3 overflow-x-auto sm:gap-4">
            {ONGLETS.map((onglet) => {
              const actif = onglet.exact
                ? pathname === onglet.href
                : pathname?.startsWith(onglet.href);
              return (
                <Link
                  key={onglet.href}
                  href={onglet.href}
                  className={[
                    "shrink-0 text-sm",
                    actif ? "font-medium text-papier" : "text-ardoise-200 hover:text-papier",
                  ].join(" ")}
                >
                  {onglet.libelle}
                </Link>
              );
            })}
          </nav>
        </div>
        <p className="hidden shrink-0 text-xs text-ardoise-200 sm:block">{utilisateur.nom}</p>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
    </div>
  );
}
