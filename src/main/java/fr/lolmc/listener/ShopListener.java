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

    /** Ouvre la boutique pour un joueur (appelable depuis /lol shop). */
    public void openShop(Player player) {
        if (!canShopHere(player)) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "🏪 La boutique n'est accessible qu'a la base !",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        shopGUI.open(player);
    }

    /** LoL : en partie, la boutique n'est accessible qu'à la fontaine (shop.base-only), ou mort. */
    public boolean canShopHere(Player player) {
        // Un champion mort peut acheter et vendre où qu'il soit (LoL)
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR && championManager.hasChampion(player)) return true;
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(player);
        boolean baseOnly = LolPlugin.getInstance().getConfig()
                .getBoolean("shop.base-only", true);
        if (baseOnly && gm != null && gm.isGameRunning()
                && fr.lolmc.util.WorldContext.isInGameWorld(player)) {
            var team = LolPlugin.getInstance().getTeamManager().getTeam(player);
            var mm = fr.lolmc.instance.InstanceHelper.mapManager(player);
            org.bukkit.Location base = (team != null && mm != null) ? mm.getBaseSpawn(team) : null;
            double radius = LolPlugin.getInstance().getConfig().getDouble("shop.base-radius", 15.0);
            return base != null && base.getWorld().equals(player.getWorld())
                    && base.distanceSquared(player.getLocation()) <= radius * radius;
        }
        return true;
    }

    private final java.util.Set<java.util.UUID> awaitingSearch =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    public void awaitSearchInput(Player p) { awaitingSearch.add(p.getUniqueId()); }

    @org.bukkit.event.EventHandler
    public void onChatSearch(io.papermc.paper.event.player.AsyncChatEvent e) {
        if (!awaitingSearch.remove(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        String msg = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        org.bukkit.Bukkit.getScheduler().runTask(LolPlugin.getInstance(), () -> {
            if (!p.isOnline()) return;
            // Même règle que l'ouverture : le joueur a pu quitter la base en tapant sa recherche
            if (!canShopHere(p)) {
                shopGUI.cleanup(p);
                p.sendMessage(Component.text("🏪 La boutique n'est accessible qu'a la base !", NamedTextColor.RED));
                return;
            }
            if (msg.equalsIgnoreCase("annuler")) shopGUI.open(p);
            else shopGUI.setSearch(p, msg);
        });
    }

    // ── Initialiser l'inventaire d'un joueur ──────────────────────
    public PlayerInventoryManager getOrCreate(Player player) {
        return inventoryManagers.computeIfAbsent(
                player.getUniqueId(), _ -> new PlayerInventoryManager());
    }

    public void initPlayer(Player player) {
        getOrCreate(player);
        goldManager.initPlayer(player.getUniqueId());
    }

    public void cleanup(java.util.UUID uuid) {
        inventoryManagers.remove(uuid);
        awaitingSearch.remove(uuid);
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
                // Boutique ouverte à la base puis joueur parti : plus d'achat possible
                if (!canShopHere(player)) {
                    player.closeInventory();
                    player.sendMessage(Component.text(
                            "🏪 La boutique n'est accessible qu'a la base !", NamedTextColor.RED));
                    return;
                }
                handleShopClick(player, raw, event.isRightClick());
            }
            return;
        }

        // ── INVENTAIRE JOUEUR — protéger la hotbar (0-8) et les objets (9-14) ──
        if (!championManager.hasChampion(player)) return;
        // Guard : ne traiter que les clics dans l'inventaire du joueur (pas conteneur ouvert)
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(player.getInventory())) return;

        int slot = event.getSlot();
        boolean isSortSlot = slot >= 0 && slot <= 8;
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

    private void handleShopClick(Player player, int slot, boolean rightClick) {
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
                // purchase() gère déjà item == null en première ligne (retourne false)
                if (purchase(player, it)) {
                    shopGUI.openDetail(player, it.getId()); // rafraîchir (or / dispo)
                }
                return;
            }
            String navId = shopGUI.getDetailNavId(player, slot);
            if (navId != null) { shopGUI.openDetail(player, navId); return; }
            return;
        }

        // ── Vue PARCOURS ──
        // D'abord vérifier si c'est un bouton de pagination
        if (shopGUI.handlePaginationClick(player, slot)) return;
        // Boussole de recherche (jamais routée avant : le clic ne faisait rien)
        if (shopGUI.handleSearchClick(player, slot, rightClick)) return;

        LolItem.ItemCategory cat = shopGUI.getClickedCategory(slot);
        if (cat != null) { shopGUI.open(player, cat); return; }

        String itemId = shopGUI.getClickedItemId(player, slot);
        if (itemId != null) { shopGUI.openDetail(player, itemId); } // ouvre la fiche/recette
    }

    /** Prix réel d'un objet pour ce joueur (composants possédés déduits), pour l'affichage. */
    public int effectiveCost(Player player, LolItem item) {
        if (item.getCategory() == ItemCategory.CONSUMABLE) return item.getGoldCost();
        return getOrCreate(player).effectiveCost(item);
    }

    /**
     * Règles d'unicité de LoL : une seule paire de bottes, un seul objet de jungle, une seule ligne
     * d'objets de support, pas deux fois le même objet légendaire. Les objets consommés par la
     * recette ne comptent pas (on peut améliorer ses bottes). Renvoie le message de refus, ou null.
     */
    private String limitViolation(PlayerInventoryManager inv, LolItem item, java.util.List<Integer> consumed) {
        LolItem.Tier t = item.getTier();
        for (int i = 0; i < 6; i++) {
            LolItem owned = inv.getItem(i);
            if (owned == null || consumed.contains(i)) continue;
            LolItem.Tier ot = owned.getTier();
            if (t == LolItem.Tier.BOOTS && ot == LolItem.Tier.BOOTS) return "Une seule paire de bottes.";
            if (t == LolItem.Tier.JUNGLE && ot == LolItem.Tier.JUNGLE) return "Un seul objet de jungle.";
            if (t == LolItem.Tier.SUPPORT_QUEST && ot == LolItem.Tier.SUPPORT_QUEST) return "Un seul objet de support.";
            if (t == LolItem.Tier.LEGENDARY && owned.getId().equals(item.getId()))
                return "Tu possèdes déjà " + item.getDisplayName() + ".";
        }
        return null;
    }

    /** Achète un item (vérifie la recette, l'or dynamique et la place). Retourne true si l'achat a réussi. */
    private boolean purchase(Player player, LolItem item) {
        if (item == null) return false;
        if (!item.isPurchasable()) {
            player.sendActionBar(Component.text("❌ Cet objet s'obtient par transformation.", NamedTextColor.RED));
            return false;
        }
        // Chaussures magiques : pas de bottes avant d'avoir reçu les Chaussures légèrement magiques
        var rmBoots = LolPlugin.getInstance().getRuneManager();
        var pmBoots = LolPlugin.getInstance().getPassiveManager();
        if (item.getTier() == LolItem.Tier.BOOTS && rmBoots != null && pmBoots != null
                && rmBoots.getPage(player.getUniqueId()).has("magical_footwear")
                && !pmBoots.getState(player).magicalFootwearGiven) {
            player.sendActionBar(Component.text("❌ Chaussures magiques : bottes interdites avant de recevoir les vôtres.", NamedTextColor.RED));
            return false;
        }

        BaseChampion champ = championManager.getChampion(player);
        PlayerInventoryManager inv = getOrCreate(player);
        ConsumableManager cm = LolPlugin.getInstance().getConsumableManager();
        var hb = LolPlugin.getInstance().getHotbarManager();

        // ── GESTION DES CONSOMMABLES ──
        if (item.getCategory() == ItemCategory.CONSUMABLE && cm != null) {
            boolean refillable = item.getId().equals("refillable_potion") || item.getId().equals("refillable_potion2");
            if (refillable && (hb.getConsumables(player).contains("refillable_potion")
                    || hb.getConsumables(player).contains("refillable_potion2"))) {
                player.sendActionBar(Component.text("❌ Tu possèdes déjà une fiole rechargeable (elle se recharge à la base).",
                        NamedTextColor.RED));
                return false;
            }
            // Limites LoL : 5 potions de soin, 2 balises de contrôle
            int max = switch (item.getId()) { case "health_potion" -> 5; case "control_ward" -> 2; default -> 99; };
            if (java.util.Collections.frequency(hb.getConsumables(player), item.getId()) >= max) {
                player.sendActionBar(Component.text("❌ Maximum " + max + " × " + item.getDisplayName() + ".", NamedTextColor.RED));
                return false;
            }
            if (!hb.hasConsumableRoom(player)) {
                player.sendActionBar(Component.text("❌ Plus de place pour les consommables (page 2 pleine).",
                        NamedTextColor.RED));
                return false;
            }
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
            if (refillable) cm.giveRefillable(player); // 2 charges dès l'achat
            hb.showPage2(player, champ); // basculer sur la page utilitaire pour voir le consommable
            player.sendActionBar(Component.text("🧪 " + item.getDisplayName() + " ajouté (page 2)", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
            return true;
        }

        // ── GESTION DES ITEMS NORMAUX (Avec Arbre de Recette Dynamique) ──

        // 1. Composants possédés, en descendant l'arbre de recette (comme LoL)
        java.util.List<Integer> slotsToConsume = inv.recipeSlots(item);
        int totalDiscount = 0;
        for (int i : slotsToConsume) totalDiscount += inv.getItem(i).getGoldCost();

        String refusal = limitViolation(inv, item, slotsToConsume);
        if (refusal != null) {
            player.sendActionBar(Component.text("❌ " + refusal, NamedTextColor.RED));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
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
            goldManager.refundGold(player.getUniqueId(), effectiveCost);
            for (java.util.Map.Entry<Integer, LolItem> entry : removedComponents.entrySet()) {
                LolItem comp = entry.getValue();
                inv.equipItem(player, champ, comp); // réapplique ses stats (pas de second applyStats)
                hb.addItem(player, comp.getId());
            }
            player.sendMessage(Component.text("❌ Impossible d'équiper l'item.", NamedTextColor.RED));
            return false;
        }

        // Rune Remise immédiate : 7,5 % du prix des objets légendaires rendus
        var rm = LolPlugin.getInstance().getRuneManager();
        if (rm != null && item.getTier() == LolItem.Tier.LEGENDARY) rm.onLegendaryPurchase(player, item.getGoldCost());

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
        // La vente suit la même règle que l'achat : à la base uniquement en partie
        if (!canShopHere(player)) {
            player.sendActionBar(Component.text("🏪 Vente possible uniquement à la base !", NamedTextColor.RED));
            return;
        }

        BaseChampion champ = championManager.getChampion(player);
        var soldItem = inv.getItem(lolSlot);
        int refund = inv.sellItem(player, champ, lolSlot);
        if (soldItem != null) {
            var hb = LolPlugin.getInstance().getHotbarManager();
            hb.removeItem(player, soldItem.getId());
            hb.renderPage(player, champ);
        }
        goldManager.refundGold(player.getUniqueId(), refund);
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
            // Fermée pour taper une recherche : garder l'onglet, la boutique va se rouvrir
            if (awaitingSearch.contains(p.getUniqueId())) return;

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
        // La main tient toujours un slot de la hotbar (0-8), entièrement gérée par le plugin
        event.setCancelled(true);
    }

    // ── Consommables ───────────────────────────────────────────────

    /**
     * Appelé par AbilityListener quand un consommable est cliqué dans la hotbar.
     *
     * @return true si le consommable a été réellement utilisé et doit être retiré de la hotbar.
     *         Un usage refusé (HP pleins, potion déjà active, niveau trop bas...) ne consomme rien,
     *         et la fiole rechargeable reste toujours dans l'inventaire (elle se recharge à la base).
     */
    public boolean useConsumablePublic(Player player, String id) {
        ConsumableManager cm = LolPlugin.getInstance().getConsumableManager();
        if (cm == null) return false;
        return switch (id) {
            case "health_potion", "health_potion2" -> cm.useHealthPotion(player);
            case "cappa_juice", "cappa_juice2" -> { // patch 26 : « ce jus ne fait rien »
                player.sendActionBar(Component.text("🍯 Délicieux. Ça ne fait rien.", NamedTextColor.GOLD));
                yield true;
            }
            case "refillable_potion", "refillable_potion2" -> {
                cm.useRefillablePotion(player);
                yield false;
            }
            case "biscuit", "biscuit_will"                  -> cm.useBiscuit(player);
            case "elixir_avarice"                           -> cm.useElixirAvarice(player);
            case "elixir_force"                             -> cm.useElixirForce(player);
            case "elixir_skill"                             -> cm.useElixirSkill(player);
            case "elixir_wrath", "elixir_wrath2"            -> !isElixirLevelTooLow(player) && cm.useElixirWrath(player);
            case "elixir_iron", "elixir_iron2"              -> !isElixirLevelTooLow(player) && cm.useElixirIron(player);
            case "elixir_sorcery", "elixir_sorcery2"        -> !isElixirLevelTooLow(player) && cm.useElixirSorcery(player);
            case "stealth_ward", "stealth_ward2"            -> cm.placeWard(player, false);
            case "control_ward", "control_ward2" -> {
                cm.placeControlWard(player);
                yield true;
            }
            case "farsight", "farsight2"                    -> cm.placeWard(player, true);
            case "oracle_lens", "oracle_lens2" -> {
                LolPlugin.getInstance().getAbilityListener().revealNearbyWards(player);
                yield true;
            }
            default -> {
                player.sendActionBar(net.kyori.adventure.text.Component.text(
                        "Consommable: " + id, net.kyori.adventure.text.format.NamedTextColor.GRAY));
                yield false;
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Vrai si le slot correspond à un emplacement d'item LoL de l'inventaire (9-14). */
    private boolean isLolItemSlot(int slot) {
        return PlayerInventoryManager.getLolSlotIndex(slot) >= 0;
    }

    public GoldManager getGoldManager() { return goldManager; }

    /** Vrai si le niveau du joueur est insuffisant pour les Élixirs (niveau 9 requis, comme en LoL). */
    private boolean isElixirLevelTooLow(org.bukkit.entity.Player player) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return true;
        int lvl = cm.getChampion(player).getLevelSystem().getLevel();
        if (lvl < 9) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "❌ Les Élixirs nécessitent le niveau 9 (tu es niveau " + lvl + ").",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }
        return false;
    }
}