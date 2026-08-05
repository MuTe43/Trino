import type { Metadata } from "next";

// The kiosk shell: no nav, no header chrome from the main app, no links out.
// This is a screen bolted to a wall, not a page someone browses -- it never
// scrolls, never shows a cursor and never lets a stray tap select text.
export const metadata: Metadata = {
  title: "Trino — Affichage en gare",
};

export default function AffichageLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="fixed inset-0 h-screen w-screen select-none overflow-hidden bg-encre text-papier cursor-none">
      {children}
    </div>
  );
}
