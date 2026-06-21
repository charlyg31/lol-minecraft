package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les auto-attaques façon LoL :
 *  - Portée propre à chaque champion (mêlée ~2 blocs, distance ~5-6 blocs)
 *  - Cadence basée sur la vitesse d'attaque (attack speed)
 *  - Les ADC à distance tirent un projectile, les mêlées frappent au contact
 */
public class AutoAttackManager {

    // Dernière auto-attaque par joueur (pour respecter la cadence)
    private final Map<UUID, Long> lastAttack = new HashMap<>();

    /**
     * Tente une auto-attaque sur une cible.
     * @return true si l'attaque a eu lieu
     */
    public boolean tryAutoAttack(Player attacker, Player target) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker) || !cm.hasChampion(target)) return false;

        BaseChampion champ = cm.getChampion(attacker);

        // 1. Vérifier la portée du champion
        double range = champ.getAutoAttackRange();
        double dist = attacker.getLocation().distance(target.getLocation());
        if (dist > range) {
            attacker.sendActionBar(Component.text("⚔ Hors de portée", NamedTextColor.GRAY));
            return false;
        }

        // 2. Vérifier la cadence (attack speed)
        long now = System.currentTimeMillis();
        double attackSpeed = champ.getStats().getFinalAttackSpeed();
        long cooldownMs = (long) (1000.0 / Math.max(0.1, attackSpeed));
        Long last = lastAttack.get(attacker.getUniqueId());
        if (last != null && (now - last) < cooldownMs) {
            return false; // trop tôt
        }
        lastAttack.put(attacker.getUniqueId(), now);

        // 3. Calculer les dégâts (avec crit géré par calcAutoAttackDamage)
        double rawDamage = champ.getStats().getFinalAD();
        boolean crit = Math.random() < champ.getStats().getFinalCritChance();
        if (crit) rawDamage *= champ.getStats().getFinalCritDamage();

        // 4. Animation selon mêlée/distance
        boolean ranged = range > 3.0;
        if (ranged) {
            shootProjectile(attacker, target);
        } else {
            attacker.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    target.getLocation().add(0, 1, 0), 1);
        }
        attacker.getWorld().playSound(attacker.getLocation(),
                ranged ? Sound.ENTITY_ARROW_SHOOT : Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 1f);

        // 5. Appliquer les dégâts (physique, auto-attaque)
        DamageUtil.damage(attacker, target, rawDamage, false, DamageUtil.Type.PHYSICAL);

        // 6. Déclencher les passifs on-hit (BotRK, Kraken, etc.)
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onAutoAttack(attacker, target);

        if (crit) {
            target.getWorld().spawnParticle(Particle.CRIT,
                    target.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3);
        }
        return true;
    }

    /**
     * Auto-attaque sur une entité non-joueur (sbire, monstre de jungle).
     * Respecte la PORTÉE et la CADENCE du champion (anti-spam), comme l'AA sur joueur.
     * @return true si l'attaque a été portée, false si hors de portée ou trop tôt.
     */
    public boolean tryAutoAttackEntity(Player attacker, org.bukkit.entity.LivingEntity target) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return false;
        BaseChampion champ = cm.getChampion(attacker);

        // 1. Portée
        double range = champ.getAutoAttackRange();
        double dist = attacker.getLocation().distance(target.getLocation());
        if (dist > range) {
            attacker.sendActionBar(Component.text("⚔ Hors de portée", NamedTextColor.GRAY));
            return false;
        }

        // 2. Cadence (attack speed) — MÊME compteur que l'AA joueur (anti-spam global)
        long now = System.currentTimeMillis();
        double attackSpeed = champ.getStats().getFinalAttackSpeed();
        long cooldownMs = (long) (1000.0 / Math.max(0.1, attackSpeed));
        Long last = lastAttack.get(attacker.getUniqueId());
        if (last != null && (now - last) < cooldownMs) {
            return false; // trop tôt : on bloque le spam
        }
        lastAttack.put(attacker.getUniqueId(), now);

        // 3. Dégâts (+ crit)
        double rawDamage = champ.getStats().getFinalAD();
        boolean crit = Math.random() < champ.getStats().getFinalCritChance();
        if (crit) rawDamage *= champ.getStats().getFinalCritDamage();

        // 4. Animation
        boolean ranged = range > 3.0;
        if (ranged) {
            attacker.getWorld().spawnParticle(Particle.CRIT,
                    target.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2);
        } else {
            attacker.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    target.getLocation().add(0, 1, 0), 1);
        }
        attacker.getWorld().playSound(attacker.getLocation(),
                ranged ? Sound.ENTITY_ARROW_SHOOT : Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 1f);

        // 5. Appliquer les dégâts directement (sbire/monstre, pas de système de résistance)
        double newHealth = Math.max(0, target.getHealth() - rawDamage);
        target.setHealth(newHealth);
        target.playEffect(org.bukkit.EntityEffect.HURT);

        // 6. Passifs on-hit
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onAutoAttackEntity(attacker, target);

        return true;
    }

    private void shootProjectile(Player from, Player to) {
        var dir = to.getLocation().add(0, 1, 0).toVector()
                .subtract(from.getEyeLocation().toVector()).normalize();
        double dist = from.getLocation().distance(to.getLocation());
        for (double d = 0; d < dist; d += 0.5) {
            var point = from.getEyeLocation().add(dir.clone().multiply(d));
            from.getWorld().spawnParticle(Particle.CRIT, point, 1, 0, 0, 0, 0);
        }
    }

    public void cleanup(UUID uuid) {
        lastAttack.remove(uuid);
    }
}
