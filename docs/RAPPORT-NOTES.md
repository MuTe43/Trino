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

### Phase 3 — moteur de retards, temps réel et diffusion

La phase où le système cesse d'enregistrer pour se mettre à prédire. À chaque
ping : résoudre les gares franchies, horodater les heures réelles, calculer et
propager le retard, faire tourner la machine à états, publier un delta sur les
canaux concernés.

**Deux unités qu'il ne faut jamais diviser l'une par l'autre.** `avancement_km`
et `pk_km` sont du chaînage ; la `vitesseKmh` portée par un ping est une vitesse
sol, parce que c'est ce que remonte un équipement AVL. Diviser un écart de
chaînage par une vitesse sol fausse la prédiction d'un facteur allant jusqu'à
1,4 — sans erreur, sans plantage, et avec une heure d'arrivée parfaitement
plausible. `CalculateurEta` dérive donc sa propre vitesse du chaînage, sur une
fenêtre de six positions. La méthode s'appelle `vitesseChainageKmh` et non
`vitesse` : le nom est là pour qu'une session ultérieure ne puisse pas y
substituer distraitement la valeur du ping. Vérifié à l'exécution — 05:37:10 par
le chaînage, là où les 200 km/h annoncés par le ping auraient donné ~05:31.

**L'horloge du moteur est celle du flux.** Le simulateur porte une horloge
accélérée : à x20, un ping est horodaté à des heures de l'heure murale. Comparer
`derniere_position_at` à `now()` aurait déclaré silencieuse la journée entière.
`HorlogeCirculation` ancre donc le temps sur le dernier ping observé, puis le
fait avancer en secondes réelles entre deux pings ; à x1, c'est exactement
l'horloge système. La seconde moitié compte autant que la première : une horloge
figée sur le dernier ping ne peut jamais conclure que le flux se tait depuis 90
secondes.

**La transition que rien ne réveille.** Une course dont la rame ne se présente
jamais ne reçoit aucun ping — aucun événement ne déclenche le moteur. Sans le
second travail de `DetecteurSilence`, un départ de 14:00 s'affiche à l'heure sur
le tableau pendant que le quai est vide. C'est la panne la plus visible pour un
voyageur, et celle qu'un jury cherchera.

**Défauts trouvés et corrigés :**

1. **Les canaux de gare étaient quasi globaux.** « Les gares encore devant le
   train » était codé `arrivee_reelle == null`. Or une gare d'origine n'a pas
   d'heure d'arrivée théorique, donc jamais d'heure réelle : la condition restait
   vraie toute la course, et chaque course publiait sur sa gare d'origine du
   départ au terminus. Tunis Ville étant l'origine de la plupart des lignes,
   `gare:1` devenait un canal quasi global — exactement ce que l'invariant 5
   interdit. Remplacé par un test de chaînage (`pk_km >= avancement_km`).
   Vérifié : la gare d'origine reçoit désormais 0 événement pour un train situé
   52 km plus loin, contre un par ping auparavant.

2. **Les deltas SSE étaient publiés à l'intérieur de la transaction.** Un
   rollback postérieur laissait les abonnés avec un delta décrivant un état
   jamais enregistré — et un client qui réagit en rechargeant l'instantané REST
   lisait la ligne d'avant commit, en contradiction avec le delta qu'il venait
   d'appliquer. Publication déplacée en `afterCommit`.

3. **Un emitter en échec d'écriture était retiré mais jamais clôturé.** Avec un
   timeout à 0, plus rien ne lui écrivait, donc le conteneur ne découvrait jamais
   la connexion morte : requête et réponse Tomcat restaient épinglées pour la
   durée du processus. C'est précisément le cas de la connexion à moitié fermée
   que le retrait était censé traiter.

4. **Un seul thread pour tous les `@Scheduled`.** La taille par défaut du pool
   d'ordonnancement de Spring est de 1. Le battement de cœur SSE écrit
   séquentiellement vers tous les abonnés, et `DetecteurSilence` tient une
   transaction pendant son balayage : un seul consommateur SSE bloqué suffisait
   à arrêter silencieusement le mécanisme de dégradation sur lequel repose toute
   la décision 8, tout en gardant une connexion à la base ouverte.

5. **Un train à quai disparaissait de son propre tableau de départs.**
   `depart_estimee` était gelée dès l'horodatage de `arrivee_reelle`, alors que
   la règle de gel du modèle de domaine ne concerne que `arrivee_estimee`. Le
   tableau de départs filtrant sur `depart_estimee`, un train retenu à quai
   franchissait sa propre estimation périmée et sortait de l'affichage.

6. **`DetecteurSilence` ne se rattrapait pas.** Le retard propagé par le second
   travail n'était jamais défait quand une course redevenait `A_QUAI` : le
   tableau continuait d'annoncer un retard pour un train qui n'en avait plus.
   Ajouté au passage l'isolement par course, pour qu'une course fautive ne fasse
   pas échouer le balayage des 79 autres.

