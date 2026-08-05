# Phase 5 — Tableaux de bord + exports (2 days)

Also read: `docs/architecture/api-contract.md` (dashboard section) and
`docs/architecture/domain-model.md` (Role enum — dashboards are gated on
`RESPONSABLE_EXPLOITATION`).

Your #2 priority.

## Before the dashboard — two carried items

Both were filed for later phases and both are wrong there.

### 1. Measure, then multiplex the SSE connections

Measure first, in a real Chrome window against `npm run start`: open the map at
full-network zoom, then use search and open a course detail. Count connections
to the API origin in devtools.

The expectation is saturation. One socket per ligne, five lignes, and a browser
allows about six connections per origin over HTTP/1.1 — shared with every REST
call, since the API is a single origin. At full-network zoom the map holds five,
and the next REST request queues behind them. The app appears to hang, and it
happens at the default view rather than in some corner case.

Filed under phase 7 in `STATE.md`; that is too late. It is a demo-stopper on the
screen you will spend the most time showing.

Fix by multiplexing rather than by reaching for HTTP/2, which needs TLS and buys
a local-only demo nothing:

```
GET /api/v1/stream?lignes=1,2,3&gares=7
```

One emitter per client. Each frame keeps its channel identity in the event
payload, and the hub fans subscribed channels into the single connection.

**This does not violate invariant 5.** That rule exists so a client is never
sent every train on the network. An explicit subscription list satisfies it —
the client still receives only what it asked for. Keep the per-ligne and
per-gare endpoints for the kiosk board, which needs exactly one channel and
should stay simple.

### 2. Restyle `/connexion`

It is the one screen contradicting phase 4's design direction — centred card,
`bg-blue-600`, a font weight the app never loads. Filed under phase 6, but the
dashboard you build in *this* phase sits behind it, so it is the first thing a
jury sees on the way to your best analytical work. Twenty minutes against the
palette and type already in `globals.css`.

## Goal

The KPI dashboard for `RESPONSABLE_EXPLOITATION`, the punctuality charts, and
CSV/XLSX export.

## Build

```
backend/api/.../analytique/service/{ServiceKpi,ServicePonctualite,ServiceExport}.java
backend/api/.../analytique/web/{TableauBordController,RapportController}.java
backend/api/.../analytique/dto/*.java
frontend/src/app/exploitation/tableau-bord/page.tsx
frontend/src/components/graphiques/{CourbePonctualite,HistogrammeRetards,HeatmapRetards}.tsx
```

Add `poi-ooxml` for XLSX. Nothing else.

## Queries

Write these as native SQL in the repository, not JPQL — they are aggregates and
JPQL will fight you.

- KPI for a date: counts by status, `avg(retard_min)` over courses with
  `retard_min > 0`, punctuality = share of `passage_gare` with `retard_min < 5`,
  open/resolved incidents, cancelled courses.
- Delays by ligne: group `course` by `ligne_id` for the date.
- Heatmap: `passage_gare` joined to `gare`, bucketed by hour of
  `arrivee_theorique`, averaging `retard_min`. Gare on one axis, hour on the
  other.
- Punctuality over a range with `granularite=jour|mois`: `date_trunc` and group.

Add a covering index if any of these exceeds 500ms on your seeded data. Measure
before adding it.

## A single day is too noisy to headline

Measured in phase 2: two runs of the same simulated day gave 28.8% and 23.3%
of courses 5+ minutes late — about four courses of variance on 73, roughly
±5 points. The perturbation model is stochastic, so this is expected, not a
bug.

Consequence: the daily KPI card is fine as an operational view, but the
punctuality figure you *present* must come from a range. Default the dashboard
date picker to the last 7 days, not to today, and label the daily card as one
day's figure. Otherwise the headline number moves every time the demo is reset
— including if a jury asks you to run it again.

## Charts

Recharts. Three of them: a punctuality line over the selected range, a histogram
of delay buckets, and the heatmap as a coloured grid (plain CSS grid, not a
charting library — it is a table with background colours).

`voyageursImpactes` is estimated from train capacity on delayed courses. Label
it in the UI as an estimate. Do not present a modelled number as measured — a
jury will ask, and having flagged it yourself is the good answer.

## Export

`GET /rapports/{nom}/export?du=&au=&format=csv|xlsx`. Streams the response,
`Content-Disposition: attachment`, filename
`trino-{nom}-{du}-{au}.{ext}`. CSV uses `;` as separator and a UTF-8 BOM so
Excel on a French Windows locale opens it correctly — this detail matters more
than it sounds when your supervisor double-clicks the file.

PDF is out of scope. If phase 7 has slack, revisit.

## Acceptance

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"responsable@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)

curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/v1/tableau-bord/kpi?date=$(date +%F)" | jq
# every field non-null; tauxPonctualite between 0 and 1

curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/v1/rapports/ponctualite/export?du=$(date +%F)&au=$(date +%F)&format=xlsx" \
  -o /tmp/r.xlsx && file /tmp/r.xlsx | grep -qi 'excel\|zip'

head -c 3 <(curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/v1/rapports/ponctualite/export?du=$(date +%F)&au=$(date +%F)&format=csv") \
  | xxd | grep -q 'efbb bf'    # BOM present

VOY=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"voyageur@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)
curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $VOY" \
  "localhost:8080/api/v1/tableau-bord/kpi?date=$(date +%F)"    # expect 403
```

Run the simulator at acceleration 60 for a few hours beforehand so the charts
have several days of data. Empty charts demo badly.
