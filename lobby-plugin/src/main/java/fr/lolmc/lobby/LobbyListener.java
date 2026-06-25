package fr.lolmc.lobby;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Gère les connexions/déconnexions dans le lobby. */
public class LobbyListener implements Listener {

    private final LobbyPlugin plugin;

    public LobbyListener(LobbyPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Message de bienvenue + ouverture du menu
        var p = e.getPlayer();
        p.sendMessage(net.kyori.adventure.text.Component.text(
            "⚔ Bienvenue dans le lobby LoL! Tape /lol pour jouer.",
            net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        var p = e.getPlayer();
        plugin.getQueueManager().leave(p);
        plugin.getPartyManager().cleanup(p.getUniqueId());
    }
}
