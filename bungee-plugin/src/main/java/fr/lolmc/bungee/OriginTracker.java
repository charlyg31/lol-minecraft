package fr.lolmc.bungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Suivi du serveur d'origine de chaque joueur.
 *
 * Quand un joueur change de serveur vers le lobby :
 *   - On mémorise le serveur depuis lequel il venait
 *   - On l'envoie au plugin lobby via PluginMessage (canal lolmc:bridge)
 *
 * Format du message envoyé au lobby :
 *   JSON : {"type":"PLAYER_ORIGIN","uuid":"...","server":"survie"}
 *
 * Le lobby-plugin reçoit ce message et stocke le serveur d'origine
 * dans LobbyBridge.playerOriginServer pour l'inclure ensuite dans
 * les données envoyées au serveur de jeu.
 */
public class OriginTracker {

    public static final String CHANNEL = "lolmc:bridge";

    // Serveur précédent de chaque joueur (avant d'aller sur le lobby)
    private final Map<UUID, String> previousServer = new ConcurrentHashMap<>();

    private final LolBungeePlugin plugin;
    private final Logger log;
    private final String lobbyServerName;

    public OriginTracker(LolBungeePlugin plugin) {
        this.plugin          = plugin;
        this.log             = plugin.getLogger();
        this.lobbyServerName = plugin.getConfig().getString("lobby-server", "lobby");
    }

    /**
     * Appelé par ServerSwitchListener quand un joueur change de serveur.
     *
     * @param player     Le joueur qui change de serveur
     * @param fromServer Serveur de départ (null si première connexion)
     * @param toServer   Serveur d'arrivée
     */
    public void onServerSwitch(ProxiedPlayer player, String fromServer, String toServer) {
        if (fromServer == null) return; // première connexion → pas de "depuis"

        // Le joueur rejoint le lobby depuis un autre serveur
        if (lobbyServerName.equals(toServer) && !lobbyServerName.equals(fromServer)) {
            previousServer.put(player.getUniqueId(), fromServer);
            log.info("[OriginTracker] " + player.getName()
                + " : " + fromServer + " → " + toServer
                + " (origine sauvegardée : " + fromServer + ")");

            // Envoyer l'info au plugin lobby via PluginMessage
            sendOriginToLobby(player, fromServer);
        }

        // Le joueur quitte le lobby → nettoyer (il repart sur son serveur d'origine)
        if (lobbyServerName.equals(fromServer) && !lobbyServerName.equals(toServer)) {
            previousServer.remove(player.getUniqueId());
        }
    }

    /**
     * Envoie le serveur d'origine au plugin lobby via PluginMessage.
     * Le lobby-plugin reçoit ce message sur le canal lolmc:bridge.
     */
    private void sendOriginToLobby(ProxiedPlayer player, String originServer) {
        String json = "{\"type\":\"PLAYER_ORIGIN\","
                    + "\"uuid\":\"" + player.getUniqueId() + "\","
                    + "\"server\":\"" + originServer + "\"}";

        byte[] data = json.getBytes(StandardCharsets.UTF_8);

        // Envoyer via le canal PluginMessage au serveur lobby
        var lobbyServer = plugin.getProxy().getServerInfo(lobbyServerName);
        if (lobbyServer == null) {
            log.warning("[OriginTracker] Serveur lobby '" + lobbyServerName + "' introuvable dans BungeeCord.");
            return;
        }

        // Le joueur est maintenant sur le lobby → on peut lui envoyer le message
        // via son channel (le canal est bidirectionnel entre proxy et serveur)
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream(baos)) {
                out.writeUTF("Forward");
                out.writeUTF(lobbyServerName);
                out.writeUTF(CHANNEL);
                out.writeShort(data.length);
                out.write(data);
                // Envoyer via le joueur (il est maintenant sur le lobby)
                player.sendData(CHANNEL, baos.toByteArray());
            } catch (IOException e) {
                log.warning("[OriginTracker] Erreur envoi origine: " + e.getMessage());
            }
        });
    }

    public String getPreviousServer(UUID uuid) { return previousServer.get(uuid); }
    public void   cleanup(UUID uuid)           { previousServer.remove(uuid); }
}
