package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
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

    /**
     * Imperial Mandate : si l'attaquant porte l'objet, marque la cible (marque expirant après 4s).
     * À appeler en plus de stun/root/slow quand ces derniers immobilisent ou ralentissent un ennemi.
     */
    public void markCoordinatedFire(Player attacker, LivingEntity target) {
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm == null || !pm.hasAnyItem(attacker, "imperial_mandate")) return;
        if (!(target instanceof Player)) return; // ne marque que les champions
        pm.getState((Player) target).coordinatedFireMarkExpire = System.currentTimeMillis() + 4000L;
        fr.lolmc.util.VisualEffectUtil.impact(target.getWorld(), target.getLocation().add(0, 2.3, 0),
                Material.PURPLE_STAINED_GLASS, 0.2f, 8L);
    }

    /**
     * Zeke's Convergence : si le porteur immobilise/ralentit un ennemi, l'allié champion le plus
     * proche du porteur (hors lui-même) reçoit +30% dégâts pendant 6s.
     */
    public void applyZekeConduit(Player caster, LivingEntity ccTarget) {
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm == null || !pm.hasAnyItem(caster, "zekess_convergence")) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        var tm = LolPlugin.getInstance().getTeamManager();
        if (cm == null || tm == null) return;
        Player nearestAlly = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player p : fr.lolmc.util.WorldContext.getGamePlayers()) {
            if (p.equals(caster) || !cm.hasChampion(p) || !tm.areAllies(caster, p)) continue;
            if (!p.getWorld().equals(caster.getWorld())) continue;
            double d = p.getLocation().distanceSquared(caster.getLocation());
            if (d < nearestDist) { nearestDist = d; nearestAlly = p; }
        }
        if (nearestAlly != null && nearestDist <= 10.0*10.0) { // portée de proximité, cohérente avec les autres auras du projet
            cm.getChampion(nearestAlly).getStats().applyZekeBuff(6000L);
            nearestAlly.sendActionBar(Component.text("⚡ Conduit ! +30% dégâts (6s)", NamedTextColor.AQUA));
        }
    }

    /**
     * Rune Aftershock : après avoir immobilisé/ralenti un ennemi, le porteur gagne des
     * résistances temporaires (2.5s) puis explose en dégâts magiques sur les ennemis proches.
     */
    public void applyAftershock(Player caster, LivingEntity ccTarget) {
        var rm = LolPlugin.getInstance().getRuneManager();
        var cm = LolPlugin.getInstance().getChampionManager();
        if (rm == null || cm == null || !cm.hasChampion(caster)) return;
        var page = rm.getPage(caster.getUniqueId());
        if (page == null || !"aftershock".equals(page.keystone)) return;
        var state = LolPlugin.getInstance().getPassiveManager().getState(caster);
        if (state.isOnCooldown(state.aftershockLastUse, 20000L)) return;
        state.aftershockLastUse = System.currentTimeMillis();

        var stats = cm.getChampion(caster).getStats();
        double bonusRes = Math.min(35 + stats.getBonusArmor() * 0.80, 150);
        stats.addBonusArmor(bonusRes);
        stats.addBonusMR(bonusRes);
        caster.sendActionBar(Component.text("🛡 Réplique ! +" + (int) bonusRes + " résistances", NamedTextColor.GOLD));

        new BukkitRunnable() {
            @Override public void run() {
                stats.addBonusArmor(-bonusRes);
                stats.addBonusMR(-bonusRes);
                double shockDmg = 25.0 + stats.getBonusHP() * 0.08;
                for (var e : caster.getWorld().getNearbyEntities(caster.getLocation(), 6, 3, 6)) {
                    if (e instanceof Player enemyP && !enemyP.equals(caster) && cm.hasChampion(enemyP)
                            && LolPlugin.getInstance().getTeamManager().areEnemies(caster, enemyP)) {
                        cm.getChampion(enemyP).getHPSystem().takeDamage(shockDmg);
                    }
                }
                fr.lolmc.util.VisualEffectUtil.impactBurst(caster.getWorld(), caster.getLocation().add(0,1,0),
                        Material.STONE, 0.3f, 1.0, 8, 6L);
            }
        }.runTaskLater(LolPlugin.getInstance(), 50L);
    }

    public void stun(LivingEntity target, int ticks) {
        ticks = withTenacity(target, ticks);
        long until = now() + ticks * 50L;
        stunUntil.merge(target.getUniqueId(), until, Math::max);
        silenceUntil.merge(target.getUniqueId(), until, Math::max); // un stun empêche aussi les sorts
        immobilize(target, ticks);
        fr.lolmc.util.VisualEffectUtil.impact(target.getWorld(),
                target.getLocation().add(0, 2.2, 0), Material.YELLOW_STAINED_GLASS, 0.28f, 5L);
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
        if (target instanceof Player p) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(p)) {
                double sr = cm.getChampion(p).getStats().getFinalSlowResist(); // 0..0.95 (Boots of Swiftness)
                ticks = (int) Math.round(ticks * (1.0 - sr));
            }
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Math.max(1, ticks), amplifier, false, true));
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
