#!/bin/bash
# LolMC — Script de release GitHub
# Usage : ./release.sh <version> [BungeeCord.jar] [github_token]

VERSION=${1:-""}
BUNGEE_PATH=${2:-""}
ARG_TOKEN=${3:-""}

if [ -z "$VERSION" ]; then
    echo "❌ Usage : ./release.sh <version> [BungeeCord.jar] [token]"
    exit 1
fi

# Chercher BungeeCord.jar
if [ -z "$BUNGEE_PATH" ] || [ ! -f "$BUNGEE_PATH" ]; then
    for c in /sdcard/Download/BungeeCord.jar /root/proxy/BungeeCord.jar /opt/proxy/BungeeCord.jar; do
        [ -f "$c" ] && BUNGEE_PATH="$c" && break
    done
fi
if [ ! -f "$BUNGEE_PATH" ]; then
    echo "❌ BungeeCord.jar introuvable. Précise le chemin en 2ème argument."
    exit 1
fi
echo "📦 BungeeCord.jar : $BUNGEE_PATH"

TAG="v$VERSION"
REPO="charlyg31/lol-minecraft"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Compilation LolMC.jar ─────────────────────────────────────────────
echo "🔨 Compilation de LolMC.jar..."
if ! mvn package -q -DskipTests 2>&1 | grep -v "WARNING"; then
    echo "❌ Echec compilation LolMC"
    exit 1
fi
LOLMC_JAR=$(find target -name "lol-minecraft-*.jar" ! -name "*-original*" 2>/dev/null | head -1)
[ -z "$LOLMC_JAR" ] && echo "❌ LolMC.jar introuvable" && exit 1
cp "$LOLMC_JAR" "LolMC-$VERSION.jar"
echo "✅ LolMC-$VERSION.jar"

# ── Compilation LolMC-Bungee.jar ─────────────────────────────────────
echo "🔨 Compilation de LolMC-Bungee.jar..."
cd bungee-plugin
if ! mvn package -q -DskipTests -Dbungee.jar.path="$BUNGEE_PATH" 2>&1 | grep -v "WARNING"; then
    echo "❌ Echec compilation Bungee"
    cd ..
    exit 1
fi
BUNGEE_JAR=$(find target -name "LolMC-Bungee-*.jar" ! -name "*-original*" 2>/dev/null | head -1)
[ -z "$BUNGEE_JAR" ] && echo "❌ LolMC-Bungee.jar introuvable" && cd .. && exit 1
cp "$BUNGEE_JAR" "../LolMC-Bungee-$VERSION.jar"
cd ..
echo "✅ LolMC-Bungee-$VERSION.jar"

# ── Tag Git ───────────────────────────────────────────────────────────
echo "🏷️  Tag $TAG..."
git tag -d "$TAG" 2>/dev/null || true
git push origin --delete "$TAG" 2>/dev/null || true
git tag -a "$TAG" -m "Release $TAG"
git push origin "$TAG"
echo "✅ Tag $TAG poussé"

# ── Token GitHub ──────────────────────────────────────────────────────
# Priorité : argument $3 > variable GITHUB_TOKEN > git config
GH_TOKEN="$ARG_TOKEN"
[ -z "$GH_TOKEN" ] && GH_TOKEN="$GITHUB_TOKEN"
[ -z "$GH_TOKEN" ] && GH_TOKEN="$(git config github.token 2>/dev/null || true)"
[ -z "$GH_TOKEN" ] && GH_TOKEN="$(git config --global github.token 2>/dev/null || true)"

if [ -z "$GH_TOKEN" ]; then
    echo ""
    echo "⚠️  Token GitHub manquant — JARs compilés mais non uploadés."
    echo "   Pour uploader manuellement :"
    echo "   1. Crée la release sur https://github.com/$REPO/releases/tag/$TAG"
    echo "   2. Uploade LolMC-$VERSION.jar et LolMC-Bungee-$VERSION.jar"
    echo ""
    echo "   Ou relance avec le token :"
    echo "   ./release.sh $VERSION $BUNGEE_PATH TON_TOKEN"
    exit 0
fi

echo "🚀 Création de la release GitHub..."

# Supprimer l'ancienne release si elle existe
OLD_ID=$(curl -sf -H "Authorization: token $GH_TOKEN" \
    "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
    | grep '"id"' | head -1 | grep -o '[0-9]*' || true)
[ -n "$OLD_ID" ] && curl -sf -X DELETE \
    -H "Authorization: token $GH_TOKEN" \
    "https://api.github.com/repos/$REPO/releases/$OLD_ID" > /dev/null 2>&1 || true

# Créer la release
NOTES="## LolMC $TAG\n\n### Installation\n- **LolMC-$VERSION.jar** → \`/plugins/\` du serveur de jeu\n- **LolMC-Bungee-$VERSION.jar** → \`/plugins/\` du proxy BungeeCord"
RESP=$(curl -s -X POST \
    -H "Authorization: token $GH_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"LolMC $TAG\",\"body\":\"$NOTES\"}" \
    "https://api.github.com/repos/$REPO/releases")

UPLOAD_URL=$(echo "$RESP" | grep '"upload_url"' | head -1 | sed 's/.*"upload_url": "\(.*\){.*/\1/')

if [ -z "$UPLOAD_URL" ]; then
    echo "❌ Erreur API GitHub :"
    echo "$RESP" | head -5
    exit 1
fi
echo "✅ Release créée"

# Upload des JARs
for JAR in "LolMC-$VERSION.jar" "LolMC-Bungee-$VERSION.jar"; do
    echo "📤 Upload $JAR..."
    RES=$(curl -s -X POST \
        -H "Authorization: token $GH_TOKEN" \
        -H "Content-Type: application/java-archive" \
        --data-binary @"$JAR" \
        "${UPLOAD_URL}?name=$JAR")
    if echo "$RES" | grep -q '"state": "uploaded"'; then
        echo "✅ $JAR uploadé"
    else
        echo "❌ Erreur upload $JAR :"
        echo "$RES" | grep -E '"message"|"errors"' | head -3
    fi
done

rm -f "LolMC-$VERSION.jar" "LolMC-Bungee-$VERSION.jar"
echo ""
echo "✅ Release $TAG terminée → https://github.com/$REPO/releases/tag/$TAG"
