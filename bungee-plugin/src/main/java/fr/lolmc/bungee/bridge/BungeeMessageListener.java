package fr.lolmc.bungee.bridge;

import fr.lolmc.bungee.LolBungeePlugin;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

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
        // Jamais relayé (ni vers le client, ni vers le serveur) : seul le proxy traite ce canal
        e.setCancelled(true);
        // Un client moddé peut envoyer n'importe quel message sur ce canal : on n'accepte
        // que ceux qui viennent d'un serveur du réseau.
        if (!(e.getSender() instanceof Server source)) return;

        String json = new String(e.getData(), StandardCharsets.UTF_8);
        Map<String, String> data = parseJson(json);
        String type = data.getOrDefault("type", "");
        String sourceName = source.getInfo().getName();

        switch (type) {
            case "GAME_END" -> {
                // Seul le serveur de jeu peut renvoyer des joueurs vers leur origine
                String gameServer = plugin.getConfig().getString("game-server", "lolmc-01");
                if (gameServer.equals(sourceName)) handleGameEnd(data, source);
            }
            default -> {
                // Actions joueur (menu /lol des serveurs Spigot) : le joueur visé doit être
                // connecté au serveur qui envoie le message.
                ProxiedPlayer p = getPlayer(data);
                if (p == null || p.getServer() == null
                        || !p.getServer().getInfo().getName().equals(sourceName)) return;
                switch (type) {
                    case "QUEUE_JOIN"   -> handleQueueJoin(p, data);
                    case "QUEUE_LEAVE"  -> plugin.getQueueManager().leave(p);
                    case "SET_ROLE"     -> handleSetRole(p, data);
                    case "PARTY_INVITE" -> handlePartyInvite(p, data);
                    case "PARTY_ACCEPT" -> plugin.getPartyManager().accept(p);
                    case "PARTY_LEAVE"  -> plugin.getPartyManager().leave(p);
                    default -> { }
                }
            }
        }
    }

    private void handleGameEnd(Map<String, String> d, Server source) {
        // Le serveur de jeu signale la fin de partie
        // Renvoyer chaque joueur vers son serveur d'origine
        String playersStr = d.getOrDefault("players", "");
        if (playersStr.isEmpty()) return;
        for (String uuidStr : playersStr.split(",")) {
            try {
                UUID uuid = UUID.fromString(uuidStr.trim());
                ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
                // Uniquement les joueurs encore sur le serveur de jeu
                if (p == null || p.getServer() == null
                        || !p.getServer().getInfo().equals(source.getInfo())) continue;
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

    private void handleQueueJoin(ProxiedPlayer p, Map<String, String> d) {
        boolean ranked = "true".equalsIgnoreCase(d.getOrDefault("ranked", "false"));
        plugin.getQueueManager().join(p, ranked);
    }

    private void handleSetRole(ProxiedPlayer p, Map<String, String> d) {
        String role = d.get("role");
        if (role != null) {
            var roles = java.util.List.of(role.toUpperCase(java.util.Locale.ROOT).split(","));
            plugin.getRoleManager().setRoles(p.getUniqueId(), roles);
            p.sendMessage(new TextComponent("§a✔ Rôles : §l" + role));
        }
    }

    private void handlePartyInvite(ProxiedPlayer leader, Map<String, String> d) {
        String targetName = d.get("target");
        if (targetName == null) return;
        ProxiedPlayer target = ProxyServer.getInstance().getPlayer(targetName);
        if (target == null) { leader.sendMessage(new TextComponent("§cJoueur introuvable.")); return; }
        plugin.getPartyManager().invite(leader, target);
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
                String k = kv[0].trim().replace("\"", "");
                String v = kv[1].trim().replace("\"", "");
                map.put(k, v);
            }
        }
        return map;
    }
}
