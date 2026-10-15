# LolMC — League of Legends dans Minecraft

Plugin Paper qui recrée la Faille de l'invocateur dans Minecraft, **sans mod client** : 20 champions jouables, sbires, jungle, tourelles, brouillard de guerre, boutique, runes, sorts d'invocateur, file d'attente avec groupes et API web de statistiques.

Les valeurs de jeu (stats, dégâts, délais, coûts, portées, vitesses de projectiles, règles de ciblage) sont alignées sur les données du jeu du **patch 26.20** : Data Dragon 16.20.1, fichiers de CommunityDragon et wiki officiel de League of Legends. Les portées et vitesses de projectiles viennent des missiles du jeu (CommunityDragon) ; 100 unités LoL = 1 bloc.

> **Stack :** Paper 26.3 · Java 25 · Maven · BungeeCord (optionnel)

---

## Sommaire

- [Démarrage rapide](#démarrage-rapide)
- [Architecture](#architecture)
- [Gameplay](#gameplay)
- [Configuration](#configuration)
- [Configurer la carte](#configurer-la-carte)
- [Mode multi-instances (expérimental)](#mode-multi-instances-expérimental)
- [Commandes](#commandes)
- [Permissions](#permissions)
- [API web des statistiques](#api-web-des-statistiques)
- [Compilation et release](#compilation-et-release)
- [Stack technique](#stack-technique)
- [État du projet](#état-du-projet)

---

## Démarrage rapide

1. Compiler (voir [Compilation](#compilation-et-release)) ou récupérer les JAR d'une release.
2. Déposer `LolMC.jar` dans `plugins/` d'un serveur **Paper 26.3** tournant sous **Java 25**.
3. Démarrer le serveur une fois pour générer `plugins/LolMC/config.yml`.
4. Construire la carte dans le monde `lolmc_template`, puis la configurer avec `/lola` (voir [Configurer la carte](#configurer-la-carte)).
5. Tester seul avec `/lola solo` ou `/lola testgame`.

Optionnel : `LolMC-Bungee.jar` sur le proxy BungeeCord pour une file d'attente réseau, et Multiverse-Core pour gérer les mondes.

---

## Architecture

```
.  (racine)      → LolMC.jar         serveur de jeu : la partie complète
bungee-plugin/   → LolMC-Bungee.jar  proxy : file d'attente, groupes, retour au serveur d'origine
```

Deux `pom.xml` indépendants (pas de module Maven parent), à compiler séparément. Sans BungeeCord, `LolMC.jar` fonctionne seul : les joueurs passent alors par `/roles`, `/queue` et `/party` au lieu du proxy (`bridge.enabled: false`).

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

Chaque champion a son passif et ses 4 sorts (Q/W/E/R) avec scaling AD/AP, une prévisualisation des sorts visible du seul lanceur et des recharges affichées dans la hotbar.

### Sbires

- Première vague à 0:30, puis toutes les 30 s (25 s dès 14:00, 20 s dès 30:00).
- Vague : 3 mêlées, un canon (toutes les 3 vagues, puis 2 dès 14:00, puis chaque vague dès 25:00), 3 casters ; super-sbire en queue de vague après un inhibiteur détruit (2 quand les 3 sont tombés).
- Stats améliorées toutes les 90 s selon les formules du wiki ; or et XP par type.
- Ciblage LoL : appel à l'aide quand un champion allié est attaqué, sinon le **sbire ennemi le plus proche**, et un champion seulement s'il n'y a aucun sbire. La cible est gardée tant qu'elle reste valide et à portée.
- Casters et canons tirent des projectiles, y compris sur les structures, depuis leur portée.
- Les sbires ne voient pas un champion caché dans un buisson, sauf s'ils sont dans le même buisson ou que leur équipe y a de la vision.

### Tourelles et structures

- 0,833 attaque/s, portée 750, projectile à 1200 unités/s imblocable, dégâts selon le niveau de la tourelle et la minute.
- Priorité : appel à l'aide (champion qui blesse un champion allié), puis canon/super-sbire, mêlée, caster, champion. La cible est gardée tant qu'elle est valide.
- Montée en puissance (+50 % par tir sur un champion, max +150 %), 30 % de pénétration d'armure.
- **Armure renforcée** : −80 % de tous les dégâts sans sbire attaquant à proximité, réactivée 3 s après leur départ.
- Plaques (patch 26.1), Rempart, Croissance cristalline, baisse d'armure des tourelles extérieures de 11:00 à 15:00.
- Vision vraie des tourelles (1100 unités) : les buissons à portée ne cachent rien à leur équipe.
- Régénération des inhibiteurs, du Nexus et des tourelles de base ; réapparition des tourelles de Nexus.
- Protection anti-backdoor : chaque structure n'est attaquable qu'après celle qui la précède.

### Jungle

Camps classiques, buffs Bleu et Rouge, Larves du Néant, Héraut (invoqué puis monté par un allié qui dirige sa charge), Baron Nashor (3 formes de fosse, Main du Baron qui renforce les sbires), dragons élémentaires avec âme au 4ᵉ et Dragon Ancien.

Les monstres dorment jusqu'au premier coup, puis tout le camp attaque le champion le plus proche dans sa laisse. Chaque changement de cible coûte de la patience (6 au total) ; à zéro, hors laisse ou sans coup reçu, le monstre rentre au camp en se soignant. Les petits loups et raptors meurent 10 s après leur grand monstre, et les Krugs se divisent en mini-krugs déjà en combat.

### Vision

- Brouillard de guerre : champions, sbires, tourelles et wards donnent la vision de leur équipe.
- Buissons : un champion dedans est invisible pour les ennemis, sauf si ceux-ci ont un champion ou une ward dans le même buisson.
- Attaquer ou lancer un sort **révèle 2 s**, même depuis un buisson, si un champion ou un sbire ennemi est à 10 blocs ou moins (les monstres de la jungle ne comptent pas).
- **Reliques** (une à la fois, à charges, recharge selon le niveau moyen des champions) : Balise camouflée (2 charges, 210 → 90 s, donnée au départ), Brouilleur oraculaire (2 charges, 160 → 100 s ; drone de 8 s qui révèle et neutralise les balises ennemies), Altération divinatoire (dès le niveau 9 ; 198 → 99 s, vision 500 puis 800 et autodestruction 3 s après avoir repéré un champion).
- Balises de contrôle (2 max).

### Autres systèmes

- **Dégâts** : armure/RM, pénétration plate et en %, boucliers, réduction de soins, vrais dégâts, critiques, vol de vie, omnivamp.
- **Contrôles** : étourdissement, immobilisation, silence, ralentissement, projection, avec ténacité.
- **Économie** : or passif, or des sbires, primes de série, assistances, plaques.
- **Runes** : les 5 voies complètes, keystones et runes mineures (Grimoire déchaîné via `/l grimoire`).
- **Objets** : catalogue complet avec passifs et actifs, élixirs dès le niveau 9 ; boutique à la fontaine, hotbar à deux pages.
- **Sorts d'invocateur** : Flash, Embrasement, Soin, Barrière, Fatigue, Téléportation, Châtiment, Purge, Fantôme.

---

## Configuration

### Serveur de jeu — `plugins/LolMC/config.yml` (extraits)

```yaml
bridge:
  enabled: false                 # true derrière le proxy BungeeCord
  game-server: "lolmc-01"
  lobby-server: "lobby"

world:
  template: "lolmc_template"     # monde de référence, configuré une fois via /lola
  use-instances: false           # EXPÉRIMENTAL (voir plus bas)
  max-instances: 4               # parties simultanées max (0 = illimité)
  name: "lolmc_template"         # monde de jeu utilisé quand use-instances=false
  lobby: "lobby"

bushes:
  blocks: [TALL_GRASS, LARGE_FERN, FERN, SWEET_BERRY_BUSH]   # blocs qui forment un buisson

shop:
  base-only: true                # achat uniquement à la fontaine
  base-radius: 15.0

combat:
  aa-lock-on: true               # un clic verrouille la cible, l'AA s'enchaîne à portée

turrets:
  attack-radius: 7.5             # 750 unités
  detection-height: 6
  beam-height: 6

fog:
  enabled: true
  vision-range: 13.5             # 1350 unités (vision d'un champion)

minimap:
  center-x: 0
  center-z: 0
  scale: CLOSE                   # CLOSEST=128 CLOSE=256 NORMAL=512 FAR=1024 FARTHEST=2048 blocs

database:
  type: sqlite                   # sqlite | mysql
  mysql: { host: localhost, port: 3306, database: lolmc, user: root, password: "", ssl: false }

api:
  enabled: false
  bind: "127.0.0.1"
  port: 8080
  key: ""                        # clé exigée dans l'en-tête X-Api-Key (vide = sans auth)
  cors-origin: "*"
```

### Équilibrage — `plugins/LolMC/champions.yml`

Facultatif. Les valeurs du jeu (délais, coûts par rang, dégâts, ratios) sont dans le code de chaque champion ; ce fichier ne sert qu'à les **surcharger** (une clé absente = valeur du code), rechargé par `/lola reload`. Il est livré vide (version 3) : au démarrage, une ancienne version qui dupliquait toutes les valeurs est sauvegardée en `champions.yml.v<ancienne version>.bak` puis remplacée.

Le bloc `heads:` liste les 20 champions avec un champ `PASTE_VALUE_HERE` à remplacer par la valeur base64 d'une tête personnalisée (par exemple depuis minecraft-heads.com).

### Proxy — `plugins/LolMC-Bungee/config.yml`

```yaml
game-server: "lolmc-01"     # nom exact du serveur de jeu dans la config BungeeCord
lobby-server: "lobby"
fallback-server: "survie"   # repli si le serveur d'origine du joueur est inconnu
players-per-game: 10
max-party-size: 5
```

### Persistance

`database.type` : `sqlite` (par défaut, fichier local) ou `mysql`. Les colonnes ajoutées par les mises à jour sont créées automatiquement dans une base existante.

---

## Configurer la carte

1. Créer et construire le monde `lolmc_template` (nom défini par `world.template`).
2. Poser structures, spawns, routes des sbires (`/lola road`), camps de jungle (`/lola jungle <type> <blue|red>` ; épiques et larves sans équipe ; `/lola jungle scuttle_crab <top|bot>` pour chacun des deux crabes) et PNJ de boutique (`/lola shopnpc`).
3. Tester avec `/lola testgame` ou `/lola solo`.

**Fosse du Baron** : les formes Territoriale et Omnisciente modifient le terrain. Pour chacune : sélectionner la fosse (`/lola schem pos1` / `pos2`), lancer `/lola baronpit <territorial|omniscient> start`, construire la variante, puis `/lola baronpit <forme> save`. Seuls les blocs modifiés sont enregistrés (`jungle.yml`). `preview` pose une variante, `clear` la retire, `/lola baronpit reset` remet le terrain normal.

En mode instances, sauvegarder et **décharger** `lolmc_template` avant de lancer des parties (`/mv unload lolmc_template` avec Multiverse), et ne jamais configurer la carte dans une copie `lolmc_game_N` : elle est supprimée en fin de partie.

---

## Mode multi-instances (expérimental)

Par défaut (`use-instances: false`), une seule partie tourne, directement dans le monde `world.name`. C'est le mode le plus testé.

Avec `use-instances: true`, chaque match copie le monde template dans un monde isolé (`lolmc_game_1`, `lolmc_game_2`…), dans la limite de `world.max-instances`.

- **Isolé par instance :** partie, sbires, jungle, tourelles, brouillard, récompenses, annonces, hauts faits, passifs, base, sélection des champions, vote d'abandon, tab et statistiques de fin de partie.
- **Équipes :** une seule table joueur → équipe, mais toutes les lectures sont limitées au monde du joueur ; deux parties ne se mélangent jamais.
- **Template :** copié depuis `plugins/LolMC/template/` s'il contient un monde, sinon depuis le dossier `world.template`. `/lola template save` enregistre le monde courant comme template et y rattache `map.yml`, `roads.yml` et `jungle.yml`.
- **Plantes :** chaque instance a les siennes, dont les chunks restent chargés pendant la partie.
- **Nettoyage :** un monde d'instance vide est supprimé (60 s après pour une partie en cours) ; les mondes `lolmc_game_*` laissés par un crash sont supprimés au démarrage.
- **BungeeTabListPlus :** variables `lolmc_team`, `lolmc_champion`, `lolmc_kills`, `lolmc_deaths`, `lolmc_assists`, `lolmc_kda`, `lolmc_cs`, `lolmc_gold`, `lolmc_game` (ex. `${player lolmc_kda}`). Les classes BTLP de `src/main/java/codecrafter47` sont des stubs de compilation exclus du JAR.

---

## Commandes

### Joueur (serveur de jeu)

| Commande | Description |
|----------|-------------|
| `/roles` (alias `/lobby`, `/play`) | Menu de préparation (rôles, file) |
| `/queue [leave]` | Rejoindre ou quitter la file locale |
| `/party <invite\|accept\|leave\|info>` | Gérer son groupe |
| `/champion <pick <nom>\|list>` · `/pick <champion>` | Choisir son champion |
| `/spell <sort1> <sort2>` · `/lock` | Sorts d'invocateur, verrouiller la sélection |
| `/runes` | Page de runes |
| `/shop` | Boutique |
| `/team <bleu\|rouge\|auto>` · `/t <message>` | Équipe, chat d'équipe |
| `/recall` | Retour à la base |
| `/ping <danger\|omw\|missing\|assist\|enemy>` | Ping d'équipe |
| `/l <runes\|ping\|ff\|stats\|shop>` | Raccourcis en partie (`ff` = vote d'abandon) |
| `/l grimoire <sort possédé> <nouveau>` | Grimoire déchaîné : échanger un sort d'invocateur |

### Admin — `/lola` (alias `/lolAdmin`)

| Catégorie | Sous-commandes |
|-----------|----------------|
| Partie | `start`, `stop`, `select`, `join <champion> [instance]` (alias `rejoindre`), `reload`, `help`, `permcheck` |
| Carte (monde template) | `set`, `position`, `center`, `lane`, `schem`, `road`, `jungle`, `baronpit`, `shopnpc`, `mode`, `template [save]` |
| Test (partie en cours) | `solo`, `testgame`, `give`, `level`, `gold`, `hp`, `resetcd`, `buff`, `spawn`, `wave`, `team`, `debug` |
| Instances | `instance` (liste), `instance <n>`, `instance template` (téléportation) |

**`/lola join` — rejoindre une partie en cours.** LoL ne le permet pas : les règles de rattrapage sont propres au plugin. Le joueur entre dans l'équipe la moins remplie (refus si 5 contre 5), dans la partie la moins remplie si plusieurs instances tournent (ou celle donnée en argument). Il arrive à la fontaine au niveau moyen des participants (arrondi à l'inférieur, points de compétence à répartir), avec 500 PO plus l'or passif écoulé depuis 1:50, sa page de runes, Saut éclair + Embrasement (Châtiment pour un jungler) et la Balise camouflée. Il devient participant à part entière (reconnexion, statistiques, fin de partie). Un joueur déjà dans la partie se reconnecte simplement ; la commande est refusée pendant la sélection des champions.

### Proxy — `/lol`, `/lols`, `/lolr`

| Commande | Description |
|----------|-------------|
| `/lol <rôle1> <rôle2> …` | Rejoindre la file normale (min. 2 rôles, ou 1 en groupe de 5) |
| `/lol all` | Accepter tous les rôles |
| `/lol leave` · `/lol stop` · `/lol quitter` | Quitter la file |
| `/lol accept` · `/lol refuse` | Répondre à une partie trouvée |
| `/lol party invite\|accept\|decline\|leave\|kick\|promote\|disband\|liste` | Gérer le groupe |
| `/lol start` | Chef de groupe : lancer la recherche |
| `/lolr …` | File classée (mêmes règles, avec bans et picks) ; `/lolr on\|off` pour les admins |
| `/lols [rôle]` | Test solo de l'enchaînement file → sélection → partie (`lolmc.admin`) |

Rôles : `top`, `jungle`, `mid`, `adc`, `support`. En groupe, chaque membre choisit ses rôles, puis le chef lance la recherche une fois tout le monde prêt ; le groupe reste dans la même équipe. Les rôles sont sauvegardés par joueur (`roles_data.yml`) et chacun retourne à son serveur et à sa position d'origine en fin de partie.

---

## Permissions

Déclarées dans `plugin.yml`, compatibles **LuckPerms**. Chaque famille a un joker `.*` qui donne tout, et chaque commande sa propre permission. Sans plugin de permissions, les opérateurs ont tout et les autres joueurs ont les commandes joueur, mais aucun champion (sauf la rotation gratuite).

### Serveur de jeu

**Admin (`/lola`)** : `lolmc.admin.<commande>` pour une seule commande, `lolmc.admin.<groupe>` pour un groupe, `lolmc.admin.*` pour tout (défaut : op). `lolmc.admin` reste accepté comme ancien nom de `lolmc.admin.*`. L'aide et l'autocomplétion de `/lola` n'affichent que les commandes autorisées.

| Groupe | Commandes (`lolmc.admin.<commande>`) |
|--------|--------------------------------------|
| `lolmc.admin.game` | `start`, `stop`, `select`, `join` (aussi `/lola rejoindre`), `instance`, `reload`, `permcheck` |
| `lolmc.admin.config` | `set`, `position`, `center`, `lane`, `schem`, `road`, `jungle`, `baronpit`, `shopnpc`, `mode`, `template` |
| `lolmc.admin.test` | `solo`, `testgame`, `give`, `level`, `gold`, `hp`, `resetcd`, `buff`, `spawn`, `wave`, `team`, `debug` |

`lolmc.admin.give` permet aussi de changer de champion pendant une partie ou une sélection.

**Joueur** : `lolmc.player.<commande>`, toutes : `lolmc.player.*` (défaut : tout le monde ; `lolmc.player` = ancien nom). Commandes : `shop`, `team`, `party`, `queue`, `recall`, `ping`, `pick` (aussi `/champion`), `runes`, `spell`, `lock`, `roles`, `lobby`, et pour `/l` : `ff`, `stats`, `grimoire` (`/l runes`, `/l ping` et `/l shop` utilisent `runes`, `ping` et `shop`).

**Champions** : il faut **posséder** un champion pour le jouer : `lolmc.champion.<id>`, tous : `lolmc.champion.*` (défaut : op ; `lolmc.champion.use` = ancien nom). Identifiants : `garen`, `darius`, `malphite`, `nasus`, `warwick`, `amumu`, `masteryi`, `leesin`, `annie`, `veigar`, `zed`, `yasuo`, `morgana`, `leona`, `blitzcrank`, `janna`, `ashe`, `sivir`, `jinx`, `missfortune`.
- Les champions de la **rotation gratuite** (`champions.free-rotation` dans `config.yml`, vide par défaut) sont jouables par tout le monde.
- Les champions non possédés apparaissent verrouillés (🔒) dans les menus et barrés dans `/champion list` ; la sélection automatique (fin du temps) ne choisit qu'un champion possédé.
- Un joueur qui ne possède aucun champion ne peut pas entrer en file locale (`/queue`, `/roles`). Via le proxy, le contrôle n'a lieu qu'en sélection : s'il ne possède rien, il reçoit le premier champion de la rotation gratuite (ou, si elle est vide, Garen : la partie ne peut pas démarrer avec une place vide).
- `/lola join` exige aussi de posséder le champion choisi.

**Skins** : `lolmc.skin.<champion>.<skin>` (ex. `lolmc.skin.jinx.arcane`), tous : `lolmc.skin.*` (défaut : personne ; `lolmc.skin` = ancien nom). `/lola permcheck` vérifie que chaque skin du code a sa permission déclarée.

### Proxy

| Permission | Rôle |
|------------|------|
| `lolmc.play` | `/lol` et `/lolr` (accordée à la connexion si aucun plugin de permissions n'a décidé) |
| `lolmc.admin` | `/lolr on\|off`, `/lols` |

### Exemples LuckPerms

```
/lpb group default permission set lolmc.play true      # proxy : file pour tous
/lpb group admin permission set lolmc.admin true       # proxy : gestion du classé
/lp group admin permission set lolmc.admin.* true      # jeu : admin complet
/lp group moderateur permission set lolmc.admin.test true
/lp group animateur permission set lolmc.admin.join true   # seulement /lola join
/lp group default permission set lolmc.champion.garen true # posséder Garen
/lp user Pseudo permission set lolmc.champion.* true       # tous les champions
/lp group vip permission set lolmc.skin.* true
```

---

## API web des statistiques

Serveur HTTP en lecture seule, activé par `api.enabled: true`.

| Route | Description |
|-------|-------------|
| `GET /api/player/{uuid}` · `GET /api/player/name/{pseudo}` | Fiche d'un joueur |
| `GET /api/player-champions/{uuid}` | Statistiques par champion |
| `GET /api/match/history/{uuid}` | Historique des parties |
| `GET /api/match/detail/{id}` | Détail d'une partie |
| `GET /api/leaderboard` | Classement |
| `GET /api/champions` | Liste des champions |
| `GET /api/online` | Joueurs en ligne, statut et instance |
| `GET /api/status` | Statut du serveur et de chaque instance |

Écoute par défaut sur `127.0.0.1`. Pour un site externe : `api.bind: "0.0.0.0"`, port ouvert et clé `api.key` (en-tête `X-Api-Key`).

---

## Compilation et release

Prérequis : **JDK 25** et **Maven 3.6.3+**.

```bash
mvn package                      # → target/lol-minecraft-1.0.0.jar
cd bungee-plugin && mvn package  # → bungee-plugin/target/LolMC-Bungee-1.0.0.jar
```

Les JAR embarquent leurs dépendances (maven-shade-plugin). L'API BungeeCord est téléchargée depuis le dépôt SpigotMC.

`./release.sh <version> [token_github]` compile les deux projets, pousse le tag `v<version>` et publie la release GitHub avec les deux JAR (token lu aussi dans `GITHUB_TOKEN` ou `git config github.token`).

Une analyse Qodana tourne sur chaque push et pull request vers `main` (`.github/workflows/qodana_code_quality.yml`).

---

## Stack technique

| Composant | Version |
|-----------|---------|
| Paper API | 26.3 build 190 (api-version 26.3) |
| Java | 25 |
| SQLite JDBC | 3.53.4.0 |
| MySQL Connector/J | 26.7.0 |
| Multiverse-Core | 4.3.12 (soft-depend) |
| BungeeCord API | 1.21-R0.4 |
| maven-compiler-plugin / maven-shade-plugin | 3.16.0 / 3.6.2 |

Les dépendances transitives vulnérables de paper-api (plexus-utils, commons-lang3, log4j-api) et de l'API BungeeCord (netty-codec-compression) sont épinglées dans `dependencyManagement`.

---

## État du projet

En développement actif. Le mode partie unique est le plus testé ; le mode multi-instances est expérimental et n'a pas encore été validé avec deux parties simultanées. Aucun mode n'a encore été éprouvé par une longue session de jeu réelle : retours et rapports de bugs bienvenus dans les issues.
