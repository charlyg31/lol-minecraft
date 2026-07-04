package fr.lolmc.shop;

import fr.lolmc.LolPlugin;
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
 * Boutique LoL avec pagination.
 *
 * Layout (54 slots) :
 *   Rangée 0 (slots 0-8) : onglets catégorie (0-6) + or (7) + fermer (8)
 *   Rangées 1-4 (slots 9-44) : grille items — 36 items par page
 *   Rangée 5 (slots 45-53) : nav pagination
 *     45=◀◀ première  46=◀ précédent  47=vide  48=page X/Y  49=vide  50=vide  51=▶ suivant  52=▶▶ dernière  53=filtre
 */
public class ShopGUI {

    public static final String BROWSE_PREFIX = "🏪 Boutique — ";
    public static final String DETAIL_PREFIX = "🧬 Recette — ";

    private static final LolItem.ItemCategory DEFAULT_CAT = LolItem.ItemCategory.DAMAGE;
    private static final int ITEMS_PER_PAGE = 36;
    private static final int ITEMS_START    = 9;
    private static final int ITEMS_END      = 44; // inclusif

    // Slots onglets
    private static final int[] TAB_SLOTS = {0, 1, 2, 3, 4, 5, 6};
    private static final int GOLD_SLOT   = 7;
    private static final int CLOSE_SLOT  = 8;

    // Slots nav pagination (rangée 5)
    private static final int NAV_FIRST  = 45;
    private static final int NAV_PREV   = 46;
    private static final int NAV_INFO   = 49;
    private static final int NAV_NEXT   = 51;
    private static final int NAV_LAST   = 52;

    // Fiche detail
    private static final int D_ITEM       = 4;
    private static final int D_BACK       = 0;
    private static final int D_GOLD       = 8;
    private static final int D_BUY        = 49;
    private static final int[] D_COMPONENTS = {20, 21, 22, 23, 24};
    private static final int[] D_BUILDS    = {37, 38, 39, 40, 41, 42, 43};

    // ── État par joueur ───────────────────────────────────────────
    private final Map<UUID, Map<Integer, String>>  slotToItemId    = new HashMap<>();
    private final Map<UUID, LolItem.ItemCategory>  currentCategory = new HashMap<>();
    private final Map<UUID, Integer>               currentPage     = new HashMap<>();
    private final Map<UUID, String>                detailItem      = new HashMap<>();
    private final Map<UUID, Map<Integer, String>>  detailNav       = new HashMap<>();

    // ══════════════════════════════════════════════════════════════
    // VUE PARCOURS
    // ══════════════════════════════════════════════════════════════

    public void open(Player player) {
        open(player, getCurrentCategory(player), getPage(player));
    }

    public void open(Player player, LolItem.ItemCategory cat) {
        open(player, cat, 0);
    }

    public void open(Player player, LolItem.ItemCategory cat, int page) {
        detailItem.remove(player.getUniqueId());
        currentCategory.put(player.getUniqueId(), cat);

        String search = currentSearch.getOrDefault(player.getUniqueId(), "").toLowerCase().trim();
        List<LolItem> items = ItemRegistry.byCategory(cat).stream()
            .filter(it -> search.isEmpty()
                || it.getDisplayName().toLowerCase().contains(search)
                || it.getId().toLowerCase().contains(search))
            .toList();
        int totalPages = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        currentPage.put(player.getUniqueId(), page);

        String title = BROWSE_PREFIX + getCategoryLabel(cat)
                + (totalPages > 1 ? " [" + (page+1) + "/" + totalPages + "]" : "");
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(title, NamedTextColor.GOLD));

        // ── Rangée 0 : onglets + or + fermer ──
        LolItem.ItemCategory[] cats = LolItem.ItemCategory.values();
        for (int i = 0; i < Math.min(cats.length, TAB_SLOTS.length); i++)
            inv.setItem(TAB_SLOTS[i], tabButton(cats[i], cats[i] == cat));
        inv.setItem(GOLD_SLOT, goldDisplay(player));
        inv.setItem(CLOSE_SLOT, navButton(Material.BARRIER, "✖ Fermer", NamedTextColor.RED));

        // ── Rangées 1-4 : items de la page ──
        Map<Integer, String> mapping = new HashMap<>();
        int start = page * ITEMS_PER_PAGE;
        int end   = Math.min(start + ITEMS_PER_PAGE, items.size());
        for (int i = start; i < end; i++) {
            LolItem item = items.get(i);
            int slot = ITEMS_START + (i - start);
            inv.setItem(slot, decorateBrowse(item.buildItemStack(), item));
            mapping.put(slot, item.getId());
        }
        slotToItemId.put(player.getUniqueId(), mapping);

        // ── Rangée 5 : pagination ──
        ItemStack filler = filler();
        for (int s = 45; s <= 53; s++) inv.setItem(s, filler);