**Un défaut de spécification, pas de code.** La commande d'acceptation
`select count(*) from passage_gare where arrivee_estimee is null` attendait 0 et
renvoyait 80 — soit exactement une gare d'origine par course. Une origine n'a pas
d'heure d'arrivée théorique, et la contrainte `chk_passage_estimee_suit_theorique`
impose alors l'absence d'estimée. La requête contredisait le modèle de domaine ;
c'est la requête qui a été corrigée, pas le code. Écrire une commande
d'acceptation qu'aucune implémentation correcte ne peut satisfaire est un piège à
signaler plutôt qu'à contourner.

**Résultat mesuré :** 49 tests verts (18 avant la phase). Sur une journée
simulée à x20 — résorption vérifiée sur une course réelle à 17→15→13→11 minutes
avec `marge_min` à 2, au lieu de 19 minutes traînées jusqu'au terminus ; mélange
d'états atteint (`A_QUAI`, `EN_CIRCULATION`, `RETARDE`, `TERMINUS_ATTEINT`) ; 21
courses passées `RETARDE` sans le moindre ping ; 10 courses basculées en
`ARRET_EXCEPTIONNEL` environ 70 secondes après l'arrêt du flux.

### Phase 4 — portail voyageur, carte et tableau d'affichage

**Une direction visuelle décidée avant d'écrire un composant.** Le défaut par
défaut d'une interface générée est reconnaissable : carte grise, accent bleu,
ombre portée, coins arrondis uniformes, police Inter. La direction retenue
l'exclut explicitement — bleu SNCFT employé comme encre et non comme aplat,
IBM Plex Sans et sa coupe condensée, chiffres tabulaires partout où une heure
est comparée à une autre, et une liste de proscriptions aussi précise que la
liste des choix. Nommer les défauts à éviter s'est révélé plus efficace que
décrire la cible.

**Le tableau d'affichage n'est pas une page web.** Lu à distance dans un hall,
en 1920×1080, sans pointeur ni survol : typographie condensée, contraste élevé,
destination en colonne la plus large, heure théorique barrée au-dessus de
l'heure révisée dans la couleur du retard. C'est l'écran où la distinction
*prévue / estimée / réelle* du §4.5 devient visible pour le voyageur.

**Défauts trouvés et corrigés :**

1. **Les classes Tailwind construites par interpolation n'existaient pas.**
   C'est le défaut le plus instructif du projet. Tailwind 4 ne génère que les
   utilitaires dont le nom apparaît *littéralement* dans les sources : une
   classe assemblée à l'exécution (`` `text-${statut}` ``) n'est jamais émise.
   Neuf des dix couleurs de statut n'existaient donc pas, et toute la gamme
   s'affichait dans la couleur héritée. Le compilateur TypeScript, ESLint et la
   compilation de production passaient tous au vert : le code était correct, seul
   le CSS manquait. Corrigé par une table de correspondance littérale.

2. **La couleur d'un arrêt révisé était celle du pire retard de la course.** Une
   mise à jour de retard écrasait la classe de chaque arrêt avec celle calculée
   au niveau de la course, si bien qu'un arrêt aval ayant résorbé son retard
   affichait ses propres minutes dans la couleur du retard maximal. Symptôme
   discret, donnée fausse.

3. **La carte s'abonnait à toutes les lignes du référentiel**, pas aux lignes
   visibles — contraire à l'invariant de segmentation des canaux.

4. **Deux fuites sur les déconnexions SSE.** Une déconnexion client remontait en
   ERROR avec une trace, puis échouait une seconde fois en tentant d'écrire une
   réponse JSON dans un flux `text/event-stream`. Sous Windows elle prend la
   forme d'une `IOException` nue, non des exceptions attendues. Et les échecs du
   battement de cœur, émis par le planificateur, ne passaient pas du tout par le
   chemin de l'émetteur : il a fallu leur donner un `ErrorHandler` propre.

**Une limite d'architecture découverte, pas subie.** Un navigateur n'autorise
qu'environ six connexions simultanées par origine en HTTP/1.1, partagées entre
le flux SSE et les appels REST. Avec un canal par ligne et cinq lignes, la carte
au zoom réseau complet sature l'origine et la requête REST suivante se met en
file — l'application paraît figée, au zoom par défaut et non dans un cas
limite. La réponse retenue est le multiplexage : une seule connexion portant une
liste d'abonnements explicite, l'identité du canal voyageant dans la charge
utile. La segmentation est préservée — le client ne reçoit toujours que ce qu'il
a demandé — sans exiger HTTP/2, donc sans TLS, pour une démonstration locale.

---

### Phase 5 — tableaux de bord, exports et multiplexage SSE

**Une limite du protocole, mesurée plutôt que supposée.** Un navigateur
n'autorise qu'environ six connexions simultanées par origine en HTTP/1.1, et ce
budget est partagé entre les flux SSE et tous les appels REST vers la même
origine. Avec un canal par ligne, la carte au zoom réseau complet en occupait
cinq. La mesure a corrigé l'estimation : la saturation n'arrive pas à cinq mais à
six — cinq flux laissent exactement une socket libre, et c'est la requête REST
suivante, celle d'un détail de train ouvert par-dessus la carte, qui n'est jamais
servie. L'application paraît figée, au zoom par défaut, sans aucune erreur.

