#!/usr/bin/env bash
# Dumps the Trino database to a dated, compressed file and prunes old ones.
#
# §6 of the cahier des charges asks for automatic backup. Until this existed the
# only thing in the repository was a paragraph in decisions.md saying it was
# intended, which is worth nothing to whoever has to restore.
#
# Usage:
#   scripts/sauvegarde.sh                    # dump, keep the 7 most recent
#   scripts/sauvegarde.sh --retention=30
#   scripts/sauvegarde.sh --repertoire=/var/backups/trino
#
# Environment:
#   TRINO_DB_CONTENEUR  docker container running Postgres   (default trino-db)
#   TRINO_DB_NOM        database name                       (default trino)
#   TRINO_DB_UTILISATEUR                                    (default trino)
#   PGPASSWORD          only needed when pg_dump runs on the host
#
# Restore:
#   gunzip -c sauvegardes/trino-2026-08-19-1642.sql.gz \
#     | docker exec -i trino-db psql -U trino -d trino
#
# Automatic, every night at 02:00 -- the hour before GenerateurCourses
# materialises the new service day at 03:00, so a restored dump is always a
# whole day rather than half of one:
#   0 2 * * * cd /chemin/vers/Trino && scripts/sauvegarde.sh >> /var/log/trino-sauvegarde.log 2>&1
set -euo pipefail

CONTENEUR="${TRINO_DB_CONTENEUR:-trino-db}"
BASE="${TRINO_DB_NOM:-trino}"
UTILISATEUR="${TRINO_DB_UTILISATEUR:-trino}"
REPERTOIRE="sauvegardes"
RETENTION=7

for argument in "$@"; do
  case "$argument" in
    --retention=*) RETENTION="${argument#*=}" ;;
    --repertoire=*) REPERTOIRE="${argument#*=}" ;;
    *) echo "Option inconnue : $argument" >&2; exit 2 ;;
  esac
done

if ! [ "$RETENTION" -ge 1 ] 2>/dev/null; then
  echo "--retention doit être un entier d'au moins 1, reçu « $RETENTION »." >&2
  exit 2
fi

cd "$(dirname "$0")/.."
mkdir -p "$REPERTOIRE"

# Minutes, not just the date. A restore rehearsal and the nightly cron can land
# on the same day, and a filename that collides overwrites the only good copy
# with whatever the rehearsal produced.
HORODATAGE="$(date +%Y-%m-%d-%H%M)"
FICHIER="$REPERTOIRE/trino-$HORODATAGE.sql.gz"

# The running container FIRST, and that order is the whole point.
#
# A host pg_dump with no --host/--port connects to whatever owns the default
# port. On this machine that is an unrelated PostgreSQL (see
# docker-compose.override.yml), so preferring the host binary produced a
# perfectly valid, complete dump -- of the wrong database. The completeness
# check below cannot catch that: the wrong dump is also complete. A backup filed
# as evidence of a database it never read is worse than no backup.
#
# The container is unambiguous: it IS the database. The host branch stays for a
# deployment where Postgres is not containerised, and there it must be told
# exactly where to connect.
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTENEUR"; then
  echo "Sauvegarde via docker exec $CONTENEUR vers $FICHIER"
  docker exec "$CONTENEUR" pg_dump --username="$UTILISATEUR" --dbname="$BASE" \
    --no-owner --no-privileges | gzip > "$FICHIER"
elif command -v pg_dump > /dev/null 2>&1; then
  HOTE="${TRINO_DB_HOTE:-localhost}"
  PORT="${TRINO_DB_PORT:-5432}"
  echo "Sauvegarde via pg_dump local vers $FICHIER ($HOTE:$PORT)"
  echo "Conteneur « $CONTENEUR » absent : vérifiez que $HOTE:$PORT est bien la base Trino." >&2
  pg_dump --host="$HOTE" --port="$PORT" --username="$UTILISATEUR" --dbname="$BASE" \
    --no-owner --no-privileges | gzip > "$FICHIER"
else
  echo "Ni conteneur « $CONTENEUR » en cours d'exécution, ni pg_dump local." >&2
  exit 1
fi

# A dump is only worth keeping if it finished. pg_dump writes this marker as its
# last line, so a run killed half way -- or one that failed after the pipe had
# already created the file -- is caught here instead of at restore time, which
# is the worst possible moment to discover it.
if ! gunzip -c "$FICHIER" | tail -5 | grep -q "PostgreSQL database dump complete"; then
  echo "Sauvegarde incomplète, fichier supprimé : $FICHIER" >&2
  rm -f "$FICHIER"
  exit 1
fi

TAILLE="$(du -h "$FICHIER" | cut -f1)"
echo "Sauvegarde terminée : $FICHIER ($TAILLE)"

# Prune by count, newest kept. Listed with ls -1t and sliced after the retention
# count; the glob is anchored on the same prefix this script writes, so nothing
# else in the directory is ever a candidate.
SUPPRIMES=0
while IFS= read -r ancien; do
  [ -n "$ancien" ] || continue
  rm -f "$REPERTOIRE/$ancien"
  SUPPRIMES=$((SUPPRIMES + 1))
done < <(cd "$REPERTOIRE" && ls -1t trino-*.sql.gz 2>/dev/null | tail -n "+$((RETENTION + 1))")

if [ "$SUPPRIMES" -gt 0 ]; then
  echo "Rétention $RETENTION : $SUPPRIMES ancienne(s) sauvegarde(s) supprimée(s)."
fi
