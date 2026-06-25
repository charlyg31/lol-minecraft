package fr.lolmc.bridge;

import fr.lolmc.LolPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * BridgeManager — communication cross-serveur via BungeeCord PluginMessage.
 *
 * Canal entrant "lolmc:bridge" : messages JSON du lobby vers le serveur de jeu.
 * Canal sortant "BungeeCord"   : connexion d'un joueur sur un autre serveur.
 *
 * Format des messages (JSON simple, sans dépendance externe) :
 *
 *   LOBBY → GAME:
 *     {"type":"QUEUE_JOIN","uuid":"...","name":"...","party":["uuid1","uuid2"],
 *      "runes":{"keystone":"conqueror","minors":["triumph",...]},
 *      "spell1":"FLASH","spell2":"IGNITE","elo":1200}
 *
 *     {"type":"QUEUE_LEAVE","uuid":"..."}
 *
 *   GAME → LOBBY:
 *     {"type":"QUEUE_STATUS","uuid":"...","status":"WAITING|FOUND|LEFT","queueSize":3}
 *     {"type":"GAME_START","players":["uuid1",...],"server":"lolmc-01"}
 *     {"type":"GAME_END","winner":"BLUE","duration":1847,"players":[
 *       {"uuid":"...","kills":5,"deaths":2,"assists":8}]}
 *
 * Activation dans config.yml :
 *   bridge:
 *     enabled: true
 *     lobby-server: "lobby"    # nom du serveur BungeeCord du lobby
 *     game-server: "lolmc-01"  # nom de CE serveur dans BungeeCord
 */
public class BridgeManager implements PluginMessageListener {

    public static final String CHANNEL_IN  = "lolmc:bridge";
    public static final String CHANNEL_OUT = "BungeeCord";

    private final LolPlugin plugin;
    private final Logger log;
    private boolean enabled;
    private String lobbyServer;
    private String gameServer;

    // Données reçues du lobby en attente de connexion du joueur
    // uuid → données JSON brutes
    private final Map<UUID, Map<String, String>> pendingPlayerData = new java.util.concurrent.ConcurrentHashMap<>();

