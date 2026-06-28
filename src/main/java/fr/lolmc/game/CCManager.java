package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Contrôle de foule fidèle à LoL.
 *
 *  - STUN : immobilise ET empêche d'agir (auto-attaque + sorts bloqués).
 *  - ROOT : immobilise mais on peut encore lancer des sorts / attaquer.
 *  - SILENCE : empêche les sorts mais on peut bouger et auto-attaquer.
 *  - SLOW : ralentissement classique.
 *
 * La TÉNACITÉ de la cible réduit la durée du CC (stun/root/silence/slow).
 * Le blocage d'action est appliqué dans BaseChampion.tryUseAbility.
 */
public class CCManager {

    private final Map<UUID, Long> stunUntil = new HashMap<>();
    private final Map<UUID, Long> rootUntil = new HashMap<>();
    private final Map<UUID, Long> silenceUntil = new HashMap<>();

    private long now() { return System.currentTimeMillis(); }

    /** Réduit la durée (en ticks) selon la ténacité de la cible (si c'est un champion). */
    private int withTenacity(LivingEntity target, int ticks) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (target instanceof Player p && cm.hasChampion(p)) {
            double ten = cm.getChampion(p).getStats().getFinalTenacity(); // 0..0.95
            ticks = (int) Math.round(ticks * (1.0 - ten));
        }
        return Math.max(1, ticks);
    }

    private void immobilize(LivingEntity t, int ticks) {
        // SLOWNESS très élevé = quasi immobile (approximation Minecraft)
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false));
    }

    // ── Application ──

    public void stun(LivingEntity target, int ticks) {
        ticks = withTenacity(target, ticks);
        long until = now() + ticks * 50L;
        stunUntil.merge(target.getUniqueId(), until, Math::max);
        silenceUntil.merge(target.getUniqueId(), until, Math::max); // un stun empêche aussi les sorts
        immobilize(target, ticks);
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 2.2, 0), 10, 0.3, 0.1, 0.3);
        if (target instanceof Player p)
            p.sendActionBar(Component.text("💫 Étourdi!", NamedTextColor.YELLOW));
    }

    public void root(LivingEntity target, int ticks) {
        ticks = withTenacity(target, ticks);
        rootUntil.merge(target.getUniqueId(), now() + ticks * 50L, Math::max);
        immobilize(target, ticks);
        if (target instanceof Player p)
            p.sendActionBar(Component.text("🌿 Enraciné!", NamedTextColor.GREEN));
    }

    public void silence(LivingEntity target, int ticks) {
        ticks = withTenacity(target, ticks);
        silenceUntil.merge(target.getUniqueId(), now() + ticks * 50L, Math::max);
        if (target instanceof Player p)
            p.sendActionBar(Component.text("🔇 Réduit au silence!", NamedTextColor.GRAY));
    }

    public void slow(LivingEntity target, int ticks, int amplifier) {
        ticks = withTenacity(target, ticks);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, amplifier, false, true));
    }

    // ── Airborne (knockup) ────────────────────────────────────────────
    private final java.util.Map<UUID, Long> airborneUntil
        = new java.util.concurrent.ConcurrentHashMap<>();

    public void setAirborne(org.bukkit.entity.LivingEntity target, int ticks) {
        ticks = withTenacity(target, ticks);
        long until = now() + ticks * 50L;
        airborneUntil.merge(target.getUniqueId(), until, Math::max);
        target.setVelocity(new org.bukkit.util.Vector(0, 0.7, 0));
        if (target instanceof org.bukkit.entity.Player p)
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                "💨 Propulsé!", net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }

    public boolean isAirborne(UUID id) {
        return now() < airborneUntil.getOrDefault(id, 0L);
    }

    // ── État ──

    public boolean isStunned(UUID id)  { return now() < stunUntil.getOrDefault(id, 0L); }
    public boolean isRooted(UUID id)   { return now() < rootUntil.getOrDefault(id, 0L); }
    public boolean isSilenced(UUID id) { return now() < silenceUntil.getOrDefault(id, 0L); }

    /** Peut lancer un sort ? (bloqué par stun ou silence) */
    public boolean canCastAbility(UUID id) { return !isStunned(id) && !isSilenced(id); }

    /** Retire tous les CC sur une entité (QSS, Purification, Mercurial). */
    public void cleanse(LivingEntity target) {
        UUID id = target.getUniqueId();
        stunUntil.remove(id);
        rootUntil.remove(id);
        silenceUntil.remove(id);
        if (target instanceof Player p) {
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.MINING_FATIGUE);
        }
    }
    /** Peut auto-attaquer ? (bloqué par stun seulement ; le silence n'empêche pas l'AA) */
    public boolean canAutoAttack(UUID id) { return !isStunned(id); }

    public void clear(UUID id) {
        stunUntil.remove(id);
        rootUntil.remove(id);
        silenceUntil.remove(id);
        airborneUntil.remove(id);
    }
}
