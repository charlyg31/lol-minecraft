package fr.lolmc.champion.base;

import fr.lolmc.LolPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prochaine attaque de base renforcée (LoL : Q de Garen et de Nasus, W de Darius, Q de Leona, E de
 * Blitzcrank). Le sort arme l'effet et remet à zéro le délai d'attaque ; la prochaine attaque de
 * base dans la fenêtre le consomme. Avant, ces sorts frappaient tout de suite la cible visée.
 */
public final class EmpoweredAttack {

    private EmpoweredAttack() {}

    /** Effet appliqué à l'impact de l'attaque renforcée. */
    @FunctionalInterface
    public interface Effect {
        /**
         * @param normalDamage dégâts que l'attaque de base aurait infligés
         * @return true si l'effet remplace les dégâts normaux de l'attaque (sinon ils s'ajoutent)
         */
        boolean apply(Player attacker, LivingEntity target, double normalDamage);
    }

    private record Armed(Effect effect, long until) {}

    private static final Map<UUID, Armed> ARMED = new ConcurrentHashMap<>();

    /** Arme la prochaine attaque de base pendant {@code durationMs} et remet à zéro son délai. */
    public static void arm(Player attacker, long durationMs, Effect effect) {
        ARMED.put(attacker.getUniqueId(), new Armed(effect, System.currentTimeMillis() + durationMs));
        var aa = LolPlugin.getInstance().getAutoAttackManager();
        if (aa != null) aa.resetAttackTimer(attacker);
    }

    /** Vrai si une attaque renforcée est prête. */
    public static boolean isArmed(UUID id) {
        Armed a = ARMED.get(id);
        return a != null && a.until() > System.currentTimeMillis();
    }

    /** Retire et renvoie l'effet armé (null s'il n'y en a pas ou s'il a expiré). */
    public static Effect take(Player attacker) {
        Armed a = ARMED.remove(attacker.getUniqueId());
        return a != null && a.until() > System.currentTimeMillis() ? a.effect() : null;
    }

    public static void clear(UUID id) { ARMED.remove(id); }
}
