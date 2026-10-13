package fr.lolmc.util;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Traduit les effets de potion vanilla posés par les sorts dans le système de combat LoL.
 * Les dégâts vanilla des champions étant entièrement remplacés (DamageUtil/HPSystem),
 * Force, Résistance et Célérité n'avaient sinon aucun effet.
 *
 *   Force      : +10 % de dégâts infligés par niveau
 *   Résistance : -20 % de dégâts subis par niveau (comme en vanilla)
 *   Célérité   : +15 % de vitesse d'attaque par niveau
 */
public final class PotionCombat {

    private PotionCombat() {}

    private static int level(Player p, PotionEffectType type) {
        PotionEffect e = p.getPotionEffect(type);
        return e != null ? e.getAmplifier() + 1 : 0;
    }

    /** Multiplicateur des dégâts infligés par {@code attacker}. */
    public static double damageDealtMultiplier(Player attacker) {
        return 1.0 + 0.10 * level(attacker, PotionEffectType.STRENGTH);
    }

    /** Multiplicateur des dégâts subis par {@code victim} (0 = immunité). */
    public static double damageTakenMultiplier(Player victim) {
        return Math.max(0.0, 1.0 - 0.20 * level(victim, PotionEffectType.RESISTANCE));
    }

    /** Multiplicateur de vitesse d'attaque de {@code attacker}. */
    public static double attackSpeedMultiplier(Player attacker) {
        return 1.0 + 0.15 * level(attacker, PotionEffectType.HASTE);
    }
}
