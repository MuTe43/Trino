import type { Metadata } from "next";
import { IBM_Plex_Sans, IBM_Plex_Sans_Condensed } from "next/font/google";
import "./globals.css";

// UI body copy: tables, labels, running text.
const policeUi = IBM_Plex_Sans({
  variable: "--police-ui",
  subsets: ["latin"],
  weight: ["400", "500"],
});

// Condensed face for the kiosk/station board, where digits and platform
// numbers need to sit tight in a fixed-height row.
const policeCondensee = IBM_Plex_Sans_Condensed({
  variable: "--police-condensee",
  subsets: ["latin"],
  weight: ["400", "500"],
});

export const metadata: Metadata = {
  title: "Trino — Suivi SNCFT",
  description: "Suivi en temps réel des trains SNCFT",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="fr" className={`${policeUi.variable} ${policeCondensee.variable}`}>
      <body>{children}</body>
    </html>
  );
}
