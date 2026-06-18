package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;

public class AbilityListener implements Listener {

    private final ChampionManager manager;

    public AbilityListener(ChampionManager manager) {
        this.manager = manager;
        // Affichage portée toutes les 2 ticks
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : LolPlugin.getInstance().getServer().getOnlinePlayers()) {
                    if (manager.hasChampion(p)) manager.getChampion(p).displayRangeIfHoldingAbility(p);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
    }

    // Clic droit sur un joueur → sort actif sur cible
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player caster = e.getPlayer();
        if (!manager.hasChampion(caster)) return;
        if (!(e.getRightClicked() instanceof Player target)) return;

        int slot = caster.getInventory().getHeldItemSlot();
        if (slot < 0 || slot > 4) return;

        e.setCancelled(true);
        manager.getChampion(caster).tryUseAbility(caster, slot, target);
    }

    // Clic droit dans le vide → self-cast (W/E buffs, AoE autour du caster)
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player caster = e.getPlayer();
        if (!manager.hasChampion(caster)) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        int slot = caster.getInventory().getHeldItemSlot();
        if (slot < 1 || slot > 4) return; // slot 0 = AA uniquement via dégâts

        e.setCancelled(true);
        // Self-cast : passe null comme cible (les sorts gèrent eux-mêmes)
        manager.getChampion(caster).tryUseAbility(caster, slot, null);
    }

    // Clic gauche sur entité → AA (slot 0)
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player caster)) return;
        if (!(e.getEntity() instanceof Player target)) return;
        if (!manager.hasChampion(caster)) return;

        if (caster.getInventory().getHeldItemSlot() == 0) {
            e.setCancelled(true);
            manager.getChampion(caster).tryUseAbility(caster, 0, target);
        }
    }

    // Changement de slot → refresh tooltip
    @EventHandler
    public void onSlotChange(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasChampion(p)) return;
        int s = e.getNewSlot();
        if (s >= 0 && s <= 4) manager.getChampion(p).refreshSlot(p, s);
    }

    // Bloquer modification des slots 0-4
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!manager.hasChampion(p)) return;
        if (e.getSlot() >= 0 && e.getSlot() <= 4) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (!manager.hasChampion(e.getPlayer())) return;
        if (e.getPlayer().getInventory().getHeldItemSlot() <= 4) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.removeChampion(e.getPlayer());
    }
}
