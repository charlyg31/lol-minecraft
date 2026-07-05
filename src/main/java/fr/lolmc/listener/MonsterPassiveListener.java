package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.JungleManager;
import fr.lolmc.game.JungleManager.MonsterType;
import fr.lolmc.game.MonsterAbilities;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Gère les mécaniques PASSIVES des monstres déclenchées quand on les frappe.
 * Notamment : le buff Rouge brûle et ralentit l'attaquant.
 */
public class MonsterPassiveListener implements Listener {

    /**
     * Convertit les dégâts vanilla en dégâts virtuels pour les entités
     * à HP virtuels (Baron 8500, Atakhan 9000, Elder 5000, canon 1257...).
     * Laisse passer le coup fatal (isDying) pour EntityDeathEvent.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVirtualHpDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        if (!fr.lolmc.util.VirtualHP.has(le)) return;
        if (fr.lolmc.util.VirtualHP.isDying(le)) return; // coup fatal → vanilla
        e.setCancelled(true);
        Player src = null;
        if (e instanceof EntityDamageByEntityEvent be) {
            if (be.getDamager() instanceof Player p) src = p;
            else if (be.getDamager() instanceof org.bukkit.entity.Projectile proj
                    && proj.getShooter() instanceof Player p2) src = p2;
        }
        fr.lolmc.util.VirtualHP.damage(le, e.getDamage(), src);
    }

    @EventHandler
    public void onMonsterHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity mob)) return;
        if (!JungleManager.isJungleMonster(mob)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!LolPlugin.getInstance().getChampionManager().hasChampion(attacker)) return;

        MonsterType type = JungleManager.getMonsterType(mob);
        if (type == null) return;

        // (LoL : les buffs ne punissent pas d'être frappés — la brûlure du
        //  Rouge vient de sa capacité redSmash, pas d'une riposte passive)
    }

    /** Quand un monstre de jungle ou un sbire FRAPPE : déclenche son animation d'attaque. */
    @EventHandler
    public void onMonsterAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof LivingEntity dmg
                && (JungleManager.isJungleMonster(dmg) || fr.lolmc.game.MinionManager.isMinion(dmg))) {
            fr.lolmc.util.MobAnimator.triggerAttack(dmg.getUniqueId());
        }
    }
}
