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

    private final OriginTracker   tracker;
    private final LolBungeePlugin plugin;

    public ServerSwitchListener(LolBungeePlugin plugin, OriginTracker tracker) {
        this.tracker = tracker;
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent e) {
        // Le serveur actuel du joueur AVANT la connexion
        // (e.getPlayer().getServer() retourne l'ancien serveur avant la complétion)
        String fromServer = null;
        try {
            var current = e.getPlayer().getServer();
            if (current != null) fromServer = current.getInfo().getName();
        } catch (Exception ignored) {}
        String toServer = e.getServer().getInfo().getName();

        tracker.onServerSwitch(e.getPlayer(), fromServer, toServer);
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent e) {
        // Nettoyer si le joueur se déconnecte complètement : origine, file et groupe
        // (avant : un chef déconnecté gardait son groupe et les joueurs restaient en file)
        tracker.cleanup(e.getPlayer().getUniqueId());
        plugin.getQueueManager().leave(e.getPlayer());
        plugin.getPartyManager().cleanup(e.getPlayer().getUniqueId());
    }
}
