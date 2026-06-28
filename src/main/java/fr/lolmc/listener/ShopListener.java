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
    }

    public void cleanup(java.util.UUID uuid) {
        inventoryManagers.remove(uuid);
    }

    public void cleanupAll() {
        inventoryManagers.clear();
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

            // CORRECTION : Utiliser getRawSlot() pour ne valider que la grille supérieure (0-53)
            int raw = event.getRawSlot();
            if (raw >= 0 && raw < event.getView().getTopInventory().getSize()) {
                handleShopClick(player, raw);
            }
            return;
        }

        // ── INVENTAIRE JOUEUR — protéger slots sorts (0-4) et items (5,6,7,8,18,19) ──
        if (!championManager.hasChampion(player)) return;
        // Guard : ne traiter que les clics dans l'inventaire du joueur (pas conteneur ouvert)
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(player.getInventory())) return;

        int slot = event.getSlot();
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

        // ── Vue FICHE (arborescence) ──
        if (shopGUI.isDetailView(player)) {
            if (shopGUI.isBackSlot(slot)) { shopGUI.open(player); return; }
            if (shopGUI.isBuySlot(slot)) {
                String id = shopGUI.getDetailItemId(player);
                LolItem it = (id != null) ? ItemRegistry.get(id) : null;
                if (it != null && purchase(player, it)) {
                    shopGUI.openDetail(player, it.getId()); // rafraîchir (or / dispo)
                }
                return;
            }
            String navId = shopGUI.getDetailNavId(player, slot);
            if (navId != null) { shopGUI.openDetail(player, navId); return; }
            return;
        }

        // ── Vue PARCOURS ──
        LolItem.ItemCategory cat = shopGUI.getClickedCategory(slot);
        if (cat != null) { shopGUI.open(player, cat); return; }

        String itemId = shopGUI.getClickedItemId(player, slot);
        if (itemId != null) { shopGUI.openDetail(player, itemId); } // ouvre la fiche/recette
    }

    /** Achète un item (vérifie la recette, l'or dynamique et la place). Retourne true si l'achat a réussi. */
    private boolean purchase(Player player, LolItem item) {
        if (item == null) return false;

        BaseChampion champ = championManager.getChampion(player);
        PlayerInventoryManager inv = getOrCreate(player);
        ConsumableManager cm = LolPlugin.getInstance().getConsumableManager();
        var hb = LolPlugin.getInstance().getHotbarManager();

        // ── GESTION DES CONSOMMABLES ──
        if (item.getCategory() == ItemCategory.CONSUMABLE && cm != null) {
            int gold = goldManager.getGold(player.getUniqueId());
            if (gold < item.getGoldCost()) {
                player.sendMessage(Component.text(
                        String.format("❌ Or insuffisant! Tu as %d or, il en faut %d.", gold, item.getGoldCost()),
                        NamedTextColor.RED));
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return false;
            }
            if (!goldManager.spendGold(player.getUniqueId(), item.getGoldCost())) return false;
            hb.addConsumable(player, item.getId());
            hb.showPage2(player, champ); // basculer sur la page utilitaire pour voir le consommable
            player.sendActionBar(Component.text("🧪 " + item.getDisplayName() + " ajouté (page 2)", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
            return true;
        }

        // ── GESTION DES ITEMS NORMAUX (Avec Arbre de Recette Dynamique) ──

        // 1. Trouver TOUS les composants requis déjà possédés dans l'inventaire
        java.util.List<Integer> slotsToConsume = new java.util.ArrayList<>();
        int totalDiscount = 0;

        if (item.getBuildsFrom() != null) {
            for (String componentId : item.getBuildsFrom()) {
                for (int i = 0; i < 6; i++) {
                    LolItem owned = inv.getItem(i);
                    // Si on possède le composant et qu'on ne l'a pas déjà marqué pour destruction
                    if (owned != null && owned.getId().equals(componentId) && !slotsToConsume.contains(i)) {
                        slotsToConsume.add(i);
                        totalDiscount += owned.getGoldCost();
                        break; // On passe au composant requis suivant de la liste
                    }
                }
            }
        }

        // 2. Calcul du coût effectif (Prix total - Réduction accumulée des composants possédés)
        int effectiveCost = Math.max(0, item.getGoldCost() - totalDiscount);
        int currentGold = goldManager.getGold(player.getUniqueId());

        if (currentGold < effectiveCost) {
            if (totalDiscount > 0) {
                player.sendMessage(Component.text(
                        String.format("❌ Or insuffisant pour upgrader! Il te faut %d or (différence avec tes composants).", effectiveCost),
                        NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text(
                        String.format("❌ Or insuffisant! Tu as %d or, il en faut %d.", currentGold, item.getGoldCost()),
                        NamedTextColor.RED));
            }
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }

        // 3. Vérification de la place réelle (Taille actuelle - composants détruits + 1 nouvel item)
        int currentItemCount = 0;
        for (int i = 0; i < 6; i++) {
            if (inv.getItem(i) != null) currentItemCount++;
        }

        if (currentItemCount - slotsToConsume.size() + 1 > 6) {
            player.sendMessage(Component.text("❌ Inventaire plein (6/6 items)!", NamedTextColor.RED));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }

        // 4. Retrait de l'or
        if (!goldManager.spendGold(player.getUniqueId(), effectiveCost)) return false;

        // 5. Retrait des composants (sans remboursement d'or, transférés dans l'upgrade)
        // On stocke temporairement les items pour un éventuel rollback en cas d'erreur inattendue
        java.util.Map<Integer, LolItem> removedComponents = new java.util.HashMap<>();
        for (int slot : slotsToConsume) {
            LolItem component = inv.getItem(slot);
            removedComponents.put(slot, component);

            inv.removeItemNoRefund(champ, slot);
            hb.removeItem(player, component.getId());
        }

        // 6. Équipement de l'item final
        if (!inv.equipItem(player, champ, item)) {
            // ROLLBACK en cas d'échec d'équipement
            goldManager.addGold(player.getUniqueId(), effectiveCost);
            for (java.util.Map.Entry<Integer, LolItem> entry : removedComponents.entrySet()) {
                LolItem comp = entry.getValue();
                comp.applyStats(champ.getStats(), champ.getHPSystem(), champ.getResourceSystem());
                inv.equipItem(player, champ, comp);
                hb.addItem(player, comp.getId());
            }
            player.sendMessage(Component.text("❌ Impossible d'équiper l'item.", NamedTextColor.RED));
            return false;
        }

        // 7. Finalisation visuelle et sonore
        hb.addItem(player, item.getId());
        hb.renderPage(player, champ);
        hudManager.updateHUD(player, champ);

        if (!slotsToConsume.isEmpty()) {
            player.sendActionBar(Component.text(
                    String.format("⬆ Upgrade! %s acheté (coût effectif : %d) | Or: %d",
                            item.getDisplayName(), effectiveCost, goldManager.getGold(player.getUniqueId())),
                    NamedTextColor.GREEN));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        } else {
            player.sendActionBar(Component.text(
                    String.format("💰 Or: %d | %s acheté!", goldManager.getGold(player.getUniqueId()),
                            item.getDisplayName()), NamedTextColor.GOLD));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
        }

        return true;
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

        // CORRECTION : Attendre 1 tick pour s'assurer qu'on ne change pas juste d'onglet/vue dans la boutique
        org.bukkit.Bukkit.getScheduler().runTask(LolPlugin.getInstance(), () -> {
            if (!p.isOnline()) return;

            // Si le nouvel inventaire ouvert par le joueur n'est plus la boutique LoL, on clean
            if (!ShopGUI.isShopInventory(p.getOpenInventory().title())) {
                shopGUI.cleanup(p);
            }
        });
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
            case "elixir_wrath", "elixir_wrath2" -> {
                if (!checkElixirLevel(player)) break;
                cm.useElixirWrath(player);
            }
            case "elixir_iron", "elixir_iron2" -> {
                if (!checkElixirLevel(player)) break;
                cm.useElixirIron(player);
            }
            case "elixir_sorcery", "elixir_sorcery2" -> {
                if (!checkElixirLevel(player)) break;
                cm.useElixirSorcery(player);
            }
            case "stealth_ward", "stealth_ward2"            -> cm.placeWard(player, false);
            case "control_ward", "control_ward2"            -> cm.placeControlWard(player);
            case "farsight", "farsight2"                    -> cm.placeWard(player, true);
            case "oracle_lens", "oracle_lens2"              -> LolPlugin.getInstance().getAbilityListener().revealNearbyWards(player);
            case "cappa_juice", "cappa_juice2"              -> cm.useHealthPotion(player);
            default -> player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "Consommable: " + id, net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Vrai si le slot correspond à un emplacement d'item LoL (hotbar ou inventaire). */
    private boolean isLolItemSlot(int slot) {
        for (int s : PlayerInventoryManager.ITEM_SLOTS) if (s == slot) return true;
        for (int s : fr.lolmc.item.HotbarManager.INV_ITEM_SLOTS) if (s == slot) return true;
        return false;
    }

    public GoldManager getGoldManager() { return goldManager; }
    public Map<UUID, PlayerInventoryManager> getInventoryManagers() { return inventoryManagers; }

    /** Les Élixirs nécessitent le niveau 9 (comme en LoL). */
    private boolean checkElixirLevel(org.bukkit.entity.Player player) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return false;
        int lvl = cm.getChampion(player).getLevelSystem().getLevel();
        if (lvl < 9) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "❌ Les Élixirs nécessitent le niveau 9 (tu es niveau " + lvl + ").",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return false;
        }
        return true;
    }
}