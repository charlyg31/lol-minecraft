package fr.lolmc.bungee;

import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

/**
 * Écoute les changements de serveur BungeeCord.
 *
 * ServerConnectedEvent : déclenché quand un joueur est connecté à un serveur.
 *   → player.getServer() = nouveau serveur
 *   → event.getPreviousServer() = serveur précédent (null si premier login)
 */
public class ServerSwitchListener implements Listener {

    private final LolBungeePlugin plugin;
    private final OriginTracker   tracker;

    public ServerSwitchListener(LolBungeePlugin plugin, OriginTracker tracker) {
        this.plugin  = plugin;
        this.tracker = tracker;
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent e) {
        String fromServer = (e.getPreviousServer() != null)
            ? e.getPreviousServer().getInfo().getName()
            : null;
        String toServer = e.getServer().getInfo().getName();

        tracker.onServerSwitch(e.getPlayer(), fromServer, toServer);
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent e) {
        // Nettoyer si le joueur se déconnecte complètement
        tracker.cleanup(e.getPlayer().getUniqueId());
    }
}
