package fr.lolmc.shop;

import fr.lolmc.item.ItemRegistry;
import fr.lolmc.item.LolItem;
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
 * Boutique LoL — deux vues :
 *   • PARCOURS : onglets de catégories + grille d'items (clic = ouvre la fiche).
 *   • FICHE (arborescence) : l'item, ses composants (recette), ce qu'il construit,
 *     et un bouton Acheter. On peut cliquer un composant pour naviguer dans l'arbre.
 */
public class ShopGUI {

    public static final String BROWSE_PREFIX = "🏪 Boutique — ";
    public static final String DETAIL_PREFIX = "🧬 Recette — ";

    private static final LolItem.ItemCategory DEFAULT_CAT = LolItem.ItemCategory.DAMAGE;

    // ── PARCOURS ──
    private static final int[] TAB_SLOTS   = {0, 1, 2, 3, 4, 5, 6};
    private static final int   ITEMS_START = 9;
    private static final int   GOLD_SLOT_B = 8;

    // ── FICHE ──
    private static final int   D_ITEM   = 4;   // l'item affiché
    private static final int   D_BACK   = 0;   // retour
    private static final int   D_GOLD   = 8;   // or
    private static final int   D_BUY    = 49;  // acheter
    private static final int[] D_COMPONENTS = {20, 21, 22, 23, 24}; // recette (jusqu'à 5)
    private static final int[] D_BUILDS    = {37, 38, 39, 40, 41, 42, 43}; // construit en (jusqu'à 7)

    // État par joueur
    private final Map<UUID, Map<Integer, String>> slotToItemId = new HashMap<>(); // parcours
    private final Map<UUID, LolItem.ItemCategory> currentCategory = new HashMap<>();
    private final Map<UUID, String> detailItem = new HashMap<>();                  // fiche ouverte
    private final Map<UUID, Map<Integer, String>> detailNav = new HashMap<>();     // slots navigables de la fiche

    // ══════════════════════════════════════════════════════════════
    // VUE PARCOURS
    // ══════════════════════════════════════════════════════════════

    public void open(Player player) { open(player, getCurrentCategory(player)); }

    public void open(Player player, LolItem.ItemCategory cat) {
        detailItem.remove(player.getUniqueId());
        currentCategory.put(player.getUniqueId(), cat);
        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text(BROWSE_PREFIX + getCategoryLabel(cat), NamedTextColor.GOLD));

        // Onglets
        LolItem.ItemCategory[] cats = LolItem.ItemCategory.values();
        for (int i = 0; i < Math.min(cats.length, TAB_SLOTS.length); i++) {
            inv.setItem(TAB_SLOTS[i], tabButton(cats[i], cats[i] == cat));
        }
        inv.setItem(GOLD_SLOT_B, goldDisplay(player));

        // Items de la catégorie
        Map<Integer, String> mapping = new HashMap<>();
        int slot = ITEMS_START;
        for (LolItem item : ItemRegistry.byCategory(cat)) {
            if (slot >= 54) break;
            inv.setItem(slot, decorateBrowse(item.buildItemStack(), item));
            mapping.put(slot, item.getId());
            slot++;
        }
        slotToItemId.put(player.getUniqueId(), mapping);

        ItemStack filler = filler();
        for (int i = 0; i < 54; i++) if (inv.getItem(i) == null) inv.setItem(i, filler);

