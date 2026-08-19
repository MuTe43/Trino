#!/usr/bin/env bash
# Puts the database into the state the demo is rehearsed against, then starts
# the feed.
#
#   1. clears what previous runs left behind (notifications, subscriptions, test
#      incidents, the load-test fleet, the inbox);
#   2. regenerates today's courses;
#   3. backfills two weeks of finished days so the charts are not flat;
#   4. prints the ids the demo will actually use, derived at run time;
#   5. starts the simulator at acceleration 30.
#
# Nothing here hardcodes an id. Three acceptance commands across this project
# measured nothing because they named a row that could not exercise the
# behaviour -- DR201 was a train that was never seeded, cibleId 1 was a
# backfilled course already at TERMINUS_ATTEINT. Every subject below comes out
# of the database at the moment it is needed.
#
# Usage:
#   scripts/demo.sh                 # reset, backfill, start the simulator
#   scripts/demo.sh --sans-feed     # everything except starting the simulator
#   scripts/demo.sh --acceleration=60
#
# Environment:
#   TRINO_DB_URL       JDBC URL if the database is not on localhost:5432
#   TRINO_API_BASE_URL default http://localhost:8080; 8081 on this dev machine
#   TRINO_DB_CONTENEUR default trino-db
set -euo pipefail

cd "$(dirname "$0")/.."

CONTENEUR="${TRINO_DB_CONTENEUR:-trino-db}"
ACCELERATION=30
DEMARRER_FEED=1

# The API's address, in order of authority:
#
#   1. TRINO_API_BASE_URL, if the operator set it;
#   2. whatever port docker compose actually published for the api service;
#   3. the portable default.
#
# Step 2 exists because the compose file publishes 8081:8080 on this machine --
# an unrelated service owns 8080 -- and a script that assumed the default polled
# a port nobody was listening on and declared the API dead after three minutes.
# Asking compose is better than an env var the next person will forget: it is
# right on any machine, whatever the mapping.
API="${TRINO_API_BASE_URL:-}"
if [ -z "$API" ]; then
  PORT_PUBLIE="$(docker compose port api 8080 2>/dev/null | sed 's/.*://')"
  if [ -n "$PORT_PUBLIE" ]; then
    API="http://localhost:$PORT_PUBLIE"
  else
    API="http://localhost:8080"
  fi
fi

for argument in "$@"; do
  case "$argument" in
    --sans-feed) DEMARRER_FEED=0 ;;
    --acceleration=*) ACCELERATION="${argument#*=}" ;;
    *) echo "Option inconnue : $argument" >&2; exit 2 ;;
  esac
done

sql() {
  docker exec "$CONTENEUR" psql -U trino -d trino -tAc "$1"
}

echo "== 1. Remise à zéro =="

# The API must be down while the reset runs, and up again before the simulator
# starts. Two separate reasons, both measured in phase 9:
#
#   - it holds hot circulation state keyed on course ids this script deletes;
#   - HorlogeCirculation is monotonic, so a process that has already observed a
#     later simulated time treats the restarted feed as silent and
#     DetecteurSilence flips every course to ARRET_EXCEPTIONNEL. 211 in a minute.
#
# So this script cycles it rather than refusing to run: refusing was the earlier
# behaviour and it was self-contradictory, because step 4 below starts a
# simulator that needs the API answering.
# The compose simulator has to go first and stay down. It keeps its own in-memory
# state for every course it has ever started moving, and MoteurSimulation.recharger
# deliberately never drops a course that is in motion -- so after this script
# deletes and regenerates the day, that container posts positions for ids the API
# no longer knows. Measured: "0 position(s) acceptée(s), 178 rejetée(s)", i.e. a
# map with no trains on it at all. It would also race the accelerated simulator
# this script starts at step 4, against a monotone clock.
if docker compose ps --services --filter status=running 2>/dev/null | grep -qx simulateur; then
  echo "Arrêt du simulateur de la pile (celui de la démo tourne en accéléré)…"
  docker compose stop simulateur > /dev/null
fi

