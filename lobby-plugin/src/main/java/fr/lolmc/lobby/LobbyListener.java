package fr.lolmc.lobby;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Événements joueurs dans le lobby.
 */
public class LobbyListener implements Listener {

    private final LobbyPlugin plugin;

    public LobbyListener(LobbyPlugin p) { this.plugin = p; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Demander à BungeeCord le nom du serveur d'origine du joueur
        // (réponse async, stockée dans LobbyBridge.playerOriginServer)
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            var bridge = plugin.getBridge();
            if (bridge != null) bridge.requestCurrentServer(e.getPlayer());
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        var p = e.getPlayer();
        plugin.getQueueManager().leave(p);
        plugin.getPartyManager().cleanup(p.getUniqueId());
    }
}
