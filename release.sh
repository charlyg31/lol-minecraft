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
git tag -d "$TAG" 2>/dev/null || true
git push origin --delete "$TAG" 2>/dev/null || true
git tag -a "$TAG" -m "Release $TAG"
git push origin "$TAG"
echo "✅ Tag $TAG poussé"

echo "🚀 Création de la release GitHub..."

# Lire le token depuis la config git
GITHUB_TOKEN=$(git config --get github.token 2>/dev/null || echo "")
if [ -z "$GITHUB_TOKEN" ]; then
    echo "❌ Token GitHub manquant."
    echo "   Configure-le avec : git config --global github.token TON_TOKEN"
    echo "   Puis relance le script."
    rm -f "LolMC-$VERSION.jar" "LolMC-Bungee-$VERSION.jar"
    exit 1
fi

REPO="charlyg31/lol-minecraft"

# Supprimer l'ancienne release si elle existe
RELEASE_ID=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
    "https://api.github.com/repos/$REPO/releases/tags/$TAG" \
    | grep '"id"' | head -1 | grep -o '[0-9]*')
if [ -n "$RELEASE_ID" ]; then
    curl -s -X DELETE -H "Authorization: token $GITHUB_TOKEN" \
        "https://api.github.com/repos/$REPO/releases/$RELEASE_ID" > /dev/null
fi

# Créer la release
RELEASE_RESPONSE=$(curl -s -X POST \
    -H "Authorization: token $GITHUB_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"LolMC $TAG\",\"body\":\"## LolMC $TAG\n\n### Installation\n- **LolMC-$VERSION.jar** → \`/plugins/\` du serveur de jeu\n- **LolMC-Bungee-$VERSION.jar** → \`/plugins/\` du proxy BungeeCord\n\nVoir le README pour la configuration complète.\"}" \
    "https://api.github.com/repos/$REPO/releases")

UPLOAD_URL=$(echo "$RELEASE_RESPONSE" | grep '"upload_url"' | sed 's/.*"upload_url": "\(.*\){.*/\1/')

if [ -z "$UPLOAD_URL" ]; then
    echo "❌ Erreur création release : $RELEASE_RESPONSE"
    rm -f "LolMC-$VERSION.jar" "LolMC-Bungee-$VERSION.jar"
    exit 1
fi

# Upload LolMC.jar
echo "📤 Upload LolMC-$VERSION.jar..."
curl -s -X POST \
    -H "Authorization: token $GITHUB_TOKEN" \
    -H "Content-Type: application/java-archive" \
    --data-binary @"LolMC-$VERSION.jar" \
    "${UPLOAD_URL}?name=LolMC-$VERSION.jar" > /dev/null
echo "✅ LolMC-$VERSION.jar uploadé"

# Upload LolMC-Bungee.jar
echo "📤 Upload LolMC-Bungee-$VERSION.jar..."
curl -s -X POST \
    -H "Authorization: token $GITHUB_TOKEN" \
    -H "Content-Type: application/java-archive" \
    --data-binary @"LolMC-Bungee-$VERSION.jar" \
    "${UPLOAD_URL}?name=LolMC-Bungee-$VERSION.jar" > /dev/null
echo "✅ LolMC-Bungee-$VERSION.jar uploadé"

echo ""
echo "✅ Release $TAG publiée !"

rm -f "LolMC-$VERSION.jar" "LolMC-Bungee-$VERSION.jar"
