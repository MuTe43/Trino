import { notFound } from "next/navigation";
import { ApiError, listerDeparts, trouverGare } from "@/lib/api";
import { TableauAffichage } from "@/components/TableauAffichage";

// Live data (SSE + a 60s REST resync) means this page must never be
// statically cached.
export const dynamic = "force-dynamic";

interface PageProps {
  params: Promise<{ gareId: string }>;
}

export default async function AffichageGarePage({ params }: PageProps) {
  const { gareId } = await params;
  const id = Number(gareId);
  if (!Number.isInteger(id)) {
    notFound();
  }

  let gare;
  let departs;
  try {
    [gare, departs] = await Promise.all([trouverGare(id), listerDeparts(id, 20)]);
  } catch (erreur) {
    if (erreur instanceof ApiError && erreur.statut === 404) {
      notFound();
    }
    throw erreur;
  }

  return <TableauAffichage gareId={gare.id} gareNom={gare.nom} departsInitiaux={departs} />;
}