La réponse est le multiplexage : une seule connexion portant une liste
d'abonnements explicite, l'identité du canal voyageant dans la charge utile.
Douze canaux ne coûtent alors plus rien. La segmentation est préservée — le
client ne reçoit que ce qu'il a nommé — sans exiger HTTP/2, donc sans TLS, pour
une démonstration locale.

**Un défaut de routage silencieux dans ce même multiplexage.** Une course publie
vers sa ligne *et* vers chaque gare qu'elle n'a pas encore dépassée. La
dé-duplication qui évite d'envoyer deux fois la même trame à un client
supprimait aussi les étiquettes des autres canaux : la trame ne nommait que le
premier. Le client route sur ce champ, donc une page affichant à la fois la carte
(`ligne:1`) et un tableau de gare (`gare:7`) recevait la donnée sur la carte et
jamais sur le tableau. Aucune erreur, aucune reconnexion : un tableau qui cesse
simplement de bouger. La trame porte désormais la liste complète des canaux
concernés, toujours envoyée une seule fois.

**Des jours entiers ou parfaitement à l'heure, ou entièrement en retard.** Le
générateur d'historique dérivait sa graine de la clé naturelle de la course et la
passait directement à `new Random(…)`. La première valeur tirée par ce générateur
est quasi linéaire en la graine — et cette première valeur était précisément la
porte de perturbation. Les jours synthétisés sortaient donc bimodaux. Corrigé en
faisant passer la graine par un finaliseur murmur3. Dans la même passe, un appel
`poisson(…)` laissé dans une condition de boucle était retiré à chaque itération
au lieu d'une fois.

**Une régression d'export invisible en test.** Écrire le fichier via un
`StreamingResponseBody` bascule la requête en mode asynchrone. `FiltreJwt` est un
`OncePerRequestFilter` et ignore les répartitions asynchrones ; le
`AuthorizationFilter` de Spring Security, lui, ne les ignore pas. La règle de
rôle refusait donc la seconde répartition, après que l'en-tête `text/csv` a déjà
été validé : le fichier se téléchargeait correctement et le serveur écrivait
trois traces d'erreur par export. Écrit désormais de façon synchrone.

**Une requête de contrôle qui confirme le bug qu'elle devait détecter.**
`extract(hour from timestamptz)` dépend du `TimeZone` de session, que le pilote
JDBC règle depuis la JVM. Une requête de vérification écrite sans `at time zone`
donne exactement le même résultat qu'un dépôt qui l'a oublié aussi. Le contrôle
n'en est pas un : il faut fixer le fuseau explicitement des deux côtés.

**Un test instable de notre fait.** `@EnableScheduling` porte sur la classe
`@SpringBootConfiguration` dont une tranche `@WebMvcTest` s'amorce : le battement
de cœur SSE était donc réellement planifié à l'intérieur de la tranche, et son
premier tir tombait avant ou après l'abonnement du test selon la charge de la
machine. Vert isolé, rouge dans la suite complète. La correction ne consiste pas
à réduire la fenêtre de course — il n'en reste aucune à réduire — mais à rendre
la mesure insensible : on compare désormais une connexion à quatre canaux et une
à un seul, un battement parasite atteignant les deux et s'annulant.

**Une ponctualité qui aurait commencé chaque matin à 100 %.** Le taux ne compte
que les arrêts réellement atteints. Un arrêt encore devant son train porte un
retard de zéro : le compter reviendrait à noter à l'heure tout le reste de la
journée non encore parcourue. Le dénominateur est publié à part et vaut zéro en
début de service — l'interface affiche alors « — », jamais « 0 % ».

### Phase 6 — incidents et console d'exploitation

**Une course annulée pouvait ressusciter.** L'action d'agent n'avait pas de garde
sur les états terminaux, donc `ANNULE → ARRET_EXCEPTIONNEL` était acceptée — et
`ARRET_EXCEPTIONNEL` est délibérément non terminal, si bien que le ping suivant
re-dérivait la course vers `EN_CIRCULATION`. Garder uniquement le moteur
protégeait le flux et laissait la console comme seconde porte d'entrée. Une même
règle métier doit être gardée à toutes ses entrées, pas à la plus visible.

**Un abonnement à quarante-cinq canaux, explicite mais global de fait.** La carte
de supervision s'abonnait aux cinq lignes et aux quarante gares. La liste était
nommée, donc conforme à la lettre de la règle de segmentation, et contraire à son
intention : un client recevait tout le réseau. La cause était en amont — un
incident rattaché à une seule gare ne publiait que sur `gare:{id}`. Le diffuseur
propage désormais un tel incident vers les lignes desservant cette gare, et la
carte tient cinq canaux. Corriger le symptôme aurait laissé la cause en place.

