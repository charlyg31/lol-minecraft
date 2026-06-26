# LolMC — League of Legends dans Minecraft

Plugin Paper qui recrée un MOBA complet fidèle à **League of Legends** directement dans Minecraft, sans mod client. 20 champions jouables, système d'instances multi-parties simultanées, intégration BungeeCord complète.

> **Stack :** Paper 26.1.2 · Java 25 · BungeeCord (optionnel)

---

## Architecture — 2 plugins

```
Proxy BungeeCord
└── LolMC-Bungee.jar   → file d'attente, groupes, runes, retour serveur d'origine

Serveur de jeu (dédié)
└── LolMC.jar          → la partie complète
```

Les joueurs tapent `/lol <rôles>` depuis **n'importe quel serveur** du réseau (survie, skyblock, créatif…). Le proxy gère la file. Quand 10 joueurs sont prêts, ils sont envoyés sur le serveur de jeu. À la fin de la partie, chaque joueur retourne automatiquement sur le serveur et à la position exacte d'où il venait.

Sans BungeeCord, le plugin fonctionne aussi en serveur autonome.

---

## LolMC-Bungee.jar — Plugin proxy

### Installation

Placer dans le dossier `/plugins/` du **proxy BungeeCord uniquement**.

### Configuration (`config.yml`)

```yaml
game-server: "lolmc-01"   # nom exact dans la config BungeeCord
fallback-server: "survie"  # serveur de repli si origine inconnue
players-per-game: 10       # joueurs nécessaires pour lancer une partie
max-party-size: 5          # taille maximale d'un groupe
```

### Fonctionnalités

**File d'attente cross-serveur**
- Le joueur choisit ses rôles via `/lol <rôle1> <rôle2>...` depuis n'importe quel serveur
- Minimum 2 rôles obligatoires (ou `/lol all` pour accepter tous les rôles)
- Quand 10 joueurs sont en file, ils sont automatiquement envoyés sur `game-server`
- Les données (runes, rôles, sorts) sont transmises au serveur de jeu avant la connexion

**Groupes cross-serveur**
- Les membres peuvent être sur des serveurs différents
- Le premier joueur qui invite devient automatiquement chef
- Validation intelligente des rôles en groupe : algorithme de matching bipartite qui vérifie qu'une assignation valide existe (1 rôle unique par joueur)
- La file se lance automatiquement quand tout le groupe est prêt

**Runes et sorts persistants**
- Keystone + sorts d'invocateur sauvegardés par joueur dans `runes_data.yml`
- Rôles sauvegardés dans `roles_data.yml`
- Accessibles et modifiables depuis n'importe quel serveur

**Retour au serveur d'origine**
- À la fin de la partie, chaque joueur retourne exactement sur le serveur d'où il venait
- Capture automatique via `ServerConnectedEvent` côté proxy
- Fallback configurable si le serveur d'origine est inconnu

### Commandes `/lol`

**File d'attente**
| Commande | Description |
|----------|-------------|
| `/lol <rôle1> <rôle2> ...` | Rejoindre la file avec les rôles souhaités (min. 2) |
| `/lol all` | Rejoindre la file en acceptant tous les rôles |
| `/lol leave` | Quitter la file |
| `/lol runes` | Voir son keystone et ses sorts actuels |

Rôles valides : `top` · `jungle` · `mid` · `adc` · `support`

**Groupes**
| Commande | Description |
|----------|-------------|
| `/lol party invite <joueur>` | Inviter un joueur (crée le groupe si besoin) |
| `/lol party accept` | Accepter une invitation |
| `/lol party decline` | Refuser une invitation |
| `/lol party leave` | Quitter le groupe |
| `/lol party kick <joueur>` | Exclure un membre (chef) |
| `/lol party promote <joueur>` | Transférer le chef (chef) |
| `/lol party disband` | Dissoudre le groupe (chef) |
| `/lol party liste` | Voir les membres et leur statut |

Aliases : `/lol party` → `/lol p` · `/lol leave` → `/lol quitter` · `/ll` · `/jouer`

---

## LolMC.jar — Plugin de jeu

### Installation

Placer dans le dossier `/plugins/` du **serveur de jeu uniquement**.

Dépendance optionnelle : **Multiverse-Core** pour le chargement des mondes d'instances (fonctionne sans via WorldCreator natif Paper).

