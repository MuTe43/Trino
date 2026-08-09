"use client";

import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { PointPonctualiteDTO } from "@/lib/types";

/**
 * Punctuality over the selected range.
 *
 * This is the figure that gets presented, and it is deliberately a range rather
 * than a day: two runs of the same simulated day gave 28.8 % and 23.3 % of
 * courses 5+ minutes late in phase 2, so a single-day headline moves every time
 * the demo is reset.
 *
 * Colours come through `var(--...)` in SVG attributes, never through a Tailwind
 * class built at runtime — see the note in `HeatmapRetards`.
 */

/**
 * `2026-08-02` -> `02/08`, by slicing rather than by parsing.
 *
 * `new Date("2026-08-02")` is midnight UTC, so rendering it through
 * `toLocaleDateString` in a negative-offset zone shows the previous day. The
 * value is a calendar date, not an instant; there is nothing here to convert.
 */
function etiquetteJour(periode: string): string {
  return `${periode.slice(8, 10)}/${periode.slice(5, 7)}`;
}

interface Props {
  points: PointPonctualiteDTO[];
}

export default function CourbePonctualite({ points }: Props) {
  if (points.length === 0) {
    return <p className="p-4 text-sm text-ardoise-400">Aucune donnée sur la période.</p>;
  }

  const donnees = points.map((point) => ({
    jour: etiquetteJour(point.periode),
    ponctualite: Number((point.tauxPonctualite * 100).toFixed(1)),
    passages: point.passages,
    retardMoyen: point.retardMoyenMin,
  }));

  return (
    <ResponsiveContainer width="100%" height={260}>
      <LineChart data={donnees} margin={{ top: 8, right: 16, bottom: 4, left: -8 }}>
        <CartesianGrid stroke="var(--filet)" vertical={false} />
        <XAxis
          dataKey="jour"
          tick={{ fill: "var(--ardoise-400)", fontSize: 12 }}
          tickLine={false}
          axisLine={{ stroke: "var(--filet)" }}
        />
        <YAxis
          // Not a fixed 0-100: the interesting band is the top third, and a
          // full axis flattens every real movement into a straight line. Not a
          // fixed floor either — a hard 50 would push a genuinely bad day
          // outside the plot area, where it reads as missing data rather than
          // as the number this dashboard exists to show. The floor follows the
          // data down instead, in steps of ten.
          domain={[
            (dataMin: number) => Math.max(0, Math.min(50, Math.floor((dataMin - 5) / 10) * 10)),
            100,
          ]}
          unit="%"
          tick={{ fill: "var(--ardoise-400)", fontSize: 12 }}
          tickLine={false}
          axisLine={false}
          width={56}
        />
        <Tooltip
          contentStyle={{
            border: "1px solid var(--filet)",
            borderRadius: "var(--rayon-carte)",
            fontSize: 12,
          }}
          labelFormatter={(jour) => `Le ${jour}`}
          formatter={(valeur, nom) => {
            if (nom === "ponctualite") return [`${valeur} %`, "Ponctualité"];
            return [valeur, nom];
          }}
        />
        <Line
          type="monotone"
          dataKey="ponctualite"
          stroke="var(--sncft-bleu)"
          strokeWidth={2}
          dot={{ r: 3, fill: "var(--sncft-bleu)" }}
          activeDot={{ r: 5 }}
          isAnimationActive={false}
        />
      </LineChart>
    </ResponsiveContainer>
  );
}
