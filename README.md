# LolMC — League of Legends dans Minecraft

Plugin Paper qui recrée un MOBA inspiré de **League of Legends** dans Minecraft, sans mod client. 20 champions jouables, boutique, jungle, tourelles, brouillard de guerre, système de runes, API web de statistiques.

> **Stack :** Paper 26.1.2 · Java 25 · BungeeCord (optionnel)

---

## Deux projets Maven distincts

```
bungee-plugin/   → LolMC-Bungee.jar  (proxy : file d'attente, groupes, retour serveur d'origine)
.  (racine)      → LolMC.jar         (serveur de jeu : la partie complète)
```

Ce sont deux `pom.xml` séparés, à compiler indépendamment (pas de module Maven parent). Sans BungeeCord, `LolMC.jar` fonctionne seul en serveur autonome ; les commandes joueur passent alors par `/roles`, `/queue`, `/party` plutôt que par le proxy.

---

## LolMC-Bungee.jar — Plugin proxy (optionnel)

### Installation

Placer dans `/plugins/` du **proxy BungeeCord uniquement**.

### Configuration (`config.yml`)

```yaml
game-server: "lolmc-01"     # nom exact du serveur de jeu dans la config BungeeCord
lobby-server: "lobby"       # serveur lobby (optionnel)
fallback-server: "survie"   # serveur de repli si l'origine du joueur est inconnue
players-per-game: 10        # joueurs nécessaires pour lancer une partie normale
max-party-size: 5           # taille maximale d'un groupe
```

### Commandes

**File normale — `/lol`**

| Commande | Description |
|----------|--------------|
| `/lol <rôle1> <rôle2> ...` | Rejoindre la file avec les rôles souhaités (min. 2, ou 1 seul en groupe de 5) |
| `/lol all` | Accepter tous les rôles |
| `/lol leave` · `/lol stop` (alias `/lol quitter`) | Quitter la file (arrêter la recherche) |
| `/lol accept` / `/lol refuse` | Accepter ou refuser une proposition de partie |
| `/lol party invite/accept/decline/leave/kick/promote/disband/liste` | Gestion du groupe |
| `/lol start` | Chef de groupe : lancer la recherche quand tous les membres ont choisi leurs rôles |

**En groupe** : chaque membre choisit ses rôles avec `/lol <rôles>` (statut « prêt ») ; c'est le **chef** qui lance la recherche (`/lol start`, ou en choisissant ses propres rôles) une fois que tout le monde est prêt et que les postes sont compatibles. Le groupe reste toujours dans la même équipe. Si un membre quitte ou est exclu pendant la recherche, elle est annulée pour tout le groupe.

Rôles valides : `top` · `jungle` · `mid` · `adc` · `support`

**Test solo — `/lols [rôle]`** (par défaut `mid`, permission `lolmc.admin`) : simule une « partie trouvée » en solo pour tester l'enchaînement file → sélection de champion → partie, sans attendre d'autres joueurs.

**File classée — `/lolr`**

Mêmes règles de rôles que `/lol`, mais avec bans et pick en début de partie. La file classée est séparée de la file normale : un joueur de `/lol` n'est jamais placé dans une partie classée. `/lolr stop` quitte la file classée, `/lolr start` lance la recherche classée du groupe (chef). Une sous-commande admin (`/lolr on` / `/lolr off`) active ou coupe les parties classées côté proxy.

### Fonctionnalités

- File d'attente et groupes fonctionnant à travers plusieurs serveurs du réseau
- Le premier joueur qui invite dans un groupe en devient le chef
- Rôles souhaités sauvegardés par joueur dans `roles_data.yml`
- Retour automatique au serveur (et à la position) d'origine à la fin de la partie

---

## LolMC.jar — Plugin de jeu

### Installation

Placer dans `/plugins/` du serveur de jeu.

Dépendance optionnelle : **Multiverse-Core** (chargement de mondes ; fonctionne aussi sans, via `WorldCreator` natif Paper).

### Configuration (`config.yml`) — extraits principaux

