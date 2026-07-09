#!/usr/bin/env bash
set -euo pipefail

ROOT="src/main/java/fr/lolmc"
FAIL=0

check_file() {
    if [ ! -f "$1" ]; then
        echo "❌ Fichier introuvable : $1"
        FAIL=1
        return 1
    fi
    return 0
}

F="$ROOT/game/MatchScoreboard.java"
if check_file "$F"; then
    if grep -q "notifyGameEnd" "$F"; then
        echo "⏭  MatchScoreboard.java : déjà branché, ignoré"
    else
        if ! grep -qF '        persistResults(winner, tm);' "$F"; then
            echo "❌ Ancre introuvable dans MatchScoreboard.java"
            FAIL=1
        else
            sed -i "/^        persistResults(winner, tm);\$/a\\
\\
        // Notifier le lobby (BungeeCord) de la fin de partie\\
        var bridge = LolPlugin.getInstance().getBridgeManager();\\
        if (bridge != null \&\& bridge.isEnabled()) {\\
            long duration = LolPlugin.getInstance().getGameManager().getElapsedSeconds();\\
            List<fr.lolmc.stats.persistence.PlayerStats> endStats = new ArrayList<>();\\
            for (UUID id : stats.keySet()) {\\
                endStats.add(new fr.lolmc.stats.persistence.PlayerStats(id, \"\"));\\
            }\\
            bridge.notifyGameEnd(winner.name(), duration, endStats);\\
        }" "$F"
            echo "✔ MatchScoreboard.java : notifyGameEnd branché"
        fi
    fi
fi

F="$ROOT/game/GameManager.java"
if check_file "$F"; then
    if grep -q "sendPlayerToLobby" "$F"; then
        echo "⏭  GameManager.java : déjà branché, ignoré"
    else
        if ! grep -qF '                p.setGameMode(org.bukkit.GameMode.SURVIVAL);' "$F"; then
            echo "❌ Ancre introuvable dans GameManager.java"
            FAIL=1
        else
            sed -i "/^                p.setGameMode(org.bukkit.GameMode.SURVIVAL);\$/,/^        }\$/{
                /^        }\$/a\\
\\
        // Renvoyer les joueurs vers le lobby (BungeeCord), si configuré\\
        var bridge = LolPlugin.getInstance().getBridgeManager();\\
        if (bridge != null \&\& bridge.isEnabled()) {\\
            for (org.bukkit.entity.Player p : fr.lolmc.util.WorldContext.getGamePlayers()) {\\
                bridge.sendPlayerToLobby(p);\\
            }\\
        }
            }" "$F"
            echo "✔ GameManager.java : sendPlayerToLobby branché"
        fi
    fi
fi

F="$ROOT/game/RoleQueueManager.java"
if check_file "$F"; then
    if grep -q "notifyQueueStatus" "$F"; then
        echo "⏭  RoleQueueManager.java : déjà branché, ignoré"
    else
        if ! grep -qF '        tryFormGame();' "$F"; then
            echo "❌ Ancre introuvable (tryFormGame) dans RoleQueueManager.java"
            FAIL=1
        else
            sed -i "/^        tryFormGame();\$/i\\
        notifyQueueStatus(player.getUniqueId(), \"JOINED\");" "$F"

            sed -i '/Sorti de la file\.", NamedTextColor.YELLOW));$/a\
        notifyQueueStatus(player.getUniqueId(), "LEFT");' "$F"

            sed -i '/notifyQueueStatus(player.getUniqueId(), "LEFT");$/{
n
a\
\
    /** Notifie le lobby (BungeeCord) du statut de queue d'"'"'un joueur, si activé. */\
    private void notifyQueueStatus(UUID uuid, String status) {\
        var bridge = LolPlugin.getInstance().getBridgeManager();\
        if (bridge != null \&\& bridge.isEnabled()) {\
            bridge.notifyQueueStatus(uuid, status, inQueue.size());\
        }\
    }
}' "$F"
            echo "✔ RoleQueueManager.java : notifyQueueStatus branché"
        fi
    fi
fi

F="$ROOT/listener/HealthListener.java"
if check_file "$F"; then
    if grep -q "bridge.cleanupPlayer" "$F"; then
        echo "⏭  HealthListener.java : déjà branché, ignoré"
    else
        if ! grep -qF '            gm.saveSnapshot(p);' "$F"; then
            echo "❌ Ancre introuvable dans HealthListener.java"
            FAIL=1
        else
            sed -i "/^            gm.saveSnapshot(p);\$/{
n
a\\
        var bridge = LolPlugin.getInstance().getBridgeManager();\\
        if (bridge != null) bridge.cleanupPlayer(p.getUniqueId());
}" "$F"
            echo "✔ HealthListener.java : cleanupPlayer branché"
        fi
    fi
fi

F="$ROOT/bridge/BridgeManager.java"
if check_file "$F"; then
    if grep -q "String server = getOriginServer" "$F"; then
        echo "⏭  BridgeManager.java : déjà branché, ignoré"
    else
        if ! grep -qF '        String server = originServers.remove(player.getUniqueId());' "$F"; then
            echo "❌ Ancre introuvable dans BridgeManager.java"
            FAIL=1
        else
            sed -i \
                -e 's/^        String server = originServers\.remove(player\.getUniqueId());$/        String server = getOriginServer(player.getUniqueId());/' \
                "$F"
            sed -i "/^        String server = getOriginServer(player.getUniqueId());\$/{
n
a\\
        originServers.remove(player.getUniqueId());
}" "$F"
            echo "✔ BridgeManager.java : getOriginServer consommée"
        fi
    fi
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
    echo "══════════════════════════════════════════"
    echo "✔ Tous les branchements sont appliqués."
    echo "  Vérifie la compilation, puis :"
    echo "    git add -A"
    echo "    git commit -m \"fix: brancher BridgeManager dans le flux reel\""
    echo "    git push origin main"
    echo "══════════════════════════════════════════"
else
    echo "❌ Au moins un fichier a échoué — vérifie manuellement."
    exit 1
fi
