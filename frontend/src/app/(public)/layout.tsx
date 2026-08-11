import { EntetePublique } from "@/components/EntetePublique";

// Shared chrome for every public route (`/`, `/gares/{id}`, `/trains/{id}`).
// A route group -- the parenthesised folder name never appears in the URL --
// so this changes no path. `/affichage/{gareId}` (a kiosk) and
// `/exploitation/*` (role-gated) each own a different shell and stay outside
// this group entirely.
export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-screen flex-col overflow-hidden">
      <EntetePublique />
      <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
    </div>
  );
}
