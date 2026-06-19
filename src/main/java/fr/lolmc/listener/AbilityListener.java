package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.item.HotbarManager;
import fr.lolmc.item.PassiveManager;
import fr.lolmc.manager.ChampionManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class AbilityListener implements Listener {

    private final ChampionManager manager;

    public AbilityListener(ChampionManager manager) {
        this.manager = manager;
        // Affichage portée toutes les 2 ticks (uniquement page 1, slots sorts)
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : LolPlugin.getInstance().getServer().getOnlinePlayers()) {
                    if (!manager.hasChampion(p)) continue;
                    if (LolPlugin.getInstance().getHotbarManager().getPage(p) != 1) continue;
                    manager.getChampion(p).displayRangeIfHoldingAbility(p);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
    }

    private HotbarManager hotbar() { return LolPlugin.getInstance().getHotbarManager(); }

    // ── Clic droit sur un joueur → sort ciblé OU item actif ──
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player caster = e.getPlayer();
        if (!manager.hasChampion(caster)) return;
        if (!(e.getRightClicked() instanceof Player target)) return;

        e.setCancelled(true);
        int slot = caster.getInventory().getHeldItemSlot();
        ItemStack held = caster.getInventory().getItem(slot);
        handleSlotAction(caster, slot, held, target);
    }

    // ── Clic droit dans le vide → self-cast / Flash / actif / page ──
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player caster = e.getPlayer();
        if (!manager.hasChampion(caster)) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        int slot = caster.getInventory().getHeldItemSlot();
        ItemStack held = caster.getInventory().getItem(slot);
        if (!HotbarManager.isLolItem(held)) return;

        e.setCancelled(true);

        // Shift + clic droit sur un sort = apprendre / améliorer (dépenser un point)
        if (caster.isSneaking() && "ability".equals(HotbarManager.getType(held))
                && slot >= 1 && slot <= 4) {
            tryLevelUpAbility(caster, slot);
            return;
        }

        handleSlotAction(caster, slot, held, null);
    }

    /**
     * Améliore un sort et notifie le joueur.
     */
    private void tryLevelUpAbility(Player caster, int slot) {
        BaseChampion champ = manager.getChampion(caster);
        if (champ.levelUpAbility(caster, slot)) {
            int rank = champ.getLevelSystem().getAbilityRank(slot);
            String name = champ.getAbility(slot).getName();
            caster.sendActionBar(net.kyori.adventure.text.Component.text(
                "✨ " + name + " amélioré au rang " + rank + "!",
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
            caster.playSound(caster.getLocation(),
                org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
            // Rafraîchir le clignotement
            updateAbilityGlow(caster, champ);
        } else {
            caster.sendActionBar(net.kyori.adventure.text.Component.text(
                "❌ Impossible d'améliorer (pas de point ou rang max / niveau requis)",
                net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    /**
     * Fait clignoter les sorts améliorables (ajoute l'enchantement glow).
     */
    public void updateAbilityGlow(Player player, BaseChampion champ) {
        if (hotbar().getPage(player) != 1) return;
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null) continue;
            boolean canUp = champ.getLevelSystem().canLevelUp(slot);
            var meta = item.getItemMeta();
            if (meta == null) continue;
            if (canUp) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            } else {
                meta.removeEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING);
            }
            item.setItemMeta(meta);
        }
    }

    /**
     * Route l'action selon le type PDC de l'item tenu.
     */
    private void handleSlotAction(Player caster, int slot, ItemStack held, Player target) {
        String type = HotbarManager.getType(held);
        if (type == null) return;
        BaseChampion champ = manager.getChampion(caster);

        switch (type) {
            case "ability" -> {
                // slot 0 = AA (géré par clic gauche), 1-4 = sorts
                if (slot >= 1 && slot <= 4) {
                    champ.tryUseAbility(caster, slot, target);
                }
            }
            case "flash" -> LolPlugin.getInstance().getFlashManager().useFlash(caster);
            case "item" -> {
                String itemId = HotbarManager.getId(held);
                PassiveManager pm = LolPlugin.getInstance().getPassiveManager();
                if (pm != null && itemId != null) pm.activateItem(caster, itemId);
            }
            case "consumable" -> {
                String consId = HotbarManager.getId(held);
                LolPlugin.getInstance().getShopListener().useConsumablePublic(caster, consId);
                hotbar().removeConsumable(caster, consId);
                hotbar().renderPage(caster, champ);
            }
            case "page" -> hotbar().switchPage(caster, champ);
        }
    }

    // ── Clic gauche sur entité → AA (slot 0) ──
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player caster)) return;
        if (!(e.getEntity() instanceof Player target)) return;
        if (!manager.hasChampion(caster)) return;

        e.setCancelled(true);
        int slot = caster.getInventory().getHeldItemSlot();
        ItemStack held = caster.getInventory().getItem(slot);
        // Auto-attaque uniquement si on tient le slot AA (type ability slot 0)
        if ("ability".equals(HotbarManager.getType(held)) && slot == 0) {
            manager.getChampion(caster).tryUseAbility(caster, 0, target);
            PassiveManager pm = LolPlugin.getInstance().getPassiveManager();
            if (pm != null) pm.onAutoAttack(caster, target);
        }
    }

    // ── Refresh tooltip au changement de slot (sorts uniquement) ──
    @EventHandler
    public void onSlotChange(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasChampion(p)) return;
        if (hotbar().getPage(p) != 1) return;
        int s = e.getNewSlot();
        if (s >= 1 && s <= 4) manager.getChampion(p).refreshSlot(p, s);
    }

    // ── Bloquer toute manipulation de la hotbar LoL ──
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!manager.hasChampion(p)) return;
        // Bloquer si l'item cliqué OU le slot est un item LoL
        ItemStack clicked = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        if (HotbarManager.isLolItem(clicked) || HotbarManager.isLolItem(cursor)) {
            e.setCancelled(true);
        }
        // Bloquer les slots hotbar 0-8 systématiquement
        if (e.getSlot() >= 0 && e.getSlot() <= 8 && e.getClickedInventory() == p.getInventory()) {
            e.setCancelled(true);
        }
    }

    // ── Empêcher de dropper les items LoL ──
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (HotbarManager.isLolItem(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    // ── Nettoyage mémoire à la déconnexion ──
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        cleanupPlayer(p);
    }

    @EventHandler
    public void onKick(PlayerKickEvent e) {
        cleanupPlayer(e.getPlayer());
    }

    private void cleanupPlayer(Player p) {
        manager.removeChampion(p);
        LolPlugin.getInstance().getHotbarManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getFlashManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getWardManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getTeamManager().removePlayer(p.getUniqueId());
        LolPlugin.getInstance().getPartyManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getMatchmakingManager().cleanup(p.getUniqueId());
    }
}
