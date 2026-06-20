package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.stats.ChampionStats;
import org.bukkit.entity.Player;

/**
 * Routeur central de dégâts — chaîne complète LoL.
 *
 * Ordre d'application (après que le sort a déjà calculé la réduction armure/MR) :
 *   1. Réductions défensives de la cible (plate + %, AA si auto-attaque)
 *   2. Absorption par les boucliers (anti-magie en priorité si dégât magique)
 *   3. Dégâts restants → HP
 *   4. Vol de vie / omnivamp de l'attaquant
 *   5. Passifs défensifs (Sterak's, Guardian Angel) + DoT (Liandry's)
 */
public class DamageUtil {

    public enum Type { PHYSICAL, MAGICAL, TRUE }

    /**
     * Inflige des dégâts déjà réduits par l'armure/MR.
     * @param amount dégâts post-résistance
     */
    public static void damage(Player attacker, Player victim, double rawAmount, boolean isAbility, Type type) {
        ChampionManager cm = LolPlugin.getInstance().getChampionManager();
        if (cm == null || !cm.hasChampion(victim)) {
            victim.damage(rawAmount / 25.0); // fallback non-champion
            return;
        }

        BaseChampion vc = cm.getChampion(victim);
        ChampionStats vs = vc.getStats();
        ChampionStats as = (attacker != null && cm.hasChampion(attacker))
                ? cm.getChampion(attacker).getStats() : null;

        // 1. Résistance (armure/MR avec pénétration de l'attaquant)
        double afterResist;
        if (type == Type.TRUE || as == null) {
            afterResist = rawAmount;
        } else if (type == Type.MAGICAL) {
            afterResist = as.calcMagicalDamage(rawAmount, vs);
        } else {
            afterResist = as.calcPhysicalDamage(rawAmount, vs);
        }

        // 2. Réductions défensives plates/% de la cible
        double finalDmg = afterResist;
        if (type != Type.TRUE) {
            finalDmg = vs.applyDamageReductions(afterResist, !isAbility);
        }

        // 2. Boucliers (les vrais dégâts passent à travers ? Non, les boucliers absorbent tout en LoL)
        double afterShield = vs.absorbWithShield(finalDmg, type == Type.MAGICAL);

        // 3. HP
        vc.getHPSystem().takeDamage(afterShield);
        // Révéler la victime si elle est dans un bush (combat = visible)
        var bushMgr = LolPlugin.getInstance().getBushManager();
        if (bushMgr != null) bushMgr.revealOnDamage(victim);
        var baseMgr = LolPlugin.getInstance().getBaseManager();
        if (baseMgr != null) baseMgr.onDamage(victim);

        // 4. Vol de vie / omnivamp pour l'attaquant
        if (attacker != null && cm.hasChampion(attacker)) {
            BaseChampion ac = cm.getChampion(attacker);
            double vamp = isAbility
                ? ac.getStats().getFinalOmnivamp()
                : ac.getStats().getFinalLifeSteal() + ac.getStats().getFinalOmnivamp();
            if (vamp > 0) ac.getHPSystem().heal(afterShield * vamp);
        }

        // 5. Passifs défensifs + DoT
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) {
            pm.onDamageTaken(victim, afterShield);
            if (isAbility && attacker != null && cm.hasChampion(attacker)) {
                pm.onAbilityDamage(attacker, victim, afterShield, type == Type.MAGICAL);
            }
        }

        // 6. Affichage
        var hud = LolPlugin.getInstance().getHUDManager();
        if (hud != null) {
            hud.updateHUD(victim, vc);
            if (attacker != null && cm.hasChampion(attacker))
                hud.updateHUD(attacker, cm.getChampion(attacker));
        }
    }

    // ── Raccourcis (compat avec l'ancien code) ──

    /** Dégâts déjà calculés, type physique par défaut (compat ancienne signature). */
    public static void damage(Player attacker, Player victim, double amount, boolean isAbility) {
        damage(attacker, victim, amount, isAbility, Type.PHYSICAL);
    }

    /** Dégât de sort (physique par défaut — les sorts magiques utilisent abilityDamageMagic). */
    public static void abilityDamage(Player attacker, Player victim, double amount) {
        damage(attacker, victim, amount, true, Type.PHYSICAL);
    }

    public static void abilityDamageMagic(Player attacker, Player victim, double amount) {
        damage(attacker, victim, amount, true, Type.MAGICAL);
    }

    public static void trueDamage(Player attacker, Player victim, double amount) {
        damage(attacker, victim, amount, true, Type.TRUE);
    }
}
