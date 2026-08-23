# Runbook — running and testing Trino by hand

Every command here is written for **Git Bash** on this machine, with the standing
deviations already applied:

| Standing deviation | Why |
|---|---|
| Postgres on **5433** | An unrelated PostgreSQL service owns 5432. |
| API on **8081** | An unrelated service owns 8080. |
| `node` instead of `jq` | `jq` is not installed. |
| `docker exec trino-db psql` instead of `psql` | `psql` is not installed on the host. |

The phase files say 8080 and `jq`. **Substitute at run time — never edit a phase
file or `application.yml`.**

---

## 0. Ports at a glance

| What | Port | Check |
|---|---|---|
| Postgres (`trino-db`) | 5433 → container 5432 | `docker ps` |
| Mailpit SMTP (`trino-mailpit`) | 1025 | — |
| Mailpit inbox (web) | 8025 | http://localhost:8025 |
| API | 8081 | http://localhost:8081/actuator/health |
| Frontend | 3000 | http://localhost:3000 |
| Simulator | no port | it only makes outbound calls |

---

## 1. Start the stack

Run these from the repo root, each in its own terminal (the API, the simulator
and the frontend all stay in the foreground).

**This section assumes only `db` and `mailpit` are containerised.** If the full
stack from §11.1 is up, its `api` already owns 8081 and its `simulateur` is
already feeding positions — a second one races it against a clock that never
goes backwards. Stop them first:

```bash
docker compose stop api simulateur web
```

### 1.1 Containers

```bash
docker compose up -d db mailpit
```

Wait for both to be healthy:

```bash
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
```

### 1.2 API

```bash
cd backend && ./mvnw -q -pl api spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --spring.datasource.url=jdbc:postgresql://localhost:5433/trino"
```

Flyway runs on startup. Confirm it is up:

```bash
curl -s http://localhost:8081/actuator/health
```

The body now carries the probe groups, so the check is still a `"status":"UP"`
match but no longer an exact-string one:

```
{"status":"UP","groups":["liveness","readiness"]}
```

The two groups are readable on their own:

```bash
curl -s http://localhost:8081/actuator/health/readiness
curl -s http://localhost:8081/actuator/health/liveness
```

Neither is a substitute for waiting on data. Nothing answers on 8081 until the
context has refreshed — Tomcat binds after the beans are built, so Flyway has
already migrated by the time any of the three replies — and `GenerateurCourses`
hangs off the same `ApplicationReadyEvent` as readiness, in no defined order.
Anything needing today's courses has to ask for the courses, which is what
`scripts/demo.sh` does.

### 1.3 Simulator

It reads the day's courses over HTTP and POSTs positions — it never touches the
database (invariant 3). `TRINO_API_BASE_URL` must point at **8081**, or it will
talk to whatever owns 8080.

```bash
cd backend && TRINO_API_BASE_URL=http://localhost:8081 TRINO_SIM_ACCELERATION=20 TRINO_SIM_HEURE_DEBUT=05:25 ./mvnw -q -pl simulateur spring-boot:run
```

At acceleration 20, one real second is 20 simulated seconds — a full service day
replays in roughly an hour.

### 1.4 Frontend

`NEXT_PUBLIC_API_BASE_URL` is baked in **at build time**. Building without it
produces a bundle that calls 8080 and every page silently fails to load data.

```bash
cd frontend && NEXT_PUBLIC_API_BASE_URL=http://localhost:8081 npm run build
```

```bash
cd frontend && NEXT_PUBLIC_API_BASE_URL=http://localhost:8081 npm run start
```

For hot reload instead, the same variable works on dev:

```bash
cd frontend && NEXT_PUBLIC_API_BASE_URL=http://localhost:8081 npm run dev
```

### 1.5 Stop everything

Ctrl-C the three foreground processes, then:

```bash
docker compose stop
```

---

## 2. A JSON helper

