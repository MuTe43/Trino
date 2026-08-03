# Phase 1 — Auth & rôles (1.5 days)

Also read: `docs/architecture/api-contract.md` (Auth section) and
`docs/architecture/domain-model.md` (Role enum, `utilisateur` and
`journal_connexion` tables).

## The four roles — do not invent these

```
VOYAGEUR                    public, read-only
AGENT_CIRCULATION           declares incidents, sets a course to ARRET_EXCEPTIONNEL or ANNULE
RESPONSABLE_EXPLOITATION    all of the above, plus resolves incidents and sees every dashboard
ADMINISTRATEUR              référentiel writes and user management
```

These names are load-bearing. Phase 5 and phase 6 gate on them by string, and
the seeded demo accounts below use them. Any other naming breaks both.

## Goal

JWT login with the four roles from the spec, role-gated référentiel writes, and
a connection journal that records both successes and failures.

## Build

```
backend/api/.../iam/domaine/{Utilisateur,Role,JournalConnexion}.java
backend/api/.../iam/repo/{UtilisateurRepository,JournalConnexionRepository}.java
backend/api/.../iam/service/{UtilisateurService,JetonService,JournalService}.java
backend/api/.../iam/web/{AuthController,UtilisateurController}.java
backend/api/.../iam/dto/*.java
backend/api/.../securite/{ConfigurationSecurite,FiltreJwt,DetailsUtilisateur}.java
backend/api/src/main/resources/db/migration/V3__iam.sql
frontend/src/lib/auth.ts                 token storage + refresh interceptor
frontend/src/app/(auth)/connexion/page.tsx
frontend/src/middleware.ts               protects /exploitation and /admin
```

Seed four users in V3, one per role, password `Trino2026!` hashed with BCrypt.
Put the credentials in `docs/STATE.md` so you can demo without hunting.

## Rules

- Access token 30 min, refresh 7 days, HS256, secret from `TRINO_JWT_SECRET`
  with a dev default in `application.yml`.
- Refresh tokens are stored hashed in a `refresh_token` table with `revoque`.
  No rotation, no device tracking — out of scope.
- `JournalService.enregistrer(...)` is called on every login attempt, before the
  response is built. A failed login with an unknown email still writes a row
  with `utilisateur_id` null.
- Authorities are `ROLE_` + enum name. Use `@PreAuthorize` on service methods,
  not on controllers.
- SSE endpoints and all référentiel `GET`s stay public — configure this
  explicitly, do not rely on defaults.
- Frontend stores the access token in memory and the refresh token in an
  httpOnly cookie set by the API. No `localStorage` for tokens.

## Acceptance

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)
test -n "$TOKEN"
curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8080/api/v1/gares \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"code":"TEST","nom":"Test","region":"Tunis","latitude":36.8,"longitude":10.1,"nbQuais":2}'
# expect 201

VOY=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"voyageur@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)
curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8080/api/v1/gares \
  -H "Authorization: Bearer $VOY" -d '{}'   # expect 403

curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"inconnu@x.tn","motDePasse":"faux"}' -o /dev/null
psql -h localhost -U trino -d trino -c \
  "select succes, email_tente from journal_connexion order by id desc limit 1"
# expect: f | inconnu@x.tn
```

## Then

Update `docs/STATE.md` and stop.