```yaml
world:
  template: "lolmc_template"     # monde de référence, configuré une fois via /lola
  use-instances: false           # EXPÉRIMENTAL — voir la section dédiée plus bas
  max-instances: 4               # parties simultanées max (0 = illimité)
  name: "lolmc_template"         # monde de jeu réellement utilisé quand use-instances=false
  lobby: "lobby"                 # monde lobby optionnel

bridge:
  enabled: false                 # true si utilisé derrière le proxy BungeeCord
  game-server: "lolmc-01"
  lobby-server: "lobby"

scale:
  lol-units-per-block: 65        # conversion unités LoL → blocs Minecraft (portées, etc.)

shop:
  base-only: true                # achat uniquement à la fontaine (false = achat partout)
  base-radius: 15.0

combat:
  aa-lock-on: true                # un clic verrouille la cible, l'AA s'enchaîne à portée

fog:
  enabled: true
  vision-range: 30

minimap:
  center-x: 0
  center-z: 0
  scale: CLOSE                    # CLOSEST=128b CLOSE=256 NORMAL=512 FAR=1024 FARTHEST=2048

database:
  type: sqlite                    # sqlite | mysql
  mysql: { host: localhost, port: 3306, database: lolmc, user: root, password: "", ssl: false }

api:
  enabled: false                  # API web REST des statistiques (voir plus bas)
  bind: "127.0.0.1"               # "0.0.0.0" pour écouter sur toutes les interfaces
  port: 8080
  key: ""                         # clé exigée dans l'en-tête X-Api-Key (vide = pas d'auth)
  cors-origin: "*"
```

Un bloc `heads:` liste les 20 champions avec un champ `PASTE_VALUE_HERE` à remplir avec la valeur base64 d'une tête custom (ex. minecraft-heads.com).

---

## Mode multi-instances (expérimental)

Par défaut (`use-instances: false`), une seule vraie partie tourne à la fois, directement dans le monde `world.name`. C'est le mode stable et le plus testé.

En activant `use-instances: true`, chaque match copie le monde `world.template` dans un nouveau monde isolé (`lolmc_game_1`, `lolmc_game_2`…, préfixe fixe). `world.max-instances` limite le nombre de parties simultanées (une création au-delà est refusée).

**Ce qui est propre à chaque instance :** `GameManager`, `MinionManager`, `JungleManager`, `TurretManager`, `FogOfWarManager`, `RewardManager`, `AnnouncementManager`, `FeatManager`, `PassiveManager`, `BaseManager`, ainsi que la sélection de champion (chrono, bans, choix), le vote d'abandon, le tab et les stats de fin de partie (écran de score et sauvegarde en base limités aux joueurs de la partie).