`jq` is absent, so pipe to `node`. Paste this once per terminal and use `j` like
a tiny `jq`:

```bash
j() { node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const o=JSON.parse(d);console.log(typeof o==='object'?JSON.stringify(eval('o$1'),null,1):o)})"; }
```

Usage: `curl -s ... | j .total` · `curl -s ... | j '.contenu[0].numeroTrain'` ·
`curl -s ... | j ''` to pretty-print the whole body.

**Quote any path containing `[`** — bash treats `[0]` as a glob and will mangle
it if a matching filename happens to exist. Verified working against
`localhost:8025/api/v1/messages`.

---

## 3. Database

There is no host `psql`. Go through the container.

### 3.1 Interactive shell

```bash
docker exec -it trino-db psql -U trino -d trino
```

`\dt` lists tables, `\d abonnement` describes one, `\q` quits.

### 3.2 One-off query

`-t -A -F'|'` gives clean, script-friendly output (no headers, no padding).

```bash
docker exec trino-db psql -U trino -d trino -t -A -F'|' -c "select id, numero, nom from train limit 5;"
```

### 3.3 Which migrations have run

```bash
docker exec trino-db psql -U trino -d trino -c "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;"
```

### 3.4 Queries worth keeping

Day's courses by status:

```bash
docker exec trino-db psql -U trino -d trino -c "select statut, count(*) from course where date_service = current_date group by statut order by 2 desc;"
```

Subscriptions (the token is truncated on purpose — it is a bearer credential):

```bash
docker exec trino-db psql -U trino -d trino -t -A -F'|' -c "select id, coalesce(left(jeton_anonyme,8)||'...','compte '||utilisateur_id), cible_type, cible_id, canaux, coalesce(email,'-') from abonnement order by id;"
```

Notifications emitted:

```bash
docker exec trino-db psql -U trino -d trino -t -A -F'|' -c "select id, abonnement_id, evenement, course_id, canal, statut, coalesce(left(erreur,60),'-') from notification order by id desc limit 20;"
```

Alert rules:

```bash
docker exec trino-db psql -U trino -d trino -t -A -F'|' -c "select id, evenement, seuil_min, gravite_min, canaux, actif, modifie_par from regle_alerte order by id;"
```

---

## 4. Authentication

Four demo accounts, password `Trino2026!`:
`admin@` · `agent@` · `responsable@` · `voyageur@sncft.tn`.

Grab a token into a shell variable:

```bash
ADM=$(curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@sncft.tn","motDePasse":"Trino2026!"}' | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).accessToken))")
```

Same shape for the others — swap the email and the variable name (`AG`, `RESP`,
`VOY`). Access tokens last 30 minutes; re-run when calls start returning 401.

Check who you are:

```bash
curl -s -H "Authorization: Bearer $ADM" localhost:8081/api/v1/auth/me
```

**Roles are not hierarchical.** `ADMINISTRATEUR` gets 403 on the dashboards and
on the incident console — different duties, by design. Expect it.

---

## 5. Phase 8 — notifications and alerts

### 5.1 Follow something, as an anonymous passenger

The browser gets an `HttpOnly` cookie; `curl` supplies its own token in
`X-Abonne`. Both are legitimate — the value is only ever a key to your own rows.

```bash
JETON=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 32); echo "JETON=$JETON"
```

```bash
curl -s -X POST localhost:8081/api/v1/abonnements -H 'Content-Type: application/json' -H "X-Abonne: $JETON" -d '{"cibleType":"COURSE","cibleId":1361,"canaux":["IN_APP","EMAIL"],"email":"test@exemple.tn"}'
```

`201` the first time, `200` on a repeat (re-subscribing updates, it is not a
409). `cibleType` may also be `LIGNE` or `GARE` — a LIGNE subscription is the
easiest way to see traffic, since every delayed course on that line matches.

Read them back:

```bash
curl -s -H "X-Abonne: $JETON" localhost:8081/api/v1/abonnements/miennes
```

