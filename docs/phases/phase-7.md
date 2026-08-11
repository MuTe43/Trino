# Phase 7 — Console administrateur (2 days)

Also read: `docs/architecture/api-contract.md` (Référentiel, Auth),
`docs/architecture/domain-model.md` (Role, utilisateur, journal_connexion).

## Why this phase exists

The cahier des charges gives `ADMINISTRATEUR` four use cases: gérer les
utilisateurs, paramétrer les lignes, gérer les gares, gérer les trains. Every
one has had a working REST endpoint since phase 0 and **no screen at any point**.
An actor with four use cases and zero interface is an undelivered actor.

This phase is UI over endpoints that already exist, plus the two backend gaps
those screens expose.

## Backend — three gaps

1. **`UtilisateurController` is read-only.** Add create, update (name, role),
   activate/deactivate, and an admin-set temporary password. No self-service
   reset, no email flow — out of scope.
2. **No endpoint for the connection journal.** `GET /journal-connexions?
   succes=&utilisateurId=&du=&au=&page=&taille=`, `ADMINISTRATEUR` only. The
   table has been filling since phase 1 and nothing can read it.
3. **Référentiel query filters**, deferred since phase 0: `?region=` and `?q=`
   on gares, `?type=` and `?ligneId=` on trains. The admin lists are the
   consumer that finally justifies them.

Invariant 9 applies to all of these: URL rule **and** `@PreAuthorize`.

## Build

```
backend/api/.../iam/web/UtilisateurController.java        write verbs
backend/api/.../iam/web/JournalController.java            new
backend/api/.../iam/service/UtilisateurService.java       create, désactiver, réinitialiser
backend/api/.../referentiel/repo/*.java                   filter queries
frontend/src/app/admin/layout.tsx                         role-gated shell + nav
frontend/src/app/admin/page.tsx                           counts, recent connections
frontend/src/app/admin/gares/page.tsx
frontend/src/app/admin/lignes/page.tsx
frontend/src/app/admin/trains/page.tsx
frontend/src/app/admin/utilisateurs/page.tsx
frontend/src/app/admin/journal/page.tsx
frontend/src/components/admin/TableauEditable.tsx         one component, four uses
frontend/src/components/admin/DialogueEdition.tsx
```

No migration this phase.

## Rules

- `TableauEditable` is generic over columns and serves gares, lignes, trains and
  users. Four bespoke tables is the wrong shape and four times the surface.
- **`ligne.trace` is not editable here.** A textarea of coordinate pairs is a
  loaded gun pointed at the map, and phase 5 measured how much depends on it.
  Show the point count and a read-only preview; editing is out of scope.
- Same for `desserte`: read-only list. Reordering stops invalidates `pk_km`
  monotonicity, which the delay engine assumes.
- Deleting a gare or ligne referenced by a course must return `409 CONFLIT` with
  a readable message, never a foreign-key stack trace.
- Deactivating a user does not delete them — the connection journal references
  them, and audit trails do not get holes.
- An admin cannot deactivate or demote their own account. Lock yourself out once
  during a demo and you do not get back in.
- Design direction from `phase-4.md` applies. Dense tables, tabular numerals on
  every number, hairline borders. Invariant 8 on any status colour.

## Acceptance

```bash
ADM=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)

# user management round trip
UID=$(curl -s -X POST localhost:8080/api/v1/utilisateurs -H "Authorization: Bearer $ADM" \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@sncft.tn","nom":"Test","role":"AGENT_CIRCULATION"}' | jq -r .id)
curl -s -X PATCH localhost:8080/api/v1/utilisateurs/$UID -H "Authorization: Bearer $ADM" \
  -H 'Content-Type: application/json' -d '{"actif":false}'
curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@sncft.tn","motDePasse":"..."}'    # expect 401, account inactive

# self-lockout is refused
curl -s -o /dev/null -w '%{http_code}' -X PATCH localhost:8080/api/v1/utilisateurs/1 \
  -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
  -d '{"actif":false}'                                  # expect 409

# journal is readable and paginated
curl -s -H "Authorization: Bearer $ADM" \
  "localhost:8080/api/v1/journal-connexions?succes=false&taille=5" | jq '.total'

# filters exist
curl -s "localhost:8080/api/v1/gares?q=sous" | jq '.contenu | length'      # >= 1
curl -s "localhost:8080/api/v1/trains?type=GRANDES_LIGNES" | jq '.total'   # >= 1

# referential integrity surfaces as 409, not 500
curl -s -o /dev/null -w '%{http_code}' -X DELETE localhost:8080/api/v1/lignes/1 \
  -H "Authorization: Bearer $ADM"                       # expect 409

# a responsable cannot reach the admin API
RESP=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"responsable@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)
curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $RESP" \
  localhost:8080/api/v1/journal-connexions                # expect 403
```

Then in a real browser: create a user, log in as them, deactivate them, confirm
the next login fails. Edit a gare's name and see it change on the public map.