**« Résolu instantanément », l'exact inverse de la vérité.** Le rapport
d'incidents lisait `rs.wasNull()` après avoir déjà lu deux autres colonnes. Cette
méthode rapporte sur la *dernière* colonne lue, pas sur celle qu'on croit
interroger : tous les incidents non résolus ressortaient à 0,0 h. Un chiffre
faux, plausible, et flatteur.

**Un délai de résolution négatif de cinq heures.** L'horodatage de résolution
était pris sur l'horloge de circulation — celle du flux de positions, décalée de
plusieurs heures sous simulateur accéléré — tandis que l'heure de survenue venait
de la console, donc de l'horloge de l'opérateur. Deux horloges pour deux bornes
du même intervalle. Les deux viennent maintenant de l'opérateur.

**Une erreur de validation sans destination d'affichage.** Une contrainte ajoutée
au DTO après l'écriture du formulaire renvoyait une clé qu'aucun champ ne
connaissait : l'utilisateur lisait « le formulaire contient des erreurs » avec
tous les champs intacts. Le contrat d'erreur n'est complet que si chaque clé
retournée correspond à un champ affiché.

**Un paramètre dont PostgreSQL ne peut pas déduire le type.** Un filtre optionnel
écrit `:param is null or colonne = :param` produit `could not determine data type
of parameter $7` — un 500 sur la vue par défaut de la console. Comparé à une
colonne, le type est inférable ; seul, il ne l'est pas. Remplacé par des
spécifications JPA, ce que la phase 7 a repris d'emblée.

### Phase 7 — console d'administration

**Une adresse en casse mixte ne pouvait jamais se connecter.** La création
enregistrait l'adresse en minuscules, l'authentification la cherchait telle que
saisie. Un compte créé sous `Prenom.Nom@SNCFT.tn` était donc inatteignable avec
l'adresse même que l'administrateur venait de transmettre — et chaque tentative
alimentait le journal avec un identifiant utilisateur nul, c'est-à-dire une piste
d'audit affirmant qu'aucun compte ne correspondait à cette adresse alors que le
compte existait. Invisible à la compilation comme au build, parce que les quatre
comptes de démonstration sont déjà en minuscules. Une seule définition de
« la même adresse » est désormais partagée par les deux chemins.

**Une coordonnée vide devenait (0, 0).** Le formulaire de gare convertissait un
champ vide en zéro, et la latitude n'a qu'une contrainte de non-nullité, sans
bornes : zéro était donc *accepté*. La carte publique ajuste son cadrage initial
sur l'ensemble des gares, si bien qu'une seule station placée dans le golfe de
Guinée dézoome la carte voyageur sur l'océan. Le formulaire envoie maintenant
`null`, refusé avec l'erreur portée par les bons champs. Une valeur par défaut
saisie sans intention détruisait la vue principale du produit.

**La même conversion réécrivait des colonnes nullables.** Les trente-neuf gares
du jeu de données portent un responsable nul ; un simple renommage y écrivait une
chaîne vide. L'écran des trains, écrit dans la même phase, faisait déjà
correctement la distinction — l'incohérence était interne à une seule phase.

**La documentation promettait moins que le code.** Trois documents affirmaient
qu'un jeton d'accès déjà émis restait valable jusqu'à trente minutes après une
désactivation. Le filtre relit le compte à chaque requête : l'effet est immédiat.
Le comportement était juste et la documentation fausse — le sens dans lequel
l'erreur se propage ensuite à tout ce qui s'appuie dessus.

**Un mot de passe généré par le serveur, et nommé honnêtement.** L'administrateur
ne choisit jamais le mot de passe d'autrui : le serveur en génère un, le renvoie
une seule fois, et n'en conserve que l'empreinte. Le champ s'appelle
*initial* et non *temporaire*, parce que *temporaire* promettrait un changement
forcé à la première connexion — un drapeau, un point d'entrée, une redirection et
une migration touchant les comptes de démonstration. Nommer *temporaire* une
chose qui ne l'est pas serait une promesse que le système ne tient pas, et le
prochain lecteur du champ y croirait.

### Phase 8 — notifications et alertes

**Une prise en charge trop large transforme une panne en silence.** Une recherche
au référentiel renvoyant `null` produisait une `NullPointerException` qui
remontait dans un `catch (RuntimeException)` de l'écouteur, journalisée en
avertissement — et la notification n'était tout simplement jamais émise. Rien
n'échouait visiblement ; il n'y avait rien à déboguer. Ce sont les tests qui
l'ont révélé, en affirmant la présence de lignes plutôt que l'absence d'erreurs.
Une recherche infructueuse coûte désormais sa localisation à la notification, pas
la notification.

**Une annotation qui casse une suite entière.** Le limiteur de débit, déclaré
comme composant simple, implémente un `WebMvcConfigurer` : toute tranche de test
MVC charge ce type, mais pas un composant ordinaire, et le contexte échouait donc
dans des tests sans aucun rapport. Un défaut dont l'emplacement n'indique rien sur
la cause.

