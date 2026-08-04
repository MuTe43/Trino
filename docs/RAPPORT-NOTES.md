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

### Note méthodologique — à faire une fois, pas à chaque défaut

Trois phases, une constante : la majorité des défauts n'étaient visibles ni à la
compilation, ni au typecheck, ni à la lecture du code. Ils ne sont apparus qu'en
exécutant réellement le système — script d'acceptation lancé pour de vrai,
connexion menée dans un navigateur, journée simulée jouée jusqu'au bout — ou lors
d'une passe de revue outillée confiée à un agent dédié disposant de son propre
contexte.

Deux exemples portent l'argument à eux seuls :
- Le cookie de rafraîchissement scopé `Path=/api/v1/auth` (phase 1) : chaque
  appel HTTP pris isolément était correct ; seul un vrai parcours de connexion
  dans un navigateur révélait que le middleware Next.js ne recevait jamais le
  cookie.
- Le taux de perturbation calculé par tick et non par minute simulée (phase 2) :
  la ponctualité dépendait silencieusement du facteur d'accélération. Le système
  donnait donc un résultat différent selon la vitesse à laquelle on l'observait,
  et le défaut était invisible à x1.

C'est la démarche de validation qui mérite d'être défendue, pas la liste des
correctifs. Le reste de cette section en est la matière brute.


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

### Phase 1 — auth & rôles

**Le jeton de rafraîchissement ne peut pas voyager en JSON côté client.**
Le frontend stocke l'access token en mémoire et reçoit le refresh token dans
un cookie `httpOnly` — c'est une exigence de la phase, pour qu'un script
injecté ne puisse pas l'exfiltrer. Mais un cookie `httpOnly` est par
définition illisible en JavaScript : l'intercepteur de rafraîchissement ne
peut donc pas le relire pour le renvoyer dans le corps `{refreshToken}` que
`POST /auth/refresh` attendait dans une première version. Corrigé en faisant
lire l'endpoint directement dans le cookie (`@CookieValue`), avec repli sur le
corps JSON pour un client non-navigateur. Sans ce correctif, le rafraîchissement
échouait silencieusement à chaque appel — l'utilisateur aurait été déconnecté
de force 30 minutes après chaque connexion.

**Séparation `securite` / `iam`.** `securite` (filtre JWT, config Spring
Security) ne dépend jamais des entités ou des repositories `iam` : il passe
par `UtilisateurService` et manipule des DTO. Ça garde la couche sécurité
testable sans base de données et évite un couplage circulaire si `iam`
évolue.

**Deuxième passe : six défauts trouvés par relecture, invisibles à l'exécution
manuelle.** Comme en phase 0, une passe de revue outillée (agent dédié) a
trouvé des défauts que le script d'acceptation seul ne pouvait pas voir
puisqu'il ne les exerçait pas :
- Les canaux SSE et `/actuator` tombaient dans le `authenticated()` par
  défaut faute de règle `permitAll` explicite — contraire à la règle de phase
  et à une régression sur le futur healthcheck du docker-compose (phase 7).
- `Keys.hmacShaKeyFor` choisit l'algorithme HMAC selon la longueur de la clé :
  le secret de dev de 59 octets donnait du HS384, pas HS256 comme demandé.
  Corrigé par un `signWith` explicite.
- `rafraichir` révoquait le jeton présenté et en émettait un nouveau — de la
  rotation, explicitement hors périmètre. Conséquence concrète : deux 401
  concurrents sur le frontend déclenchent deux rafraîchissements, le second
  invalidant le premier, et l'utilisateur se retrouve déconnecté de force.
  Corrigé en renvoyant le même jeton, sans le révoquer.
- Ni `login` ni `rafraichir` ne vérifiaient `actif` : un compte désactivé
  obtenait quand même un jeton valide (le contrôle n'existait qu'en aval,
  dans le filtre JWT). Corrigé.
- Le gestionnaire d'erreur du filtre de sécurité écrivait la réponse sans
  `setCharacterEncoding("UTF-8")` : les 401/403 émis par la chaîne de filtres
  affichaient des caractères mal encodés (« Acc?s refus? » au lieu de
  « Accès refusé »), contrairement à ceux du `@RestControllerAdvice`.
- `AuthController.refresh` choisissait lui-même la source du jeton (corps ou
  cookie) avant d'appeler le service — de la logique dans un contrôleur,
  contraire à l'invariant du `CLAUDE.md`. Déplacé dans `UtilisateurService`.

**`@PreAuthorize` sur les exceptions `AccessDeniedException`.** Une exception
levée par `@PreAuthorize` sur une méthode de service, appelée depuis un
contrôleur, est interceptée par la résolution d'exceptions de
`DispatcherServlet` (donc par `@RestControllerAdvice`), pas par la chaîne de
filtres Spring Security. Il a donc fallu un gestionnaire dédié dans
`ApiExceptionHandler` en plus du `AccessDeniedHandler` de
`ConfigurationSecurite`, qui ne couvre que les refus au niveau URL
(`authenticated()`).

