package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import org.bukkit.entity.Player;

/**
 * Routeur central de dégâts.
 * Tous les sorts doivent passer par ici au lieu de Player.damage()
 * pour que les vrais HP LoL (HPSystem) soient affectés.
 */
public class DamageUtil {

    /**
     * Inflige des dégâts LoL à une cible.
     * @param attacker le lanceur (peut être null pour dégâts purs)
     * @param victim   la cible
     * @param amount   dégâts déjà calculés (après réduction armure/MR)
     * @param isAbility true si sort (pour omnivamp), false si AA
     */
    public static void damage(Player attacker, Player victim, double amount, boolean isAbility) {
        ChampionManager cm = LolPlugin.getInstance().getChampionManager();
        if (cm == null || !cm.hasChampion(victim)) {
            // Fallback: dégâts Minecraft classiques si pas un champion
            victim.damage(amount / 25.0); // échelle réduite
            return;
        }

        BaseChampion vc = cm.getChampion(victim);
        vc.getHPSystem().takeDamage(amount);

        // Déclencher les passifs défensifs (Sterak's, Guardian Angel)
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onDamageTaken(victim, amount);

        // Omnivamp / lifesteal pour l'attaquant
        if (attacker != null && cm.hasChampion(attacker)) {
            BaseChampion ac = cm.getChampion(attacker);
            double vamp = isAbility
                ? ac.getStats().getFinalOmnivamp()
                : ac.getStats().getFinalLifeSteal() + ac.getStats().getFinalOmnivamp();
            if (vamp > 0) ac.getHPSystem().heal(amount * vamp);

            // Déclencher dégât de sort pour DoT (Liandry's, Demonic)
            if (isAbility && pm != null) {
                pm.onAbilityDamage(attacker, victim, amount, true);
            }
        }

        // Mettre à jour l'affichage
        var hud = LolPlugin.getInstance().getHUDManager();
        if (hud != null) {
            hud.updateHUD(victim, vc);
            if (attacker != null && cm.hasChampion(attacker))
                hud.updateHUD(attacker, cm.getChampion(attacker));
        }
    }

    /** Raccourci pour dégâts de sort. */
    public static void abilityDamage(Player attacker, Player victim, double amount) {
        damage(attacker, victim, amount, true);
    }
}
