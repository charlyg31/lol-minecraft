#!/bin/bash
# ══════════════════════════════════════════════════════════════════════
# LolMC — Script de release GitHub
# Usage : ./release.sh <version>
# Exemple : ./release.sh 1.2.0
#
# Ce script :
#   1. Compile LolMC.jar (plugin de jeu) et LolMC-Bungee.jar (proxy)
#   2. Crée un tag git v<version>
#   3. Pousse le tag sur GitHub
#   4. Crée une release GitHub avec les 2 JARs en pièces jointes
#
# Prérequis :
#   - Maven (mvn) installé
#   - GitHub CLI (gh) installé et authentifié
#   - Token GitHub avec droits "contents:write"
# ══════════════════════════════════════════════════════════════════════

set -e

VERSION=${1:-""}
if [ -z "$VERSION" ]; then
    echo "❌ Usage : ./release.sh <version>"
    echo "   Exemple : ./release.sh 1.2.0"
    exit 1
fi

TAG="v$VERSION"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "🔨 Compilation de LolMC.jar..."
mvn package -q -DskipTests
LOLMC_JAR=$(find target -name "lol-minecraft-*.jar" ! -name "*-original*" | head -1)
if [ -z "$LOLMC_JAR" ]; then
    echo "❌ LolMC.jar introuvable dans target/"
    exit 1
fi
cp "$LOLMC_JAR" "LolMC-$VERSION.jar"
echo "✅ LolMC-$VERSION.jar"

echo "🔨 Compilation de LolMC-Bungee.jar..."
cd bungee-plugin
mvn package -q -DskipTests
BUNGEE_JAR=$(find target -name "LolMC-Bungee-*.jar" ! -name "*-original*" | head -1)
if [ -z "$BUNGEE_JAR" ]; then
    echo "❌ LolMC-Bungee.jar introuvable dans bungee-plugin/target/"
    exit 1
fi
cp "$BUNGEE_JAR" "../LolMC-Bungee-$VERSION.jar"
cd ..
echo "✅ LolMC-Bungee-$VERSION.jar"

echo "🏷️  Création du tag $TAG..."
git tag -a "$TAG" -m "Release $TAG"
git push origin "$TAG"
echo "✅ Tag $TAG poussé"

echo "🚀 Création de la release GitHub..."
gh release create "$TAG" \
    "LolMC-$VERSION.jar#LolMC-$VERSION.jar (serveur de jeu)" \
    "LolMC-Bungee-$VERSION.jar#LolMC-Bungee-$VERSION.jar (proxy BungeeCord)" \
    --title "LolMC $TAG" \
    --notes "## LolMC $TAG

### Installation
- **LolMC-$VERSION.jar** → \`/plugins/\` du serveur de jeu
- **LolMC-Bungee-$VERSION.jar** → \`/plugins/\` du proxy BungeeCord

Voir le [README](README.md) pour la configuration complète."

echo ""
echo "✅ Release $TAG publiée avec succès !"
echo "   LolMC-$VERSION.jar"
echo "   LolMC-Bungee-$VERSION.jar"

# Nettoyage des JARs locaux
rm -f "LolMC-$VERSION.jar" "LolMC-Bungee-$VERSION.jar"
