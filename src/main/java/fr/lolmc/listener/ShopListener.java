package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.item.PassiveManager;
import fr.lolmc.item.consumable.ConsumableManager;
import fr.lolmc.item.LolItem.ItemCategory;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.item.ItemRegistry;
import fr.lolmc.item.LolItem;
import fr.lolmc.item.PlayerInventoryManager;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.shop.GoldManager;
import fr.lolmc.shop.ShopGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShopListener implements Listener {

    private final ShopGUI shopGUI;
    private final ChampionManager championManager;
    private final GoldManager goldManager;
    private final HUDManager hudManager;

    // PlayerInventoryManager par joueur
    private final Map<UUID, PlayerInventoryManager> inventoryManagers = new HashMap<>();

    public ShopListener(ShopGUI shopGUI, ChampionManager championManager,
                        GoldManager goldManager, HUDManager hudManager) {
        this.shopGUI = shopGUI;
        this.championManager = championManager;
        this.goldManager = goldManager;
        this.hudManager = hudManager;
    }

    // ── Initialiser l'inventaire d'un joueur ──────────────────────
    public PlayerInventoryManager getOrCreate(Player player) {
        return inventoryManagers.computeIfAbsent(
            player.getUniqueId(), k -> new PlayerInventoryManager());
    }

    public void initPlayer(Player player) {
        getOrCreate(player);
        goldManager.initPlayer(player.getUniqueId());
        // Le HotbarManager.initPlayer est appelé par GUIListener après pick champion
    }

    public void cleanup(java.util.UUID uuid) {
        inventoryManagers.remove(uuid);
    }

    // ── Clics dans l'inventaire ───────────────────────────────────
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Component title = event.getView().title();

        // ── BOUTIQUE ──
        if (ShopGUI.isShopInventory(title)) {
            // Annuler TOUT clic (y compris shift-clic, double-clic, nombre)
            event.setCancelled(true);
            // Seuls les clics dans le menu haut (slots 0-53) déclenchent un achat
            int raw = event.getRawSlot();
            if (raw >= 0 && raw < event.getView().getTopInventory().getSize()) {
                handleShopClick(player, raw);
            }
            return;
        }

        // ── INVENTAIRE JOUEUR — protéger slots sorts (0-4) et items (5,6,7,8,18,19) ──
        if (!championManager.hasChampion(player)) return;

        int slot = event.getRawSlot();
        boolean isSortSlot = slot >= 0 && slot <= 4;
        boolean isItemSlot = isLolItemSlot(slot);

        if (isSortSlot || isItemSlot) {
            event.setCancelled(true);
            if (isItemSlot) {
                if (event.isRightClick()) {
                    // Shift+clic droit = vendre
                    if (event.isShiftClick()) {
                        trySellItem(player, slot);
                    } else {
                        // Clic droit = activer l'actif de l'item
                        int lolSlot = PlayerInventoryManager.getLolSlotIndex(slot);
                        if (lolSlot >= 0) {
                            var inv = getOrCreate(player);
                            var item = inv.getItem(lolSlot);
                            if (item != null) {
                                PassiveManager pm = LolPlugin.getInstance().getPassiveManager();
                                if (pm != null) pm.activateItem(player, item.getId());
                            }
                        }
                    }
                }
            }
        }
    }

    private void handleShopClick(Player player, int slot) {
        if (!championManager.hasChampion(player)) {
            player.sendMessage(Component.text("❌ Choisis un champion d'abord!", NamedTextColor.RED));
            return;
        }

        // DEBUG temporaire : afficher ce qui est cliqué
        String dbgId = shopGUI.getClickedItemId(player, slot);
        player.sendMessage(Component.text("[debug] slot=" + slot + " item=" + dbgId, NamedTextColor.GRAY));

        // Clic sur un onglet ?
        LolItem.ItemCategory cat = shopGUI.getClickedCategory(slot);
        if (cat != null) {
            shopGUI.open(player, cat);
            return;
        }

        // Clic sur un item à acheter ?
        String itemId = shopGUI.getClickedItemId(player, slot);
        if (itemId == null) return;

        LolItem item = ItemRegistry.get(itemId);
        if (item == null) return;

        // Vérifier l'or
        int gold = goldManager.getGold(player.getUniqueId());
        if (gold < item.getGoldCost()) {
            player.sendMessage(Component.text(
                String.format("❌ Or insuffisant! Tu as %d or, il en faut %d.", gold, item.getGoldCost()),
                NamedTextColor.RED));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Vérifier l'espace
        PlayerInventoryManager inv = getOrCreate(player);
        if (inv.isFull()) {
            player.sendMessage(Component.text("❌ Inventaire plein (6/6 items)!", NamedTextColor.RED));
            return;
        }

        // Acheter
        BaseChampion champ = championManager.getChampion(player);
        if (goldManager.spendGold(player.getUniqueId(), item.getGoldCost())) {
            // Consommables: utilisation immédiate
            ConsumableManager cm = LolPlugin.getInstance().getConsumableManager();
            if (item.getCategory() == ItemCategory.CONSUMABLE && cm != null) {
                // Stocker le consommable dans la page 2 de la hotbar (utilisable plus tard)
                var hb = LolPlugin.getInstance().getHotbarManager();
                hb.addConsumable(player, item.getId());
                hb.renderPage(player, champ);
                player.sendActionBar(Component.text(
                    "🧪 " + item.getDisplayName() + " ajouté (page 2)", NamedTextColor.GREEN));
            } else {
                inv.equipItem(player, champ, item);
                // Ajouter au HotbarManager (affichage hotbar des actifs)
                var hb = LolPlugin.getInstance().getHotbarManager();
                hb.addItem(player, item.getId());
                hb.renderPage(player, champ);
                hudManager.updateHUD(player, champ);
            }
            // Rafraîchir l'affichage de l'or dans la boutique
            player.sendActionBar(Component.text(
                String.format("💰 Or: %d | %s acheté!", 
                    goldManager.getGold(player.getUniqueId()),
                    item.getDisplayName()),
                NamedTextColor.GOLD));
        }
    }

    private void trySellItem(Player player, int mcSlot) {
        if (!championManager.hasChampion(player)) return;
        int lolSlot = PlayerInventoryManager.getLolSlotIndex(mcSlot);
        if (lolSlot == -1) return;

        PlayerInventoryManager inv = getOrCreate(player);
        if (inv.getItem(lolSlot) == null) return;

        BaseChampion champ = championManager.getChampion(player);
        var soldItem = inv.getItem(lolSlot);
        int refund = inv.sellItem(player, champ, lolSlot);
        if (soldItem != null) {
            var hb = LolPlugin.getInstance().getHotbarManager();
            hb.removeItem(player, soldItem.getId());
            hb.renderPage(player, champ);
        }
        goldManager.addGold(player.getUniqueId(), refund);
        hudManager.updateHUD(player, champ);
        player.sendActionBar(Component.text(
            "💰 Or: " + goldManager.getGold(player.getUniqueId()),
            NamedTextColor.GOLD));
    }

    // ── Fermeture boutique ────────────────────────────────────────
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (ShopGUI.isShopInventory(event.getView().title())) {
            shopGUI.cleanup(p);
        }
    }

    // ── Empêcher de dropper les items LoL ────────────────────────
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        if (!championManager.hasChampion(p)) return;
        int slot = p.getInventory().getHeldItemSlot();
        if (isLolItemSlot(slot) || slot <= 4) {
            event.setCancelled(true);
        }
    }

    // ── Consommables ───────────────────────────────────────────────

    /** Appelé par AbilityListener quand un consommable est cliqué dans la hotbar. */
    public void useConsumablePublic(Player player, String id) {
        ConsumableManager cm = LolPlugin.getInstance().getConsumableManager();
        if (cm == null) return;
        switch (id) {
            case "health_potion", "health_potion2"         -> cm.useHealthPotion(player);
            case "refillable_potion", "refillable_potion2"  -> cm.useRefillablePotion(player);
            case "biscuit", "biscuit_will"                  -> cm.useBiscuit(player);
            case "elixir_wrath", "elixir_wrath2"            -> cm.useElixirWrath(player);
            case "elixir_iron", "elixir_iron2"              -> cm.useElixirIron(player);
            case "elixir_sorcery", "elixir_sorcery2"        -> cm.useElixirSorcery(player);
            case "stealth_ward", "stealth_ward2"            -> cm.placeWard(player, false);
            case "control_ward", "control_ward2"            -> cm.placeControlWard(player);
            case "farsight", "farsight2"                    -> cm.placeWard(player, true);
            default -> player.sendActionBar(net.kyori.adventure.text.Component.text(
                "Consommable: " + id, net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private boolean isLolItemSlot(int slot) {
        for (int s : PlayerInventoryManager.ITEM_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    public GoldManager getGoldManager() { return goldManager; }
    public Map<UUID, PlayerInventoryManager> getInventoryManagers() { return inventoryManagers; }
}
