package fr.lolmc.shop;

import fr.lolmc.item.ItemRegistry;
import fr.lolmc.item.LolItem;
import fr.lolmc.item.consumable.ConsumableManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * GUI de la boutique LoL.
 * 6 rangées de 9 = 54 slots
 *
 * Disposition:
 *   Rangée 0 (slots 0-8) : onglets catégories + bouton vendre
 *   Rangées 1-5 (slots 9-53) : items de la catégorie sélectionnée
 */
public class ShopGUI {

    public static final String TITLE_PREFIX = "§6🏪 Boutique — ";

    // Catégorie par défaut affichée à l'ouverture
    private static final LolItem.ItemCategory DEFAULT_CAT = LolItem.ItemCategory.DAMAGE;

    // Slots des onglets
    private static final int[] TAB_SLOTS   = {0, 1, 2, 3, 4, 5, 8};
    private static final int   SELL_SLOT   = 8;
    private static final int   BACK_SLOT   = 7;
    private static final int   INFO_SLOT   = 6;
    private static final int   ITEMS_START = 9;

    // Mapping slot → item ID (pour chaque joueur)
    private final Map<UUID, Map<Integer, String>> slotToItemId = new HashMap<>();
    // Catégorie actuelle par joueur
    private final Map<UUID, LolItem.ItemCategory> currentCategory = new HashMap<>();

    // ── Ouvrir la boutique ────────────────────────────────────────

    public void open(Player player) {
        open(player, DEFAULT_CAT);
    }

    public void open(Player player, LolItem.ItemCategory cat) {
        currentCategory.put(player.getUniqueId(), cat);
        String title = TITLE_PREFIX + getCategoryLabel(cat);
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(title));

        // Remplir les onglets
        buildTabs(inv, cat);

        // Remplir les items
        Map<Integer, String> mapping = new HashMap<>();
        List<LolItem> items = ItemRegistry.byCategory(cat);
        int slot = ITEMS_START;
        for (LolItem item : items) {
            if (slot >= 54) break;
            inv.setItem(slot, item.buildItemStack());
            mapping.put(slot, item.getId());
            slot++;
        }
        slotToItemId.put(player.getUniqueId(), mapping);

        // Remplir les cases vides
        ItemStack filler = filler();
        for (int i = ITEMS_START; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        // Bouton info
        inv.setItem(INFO_SLOT, infoButton(player));
        // Bouton vendre
        inv.setItem(SELL_SLOT, sellButton());

        player.openInventory(inv);
    }

    // ── Onglets ───────────────────────────────────────────────────

    private void buildTabs(Inventory inv, LolItem.ItemCategory current) {
        LolItem.ItemCategory[] cats = LolItem.ItemCategory.values();
        int[] tabSlots = {0, 1, 2, 3, 4, 5};
        for (int i = 0; i < Math.min(cats.length, tabSlots.length); i++) {
            inv.setItem(tabSlots[i], tabButton(cats[i], cats[i] == current));
        }
    }

    private ItemStack tabButton(LolItem.ItemCategory cat, boolean selected) {
        Material mat = switch (cat) {
            case DAMAGE       -> Material.NETHERITE_SWORD;
            case MAGE         -> Material.BLAZE_POWDER;
            case TANK         -> Material.IRON_CHESTPLATE;
            case ATTACK_SPEED -> Material.BOW;
            case SUPPORT      -> Material.GOLDEN_APPLE;
            case UTILITY      -> Material.COMPASS;
            case CONSUMABLE   -> Material.POTION;
        };
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        NamedTextColor color = selected ? NamedTextColor.GOLD : NamedTextColor.GRAY;
        meta.displayName(Component.text(
                (selected ? "▶ " : "") + getCategoryLabel(cat),
                color).decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, selected));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoButton(Player player) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("ℹ Mon inventaire", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Clique sur un item pour l'acheter.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Clique sur le slot [Vendre] pour vendre.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack sellButton() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("💰 Vendre un item (70%)", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Ferme la boutique, puis clique", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("droit sur l'item à vendre.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filler() {
        ItemStack f = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = f.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            f.setItemMeta(m);
        }
        return f;
    }

    // ── Gestion des clics ─────────────────────────────────────────

    /**
     * Retourne l'item ID cliqué, ou null si c'est un onglet/filler.
     */
    public String getClickedItemId(Player player, int slot) {
        Map<Integer, String> mapping = slotToItemId.get(player.getUniqueId());
        return mapping != null ? mapping.get(slot) : null;
    }

    /**
     * Retourne la catégorie de l'onglet cliqué, ou null.
     */
    public LolItem.ItemCategory getClickedCategory(int slot) {
        LolItem.ItemCategory[] cats = LolItem.ItemCategory.values();
        int[] tabSlots = {0, 1, 2, 3, 4, 5};
        for (int i = 0; i < tabSlots.length; i++) {
            if (tabSlots[i] == slot && i < cats.length) return cats[i];
        }
        return null;
    }

    public boolean isSellSlot(int slot) { return slot == SELL_SLOT; }

    public LolItem.ItemCategory getCurrentCategory(Player player) {
        return currentCategory.getOrDefault(player.getUniqueId(), DEFAULT_CAT);
    }

    public void cleanup(Player player) {
        slotToItemId.remove(player.getUniqueId());
        currentCategory.remove(player.getUniqueId());
    }

    // ── Helpers ───────────────────────────────────────────────────

    public static boolean isShopInventory(Component title) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(title);
        return plain.startsWith("🏪 Boutique");
    }

    private String getCategoryLabel(LolItem.ItemCategory cat) {
        return switch (cat) {
            case DAMAGE       -> "⚔ Attaque";
            case MAGE         -> "✨ Magie";
            case TANK         -> "🛡 Tank";
            case ATTACK_SPEED -> "⚡ Vitesse";
            case SUPPORT      -> "💚 Support";
            case UTILITY      -> "🧰 Utilitaire";
            case CONSUMABLE   -> "🧪 Consommables";
        };
    }
}
