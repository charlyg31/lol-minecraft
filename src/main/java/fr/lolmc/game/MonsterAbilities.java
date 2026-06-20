package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.JungleManager.MonsterType;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Donne aux monstres de jungle leurs attaques spéciales façon LoL.
 *
 * Une boucle d'IA tourne pour chaque monstre vivant : elle détecte les cibles
 * dans sa portée et déclenche des capacités selon des cooldowns. Les dégâts
 * passent par le HPSystem des joueurs (DamageUtil).
 *
 * Mécaniques implémentées :
 *  - Crabe : fuit l'attaquant
 *  - Buff Rouge : brûlure + ralentissement sur qui le frappe (passif)
 *  - Buff Bleu : tir de mana-burn à distance
 *  - Dragons : souffle de feu (cône), attaque de zone selon l'élément
 *  - Baron : nuke de zone + tir qui repousse + debuff
 *  - Héraut : charge qui repousse
 *  - Camps neutres : coup au sol AoE léger
 */
public class MonsterAbilities {

    // Cooldown de capacité par entité : entityUUID → prochain cast autorisé (ms)
    private final Map<UUID, Long> abilityCooldowns = new HashMap<>();

    public MonsterAbilities() {
        startAbilityLoop();
    }

    private void startAbilityLoop() {
        new BukkitRunnable() {
            @Override public void run() {
                for (var world : LolPlugin.getInstance().getServer().getWorlds()) {
                    for (Entity e : world.getEntities()) {
                        if (!JungleManager.isJungleMonster(e)) continue;
                        if (!(e instanceof LivingEntity mob)) continue;
                        MonsterType type = JungleManager.getMonsterType(e);
                        if (type == null) continue;
                        tickMonster(mob, type);
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L); // 1x/seconde
    }

    /** Logique d'IA d'un monstre à chaque tick. */
    private void tickMonster(LivingEntity mob, MonsterType type) {
        // Trouver les joueurs ennemis à portée
        double range = abilityRange(type);
        List<Player> targets = new ArrayList<>();
        for (Entity e : mob.getNearbyEntities(range, range, range)) {
            if (e instanceof Player p
                    && LolPlugin.getInstance().getChampionManager().hasChampion(p)
                    && !p.isDead()) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) return;

        // Vérifier le cooldown de capacité
        long now = System.currentTimeMillis();
        Long readyAt = abilityCooldowns.get(mob.getUniqueId());
        if (readyAt != null && now < readyAt) return;

        // Déclencher la capacité selon le type
        boolean used = useAbility(mob, type, targets);
        if (used) {
            abilityCooldowns.put(mob.getUniqueId(), now + abilityCooldown(type));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CAPACITÉS PAR TYPE
    // ══════════════════════════════════════════════════════════════

    private boolean useAbility(LivingEntity mob, MonsterType type, List<Player> targets) {
        Player nearest = nearest(mob, targets);
        if (nearest == null) return false;

        switch (type) {
            case SCUTTLE_CRAB -> { return crabFlee(mob, nearest); }
            case RED_BUFF -> { return redSmash(mob, targets); }
            case BLUE_BUFF -> { return blueManaBurn(mob, nearest); }
            case BARON -> { return baronNuke(mob, targets); }
            case HERALD -> { return heraldCharge(mob, nearest); }
            case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_MOUNTAIN,
                 DRAGON_CLOUD, DRAGON_CHEMTECH, DRAGON_ELDER -> {
                return dragonBreath(mob, type, targets);
            }
            // Camps neutres : coup au sol AoE léger
            case GROMP, MURKWOLF, RAPTOR, KRUG -> { return neutralSmash(mob, targets); }
            default -> { return false; }
        }
    }

    // ── Crabe : fuit l'attaquant ──────────────────────────────────

    private boolean crabFlee(LivingEntity crab, Player threat) {
        Vector away = crab.getLocation().toVector()
                .subtract(threat.getLocation().toVector()).normalize().multiply(0.8);
        away.setY(0.1);
        crab.setVelocity(away);
        crab.getWorld().spawnParticle(Particle.SPLASH, crab.getLocation(), 10, 0.3, 0.3, 0.3);
        return false; // pas de cooldown (fuite continue)
    }

    // ── Buff Rouge : coup au sol qui brûle et ralentit ────────────

    private boolean redSmash(LivingEntity mob, List<Player> targets) {
        Location c = mob.getLocation();
        c.getWorld().spawnParticle(Particle.LAVA, c, 20, 1.5, 0.5, 1.5);
        c.getWorld().playSound(c, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
        for (Player p : targets) {
            if (p.getLocation().distance(c) <= 4) {
                DamageUtil.damage(null, p, 40, true, DamageUtil.Type.MAGICAL);
                applyRedBuffDebuff(p); // brûlure + ralentissement
            }
        }
        return true;
    }

    /** Applique la brûlure + ralentissement du buff Rouge (utilisé aussi en passif). */
    public static void applyRedBuffDebuff(Player victim) {
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true));
        // Brûlure : dégâts sur la durée (3s)
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 3 || victim.isDead() || !victim.isOnline()) { cancel(); return; }
                DamageUtil.damage(null, victim, 12, true, DamageUtil.Type.TRUE);
                victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation().add(0,1,0), 5, 0.2, 0.4, 0.2);
                ticks++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);
    }

    // ── Buff Bleu : tir de mana-burn ──────────────────────────────

    private boolean blueManaBurn(LivingEntity mob, Player target) {
        Location from = mob.getEyeLocation();
        shootProjectile(from, target.getLocation().add(0,1,0), Particle.SOUL_FIRE_FLAME);
        DamageUtil.damage(null, target, 35, true, DamageUtil.Type.MAGICAL);
        // Brûle la ressource (mana/énergie)
        if (LolPlugin.getInstance().getChampionManager().hasChampion(target)) {
            var champ = LolPlugin.getInstance().getChampionManager().getChampion(target);
            champ.getResourceSystem().consume(30);
            target.sendActionBar(Component.text("🔵 Mana brûlé!", NamedTextColor.BLUE));
        }
        return true;
    }

    // ── Baron : nuke de zone + repousse + debuff ──────────────────

    private boolean baronNuke(LivingEntity mob, List<Player> targets) {
        Location c = mob.getLocation();
        c.getWorld().spawnParticle(Particle.SONIC_BOOM, c.clone().add(0,1,0), 1);
        c.getWorld().playSound(c, Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1f);
        for (Player p : targets) {
            double dist = p.getLocation().distance(c);
            if (dist <= 7) {
                DamageUtil.damage(null, p, 120, true, DamageUtil.Type.MAGICAL);
                // Repousse
                Vector push = p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(1.2);
                push.setY(0.5);
                p.setVelocity(push);
                // Debuff : faiblesse (réduction de dégâts infligés)
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, false, true));
            }
        }
        return true;
    }

    // ── Héraut : charge qui repousse ──────────────────────────────

    private boolean heraldCharge(LivingEntity mob, Player target) {
        Vector dir = target.getLocation().toVector()
                .subtract(mob.getLocation().toVector()).normalize().multiply(1.5);
        dir.setY(0.2);
        mob.setVelocity(dir);
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1f);
        // Dégâts + repousse si proche
        if (target.getLocation().distance(mob.getLocation()) <= 3) {
            DamageUtil.damage(null, target, 60, true, DamageUtil.Type.PHYSICAL);
            Vector push = dir.clone().multiply(1.5); push.setY(0.6);
            target.setVelocity(push);
        }
        return true;
    }

    // ── Dragons : souffle de feu en cône + effet d'élément ────────

    private boolean dragonBreath(LivingEntity dragon, MonsterType type, List<Player> targets) {
        Location origin = dragon.getEyeLocation();
        Vector facing = origin.getDirection();
        Sound sound = Sound.ENTITY_ENDER_DRAGON_SHOOT;
        Particle breathParticle = dragonParticle(type);

        // Tracer le cône de souffle
        for (double d = 1; d <= 8; d += 0.5) {
            Location point = origin.clone().add(facing.clone().multiply(d));
            point.getWorld().spawnParticle(breathParticle, point, 8, 0.5, 0.5, 0.5, 0.02);
        }
        origin.getWorld().playSound(origin, sound, 1f, 1f);

        // Toucher les joueurs dans le cône (angle < 45°)
        for (Player p : targets) {
            Vector toPlayer = p.getLocation().toVector().subtract(origin.toVector());
            if (toPlayer.length() > 8) continue;
            double angle = facing.angle(toPlayer.normalize());
            if (angle < Math.toRadians(45)) {
                double dmg = type == MonsterType.DRAGON_ELDER ? 90 : 60;
                DamageUtil.damage(null, p, dmg, true, DamageUtil.Type.MAGICAL);
                applyDragonElementEffect(type, p);
            }
        }
        return true;
    }

    private void applyDragonElementEffect(MonsterType type, Player p) {
        switch (type) {
            case DRAGON_INFERNAL -> // brûlure
                new BukkitRunnable() {
                    int t = 0;
                    @Override public void run() {
                        if (t >= 3 || p.isDead() || !p.isOnline()) { cancel(); return; }
                        DamageUtil.damage(null, p, 10, true, DamageUtil.Type.MAGICAL);
                        t++;
                    }
                }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);
            case DRAGON_OCEAN -> // ralentit fortement
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, true));
            case DRAGON_MOUNTAIN -> // étourdit (lenteur extrême brève)
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 5, false, true));
            case DRAGON_CLOUD -> // le dragon devient plus rapide (déjà mobile)
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true));
            case DRAGON_CHEMTECH -> // poison
                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, false, true));
            case DRAGON_ELDER -> { // exécution : dégâts bonus si bas
                if (LolPlugin.getInstance().getChampionManager().hasChampion(p)) {
                    var hp = LolPlugin.getInstance().getChampionManager().getChampion(p).getHPSystem();
                    if (hp.getCurrentHP() / hp.getMaxHP() < 0.2) {
                        DamageUtil.damage(null, p, 9999, true, DamageUtil.Type.TRUE); // exécute
                    }
                }
            }
            default -> {}
        }
    }

    // ── Camps neutres : coup au sol AoE léger ─────────────────────

    private boolean neutralSmash(LivingEntity mob, List<Player> targets) {
        Location c = mob.getLocation();
        c.getWorld().spawnParticle(Particle.CRIT, c, 8, 0.5, 0.2, 0.5);
        for (Player p : targets) {
            if (p.getLocation().distance(c) <= 2.5) {
                DamageUtil.damage(null, p, 20, true, DamageUtil.Type.PHYSICAL);
            }
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private void shootProjectile(Location from, Location to, Particle particle) {
        Vector dir = to.toVector().subtract(from.toVector()).normalize();
        double dist = from.distance(to);
        for (double d = 0; d < dist; d += 0.4) {
            Location point = from.clone().add(dir.clone().multiply(d));
            from.getWorld().spawnParticle(particle, point, 2, 0.05, 0.05, 0.05, 0);
        }
    }

    private Particle dragonParticle(MonsterType type) {
        return switch (type) {
            case DRAGON_INFERNAL -> Particle.FLAME;
            case DRAGON_OCEAN -> Particle.SPLASH;
            case DRAGON_MOUNTAIN -> Particle.CRIT;
            case DRAGON_CLOUD -> Particle.CLOUD;
            case DRAGON_CHEMTECH -> Particle.SNEEZE;
            case DRAGON_ELDER -> Particle.SOUL_FIRE_FLAME;
            default -> Particle.FLAME;
        };
    }

    private double abilityRange(MonsterType type) {
        if (type.isEpic()) return 10;
        if (type == MonsterType.RED_BUFF || type == MonsterType.BLUE_BUFF) return 6;
        return 4;
    }

    private long abilityCooldown(MonsterType type) {
        return switch (type) {
            case BARON, DRAGON_ELDER -> 5000L;
            case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_MOUNTAIN,
                 DRAGON_CLOUD, DRAGON_CHEMTECH, HERALD -> 4000L;
            case RED_BUFF, BLUE_BUFF -> 3000L;
            default -> 2500L;
        };
    }

    private Player nearest(LivingEntity mob, List<Player> targets) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : targets) {
            double d = p.getLocation().distance(mob.getLocation());
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    public void cleanup(UUID entityId) {
        abilityCooldowns.remove(entityId);
    }
}