Unfollow (someone else's id answers 404, never 403):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "X-Abonne: $JETON" localhost:8081/api/v1/abonnements/1
```

### 5.2 Read notifications

```bash
curl -s -H "X-Abonne: $JETON" localhost:8081/api/v1/notifications | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const p=JSON.parse(d);console.log('total='+p.total);p.contenu.forEach(n=>console.log(' ',n.canal,n.statut,'|',n.evenement,'|',n.sujet))})"
```

A visitor with no identity gets an empty page, not a 401.

### 5.3 The email, in an inbox

Open **http://localhost:8025** — that is the deliverable. Or from the shell:

```bash
curl -s localhost:8025/api/v1/messages | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const m=JSON.parse(d);console.log('total='+m.total);(m.messages||[]).forEach(x=>console.log(' ',x.From.Address,'->',x.To.map(t=>t.Address).join(','),'|',x.Subject))})"
```

Empty the inbox without restarting the container:

```bash
curl -s -X DELETE localhost:8025/api/v1/messages && echo " - inbox vidée"
```

> **Mailpit has no volume, on purpose.** `docker compose restart mailpit` (or
> stop/start) empties it. A persisted inbox would show yesterday's demo next to
> today's.

### 5.4 Force a notification without waiting for a delay

A delayed course only crosses the threshold when the simulator has run a while.
An incident fires immediately, and the seeded `INCIDENT_DECLARE` rule accepts
`MOYENNE` and above. Subscribe to `LIGNE` 1 first, then:

```bash
AG=$(curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"agent@sncft.tn","motDePasse":"Trino2026!"}' | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).accessToken))")
```

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8081/api/v1/incidents -H "Authorization: Bearer $AG" -H 'Content-Type: application/json' -d '{"type":"PANNE_LOCOMOTIVE","description":"Panne de locomotive, remorquage en cours","gravite":"CRITIQUE","ligneId":1,"impact":"Retards en cascade","survenuAt":"2026-08-12T13:40:00Z"}'
```

Dispatch is asynchronous — give it a few seconds before checking.

### 5.5 Alert rules (ADMINISTRATEUR only)

```bash
curl -s -H "Authorization: Bearer $ADM" localhost:8081/api/v1/regles-alerte | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>JSON.parse(d).forEach(r=>console.log(r.id,r.evenement,'seuil='+r.seuilMin,'gravite='+r.graviteMin,r.canaux.join('|'),'actif='+r.actif)))"
```

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8081/api/v1/regles-alerte -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' -d '{"evenement":"RETARD_SEUIL","seuilMin":15,"canaux":["IN_APP"],"actif":true}'
```

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X PATCH localhost:8081/api/v1/regles-alerte/1 -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' -d '{"actif":false}'
```

`seuilMin` is required for `RETARD_SEUIL` and refused (409) for every other
event. `PATCH` takes no `evenement` — that would be a different rule.

Refusals worth checking (both should be **403**, and the second must not be 400):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $VOY" localhost:8081/api/v1/regles-alerte
```

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8081/api/v1/regles-alerte -H "Authorization: Bearer $VOY" -H 'Content-Type: application/json' -d '{"evenement":"PAS_UN_EVENEMENT","canaux":[]}'
```

### 5.6 The rate limit

Ten posts a minute per IP; the eleventh is `429 TROP_DE_REQUETES`. The window is
fixed and shared with every other `POST /abonnements` from this machine, so if
you ran 5.1 within the same minute the refusal arrives that many posts early —
which is the limit working, not an off-by-one.

```bash
for i in $(seq 1 11); do curl -s -o /dev/null -w "$i: %{http_code}\n" -X POST localhost:8081/api/v1/abonnements -H 'Content-Type: application/json' -H "X-Abonne: $JETON" -d '{"cibleType":"GARE","cibleId":'"$i"',"canaux":["IN_APP"]}'; done
```

