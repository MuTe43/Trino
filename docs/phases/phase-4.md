# Phase 4 — Portail voyageur + carte (3 days)

Also read: `docs/architecture/api-contract.md`, and
`/mnt/skills/public/frontend-design/SKILL.md` before writing any component.

Your #1 priority. If everything after this is cut, the project still defends.

## Goal

The public-facing app: a live map of trains, search, a train detail page with
its stop list and delays, and a station departure board in kiosk mode.

## Before any frontend work — two small backend jobs

Phase 4 is otherwise frontend-only; these two are the exception.

1. **`DepartGareDTO`** for `/gares/{id}/departs` — see `api-contract.md`.
   The endpoint currently returns `PassageDTO`, which carries no train number
   and no destination, so the station board cannot be built from it.

2. **Populate `quai`.** It is null throughout the seed, so the board's voie
   column renders `—` on every row. A departure board with an empty platform
   column looks broken, and this is your showcase screen. Assign it
   deterministically at course generation from the gare's `nb_quais` — this
   touches phase-2 code, which is fine.

3. **CORS origin from configuration.** `ConfigurationSecurite` hardcodes
   `http://localhost:3000`; serving the frontend anywhere else fails with a
   generic error that reads like a frontend bug. `trino.cors.origines`,
   defaulting to `http://localhost:3000`, overridable by env. Phase 7's compose
   needs this too.

4. **`GET /courses?statut=` accepts a CSV.** The snapshot URL below passes
   `EN_CIRCULATION,RETARDE`. Bind `List<StatutCourse>` so one request serves
   the map, rather than the client issuing two.

5. **Stop SSE disconnects logging as ERROR.** A client disconnect currently
   routes through `ApiExceptionHandler`, logs a stack trace, then fails again
   trying to write `ErreurDTO` as `text/event-stream`. A browser `EventSource`
   disconnects on every navigation, so without this the log fills with false
   alarms during exactly the phase where you need to read it. Handle
   `AsyncRequestNotUsableException` / `ClientAbortException` in the emitter
   path and drop them at DEBUG.

   Those two are not sufficient on Windows, where a disconnect surfaces as a
   bare `java.io.IOException` from the scheduled heartbeat and from the
   dispatcher. Any `IOException` raised while writing to an emitter is a client
   that went away — catch it in the send path and drop at DEBUG. The scheduler
   also needs its own `ErrorHandler`, or the heartbeat task's failures bypass
   the emitter path entirely.

## Design direction — decided, not open

Defaults are the failure mode here. A grey card, a blue accent, `shadow-md`,
`rounded-lg` and Inter is what every generated UI looks like. The constraints
below exist to make that impossible.

### Palette

SNCFT's identity is a deep navy. Use it as ink and chrome, never as a large
fill — saturated at scale it fights the map and drowns the status colours.

```
--sncft-bleu    #1D2B7D   chrome, headers, links, the board ground's parent
--encre         #0B1020   body text, and the station board background
--ardoise-700   #2A3566   dividers on dark
--ardoise-400   #6B7CB8   secondary text on dark
--ardoise-200   #8FA0D8   tertiary on dark
--papier        #F7F8FB   page ground (cool-tinted, never pure grey)
```

Status ramp — the only saturated colours on screen, so they carry meaning:

```
A_L_HEURE   #1E9E5A on light / #4ADE80 on dark
R5, R10     #C77E0A on light / #F5B942 on dark
R15, R30    #D2691E on light / #F2833C on dark
R60_PLUS    #C43F36 on light / #E8564C on dark
ANNULE      #6B7280 on light / #5A6699 on dark
```

Two values per status because the board is dark and everything else is light.
Never reuse a light-mode status colour on the board — it fails contrast.

### Type

IBM Plex Sans for UI, IBM Plex Sans Condensed for the board and for any
destination name. Weights 400 and 500 only. Not Inter, not system-ui.

**`font-variant-numeric: tabular-nums` on every element that renders a time,
a delay, a platform, or a train number.** Non-negotiable, and it is most of
what makes a timetable look designed rather than assembled.

### Density and shape

Transport UI is dense. 4px base grid. Radius 2px on dense controls, 8px on
cards, 0 on the station board. Separate surfaces with hairline borders and
tone, never with drop shadows.

### Signature components

Two components should look deliberately made, not generic:

**`ListeArrets`** renders as a vertical timetable strip — a rule down the left
with a node per gare, filled for franchi stops and hollow for those ahead. Each
row shows the scheduled time struck through with the revised time beside it,
in the status colour, whenever they differ. This is the *prévue / estimée /
réelle* distinction from `domain-model.md` made visible; it is the clearest
place in the whole product where the architecture shows.

**The station board** — see below.

### Forbidden

- Inter, system-ui, or any default font stack
- `shadow-sm` / `shadow-md` / any drop shadow
- Uniform `rounded-lg` on everything
- Indigo, violet, or teal accents — the palette above is the whole palette
- Emoji as icons
- A centred card floating on a grey page
- Proportional figures anywhere a number is compared against another number

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

This is the "écrans d'affichage en gare" requirement from the spec, and it is
the best-looking screen in the project. It is not a web page: 1920x1080, read
from across a hall, no pointer, no hover, no navigation.

- Ground `--encre`, rows separated by 1px `#1B2447` hairlines, no card, no
  radius, no shadow.
- Grid: départ, destination, train, voie, statut. Destination is the widest
  column and the largest type — it is what a passenger scans for.
- Departure time in Condensed at the largest size on screen. When delayed,
  the scheduled time sits above it, smaller, struck through, in
  `--ardoise-400`, and the revised time takes the status colour.
- Cancelled rows go entirely `--ardoise` with the destination struck through
  and `supprimé` in red — present but visibly dead, never removed.
- Rows sized so 8 to 10 fit at 1080p without scrolling.
- Header carries the gare name and a live clock; both in Condensed.
- Sorted by `departEstime`, so a delayed train falls down the board.
- Reconnects silently and runs untouched for hours.

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
- Search a real train number and a station name; both return results. Take the
  number from the seed, do not hardcode one:
  `psql -h localhost -U trino -d trino -tAc "select numero from train limit 1"`
  (`DR201` appears in `api-contract.md` as an illustrative example only and is
  not seeded.)
- `/affichage/1` renders legibly at 1920x1080 and updates on its own
- Chrome devtools at 375px: map and board both usable
- Devtools Network: exactly one EventSource per open ligne channel, and it
  closes on navigation. **Measure this in a real Chrome window against
  `npm run start`, not in dev mode and not in an embedded browser.** React
  StrictMode double-invokes effects in dev, and an embedded browser may report
  `document.hidden` as permanently true, which makes the hook close streams and
  the count meaningless.
- `ANNULE` styling cannot be exercised yet: nothing creates a cancelled course
  until phase 6. Either check it by setting `statut` directly in the database
  for one course as a one-off visual test, or record it as unverified and carry
  it to phase 6. Do not add code to produce one.

```bash
# clients must not recompute expected times themselves
grep -rn "retardMin +\|arriveeTheorique +\|addMinutes" frontend/src --include=*.tsx
# expect no output — they read arriveeEstimee
```