**Un cookie pour toute identité.** Exiger un compte pour suivre un train
livrerait la fonctionnalité à personne : un voyageur qui vérifie si son train a du
retard n'a pas de compte et n'en créera pas pour un trajet. Le serveur émet donc
un jeton aléatoire au premier abonnement et le renvoie dans un cookie `HttpOnly`.
C'est un jeton porteur : quiconque le détient lit les notifications de ce voyageur.
Il n'apparaît donc jamais dans un corps de réponse, une URL, ni un journal, et le
canal SSE correspondant est dérivé côté serveur au lieu d'être nommé par le
client — la liste d'abonnements de `/stream` étant fournie par le client, un
paramètre pour ce canal permettrait de lire les notifications d'autrui.

**Un détail de configuration dont le symptôme est l'absence de symptôme.** Le
cookie est en `SameSite=Lax`. Le portail et l'API sont deux origines distinctes
mais le même *site* — cet attribut ignore le port. `None` exigerait `Secure`,
donc en HTTP local le navigateur abandonnerait le cookie et la cloche de
notifications ne se lierait jamais, sans le moindre message d'erreur.

**Une dé-duplication comptée en minutes simulées.** Une course qui franchit un
seuil de retard émet à chaque ping tant qu'elle reste au-dessus. La garde autorise
une notification par abonnement, évènement et course toutes les trente minutes
d'horloge de circulation — pas d'horloge murale. Au facteur 20 de la
démonstration, trente minutes simulées valent quatre-vingt-dix secondes réelles ;
une fenêtre en temps réel laisserait passer vingt fois trop de messages, c'est-à-dire
des centaines pendant une soutenance. À vitesse réelle, les deux horloges
coïncident.

**Une trace même quand le canal est mort.** La ligne de notification est écrite
*avant* la tentative d'envoi et mise à jour après. Un canal indisponible laisse
donc une ligne en échec portant sa cause — la différence entre « le serveur SMTP
était injoignable à 08:14 » et une notification qui n'a silencieusement jamais
existé. Vérifié en pointant SMTP vers un port fermé : l'ingestion continue de
répondre en quelques dizaines de millisecondes, et la ligne en échec porte le
message du serveur de messagerie.

### Phase 9 — couverture, durcissement et démonstration

**La revendication la plus faible du projet, transformée en mesure.** Le cahier
des charges demande *plusieurs centaines de trains simultanément* ; le jeu de
données matérialise 80 courses par jour. L'architecture devait tenir — 300 trains
à un tick de 5 s font 60 messages par seconde — mais c'était un raisonnement, pas
un chiffre. `scripts/charge.sh` ajoute 320 trains dont les départs tiennent dans
une fenêtre de 20 minutes, plus courte que la plus brève desserte du réseau (35
min), si bien que tous circulent en même temps. Les chiffres relevés sont au §5.2.

Le profil de charge n'est **pas** une migration Flyway. Une migration est
immuable et s'applique partout : une flotte qui existe pour être mesurée puis
supprimée ne doit jamais atteindre une base de démonstration.

**Une horloge qui ne recule jamais, et la panne que cela provoque.** Redémarrer
le simulateur sans redémarrer l'API fait passer *toutes* les courses en
`ARRET_EXCEPTIONNEL`. `HorlogeCirculation` est monotone — c'est le bon choix, une
horloge de terrain ne revient pas en arrière — mais le simulateur accéléré
repart de `heure-debut`, donc chaque ping arrive horodaté plusieurs heures avant
ce que l'API a déjà observé, et `DetecteurSilence` conclut au silence. Mesuré :
211 courses basculées en une minute. Ce n'est pas un défaut du produit ; c'est un
piège de démonstration, et l'étape 8 (« tuer le simulateur ») mène droit dessus.
`scripts/demo.sh` refuse maintenant de s'exécuter si l'API répond.

**Un paramètre nul dont PostgreSQL ne peut pas deviner le type.** Les quatre
critères de recherche ajoutés au §4.9 ont d'abord été écrits sur le patron
existant, `(:param is null or colonne = :param)`. Pour les deux bornes horaires —
des `timestamptz` — PostgreSQL répond `could not determine data type of parameter
$23` et **toute** requête `/recherche` retourne 500, y compris celles qui ne
passent aucune borne. La phase 6 avait rencontré exactement la même erreur sur
`$7` dans les filtres du référentiel. Deux fois la même faute, invisible au
compilateur et à tout test sur simulacre. Corrigé en liant toujours une fenêtre
réelle, par défaut les bornes de la journée de service — que le prédicat
`dateService` a déjà restreintes. `CriteresRechercheTest` exécute désormais chaque
critère contre une vraie base.