API_RELANCER=0
if curl -s -m 2 -o /dev/null "$API/actuator/health" 2>/dev/null; then
  if docker compose ps --services --filter status=running 2>/dev/null | grep -qx api; then
    echo "Arrêt de l'API le temps de la remise à zéro…"
    docker compose stop api > /dev/null
    API_RELANCER=1
  else
    echo "L'API répond sur $API mais n'est pas gérée par docker compose." >&2
    echo "Arrêtez-la (Ctrl-C sur son ./mvnw) puis relancez ce script : le moteur" >&2
    echo "garde en mémoire des courses qui vont être supprimées, et son horloge" >&2
    echo "simulée ne recule jamais." >&2
    exit 1
  fi
fi

sql "delete from notification;
     delete from abonnement;
     delete from regle_alerte where id > 4;
     update regle_alerte set canaux = 'IN_APP,EMAIL,SMS,AFFICHAGE', modifie_par = null where id <= 4;" > /dev/null
echo "notifications, abonnements et règles d'alerte remis à l'état du seed"

# Every incident, because no migration seeds one: the table is empty on a fresh
# database, so anything in it was declared while rehearsing. This also has to
# happen before the courses go -- incident.course_id is NO ACTION, not CASCADE.
sql "delete from incident;" > /dev/null
echo "incidents de test supprimés"

# Today's runs, so the day starts at A_QUAI with no progress. passage_gare and
# position_course cascade from course.
sql "delete from notification where course_id in (select id from course where date_service = current_date);
     delete from incident      where course_id in (select id from course where date_service = current_date);
     delete from course where date_service = current_date;" > /dev/null
echo "courses du jour supprimées, elles vont être régénérées"

curl -s -X DELETE "http://localhost:8025/api/v1/messages" > /dev/null 2>&1 \
  && echo "boîte Mailpit vidée" \
  || echo "Mailpit injoignable, boîte non vidée (sans conséquence)"

echo
echo "== 2. Journée courante et historique =="

# The load-test fleet must not be part of a demo: 320 extra trains make the map
# unreadable and the dashboard meaningless. Removing it also regenerates today.
TRINO_DB_URL="${TRINO_DB_URL:-}" bash scripts/charge.sh --nettoyer 2>&1 \
  | grep -E "train\(s\) de charge|train\(s\) supprimé" || true

# Fourteen days of finished service, so the punctuality curve has a shape. This
# is idempotent and deterministic: the same seed produces the same delays, so
# the numbers on stage are the numbers from the rehearsal.
#
# The result is ASSERTED, not assumed. This used to end in `|| true`, which meant
# a backfill that failed to connect was indistinguishable from one that had
# nothing to do -- the script carried on, and the failure only became visible on
# stage as a punctuality chart with a single flat point. A demo script that hides
# its own failures is worse than no demo script.
JOURS_AVANT="$(sql "select count(distinct date_service) from course where statut = 'TERMINUS_ATTEINT'")"
bash scripts/backfill.sh > /tmp/trino-backfill.log 2>&1 || true
JOURS_APRES="$(sql "select count(distinct date_service) from course where statut = 'TERMINUS_ATTEINT'")"

if [ "${JOURS_APRES:-0}" -lt 7 ]; then
  echo >&2
  echo "L'historique n'a pas été écrit : $JOURS_APRES journée(s) terminée(s) en base" >&2
  echo "(avant : ${JOURS_AVANT:-0}). Les graphiques seraient plats." >&2
  echo >&2
  echo "Cause la plus fréquente : backfill.sh vise localhost:5432 par défaut, et" >&2
  echo "sur cette machine ce port appartient à un autre PostgreSQL. Relancez avec" >&2
  echo "l'URL explicite :" >&2
  echo "  TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino bash scripts/demo.sh" >&2
  echo >&2
  echo "Journal complet : /tmp/trino-backfill.log" >&2
  exit 1
fi
echo "historique : $JOURS_APRES journée(s) terminée(s) en base"

# Today's courses were deleted above and nothing has recreated them: backfill.sh
# only writes dates strictly before today, and charge.sh --nettoyer only removes.
# GenerateurCourses materialises the current day from an ApplicationReadyEvent,
# so bringing the API back up IS the regeneration -- and it has to happen here,
# before the subjects below are derived from a table that is otherwise empty.
if [ "$API_RELANCER" -eq 1 ]; then
  echo "Redémarrage de l'API (elle régénère la journée et repart d'une horloge neuve)…"
  docker compose start api > /dev/null
