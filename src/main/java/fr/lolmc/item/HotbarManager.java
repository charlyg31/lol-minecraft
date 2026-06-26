package fr.lolmc.item;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Gère la barre d'action (hotbar) avec un système de 2 pages.
 *
 * HOTBAR (slots 0-8) — visible en jeu :
 *   Page 1 : 0=AA  1=Q  2=W  3=E  4=R  5=Flash  6,7=items ACTIFS  8=→page2
 *   Page 2 : 0..6=items actifs + consommables  7=Recall  8=←page1
 *
 * INVENTAIRE (slots 9-35) — items achetés affichés (passifs ET actifs) :
 *   Slots 9-14 : 6 emplacements d'items LoL (icônes avec stats)
 *   Slots 15-35 : vides (réservés / inaccessibles)
 *
 * Règle : les items PASSIFS n'apparaissent QUE dans l'inventaire (9-14).
 *         les items ACTIFS apparaissent dans l'inventaire ET dans la hotbar.
 *
 * Identification fiable via PersistentDataContainer (PDC), pas via le lore.
 */
public class HotbarManager {

    // Clés PDC (initialisées au constructeur, partagées statiquement pour les helpers)
    public static NamespacedKey KEY_TYPE;
    public static NamespacedKey KEY_ID;
    public static NamespacedKey KEY_SLOT;
    public static NamespacedKey KEY_RECALL;

    // Page actuelle par joueur
    private final Map<UUID, Integer> currentPage = new HashMap<>();

    public HotbarManager() {
        KEY_TYPE = new NamespacedKey(LolPlugin.getInstance(), "lol_type");
        KEY_ID   = new NamespacedKey(LolPlugin.getInstance(), "lol_id");
        KEY_SLOT = new NamespacedKey(LolPlugin.getInstance(), "lol_slot");
        KEY_RECALL = new NamespacedKey(LolPlugin.getInstance(), "lol_recall");
    }
    // Items achetés par joueur (max 6) — index 0..5
    private final Map<UUID, List<String>> ownedItems = new HashMap<>();

    // Slots INVENTAIRE (2e/3e rangée) pour afficher les 6 items achetés
    // Slots 9-14 : première rangée de l'inventaire (sous la hotbar)
    public static final int[] INV_ITEM_SLOTS = {9, 10, 11, 12, 13, 14};

    // Slots hotbar réservés aux items actifs en page 1 (après Flash)
    private static final int[] PAGE1_ACTIVE_SLOTS = {6, 7};
    // Slots hotbar pour page 2 (items actifs + consommables)
    private static final int[] PAGE2_SLOTS = {0, 1, 2, 3, 4, 5, 6};
    private static final int PAGE_BUTTON_SLOT = 8;
    private static final int FLASH_SLOT = 5;

    // ════════════════════════════════════════════════════════
    // INITIALISATION
    // ════════════════════════════════════════════════════════

    public void initPlayer(Player player, BaseChampion champ) {
        currentPage.put(player.getUniqueId(), 1);
        ownedItems.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        renderPage(player, champ);
    }

    // ════════════════════════════════════════════════════════
    // GESTION DES ITEMS ACHETÉS
    // ════════════════════════════════════════════════════════

    public boolean addItem(Player player, String itemId) {
        List<String> owned = ownedItems.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        if (owned.size() >= 6) {
            player.sendMessage(Component.text("❌ Inventaire plein (6/6 items)!", NamedTextColor.RED));
            return false;
        }
        owned.add(itemId);
        return true;
    }

    public boolean removeItem(Player player, String itemId) {
        List<String> owned = ownedItems.get(player.getUniqueId());
        if (owned == null) return false;
        return owned.remove(itemId);
    }

    public List<String> getOwnedItems(Player player) {
        return ownedItems.getOrDefault(player.getUniqueId(), new ArrayList<>());
    }

    public boolean isFull(Player player) {
        return getOwnedItems(player).size() >= 6;
    }

    // ════════════════════════════════════════════════════════
    // RENDU DES PAGES
    // ════════════════════════════════════════════════════════

    public void renderPage(Player player, BaseChampion champ) {
        int page = currentPage.getOrDefault(player.getUniqueId(), 1);
        if (page == 1) renderPage1(player, champ);
        else renderPage2(player, champ);
        // Toujours synchroniser l'inventaire (items achetés)
        renderInventoryItems(player);
    }

    private void renderPage1(Player player, BaseChampion champ) {
        PlayerInventory inv = player.getInventory();

        // Effacer toute la hotbar avant de redessiner (evite les residus de page 2)
        for (int i = 0; i <= 8; i++) inv.setItem(i, emptySlot());

        // Slots 0-4 : sorts (AA, Q, W, E, R)
        for (int i = 0; i < 5; i++) {
            var ability = champ.getAbility(i);
            if (ability != null) {
                ItemStack stack = ability.buildItemStack(champ.getStats());
                tag(stack, "ability", ability.getId(), i);
                inv.setItem(i, stack);
            }
        }

        // Slot 5 : Flash
        inv.setItem(FLASH_SLOT, buildFlash(player));

        // Slots 6,7 : 2 premiers items actifs
        List<String> activeItems = getActiveItems(player);
        for (int i = 0; i < PAGE1_ACTIVE_SLOTS.length; i++) {
            int slot = PAGE1_ACTIVE_SLOTS[i];
            if (i < activeItems.size()) {
                inv.setItem(slot, buildActiveItem(activeItems.get(i)));
            } else {
                inv.setItem(slot, emptySlot());
            }
        }

        // Slot 8 : bouton page suivante
        inv.setItem(PAGE_BUTTON_SLOT, buildPageButton(2));
    }

