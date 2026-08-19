# Trino — suivi en temps réel des trains SNCFT

Suivi de la circulation ferroviaire : position des trains en direct, retards
calculés sur l'horaire théorique, tableaux d'affichage en gare, console
d'exploitation, notifications aux voyageurs.

Projet de stage. Livraison locale, démonstration hors ligne.

---

## Démarrage en une commande

```bash
docker compose up -d
```

Cinq services, ordonnés par sondes de santé : la base avant l'API, l'API avant le
simulateur et le web.

| Service | Adresse | Rôle |
|---|---|---|
| web | http://localhost:3000 | portail voyageur et consoles |
| api | http://localhost:8081 | API REST + flux SSE |
| db | localhost:5432 | PostgreSQL 16 |
| mailpit | http://localhost:8025 | boîte de réception des notifications |
| simulateur | — | producteur de positions, n'écoute rien |

L'API est publiée sur **8081** parce qu'un service sans rapport occupe 8080 sur
la machine de développement ; le conteneur écoute toujours sur 8080, donc rien
dans `application.yml` ne connaît cette substitution.

Comptez environ deux minutes au premier démarrage : Flyway applique les
migrations et `GenerateurCourses` matérialise la journée.

```bash
curl -s localhost:8081/actuator/health   # {"status":"UP"}
```

Pour tout arrêter, données comprises :

```bash
docker compose down -v
```

---

## Développement, sans conteneurs

```bash
docker compose up -d db mailpit                            # dépendances seules
cd backend && ./mvnw -q -pl api spring-boot:run            # API sur 8080
cd backend && ./mvnw -q -pl simulateur spring-boot:run     # simulateur
cd frontend && npm run dev                                 # web sur 3000
```

Les tests exigent une base de données : depuis la phase 6 ils échouent au lieu
de se désactiver quand PostgreSQL est injoignable, délibérément — une suite
verte ayant sauté treize tests est pire qu'une suite rouge.

```bash
cd backend && TRINO_DB_URL=jdbc:postgresql://localhost:5432/trino ./mvnw test
cd frontend && npm run build && npx tsc --noEmit && npx eslint --max-warnings=0 src
```

---

## Scripts

| Script | Ce qu'il fait |
|---|---|
| `scripts/demo.sh` | Remet la base dans l'état répété, régénère la journée, synthétise deux semaines d'historique, puis lance le simulateur. Tout identifiant est dérivé à l'exécution |
| `scripts/backfill.sh` | Synthétise des journées passées pour que les graphiques aient une forme |
| `scripts/charge.sh` | Ajoute 320 trains pour le test de charge. `--nettoyer` les retire |
| `scripts/mesures.mjs` | Mesure la diffusion SSE, les requêtes de tableau de bord et la mémoire sous charge |
| `scripts/sauvegarde.sh` | Sauvegarde `pg_dump` datée, compressée et vérifiée |

### Sauvegarde automatique

Une sauvegarde par nuit à 02:00, l'heure avant que `GenerateurCourses` ne
matérialise la journée suivante à 03:00 — un dump restauré est ainsi toujours une
journée entière plutôt que la moitié d'une.

```bash
0 2 * * * cd /chemin/vers/Trino && scripts/sauvegarde.sh >> /var/log/trino-sauvegarde.log 2>&1
```

Sept sauvegardes conservées par défaut (`--retention=30` pour un mois). Le script
refuse un dump incomplet et supprime le fichier plutôt que de le garder :
découvrir qu'une sauvegarde était tronquée au moment de la restaurer est le pire
moment possible.

Restauration :

```bash
gunzip -c sauvegardes/trino-2026-08-19-0200.sql.gz | docker exec -i trino-db psql -U trino -d trino
```

Le répertoire `sauvegardes/` est ignoré par git : un dump contient toutes les
lignes de la base, empreintes bcrypt et jetons d'abonné compris.

---

## TLS

Rien n'écoute en HTTPS ici, et c'est délibéré. Terminer TLS en démonstration
locale suppose un certificat auto-signé, et la politique de cookies a été réglée
pour du HTTP en clair sur `localhost` : le jeton d'abonné est posé en
`SameSite=Lax` sans `Secure`, et basculer casserait silencieusement la cloche de
notifications.

La couche applicative est déjà agnostique. En production, TLS se termine sur un
proxy inverse placé devant l'API et le web :

- le proxy pose `X-Forwarded-Proto: https`, que Boot lit déjà pour construire ses
  URL absolues (`server.forward-headers-strategy=framework`) ;
- les cookies `refreshToken` et `jeton_abonne` passent en `Secure` ;
- `TRINO_CORS_ORIGINES` reçoit les origines `https://` réelles.

Aucun code ne change ; ce sont trois valeurs de configuration.

---

## Documentation

| Besoin | Fichier |
|---|---|
| Où en est le projet | `docs/STATE.md` |
| Entités, champs, énumérations | `docs/architecture/domain-model.md` |
| Points de terminaison, DTO, erreurs | `docs/architecture/api-contract.md` |
| Pourquoi un choix a été fait | `docs/architecture/decisions.md` |
| Journal de débogage et couverture du cahier des charges | `docs/RAPPORT-NOTES.md` |
| Commandes pour exploiter et vérifier à la main | `docs/RUNBOOK.md` |

---

## Comptes de démonstration

`admin@`, `agent@`, `responsable@`, `voyageur@sncft.tn` — mot de passe commun,
documenté dans `docs/RUNBOOK.md`. Ce sont des données de démonstration, pas des
comptes réels.
