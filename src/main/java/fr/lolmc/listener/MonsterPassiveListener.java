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

    @EventHandler
    public void onMonsterHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity mob)) return;
        if (!JungleManager.isJungleMonster(mob)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!LolPlugin.getInstance().getChampionManager().hasChampion(attacker)) return;

        MonsterType type = JungleManager.getMonsterType(mob);
        if (type == null) return;

        // Buff Rouge : brûlure + ralentissement sur qui le frappe
        if (type == MonsterType.RED_BUFF) {
            MonsterAbilities.applyRedBuffDebuff(attacker);
        }
    }
}