    private void renderPage2(Player player, BaseChampion champ) {
        PlayerInventory inv = player.getInventory();

        // Effacer toute la hotbar avant de redessiner (evite les residus de page 1)
        for (int i = 0; i <= 8; i++) inv.setItem(i, emptySlot());

        // Items actifs au-delà des 2 premiers + consommables
        List<String> activeItems = getActiveItems(player);
        List<ItemStack> page2Content = new ArrayList<>();

        // Items actifs 3+ (les 2 premiers sont en page 1)
        for (int i = 2; i < activeItems.size(); i++) {
            page2Content.add(buildActiveItem(activeItems.get(i)));
        }
        // Consommables possédés
        for (String consId : getConsumables(player)) {
            page2Content.add(fr.lolmc.item.consumable.ConsumableManager.buildConsumable(consId));
        }

        // Placer dans les slots 0-6
        for (int i = 0; i < PAGE2_SLOTS.length; i++) {
            int slot = PAGE2_SLOTS[i];
            if (i < page2Content.size()) {
                inv.setItem(slot, page2Content.get(i));
            } else {
                inv.setItem(slot, emptySlot());
            }
        }

        // Slot 7 : Recall (retour à la base)
        inv.setItem(7, buildRecallItem());
        // Slot 8 : retour page 1
        inv.setItem(PAGE_BUTTON_SLOT, buildPageButton(1));
    }

