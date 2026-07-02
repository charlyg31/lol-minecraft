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
    // ══════════════════════════════════════════════════════════════
    // SURCHARGES UNIVERSELLES (acceptent sbires, monstres, joueurs)
    // ══════════════════════════════════════════════════════════════

    /** Dégâts sur n'importe quelle entité vivante (joueur, sbire, monstre). */
    public static void damageEntity(Player attacker, org.bukkit.entity.LivingEntity victim,
                                     double rawAmount, boolean isAbility, Type type) {
        if (victim == null || victim.isDead()) return;
        if (victim instanceof Player p) {
            damage(attacker, p, rawAmount, isAbility, type);
        } else {
            // Sbire ou monstre : dégât direct sur les PV (PAS de victim.damage() -> évite StackOverflow)
            double newHealth = Math.max(0, victim.getHealth() - rawAmount);
            victim.setHealth(newHealth);
            victim.getWorld().spawnParticle(org.bukkit.Particle.CRIT,
                    victim.getLocation().add(0, 1, 0), 4, 0.3, 0.3, 0.3);
            if (newHealth <= 0 && attacker != null) {
                // Laisser MC gérer la mort (drop d'or géré par EntityDeathListener)
                victim.setHealth(0);
            }
        }
    }

    public static void abilityDamageEntity(Player a, org.bukkit.entity.LivingEntity v, double amount) {
        damageEntity(a, v, amount, true, Type.PHYSICAL);
    }
    public static void abilityDamageMagicEntity(Player a, org.bukkit.entity.LivingEntity v, double amount) {
        damageEntity(a, v, amount, true, Type.MAGICAL);
    }
    public static void trueDamageEntity(Player a, org.bukkit.entity.LivingEntity v, double amount) {
        damageEntity(a, v, amount, true, Type.TRUE);
    }

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
        // Enregistrer la contribution pour les assists
        if (attacker != null) {
            var rw = LolPlugin.getInstance().getRewardManager();
            if (rw != null) rw.recordDamage(attacker.getUniqueId(), victim.getUniqueId());
        }
        // Tracker dégâts infligés/subis dans le scoreboard de partie
        var msb = LolPlugin.getInstance().getMatchScoreboard();
        if (msb != null && afterShield > 0) {
            if (attacker != null) msb.addDamageDealt(attacker, afterShield);
            msb.addDamageTaken(victim, afterShield);
        }
        // Death recap : enregistrer la source
        var drm = LolPlugin.getInstance().getDeathRecapManager();
        if (drm != null && afterShield > 0 && attacker != null) {
            String src = attacker.getName();
            String typ = dmgType == fr.lolmc.util.DamageUtil.Type.MAGICAL ? "magic"
                       : dmgType == fr.lolmc.util.DamageUtil.Type.TRUE ? "true" : "physical";
            drm.record(victim.getUniqueId(), src, typ, afterShield);
        }
        // Passif Amumu : désormais géré via Toucher Maudit (applyCurse/onMagicHit)
        // dans Amumu.java — plus de renvoi de dégâts ici.
        // Dragon Elder : exécution si la cible tombe sous 20% HP
        if (attacker != null) {
            var ac = LolPlugin.getInstance().getChampionManager().getChampion(attacker);
            // Vérifier si l'attaquant a le buff dragon_elder
            if (attacker.hasPotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH)
                    && vc.getHPSystem().getHPRatio() < 0.20) {
                // +vrai dégâts supplémentaires proportionnels aux HP manquants
                double elderDmg = vc.getHPSystem().getMaxHP() * 0.10;
                vc.getHPSystem().takeDamage(elderDmg);
            }
        }
        // Révéler la victime si elle est dans un bush (combat = visible)
        var bushMgr = LolPlugin.getInstance().getBushManager();
        if (bushMgr != null) bushMgr.revealOnDamage(victim);
        var baseMgr = LolPlugin.getInstance().getBaseManager();
        if (baseMgr != null) baseMgr.onDamage(victim);
        var runeMgr = LolPlugin.getInstance().getRuneManager();
        if (runeMgr != null) runeMgr.onDamageTaken(victim, afterShield);
        // Effets de runes (keystones) si l'attaquant est un joueur
        if (attacker != null) {
            var rm = LolPlugin.getInstance().getRuneManager();
            if (rm != null) {
                rm.onDamageToChampion(attacker, victim, isAbility);
                rm.onConquerorHeal(attacker, afterShield);
                rm.onHitEffects(attacker, victim, afterShield, isAbility);
            }
        }

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

        // 7. Vérifier la mort
        if (vc.getHPSystem().isDead()) {
            hud.triggerDeath(victim, attacker);
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
