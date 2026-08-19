# Addendum phase 9 — Accueil voyageur (0.5–1 day)

Also read: `docs/phases/phase-4.md`, **for its "Design direction" section only** —
palette, type, density, signature components, and the Forbidden list. That section
governs this page unchanged.

## What in `phase-4.md` no longer holds

That file was written before phases 5 and 8 and three of its statements are now
superseded. Do not follow them:

| `phase-4.md` says | Current state |
|---|---|
| `app/page.tsx` is the map + search | `/` becomes the accueil; the full-screen map moves to `/carte` (this addendum) |
| "open SSE on the visible lignes", one connection per ligne | One multiplexed connection, `GET /stream?lignes=…&gares=…`, since phase 5. See `api-contract.md`. |
| Acceptance: one EventSource **per open ligne channel** | One EventSource **per client**, carrying every subscribed channel |

Everything else in that file — the design direction, the station board rules, the
marker interpolation, the client-side time-arithmetic ban — still stands.

## Why

A first-time visitor currently lands on a map of coloured markers with nothing
explaining what the colours mean, that a train can be followed, or that station
boards exist. Search is in the header but nothing invites its use. The map is a
browsing tool; the passenger's actual question is usually about one train.

This is an onboarding gap, not a marketing one. Nothing on this page sells
anything — no hero image, no feature cards, no call to action. It is a public
service explaining itself.

## Route change

- `/` becomes the accueil, and **embeds the real live map**, not a screenshot.
- `/carte` takes the current full-screen map, unchanged.
- The header's logo links to `/`; a "Carte plein écran" link goes to `/carte`.

The map on `/` is the same `CarteReseau` component at a reduced height (around
420 px), same SSE subscription behaviour. Do not build a second map, and do not
substitute a static image — a front page showing trains that actually move is
the single most convincing thing this project has.

## Sections, in order

**1. Bandeau.** The name, one sentence saying what the service does
("Suivez en direct la circulation des trains sur le réseau SNCFT"), and the
search field at a size that invites use — larger than the header's copy. It
reuses `BarreRecherche`; do not fork it.

**2. La carte, en direct.** The embedded map, with a "Carte plein écran" link.

**3. Légende des retards.** This is the highest-value part of the whole page and
it is missing from the product today. Six swatches with their meaning, using the
same `ClasseRetard` lookup the markers use — never a second colour table, or the
two will drift (invariant 8).

```
à l'heure   moins de 5 min      R15   15 à 29 min
R5          5 à 9 min           R30   30 à 59 min
R10         10 à 14 min         R60+  1 heure ou plus
                                annulé
```

Put the same legend on `/carte` as a collapsible panel. A map whose colours are
unexplained is unreadable regardless of which page it sits on.

**4. Trois usages.** Three short blocks, each a real link, each one sentence —
not feature marketing:
- *Suivre un train* — recevez une notification si votre train prend du retard,
  sans créer de compte. Links to a currently-circulating course, id derived at
  run time, never hardcoded.
- *Écrans de gare* — l'affichage des prochains départs, conçu pour les écrans en
  gare. Links to `/affichage/{gareId}` for a major station.
- *Départs d'une gare* — links to `/gares/{id}`.

**5. Pied de page.** Where the data comes from and how often it refreshes
("positions transmises toutes les 5 secondes"), and a discreet "Espace
exploitation" link to `/connexion`. Being explicit about the update frequency is
what makes a live service trustworthy rather than mysterious.

## Optional — état du réseau

Four live counters above the map: trains en circulation, retard moyen, taux de
ponctualité du jour, incidents en cours.

This needs a **new public endpoint**, `GET /api/v1/etat-reseau`, because
`/tableau-bord/kpi` is `RESPONSABLE_EXPLOITATION` only and must stay that way. It
returns a strict subset — no per-train detail, no passenger estimates, no
incident descriptions — and reuses the existing repository queries.

Do this only if the rest of phase 9 is done. It is genuinely good and it is not
worth trading the load test for.

## Rules

- All strings French. Design direction from `phase-4.md` applies without
  exception: only weights 400 and 500, tabular numerals on every number, hairline
  borders, no drop shadows, palette limited to the tokens in `globals.css`.
- No hero image, no gradient, no illustration, no icon set. Type and space only.
- Server Component for the static content; the map and counters stay Client
  Components.
- Verify at 375 px before declaring it done — this is the first page a phone user
  will ever see.
- Every id in a link is derived at run time from the database. A front page whose
  "suivre ce train" link points at a course that finished last week is worse than
  no link.

## Acceptance

```bash
cd frontend && npm run build && npm run start
```

- `/` renders the accueil with trains visibly moving inside the embedded map
- `/carte` is the full-screen map, unchanged from phase 4
- The legend shows six distinct background colours plus the cancelled state
  (invariant 8 — a runtime-assembled Tailwind class produces no colour at all)
- Every link on `/` resolves to a page that exists and to a live subject
- At 375 px: no horizontal overflow, the map is usable, the legend wraps
- Devtools Network: one EventSource, closed on navigation to `/carte`