The window is in memory — restart the API to clear it.

### 5.7 Ingestion is not blocked by a dead channel

Stop the mail server, then confirm ingestion still answers fast and the failure
is recorded rather than swallowed.

```bash
docker stop trino-mailpit
```

```bash
COURSE=$(curl -s -H 'X-Ingest-Key: dev-key' localhost:8081/api/v1/ingest/courses-du-jour | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const c=JSON.parse(d);console.log(c[0].courseId||c[0].id)})"); echo "course=$COURSE"
```

```bash
for i in 1 2 3 4 5; do curl -s -o /dev/null -w 'http=%{http_code} temps=%{time_total}s\n' -X POST localhost:8081/api/v1/ingest/positions -H 'X-Ingest-Key: dev-key' -H 'Content-Type: application/json' -d "{\"pings\":[{\"courseId\":$COURSE,\"horodatage\":\"2026-08-12T13:20:00Z\",\"latitude\":35.8256,\"longitude\":10.6084,\"vitesseKmh\":96}]}"; done
```

Expect `202` well under 200 ms. Then declare an incident (5.4), wait a few
seconds, and look for an `ECHEC` row with its cause:

```bash
docker exec trino-db psql -U trino -d trino -t -A -F'|' -c "select id, canal, statut, left(erreur,80) from notification where statut='ECHEC' order by id desc limit 5;"
```

```bash
docker start trino-mailpit
```

### 5.8 Deduplication

One notification per (subscription, event, course) per **30 simulated minutes**.
Over a replay, group with the course and the channel included — one emission
writes one row per channel, so grouping without them counts channels as
duplicates:

```bash
docker exec trino-db psql -U trino -d trino -c "select abonnement_id, evenement, course_id, canal, count(*) from notification group by 1,2,3,4 having count(*) > 2;"
```

The phase file's own query drops `course_id` and `canal` and fails over 3; it
trips on a two-channel subscription at its second window. See STATE.md.

---

## 6. Live stream (SSE)

Watch deltas arrive. Ctrl-C to stop.

```bash
curl -N "localhost:8081/api/v1/stream?lignes=1&gares=7"
```

One channel only, the shape the kiosk board uses:

```bash
curl -N localhost:8081/api/v1/stream/gares/7
```

Your own notification channel — note there is **no `abonnes=` parameter**; the
server derives it from the header or the cookie, and frames come back tagged
`abonne:moi`:

```bash
curl -N -H "X-Abonne: $JETON" localhost:8081/api/v1/stream
```

An empty subscription with no token is `400`, by design.

---

## 7. Everything else, quickly

Référentiel (reads are public):

```bash
curl -s "localhost:8081/api/v1/gares?q=sous" | j .total
```

```bash
curl -s "localhost:8081/api/v1/trains?type=GRANDES_LIGNES" | j .total
```

Courses and the station board:

```bash
curl -s "localhost:8081/api/v1/courses?statut=RETARDE&taille=5" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const p=JSON.parse(d);console.log('total='+p.total);p.contenu.forEach(c=>console.log(' id='+c.id,c.numeroTrain,c.statut,c.retardMin+'min'))})"
```

```bash
curl -s "localhost:8081/api/v1/gares/1/departs?limite=10" | j .length
```

Dashboards (`RESPONSABLE_EXPLOITATION` only — admin gets 403). **Every date
parameter here is required**, unlike the filters elsewhere in the API: `date` on
`kpi` and `retards-par-ligne`, `du`/`au` on `heatmap`, `distribution-retards` and
both reports. Omitting one is a `400 VALIDATION_ECHOUEE` naming the parameter.

```bash
curl -s -H "Authorization: Bearer $RESP" "localhost:8081/api/v1/tableau-bord/kpi?date=$(date +%F)" | j ''
```

```bash
curl -s -H "Authorization: Bearer $RESP" "localhost:8081/api/v1/tableau-bord/heatmap?du=2026-08-01&au=$(date +%F)" | j .length
```

