import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError, listerPassages, trouverCourse } from "@/lib/api";
import { DetailCourseTempsReel } from "@/components/DetailCourseTempsReel";

// Live data (SSE) means this page must never be statically cached.
export const dynamic = "force-dynamic";

interface PageProps {
  params: Promise<{ id: string }>;
}

export default async function TrainPage({ params }: PageProps) {
  const { id } = await params;
  const courseId = Number(id);
  if (!Number.isInteger(courseId)) {
    notFound();
  }

  let course;
  let passages;
  try {
    [course, passages] = await Promise.all([trouverCourse(courseId), listerPassages(courseId)]);
  } catch (erreur) {
    if (erreur instanceof ApiError && erreur.statut === 404) {
      notFound();
    }
    throw erreur;
  }

  return (
    <main className="mx-auto max-w-2xl px-4 py-6 sm:px-6">
      <Link href="/" className="text-sm text-sncft-bleu">
        ← Carte du réseau
      </Link>

      <div className="mt-4">
        <DetailCourseTempsReel courseInitiale={course} passagesInitiaux={passages} />
      </div>
    </main>
  );
}
