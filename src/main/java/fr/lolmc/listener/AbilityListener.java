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
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class AbilityListener implements Listener {

    private final ChampionManager manager;

    public AbilityListener(ChampionManager manager) {
        this.manager = manager;
        // Tâche répétée : afficher la portée quand on tient un sort
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : LolPlugin.getInstance().getServer().getOnlinePlayers()) {
                    if (!manager.hasChampion(player)) continue;
                    BaseChampion champ = manager.getChampion(player);
                    champ.displayRangeIfHoldingAbility(player);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L); // toutes les 2 ticks
    }

    // ── Clic gauche (attaque / sort sur cible) ──────────────────
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!manager.hasChampion(player)) return;
        if (!(event.getRightClicked() instanceof Player target)) return;

        BaseChampion champ = manager.getChampion(player);
        int slot = player.getInventory().getHeldItemSlot();

        if (slot >= 0 && slot <= 4) {
            event.setCancelled(true);
            champ.tryUseAbility(player, slot, target);
        }
    }

    // ── Clic droit dans le vide (sorts self-cast / directionnels) ──
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!manager.hasChampion(player)) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        int slot = player.getInventory().getHeldItemSlot();
        if (slot < 1 || slot > 4) return; // slot 0 = AA, pas de self-cast

        BaseChampion champ = manager.getChampion(player);
        BaseAbility ability = champ.getAbilityForSlot(slot);
        if (ability == null) return;

        event.setCancelled(true);

        // Self-cast (W, E sans cible) → passe le joueur lui-même comme cible
        champ.tryUseAbility(player, slot, player);
    }

    // ── Attaque de base (clic gauche sur entité) ─────────────────
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof Player target)) return;
        if (!manager.hasChampion(player)) return;

        BaseChampion champ = manager.getChampion(player);
        int slot = player.getInventory().getHeldItemSlot();

        // Slot 0 = attaque de base
        if (slot == 0) {
            event.setCancelled(true);
            champ.tryUseAbility(player, 0, target);
        }
    }

    // ── Changement de slot → rafraîchir affichage portée ─────────
    @EventHandler
    public void onSlotChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!manager.hasChampion(player)) return;

        // Rafraîchir l'item dans le slot (tooltip à jour)
        BaseChampion champ = manager.getChampion(player);
        int newSlot = event.getNewSlot();
        if (newSlot >= 0 && newSlot <= 4) {
            champ.refreshSlot(player, newSlot);
        }
    }

    // ── Empêcher de dropper/modifier les sorts dans l'inventaire ──
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!manager.hasChampion(player)) return;

        int slot = event.getSlot();
        // Protéger les slots 0-4 (sorts)
        if (slot >= 0 && slot <= 4) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!manager.hasChampion(player)) return;

        int slot = player.getInventory().getHeldItemSlot();
        if (slot >= 0 && slot <= 4) {
            event.setCancelled(true);
        }
    }

    // ── Quitter → nettoyer ───────────────────────────────────────
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.removeChampion(event.getPlayer());
    }
}
