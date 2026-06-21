# LolMC — League of Legends dans Minecraft

Un plugin Paper qui recrée un MOBA façon **League of Legends** directement dans Minecraft, sans mod : 20 champions fidèles, runes, items, jungle, tourelles, nexus, file d'attente par rôles et sélection de champions.

> **Version cible :** Paper / Minecraft **26.1.2** · **Java 25** · FAWE requis pour les schématiques

---

## 📖 Présentation

LolMC transforme un serveur Minecraft en arène MOBA. Deux équipes (Bleue et Rouge) s'affrontent sur une carte à 3 voies avec jungle, l'objectif étant de détruire le Nexus adverse.

Le plugin reproduit les mécaniques clés de LoL :

- **20 champions** avec leurs vraies statistiques de base, courbes de croissance par niveau (1-18) et sorts (Q/W/E/R) aux valeurs fidèles qui montent en puissance par rang.
- **Système de runes complet** : 5 voies (Précision, Domination, Sorcellerie, Détermination, Inspiration), keystones, runes mineures et fragments de stats, avec un éditeur en jeu et sauvegarde des pages.
- **Items** inspirés de la boutique LoL, achetés via un PNJ boutique avec de l'or gagné en jeu.
- **Sbires (minions)** qui partent en vagues, suivent leur voie, attaquent les ennemis puis les tourelles, avec super-sbires après destruction d'un inhibiteur.
- **Jungle** : monstres neutres, buffs (rouge/bleu), et objectifs épiques (Héraut, Baron, Dragons).
- **Tourelles, inhibiteurs et nexus** avec dégâts, ciblage et destruction par phases.
- **Brouillard de guerre**, bushes (buissons), wards (balises de vision).
- **File d'attente par rôles** (Top/Jungle/Mid/ADC/Support) et **sélection de champions** avant la partie.
- **Sorts d'invocateur** (Flash, Ignite, Heal, Barrier, Exhaust, Teleport, Smite, Cleanse, Ghost).
- **Parties classées et normales** avec système d'Elo et statistiques persistantes.

---

## 🎮 Champions

| Rôle | Champions |
|------|-----------|
| **Top** | Garen, Darius, Malphite, Nasus |
| **Jungle** | Warwick, Amumu, Master Yi, Lee Sin |
| **Mid** | Annie, Veigar, Zed, Yasuo |
| **Support** | Morgana, Leona, Blitzcrank, Janna |
| **ADC** | Ashe, Sivir, Jinx, Miss Fortune |

---

## ⌨️ Commandes

### Commandes joueur

| Commande | Description |
|----------|-------------|
| `/lobby` (ou `/play`, `/roles`) | Ouvre le menu de préparation (rôles, runes, sorts) |
| `/queue` (ou `/file`, `/q`) | Rejoindre la file d'attente par rôles |
| `/champion <pick\|list\|info>` (ou `/champ`) | Gérer et consulter les champions |
| `/shop` (ou `/boutique`, `/b`) | Ouvrir la boutique d'items |
| `/team <bleu\|rouge\|auto>` (ou `/equipe`) | Rejoindre une équipe |
| `/party <invite\|accept\|leave\|info>` (ou `/groupe`, `/p`) | Gérer son groupe |
| `/pick` | Choisir un champion en sélection |
| `/runes` | Ouvrir l'éditeur de runes |
| `/spell <sort1> <sort2>` | Choisir ses 2 sorts d'invocateur |
| `/lock` | Verrouiller sa sélection |
| `/recall` | Retour à la base (canalisation) |
| `/ping <danger\|omw\|miss\|assist\|enemy>` | Envoyer un ping à l'équipe |

### Commandes admin — `/lol`

Toutes les sous-commandes de `/lol` requièrent la permission `lolmc.admin` (op par défaut).

**Configuration de la carte :**