    public BridgeManager(LolPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.enabled     = cfg.getBoolean("bridge.enabled", false);
        this.lobbyServer = cfg.getString("bridge.lobby-server", "lobby");
        this.gameServer  = cfg.getString("bridge.game-server", "lolmc-01");

        if (enabled) {
            // Enregistrer les canaux PluginMessage
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_IN, this);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_OUT);
            log.info("[Bridge] Activé — lobby=" + lobbyServer + " game=" + gameServer);
        }
    }

    public boolean isEnabled() { return enabled; }

    // ════════════════════════════════════════════════════════
    // RÉCEPTION (Lobby → Game)
    // ════════════════════════════════════════════════════════

    @Override
    public void onPluginMessageReceived(String channel, Player unused, byte[] message) {
        if (!channel.equals(CHANNEL_IN)) return;
        String json = new String(message, java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> data = parseJson(json);
        String type = data.getOrDefault("type", "");

        switch (type) {
            case "QUEUE_JOIN"  -> handleQueueJoin(data);
            case "QUEUE_LEAVE" -> handleQueueLeave(data);
            case "PLAYER_DATA" -> handlePlayerData(data);
            default -> log.warning("[Bridge] Message inconnu: " + type);
        }
    }

    private void handleQueueJoin(Map<String, String> data) {
        UUID uuid = UUID.fromString(data.getOrDefault("uuid", ""));
        // Stocker les données pour quand le joueur se connecte
        pendingPlayerData.put(uuid, data);
        // Chercher si le joueur est déjà connecté (il peut arriver avant le message)
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> applyPlayerData(player, data));
        }
        log.info("[Bridge] Queue join reçu pour " + data.getOrDefault("name", uuid.toString()));
    }

    private void handleQueueLeave(Map<String, String> data) {
        UUID uuid = UUID.fromString(data.getOrDefault("uuid", ""));
        pendingPlayerData.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            plugin.getMatchmakingManager().leaveQueue(player);
        }
    }

    private void handlePlayerData(Map<String, String> data) {
        UUID uuid = UUID.fromString(data.getOrDefault("uuid", ""));
        pendingPlayerData.put(uuid, data);
    }

    /**
     * Appelé quand un joueur rejoint ce serveur.
     * Applique les données reçues du lobby (runes, party, queue).
     */
    public void onPlayerJoin(Player player) {
        if (!enabled) return;
        Map<String, String> data = pendingPlayerData.remove(player.getUniqueId());
        if (data == null) return;
        // Délai pour laisser le joueur charger complètement
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyPlayerData(player, data), 20L);
    }

    private void applyPlayerData(Player player, Map<String, String> data) {
        // ── Runes ──
        String keystone = data.getOrDefault("keystone", "");
        if (!keystone.isEmpty()) {
            var rm = plugin.getRuneManager();
            if (rm != null) rm.applyKeystoneFromBridge(player, keystone, data);
        }

        // ── Sorts d'invocateur ──
        String spell1 = data.getOrDefault("spell1", "FLASH");
        String spell2 = data.getOrDefault("spell2", "IGNITE");
        var sm = plugin.getSummonerSpellManager();
        if (sm != null) sm.setSpells(player, spell1, spell2);

        // ── Party ──
        String partyStr = data.getOrDefault("party", "");
        if (!partyStr.isEmpty()) {
            var pm = plugin.getPartyManager();
            for (String uuidStr : partyStr.split(",")) {
                try {
                    UUID memberUuid = UUID.fromString(uuidStr.trim());
                    // Reconstituer le groupe si les membres sont aussi connectés
                    Player member = Bukkit.getPlayer(memberUuid);
                    if (member != null && !member.equals(player)) {
                        pm.addToParty(player, member);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // ── Auto-queue si le joueur était en file ──
        String wasQueued = data.getOrDefault("queued", "false");
        if ("true".equals(wasQueued)) {
            var mm = plugin.getMatchmakingManager();
            if (mm != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> mm.joinQueue(player), 40L);
            }
        }

        player.sendMessage(Component.text(
            "✔ Données synchronisées depuis le lobby!", NamedTextColor.GREEN));
        log.info("[Bridge] Données appliquées pour " + player.getName());
    }

    // ════════════════════════════════════════════════════════
    // ENVOI (Game → Lobby)
    // ════════════════════════════════════════════════════════

    /**
     * Envoie un message au lobby via BungeeCord.
     * Nécessite qu'au moins un joueur soit connecté pour servir de porteur.
     */
    public void sendToLobby(String jsonPayload) {
        if (!enabled) return;
        // Trouver un joueur porteur (n'importe lequel)
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) {
            log.warning("[Bridge] Impossible d'envoyer au lobby: aucun joueur connecté");
            return;
        }
        sendPluginMessage(carrier, "Forward", lobbyServer, CHANNEL_IN, jsonPayload);
    }

    /** Notifie le lobby du statut de la file d'un joueur. */
    public void notifyQueueStatus(UUID uuid, String status, int queueSize) {
        sendToLobby("{\"type\":\"QUEUE_STATUS\",\"uuid\":\"" + uuid
                  + "\",\"status\":\"" + status
                  + "\",\"queueSize\":" + queueSize + "}");
    }

    /** Notifie le lobby du démarrage d'une partie (pour envoyer les joueurs sur ce serveur). */
    public void notifyGameStart(java.util.List<UUID> players) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"GAME_START\",\"server\":\"").append(gameServer).append("\",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(players.get(i)).append("\"");
        }
        sb.append("]}");
        sendToLobby(sb.toString());
    }

    /** Notifie le lobby de la fin de partie (stats, résultat). */
    public void notifyGameEnd(String winner, long durationSeconds,
                              java.util.List<fr.lolmc.stats.persistence.PlayerStats> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"GAME_END\",\"winner\":\"").append(winner)
          .append("\",\"duration\":").append(durationSeconds)
          .append(",\"server\":\"").append(gameServer)
          .append("\",\"players\":[");
        boolean first = true;
        for (var st : stats) {
            if (!first) sb.append(",");
            sb.append("{\"uuid\":\"").append(st.uuid).append("\"}");
            first = false;
        }
        sb.append("]}");
        sendToLobby(sb.toString());
    }

    /**
     * Envoie un joueur vers le serveur lobby.
     * Utilisé après la fin de partie.
     */
    public void sendPlayerToLobby(Player player) {
        if (!enabled) return;
        sendPluginMessage(player, "Connect", lobbyServer, null, null);
    }

    // ════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════

    private void sendPluginMessage(Player carrier, String subChannel,
                                    String target, String forwardChannel, String payload) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {

            out.writeUTF(subChannel);
            if ("Forward".equals(subChannel) && target != null) {
                out.writeUTF(target);
                out.writeUTF(forwardChannel != null ? forwardChannel : CHANNEL_IN);
                byte[] data = payload != null
                    ? payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : new byte[0];
                out.writeShort(data.length);
                out.write(data);
            } else if ("Connect".equals(subChannel)) {
                out.writeUTF(target);
            }

            carrier.sendPluginMessage(plugin, CHANNEL_OUT, baos.toByteArray());
        } catch (IOException e) {
            log.warning("[Bridge] Erreur envoi message: " + e.getMessage());
        }
    }

    /**
     * Parseur JSON minimal (ne nécessite pas de dépendance externe).
     * Supporte les objets plats {"key":"value",...} et les arrays comme strings.
     */
    static Map<String, String> parseJson(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        // Découper par les virgules de premier niveau (hors strings)
        List<String> pairs = new ArrayList<>();
        int depth = 0; boolean inStr = false; StringBuilder cur = new StringBuilder();
        for (char c : json.toCharArray()) {
            if (c == '"' && depth == 0) inStr = !inStr;
            else if (!inStr && (c == '[' || c == '{')) depth++;
            else if (!inStr && (c == ']' || c == '}')) depth--;
            else if (c == ',' && !inStr && depth == 0) {
                pairs.add(cur.toString().trim()); cur.setLength(0); continue;
            }
            cur.append(c);
        }
        if (!cur.isEmpty()) pairs.add(cur.toString().trim());

        for (String pair : pairs) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String key = pair.substring(0, colon).trim().replace("\"", "");
            String val = pair.substring(colon + 1).trim();
            if (val.startsWith("\"") && val.endsWith("\""))
                val = val.substring(1, val.length() - 1);
            result.put(key, val);
        }
        return result;
    }

    public void disable() {
        if (!enabled) return;
        try {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_IN);
        } catch (Exception ignored) {}
    }
}
