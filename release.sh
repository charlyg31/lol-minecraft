#!/bin/bash
# ══════════════════════════════════════════════════════════════════════
# LolMC — Script de release GitHub
# Usage : ./release.sh <version> [chemin/vers/BungeeCord.jar]
# Exemple : ./release.sh 1.0.0 /opt/proxy/BungeeCord.jar
#
# Si BungeeCord.jar n'est pas fourni, le script le cherche automatiquement
# dans les emplacements courants.
# ══════════════════════════════════════════════════════════════════════

set -e

VERSION=${1:-""}
BUNGEE_PATH=${2:-""}

if [ -z "$VERSION" ]; then
    echo "❌ Usage : ./release.sh <version> [chemin/BungeeCord.jar]"
    echo "   Exemple : ./release.sh 1.0.0 /opt/proxy/BungeeCord.jar"
    exit 1
fi

# Chercher BungeeCord.jar automatiquement si non fourni
if [ -z "$BUNGEE_PATH" ]; then
    for candidate in \
        "/opt/proxy/BungeeCord.jar" \
        "/root/proxy/BungeeCord.jar" \
        "/home/proxy/BungeeCord.jar" \
        "$(find / -name "BungeeCord.jar" -maxdepth 6 2>/dev/null | head -1)"
    do
        if [ -f "$candidate" ]; then
            BUNGEE_PATH="$candidate"
            break
        fi
    done
fi

if [ -z "$BUNGEE_PATH" ] || [ ! -f "$BUNGEE_PATH" ]; then
    echo "❌ BungeeCord.jar introuvable."
    echo "   Précise le chemin : ./release.sh $VERSION /chemin/vers/BungeeCord.jar"
    exit 1
fi

echo "📦 BungeeCord.jar : $BUNGEE_PATH"

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
mvn package -q -DskipTests -Dbungee.jar.path="$BUNGEE_PATH"
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
echo "✅ Release $TAG publiée !"

rm -f "LolMC-$VERSION.jar" "LolMC-Bungee-$VERSION.jar"