**Le piège documenté dans lequel je suis tombé.** `LimiteurDebit` porte un
commentaire expliquant qu'un `WebMvcConfigurer` ayant besoin d'un collaborateur
fait échouer le contexte de chaque tranche `@WebMvcTest`. En ajoutant les deux
`FilterRegistrationBean` à `ConfigurationWeb`, j'ai fait exactement cela : 15
tests en erreur, aucun lié à la configuration web. Les déclarations sont
maintenant dans `ConfigurationSecurite`, à côté des filtres qu'elles corrigent.

**Deux implémentations de géométrie, épinglées sans être couplées.**
`GeometrieLigne` (api) et `GeometrieCourse` (simulateur) sont dupliquées
volontairement : le contrat HTTP est le seul couplage autorisé (invariant 3). Rien
n'empêchait pourtant les deux copies de diverger, et la panne se présente comme
des trains dessinés à côté de la voie, chaque chiffre cohérent avec lui-même et
aucune trace nulle part. Un fichier partagé, `backend/parite-geometrie.json`,
fixe 15 chaînages de la ligne 4 réelle ; chaque module vérifie sa propre
implémentation contre les mêmes nombres. Aucun module ne dépend de l'autre. Les
deux concordent à 1e-7, soit environ un centimètre.

**Une adresse d'API qui ne peut pas être la même des deux côtés.** L'accueil est
un Server Component : il résout la course et la gare de ses trois liens *dans le
conteneur web*. `NEXT_PUBLIC_API_BASE_URL` y vaut `http://localhost:8081` —
l'adresse du **navigateur**, et depuis le conteneur c'est le conteneur lui-même,
où rien n'écoute. Les deux côtés ont réellement besoin d'adresses différentes.
`TRINO_API_BASE_URL_SERVEUR` porte celle du serveur et retombe sur l'autre, ce
qui laisse `npm run dev` inchangé. La panne est silencieuse : la page se rend
entièrement et perd seulement ses trois liens, parce que `lib/serveur.ts` dégrade
au lieu de lever. Trouvée en vérifiant l'image conteneurisée plutôt que le seul
serveur de développement — la version `dev` fonctionnait parfaitement.

**Une légende à cinq couleurs, pas à six.** L'addendum demande six pastilles
distinctes. La rampe fixée en phase 4 en compte quatre pour le retard plus
l'annulation : `R5`/`R10` partagent un ton, `R15`/`R30` aussi. Rendre six lignes
sur cinq couleurs produit deux paires identiques en pastille *et* en libellé,
qui affirment une distinction que la carte ne trace pas. La légende répond à la
question « que veut dire cette couleur » : il y a donc exactement une ligne par
couleur, et l'intervalle nomme toute la bande couverte. Inventer deux couleurs
aurait signifié une seconde table de couleurs — précisément ce que l'invariant 8
interdit.

---

## 4. Ce qui n'a pas été construit, et pourquoi

| Demandé | Livré | Raison |
|---|---|---|
| Disponibilité 99,9 % | Sondes de santé, dégradation maîtrisée, écart documenté | Non vérifiable sur une démo locale |
| Canal SMS | Adaptateur en forme de passerelle, qui journalise | Aucun compte opérateur n'existe ; le point d'intégration est marqué. `IN_APP` et `EMAIL` fonctionnent réellement — courriel remis dans une vraie boîte de réception |
| Notifications push navigateur | Non implémenté | Nécessite des clés VAPID et un service worker ; hors périmètre du délai |
| Export PDF | CSV + XLSX | Temps |
| Changement de mot de passe imposé à la première connexion | Mot de passe généré, nommé *initial* et non *temporaire* | Le construire toucherait les comptes de démonstration ; le nom retenu ne promet rien de faux |
| Rattachement d'un abonnement anonyme à un compte | Les deux identités ne fusionnent jamais | Demanderait une migration à la connexion et une règle de conflit ; énoncé plutôt que dissimulé |

Chaque ligne est une décision de périmètre, pas un oubli. C'est la différence
que le jury regardera.


---

## 5. Couverture du cahier des charges

Le tableau ci-dessous est le livrable final de la phase 9. Il liste chaque
exigence du cahier des charges avec son état : **livré**, **partiel** ou **hors
périmètre**, et pour les deux derniers la raison. Rien n'y est une surprise —
chaque ligne « partiel » ou « hors périmètre » est développée ailleurs dans ce
document.

Les numéros de section renvoient au cahier des charges d'origine.

### 5.1 Fonctionnel

