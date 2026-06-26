package fr.lolmc.bungee.bridge;

import fr.lolmc.bungee.LolBungeePlugin;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reçoit les messages du serveur de jeu (fin de partie, kick, etc.)
 * et des serveurs Spigot (actions du menu /lol).
 *
 * Format JSON :
 *   {"type":"ACTION", ...}
 *
 * Types reçus depuis le serveur de jeu :
 *   GAME_END — partie terminée, renvoyer les joueurs à leur origine
 *
 * Types reçus depuis les serveurs Spigot (menu /lol) :
 *   QUEUE_JOIN   — joueur veut rejoindre la file
 *   QUEUE_LEAVE  — joueur veut quitter la file
 *   SET_ROLE     — joueur change de rôle
 *   PARTY_INVITE — joueur invite quelqu'un
 *   PARTY_ACCEPT — joueur accepte une invitation
 *   PARTY_LEAVE  — joueur quitte le groupe
 */
public class BungeeMessageListener implements Listener {

    private final LolBungeePlugin plugin;

    public BungeeMessageListener(LolBungeePlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (!e.getTag().equals("lolmc:bridge")) return;
        e.setCancelled(true);

        // Lire le JSON
        String json;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(e.getData()))) {
            // Format BungeeCord Forward : SubChannel, serveur, longueur, données
            // Si c'est un Forward on lit différemment
            byte[] raw = e.getData();
            json = new String(raw, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return;
        }

        Map<String, String> data = parseJson(json);
        String type = data.getOrDefault("type", "");

        switch (type) {
            case "GAME_END"     -> handleGameEnd(data);
            case "QUEUE_JOIN"   -> handleQueueJoin(data);
            case "QUEUE_LEAVE"  -> handleQueueLeave(data);
            case "SET_ROLE"     -> handleSetRole(data);
            case "PARTY_INVITE" -> handlePartyInvite(data);
            case "PARTY_ACCEPT" -> handlePartyAccept(data);
            case "PARTY_LEAVE"  -> handlePartyLeave(data);
        }
    }

    private void handleGameEnd(Map<String, String> d) {
        // Le serveur de jeu signale la fin de partie
        // Renvoyer chaque joueur vers son serveur d'origine
        String playersStr = d.getOrDefault("players", "");
        if (playersStr.isEmpty()) return;
        for (String uuidStr : playersStr.split(",")) {
            try {
                UUID uuid = UUID.fromString(uuidStr.trim());
                ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
                if (p == null) continue;
                String origin = plugin.getOriginTracker().getPreviousServer(uuid);
                if (origin != null) {
                    var srv = ProxyServer.getInstance().getServerInfo(origin);
                    if (srv != null) { p.connect(srv); continue; }
                }
                // Fallback : serveur par défaut
                String fallback = plugin.getConfig().getString("fallback-server", "survie");
                var fallbackSrv = ProxyServer.getInstance().getServerInfo(fallback);
                if (fallbackSrv != null) p.connect(fallbackSrv);
            } catch (Exception ignored) {}
        }
    }

    private void handleQueueJoin(Map<String, String> d) {
        ProxiedPlayer p = getPlayer(d);
        if (p != null) plugin.getQueueManager().join(p);
    }

    private void handleQueueLeave(Map<String, String> d) {
        ProxiedPlayer p = getPlayer(d);
        if (p != null) plugin.getQueueManager().leave(p);
    }

    private void handleSetRole(Map<String, String> d) {
        ProxiedPlayer p = getPlayer(d);
        if (p == null) return;
        String role = d.get("role");
        if (role != null) {
            var roles = java.util.List.of(role.split(","));
            plugin.getRoleManager().setRoles(p.getUniqueId(), roles);
            p.sendMessage(new TextComponent("§a✔ Rôles : §l" + role));
        }
    }

    private void handlePartyInvite(Map<String, String> d) {
        ProxiedPlayer leader = getPlayer(d);
        String targetName = d.get("target");
        if (leader == null || targetName == null) return;
        ProxiedPlayer target = ProxyServer.getInstance().getPlayer(targetName);
        if (target == null) { leader.sendMessage(new TextComponent("§cJoueur introuvable.")); return; }
        plugin.getPartyManager().invite(leader, target);
    }

    private void handlePartyAccept(Map<String, String> d) {
        ProxiedPlayer p = getPlayer(d);
        if (p != null) plugin.getPartyManager().accept(p);
    }

    private void handlePartyLeave(Map<String, String> d) {
        ProxiedPlayer p = getPlayer(d);
        if (p != null) plugin.getPartyManager().leave(p);
    }

    private ProxiedPlayer getPlayer(Map<String, String> d) {
        try {
            return ProxyServer.getInstance().getPlayer(UUID.fromString(d.get("uuid")));
        } catch (Exception e) { return null; }
    }

    private Map<String, String> parseJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}"))   json = json.substring(0, json.length() - 1);
        for (String pair : json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().replaceAll("\"", "");
                String v = kv[1].trim().replaceAll("\"", "");
                map.put(k, v);
            }
        }
        return map;
    }
}
