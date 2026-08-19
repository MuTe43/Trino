#!/usr/bin/env bash
# Builds the service day the load test runs against.
#
# The seed materialises 80 courses. The cahier des charges (§6) asks the
# architecture to hold "plusieurs centaines de trains simultanément", and until
# this existed that claim was an argument rather than a measurement.
#
# This adds 320 trains with one timetable slot each, departing inside a
# 20-minute window -- narrower than the shortest desserte on the network (35
# min), so every one of them is still running when the last leaves. Combined
# with the seeded timetable that is ~400 courses on the day and 300+ of them
# concurrent.
#
# It writes train and horaire rows only. GenerateurCourses turns them into
# courses and passages exactly as it does for the seeded timetable -- a load
# profile that built its own courses would measure a code path the product does
# not use. Nothing here is a Flyway migration: migrations are immutable and
# apply everywhere, and this fleet exists to be measured and then deleted.
#
# Usage:
#   scripts/charge.sh                  # 320 trains, 05:00 to 05:20
#   scripts/charge.sh --trains=500
#   scripts/charge.sh --depart=06:00 --etalement=25
#   scripts/charge.sh --nettoyer       # remove the fleet and everything it produced
#
# Then, in three terminals:
#   1. the API, with the measurement profile:
#        cd backend && ./mvnw -q -pl api spring-boot:run \
#          -Dspring-boot.run.profiles=metrologie \
#          -Dspring-boot.run.arguments=--server.port=8081
#   2. the simulator, started just before the fleet departs:
#        cd backend && TRINO_API_BASE_URL=http://localhost:8081 \
#          TRINO_SIM_HEURE_DEBUT=04:55 TRINO_SIM_ACCELERATION=10 \
#          ./mvnw -q -pl simulateur spring-boot:run
#   3. the measurements:
#        node scripts/mesures.mjs
#
# Environment:
#   TRINO_DB_URL  JDBC URL, if the database is not on localhost:5432. This dev
#                 machine publishes it on 5433 (see docker-compose.override.yml).
set -euo pipefail

cd "$(dirname "$0")/../backend"

# server.port=0 rather than web-application-type=none: ConfigurationSecurite
# takes an HttpSecurity, which only exists in a web context, so switching the
# context type off fails startup outright. An ephemeral port never collides
# with the API this is being run alongside.
ARGS="--spring.profiles.active=charge --server.port=0"
if [ -n "${TRINO_DB_URL:-}" ]; then
  ARGS="$ARGS --spring.datasource.url=$TRINO_DB_URL"
fi
for extra in "$@"; do
  ARGS="$ARGS $extra"
done

exec ./mvnw -q -pl api spring-boot:run -Dspring-boot.run.arguments="$ARGS"