fi

echo "En attente de l'API sur $API et de la régénération de la journée…"

# Waiting on /actuator/health is NOT enough, and the difference is a race that
# cost a run: Tomcat answers UP as soon as it is listening, while
# GenerateurCourses materialises the day from an ApplicationReadyEvent that
# fires afterwards. The script then read an empty course table and declared the
# generation failed -- while it was still running, and succeeded a second later.
#
# So the wait is on the thing actually needed: courses for today, in the
# database. Same principle as everything else in this script -- derive the
# condition from the data, never from a proxy that is merely correlated with it.
COURSES_PRETES=0
for _ in $(seq 1 60); do
  if curl -s -m 3 "$API/actuator/health" 2>/dev/null | grep -q '"status":"UP"' \
     && [ "$(sql "select count(*) from course where date_service = current_date")" -gt 0 ] 2>/dev/null; then
    COURSES_PRETES=1
    break
  fi
  sleep 3
done
if [ "$COURSES_PRETES" -eq 0 ]; then
  echo "L'API n'a pas régénéré la journée sur $API après 3 minutes." >&2
  echo "Vérifiez ses journaux (docker compose logs api) puis relancez ce script." >&2
  exit 1
fi
echo "API prête, journée du jour régénérée"

echo
echo "== 3. Sujets de la démo, dérivés à l'instant =="

COURSE=$(sql "select id from course
              where date_service = current_date
              order by depart_theorique
              limit 1")
GARE=$(sql "select g.id from gare g
            join desserte d on d.gare_id = g.id
            where g.actif
            group by g.id, g.nom
            order by count(*) desc, g.nom
            limit 1")
LIGNE=$(sql "select id from ligne where actif order by distance_km desc limit 1")

if [ -z "$COURSE" ] || [ -z "$GARE" ] || [ -z "$LIGNE" ]; then
  echo "Aucune course, gare ou ligne exploitable : la génération a échoué." >&2
  exit 1
fi

FRONT="${TRINO_FRONT_BASE_URL:-http://localhost:3000}"
cat <<RECAP

  1. Accueil puis carte plein écran         $FRONT/  puis  $FRONT/carte
  2. Un train et sa liste d'arrêts          $FRONT/trains/$COURSE
  3. Écran de gare (second écran)           $FRONT/affichage/$GARE
     départs de cette gare                  $FRONT/gares/$GARE
  4. Suivre le train, cloche, puis l'e-mail http://localhost:8025
  5. Agent : déclarer un incident           $FRONT/exploitation/incidents
     ligne la plus longue, id               $LIGNE
  6. Responsable : tableau de bord, export  $FRONT/exploitation/tableau-bord
  7. Admin : créer un compte, désactiver    $FRONT/admin/utilisateurs
  8. Tuer le simulateur, dégradation        Ctrl-C ici, puis attendre 90 s

RECAP

if [ "$DEMARRER_FEED" -eq 0 ]; then
  echo "--sans-feed : le simulateur n'est pas démarré."
  echo "Pour revenir au flux temps réel de la pile : docker compose start simulateur"
  exit 0
fi

echo "== 4. Simulateur, accélération x$ACCELERATION =="
echo "Ctrl-C pour l'arrêter -- c'est l'étape 8 de la démo."
echo "Ensuite, pour rendre la main au flux temps réel de la pile :"
echo "  docker compose start simulateur"
echo

cd backend
# heure-debut just before the first departure of the day, so an accelerated run
# actually plays the morning instead of skipping it.
PREMIER_DEPART=$(docker exec "$CONTENEUR" psql -U trino -d trino -tAc \
  "select to_char(min(depart_theorique) at time zone 'Africa/Tunis' - interval '5 minutes', 'HH24:MI')
   from course where date_service = current_date")

exec env \
  TRINO_API_BASE_URL="$API" \
  TRINO_SIM_ACCELERATION="$ACCELERATION" \
  TRINO_SIM_HEURE_DEBUT="$PREMIER_DEPART" \
  ./mvnw -q -pl simulateur spring-boot:run
