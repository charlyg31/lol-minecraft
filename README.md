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
| `/lol leave` (alias `/lol quitter`) | Quitter la file |
| `/lol accept` / `/lol refuse` | Accepter ou refuser une proposition de partie |
| `/lol party invite/accept/decline/leave/kick/promote/disband/liste` | Gestion du groupe |

Rôles valides : `top` · `jungle` · `mid` · `adc` · `support`

**File classée — `/lolr`**

Mêmes règles de rôles que `/lol`, mais avec bans et pick en début de partie. Une sous-commande admin (`/lolr on` / `/lolr off`) active ou coupe les parties classées côté proxy.

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
  instance-prefix: "lolmc_game_" # préfixe des mondes d'instance (ne pas changer après coup)
  use-instances: false           # EXPÉRIMENTAL — voir la section dédiée plus bas
  max-instances: 1
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
  type: sqlite                    # sqlite | mysql | mongodb
  mysql: { host: localhost, port: 3306, database: lolmc, user: root, password: "" }

api:
  enabled: false                  # API web REST des statistiques (voir plus bas)
  port: 8080
```

Un bloc `heads:` liste les 20 champions avec un champ `PASTE_VALUE_HERE` à remplir avec la valeur base64 d'une tête custom (ex. minecraft-heads.com).

---

## Mode multi-instances (expérimental)

Par défaut (`use-instances: false`), une seule vraie partie tourne à la fois, directement dans le monde `world.name`. C'est le mode stable et le plus testé.

En activant `use-instances: true`, chaque match du matchmaking local copie le monde `world.template` dans un nouveau monde isolé (`lolmc_game_N`). Chaque instance a ses propres `GameManager`, `MinionManager`, `JungleManager`, `TurretManager`, `FogOfWarManager`, `RewardManager`, `AnnouncementManager`, `FeatManager`, `PassiveManager`, `BaseManager`.

**Limite connue :** le tableau de score de fin de partie (`MatchScoreboard` — kills, morts, CS, or) reste partagé entre toutes les instances. Avec `max-instances: 1` (la valeur par défaut), ce n'est pas un problème ; avec plusieurs instances simultanées, les scores de différentes parties se mélangeraient. Ce mode n'a jamais été testé sur un vrai serveur en conditions réelles.

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
- **Jungle** : camps classiques, buffs Bleu/Rouge, Héraut, Baron, dragons élémentaires avec âme au 4ᵉ et Dragon Ancien au 5ᵉ
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

`/lola <start|stop|set|position|lane|solo|give|level|gold|hp|resetcd|buff|spawn|wave|select|reload|debug|testgame|schem|road|jungle|shopnpc|mode|team|help>` (alias `/lolAdmin`)

Les sous-commandes de configuration de carte (`set`, `position`, `lane`, `jungle`, `schem`, `road`, `shopnpc`) s'appliquent au monde template, avant toute partie. Les sous-commandes de test (`solo`, `give`, `buff`, `wave`, `hp`, `gold`, `level`, `resetcd`) agissent sur la partie en cours.

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
| `GET /api/online` | Joueurs en ligne et statut de partie |
| `GET /api/status` | Statut général du serveur |

Nécessite un port ouvert côté hébergeur pour un accès depuis un site externe.

---

## Persistance

Trois backends au choix (`database.type` dans `config.yml`) : `sqlite` (par défaut, local), `mysql`, ou `mongodb`.

---

## Permissions

Déclarées dans `plugin.yml` du serveur de jeu :

- `lolmc.admin` (défaut : op) — commandes `/lola`, avec les sous-permissions `lolmc.admin.config` et `lolmc.admin.test`
- `lolmc.player` (défaut : true) — commandes joueur, avec une sous-permission par commande (`lolmc.player.shop`, `.team`, `.party`, `.queue`, `.recall`, `.ping`, `.pick`, `.runes`, `.spell`, `.lock`, `.roles`, `.lobby`)
- `lolmc.champion.use` (défaut : true) — jouer tous les champions, avec une sous-permission par champion pour restreindre individuellement
- `lolmc.skin` (défaut : false) — accès à tous les skins, avec une permission par skin attribuable individuellement (32 skins déclarés)

Côté proxy : `lolmc.play` (file et groupes via `/lol`/`/lolr`), `lolmc.bungee`, `lolmc.admin` (active/désactive le classé).

**BungeeCord n'a pas de vrai système `default: true` déclaratif comme Bukkit** : une permission jamais explicitement accordée (par ce plugin ou par un plugin de permissions comme LuckPerms) renvoie toujours faux, y compris pour un joueur non-op. Sans rien de plus, `/lol` et `/lolr` répondraient donc « vous n'avez pas la permission » à tout le monde. `DefaultPermissionsListener` (dans `bungee-plugin`) accorde `lolmc.play` à la connexion — mais seulement si aucun plugin de permissions n'a déjà pris une décision explicite pour ce joueur, pour ne jamais écraser une vraie restriction posée ailleurs.

---

## Configurer la carte

1. Créer et construire le monde `lolmc_template` (le nom exact vient de `world.template`)
2. Poser les structures, spawns, routes de sbires et camps de jungle avec les sous-commandes `/lola` correspondantes
3. Tester avec `/lola testgame` ou `/lola solo`
4. En mode instances, ce monde sera copié pour chaque nouvelle partie ; en mode par défaut, c'est le monde `world.name` qui est utilisé directement

---

## Stack technique

| Composant | Version |
|-----------|---------|
| Paper API | 26.1.2 |
| Java | 25 |
| HikariCP | 7.1.0 |
| SQLite JDBC | 3.53.2.1 |
| MySQL Connector/J | 9.7.0 |
| MongoDB Driver (sync) | 5.9.0 |
| Multiverse-Core | 4.3.12 (soft-depend) |

Build avec Maven (`mvn package`), un `pom.xml` par projet. Le JAR final embarque ses dépendances (maven-shade-plugin).

---

## État du projet

Ce dépôt est en développement actif. Le mode partie unique est le chemin le plus testé. Le mode multi-instances est fonctionnel mais expérimental (voir la limite documentée plus haut). Aucun de ces deux modes n'a été validé par une vraie session de jeu prolongée ; les retours et rapports de bugs sont les bienvenus via les issues.
