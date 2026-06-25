package fr.lolmc.lobby;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Routage des clics dans les GUIs du lobby. */
public class LobbyGUIListener implements Listener {

    private final LobbyPlugin plugin;
    public LobbyGUIListener(LobbyPlugin p) { this.plugin = p; }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof org.bukkit.entity.Player p)) return;
        var title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(e.getView().title());
        if (title.startsWith("LoL — ")) e.setCancelled(true);
        // TODO: router vers LobbyRuneGUI, LobbySpellGUI selon le titre
    }
}
