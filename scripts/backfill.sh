#!/usr/bin/env bash
# Synthesises finished service dates so the dashboard has history to chart.
#
# Running the simulator for longer does NOT do this: GenerateurCourses
# materialises one service date, the simulator replays that date quickly and
# stops, and you end up with one day and a flat chart.
#
# Idempotent and deterministic. Re-running recomputes the same delays from the
# same per-course seed, so a demo reset reproduces the same numbers. It only
# ever touches dates strictly before today -- today belongs to the simulator.
#
# Usage:
#   scripts/backfill.sh              # 14 days
#   scripts/backfill.sh --jours=30
#
# Environment:
#   TRINO_DB_URL  JDBC URL, if the database is not on localhost:5432. This dev
#                 machine publishes it on 5433 (see docker-compose.override.yml),
#                 so the default here matches docker-compose.yml and the
#                 override is passed explicitly.
set -euo pipefail

cd "$(dirname "$0")/../backend"

# server.port=0 rather than web-application-type=none: ConfigurationSecurite
# takes an HttpSecurity, which only exists in a web context, so switching the
# context type off fails startup outright. An ephemeral port costs nothing and
# never collides with an API already running on 8080/8081.
ARGS="--spring.profiles.active=backfill --server.port=0"
if [ -n "${TRINO_DB_URL:-}" ]; then
  ARGS="$ARGS --spring.datasource.url=$TRINO_DB_URL"
fi
for extra in "$@"; do
  ARGS="$ARGS $extra"
done

exec ./mvnw -q -pl api spring-boot:run -Dspring-boot.run.arguments="$ARGS"
