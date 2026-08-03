# Phase 0 — Fondations + référentiel (2 days)

Also read: `docs/architecture/domain-model.md`.

## Goal

A running Postgres, a Spring Boot API that starts, a Next.js shell that loads,
and the full référentiel (gares, lignes, desserte, trains) seeded with real
SNCFT data and exposed over REST.

## Build

```
docker-compose.yml                       db only, postgres:16, port 5432
backend/pom.xml                          parent, modules: api, simulateur
backend/api/pom.xml                      web, data-jpa, validation, flyway, postgresql, actuator
backend/api/src/main/resources/application.yml
backend/api/src/main/java/tn/sncft/trino/TrinoApplication.java
backend/api/.../commun/ApiExceptionHandler.java     the error envelope
backend/api/.../commun/dto/PageDTO.java
backend/api/.../referentiel/domaine/{Gare,Ligne,Desserte,Train,TypeTrain}.java
backend/api/.../referentiel/repo/{GareRepository,LigneRepository,DesserteRepository,TrainRepository}.java
backend/api/.../referentiel/service/{GareService,LigneService,TrainService}.java
backend/api/.../referentiel/web/{GareController,LigneController,TrainController}.java
backend/api/.../referentiel/dto/*.java              records only
backend/api/src/main/resources/db/migration/V1__referentiel.sql
backend/api/src/main/resources/db/migration/V2__seed_reseau.sql
frontend/  (create-next-app, TypeScript, Tailwind 4, App Router)
frontend/src/lib/api.ts                  typed fetch wrapper, base URL from env
frontend/src/app/page.tsx                placeholder that lists gares from the API
```

Two migrations this phase only because the seed is genuinely separate from the
schema. From phase 1 on, one migration per phase.

## Rules

- Entities are JPA, fields French, `@Enumerated(EnumType.STRING)` always.
- No Lombok. Java records for DTOs, plain classes with explicit getters for
  entities.
- `trace` on `ligne` maps to `String` in the entity and is parsed in the service
  layer. Do not add a JSONB converter dependency.
- Write CRUD for all three, but only wire `GET` endpoints to the frontend now.
- Seed coordinates must be real. If unsure of a station's location, use the
  city centre — do not invent points in the sea.

## Acceptance

```bash
docker compose up -d db
cd backend && ./mvnw -q clean install
cd backend && ./mvnw -q -pl api spring-boot:run &
sleep 25
curl -s localhost:8080/actuator/health | grep -q '"status":"UP"'
test $(curl -s 'localhost:8080/api/v1/gares?taille=200' | grep -o '"id"' | wc -l) -ge 35
curl -s localhost:8080/api/v1/lignes/1/desserte | grep -q 'pkKm'
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/gares/999999   # expect 404
cd frontend && npm run build
```

The 404 must return the error envelope from `ApiExceptionHandler`, not a Spring
Boot default whitelabel body. Check the JSON keys are French.

## Then

Update `docs/STATE.md` and stop.