    /** Construit l'item de Recall (retour à la base). */
    private ItemStack buildRecallItem() {
        ItemStack item = new ItemStack(Material.ENDER_PEARL);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text("🌀 Recall",
                    net.kyori.adventure.text.format.NamedTextColor.AQUA)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("Clic droit : retour à la base (8s)",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("Interrompu par les dégâts ou le mouvement",
                    net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(KEY_RECALL, PersistentDataType.BYTE, (byte) 1);
            // Marquer AUSSI avec KEY_TYPE pour passer le filtre isLolItem dans onInteract
            meta.getPersistentDataContainer().set(KEY_TYPE, PersistentDataType.STRING, "recall");
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Vrai si l'item est le bouton de recall. */
    public static boolean isRecallItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(KEY_RECALL, PersistentDataType.BYTE);
    }

    // ════════════════════════════════════════════════════════
    // CHANGEMENT DE PAGE
    // ════════════════════════════════════════════════════════

    public void switchPage(Player player, BaseChampion champ) {
        int page = currentPage.getOrDefault(player.getUniqueId(), 1);
        currentPage.put(player.getUniqueId(), page == 1 ? 2 : 1);
        renderPage(player, champ);
        player.sendActionBar(Component.text(
            page == 1 ? "📖 Page 2 — Utilitaire" : "⚔ Page 1 — Combat",
            NamedTextColor.YELLOW));
    }

    public int getPage(Player player) {
        return currentPage.getOrDefault(player.getUniqueId(), 1);
    }

    // ════════════════════════════════════════════════════════
    // CONSTRUCTION DES ITEMSTACK (avec PDC)
    // ════════════════════════════════════════════════════════

    private ItemStack buildFlash(Player player) {
        boolean ready = !LolPlugin.getInstance().getFlashManager().isOnCooldown(player);
        ItemStack flash = new ItemStack(ready ? Material.ENDER_PEARL : Material.GRAY_DYE);
        ItemMeta meta = flash.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✦ Flash", ready ? NamedTextColor.AQUA : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
            List<Component> lore = new ArrayList<>();
            if (ready) {
                lore.add(Component.text("Téléporte ~5 blocs dans la direction visée.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Clic droit pour utiliser.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                double rem = LolPlugin.getInstance().getFlashManager().getRemaining(player);
                lore.add(Component.text(String.format("En recharge: %.0fs", rem), NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            flash.setItemMeta(meta);
        }
        tag(flash, "flash", "flash", -1);
        return flash;
    }

    private ItemStack buildActiveItem(String itemId) {
        LolItem item = ItemRegistry.get(itemId);
        if (item == null) return emptySlot();
        ItemStack stack = item.buildItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            // Ajouter un indicateur "ACTIF — clic droit"
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.text("➤ ACTIF — clic droit", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
        tag(stack, "item", itemId, -1);
        return stack;
    }

    private ItemStack buildPageButton(int targetPage) {
        ItemStack btn = new ItemStack(targetPage == 2 ? Material.ARROW : Material.SPECTRAL_ARROW);
        ItemMeta meta = btn.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(
                    targetPage == 2 ? "→ Page Utilitaire" : "← Page Combat",
                    NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
            meta.lore(List.of(Component.text("Clic pour changer de page", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            btn.setItemMeta(meta);
        }
        tag(btn, "page", String.valueOf(targetPage), -1);
        return btn;
    }

    private ItemStack emptySlot() {
        ItemStack empty = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = empty.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            empty.setItemMeta(meta);
        }
        tag(empty, "empty", "", -1);
        return empty;
    }

    // ════════════════════════════════════════════════════════
    // PDC — identification fiable
    // ════════════════════════════════════════════════════════

    private void tag(ItemStack stack, String type, String id, int slot) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_TYPE, PersistentDataType.STRING, type);
        pdc.set(KEY_ID, PersistentDataType.STRING, id);
        pdc.set(KEY_SLOT, PersistentDataType.INTEGER, slot);
        stack.setItemMeta(meta);
    }

    /**
     * Reconstruit ET re-marque un item de sort dans la hotbar (slots 0-4).
     * À appeler après un cast pour que l'item garde son marquage PDC
     * (sinon getType() renvoie null et les clics suivants sont ignorés).
     * Ne fait rien si le joueur n'est pas sur la page 1.
     */
    public void refreshAbilitySlot(Player player, BaseChampion champ, int slot) {
        if (!fr.lolmc.util.WorldContext.isInGameWorld(player)) return;
        if (slot < 0 || slot > 4) return;
        var ability = champ.getAbility(slot);
        if (ability == null) return;
        org.bukkit.inventory.ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType() == org.bukkit.Material.AIR) return;
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        double remaining = ability.getRemainingCooldown(player);
        java.util.List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
        if (remaining > 0) {
            meta.displayName(net.kyori.adventure.text.Component.text(
                ability.getName() + " ⏱ " + String.format("%.1f", remaining) + "s",
                net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lore.add(net.kyori.adventure.text.Component.text(
                "CD: " + String.format("%.1f", remaining) + "s",
                net.kyori.adventure.text.format.NamedTextColor.RED)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        } else {
            meta.displayName(net.kyori.adventure.text.Component.text(
                ability.getName(),
                net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lore.add(net.kyori.adventure.text.Component.text(
                "✔ Prêt!",
                net.kyori.adventure.text.format.NamedTextColor.GREEN)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        String desc = ability.getDynamicDescription(champ.getStats());
        if (desc != null && !desc.isEmpty())
            lore.add(net.kyori.adventure.text.Component.text(desc,
                net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    public static String getType(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(KEY_TYPE, PersistentDataType.STRING);
    }

    public static String getId(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
    }

    public static boolean isLolItem(ItemStack stack) {
        return getType(stack) != null;
    }

    // ════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════

    private List<String> getActiveItems(Player player) {
        List<String> result = new ArrayList<>();
        for (String id : getOwnedItems(player)) {
            LolItem item = ItemRegistry.get(id);
            if (item != null && item.hasActive()) result.add(id);
        }
        return result;
    }

    // Consommables possédés (gérés séparément du stock d'items)
    private final Map<UUID, List<String>> consumables = new HashMap<>();

    public void addConsumable(Player player, String consId) {
        consumables.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(consId);
    }
    public List<String> getConsumables(Player player) {
        return consumables.getOrDefault(player.getUniqueId(), new ArrayList<>());
    }
    public void removeConsumable(Player player, String consId) {
        List<String> list = consumables.get(player.getUniqueId());
        if (list != null) list.remove(consId);
    }


    /**
     * Affiche les items achetés dans l'inventaire Minecraft (slots 9-14).
     * Les items passifs et actifs y sont tous visibles.
     * Appelée après chaque rendu de page.
     */
    public void renderInventoryItems(Player player) {
        var inv = player.getInventory();
        List<String> owned = getOwnedItems(player);
        for (int i = 0; i < INV_ITEM_SLOTS.length; i++) {
            int mcSlot = INV_ITEM_SLOTS[i];
            if (i < owned.size()) {
                LolItem item = ItemRegistry.get(owned.get(i));
                if (item != null) {
                    ItemStack stack = item.buildItemStack();
                    // Marquer PDC pour bloquer le déplacement
                    tag(stack, "inv_item", item.getId(), i);
                    inv.setItem(mcSlot, stack);
                } else {
                    inv.setItem(mcSlot, emptyInvSlot(i + 1));
                }
            } else {
                inv.setItem(mcSlot, emptyInvSlot(i + 1));
            }
        }
        // Vider les slots restants 15-35 (protection)
        for (int s = 15; s <= 35; s++) {
            if (inv.getItem(s) != null && isLolItem(inv.getItem(s)))
                inv.setItem(s, null);
        }
    }

    private ItemStack emptyInvSlot(int number) {
        ItemStack empty = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = empty.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Slot item " + number, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
            tag(empty, "inv_empty", "", -1);
            empty.setItemMeta(meta);
        }
        return empty;
    }

    // Nettoyage mémoire (appelé à la déconnexion)
    public void cleanup(UUID uuid) {
        currentPage.remove(uuid);
        ownedItems.remove(uuid);
        consumables.remove(uuid);
    }
}
