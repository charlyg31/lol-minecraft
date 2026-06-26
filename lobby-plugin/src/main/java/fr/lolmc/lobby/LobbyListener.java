package fr.lolmc.lobby;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

/**
 * Événements joueurs dans le lobby :
 *  - Connexion : message de bienvenue + item "Menu LoL" dans la main
 *  - Clic droit avec l'item → ouvre le menu principal
 *  - Déconnexion : nettoyage queue/party
 */
public class LobbyListener implements Listener {

    static final String MENU_ITEM_NAME = "§6§l⚔ Menu LoL";
    private final LobbyPlugin plugin;

    public LobbyListener(LobbyPlugin p) { this.plugin = p; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var p = e.getPlayer();

        // Message de bienvenue
        p.sendMessage(Component.text("⚔ Bienvenue dans le lobby LoL!", NamedTextColor.GOLD));
        p.sendMessage(Component.text("Utilise /lol ou clique droit avec l'item pour jouer.",
            NamedTextColor.GRAY));

        // Donner l'item de menu dans la main si pas déjà là
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> giveMenuItem(p), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        var p = e.getPlayer();
        plugin.getQueueManager().leave(p);
        plugin.getPartyManager().cleanup(p.getUniqueId());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        var p = e.getPlayer();
        var action = e.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
         && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack held = p.getInventory().getItemInMainHand();
        if (!isMenuItem(held)) return;

        e.setCancelled(true);
        LobbyMainMenu.openMain(p);
    }

    /** Donne l'item de menu dans le slot 4 (milieu de la hotbar). */
    static void giveMenuItem(org.bukkit.entity.Player p) {
        // Vérifier si l'item est déjà dans l'inventaire
        for (ItemStack is : p.getInventory().getContents()) {
            if (isMenuItem(is)) return;
        }
        ItemStack menu = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = menu.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(MENU_ITEM_NAME)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("§7Clic droit → Menu LoL")
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("§8Rôle, Runes, File, Groupe")
                    .decoration(TextDecoration.ITALIC, false)
            ));
            menu.setItemMeta(meta);
        }
        p.getInventory().setItem(4, menu);
    }

    static boolean isMenuItem(ItemStack is) {
        if (is == null || is.getType() != Material.NETHER_STAR) return false;
        ItemMeta m = is.getItemMeta();
        if (m == null || !m.hasDisplayName()) return false;
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(m.displayName());
        return name.contains("Menu LoL");
    }
}
