package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.RoleQueueManager.Role;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Menu d'avant-partie (lobby principal) :
 *  - Choix des rôles préférés (au moins 2)
 *  - Accès à l'éditeur de runes
 *  - Bouton pour rejoindre la file d'attente
 */
public class PreGameGUI implements Listener {

    private static final String TITLE = "§6Préparation de partie";

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        // Les 5 rôles (slots 1-5)
        Role[] roles = Role.values();
        var selected = LolPlugin.getInstance().getRoleQueueManager().getPreferredRoles(player.getUniqueId());
        for (int i = 0; i < roles.length; i++) {
            inv.setItem(1 + i, roleIcon(roles[i], selected.contains(roles[i])));
        }

        // Bouton runes (slot 11)
        inv.setItem(11, button(Material.ENCHANTED_BOOK, "§dConfigurer mes Runes",
                "Ouvre l'éditeur de pages de runes"));

        // Bouton sorts (slot 13)
        inv.setItem(13, button(Material.BLAZE_POWDER, "§eSorts d'invocateur",
                "Configure tes 2 sorts d'invocateur"));

        // Bouton rejoindre la file (slot 15)
        int roleCount = selected.size();
        inv.setItem(15, button(
                roleCount >= 2 ? Material.LIME_DYE : Material.GRAY_DYE,
                roleCount >= 2 ? "§aRejoindre la file" : "§7Choisis 2 rôles d'abord",
                "Rôles choisis : " + roleCount + "/2 minimum"));

        player.openInventory(inv);
    }

    private ItemStack roleIcon(Role role, boolean selected) {
        Material mat = switch (role) {
            case TOP -> Material.IRON_CHESTPLATE;
            case JUNGLE -> Material.OAK_SAPLING;
            case MID -> Material.BLAZE_ROD;
            case ADC -> Material.BOW;
            case SUPPORT -> Material.SHIELD;
        };
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text((selected ? "✔ " : "") + role.displayName,
                    selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(
                    selected ? "Clique pour retirer" : "Clique pour ajouter", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            if (selected) {
                meta.addEnchant(fr.lolmc.util.Compat.glowEnchant(), 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        int slot = e.getSlot();
        var rqm = LolPlugin.getInstance().getRoleQueueManager();

        // Rôles (slots 1-5)
        if (slot >= 1 && slot <= 5) {
            Role role = Role.values()[slot - 1];
            rqm.toggleRole(player, role);
            open(player); // rafraîchir
            return;
        }
        // Bouton runes
        if (slot == 11) {
            player.closeInventory();
            LolPlugin.getInstance().getRuneGUI().open(player);
            return;
        }
        // Bouton sorts
        if (slot == 13) {
            player.closeInventory();
            player.sendMessage(Component.text("Utilise /spell <sort1> <sort2> (ex: /spell flash ignite)",
                    NamedTextColor.AQUA));
            return;
        }
        // Bouton rejoindre la file
        if (slot == 15) {
            player.closeInventory();
            rqm.joinQueue(player);
            return;
        }
    }

    private ItemStack button(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }
}