**Monde template :** les instances sont copiées depuis `plugins/LolMC/template/` si ce dossier contient un monde (`level.dat` ou `region/`). Sinon, le plugin retombe sur le dossier du monde `world.template` à la racine du serveur (ancien comportement). `/lola template save` enregistre le monde **où tu te trouves** dans `plugins/LolMC/template/` (le monde est sauvegardé puis copié), le définit comme `world.template` et rattache `map.yml` (tours, nexus, spawns), `roads.yml` (chemins) et `jungle.yml` (camps) à ce monde (refusé dans un monde d'instance ou pendant une partie) ; `/lola template` affiche la source utilisée. On peut aussi y déposer à la main un dossier de monde.

**Plantes :** chaque instance a ses propres plantes (positions copiées du template) ; leur chunk est maintenu chargé par un ticket de chunk du plugin tant que la partie dure, pour qu'elles poussent et repoussent même sans joueur à proximité.

**Équipes :** `TeamManager` garde une table joueur → équipe unique, mais chaque lecture est limitée au monde du joueur : `areAllies`/`areEnemies` sont faux entre deux mondes, l'auto-équilibrage ne compte que les joueurs du même monde, et les listes d'équipiers (pings, buffs, chat d'équipe, wards…) filtrent par monde. Les deux parties ne se mélangent donc pas.

**Commandes de test `/lola` :** `start`, `stop`, `solo`, `testgame`, `spawn`, `wave` et `mode` agissent sur la partie de l'admin : son instance s'il en est dans une, sinon le monde template. Les plantes (miel, cône, voyant) sont aussi recréées dans chaque instance.

**BungeeTabListPlus :** si BTLP (avec son bridge Bukkit) est installé, LolMC enregistre les variables `lolmc_team`, `lolmc_champion`, `lolmc_kills`, `lolmc_deaths`, `lolmc_assists`, `lolmc_kda`, `lolmc_cs`, `lolmc_gold` et `lolmc_game` (à utiliser par ex. avec `${player lolmc_kda}`). Les valeurs sont propres à chaque joueur, donc à chaque instance. Les classes de l'API BTLP présentes dans `src/main/java/codecrafter47` sont de simples stubs de compilation, exclus du jar.

**Nettoyage automatique :** un monde d'instance sans aucun joueur est supprimé (après 60 s pour une partie en cours, immédiatement pour un monde sans partie). Les mondes `lolmc_game_*` laissés par un crash sont supprimés au démarrage. Le monde template n'est jamais touché. Ce mode n'a jamais été testé sur un vrai serveur en conditions réelles.

---

## Gameplay

### 20 champions

| Rôle | Champions |
|------|-----------|
| Top | Garen, Darius, Malphite, Nasus |
| Jungle | Warwick, Amumu, Master Yi, Lee Sin |
| Mid | Annie, Veigar, Zed, Yasuo |
| Support | Morgana, Leona, Blitzcrank, Janna |
| ADC | Ashe, Sivir, Jinx, Miss Fortune |

Chaque champion a ses 4 sorts (Q/W/E/R) plus passif, avec scaling AP/AD, prévisualisation directionnelle des sorts (visible du seul lanceur), et indicateurs de recharge dans la hotbar.

### Systèmes de jeu

- **Dégâts** : résistances, pénétration plate et en %, boucliers (physiques et magiques séparés), réduction de soins (Grievous Wounds), vrais dégâts, critiques, vol de vie et omnivamp
- **Contrôles de foule** : étourdissement, immobilisation, silence, ralentissement, projection aérienne, avec ténacité et résistance au ralentissement séparées
- **Sbires** : vagues toutes les 30s, mêlée/casters/canon selon le temps de jeu, super-sbires après destruction d'un inhibiteur
- **Jungle** : camps classiques, buffs Bleu/Rouge, Héraut (invoqué, puis monté par un allié qui dirige sa charge d'un clic droit), Baron (3 formes, sbires renforcés plus grands), dragons élémentaires avec âme au 4ᵉ et Dragon Ancien au 5ᵉ
- **Tourelles et structures** : priorité d'aggro fidèle, plaques de tourelle, or au dernier coup et à l'équipe
- **Économie** : or passif, or de sbires progressif, primes de série de kills, assistances
- **Runes** : les 5 voies complètes (Précision, Domination, Sorcellerie, Résolution, Inspiration), keystones et runes mineures
- **Objets** : catalogue complet avec passifs actifs et statiques, élixirs à partir du niveau 9
- **Sorts d'invocateur** : Flash, Ignite, Heal, Barrier, Exhaust, Téléport, Smite, Cleanse, Ghost
- **Boutique** : achat/vente, hotbar à deux pages (sorts + objets), fiole rechargeable
- **Brouillard de guerre et vision** : buissons, wards (furtive, de contrôle, lointaine), révélation au combat

### Commandes joueur

| Commande | Description |
|----------|--------------|
| `/roles` (alias `/lobby`, `/play`) | Menu de préparation (rôles, file) |
| `/queue` | Rejoindre ou quitter la file locale |
| `/party` | Gérer son groupe |
| `/champion` | Choisir ou lister les champions |
| `/pick` · `/spell` · `/lock` | Sélection de champion et de sorts |
| `/runes` | Configurer sa page de runes |
| `/shop` | Ouvrir la boutique |
| `/team` | Choisir son équipe / chat d'équipe |
| `/recall` | Retour à la base |
| `/ping` | Ping d'équipe (danger, omw, missing, assist, enemy) |
| `/l runes\|ping\|ff\|stats` | Raccourcis regroupés |

### Commandes admin — `/lola`

`/lola <start|stop|instance|set|position|center|lane|solo|give|level|gold|hp|resetcd|buff|spawn|wave|select|reload|debug|testgame|schem|road|jungle|baronpit|shopnpc|mode|team|permcheck|help>` (alias `/lolAdmin`)

Les sous-commandes de configuration de carte (`set`, `position`, `center`, `lane`, `jungle`, `baronpit`, `schem`, `road`, `shopnpc`, `mode`) s'appliquent au monde template, avant toute partie. `/lola template [save]` gère le monde template (voir plus bas). `/lola instance` liste les instances actives ; `/lola instance <n>` téléporte l'admin dans l'instance n, `/lola instance template` dans le monde template. Les sous-commandes de test (`solo`, `give`, `buff`, `wave`, `hp`, `gold`, `level`, `resetcd`) agissent sur la partie en cours.

**Fosse du Baron** : les formes Territoriale (petit mur devant la fosse) et Omnisciente (long tunnel sur le côté) modifient le terrain, qui dépend de la carte. Pour chaque forme : sélectionner la fosse avec `/lola schem pos1` et `pos2`, faire `/lola baronpit <territorial|omniscient> start`, construire la variante, puis `/lola baronpit <forme> save`. Seuls les blocs modifiés sont gardés (`jungle.yml`) et le terrain normal est remis. En partie, la variante est posée à l'apparition du Baron et retirée en fin de partie. `preview` pose une variante, `clear` la supprime, `/lola baronpit reset` remet le terrain normal.

---

## API web des statistiques (optionnelle)

Activable via `api.enabled: true` dans `config.yml`. Expose un serveur HTTP local en lecture seule :

| Route | Description |
|-------|--------------|
| `GET /api/player/{uuid}` | Fiche d'un joueur |
| `GET /api/player/name/{pseudo}` | Fiche d'un joueur par pseudo |
| `GET /api/player-champions/{uuid}` | Statistiques par champion |
| `GET /api/match/history/{uuid}` | Historique de parties |
| `GET /api/match/detail/{id}` | Détail d'une partie |
| `GET /api/leaderboard` | Classement |
| `GET /api/champions` | Liste des champions |
| `GET /api/online` | Joueurs en ligne, statut de partie et numéro d'instance |
| `GET /api/status` | Statut général du serveur, avec le détail de chaque instance (`instances`) |

Par défaut l'API n'écoute que sur `127.0.0.1`. Pour un accès depuis un site externe : `api.bind: "0.0.0.0"`, un port ouvert côté hébergeur, et de préférence une clé `api.key` (envoyée dans l'en-tête `X-Api-Key`).

---

## Persistance

Deux backends au choix (`database.type` dans `config.yml`) : `sqlite` (par défaut, local) ou `mysql`. Les colonnes ajoutées par les mises à jour sont créées automatiquement dans une base existante.

---

## Permissions

Les permissions sont déclarées dans `plugin.yml` du serveur de jeu et fonctionnent avec **LuckPerms** (ou tout autre plugin de permissions). Sans plugin de permissions, les valeurs par défaut s'appliquent : tout le monde joue, seuls les opérateurs administrent.

### Serveur de jeu (Paper)

| Permission | Défaut | Rôle |
|------------|--------|------|
| `lolmc.admin` | op | Toutes les commandes `/lola` (donne `lolmc.admin.config` et `lolmc.admin.test`) |
| `lolmc.admin.config` | op | Configurer la carte : `set`, `position`, `center`, `lane`, `schem`, `road`, `jungle`, `baronpit`, `shopnpc`, `mode` |
| `lolmc.admin.test` | op | Commandes de test : `solo`, `give`, `level`, `gold`, `team`, `testgame`, `debug`, `spawn`, `buff`, `resetcd`, `hp`, `wave` |
| `lolmc.player` | true | Toutes les commandes joueur (donne les 12 permissions ci-dessous) |
| `lolmc.player.shop` | true | `/shop` |
| `lolmc.player.team` | true | `/team` |
| `lolmc.player.party` | true | `/party` |
| `lolmc.player.queue` | true | `/queue` |
| `lolmc.player.recall` | true | `/recall` |
| `lolmc.player.ping` | true | `/ping` |
| `lolmc.player.pick` | true | `/pick` et `/champion` |
| `lolmc.player.runes` | true | `/runes` |
| `lolmc.player.spell` | true | `/spell` |
| `lolmc.player.lock` | true | `/lock` |
| `lolmc.player.roles` | true | `/roles` (alias `/lobby`, `/play`) |
| `lolmc.player.lobby` | true | Menu de préparation |
| `lolmc.champion.use` | true | Jouer tous les champions (donne les 20 permissions suivantes) |
| `lolmc.champion.<id>` | true | Jouer un champion précis (voir les identifiants ci-dessous) |
| `lolmc.skin` | false | Tous les skins (donne les 32 permissions de skin) |
| `lolmc.skin.<champion>.<skin>` | false | Un skin précis, par exemple `lolmc.skin.jinx.arcane` |

Les commandes `/lola` utilisent la permission fine de leur catégorie : un modérateur peut avoir `lolmc.admin.test` sans pouvoir modifier la carte. Les sous-commandes `start`, `stop`, `select`, `reload`, `permcheck` et `help` demandent `lolmc.admin`. Les opérateurs passent toujours.

**Identifiants de champion** (`lolmc.champion.<id>`) : `garen`, `darius`, `malphite`, `nasus`, `warwick`, `amumu`, `masteryi`, `leesin`, `annie`, `veigar`, `zed`, `yasuo`, `morgana`, `leona`, `blitzcrank`, `janna`, `ashe`, `sivir`, `jinx`, `missfortune`.

La liste des skins est dans `plugin.yml` ; `/lola permcheck` vérifie que chaque skin du code a bien sa permission déclarée.

### Proxy (BungeeCord)

| Permission | Rôle |
|------------|------|
| `lolmc.play` | Utiliser `/lol` et `/lolr` (file et groupes) |
| `lolmc.admin` | Activer ou couper les parties classées (`/lolr on` / `/lolr off`) |

BungeeCord n'a pas de système `default: true` comme Bukkit : une permission jamais accordée renvoie toujours faux. `DefaultPermissionsListener` accorde donc `lolmc.play` à la connexion, mais seulement si aucun plugin de permissions n'a déjà pris une décision pour ce joueur, pour ne jamais écraser une restriction posée ailleurs.

### Exemples LuckPerms

LuckPerms s'installe sur chaque serveur Paper **et** sur le proxy (version Bungee, commandes `/lpb`). Pour partager les groupes entre les deux, utilise la même base de données de stockage.

```
# Proxy : autoriser la file à tout le monde (explicite, recommandé avec LuckPerms)
/lpb group default permission set lolmc.play true

# Proxy : réserver les parties classées aux admins
/lpb group admin permission set lolmc.admin true

# Jeu : administrateur complet
/lp group admin permission set lolmc.admin true

# Jeu : modérateur qui teste sans toucher à la carte
/lp group moderateur permission set lolmc.admin.test true

# Jeu : tous les skins pour les VIP, un seul skin pour un joueur
/lp group vip permission set lolmc.skin true
/lp user Pseudo permission set lolmc.skin.jinx.arcane true

# Jeu : interdire un champion à un groupe (ex. champion du mois ou en test)
/lp group default permission set lolmc.champion.zed false
```

---

## Configurer la carte

1. Créer et construire le monde `lolmc_template` (le nom exact vient de `world.template`)
2. Poser les structures, spawns, routes de sbires et camps de jungle avec les sous-commandes `/lola` correspondantes
3. Tester avec `/lola testgame` ou `/lola solo`
4. En mode instances, le dossier de ce monde est copié pour chaque nouvelle partie (Multiverse n'est pas obligatoire) ; en mode par défaut, c'est le monde `world.name` qui est utilisé directement

En mode instances, sauvegarde et **décharge** `lolmc_template` avant de lancer des parties (`/mv unload lolmc_template` avec Multiverse), pour que la copie soit complète. Ne configure jamais la carte dans une copie `lolmc_game_N` : elle est supprimée à la fin de la partie.

---

## Stack technique

| Composant | Version |
|-----------|---------|
| Paper API | 26.2 (build 111, compatible api-version 26.1) |
| Java | 25 |
| SQLite JDBC | 3.53.2.1 |
| MySQL Connector/J | 26.7.0 |
| Multiverse-Core | 4.3.12 (soft-depend) |

Build avec Maven (`mvn package`), un `pom.xml` par projet. Le JAR final embarque ses dépendances (maven-shade-plugin).

---

## État du projet

Ce dépôt est en développement actif. Le mode partie unique est le chemin le plus testé. Le mode multi-instances est expérimental (voir la section dédiée plus haut) : l'isolation entre parties est implémentée mais n'a pas encore été validée par une partie à deux instances simultanées. Aucun de ces deux modes n'a été validé par une vraie session de jeu prolongée ; les retours et rapports de bugs sont les bienvenus via les issues.