### Configuration (`config.yml`)

```yaml
world:
  template: "lolmc_template"    # monde configuré une fois, jamais utilisé en jeu
  instance-prefix: "lolmc_game_" # préfixe des mondes d'instances
  max-instances: 5               # parties simultanées maximum

bridge:
  enabled: false                 # true si BungeeCord utilisé
  game-server: "lolmc-01"
  lobby-server: "lobby"          # inutilisé (architecture sans lobby)

turrets:
  attack-radius: 8
  detection-height: 6
  base-damage: 150

fog:
  enabled: true
  vision-range: 30

database:
  type: sqlite                   # sqlite | mysql
  mysql:
    host: localhost
    port: 3306
    database: lolmc
    user: root
    password: ""

heads:                           # textures base64 des têtes de champions
  garen: "PASTE_VALUE_HERE"      # https://minecraft-heads.com
  # ...
```

---

## Système d'instances

Chaque partie se déroule dans son propre monde Minecraft, totalement isolé.

**Convention de nommage :**
- Template : `lolmc_template` (configuré une fois avec `/lol set`, jamais joué)
- Instances : `lolmc_game_1`, `lolmc_game_2`, … (compteur atomique)

**Cycle de vie d'une instance :**
1. Copie asynchrone du dossier `lolmc_template` → `lolmc_game_N` (I/O async)
2. Chargement du monde via Multiverse ou WorldCreator natif (sync)
3. Téléportation des joueurs dans l'instance
4. Phase de ban → sélection de champions → démarrage
5. Fin de partie → compte à rebours 30s → retour serveur d'origine → suppression du monde

**Isolation complète :** chaque instance a ses propres `GameManager`, `MinionManager`, `JungleManager`, `TurretManager`, `FogOfWarManager`, `PassiveManager`, `RewardManager`. Plusieurs parties simultanées ne peuvent pas s'interférer.

---

## Gameplay — Mécaniques fidèles à LoL

### 20 Champions

| Rôle | Champions |
|------|-----------|
| **Top** | Garen, Darius, Malphite, Nasus |
| **Jungle** | Warwick, Amumu, Master Yi, Lee Sin |
| **Mid** | Annie, Veigar, Zed, Yasuo |
| **Support** | Morgana, Leona, Blitzcrank, Janna |
| **ADC** | Ashe, Sivir, Jinx, Miss Fortune |