        if (totalPages > 1) {
            if (page > 0) {
                inv.setItem(NAV_FIRST, navButton(Material.SPECTRAL_ARROW,
                    "⏮ Première page", NamedTextColor.AQUA));
                inv.setItem(NAV_PREV,  navButton(Material.ARROW,
                    "◀ Page précédente", NamedTextColor.YELLOW));
            }
            inv.setItem(NAV_INFO, pageInfo(page, totalPages, start, Math.min(end, items.size()), items.size()));
            if (page < totalPages - 1) {
                inv.setItem(NAV_NEXT, navButton(Material.ARROW,
                    "▶ Page suivante", NamedTextColor.YELLOW));
                inv.setItem(NAV_LAST, navButton(Material.SPECTRAL_ARROW,
                    "⏭ Dernière page", NamedTextColor.AQUA));
            }
        }

        // Slot 53 : filtre texte actuel
        inv.setItem(53, searchButton(currentSearch.getOrDefault(player.getUniqueId(), "")));
        // Remplir les vides
        for (int i = 0; i < 54; i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
        player.openInventory(inv);
    }

    private ItemStack pageInfo(int page, int total, int from, int to, int totalItems) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        m.displayName(Component.text(
            String.format("Page %d / %d", page+1, total), NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, true));
        m.lore(List.of(
            Component.text(String.format("Items %d-%d sur %d", from+1, to, totalItems), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(m);
        return item;
    }

    /** Ajoute un indice "clic = fiche" à l'item de la grille. */
    private ItemStack decorateBrowse(ItemStack stack, LolItem item) {
        ItemMeta m = stack.getItemMeta();
        if (m == null) return stack;
        List<Component> lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        if (!item.getBuildsFrom().isEmpty())
            lore.add(Component.text("🧬 " + item.getBuildsFrom().size() + " composants",
                NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("▶ Clic : voir la recette / acheter", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        stack.setItemMeta(m);
        return stack;
    }

    // ══════════════════════════════════════════════════════════════
    // VUE FICHE (arborescence + achat)
    // ══════════════════════════════════════════════════════════════

    public void openDetail(Player player, String itemId) {
        LolItem item = ItemRegistry.get(itemId);
        if (item == null) { open(player); return; }
        detailItem.put(player.getUniqueId(), itemId);

        Inventory inv = Bukkit.createInventory(null, 54,
            Component.text(DETAIL_PREFIX + item.getDisplayName(), NamedTextColor.LIGHT_PURPLE));

        ItemStack filler = filler();
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        inv.setItem(D_ITEM, item.buildItemStack());
        inv.setItem(D_BACK, navButton(Material.ARROW, "◀ Retour à la boutique", NamedTextColor.YELLOW));
        inv.setItem(D_GOLD, goldDisplay(player));

        Map<Integer, String> nav = new HashMap<>();

        // Composants (recette)
        inv.setItem(18, label("⬇ Construit à partir de :", NamedTextColor.AQUA));
        List<String> comps = item.getBuildsFrom();
        if (comps.isEmpty())
            inv.setItem(D_COMPONENTS[2], label("Composant de base (pas de recette)", NamedTextColor.GRAY));
        else
            placeRow(inv, comps, D_COMPONENTS, nav);

        // Ce que l'item construit
        inv.setItem(27, label("⬆ Composant de :", NamedTextColor.GOLD));
        List<String> into = new ArrayList<>();
        for (LolItem b : ItemRegistry.buildsInto(itemId)) into.add(b.getId());
        if (into.isEmpty())
            inv.setItem(D_BUILDS[3], label("Item final (ne construit rien)", NamedTextColor.GRAY));
        else
            placeRow(inv, into, D_BUILDS, nav);

        inv.setItem(D_BUY, buyButton(player, item));

        detailNav.put(player.getUniqueId(), nav);
        player.openInventory(inv);
    }

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
        int gold = getGold(player);
        boolean afford = gold >= item.getGoldCost();
        ItemStack b = new ItemStack(afford ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK);
        ItemMeta m = b.getItemMeta();
        if (m == null) return b;
        m.displayName(Component.text((afford ? "✔ Acheter — " : "✘ Or insuffisant — ")
            + item.getGoldCost() + " or",
            afford ? NamedTextColor.GREEN : NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(item.getDisplayName(), NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Ton or : " + gold, NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false));
        if (!afford)
            lore.add(Component.text("Il te manque " + (item.getGoldCost()-gold) + " or",
                NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        b.setItemMeta(m);
        return b;
    }

    // ══════════════════════════════════════════════════════════════
    // NAVIGATION — ROUTAGE DES CLICS (lu par ShopListener)
    // ══════════════════════════════════════════════════════════════

    /** Vérifie si le slot est un bouton de pagination et navigue. Retourne true si consommé. */
    public boolean handlePaginationClick(Player player, int slot) {
        LolItem.ItemCategory cat = getCurrentCategory(player);
        int page = getPage(player);
        String search = currentSearch.getOrDefault(player.getUniqueId(), "").toLowerCase().trim();
        List<LolItem> items = ItemRegistry.byCategory(cat).stream()
            .filter(it -> search.isEmpty()
                || it.getDisplayName().toLowerCase().contains(search)
                || it.getId().toLowerCase().contains(search))
            .toList();
        int totalPages = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);

        if (slot == NAV_PREV  && page > 0)               { open(player, cat, page - 1); return true; }
        if (slot == NAV_NEXT  && page < totalPages - 1)  { open(player, cat, page + 1); return true; }
        if (slot == NAV_FIRST && page > 0)               { open(player, cat, 0);         return true; }
        if (slot == NAV_LAST  && page < totalPages - 1)  { open(player, cat, totalPages - 1); return true; }
        if (slot == CLOSE_SLOT) { player.closeInventory(); return true; }
        if (slot == NAV_INFO)   return true; // bouton info, rien à faire
        return false;
    }

    public boolean isDetailView(Player player) {
        if (!detailItem.containsKey(player.getUniqueId())) return false;
        var view = player.getOpenInventory();
        if (view == null) return false;
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(view.title());
        return title.startsWith(DETAIL_PREFIX);
    }

    public String getDetailItemId(Player player)             { return detailItem.get(player.getUniqueId()); }
    public boolean isBackSlot(int slot)                      { return slot == D_BACK; }
    public boolean isBuySlot(int slot)                       { return slot == D_BUY; }
    public String getDetailNavId(Player player, int slot)    { var n=detailNav.get(player.getUniqueId()); return n!=null?n.get(slot):null; }
    public String getClickedItemId(Player player, int slot)  { var m=slotToItemId.get(player.getUniqueId()); return m!=null?m.get(slot):null; }
    public LolItem.ItemCategory getCurrentCategory(Player p) { return currentCategory.getOrDefault(p.getUniqueId(), DEFAULT_CAT); }
    public int getPage(Player p)                             { return currentPage.getOrDefault(p.getUniqueId(), 0); }

    public LolItem.ItemCategory getClickedCategory(int slot) {
        LolItem.ItemCategory[] cats = LolItem.ItemCategory.values();
        for (int i = 0; i < TAB_SLOTS.length; i++)
            if (TAB_SLOTS[i] == slot && i < cats.length) return cats[i];
        return null;
    }

    private final Map<UUID, String> currentSearch = new HashMap<>();

    public void setSearch(Player player, String query) {
        currentSearch.put(player.getUniqueId(), query.toLowerCase().trim());
        open(player, getCurrentCategory(player), 0);
    }

    public void cleanup(Player player) {
        UUID id = player.getUniqueId();
        slotToItemId.remove(id); currentCategory.remove(id);
        currentPage.remove(id); detailItem.remove(id); detailNav.remove(id);
        currentSearch.remove(id);
    }

    public static boolean isShopInventory(Component title) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(title);
        return plain.startsWith("🏪 Boutique") || plain.startsWith("🧬 Recette");
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS VISUELS
    // ══════════════════════════════════════════════════════════════

    private ItemStack searchButton(String current) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        String label = current.isEmpty() ? "🔍 Rechercher un item..." : "🔍 Filtre: " + current;
        m.displayName(Component.text(label, NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        if (!current.isEmpty()) {
            m.lore(List.of(
                Component.text("Clic droit → effacer le filtre", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
        }
        item.setItemMeta(m);
        return item;
    }

    public static final int SEARCH_SLOT = 53;

    public boolean handleSearchClick(Player player, int slot, boolean rightClick) {
        if (slot != SEARCH_SLOT) return false;
        if (rightClick) {
            currentSearch.remove(player.getUniqueId());
            open(player, getCurrentCategory(player), 0);
        } else {
            // Demander au joueur de taper dans le chat
            player.closeInventory();
            player.sendMessage(Component.text(
                "🔍 Tape le nom de l'item à rechercher dans le chat (ou 'annuler'):",
                NamedTextColor.AQUA));
            LolPlugin.getInstance().getShopListener().awaitSearchInput(player);
        }
        return true;
    }

    public boolean isSearchSlot(int slot) { return slot == SEARCH_SLOT; }

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
        // Compter les items de cette catégorie
        int count = ItemRegistry.byCategory(cat).size();
        meta.displayName(Component.text((selected ? "▶ " : "") + getCategoryLabel(cat)
            + " (" + count + ")", selected ? NamedTextColor.GOLD : NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, selected));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack goldDisplay(Player player) {
        int gold = getGold(player);
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        m.displayName(Component.text("💰 " + gold + " or", NamedTextColor.GOLD)
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
        if (m != null) { m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false)); f.setItemMeta(m); }
        return f;
    }

    private int getGold(Player player) {
        return fr.lolmc.LolPlugin.getInstance().getGoldManager().getGold(player.getUniqueId());
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
