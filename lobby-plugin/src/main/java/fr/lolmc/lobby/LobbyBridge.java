package fr.lolmc.lobby;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Gère la communication PluginMessage avec le serveur de jeu LoL.
 *
 * Canal sortant vers le jeu : "lolmc:bridge"
 * Canal entrant du jeu      : "lolmc:bridge"
 * Canal BungeeCord pour TP  : "BungeeCord"
 */
public class LobbyBridge implements PluginMessageListener {

    public static final String CHANNEL_LOLMC    = "lolmc:bridge";
    public static final String CHANNEL_BUNGEE   = "BungeeCord";

    private final LobbyPlugin plugin;
    private final LobbyPartyManager partyManager;
    private final LobbyQueueManager queueManager;
    private final LobbyRuneManager runeManager;
    private final String gameServer;
    private final Logger log;
    // Serveur BungeeCord d'origine de chaque joueur (pour le retour après partie)
    private final java.util.Map<java.util.UUID, String> playerOriginServer
        = new java.util.concurrent.ConcurrentHashMap<>();

    public LobbyBridge(LobbyPlugin plugin, LobbyPartyManager pm,
                       LobbyQueueManager qm, LobbyRuneManager rm) {
        this.plugin       = plugin;
        this.partyManager = pm;
        this.queueManager = qm;
        this.runeManager  = rm;
        this.gameServer   = plugin.getConfig().getString("game-server", "lolmc-01");
        this.log          = plugin.getLogger();

        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_LOLMC, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_BUNGEE, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_BUNGEE);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_LOLMC);
    }

    // ════════════════════════════════════════════════════════
    // RÉCEPTION (Game → Lobby)
    // ════════════════════════════════════════════════════════

    @Override
    public void onPluginMessageReceived(String channel, Player unused, byte[] bytes) {
        // Canal BungeeCord → réponse GetServer
        if (channel.equals(CHANNEL_BUNGEE)) {
            try (java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(bytes))) {
                String subChannel = in.readUTF();
                if ("GetServer".equals(subChannel)) {
                    String serverName = in.readUTF();
                    if (unused != null)
                        playerOriginServer.put(unused.getUniqueId(), serverName);
                }
            } catch (Exception ignored) {}
            return;
        }
        if (!channel.equals(CHANNEL_LOLMC)) return;
        String json = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> data = parseJson(json);
        String type = data.getOrDefault("type", "");

        switch (type) {
            case "QUEUE_STATUS" -> handleQueueStatus(data);
            case "GAME_START"   -> handleGameStart(data);
            case "GAME_END"     -> handleGameEnd(data);
        }
    }

    private void handleQueueStatus(Map<String, String> d) {
        try {
            UUID uuid = UUID.fromString(d.get("uuid"));
            String status = d.getOrDefault("status", "WAITING");
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) return;
            String msg = switch (status) {
                case "WAITING" -> "&7File: " + d.getOrDefault("queueSize","?") + " joueurs...";
                case "FOUND"   -> "&aPartie trouvée!";
                case "LEFT"    -> "&cTu as quitté la file.";
                default        -> "&7Statut: " + status;
            };
            p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(msg));
        } catch (Exception ignored) {}
    }

    private void handleGameStart(Map<String, String> d) {
        String playersStr = d.getOrDefault("players", "");
        String server = d.getOrDefault("server", gameServer);
        for (String uuidStr : playersStr.split(",")) {
            try {
                UUID uuid = UUID.fromString(uuidStr.trim().replace("[","").replace("]","").replace("\"",""));
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.sendMessage(net.kyori.adventure.text.Component.text(
                        "⚔ Partie trouvée! Connexion au serveur de jeu...",
                        net.kyori.adventure.text.format.NamedTextColor.GOLD));
                    sendToServer(p, server);
                }
            } catch (Exception ignored) {}
        }
    }

    private void handleGameEnd(Map<String, String> d) {
        String winner = d.getOrDefault("winner", "?");
        long duration = Long.parseLong(d.getOrDefault("duration", "0"));
        String time = String.format("%d:%02d", duration / 60, duration % 60);
        // Notifier tous les joueurs du lobby de la fin de la partie
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.sendMessage(net.kyori.adventure.text.Component.text(
                String.format("🏆 Partie terminée! Vainqueur: %s — Durée: %s", winner, time),
                net.kyori.adventure.text.format.NamedTextColor.GOLD));
        }
    }

    // ════════════════════════════════════════════════════════
    // ENVOI (Lobby → Game)
    // ════════════════════════════════════════════════════════

    /**
     * Envoie les données d'un joueur au serveur de jeu avant de l'y connecter.
     * Le serveur de jeu recevra ces données et les appliquera au joueur.
     */
    public void sendPlayerToGame(Player player, boolean queued) {
        var runes = runeManager.getPageJson(player.getUniqueId());
        var party = partyManager.getPartyMembers(player.getUniqueId());

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"type\":\"QUEUE_JOIN\",");
        sb.append("\"uuid\":\"").append(player.getUniqueId()).append("\",");
        sb.append("\"name\":\"").append(player.getName()).append("\",");
        sb.append("\"queued\":\"").append(queued).append("\",");
        // Runes
        sb.append("\"keystone\":\"").append(runes.getOrDefault("keystone","")).append("\",");
        sb.append("\"minors\":\"").append(runes.getOrDefault("minors","")).append("\",");
        // Sorts
        sb.append("\"spell1\":\"").append(runes.getOrDefault("spell1","FLASH")).append("\",");
        sb.append("\"spell2\":\"").append(runes.getOrDefault("spell2","IGNITE")).append("\",");
        // Party
        String role = plugin.getRoleManager().getRole(player.getUniqueId());
        sb.append("\"role\":\"").append(role).append("\",");
        // Serveur d'origine (pour retour après partie)
        String originServer = playerOriginServer.getOrDefault(player.getUniqueId(), null);
        if (originServer != null)
            sb.append("\"origin_server\":\"").append(originServer).append("\",");
        sb.append("\"party\":\"");
        sb.append(String.join(",", party.stream().map(UUID::toString).toList()));
        sb.append("\"");
        sb.append("}");

        sendForward(player, gameServer, CHANNEL_LOLMC, sb.toString());
        log.info("[Bridge] Données envoyées pour " + player.getName() + " → " + gameServer);
    }

    /** Connecte le joueur au serveur de jeu BungeeCord. */
    public void sendToServer(Player player, String server) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, CHANNEL_BUNGEE, baos.toByteArray());
        } catch (IOException e) {
            log.warning("[Bridge] Erreur connexion serveur: " + e.getMessage());
        }
    }

    /**
     * Demande à BungeeCord le nom du serveur actuel du joueur.
     * La réponse arrive dans onPluginMessageReceived (sous-canal GetServer).
     */
    public void requestCurrentServer(Player player) {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.io.DataOutputStream out = new java.io.DataOutputStream(baos)) {
            out.writeUTF("GetServer");
            player.sendPluginMessage(plugin, CHANNEL_BUNGEE, baos.toByteArray());
        } catch (Exception e) {
            log.warning("[Bridge] Erreur GetServer: " + e.getMessage());
        }
    }

    public String getOriginServer(java.util.UUID uuid) {
        return playerOriginServer.getOrDefault(uuid, null);
    }

    public void clearOriginServer(java.util.UUID uuid) {
        playerOriginServer.remove(uuid);
    }

    /** Forward un message JSON vers un serveur cible via BungeeCord. */
    private void sendForward(Player carrier, String target, String channel, String payload) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("Forward");
            out.writeUTF(target);
            out.writeUTF(channel);
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            out.writeShort(data.length);
            out.write(data);
            carrier.sendPluginMessage(plugin, CHANNEL_BUNGEE, baos.toByteArray());
        } catch (IOException e) {
            log.warning("[Bridge] Erreur forward: " + e.getMessage());
        }
    }

    static Map<String, String> parseJson(String json) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}"))   json = json.substring(0, json.length() - 1);
        boolean inStr = false; int depth = 0;
        StringBuilder cur = new StringBuilder();
        List<String> pairs = new ArrayList<>();
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
        try {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_LOLMC);
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_BUNGEE);
        } catch (Exception ignored) {}
    }
}
