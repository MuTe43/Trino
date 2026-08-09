"use client";

import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { BucketRetardDTO, ClasseRetard } from "@/lib/types";

/**
 * How many courses fell in each delay bucket over the selected range.
 *
 * Bars are painted with the same ramp the station board and the map use, so a
 * reader who has learned the colours on one screen reads them the same way
 * here. The values are `var(--...)` in an SVG attribute rather than Tailwind
 * classes: nothing is assembled into a class name, so invariant 8 cannot bite.
 */

/** Bucket boundaries mirror ClasseRetard.de on the server. */
const LIBELLES: Record<ClasseRetard, string> = {
  A_L_HEURE: "À l'heure",
  R5: "5 – 9 min",
  R10: "10 – 14 min",
  R15: "15 – 29 min",
  R30: "30 – 59 min",
  R60_PLUS: "60 min +",
  ANNULE: "Supprimé",
};

const COULEURS: Record<ClasseRetard, string> = {
  A_L_HEURE: "var(--statut-a-lheure)",
  R5: "var(--statut-r10)",
  R10: "var(--statut-r10)",
  R15: "var(--statut-r30)",
  R30: "var(--statut-r30)",
  R60_PLUS: "var(--statut-r60)",
  ANNULE: "var(--statut-annule)",
};

interface Props {
  buckets: BucketRetardDTO[];
}

export default function HistogrammeRetards({ buckets }: Props) {
  if (buckets.length === 0) {
    return <p className="p-4 text-sm text-ardoise-400">Aucune donnée sur la période.</p>;
  }

  const donnees = buckets.map((bucket) => ({
    bucket: bucket.classe,
    libelle: LIBELLES[bucket.classe],
    courses: bucket.courses,
  }));

  return (
    <ResponsiveContainer width="100%" height={260}>
      <BarChart data={donnees} margin={{ top: 8, right: 16, bottom: 4, left: -8 }}>
        <CartesianGrid stroke="var(--filet)" vertical={false} />
        <XAxis
          dataKey="libelle"
          tick={{ fill: "var(--ardoise-400)", fontSize: 11 }}
          tickLine={false}
          axisLine={{ stroke: "var(--filet)" }}
          interval={0}
        />
        <YAxis
          allowDecimals={false}
          tick={{ fill: "var(--ardoise-400)", fontSize: 12 }}
          tickLine={false}
          axisLine={false}
          width={48}
        />
        <Tooltip
          cursor={{ fill: "var(--papier)" }}
          contentStyle={{
            border: "1px solid var(--filet)",
            borderRadius: "var(--rayon-carte)",
            fontSize: 12,
          }}
          formatter={(valeur) => [`${valeur} course${Number(valeur) > 1 ? "s" : ""}`, "Courses"]}
        />
        <Bar dataKey="courses" isAnimationActive={false} radius={[2, 2, 0, 0]}>
          {donnees.map((entree) => (
            <Cell key={entree.bucket} fill={COULEURS[entree.bucket]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