| § | Exigence | État | Précision |
|---|---|---|---|
| 4.1 | Référentiel gares, lignes, trains, dessertes | **Livré** | CRUD complet, console d'administration, 39 gares et 5 lignes réelles |
| 4.2 | Plan de transport (horaires, courses datées) | **Livré** | `horaire` matérialisé en 80 courses et 683 passages par jour |
| 4.3 | Réception des positions GPS | **Livré** | `POST /ingest/positions`, authentifié par clé, simulateur remplaçable par du matériel AVL |
| 4.4 | Calcul du retard et propagation aval | **Livré** | `retard = réelle − théorique`, jamais d'heure théorique codée en dur |
| 4.5 | Trois heures : prévue, estimée, réelle | **Livré** | Visibles sur `ListeArrets` — heure prévue barrée, estimée à côté, réelle une fois franchie |
| 4.6 | Carte temps réel du réseau | **Livré** | MapLibre, interpolation sur la polyligne, une connexion SSE multiplexée |
| 4.7 | Tableau d'affichage en gare | **Livré** | `/affichage/{gareId}`, conçu pour un écran, resynchronisation REST toutes les 60 s |
| 4.8 | Sept évènements de notification | **Partiel** | 4 sur 7 : `RETARD_SEUIL`, `COURSE_ANNULEE`, `INCIDENT_DECLARE`, `INCIDENT_RESOLU`. `DEPART` et `ARRIVEE` non construits (temps). `CHANGEMENT_QUAI` **hors périmètre** : `quai` est dérivé de façon déterministe à la génération et ne change jamais, donc aucun changement de quai n'existe à notifier — l'émettre demanderait de rendre `quai` mutable et de donner à un agent l'action de le changer, c'est-à-dire une fonctionnalité, pas une logique d'émission |
| 4.9 | Sept critères de recherche | **Livré** | Texte libre (numéro, nom, ligne, gare), ligne, gare, date, **région**, **destination**, **fenêtre horaire** — les quatre derniers ajoutés en phase 9 |
| 4.10 | Gestion des incidents | **Livré** | Déclaration, modification, résolution, diffusion vers la carte publique, rôles séparés |
| 4.11 | Six rapports exportables | **Partiel** | 5 sur 6 : `ponctualite`, `incidents`, `retards-par-ligne`, `retards-par-gare`, `disponibilite-trains`, en CSV et XLSX. **Export PDF non construit** : demande une dépendance et un gabarit, pour une valeur inférieure à celle des cinq rapports ci-dessus, que CSV et XLSX couvrent déjà pour l'usage analytique |
| 4.12 | Tableaux de bord d'exploitation | **Livré** | KPI du jour, ponctualité, heatmap, histogramme des retards, rapport incidents |
| 4.13 | Comptes, rôles et journal de connexions | **Livré** | Quatre rôles, double garde URL + `@PreAuthorize` (invariant 9), journal consultable |
| — | Types de trains, causes de retard, rôles configurables | **Hors périmètre, assumé** | Ce sont des énumérations Java, et c'est la bonne conception : ce sont des vocabulaires de domaine fermés, pas des réglages. Un nouveau type de train implique de nouvelles règles métier, une nouvelle cause de retard une nouvelle correspondance depuis les types d'incident, un nouveau rôle de nouvelles règles d'autorisation — rien de tout cela ne se fournit par une ligne de base. En faire des données échangerait une garantie à la compilation contre une panne à l'exécution |

### 5.2 Non fonctionnel (§6)

| Exigence | État | Précision |
|---|---|---|
| Plusieurs centaines de trains simultanément | **Livré et mesuré** | 321 courses simultanées, chiffres au §6 |
| Sauvegarde automatique | **Livré** | `scripts/sauvegarde.sh` : `pg_dump` daté, compressé, vérifié complet, rétention par nombre, ligne `cron` dans le `README` |
| Démarrage en une commande | **Livré** | `docker compose up` — base, Mailpit, API, simulateur, web, ordonnés par sondes de santé. Vérifié après `down -v` |
| Disponibilité 24h/24 à 99,9 % | **Hors périmètre, assumé** | Non mesurable sur une livraison locale. Ce qui existe : couche applicative sans état, sondes `health`/`readiness`, dégradation maîtrisée quand le flux s'arrête, et désormais une sauvegarde. L'écart est énoncé, pas masqué (décision 8) |
| HTTPS | **Hors périmètre, assumé** | Terminer TLS en démonstration locale suppose un certificat auto-signé, et la politique de cookies a été réglée pour du HTTP en clair sur `localhost` (décision 12) : basculer casserait silencieusement la cloche de notifications. La couche applicative est déjà agnostique — en production TLS se termine sur un proxy inverse, qui pose `X-Forwarded-Proto`, et les cookies passent en `Secure`. Documenté dans le `README` |

### 5.3 Trois constats énoncés plutôt que corrigés

**Un abonné LIGNE ou GARE reçoit une notification par course en retard.** Mesuré :
124 messages sur 62 courses en 85 secondes au facteur 20. C'est la clé de
dé-duplication spécifiée, appliquée fidèlement — la borne est par course, pas par
abonné — et dans une gare chargée c'est aussi une image honnête de la journée. Ce
n'est pas un risque de démonstration : le portail ne propose que « Suivre ce
train », les abonnements LIGNE et GARE existent à l'API et n'ont aucun bouton. La
réponse à l'échelle réelle est un résumé périodique, pas une borne plus serrée.