Connection journal (`ADMINISTRATEUR` only):

```bash
curl -s -H "Authorization: Bearer $ADM" "localhost:8081/api/v1/journal-connexions?taille=5" | j .total
```

---

## 8. Tests

**Never run `./mvnw test` without `TRINO_DB_URL`.** The DB-backed tests self-skip
when the database is unreachable, and the suite reports green having exercised
none of the dashboard or seed SQL.

```bash
cd backend && TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino ./mvnw -q test
```

A green run must say **0 skipped**. To see the totals rather than only failures,
drop `-q`:

```bash
cd backend && TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino ./mvnw -pl api test 2>&1 | grep -E "Tests run:.*Failures|BUILD" | tail -5
```

Frontend:

```bash
cd frontend && npx tsc --noEmit && npx eslint src --max-warnings=0 && npm run build
```

---

## 9. Resetting between demos

Clear phase 8 data and put the alert rules back as V8 seeded them:

```bash
docker exec trino-db psql -U trino -d trino -c "delete from notification; delete from abonnement; delete from regle_alerte where id > 4; update regle_alerte set canaux='IN_APP,EMAIL,SMS,AFFICHAGE', modifie_par=null where id <= 4;"
```

Remove incidents declared while testing (adjust the date):

```bash
docker exec trino-db psql -U trino -d trino -c "delete from incident where survenu_at >= '2026-08-12T12:00:00Z';"
```

Empty the mail inbox:

```bash
curl -s -X DELETE localhost:8025/api/v1/messages && echo " - inbox vidée"
```

Accounts created while testing cannot be deleted — `journal_connexion` and
`refresh_token` both reference them. Deactivate instead (that is the design, not
an obstacle). If you must, clear the referencing rows first.

Nuclear option — throws away the whole database, Flyway re-runs from V1 on the
next API start:

```bash
docker compose down -v && docker compose up -d db mailpit
```

---

## 10. Gotchas that have already cost time

- **Accents in a request body.** Git Bash transcodes `argv` to the Windows ANSI
  codepage for a native binary, so `-d '{"nom":"Gabès"}'` arrives mangled. Put
  the JSON in a UTF-8 file and send `-d @corps.json`.
- **`document.hidden` is permanently true in the in-app browser**, so the map has
  never been *seen* rendering. Use a real Chrome window for anything visual.
- **The bell renders nothing until you follow something.** That is correct, not a
  bug.
- **Only `IN_APP` and `EMAIL` are offered in the UI.** `SMS` is a stub that logs
  (there is no phone number anywhere in the model) and `AFFICHAGE` is the station
  board, already delivered over `gare:{id}`.
- **Two `psql` ports look plausible, and `docker ps` shows both.** Compose
  *appends* to a `ports` list rather than replacing it, so with
  `docker-compose.override.yml` loaded the db container publishes 5432 **and**
  5433. Only 5433 reaches it: on 5432 the unrelated PostgreSQL wins the bind and
  answers `authentification par mot de passe échouée pour l'utilisateur
  « trino »` — or, if you do have a role by that name over there, confusing
  "table does not exist" errors.
- **PowerShell differs.** No `&&` chaining (`A; if ($?) { B }`), and env vars are
  `$env:VAR = 'x'; cmd` rather than `VAR=x cmd`. The commands above assume Git
  Bash.

---

## 11. Phase 9 — charge, sauvegarde, démonstration

### 11.1 La pile entière en une commande

```bash
docker compose up -d
docker compose ps            # les cinq services, avec leur santé
docker compose logs -f api
```

Depuis une base vierge : `docker compose down -v && docker compose up -d`, puis
comptez deux minutes le temps que Flyway migre et que la journée se matérialise.

### 11.2 Test de charge

