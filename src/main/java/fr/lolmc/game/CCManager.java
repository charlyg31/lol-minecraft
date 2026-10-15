package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
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

    // ── Vitesse de déplacement (LoL) ──────────────────────────────────
    // Un modificateur d'attribut applique : vitesse LoL du champion (base + bonus, ex. bottes) / 345,
    // × (1 + somme des accélérations %) × (1 − ralentissement le plus fort). Avant, la vitesse LoL
    // (et les bottes) n'avait aucun effet, et les ralentissements n'étaient que des potions
    // Minecraft par paliers de 15 %.
    private record SpeedEffect(double pct, long start, long until, boolean decays) {
        double current(long now) {
            if (!decays) return pct;
            double f = 1.0 - (double) (now - start) / Math.max(1, until - start);
            return pct * Math.max(0, f);
        }
    }
    private final Map<UUID, java.util.List<SpeedEffect>> slows = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, java.util.List<SpeedEffect>> boosts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final double REFERENCE_MS = 345.0;
    private org.bukkit.NamespacedKey msKey;

    {
        new BukkitRunnable() {
            @Override public void run() { tickMovement(); }
        }.runTaskTimer(LolPlugin.getInstance(), 5L, 2L);
    }

    /** Ralentit de {@code pct} (0..1) pendant {@code ticks} ; seul le plus fort s'applique (LoL). */
    public void slowPct(LivingEntity target, double pct, int ticks) { addSlow(target, pct, ticks, false); }

    /** Ralentissement qui décroît jusqu'à 0 sur la durée. */
    public void slowDecaying(LivingEntity target, double pct, int ticks) { addSlow(target, pct, ticks, true); }

    private void addSlow(LivingEntity target, double pct, int ticks, boolean decays) {
        if (isCcImmune(target)) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (target instanceof Player p && cm.hasChampion(p)) {
            double sr = cm.getChampion(p).getStats().getFinalSlowResist();
            pct *= (1.0 - sr);
            long now = now();
            slows.computeIfAbsent(target.getUniqueId(), _ -> new java.util.concurrent.CopyOnWriteArrayList<>())
                 .add(new SpeedEffect(Math.min(0.99, pct), now, now + ticks * 50L, decays));
            notifyCC(target, false);
        } else {
            // Sbire / monstre : potion approchante (15 % par niveau)
            int amp = (int) Math.max(0, Math.round(pct / 0.15) - 1);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Math.max(1, ticks), Math.min(amp, 6), false, true));
        }
    }

    /** Retire tous les ralentissements en cours (immunité de Highlander). */
    public void clearSlows(UUID id) { slows.remove(id); }

    /** Accélère de {@code pct} (0.35 = +35 %) pendant {@code ticks} ; les accélérations s'additionnent. */
    public void speedPct(LivingEntity target, double pct, int ticks) { addBoost(target, pct, ticks, false); }

    /** Accélération qui décroît jusqu'à 0 sur la durée. */
    public void speedDecaying(LivingEntity target, double pct, int ticks) { addBoost(target, pct, ticks, true); }

    private void addBoost(LivingEntity target, double pct, int ticks, boolean decays) {
        long now = now();
        boosts.computeIfAbsent(target.getUniqueId(), _ -> new java.util.concurrent.CopyOnWriteArrayList<>())
              .add(new SpeedEffect(pct, now, now + ticks * 50L, decays));
    }

    private double strongestSlow(UUID id, long now) {
        var l = slows.get(id);
        if (l == null) return 0;
        l.removeIf(e -> e.until() <= now);
        double max = 0;
        for (var e : l) max = Math.max(max, e.current(now));
        return max;
    }

    private double totalBoost(UUID id, long now) {
        var l = boosts.get(id);
        if (l == null) return 0;
        l.removeIf(e -> e.until() <= now);
        double sum = 0;
        for (var e : l) sum += e.current(now);
        return sum;
    }

    private void tickMovement() {
        if (msKey == null) msKey = new org.bukkit.NamespacedKey(LolPlugin.getInstance(), "lol_movespeed");
        var cm = LolPlugin.getInstance().getChampionManager();
        long now = now();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            var attr = p.getAttribute(fr.lolmc.util.Compat.movementSpeed());
            if (attr == null) continue;
            double amount = 0;
            if (cm.hasChampion(p) && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                var st = cm.getChampion(p).getStats();
                double ms = st.getFinalMovementSpeed();
                // Célérité : les accélérations en % sont aussi plus efficaces
                double factor = ms / REFERENCE_MS * (1 + totalBoost(p.getUniqueId(), now) * st.getMsBonusEfficiency())
                        * (1 - strongestSlow(p.getUniqueId(), now));
                amount = factor - 1;
            } else {
                slows.remove(p.getUniqueId()); boosts.remove(p.getUniqueId());
            }
            var cur = attr.getModifier(msKey);
            if (cur != null && Math.abs(cur.getAmount() - amount) < 1e-4) continue;
            if (cur != null) attr.removeModifier(msKey);
            if (Math.abs(amount) > 1e-4)
                attr.addTransientModifier(new org.bukkit.attribute.AttributeModifier(msKey, amount,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR));
        }
    }

    private final Map<UUID, Long> stunUntil = new HashMap<>();
    private final Map<UUID, Long> rootUntil = new HashMap<>();
    private final Map<UUID, Long> silenceUntil = new HashMap<>();

    private long now() { return System.currentTimeMillis(); }

    // Immunité aux contrôles : jusqu'à la fin, tant que la cible a un bouclier anti-magie
    private final Map<UUID, Long> ccImmuneUntil = new HashMap<>();

    /** Immunité aux contrôles tant que le bouclier anti-magie tient (Bouclier Noir de Morgana). */
    public void grantSpellShieldImmunity(LivingEntity target, long ms) {
        ccImmuneUntil.put(target.getUniqueId(), now() + ms);
    }

    public boolean isCcImmune(LivingEntity target) {
        Long until = ccImmuneUntil.get(target.getUniqueId());
        if (until == null || now() >= until) return false;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (target instanceof Player p && cm.hasChampion(p)
                && cm.getChampion(p).getStats().getMagicShield() <= 0) {
            ccImmuneUntil.remove(target.getUniqueId()); // bouclier brisé
            return false;
        }
        return true;
    }

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
     * Contrôle posé par {@code attacker} sur {@code target} : effets d'objets (Mandat impérial,
     * Fimbulvetr, Flûtes de Bandle, Traîneau du solstice…). Avant : ancienne version du Mandat
     * (« Tir coordonné ») et de Zeke (« Conduit »), retirées de LoL.
     */
    public void markCoordinatedFire(Player attacker, LivingEntity target) {
        fr.lolmc.item.ItemEffects.onCrowdControl(attacker, target, true);
    }

    /** Ancien « Conduit » de Zeke (retiré de LoL) : la Convergence de Zeke agit au lancer de l'ultime. */
    public void applyZekeConduit(Player caster, LivingEntity ccTarget) { }

    /** Attribue un contrôle au sort qui vient de toucher la cible, pour les effets d'objets. */
    private void notifyCC(LivingEntity target, boolean immobilize) {
        var rm = LolPlugin.getInstance().getRuneManager();
        if (rm != null && target instanceof Player tp) rm.onCrowdControlled(tp); // Inébranlable
        Player src = fr.lolmc.item.ItemEffects.recentAbilitySource(target);
        if (src != null) fr.lolmc.item.ItemEffects.onCrowdControl(src, target, immobilize);
        else fr.lolmc.item.ItemEffects.pendingCrowdControl(target, immobilize); // dégâts du sort juste après
    }

    /**
     * Rune Aftershock : après avoir immobilisé/ralenti un ennemi, le porteur gagne des
     * résistances temporaires (2.5s) puis explose en dégâts magiques sur les ennemis proches.
     */
    public void applyAftershock(Player caster, LivingEntity ccTarget) {
        var rm = LolPlugin.getInstance().getRuneManager();
        if (rm != null) rm.aftershock(caster);
    }

    /** Temps restant d'immobilisation (étourdissement, entrave, projection) de {@code id}, en ms. */
    public long immobileRemainingMs(UUID id) {
        long end = Math.max(stunUntil.getOrDefault(id, 0L),
                Math.max(rootUntil.getOrDefault(id, 0L), airborneUntil.getOrDefault(id, 0L)));
        return Math.max(0, end - now());
    }

    /** Déplacements ou actions restreints : étourdi, entravé, projeté, réduit au silence ou ralenti. */
    public boolean isImpaired(LivingEntity e) {
        UUID id = e.getUniqueId();
        return isStunned(id) || isRooted(id) || isAirborne(id) || isSilenced(id) || isSlowed(e);
    }

    public void stun(LivingEntity target, int ticks) {
        if (isCcImmune(target)) return;
        ticks = withTenacity(target, ticks);
        long until = now() + ticks * 50L;
        stunUntil.merge(target.getUniqueId(), until, Math::max);
        notifyCC(target, true);
        silenceUntil.merge(target.getUniqueId(), until, Math::max); // un stun empêche aussi les sorts
        immobilize(target, ticks);
        fr.lolmc.util.VisualEffectUtil.impact(target.getWorld(),
                target.getLocation().add(0, 2.2, 0), Material.YELLOW_STAINED_GLASS, 0.28f, 5L);
        if (target instanceof Player p)
            p.sendActionBar(Component.text("💫 Étourdi!", NamedTextColor.YELLOW));
    }

    public void root(LivingEntity target, int ticks) {
        if (isCcImmune(target)) return;
        ticks = withTenacity(target, ticks);
        rootUntil.merge(target.getUniqueId(), now() + ticks * 50L, Math::max);
        notifyCC(target, true);
        immobilize(target, ticks);
        if (target instanceof Player p)
            p.sendActionBar(Component.text("🌿 Enraciné!", NamedTextColor.GREEN));
    }

    public void silence(LivingEntity target, int ticks) {
        if (isCcImmune(target)) return;
        ticks = withTenacity(target, ticks);
        silenceUntil.merge(target.getUniqueId(), now() + ticks * 50L, Math::max);
        if (target instanceof Player p)
            p.sendActionBar(Component.text("🔇 Réduit au silence!", NamedTextColor.GRAY));
    }

    public void slow(LivingEntity target, int ticks, int amplifier) {
        if (isCcImmune(target)) return;
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
        if (isCcImmune(target)) return;
        ticks = withTenacity(target, ticks);
        long until = now() + ticks * 50L;
        airborneUntil.merge(target.getUniqueId(), until, Math::max);
        notifyCC(target, true);
        target.setVelocity(new org.bukkit.util.Vector(0, 0.7, 0));
        if (target instanceof org.bukkit.entity.Player p)
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                "💨 Propulsé!", net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }

    public boolean isAirborne(UUID id) {
        return now() < airborneUntil.getOrDefault(id, 0L);
    }

    // ── État ──

    /** Cible actuellement ralentie (ralentissement en % ou potion de lenteur). */
    public boolean isSlowed(LivingEntity e) {
        return strongestSlow(e.getUniqueId(), now()) > 0 || e.hasPotionEffect(PotionEffectType.SLOWNESS);
    }

    public boolean isStunned(UUID id)  { return now() < stunUntil.getOrDefault(id, 0L); }
    public boolean isRooted(UUID id)   { return now() < rootUntil.getOrDefault(id, 0L); }
    public boolean isSilenced(UUID id) { return now() < silenceUntil.getOrDefault(id, 0L); }

    /** Peut lancer un sort ? (bloqué par stun ou silence) */
    public boolean canCastAbility(UUID id) { return !isStunned(id) && !isSilenced(id) && !HeraldRodeo.isRiding(id); }

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
    public boolean canAutoAttack(UUID id) {
        Player p = org.bukkit.Bukkit.getPlayer(id);
        var fm = LolPlugin.getInstance().getFlashManager();
        if (p != null && fm != null && fm.isHexChanneling(p)) return false; // Saut Hextech en canalisation
        return !isStunned(id) && !HeraldRodeo.isRiding(id);
    }

    public void clear(UUID id) {
        ccImmuneUntil.remove(id);
        slows.remove(id);
        boosts.remove(id);
        stunUntil.remove(id);
        rootUntil.remove(id);
        silenceUntil.remove(id);
        airborneUntil.remove(id);
    }
}