**Le chemin d'abonnement par compte est inatteignable depuis le navigateur.**
`EventSource` ne peut pas envoyer d'en-tête `Authorization`, donc le portail
n'authentifie jamais son flux et tout abonnement pris depuis l'interface est
anonyme. La branche `utilisateur_id` est construite et testée, mais aucun humain
ne l'exerce. Le correctif est un jeton dans un cookie que le point de terminaison
de flux lit — exactement ce que fait déjà le chemin anonyme. C'est une note de
conception, pas un défaut.

**Le changement de mot de passe à la première connexion n'est pas imposé.** Il
attraperait les quatre comptes de démonstration et risquerait de casser le chemin
de connexion le jour de la soutenance. Consigné comme travail futur.

---

## 6. Le test de charge

C'était la revendication la plus faible du projet : le cahier des charges (§6)
demande *plusieurs centaines de trains simultanément*, le jeu de données en
matérialise 80 par jour, et l'argument « 300 trains à un tick de 5 s font 60
messages par seconde, la diffusion coûte plus que l'ingestion » restait un
raisonnement. Voici les chiffres.

### 6.1 Protocole

`scripts/charge.sh` ajoute 320 trains, un créneau horaire chacun, départs
répartis sur 20 minutes à partir de 05:00. Vingt minutes parce que la plus brève
desserte du réseau dure 35 minutes : toute la flotte est encore en course quand
la dernière part. Le script n'écrit que des lignes `train` et `horaire` —
`GenerateurCourses` les transforme en courses et en passages exactement comme
pour l'horaire du jeu de données, parce qu'un profil de charge qui fabriquerait
ses propres courses mesurerait un chemin de code que le produit n'emprunte pas.

Simulateur à l'accélération 5, tick de 5 secondes. L'accélération ne change pas
le débit — le tick est en temps réel — seulement la durée du plateau.

Fenêtre de mesure : 300 secondes, centrée sur le pic, machine par ailleurs au
repos. Un premier relevé a été jeté parce que des compilations Maven tournaient
en parallèle et gonflaient le p95 d'un facteur trois ; un chiffre contaminé par
son propre banc n'est pas un chiffre.

### 6.2 Résultats

| Mesure | Valeur |
|---|---|
| Courses simultanées | **320** en continu, 321 au pic |
| Positions par tick | 320, aucune rejetée |
| Débit d'ingestion | 64 positions/seconde |
| **Latence d'ingestion** (lot de 320, vue producteur) | **p50 223 ms · p95 427 ms · p99 573 ms** |
| Marge sur le tick de 5 s | **facteur ~22** au p50, ~12 au p95 |
| **Étalement de la diffusion SSE** (première à dernière trame d'un tick) | **p50 18 ms · p95 42 ms · max 89 ms** |
| Trames reçues sur 300 s, un client, cinq canaux | 17 681 |
| `tableau-bord/kpi` | p50 10 ms · p95 72 ms |
| `tableau-bord/retards-par-ligne` | p50 9 ms · p95 12 ms |
| `tableau-bord/heatmap` (7 jours) | p50 15 ms · p95 16 ms |
| `tableau-bord/distribution-retards` (7 jours) | p50 11 ms · p95 14 ms |
| Mémoire JVM utilisée | 183 à 240 Mio |
| RSS du conteneur API | 585 Mio |

La latence d'ingestion est mesurée côté producteur, dans le simulateur
(`JournalLatence`), et non par une sonde serveur. C'est le point de vue qui
compte : c'est ce que du matériel AVL réel subirait, réseau et sérialisation
compris. Elle englobe aussi la diffusion SSE, que `PublicationApresCommit`
exécute sur le fil de la requête.

### 6.3 Ce que les chiffres disent

**L'architecture tient, avec de la marge.** Un lot de 320 positions coûte 223 ms
au p50 contre un budget de 5 000 ms — le facteur 22 est la réponse à la question
posée. Rapporté à l'unité, une position coûte environ 0,7 ms, délai de retard,
machine à états, écriture et diffusion compris.

**La diffusion n'était pas le goulot d'étranglement attendu.** L'argument
initial supposait que le fan-out dominerait ; l'étalement mesuré est de 18 ms au
p50 pour 320 trames vers un client abonné aux cinq lignes. Avec plusieurs
dizaines de clients ce poste croîtrait linéairement et redeviendrait le sujet —
mais à l'échelle demandée il ne l'est pas.

**Les requêtes de tableau de bord ne se dégradent pas sous charge.** Elles lisent
des lignes commitées, dont le nombre dépend de la journée et non du nombre de
trains en mouvement ; sous 320 courses simultanées elles restent toutes sous 20 ms
au p50. Le p95 de `kpi` à 72 ms est le seul point qui bouge, et il s'agit de la
contention sur le pool de connexions au moment d'un tick.

**Aucune position rejetée sur toute la fenêtre.** 0 rejet sur les 17 681 trames
observées et les lots correspondants.

Un point d'honnêteté : 320 trains sur un réseau dont le jeu de données compte
cinq lignes et 39 gares est une densité irréaliste. La mesure porte sur le coût
de traitement d'un ping et sur la diffusion, pas sur la plausibilité du plan de
transport.