Le simulateur de la pile doit être arrêté avant celui du test : deux producteurs
sur une même API se disputent une horloge simulée qui ne recule jamais, et celui
du conteneur continue de poster des positions pour des courses que l'autre a
laissées derrière lui. `demo.sh` fait le même arrêt, pour la même raison mesurée
(« 0 position(s) acceptée(s), 178 rejetée(s) »).

```bash
docker compose stop simulateur
TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino bash scripts/charge.sh
docker compose restart api                       # horloge simulée remise à zéro
cd backend && TRINO_API_BASE_URL=http://localhost:8081   TRINO_SIM_HEURE_DEBUT=04:55 TRINO_SIM_ACCELERATION=5   ./mvnw -q -pl simulateur spring-boot:run
# dans un autre terminal, une fois le plateau atteint (~4 min) :
TRINO_API_BASE_URL=http://localhost:8081 node scripts/mesures.mjs --duree=300
```

La latence d'ingestion n'est pas dans la sortie de `mesures.mjs` : elle est
journalisée chaque minute par le simulateur (`Latence d'ingestion : n=… p50=…`).

**Toujours nettoyer après.** La flotte de charge fait échouer `CoherenceSeedTest`
si elle reste, et rend la carte illisible :

```bash
TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino bash scripts/charge.sh --nettoyer
docker compose start simulateur                  # rendre la main au flux normal
```

Pour la mémoire JVM, démarrer l'API avec `--spring.profiles.active=metrologie`,
qui expose `/actuator/metrics`. Ce profil n'est pas actif par défaut : `/actuator/**`
est en accès libre, et les métriques renseignent gratuitement un attaquant.

### 11.3 Sauvegarde et restauration

```bash
bash scripts/sauvegarde.sh                  # rétention 7 par défaut
bash scripts/sauvegarde.sh --retention=30
gunzip -c sauvegardes/trino-AAAA-MM-JJ-hhmm.sql.gz | docker exec -i trino-db psql -U trino -d trino
```

### 11.4 Démonstration

```bash
TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino bash scripts/demo.sh
```

Remet à zéro, régénère la journée, synthétise l'historique, puis lance le feed
accéléré. Le script imprime les URL exactes des huit étapes, identifiants dérivés
à l'exécution.

**Laissez la pile tourner.** Le script arrête lui-même le simulateur et l'API le
temps de la remise à zéro, puis **redémarre l'API** — parce qu'il faut qu'elle
reparte pour régénérer la journée qu'il vient de supprimer
(`GenerateurCourses` matérialise le jour sur `ApplicationReadyEvent`). Il ne
redémarre que ce qu'il a arrêté : si vous faites `docker compose stop api`
d'abord, la journée est supprimée, rien ne la recrée, et le script attend trois
minutes avant d'abandonner. Une version antérieure refusait de tourner face à une
API vivante, ce qui était contradictoire — l'étape 4 démarre un simulateur qui a
besoin d'une API qui répond.

`TRINO_DB_URL` est nécessaire ici parce que `backfill.sh`, appelé à l'étape 2,
vise `localhost:5432` par défaut. Sans elle, le script s'arrête en le disant
plutôt que de servir des graphiques plats.

Pour rendre la main au flux temps réel de la pile, une fois la démonstration
terminée :

```bash
docker compose start simulateur
```

### 11.5 Vérifier le balayage des notifications orphelines

```bash
docker exec trino-db psql -U trino -d trino -c "
insert into notification (evenement, destinataire, canal, sujet, contenu, statut, cree_at)
values ('RETARD_SEUIL','test@sncft.tn','EMAIL','Tuée en vol','x','EN_ATTENTE', now() - interval '30 seconds');"
docker compose restart api
docker exec trino-db psql -U trino -d trino -c "select statut, erreur from notification;"
```

Attendu : `ECHEC`, cause « Processus interrompu avant la remise ». Une ligne de
plus de dix minutes est prise par le balayage périodique, avec l'autre cause.