| Commande | Description |
|----------|-------------|
| `/lol set <turret\|inhibitor\|nexus\|basenexus> <blue\|red>` | Définir une structure à ta position |
| `/lol position <blue\|red>` | Définir un point de spawn d'équipe |
| `/lol lane <blue\|red>` | Configurer une voie |
| `/lol road <top\|mid\|bot\|end>` | Peindre la route des sbires |
| `/lol jungle <camp>` | Placer un camp de jungle |
| `/lol shopnpc <blue\|red>` | Placer un PNJ boutique |
| `/lol mode <ranked\|normal>` | Définir le mode de partie |

**Lancement et tests en solo :**

| Commande | Description |
|----------|-------------|
| `/lol solo <champion>` | **Mode test solo** : t'assigne l'équipe bleue + un champion + tes runes, lance la partie complète et te téléporte au spawn |
| `/lol testgame` | Lance la map (structures + sbires + jungle + timer) sans toucher à ton perso |
| `/lol give <champion>` | Change ton champion à la volée |
| `/lol level <1-18>` | Met ton champion au niveau voulu |
| `/lol gold <montant>` | Te donne de l'or |
| `/lol team <blue\|red>` | Change d'équipe |
| `/lol select` | Démarre une sélection de champions |
| `/lol start` | Lance une partie |
| `/lol stop` | Arrête la partie en cours |

> 💡 **Pour tester seul :** `/lol solo garen` lance une partie complète où tu peux jouer immédiatement, sans attendre d'autres joueurs.

---

## 🔐 Permissions

| Permission | Description | Défaut |
|------------|-------------|--------|
| `lolmc.admin` | Toutes les commandes admin | op |
| `lolmc.admin.config` | Configuration de la carte | op |
| `lolmc.admin.test` | Commandes de test (solo, give, level…) | op |
| `lolmc.player.*` | Commandes joueur (shop, team, queue, recall…) | true |
| `lolmc.champion.use` | Jouer les champions | true |
| `lolmc.champion.<nom>` | Jouer un champion précis (ex. `lolmc.champion.garen`) | true |

Chaque commande et chaque champion possède sa propre permission, ce qui permet par exemple de réserver certains champions à des grades VIP.

---

## 🛠️ Installation

1. Télécharge ou compile le `.jar` (voir ci-dessous).
2. Place `lol-minecraft-1.0.0.jar` dans le dossier `plugins/` de ton serveur Paper **26.1.2**.
3. Installe **FAWE** (FastAsyncWorldEdit) dans `plugins/` — requis pour coller les schématiques de structures.
4. Démarre le serveur une première fois pour générer la configuration.
5. Configure la carte avec les commandes `/lol set`, `/lol position`, `/lol road`, etc.

---

## 🔧 Compilation

Le projet se compile avec **Maven** et **Java 25**.

```bash
git clone https://github.com/charlyg31/lol-minecraft.git
cd lol-minecraft
mvn clean package
```

Le `.jar` final est généré dans `target/lol-minecraft-1.0.0.jar` (avec toutes les dépendances incluses : drivers de base de données HikariCP, MySQL, SQLite).

> ⚠️ Le projet cible Java 25. Vérifie ta version avec `java -version` et adapte si besoin.

---

## ⚙️ Configuration (`config.yml`)

| Section | Contenu |
|---------|---------|
| `heads` | Textures base64 des têtes de champions |
| `bushes` | Blocs considérés comme buissons |
| `turrets` | Portée, hauteur de détection, dégâts des tourelles |
| `database` | Type de base (`sqlite`, `mysql`, `mongodb`) et identifiants |
| `api` | Serveur HTTP optionnel pour les statistiques (désactivé par défaut) |
| `fog` | Brouillard de guerre, portée de vision |

---

## 📦 Stack technique

- **Paper API** 26.1.2 (`api-version: '26.1'`)
- **Java** 25
- **FAWE** pour les schématiques
- **Adventure** pour les composants de texte et sons
- Bases de données : **SQLite** / **MySQL** / **MongoDB** (HikariCP)

---
