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
    public void onJoin(PlayerJoinEvent e) {}

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        var p = e.getPlayer();
        plugin.getQueueManager().leave(p);
        plugin.getPartyManager().cleanup(p.getUniqueId());
    }
}
