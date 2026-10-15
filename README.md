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

Camps classiques, buffs Bleu et Rouge, Larves du Néant, Héraut (invoqué puis monté par un allié qui dirige sa charge), Baron Nashor (3 formes de fosse, Main du Baron qui renforce les sbires), dragons élémentaires avec âme au 4ᵉ et Dragon Ancien. Plantes : Cône explosif, Fruit de miel et Bulbe révélateur (voir [Plantes](#plantes--lola-plant)).

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

Toute la configuration se fait **dans le monde template** (`lolmc_template` par défaut, `world.template` dans `config.yml`), jamais dans une copie `lolmc_game_N` : celles-ci sont supprimées en fin de partie. Les commandes demandent les permissions `lolmc.admin.<commande>` (groupe `lolmc.admin.config`).

Fichiers écrits dans `plugins/LolMC/` :

| Fichier | Contenu | Commandes |
|---------|---------|-----------|
| `map.yml` | structures (position, orientation, niveau), points d'apparition | `/lola set`, `/lola position` |
| `roads.yml` | routes des sbires | `/lola road` |
| `jungle.yml` | camps de jungle, terrains de la fosse du Baron | `/lola jungle`, `/lola baronpit` |
| `plants.yml` | emplacements des plantes | `/lola plant` |
| `npcs.yml` | position des PNJ boutique (le PNJ lui-même est une entité du monde) | `/lola shopnpc` |
| `schematics/*.json` | apparence des structures par phase de PV | `/lola schem` |

### Check-list avant `/lola template save`

Cocher chaque ligne, dans cet ordre :

1. **Monde construit** : la Faille complète (lanes, jungle, rivière, bases, buissons). Les buissons sont les blocs listés dans `bushes.blocks` de `config.yml` (herbes hautes, fougères…) : un champion dedans est invisible pour l'ennemi.
2. **Fours d'ancrage** : un four posé au sol sous **chaque** structure (11 tourelles, 3 inhibiteurs et 1 Nexus par équipe), face avant tournée dans l'orientation voulue. Voir [Le four sous les structures](#le-four-sous-les-structures).
3. **Schématiques** des structures enregistrées (`/lola schem`) : au minimum une par type et par équipe (`Turret_Blue`, `Turret_Red`, `Inhibitor_Blue`…), plus les phases si tu veux que les tourelles s'abîment.
4. **Structures déclarées** avec `/lola set` en cliquant sur chaque four : 22 tourelles, 6 inhibiteurs, 2 Nexus.
5. **Points d'apparition** : `/lola position <blue|red> <1-5>` pour les deux équipes. La position 1 sert aussi de **fontaine** (soin, boutique, rappel).
6. **Routes des sbires** : `/lola road` pour `top`, `mid` et `bot`, de base à base.
7. **Camps de jungle** : 2 × (Gromp, Loups, Raptors, Krugs, Sentinelle bleue, Sanglepince), 2 crabes, 1 dragon, la fosse du Néant (larves, Héraut, Baron au même point).
8. **Fosse du Baron** (facultatif) : terrains Territorial et Omniscient avec `/lola baronpit`.
9. **Plantes** : Cônes explosifs, Fruits de miel et Bulbes révélateurs avec `/lola plant`.
10. **PNJ boutique** : `/lola shopnpc <blue|red>` dans chaque fontaine.
11. **Centre de la minimap** : `/lola center` au milieu de la carte.
12. **Test** : `/lola testgame` ou `/lola solo`, vérifier que les sbires avancent, que les tourelles tirent, que les camps et les plantes apparaissent, puis `/lola stop`.
13. **Enregistrer** : `/lola template save` depuis le monde template (aucune partie en cours). Le monde est copié dans `plugins/LolMC/template/`, et `map.yml`, `roads.yml`, `jungle.yml` et `plants.yml` sont rattachés à ce monde.
14. **Mode instances** : décharger le monde template avant de lancer des parties (`/mv unload lolmc_template` avec Multiverse).

### Le four sous les structures

Chaque structure repose sur **un four posé au sol**. C'est son **ancre** :

- **Position** : le four est le **centre** de la structure. Les tourelles tirent depuis ce point (zone cylindrique de `turrets.attack-radius` = 7,5 blocs, soit 750 unités, sur `turrets.detection-height` blocs de haut), les champions et les sbires mesurent leur portée d'attaque jusqu'à lui, et la barre de vie s'affiche au-dessus.
- **Orientation** : la face avant du four (celle qui a l'ouverture) indique dans quelle direction la structure regarde. En collant une schématique, le plugin compare l'orientation du four enregistré dans la schématique à celle du four au sol et **tourne** la schématique de 0, 90, 180 ou 270° : une seule schématique sert pour les tourelles des deux côtés et de toutes les lanes.
- **Collage** : la schématique est collée **juste au-dessus** du four (le four de la schématique se retrouve 1 bloc plus haut que celui du sol). C'est ce qui permet de changer l'apparence de la structure selon ses PV (phases), puis d'afficher des ruines à sa destruction.
- **Le four doit rester en place** : `/lola set` ne le supprime pas. Sans four au sol, la structure fonctionne (PV, tirs, récompenses), mais son apparence ne change jamais.

### Structures : `/lola set`

```
/lola set turret <blue|red> <top|mid|bot|base> <rang> [outer|inner|inhibitor|nexus]
/lola set inhibitor <blue|red> <top|mid|bot> 1
/lola set basenexus <blue|red>
```

Après la commande, **clic (gauche ou droit) sur le four** de la structure. Le message confirme l'orientation lue sur le four.

| Structure | Commande | PV | Remarques |
|-----------|----------|----|-----------|
| Tourelle extérieure | `/lola set turret blue top 1` | 9000 | rang 1 = extérieure |
| Tourelle intérieure | `/lola set turret blue top 2` | 5000 | rang 2 |
| Tourelle d'inhibiteur | `/lola set turret blue top 3` | 4750 | rang 3 |
| Tourelles du Nexus | `/lola set turret blue base 1` puis `base 2` | 3500 | lane `base`, 2 par équipe |
| Inhibiteur | `/lola set inhibitor blue top 1` | 4000 | un par lane ; réapparaît 5 min après sa destruction |
| Nexus | `/lola set basenexus blue` | 5500 | — |

À faire pour `top`, `mid` et `bot`, et pour les deux équipes (11 tourelles + 3 inhibiteurs + 1 Nexus par équipe). Le niveau de tourelle (dernier argument) est déduit de la lane et du rang ; ne le préciser que pour une carte non standard. Règles appliquées : une tourelle n'est attaquable qu'une fois la précédente de sa lane détruite, un inhibiteur qu'une fois les tourelles de sa lane tombées, les tourelles du Nexus qu'une fois un inhibiteur détruit, et le Nexus qu'une fois un inhibiteur et les 2 tourelles du Nexus détruits.

`/lola set nexus <équipe> <lane> <rang>` déclare un « Nexus de lane » : cette structure n'existe pas dans LoL. Ne pas l'utiliser pour une Faille standard.

### Schématiques et phases : `/lola schem`

```
/lola schem pos1          (coin 1 = position de tes pieds)
/lola schem pos2          (coin 2)
/lola schem save <nom>
```

1. Construire la structure **sur un four** (le four fait partie de la sélection et devient le point 0,0,0).
2. Se placer à un coin de la boîte englobante : `/lola schem pos1`, puis au coin opposé : `/lola schem pos2`.
3. `/lola schem save <nom>`. Le fichier `plugins/LolMC/schematics/<nom>.json` contient tous les blocs sauf l'air, relatifs au four. Sans four dans la zone, rien n'est enregistré (erreur dans la console).

**Noms** (du plus précis au plus général, le premier trouvé est utilisé) :

| Nom | S'applique à |
|-----|--------------|
| `Turret_Blue_Top_1` | la tourelle extérieure bleue du haut uniquement |
| `Turret_Blue_Top` | toutes les tourelles bleues du haut |
| `Turret_Blue` | toutes les tourelles bleues |

Types : `Turret`, `Inhibitor`, `Nexus` (Nexus de lane), `NexusBase` (Nexus) ; équipes : `Blue`, `Red` ; lanes : `Top`, `Mid`, `Bot`, `Base`.

**Phases** : ajouter `_<seuil>` au nom. La phase affichée est celle du plus petit seuil supérieur ou égal au pourcentage de PV restant.

| Fichier | Affiché quand |
|---------|---------------|
| `Turret_Blue.json` | PV > 75 % (et au lancement de la partie) |
| `Turret_Blue_75.json` | PV ≤ 75 % |
| `Turret_Blue_50.json` | PV ≤ 50 % |
| `Turret_Blue_25.json` | PV ≤ 25 % |
| `Turret_Blue_0.json` | structure détruite (ruines) |

Après un nom de lane, `_1` à `_5` désigne le rang de la structure et non un seuil : `Turret_Blue_Top_1` est la tourelle extérieure, ses phases sont `Turret_Blue_Top_1_75`, etc. Pour les phases d'un nom de lane (`Turret_Blue_Top_…`), utiliser des seuils de 0 ou supérieurs à 5.

Quand la phase change, les blocs de l'ancienne phase que la nouvelle ne contient pas sont retirés. Au lancement de chaque partie, la phase 100 % est recollée sur toutes les structures.

### Points d'apparition et fontaine : `/lola position`

```
/lola position <blue|red> <1-5>
```

Puis clic au sol : le joueur apparaît 1 bloc au-dessus, tourné dans la direction où tu regardes. 5 positions par équipe.

La **position 1** (ou la plus petite définie) est la **fontaine** de l'équipe :
- départ des joueurs en début de partie, réapparition et rappel (`/recall`) ;
- soin de la fontaine dans un rayon de 8 blocs (et tir de la fontaine contre les ennemis) ;
- achat à la boutique dans un rayon de `shop.base-radius` (15 blocs) si `shop.base-only: true`.

### Routes des sbires : `/lola road`

```
/lola road <top|mid|bot> <blue|red>
/lola road end
```

1. `/lola road top blue` : tu reçois une **houe en or** (pinceau). `blue` = tu traces depuis la base bleue, `red` = depuis la base rouge.
2. Clic sur les blocs du chemin, **dans l'ordre**, de la base de départ jusqu'à la base adverse. Les blocs peints deviennent de la terre cuite verte (temporaire).
3. `/lola road end` : les blocs reprennent leur apparence, les blocs proches (3 blocs) sont regroupés en points de passage, et la route est enregistrée (`roads.yml`) dans le sens base bleue → base rouge.

Les sbires bleus **apparaissent au premier point** de la route et les rouges **au dernier** : commencer et finir la route devant chaque Nexus. Ils suivent les points et s'en écartent pour combattre. À faire pour `top`, `mid` et `bot` ; retracer une route la remplace.

### Camps de jungle : `/lola jungle`

```
/lola jungle <type> <blue|red>     (camps de chaque équipe)
/lola jungle scuttle_crab <top|bot>
/lola jungle <épique>              (sans équipe)
```

Puis clic au sol : le camp apparaît 1 bloc au-dessus (les monstres d'un groupe autour du point, sur 1,5 bloc).

| Type | Camp | Où | 1ʳᵉ apparition | Réapparition |
|------|------|----|----------------|--------------|
| `blue_buff` | Sentinelle bleue | jungle de chaque équipe | 0:55 | 5 min |
| `red_buff` | Sanglepince (buff rouge) | jungle de chaque équipe | 0:55 | 5 min |
| `murkwolf` | Loups (1 grand + 2 petits) | jungle de chaque équipe | 0:55 | 2 min 15 |
| `raptor` | Raptors (1 grand + 5 petits) | jungle de chaque équipe | 0:55 | 2 min 15 |
| `gromp` | Gromp | jungle de chaque équipe | 1:07 | 2 min 15 |
| `krug` | Krugs (se divisent) | jungle de chaque équipe | 1:07 | 2 min 15 |
| `scuttle_crab` + `top`/`bot` | Crabe Pillargot | rivière, un en haut et un en bas | 2:55 | 2 min 30 |
| `dragon_infernal` (ou tout `dragon_*`) | Dragon | fosse du dragon | 5:00 | 5 min |
| `voidgrub` | Larves du Néant (3) | fosse du Baron | 8:00 | jusqu'à 14:45 |
| `herald` | Héraut de la Faille | fosse du Baron | 15:00 | une fois, jusqu'à 19:45 |
| `baron` | Baron Nashor | fosse du Baron | 20:00 | 6 min |

- **Dragon** : ne placer **qu'un seul** `dragon_*` (n'importe lequel). L'élément suit la rotation de LoL (le 3ᵉ dragon fixe l'élément de la Faille), puis le **Dragon Ancien** apparaît au même endroit après une âme. Placer plusieurs types de dragon ferait apparaître plusieurs dragons.
- **Fosse du Baron** : placer `voidgrub`, `herald` et `baron` **au même point**, ils se succèdent.
- Replacer un camp le déplace (un emplacement par camp et par équipe).

**Terrain de la fosse du Baron** (formes Territoriale et Omnisciente, qui modifient le décor) :

```
/lola schem pos1 / pos2                         (sélection englobant la fosse)
/lola baronpit <territorial|omniscient> start   (photographie le terrain normal)
   … construire la variante …
/lola baronpit <territorial|omniscient> save    (enregistre les blocs modifiés, remet le terrain normal)
/lola baronpit <forme> preview | clear          (poser pour vérifier / supprimer)
/lola baronpit reset                            (remettre le terrain normal)
```

200 000 blocs au plus. Seuls les blocs qui diffèrent du terrain normal sont enregistrés (`jungle.yml`).

### Plantes : `/lola plant`

```
/lola plant blastcone [inner|outer]     (Cône explosif)
/lola plant honeyfruit                  (Fruit de miel)
/lola plant scryer [normal|late]        (Bulbe révélateur)
/lola plant list | remove | clear
```

La plante est posée **à tes pieds** (centre du bloc). `remove` supprime l'emplacement le plus proche (5 blocs max), `clear` les supprime tous, `list` les affiche. Enregistré dans `plants.yml`.

Valeurs du wiki LoL (patch 26.20). Toutes les plantes ont 1 PV : **une attaque de base** (clic d'attaque en la visant, à portée d'attaque du champion) les détruit. Elles apparaissent d'abord en **bourgeon** 🌱 inactif pendant 60 s, puis mûrissent.

| Plante | Variante | Où (Faille standard) | 1ʳᵉ apparition | Réapparition | Effet |
|--------|----------|----------------------|----------------|--------------|-------|
| Cône explosif | `inner` | 2 par quadrant de jungle, dont un qui permet d'entrer dans la fosse du dragon/du Baron | 1:15-1:25 | 5-7 min | Explosion de 325 unités de rayon : toutes les unités (attaquant compris) sont projetées jusqu'à 900 unités (9 blocs) à l'opposé du cône, par-dessus le décor |
| Cône explosif | `outer` | près des rivières, côté lanes | 9:00-9:30 | 5,5-6,5 min | idem |
| Fruit de miel | — | le long des murs de la rivière | 6:00-6:30 | 5,5-7 min | 5 fruits au sol pendant 30 s ; marcher dessus rend 6 % du mana max et max(3,5 % des PV max, 14-116 selon le niveau) PV, et ralentit de 35 % pendant 0,25 s |
| Bulbe révélateur | `normal` | 2 par quadrant, près des rampes de la rivière | 3:00-3:30 | 200-260 s (patch 26.1) | Pollen en cône dans la direction de l'attaque : champions ennemis révélés 3 s, sbires, monstres et balises ennemis 12 s |
| Bulbe révélateur | `late` | emplacements ouverts à 15:00 (patch 26.1) : côtes de la rivière près des lanes latérales, derrière les tourelles intérieures des lanes latérales, rochers des bases | 15:00 | 90-120 s | idem |

Chaque partie (monde unique ou instance) a ses propres plantes, programmées depuis le début de la partie et retirées à la fin.

Différences avec LoL :
- Chaque emplacement est indépendant. LoL limite à 1 Bulbe par quadrant (la 1ʳᵉ apparition se fait au point le plus proche des lanes latérales) et à 2 Fruits de miel par moitié de rivière, en déplaçant les fruits vers les lanes au fil de la partie. Pour s'en approcher, poser 1 Bulbe `normal` par quadrant et 1 à 2 Fruits par moitié de rivière.
- Le cône du Bulbe porte à 1500 unités (indicateur du jeu, CommunityDragon), mais son angle n'est publié nulle part : 60° ici.
- Le rayon de ramassage des fruits n'est pas publié : 0,8 bloc ici. Le soin de 14 à 116 PV est supposé linéaire (+6 PV par niveau).

### PNJ boutique : `/lola shopnpc`

```
/lola shopnpc <blue|red>
```

Crée un villageois immobile et invulnérable **à ta position**. Clic droit dessus : ouvre la boutique (seulement pour son équipe). C'est une entité du monde : elle est copiée avec le template. Pour en déplacer un, le tuer (`/kill` en le visant) puis le recréer. La boutique s'ouvre aussi par `/shop` ou `/l shop` dans la fontaine.

### Minimap : `/lola center`

Enregistre ta position comme centre de la minimap (`minimap.center-x/z` dans `config.yml`). L'échelle se règle avec `minimap.scale` (`CLOSE` = 256 blocs de côté).

### Tester et enregistrer

- `/lola testgame` : lance une partie de test (structures, sbires, jungle, plantes) dans le monde de jeu.
- `/lola solo [champion]` : partie seule, niveau 18 et 20 000 PO.
- `/lola stop` : arrête la partie (et ferme l'instance si tu es dans une instance).
- `/lola template save` : enregistre le monde où tu te trouves comme template (voir la check-list).
- `/lola instance` : liste les instances ; `/lola instance <n>` ou `/lola instance template` : s'y téléporter.

En mode instances, sauvegarder et **décharger** `lolmc_template` avant de lancer des parties (`/mv unload lolmc_template` avec Multiverse), et ne jamais configurer la carte dans une copie `lolmc_game_N` : elle est supprimée en fin de partie.

---

## Mode multi-instances (expérimental)

Par défaut (`use-instances: false`), une seule partie tourne, directement dans le monde `world.name`. C'est le mode le plus testé.

Avec `use-instances: true`, chaque match copie le monde template dans un monde isolé (`lolmc_game_1`, `lolmc_game_2`…), dans la limite de `world.max-instances`.

- **Isolé par instance :** partie, sbires, jungle, tourelles, brouillard, récompenses, annonces, hauts faits, passifs, base, sélection des champions, vote d'abandon, tab et statistiques de fin de partie.
- **Équipes :** une seule table joueur → équipe, mais toutes les lectures sont limitées au monde du joueur ; deux parties ne se mélangent jamais.
- **Template :** copié depuis `plugins/LolMC/template/` s'il contient un monde, sinon depuis le dossier `world.template`. `/lola template save` enregistre le monde courant comme template et y rattache `map.yml`, `roads.yml` et `jungle.yml`.
- **Plantes :** chaque instance a les siennes (mêmes emplacements que le template), dont les chunks restent chargés pendant la partie.
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
| Carte (monde template) | `set`, `position`, `center`, `schem`, `road`, `jungle`, `plant`, `baronpit`, `shopnpc`, `mode`, `template [save]` (détail : [Configurer la carte](#configurer-la-carte)) |
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
| `lolmc.admin.config` | `set`, `position`, `center`, `schem`, `road`, `jungle`, `plant`, `baronpit`, `shopnpc`, `mode`, `template` |
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