Chaque champion possède :
- Q / W / E / R aux valeurs officielles LoL avec scaling AP/AD par rang
- Passif implémenté (Hémorragie Darius, Détermination Garen, Flurry Lee Sin, Get Excited Jinx, Instinct de Chasse Warwick, Double Frappe Master Yi, Lumière du Soleil Leona, Siphon de l'Âme Morgana, Malfaisance Veigar, Volonté de Bataille Sivir…)
- Prévisualisation directionnelle des sorts (visible uniquement par le lanceur) : ligne pour les skillshots, cercle pour les sorts de zone
- Indicateurs de cooldown dans la hotbar (nom grisé + temps restant)

### Systèmes de jeu

**Dégâts**
- Formule complète : résistances, pénétration flat/%, boucliers, Grievous Wounds (40% items, 60% Ignite), vrai dégât
- Critiques, omnivamp, vol de vie

**Contrôles de foule (CCManager)**
- Stun, root, silence, slow, airborne (knockup réel avec vélocité)
- Tenacité, clear() propre à la mort

**Sbires**
- Vague toutes les 30s, 1ère à 1:05
- 3 mêlée + 3 casters, sbire canon dynamique (<15min 1/3, 15-25min 1/2, >25min chaque)
- Super-sbires après destruction d'inhibiteur
- XP partagée dans un rayon de 14 blocs

**Jungle**
- Tous les camps, buffs Rouge/Bleu
- Baron Nashor, Héraut de la Faille, Dragons (Infernal/Océan/Montagne/Foudre/Chimtech)
- Dragon Soul au 4e dragon, Dragon Ancestral au 5e (exécution sous 20% HP)

**Structures**
- Tourelles : priorité aggro LoL exacte (sbires → champion attaquant un allié → plus proche)
- Turret Plating avant 14min (+160 or/plaque)
- Inhibiteurs : respawn 5min, active les super-sbires, or distribué (50 global)
- Tourelles : 150 or global + 100 au dernier coup
- Vision des tourelles : révèle les ennemis dans un rayon de 10 blocs

**Économie**
- Or passif, or sbires croissant (+1/90s)
- Bounty (100-500 or selon série), killing spree annoncé
- Assists (fenêtre 10s), distribution de l'or sur structures

**Runes (14 keystones + runes mineures)**

Keystones : Conqueror, Electrocute, Press the Attack, Dark Harvest, Arcane Comet, Phase Rush, Grasp of the Undying, Fleet Footwork, Lethal Tempo, Hail of Blades, Summon Aery, First Strike, Predator, Glacial Augment

Mineures : Legend Bloodline (+6% omnivamp), Relentless Hunter (+MS), Bone Plating (bouclier), Gathering Storm (+AD/AP toutes les 10min), Second Wind, Sudden Impact, Taste of Blood…

**Items**
- ~230 items aux stats officielles, recettes, upgrades
- 47 passifs uniques (Spellblade, Kraken Slayer, BotRK, Liandry, Sterak, Black Cleaver, Thornmail, Titanic Hydra…)
- 14 items actifs (Zhonya, Galeforce avec animation dash + projectiles, Redemption, Locket, BotRK actif…)
- Élixirs disponibles à partir du niveau 9

**Sorts d'invocateur**
Flash, Ignite (GW60), Heal, Barrier, Exhaust, Téléport (vers tourelle alliée), Smite, Cleanse, Ghost

**Phase de sélection**
- 10 bans alternés Bleue/Rouge (30s par ban), timeout automatique
- Pick des champions, validation anti-doublon
- ChampSelect GUI complet

**Qualité de vie**
- Chat d'équipe `/t`
- Brouillard de guerre, wards (stealth/vision/lointaine), vision buissons
- Spectateur pendant le respawn
- Reconnexion avec état sauvegardé (HP, level, or, items)
- `/lol ff` — surrender vote (80% de l'équipe, CD 5min)
- Scoreboard en temps réel, écran de fin de partie
- CS (Creep Score) tracké

### Commandes admin (`/lol`)

**Configuration de la carte**

| Commande | Description |
|----------|-------------|
| `/lol set <turret\|inhibitor\|nexus> <blue\|red>` | Définir une structure à ta position |
| `/lol position <blue\|red>` | Point de spawn d'équipe |
| `/lol road <top\|mid\|bot\|end>` | Peindre la route des sbires |
| `/lol jungle <camp>` | Placer un camp de jungle |
| `/lol shopnpc <blue\|red>` | Placer un PNJ boutique |

**Tests**

| Commande | Description |
|----------|-------------|
| `/lol testgame` | Lancer la map complète (sbires + jungle + timer) |
| `/lol spawn <champion>` | Spawner un champion de test |
| `/lol buff <stat> <valeur>` | Modifier une stat en live |
| `/lol resetcd` | Remettre tous les cooldowns à zéro |
| `/lol hp <valeur>` | Modifier ses HP |
| `/lol wave` | Forcer une vague de sbires |
| `/lol help` | Liste des commandes admin |

---

## Configurer la carte

1. Créer un monde nommé `lolmc_template` (via Multiverse ou manuellement)
2. Construire la carte LoL dans ce monde
3. Utiliser `/lol set`, `/lol position`, `/lol road`, `/lol jungle` pour enregistrer les positions
4. Tester avec `/lol testgame`
5. Le monde template sera copié automatiquement pour chaque partie

---

## Permissions

| Permission | Plugin | Description | Défaut |
|------------|--------|-------------|--------|
| `lolmc.play` | LolMC-Bungee (proxy) | Commande `/lol` — file et groupe | true |
| `lolmc.admin` | LolMC (serveur de jeu) | Commandes admin `/lol` | op |

---

## Stack technique

| Composant | Version |
|-----------|---------|
| Paper API | 26.1.2 |
| Java | 25 |
| BungeeCord API | 1.21 (optionnel) |
| Multiverse-Core | 4.3.12 (optionnel, soft-depend) |
| Adventure | inclus dans Paper |
| HikariCP | inclus (SQLite/MySQL) |

Le projet compile avec Maven. Le JAR final inclut toutes les dépendances.