**`@PreAuthorize` seul renvoyait 400 au lieu de 403.** Trouvé en testant
réellement le script d'acceptation (pas seulement à la compilation) : un
voyageur qui POSTait `/gares` avec un corps invalide recevait 400
`VALIDATION_ECHOUEE`, pas 403. Cause : `@Valid` sur le DTO s'exécute pendant
la résolution des arguments du contrôleur, avant même l'appel à la méthode de
service — donc avant que le proxy AOP de `@PreAuthorize` n'ait la moindre
chance de refuser quoi que ce soit. Corrigé en dupliquant la règle de rôle
dans `ConfigurationSecurite` (`authorizeHttpRequests`, au niveau URL, POST/PUT
/DELETE sur `/gares`, `/lignes`, `/trains` → `ADMINISTRATEUR`), qui s'exécute
dans la chaîne de filtres, avant la validation. Le `@PreAuthorize` sur les
services reste en place en défense en profondeur.

**Le cookie de rafraîchissement invisible pour le middleware du frontend.**
Trouvé en testant le vrai flux de connexion dans un navigateur (pas seulement
via `curl`). Le cookie `refreshToken` était scopé `Path=/api/v1/auth` — ce qui
a du sens vu du seul point de vue du backend, mais le middleware Next.js tourne
pour les requêtes vers `/admin` et `/exploitation` sur le port 3000, un chemin
totalement différent. Le nom d'hôte `localhost` est partagé entre les deux
origines (le matching de domaine des cookies ignore le port), mais le matching
de chemin est exact : un cookie scopé `/api/v1/auth` n'est jamais envoyé pour
une requête vers `/admin`. Résultat : le garde-fou du middleware redirigeait
systématiquement vers `/connexion`, même juste après une connexion réussie.
Corrigé en passant le `Path` du cookie à `/`. Sans un test réel dans un
navigateur, ce défaut aurait été invisible : aucun test `curl` ni aucune
compilation ne l'aurait révélé, puisque chaque appel HTTP pris isolément se
comportait correctement.

### Phase 2 — plan de transport, simulateur et ingestion

C'est la phase qui matérialise la décision d'architecture centrale : le
simulateur devient un processus séparé, sans base de données, qui ne parle à
Trino que par HTTP.

**Deux espaces de mesure distincts.** Le tracé polyligne d'une ligne est jusqu'à
40 % plus long que sa `distance_km` déclarée : une polyligne suit les courbes,
une distance commerciale non. `avancement_km` et `pk_km` sont donc exprimés en
*chaînage* — la mesure commerciale — et les arrêts sont ancrés aux sommets du
tracé. Confondre les deux espaces fausserait toute prédiction d'arrivée d'un
facteur allant jusqu'à 1,4, sans jamais lever la moindre erreur.

**`projeter()` : inverser une position GPS en chaînage.** Le contrat d'ingestion
transporte des coordonnées, parce que c'est ce qu'émet un équipement AVL réel —
jamais un point kilométrique. Il a donc fallu une opération qui projette une
position sur le tracé pour en déduire l'avancement. C'est la conséquence directe
et assumée d'avoir placé le producteur hors du système : on consomme la forme de
donnée du matériel, pas une forme de commodité.

**Défauts trouvés et corrigés :**

1. **`GenerateurCourses` s'exécutait hors transaction.** `@Transactional` portait
   sur une méthode appelée depuis la même classe : l'auto-invocation
   court-circuite le proxy Spring, donc les deux `saveAll` validaient séparément.
   Une panne entre les deux laissait des courses sans aucun arrêt, et
   définitivement — le contrôle d'idempotence les considérant ensuite comme déjà
   générées.

2. **Cache de géométrie périmé.** Modifier un tracé laissait le flux sur
   l'ancienne polyligne jusqu'au redémarrage. Corrigé par un événement
   `LigneModifiee` et une éviction.

3. **Une seule ligne sans tracé faisait échouer les 80 courses.**
   `coursesDuJour` renvoyait 500 pour l'ensemble ; désormais la course fautive
   est ignorée, symétriquement avec l'ingestion.

4. **Le bruit de vitesse du simulateur n'était pas centré** (0,88–1,04, moyenne
   0,96). Un déficit de 4 % à chaque tick rendait une vingtaine de courses
   structurellement en retard avant la moindre perturbation : le retard mesuré
   ne mesurait donc pas ce qu'on croyait.

5. **Le taux de perturbation était calculé par tick, non par minute simulée.**
   Voir la note méthodologique ci-dessus.

6. **Deux horaires qu'aucun train ne pouvait tenir.** L1 Sousse→Msaken exigeait
   120 km/h alors que le matériel affecté plafonne à 110 et 80 ; L3
   Manouba→Oued Ellil exigeait 84 sur une ligne à 80. Recalés sur le train le
   plus lent affecté à la ligne — pas sur la limite de la ligne, qui n'est pas la
   contrainte réelle. `CoherenceSeedTest` vérifie désormais cette règle.

**Résultat mesuré sur une journée simulée complète :** 73 courses terminées,
28,8 % avec au moins 5 minutes de retard (cible ~25 %), retards répartis sur
toutes les tranches, rapport vitesse observée / vitesse nominale de 0,9993.

---

## 4. Ce qui n'a pas été construit, et pourquoi

| Demandé | Livré | Raison |
|---|---|---|
| Disponibilité 99,9 % | Sondes de santé, dégradation maîtrisée, écart documenté | Non vérifiable sur une démo locale |
| Canaux SMS / e-mail / push | Interface d'adaptateur + canal in-app | Temps ; quatre intégrations à moitié fonctionnelles valent moins qu'un canal qui marche et une conception claire |
| Export PDF | CSV + XLSX | Temps |

Chaque ligne est une décision de périmètre, pas un oubli. C'est la différence
que le jury regardera.
