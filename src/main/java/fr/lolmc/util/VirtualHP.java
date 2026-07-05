package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * HP virtuels pour les entités dépassant la limite Minecraft de 1024.
 *
 * Mojang cape l'attribut max_health à [1, 1024] : Baron 8500, Atakhan 9000,
 * Elder 5000, canon 1257 étaient silencieusement clampés à 1024.
 *
 * Principe :
 *  - Les vrais HP (max + courants) sont stockés en PDC sur l'entité.
 *  - L'attribut vanilla reste à 1024 ; la vie vanilla est maintenue
 *    proportionnelle (pour la barre de boss native / autres plugins).
 *  - Tous les dégâts passent par damage() qui décrémente le virtuel.
 *  - À 0 : l'entité est tuée via un coup vanilla attribué au tueur,
 *    ce qui déclenche EntityDeathEvent → les rewards existants marchent.
 */
public final class VirtualHP {

    private VirtualHP() {}

    private static NamespacedKey keyMax() {
        return new NamespacedKey(LolPlugin.getInstance(), "vhp_max");
    }
    private static NamespacedKey keyCur() {
        return new NamespacedKey(LolPlugin.getInstance(), "vhp_cur");
    }
    private static NamespacedKey keyDying() {
        return new NamespacedKey(LolPlugin.getInstance(), "vhp_dying");
    }

    /** Active les HP virtuels sur une entité (à appeler au spawn si maxHp > 1024). */
    public static void init(LivingEntity entity, double maxHp) {
        var pdc = entity.getPersistentDataContainer();
        pdc.set(keyMax(), PersistentDataType.DOUBLE, maxHp);
        pdc.set(keyCur(), PersistentDataType.DOUBLE, maxHp);
        // Vanilla : plein à 1024 (l'attribut a déjà été clampé par Minecraft)
        var attr = entity.getAttribute(Compat.maxHealth());
        if (attr != null) {
            attr.setBaseValue(Math.min(maxHp, 1024.0));
            entity.setHealth(attr.getValue());
        }
    }

    /** True si l'entité utilise des HP virtuels. */
    public static boolean has(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(keyMax(), PersistentDataType.DOUBLE);
    }

    public static double getMax(LivingEntity entity) {
        Double v = entity.getPersistentDataContainer().get(keyMax(), PersistentDataType.DOUBLE);
        return v != null ? v : entity.getMaxHealth();
    }

    public static double getCurrent(LivingEntity entity) {
        Double v = entity.getPersistentDataContainer().get(keyCur(), PersistentDataType.DOUBLE);
        return v != null ? v : entity.getHealth();
    }

    /** True pendant le coup fatal vanilla (à laisser passer dans les listeners). */
    public static boolean isDying(LivingEntity entity) {
        Byte b = entity.getPersistentDataContainer().get(keyDying(), PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    /**
     * Inflige des dégâts virtuels. Met à jour la HealthBar et la vie vanilla
     * proportionnelle. À 0 : tue l'entité avec attribution au tueur.
     *
     * @return HP virtuels restants (0 si mort)
     */
    public static double damage(LivingEntity entity, double amount, Player killer) {
        var pdc = entity.getPersistentDataContainer();
        double max = getMax(entity);
        double cur = Math.max(0, getCurrent(entity) - Math.max(0, amount));
        pdc.set(keyCur(), PersistentDataType.DOUBLE, cur);

        if (cur <= 0) {
            // Coup fatal vanilla pour déclencher EntityDeathEvent avec killer
            pdc.set(keyDying(), PersistentDataType.BYTE, (byte) 1);
            if (killer != null) entity.damage(4096.0, killer);
            else entity.setHealth(0);
            return 0;
        }

        // Vie vanilla proportionnelle (min 1 pour ne pas tuer par accident)
        var attr = entity.getAttribute(Compat.maxHealth());
        double vanillaMax = attr != null ? attr.getValue() : entity.getMaxHealth();
        entity.setHealth(Math.max(1.0, cur / max * vanillaMax));

        HealthBar.update(entity, cur, max);
        return cur;
    }
}
