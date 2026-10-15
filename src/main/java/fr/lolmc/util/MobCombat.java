package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Statistiques LoL des sbires et monstres (dégâts d'attaque, vitesse d'attaque, armure,
 * résistance magique, or, XP), figées à leur apparition dans le PDC, et application des dégâts
 * qu'ils reçoivent : un seul chemin pour les auto-attaques, les sorts et les dégâts vanilla.
 */
public final class MobCombat {

    private MobCombat() {}

    private static NamespacedKey key(String name) {
        return new NamespacedKey(LolPlugin.getInstance(), name);
    }

    /** Fixe les statistiques LoL d'un sbire/monstre (à l'apparition). */
    public static void setStats(LivingEntity e, double ad, double attackSpeed, double armor, double mr,
                                int gold, double xp) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(key("mob_ad"), PersistentDataType.DOUBLE, ad);
        pdc.set(key("mob_as"), PersistentDataType.DOUBLE, attackSpeed);
        pdc.set(key("mob_armor"), PersistentDataType.DOUBLE, armor);
        pdc.set(key("mob_mr"), PersistentDataType.DOUBLE, mr);
        pdc.set(key("mob_gold"), PersistentDataType.INTEGER, gold);
        pdc.set(key("mob_xp"), PersistentDataType.DOUBLE, xp);
    }

    private static double getDouble(LivingEntity e, String name, double def) {
        Double v = e.getPersistentDataContainer().get(key(name), PersistentDataType.DOUBLE);
        return v != null ? v : def;
    }

    public static double getAD(LivingEntity e)          { return getDouble(e, "mob_ad", 0); }
    public static double getAttackSpeed(LivingEntity e) { return getDouble(e, "mob_as", 1.0) * getDouble(e, "mob_as_mult", 1.0); }
    /** Multiplicateur temporaire de vitesse d'attaque (super-sbires renforcés par le Baron). */
    public static void setAttackSpeedMult(LivingEntity e, double m) {
        e.getPersistentDataContainer().set(key("mob_as_mult"), PersistentDataType.DOUBLE, m);
    }
    public static double getArmor(LivingEntity e)       { return getDouble(e, "mob_armor", 0) + boardingBonus(e); }
    public static double getMR(LivingEntity e)          { return getDouble(e, "mob_mr", 0) + boardingBonus(e); }

    /** Brise-coques (Section d'abordage) : armure et RM bonus d'un sbire de siège ou super-sbire allié proche. */
    public static void setBoardingBonus(LivingEntity e, double amount) {
        e.getPersistentDataContainer().set(key("boarding"), PersistentDataType.DOUBLE, amount);
        e.getPersistentDataContainer().set(key("boarding_until"), PersistentDataType.LONG, System.currentTimeMillis() + 1200L);
    }

    private static double boardingBonus(LivingEntity e) {
        Long until = e.getPersistentDataContainer().get(key("boarding_until"), PersistentDataType.LONG);
        return until != null && until > System.currentTimeMillis() ? getDouble(e, "boarding", 0) : 0;
    }
    public static double getXP(LivingEntity e, double def) { return getDouble(e, "mob_xp", def); }
    public static int getGold(LivingEntity e, int def) {
        Integer v = e.getPersistentDataContainer().get(key("mob_gold"), PersistentDataType.INTEGER);
        return v != null ? v : def;
    }

    /** Multiplicateur de dégâts LoL selon une résistance (gère les résistances négatives). */
    public static double resistMultiplier(double resist) {
        if (resist >= 0) return 100.0 / (100.0 + resist);
        return 2.0 - 100.0 / (100.0 - resist);
    }

    /**
     * Inflige des dégâts à un sbire/monstre : bonus contre les sbires de l'attaquant, armure ou
     * résistance magique de la cible (avec la pénétration de l'attaquant), puis PV virtuels.
     * Avant, les sorts et AA ignoraient les résistances et, sur un sbire, les PV virtuels
     * (le canon prenait ses dégâts sur sa vie Minecraft plafonnée).
     *
     * @return true si la cible est morte
     */
    public static boolean damage(Player attacker, LivingEntity target, double raw, DamageUtil.Type type) {
        if (target == null || target.isDead()) return false;
        double amount = raw;
        var cm = LolPlugin.getInstance().getChampionManager();
        var stats = attacker != null && cm.hasChampion(attacker) ? cm.getChampion(attacker).getStats() : null;
        // Attaquer un sbire/monstre depuis un bush révèle aussi l'attaquant 2 s (LoL)
        var bush = LolPlugin.getInstance().getBushManager();
        if (bush != null && stats != null) bush.revealOnAttack(attacker);
        if (stats != null && fr.lolmc.game.MinionManager.isMinion(target))
            amount *= 1.0 + stats.getMinionDamageBonus();
        // Rancune ancestrale des dragons, sbires renforcés par le Baron, Regard du Baron
        amount *= fr.lolmc.game.JungleBuffs.dragonDamageMultiplier(target, attacker);
        amount *= fr.lolmc.game.JungleBuffs.minionDamageMultiplier(target, attacker);
        amount *= fr.lolmc.game.MonsterAbilities.incomingMultiplier(target, attacker);
        if (attacker != null) amount *= fr.lolmc.item.ItemEffects.outgoingMultiplier(attacker, target, type, false); // objets
        if (attacker != null) fr.lolmc.game.JungleBuffs.onDamageDealtUnit(attacker); // âme Océanique (30 %)
        if (type == DamageUtil.Type.PHYSICAL) {
            amount = stats != null ? stats.calcPhysicalDamageVs(amount, getArmor(target))
                                   : amount * resistMultiplier(getArmor(target));
        } else if (type == DamageUtil.Type.MAGICAL) {
            amount = stats != null ? stats.calcMagicalDamageVs(amount, getMR(target))
                                   : amount * resistMultiplier(getMR(target));
        }
        if (!VirtualHP.has(target)) {
            // Entité sans PV virtuels (ancienne apparition) : on les crée à partir de sa vie actuelle
            double max = Compat.maxHealthOf(target);
            double missing = max - target.getHealth();
            VirtualHP.init(target, max);
            amount += missing;
        }
        return VirtualHP.damage(target, amount, attacker) <= 0;
    }
}