        player.openInventory(inv);
    }

    /** Ajoute un indice "clic = fiche" à l'item de la grille. */
    private ItemStack decorateBrowse(ItemStack stack, LolItem item) {
        ItemMeta m = stack.getItemMeta();
        if (m == null) return stack;
        List<Component> lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        if (!item.getBuildsFrom().isEmpty()) {
            lore.add(Component.text("🧬 " + item.getBuildsFrom().size() + " composants",
                    NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("▶ Clic : voir la recette / acheter", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        stack.setItemMeta(m);
        return stack;
    }

    // ══════════════════════════════════════════════════════════════
    // VUE FICHE (arborescence)
    // ══════════════════════════════════════════════════════════════

    public void openDetail(Player player, String itemId) {
        LolItem item = ItemRegistry.get(itemId);
        if (item == null) { open(player); return; }
        detailItem.put(player.getUniqueId(), itemId);
        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text(DETAIL_PREFIX + item.getDisplayName(), NamedTextColor.LIGHT_PURPLE));

        ItemStack filler = filler();
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // L'item au centre haut
        inv.setItem(D_ITEM, item.buildItemStack());
        inv.setItem(D_BACK, navButton(Material.ARROW, "◀ Retour", NamedTextColor.YELLOW));
        inv.setItem(D_GOLD, goldDisplay(player));

        Map<Integer, String> nav = new HashMap<>();

        // Composants (recette)
        inv.setItem(18, label("⬇ Construit à partir de :", NamedTextColor.AQUA));
        List<String> comps = item.getBuildsFrom();
        if (comps.isEmpty()) {
            inv.setItem(D_COMPONENTS[2], label("Composant de base (pas de recette)", NamedTextColor.GRAY));
        } else {
            placeRow(inv, comps, D_COMPONENTS, nav);
        }

        // Ce que l'item construit
        inv.setItem(27, label("⬆ Composant de :", NamedTextColor.GOLD));
        List<String> into = new ArrayList<>();
        for (LolItem b : ItemRegistry.buildsInto(itemId)) into.add(b.getId());
        if (into.isEmpty()) {
            inv.setItem(D_BUILDS[3], label("Item final (ne construit rien)", NamedTextColor.GRAY));
        } else {
            placeRow(inv, into, D_BUILDS, nav);
        }

        // Bouton acheter
        inv.setItem(D_BUY, buyButton(player, item));

        detailNav.put(player.getUniqueId(), nav);
        player.openInventory(inv);
    }

    /** Place une liste d'items dans une rangée de slots et les enregistre comme navigables. */
    private void placeRow(Inventory inv, List<String> ids, int[] slots, Map<Integer, String> nav) {
        for (int i = 0; i < ids.size() && i < slots.length; i++) {
            LolItem it = ItemRegistry.get(ids.get(i));
            if (it == null) continue;
            ItemStack stack = it.buildItemStack();
            ItemMeta m = stack.getItemMeta();
            if (m != null) {
                List<Component> lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(Component.text("▶ Clic : voir cet item", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
                m.lore(lore);
                stack.setItemMeta(m);
            }
            inv.setItem(slots[i], stack);
            nav.put(slots[i], it.getId());
        }
    }

    private ItemStack buyButton(Player player, LolItem item) {
        int gold = LolPluginGold(player);
        boolean afford = gold >= item.getGoldCost();
        ItemStack b = new ItemStack(afford ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK);
        ItemMeta m = b.getItemMeta();
        if (m == null) return b;
        m.displayName(Component.text((afford ? "✔ Acheter — " : "✘ ") + item.getGoldCost() + " or",
                afford ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(item.getDisplayName(), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Ton or : " + gold, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        if (!afford) lore.add(Component.text("Il te manque " + (item.getGoldCost() - gold) + " or",
                NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        b.setItemMeta(m);
        return b;
    }

    private int LolPluginGold(Player player) {
        return fr.lolmc.LolPlugin.getInstance().getGoldManager().getGold(player.getUniqueId());
    }

    // ══════════════════════════════════════════════════════════════
    // BOUTONS / HELPERS VISUELS
    // ══════════════════════════════════════════════════════════════

    private ItemStack tabButton(LolItem.ItemCategory cat, boolean selected) {
        Material mat = switch (cat) {
            case DAMAGE       -> Material.NETHERITE_SWORD;
            case MAGE         -> Material.BLAZE_ROD;
            case TANK         -> Material.IRON_CHESTPLATE;
            case ATTACK_SPEED -> Material.BOW;
            case SUPPORT      -> Material.GOLDEN_APPLE;
            case UTILITY      -> Material.COMPASS;
            case CONSUMABLE   -> Material.POTION;
        };
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text((selected ? "▶ " : "") + getCategoryLabel(cat),
                        selected ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, selected));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack goldDisplay(Player player) {
        int gold = LolPluginGold(player);
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        m.displayName(Component.text("💰 Ton or : " + gold, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(m);
        return item;
    }

    private ItemStack navButton(Material mat, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        m.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(m);
        return item;
    }

    private ItemStack label(String text, NamedTextColor color) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        m.displayName(Component.text(text, color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(m);
        return item;
    }

    private ItemStack filler() {
        ItemStack f = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = f.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            f.setItemMeta(m);
        }
        return f;
    }

    // ══════════════════════════════════════════════════════════════
    // ROUTAGE DES CLICS (lu par ShopListener)
    // ══════════════════════════════════════════════════════════════

    public boolean isDetailView(Player player) {
        if (!detailItem.containsKey(player.getUniqueId())) return false;
        // Vérifier que la vue détail est bien ouverte (titre commence par DETAIL_PREFIX)
        var view = player.getOpenInventory();
        if (view == null) return false;
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(view.title());
        return title.startsWith(DETAIL_PREFIX);
    }

    /** Item dont la fiche est ouverte (pour l'achat). */
    public String getDetailItemId(Player player) {
        return detailItem.get(player.getUniqueId());
    }

    public boolean isBackSlot(int slot)  { return slot == D_BACK; }
    public boolean isBuySlot(int slot)   { return slot == D_BUY; }

    /** Slot navigable de la fiche (composant / construit en) → item ID, ou null. */
    public String getDetailNavId(Player player, int slot) {
        Map<Integer, String> nav = detailNav.get(player.getUniqueId());
        return nav != null ? nav.get(slot) : null;
    }

    /** (Parcours) item cliqué dans la grille → ID, ou null. */
    public String getClickedItemId(Player player, int slot) {
        Map<Integer, String> mapping = slotToItemId.get(player.getUniqueId());
        return mapping != null ? mapping.get(slot) : null;
    }

    /** (Parcours) onglet cliqué → catégorie, ou null. */
    public LolItem.ItemCategory getClickedCategory(int slot) {
        LolItem.ItemCategory[] cats = LolItem.ItemCategory.values();
        for (int i = 0; i < TAB_SLOTS.length; i++) {
            if (TAB_SLOTS[i] == slot && i < cats.length) return cats[i];
        }
        return null;
    }

    public LolItem.ItemCategory getCurrentCategory(Player player) {
        return currentCategory.getOrDefault(player.getUniqueId(), DEFAULT_CAT);
    }

    public void cleanup(Player player) {
        slotToItemId.remove(player.getUniqueId());
        currentCategory.remove(player.getUniqueId());
        detailItem.remove(player.getUniqueId());
        detailNav.remove(player.getUniqueId());
    }

    public static boolean isShopInventory(Component title) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(title);
        return plain.startsWith("🏪 Boutique") || plain.startsWith("🧬 Recette");
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
