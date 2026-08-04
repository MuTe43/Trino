# Phase 4 — Portail voyageur + carte (3 days)

Also read: `docs/architecture/api-contract.md`.

Your #1 priority. If everything after this is cut, the project still defends.

## Goal

The public-facing app: a live map of trains, search, a train detail page with
its stop list and delays, and a station departure board in kiosk mode.

## Before any frontend work — two small backend jobs

Phase 4 is otherwise frontend-only; these two are the exception.

1. **`DepartGareDTO`** for `/gares/{id}/departs` — see `api-contract.md`.
   The endpoint currently returns `PassageDTO`, which carries no train number
   and no destination, so the station board cannot be built from it.

2. **Stop SSE disconnects logging as ERROR.** A client disconnect currently
   routes through `ApiExceptionHandler`, logs a stack trace, then fails again
   trying to write `ErreurDTO` as `text/event-stream`. A browser `EventSource`
   disconnects on every navigation, so without this the log fills with false
   alarms during exactly the phase where you need to read it. Handle
   `AsyncRequestNotUsableException` / `ClientAbortException` in the emitter
   path and drop them at DEBUG.

## Build

```
frontend/src/lib/sse.ts                     EventSource hook with reconnect
frontend/src/lib/types.ts                   mirrors the DTOs, hand-written
frontend/src/components/CarteReseau.tsx     MapLibre, lignes + gares + trains
frontend/src/components/MarqueurTrain.tsx   colour by classeRetard
frontend/src/components/BarreRecherche.tsx
frontend/src/components/ListeArrets.tsx     passage list with theoretical vs real
frontend/src/app/page.tsx                   map + search
frontend/src/app/trains/[id]/page.tsx       course detail
frontend/src/app/gares/[id]/page.tsx        station page
frontend/src/app/affichage/[gareId]/page.tsx  kiosk board, no nav, auto-refresh
```

## Map behaviour

- MapLibre with OSM raster tiles, `https://tile.openstreetmap.org/{z}/{x}/{y}.png`.
  Set a proper `attribution`. No Mapbox, no API key.
- Initial load: `GET /courses?date=today&statut=EN_CIRCULATION,RETARDE` for the
  snapshot. Then open SSE on the visible lignes.
- Train markers interpolate between deltas with `requestAnimationFrame` so
  movement looks continuous at a 5s update rate. Without this the demo looks
  broken — trains teleport.
- Marker colour from `classeRetard`: on time green, R5/R10 amber, R15/R30
  orange, R60_PLUS red, ANNULE grey.
- Click a marker -> the course detail panel.

## Station board (`/affichage/[gareId]`)

This is the "écrans d'affichage en gare" requirement from the spec. Design it
for a 1920x1080 screen viewed from a distance: large type, high contrast, no
navigation chrome, no interaction. Next departures with theoretical time, real
time, delay, platform, and status. Reconnects silently. It should be able to run
untouched for hours.

## Rules

- All UI strings French. Times rendered `Africa/Tunis` via `Intl.DateTimeFormat`.
- The SSE hook must handle: connection drop with exponential backoff, tab
  suspension, and unmount cleanup. Write these before styling anything.
- Server Components for the initial fetch, Client Components for anything
  touching `EventSource` or the map.
- No state management library. `useState` plus a reducer for the course map.
- Responsive: the spec asks for PC, smartphone, tablet. Verify at 375px.

## Acceptance

```bash
cd frontend && npm run build && npm run start &
```

Then, manually, with the simulator at acceleration 20:
- Trains visibly move on the map without a page refresh, for 3 minutes straight
- Kill the simulator; markers stop and switch to the `ARRET_EXCEPTIONNEL` style
  within 2 minutes; no console errors
- Restart it; markers resume without a page reload
- Search "DR201" and a station name both return results
- `/affichage/1` renders legibly at 1920x1080 and updates on its own
- Chrome devtools at 375px: map and board both usable
- Devtools Network: exactly one EventSource per open ligne channel, and it
  closes on navigation

```bash
# clients must not recompute expected times themselves
grep -rn "retardMin +\|arriveeTheorique +\|addMinutes" frontend/src --include=*.tsx
# expect no output — they read arriveeEstimee
```
