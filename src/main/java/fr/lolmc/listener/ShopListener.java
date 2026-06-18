package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
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
        PlayerInventoryManager inv = getOrCreate(player);
        inv.refreshAll(player);
        goldManager.initPlayer(player.getUniqueId());
    }

    // ── Clics dans l'inventaire ───────────────────────────────────
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Component title = event.getView().title();

        // ── BOUTIQUE ──
        if (ShopGUI.isShopInventory(title)) {
            event.setCancelled(true);
            handleShopClick(player, event.getRawSlot());
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
                // Clic droit sur item équipé = vendre
                if (event.isRightClick()) {
                    trySellItem(player, slot);
                }
            }
        }
    }

    private void handleShopClick(Player player, int slot) {
        if (!championManager.hasChampion(player)) {
            player.sendMessage(Component.text("❌ Choisis un champion d'abord!", NamedTextColor.RED));
            return;
        }

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
                String.format("❌ Or insuffisant! (%d/%d)", gold, item.getGoldCost()),
                NamedTextColor.RED));
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
            inv.equipItem(player, champ, item);
            hudManager.updateHUD(player, champ);
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
        int refund = inv.sellItem(player, champ, lolSlot);
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
