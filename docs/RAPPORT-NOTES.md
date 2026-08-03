# Notes pour le rapport de stage

Matière première, pas le rapport. Écrit en français parce que les paragraphes
d'ici partent directement dans le mémoire et dans la soutenance — c'est la
seule exception à la règle « docs en anglais » du `CLAUDE.md`.

À compléter à la fin de chaque phase, tant que c'est frais.

---

## 1. Écarts assumés par rapport au cahier des charges

### 1.1 Séparation `Train` / `Course`

Le cahier des charges (§4.2) place un attribut `Statut` sur l'entité `Train`,
avec des valeurs comme *en circulation*, *retardé*, *terminus atteint*. Ce sont
des états d'un trajet daté, pas d'un matériel roulant : la même rame assure
Tunis→Sousse le matin et le retour le soir. Deux trajets, deux statuts, un seul
train.

Le modèle a donc été scindé :
- `Train` — matériel roulant : numéro, type, capacité, vitesse maximale.
- `Course` — une exécution datée d'un trajet, porteuse du statut et du retard.

Sans cette séparation : impossible d'historiser, impossible de calculer une
ponctualité par trajet, et une rame ne peut assurer qu'un seul service par jour.

### 1.2 Entité `Desserte` ajoutée

Le §4.6 définit `Retard = Heure réelle − Heure théorique`, mais aucune entité du
cahier des charges ne définit d'où vient l'heure théorique. `Desserte` comble ce
manque : le schéma d'arrêt d'une ligne, avec pour chaque gare son ordre, son
point kilométrique et son décalage horaire depuis le départ.

C'est l'ossature de tout le moteur de retards.

### 1.3 Les trois heures du §4.5

Le cahier des charges distingue *heure prévue*, *heure estimée* et *heure
réelle*. Elles sont modélisées comme trois colonnes distinctes sur
`passage_gare`, jamais confondues :

| Champ | Sens | Évolue ? |
|---|---|---|
| Heure prévue | l'horaire publié, le contrat | jamais |
| Heure estimée | meilleure prédiction pour un arrêt non franchi | à chaque ping |
| Heure réelle | observé, nul tant que le train n'est pas passé | une seule fois |

Point à défendre : les clients lisent l'heure estimée, ils ne la recalculent
jamais en ajoutant le retard à l'heure prévue. Trois interfaces qui font ce
calcul chacune de leur côté, ce sont trois occasions de diverger.

### 1.4 Résorption du retard (`marge_min`)

Un horaire réel contient de la marge. Propager un retard de 11 minutes
inchangé jusqu'au terminus est faux. Chaque segment de desserte porte une
`marge_min` qui absorbe une partie du retard, si bien qu'un train à 11 minutes
à Sousse arrive à Sfax avec 6 minutes.

---

## 2. Décisions d'architecture à raconter

Le détail est dans `docs/architecture/decisions.md`. Les trois qui valent d'être
défendues à l'oral :

**Le simulateur est hors du système.** C'est la décision centrale. Le producteur
de positions est un processus séparé qui s'authentifie par clé et poste sur
`POST /api/v1/ingest/positions`. Trino consomme un flux GPS externe ; il ne
génère pas ses propres données. Remplacer le simulateur par un équipement AVL
réel est un changement de configuration, pas une réécriture. L'alternative —
générer les positions dans la couche métier — aurait été plus rapide à écrire et
architecturalement sans issue.

**SSE plutôt que WebSocket.** Le trafic est unidirectionnel serveur → client.
SSE apporte la reconnexion automatique et traverse les proxys sans
sous-protocole. WebSocket aurait ajouté une poignée de main et un protocole de
battement de cœur pour rien.

**Ce qui n'a pas été ajouté.** Ni Redis, ni PostGIS, ni microservices, ni bus de
messages. Chacun a été envisagé et écarté par écrit, avec le point de bascule qui
justifierait de l'ajouter. « Nous n'avons pas ajouté d'infrastructure que nous ne
pouvions pas justifier » est une position d'ingénieur plus solide qu'un conteneur
Redis inutilisé dans le `docker-compose`.

---

## 3. Journal de débogage

### Phase 0 — fondations et référentiel

Six défauts trouvés et corrigés, dont quatre par une passe de revue automatisée.

1. **Flyway ne démarrait pas.** `flyway-database-postgresql` manquait au
   `pom.xml`. Flyway 10, livré avec Spring Boot 3.4.1, a sorti le support des
   dialectes du `flyway-core` ; sans l'artefact supplémentaire, l'application ne
   démarrait pas du tout (`Unsupported Database: PostgreSQL 16.14`).

2. **Le gestionnaire d'exceptions avalait les codes de statut corrects.** Le
   `@ExceptionHandler(Exception.class)` attrapo-tout interceptait des exceptions
   du framework qui portaient déjà le bon statut — route inconnue, paramètre de
   type invalide, verbe HTTP non supporté, violation de contrainte d'unicité — et
   les transformait toutes en 500, sans journalisation. Corrigé par des
   gestionnaires spécifiques par type.

3. **Fuseau horaire dans l'enveloppe d'erreur.** `horodatage` utilisait le fuseau
   par défaut de la JVM au lieu d'UTC.

4. **Pagination non bornée dans le contrôleur.** `PageRequest.of(page, taille)`
   était construit dans le contrôleur : de la logique métier au mauvais endroit,
   et sans borne — `?taille=999999` ramenait la table entière, une page négative
   levait une exception muée en 500. Déplacé en couche service avec bornage.

5. **N+1 sur `GET /lignes/{id}/desserte`.** Pas de `join fetch` sur `gare` : une
   requête supplémentaire par arrêt.

6. **Code mort supprimé.** Deux DTO d'écriture jamais référencés.

*À noter dans le rapport : quatre de ces six défauts ont été trouvés par une
passe de revue outillée plutôt qu'à l'exécution. C'est un argument méthodologique
qui vaut d'être fait explicitement.*

### Phase 1 — (à compléter)

---

## 4. Ce qui n'a pas été construit, et pourquoi

| Demandé | Livré | Raison |
|---|---|---|
| Disponibilité 99,9 % | Sondes de santé, dégradation maîtrisée, écart documenté | Non vérifiable sur une démo locale |
| Canaux SMS / e-mail / push | Interface d'adaptateur + canal in-app | Temps ; quatre intégrations à moitié fonctionnelles valent moins qu'un canal qui marche et une conception claire |
| Export PDF | CSV + XLSX | Temps |

Chaque ligne est une décision de périmètre, pas un oubli. C'est la différence
que le jury regardera.
