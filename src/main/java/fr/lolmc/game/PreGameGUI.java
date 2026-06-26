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

    // CORRECTION : Le titre passe en Component moderne (GOLD remplace §6)[cite: 10]
    private static final Component TITLE = Component.text("Préparation de partie", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false);

    public void open(Player player) {
        // Appelle la méthode Bukkit native acceptant un Component[cite: 10]
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        // Les 5 rôles (slots 1-5)[cite: 10]
        Role[] roles = Role.values();
        var selected = LolPlugin.getInstance().getRoleQueueManager().getPreferredRoles(player.getUniqueId());
        for (int i = 0; i < roles.length; i++) {
            inv.setItem(1 + i, roleIcon(roles[i], selected.contains(roles[i])));
        }

        // Bouton runes (slot 11) - Passage en Component (LIGHT_PURPLE remplace §d)[cite: 10]
        inv.setItem(11, button(Material.ENCHANTED_BOOK, Component.text("Configurer mes Runes", NamedTextColor.LIGHT_PURPLE),
                "Ouvre l'éditeur de pages de runes"));

        // Bouton sorts (slot 13) - Passage en Component (YELLOW remplace §e)[cite: 10]
        inv.setItem(13, button(Material.BLAZE_POWDER, Component.text("Sorts d'invocateur", NamedTextColor.YELLOW),
                "Configure tes 2 sorts d'invocateur"));

        // Bouton rejoindre la file (slot 15)[cite: 10]
        int roleCount = selected.size();
        boolean canJoin = roleCount >= 2;
        Component buttonName = canJoin
                ? Component.text("Rejoindre la file", NamedTextColor.GREEN)
                : Component.text("Choisis 2 rôles d'abord", NamedTextColor.GRAY);

        inv.setItem(15, button(
                canJoin ? Material.LIME_DYE : Material.GRAY_DYE,
                buttonName,
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
        // CORRECTION : Comparaison via e.getView().title() qui retourne un Component[cite: 10]
        if (!TITLE.equals(e.getView().title())) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        int slot = e.getSlot();
        var rqm = LolPlugin.getInstance().getRoleQueueManager();

        // Rôles (slots 1-5)[cite: 10]
        if (slot >= 1 && slot <= 5) {
            Role role = Role.values()[slot - 1];
            rqm.toggleRole(player, role);
            open(player); // rafraîchir
            return;
        }
        // Bouton runes[cite: 10]
        if (slot == 11) {
            player.closeInventory();
            LolPlugin.getInstance().getRuneGUI().open(player);
            return;
        }
        // Bouton sorts[cite: 10]
        if (slot == 13) {
            player.closeInventory();
            player.sendMessage(Component.text("Utilise /spell <sort1> <sort2> (ex: /spell flash ignite)",
                    NamedTextColor.AQUA));
            return;
        }
        // Bouton rejoindre la file[cite: 10]
        if (slot == 15) {
            player.closeInventory();
            rqm.joinQueue(player);
            return;
        }
    }

    // CORRECTION : Signature adaptée pour consommer directement un Component[cite: 10]
    private ItemStack button(Material mat, Component modernName, String lore) {
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(modernName.decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Lance la séquence complète : ban → pick → démarrage. */
    public void startFullSequence() {
        var csm = LolPlugin.getInstance().getChampSelectManager();
        csm.startBanPhase();
    }
}
