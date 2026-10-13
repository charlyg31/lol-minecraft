package fr.lolmc.bungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Suivi du serveur d'origine de chaque joueur (où le renvoyer après la partie).
 *
 * L'origine est le dernier serveur, autre que le lobby et le serveur de jeu, sur lequel le
 * joueur se trouvait avant d'être envoyé en partie. Elle est conservée jusqu'à son retour
 * (fin de partie) ou sa déconnexion du proxy.
 *
 * Quand un joueur arrive sur le lobby, l'origine est aussi envoyée au plugin lobby
 * via PluginMessage (canal lolmc:bridge).
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
    private final String gameServerName;

    public OriginTracker(LolBungeePlugin plugin) {
        this.plugin          = plugin;
        this.log             = plugin.getLogger();
        this.lobbyServerName = plugin.getConfig().getString("lobby-server", "lobby");
        this.gameServerName  = plugin.getConfig().getString("game-server", "lolmc-01");
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
        boolean fromHub = lobbyServerName.equals(fromServer) || gameServerName.equals(fromServer);

        // Départ d'un serveur « normal » (survie, skyblock…) → c'est l'origine à mémoriser
        if (!fromHub) {
            previousServer.put(player.getUniqueId(), fromServer);
            log.info("[OriginTracker] " + player.getName() + " : " + fromServer + " → " + toServer
                + " (origine sauvegardée : " + fromServer + ")");
        }

        // Arrivée sur le lobby : transmettre l'origine connue au plugin lobby
        if (lobbyServerName.equals(toServer)) {
            String origin = previousServer.get(player.getUniqueId());
            if (origin != null) sendOriginToLobby(player, origin);
        }

        // Retour sur un serveur « normal » après la partie → origine consommée
        if (!lobbyServerName.equals(toServer) && !gameServerName.equals(toServer)) {
            if (fromHub) previousServer.remove(player.getUniqueId());
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

        // Envoi direct au serveur lobby (ProxiedPlayer#sendData écrirait vers le CLIENT)
        var lobbyServer = plugin.getProxy().getServerInfo(lobbyServerName);
        if (lobbyServer == null) {
            log.warning("[OriginTracker] Serveur lobby '" + lobbyServerName + "' introuvable dans BungeeCord.");
            return;
        }
        lobbyServer.sendData(CHANNEL, data, true);
    }

    public String getPreviousServer(UUID uuid) { return previousServer.get(uuid); }
    public void   cleanup(UUID uuid)           { previousServer.remove(uuid); }
}
