package fr.lolmc.bungee.queue;

import fr.lolmc.bungee.LolBungeePlugin;
import fr.lolmc.bungee.party.BungeePartyManager;
import fr.lolmc.bungee.role.BungeeRoleManager;
import fr.lolmc.bungee.rune.BungeeRuneManager;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * File d'attente cross-serveur gérée depuis le proxy.
 *
 * Quand N joueurs sont en file :
 *   1. Construire le JSON des données de chaque joueur (runes, rôle, sort, groupe)
 *   2. Envoyer le JSON au serveur de jeu via PluginMessage
 *   3. Attendre 2s (le serveur de jeu stocke les données)
 *   4. Connecter les joueurs au serveur de jeu via BungeeCord Connect
 */
public class BungeeQueueManager {

    private final LolBungeePlugin plugin;
    private final BungeePartyManager partyManager;
    private final BungeeRuneManager  runeManager;
    private final BungeeRoleManager  roleManager;

    private final Deque<UUID> queue = new ConcurrentLinkedDeque<>();
    private final int playersNeeded;
    private final String gameServer;

    public BungeeQueueManager(LolBungeePlugin plugin, BungeePartyManager pm,
                               BungeeRuneManager rm, BungeeRoleManager rlm) {
        this.plugin        = plugin;
        this.partyManager  = pm;
        this.runeManager   = rm;
        this.roleManager   = rlm;
        this.playersNeeded = plugin.getConfig().getInt("players-per-game", 10);
        this.gameServer    = plugin.getConfig().getString("game-server", "lolmc-01");
    }

    public void join(ProxiedPlayer player) {
        if (isInQueue(player.getUniqueId())) return;
        List<UUID> group = partyManager.getPartyMembers(player.getUniqueId());
        if (queue.size() + group.size() > playersNeeded) return;
        for (UUID uid : group) {
            if (!queue.contains(uid)) queue.add(uid);
        }
        // Message uniquement au joueur qui a fait la commande
        player.sendMessage(new TextComponent("§aVous êtes dans la file d'attente."));
        checkAndStartGame();
    }

    public void leave(ProxiedPlayer player) {
        queue.remove(player.getUniqueId());
    }

    public boolean isInQueue(UUID uuid) { return queue.contains(uuid); }
    public int getQueueSize()           { return queue.size(); }
    public int getPlayersNeeded()       { return playersNeeded; }

    private void checkAndStartGame() {
        if (queue.size() < playersNeeded) return;

        // Prendre les N premiers joueurs
        List<UUID> gamePlayers = new ArrayList<>();
        Iterator<UUID> it = queue.iterator();
        for (int i = 0; i < playersNeeded && it.hasNext(); i++) {
            gamePlayers.add(it.next()); it.remove();
        }

        // Chercher un carrier online pour envoyer les PluginMessages
        ProxiedPlayer carrier = findCarrier(gamePlayers);
        if (carrier == null) return;

        ServerInfo gameServerInfo = ProxyServer.getInstance().getServerInfo(gameServer);
        if (gameServerInfo == null) {
            plugin.getLogger().severe("Serveur de jeu '" + gameServer + "' introuvable dans BungeeCord !");
            return;
        }

        // Envoyer les données de chaque joueur au serveur de jeu
        for (UUID uid : gamePlayers) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            if (p == null) continue;
            sendPlayerData(carrier, p, gamePlayers);
        }

        // Attendre 2s puis connecter tout le monde
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            for (UUID uid : gamePlayers) {
                ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
                if (p != null) p.connect(gameServerInfo);
            }
        }, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Envoie les données d'un joueur au serveur de jeu via PluginMessage. */
    private void sendPlayerData(ProxiedPlayer carrier, ProxiedPlayer player, List<UUID> allPlayers) {
        Map<String, String> runes = runeManager.getPage(player.getUniqueId());
        List<String> roles = roleManager.getRoles(player.getUniqueId());
        String role = String.join(",", roles);
        List<UUID> party = partyManager.getPartyMembers(player.getUniqueId());
        String origin = plugin.getOriginTracker().getPreviousServer(player.getUniqueId());

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"type\":\"QUEUE_JOIN\",");
        sb.append("\"uuid\":\"").append(player.getUniqueId()).append("\",");
        sb.append("\"name\":\"").append(player.getName()).append("\",");
        sb.append("\"keystone\":\"").append(runes.get("keystone")).append("\",");
        sb.append("\"minors\":\"").append(runes.get("minors")).append("\",");
        sb.append("\"spell1\":\"").append(runes.get("spell1")).append("\",");
        sb.append("\"spell2\":\"").append(runes.get("spell2")).append("\",");
        sb.append("\"role\":\"").append(role).append("\",");
        if (origin != null) sb.append("\"origin_server\":\"").append(origin).append("\",");
        sb.append("\"party\":\"");
        sb.append(String.join(",", party.stream().map(UUID::toString).toList()));
        sb.append("\"}");

        sendPluginMessage(carrier, gameServer, sb.toString());
    }

    /** Envoie un PluginMessage vers un serveur via BungeeCord Forward. */
    private void sendPluginMessage(ProxiedPlayer carrier, String targetServer, String json) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("Forward");
            out.writeUTF(targetServer);
            out.writeUTF("lolmc:bridge");
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            out.writeShort(data.length);
            out.write(data);
            carrier.sendData("BungeeCord", baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Erreur PluginMessage: " + e.getMessage());
        }
    }

    private ProxiedPlayer findCarrier(List<UUID> players) {
        for (UUID uid : players) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            if (p != null) return p;
        }
        return null;
    }
}
