package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.ability.base.BasicAttackAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les auto-attaques façon LoL avec 3 types d'animation :
 *
 *  MELEE  (portée ≤ 3 blocs) : slash SWEEP_ATTACK + impact CRIT au contact.
 *  MAGE   (portée > 3, dégâts MAGICAL) : orbe magique animée (WITCH_MAGIC_PARTICLE)
 *         qui voyage vers la cible avec une traînée.
 *  ADC    (portée > 3, dégâts PHYSICAL) : projectile rapide (CRIT + traînée blanche)
 *         simulant une flèche/balle, avec son de tir.
 *
 * Le type est déduit de la BasicAttackAbility du champion (slot 0).
 */
public class AutoAttackManager {

    /** Les 3 types d'animation d'auto-attaque. */
    public enum AAType { MELEE, MAGE, ADC }

    // Dernière auto-attaque par joueur (pour respecter la cadence)
    private final Map<UUID, Long> lastAttack = new HashMap<>();

    // ════════════════════════════════════════════════════════
    // LOGIQUE PRINCIPALE
    // ════════════════════════════════════════════════════════

    /**
     * Tente une auto-attaque sur un joueur.
     * @return true si l'attaque a eu lieu
     */
    public boolean tryAutoAttack(Player attacker, Player target) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker) || !cm.hasChampion(target)) return false;

        BaseChampion champ = cm.getChampion(attacker);

        // Vérifier portée
        double range = champ.getAutoAttackRange();
        double dist = attacker.getLocation().distance(target.getLocation());
        if (dist > range) {
            attacker.sendActionBar(Component.text("⚔ Hors de portée", NamedTextColor.GRAY));
            return false;
        }
        if (!checkRangeAndCooldown(attacker, champ)) return false;

        double rawDamage = champ.getStats().getFinalAD();
        boolean crit = Math.random() < champ.getStats().getFinalCritChance();
        if (crit) rawDamage *= champ.getStats().getFinalCritDamage();

        // Animation selon le type d'AA du champion
        AAType type = getAAType(champ);
        playAnimation(attacker, target, type, crit);

        // Dégâts
        DamageUtil.damage(attacker, target, rawDamage, false, DamageUtil.Type.PHYSICAL);

        // Passifs on-hit
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onAutoAttack(attacker, target);

        return true;
    }

    /**
     * Auto-attaque sur une entité non-joueur (sbire, monstre de jungle).
     * @return true si l'attaque a été portée
     */
    public boolean tryAutoAttackEntity(Player attacker, LivingEntity target) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return false;
        BaseChampion champ = cm.getChampion(attacker);

        // Vérifier portée
        double range = champ.getAutoAttackRange();
        double dist = attacker.getLocation().distance(target.getLocation());
        if (dist > range) {
            attacker.sendActionBar(Component.text("⚔ Hors de portée", NamedTextColor.GRAY));
            return false;
        }
        if (!checkRangeAndCooldown(attacker, champ)) return false;

        double rawDamage = champ.getStats().getFinalAD();
        boolean crit = Math.random() < champ.getStats().getFinalCritChance();
        if (crit) rawDamage *= champ.getStats().getFinalCritDamage();

        AAType type = getAAType(champ);
        playAnimation(attacker, target, type, crit);

        double newHealth = Math.max(0, target.getHealth() - rawDamage);
        target.setHealth(newHealth);
        target.playHurtAnimation(attacker.getLocation().getYaw());

        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onAutoAttackEntity(attacker, target);

        return true;
    }

    // ════════════════════════════════════════════════════════
    // DÉTERMINATION DU TYPE D'AA
    // ════════════════════════════════════════════════════════

    /**
     * Déduit le type d'animation depuis la BasicAttackAbility du champion.
     *  - Portée > 3 + DamageType MAGICAL → MAGE
     *  - Portée > 3 + DamageType PHYSICAL → ADC
     *  - Portée ≤ 3 → MELEE (quelle que soit le type de dégâts)
     */
    public static AAType getAAType(BaseChampion champ) {
        double range = champ.getAutoAttackRange();
        if (range <= 3.0) return AAType.MELEE;

        BaseAbility aa = champ.getAbility(0);
        if (aa instanceof BasicAttackAbility baa) {
            if (baa.getDamageType() == BaseAbility.DamageType.MAGICAL) return AAType.MAGE;
        }
        return AAType.ADC;
    }

    // ════════════════════════════════════════════════════════
    // ANIMATIONS
    // ════════════════════════════════════════════════════════

    /** Joue l'animation vers une position (tir dans le vide). */
    public void playAnimationToLocation(Player attacker, Location target, AAType type, boolean crit) {
        switch (type) {
            case ADC  -> playADCAnimation(attacker, target);
            case MAGE -> playMageAnimation(attacker, target);
            default   -> {} // mêlée : pas d'animation dans le vide
        }
    }

    /** Joue l'animation d'AA appropriée entre attaquant et cible. */
    private void playAnimation(Player attacker, LivingEntity target, AAType type, boolean crit) {
        switch (type) {
            case MELEE -> playMeleeAnimation(attacker, target, crit);
            case MAGE  -> playMageAnimationLE(attacker, target, crit);
            case ADC   -> playADCAnimationLE(attacker, target, crit);
        }
    }

    /**
     * MÊLÉE : slash SWEEP + impact CRIT au contact.
     * Ligne de particules courte du poignet vers la cible pour simuler le swing.
     */
    private void playMeleeAnimation(Player attacker, LivingEntity target, boolean crit) {
        Location impactLoc = target.getLocation().add(0, 1, 0);
        World world = attacker.getWorld();

        // Ligne de swing (poignet → cible)
        Vector dir = impactLoc.toVector()
                .subtract(attacker.getEyeLocation().toVector()).normalize();
        Location start = attacker.getEyeLocation().add(dir.clone().multiply(0.5));
        double dist = start.distance(impactLoc);
        for (double d = 0; d < Math.min(dist, 2.5); d += 0.3) {
            world.spawnParticle(Particle.SWEEP_ATTACK,
                    start.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
        }

        // Impact
        world.spawnParticle(Particle.SWEEP_ATTACK, impactLoc, 3, 0.2, 0.2, 0.2, 0);
        if (crit) {
            world.spawnParticle(Particle.CRIT, impactLoc, 12, 0.3, 0.3, 0.3, 0.1);
            world.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.1f);
        } else {
            world.spawnParticle(Particle.CRIT, impactLoc, 4, 0.2, 0.2, 0.2, 0.05);
            world.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.0f);
        }
    }

    /**
     * MAGE : orbe magique animée qui voyage de l'attaquant vers la cible.
     * Traînée WITCH_MAGIC_PARTICLE + explosion ENCHANT à l'impact.
     * L'orbe est simulée en ticks (BukkitRunnable) pour un effet fluide.
     */
    // Surcharges Location (pour tir dans le vide ou vers une structure)
    private void playMageAnimation(Player attacker, Location end) {
        playMageAnimation(attacker, end, false);
    }
    private void playADCAnimation(Player attacker, Location end) {
        playADCAnimation(attacker, end, false);
    }

    private void playMageAnimation(Player attacker, Location end, boolean crit) {
        Location start = attacker.getEyeLocation();
        World world = attacker.getWorld();

        double totalDist = start.distance(end);
        int totalSteps = Math.max(4, (int)(totalDist / 0.35));
        Vector step = end.toVector().subtract(start.toVector())
                .normalize().multiply(totalDist / totalSteps);

        // Couleur de l'orbe : bleu-violet magique
        Particle.DustOptions mageDust = new Particle.DustOptions(
                Color.fromRGB(120, 60, 255), 1.2f);
        Particle.DustOptions trailDust = new Particle.DustOptions(
                Color.fromRGB(200, 130, 255), 0.8f);

        new BukkitRunnable() {
            int step_ = 0;
            Location current = start.clone();
            @Override public void run() {
                if (step_ >= totalSteps) {
                    // Impact
                    world.spawnParticle(Particle.ENCHANTED_HIT, end, 15, 0.4, 0.4, 0.4, 0.1);
                    world.spawnParticle(Particle.DUST, end, 8, 0.3, 0.3, 0.3, 0, mageDust);
                    world.spawnParticle(Particle.WITCH, end, 6, 0.3, 0.5, 0.3, 0.1);
                    if (crit) {
                        world.spawnParticle(Particle.FLASH, end, 1);
                        world.playSound(end, Sound.ENTITY_GENERIC_EXPLODE, 0.3f, 1.8f);
                    } else {
                        world.playSound(end, Sound.ENTITY_BLAZE_HURT, 0.5f, 1.4f);
                    }
                    cancel();
                    return;
                }
                current.add(step);
                // Noyau de l'orbe
                world.spawnParticle(Particle.DUST, current, 2, 0.05, 0.05, 0.05, 0, mageDust);
                // Traînée
                world.spawnParticle(Particle.DUST, current, 1, 0.1, 0.1, 0.1, 0, trailDust);
                world.spawnParticle(Particle.WITCH, current, 1, 0.05, 0.05, 0.05, 0.02);
                step_++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);

        world.playSound(attacker.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.4f, 1.6f);
    }

    /**
     * ADC : projectile physique rapide (flèche / balle) en CRIT + traînée blanche.
     * Plus rapide que l'orbe mage : avance de 0.6 blocs/tick.
     */
    private void playMageAnimationLE(Player attacker, LivingEntity target, boolean crit) {
        playMageAnimation(attacker, target.getLocation().add(0,1,0), crit);
    }

    private void playADCAnimation(Player attacker, Location end, boolean crit) {
        Location start = attacker.getEyeLocation();
        World world = attacker.getWorld();

        double totalDist = start.distance(end);
        // Projectile rapide : 0.6 blocs/tick (≈ 12 blocs/s)
        int totalSteps = Math.max(3, (int)(totalDist / 0.6));
        Vector step = end.toVector().subtract(start.toVector())
                .normalize().multiply(totalDist / totalSteps);

        Particle.DustOptions whiteDust = new Particle.DustOptions(
                Color.fromRGB(255, 240, 180), 0.7f);

        new BukkitRunnable() {
            int step_ = 0;
            Location current = start.clone();
            @Override public void run() {
                if (step_ >= totalSteps) {
                    // Impact
                    world.spawnParticle(Particle.CRIT, end, crit ? 16 : 6,
                            0.25, 0.25, 0.25, 0.08);
                    world.spawnParticle(Particle.DUST, end, 4, 0.1, 0.1, 0.1, 0, whiteDust);
                    if (crit) {
                        world.spawnParticle(Particle.LARGE_SMOKE, end, 4, 0.2, 0.2, 0.2, 0.02);
                        world.playSound(end, Sound.ENTITY_ARROW_HIT_PLAYER, 0.7f, 0.8f);
                    } else {
                        world.playSound(end, Sound.ENTITY_ARROW_HIT, 0.6f, 1.1f);
                    }
                    cancel();
                    return;
                }
                current.add(step);
                // Tête du projectile
                world.spawnParticle(Particle.CRIT, current, 1, 0, 0, 0, 0);
                // Traînée légère
                world.spawnParticle(Particle.DUST, current, 1, 0.03, 0.03, 0.03, 0, whiteDust);
                step_++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);

        // Son de tir immédiat
        world.playSound(attacker.getLocation(),
                crit ? Sound.ENTITY_ARROW_SHOOT : Sound.ENTITY_ARROW_SHOOT, 0.8f,
                crit ? 0.9f : 1.1f);
    }

    private void playADCAnimationLE(Player attacker, LivingEntity target, boolean crit) {
        playADCAnimation(attacker, target.getLocation().add(0,1,0), crit);
    }

    // ════════════════════════════════════════════════════════
    // UTILITAIRES
    // ════════════════════════════════════════════════════════

    /** Vérifie la cadence d'attaque. Renvoie false si trop tôt. */
    private boolean checkRangeAndCooldown(Player attacker, BaseChampion champ) {
        long now = System.currentTimeMillis();
        double attackSpeed = champ.getStats().getFinalAttackSpeed();
        long cooldownMs = (long)(1000.0 / Math.max(0.1, attackSpeed));
        Long last = lastAttack.get(attacker.getUniqueId());
        if (last != null && (now - last) < cooldownMs) return false;
        lastAttack.put(attacker.getUniqueId(), now);
        return true;
    }

    /** Vérifie si le joueur peut auto-attaquer (cadence respectée), sans déclencher. */
    public boolean canAutoAttack(Player attacker) {
        if (!LolPlugin.getInstance().getChampionManager().hasChampion(attacker)) return false;
        var champ = LolPlugin.getInstance().getChampionManager().getChampion(attacker);
        long now = System.currentTimeMillis();
        double attackSpeed = champ.getStats().getFinalAttackSpeed();
        long cooldownMs = (long)(1000.0 / Math.max(0.1, attackSpeed));
        Long last = lastAttack.get(attacker.getUniqueId());
        return last == null || (now - last) >= cooldownMs;
    }

    /** Déclenche le cooldown d'AA sans infliger de dégâts. */
    public void triggerCooldown(Player attacker) {
        lastAttack.put(attacker.getUniqueId(), System.currentTimeMillis());
    }

    public void cleanup(UUID uuid) {
        lastAttack.remove(uuid);
    }
}
