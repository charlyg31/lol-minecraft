package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.JungleManager.MonsterType;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
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
 *
 * Combat de base (LoL) : un monstre ne fait rien tant qu'on ne l'a pas frappé. Frappé, tout
 * son camp se réveille et attaque le champion à sa vitesse d'attaque (mêlée, ou projectile pour
 * Gromp, les dragons et le Baron). Si le champion sort de la laisse du camp ou cesse de le
 * frapper, le monstre rentre et récupère tous ses PV.
 */
public class MonsterAbilities {

    // Cooldown de capacité par entité : entityUUID → prochain cast autorisé (ms)
    private final Map<UUID, Long> abilityCooldowns = new HashMap<>();

    public MonsterAbilities() {
        startAbilityLoop();
        new BukkitRunnable() {
            @Override public void run() { combatTick(); }
        }.runTaskTimer(LolPlugin.getInstance(), 5L, 5L);
    }

    // ══════════════════════════════════════════════════════════════
    // COMBAT DE BASE : aggro, attaques, laisse
    // ══════════════════════════════════════════════════════════════

    private static final Map<UUID, UUID> AGGRO = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_HURT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_ATTACK = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long> RETURNING = new java.util.concurrent.ConcurrentHashMap<>();
    /** Distance max au camp avant que le monstre lâche sa cible (blocs). */
    private static final double LEASH = 9.0;
    /** Sans coup reçu pendant ce délai, le monstre abandonne et rentre (ms). */
    private static final long RESET_MS = 8000L;

    /** Un champion frappe un monstre : tout le camp (même type, à proximité) le prend pour cible. */
    public static void onDamaged(LivingEntity mob, Player attacker) {
        MonsterType type = JungleManager.getMonsterType(mob);
        if (type == null || type == MonsterType.SCUTTLE_CRAB) return; // le crabe n'attaque pas
        long now = System.currentTimeMillis();
        engage(mob, type, attacker, now);
        for (Entity e : mob.getNearbyEntities(6, 3, 6)) {
            if (e instanceof LivingEntity o && JungleManager.getMonsterType(o) == type) engage(o, type, attacker, now);
        }
    }

    private static void engage(LivingEntity mob, MonsterType type, Player attacker, long now) {
        AGGRO.putIfAbsent(mob.getUniqueId(), attacker.getUniqueId());
        LAST_HURT.put(mob.getUniqueId(), now);
        RETURNING.remove(mob.getUniqueId());
        if (mob instanceof org.bukkit.entity.Mob m && chases(type)) m.setAware(true);
    }

    /** Baron et dragons restent dans leur fosse ; les autres poursuivent leur cible. */
    private static boolean chases(MonsterType type) {
        return type != MonsterType.BARON && !type.isDragon();
    }

    private static boolean isRanged(MonsterType type) {
        return type == MonsterType.GROMP || type == MonsterType.BARON || type.isDragon();
    }

    /** Portée d'attaque (blocs, du centre du monstre au champion, hors largeur du monstre). */
    private static double attackRange(MonsterType type) {
        if (type.isDragon()) return 5.3;       // 500 unités
        return switch (type) {
            case GROMP -> 2.8;                 // 250
            case BARON -> 3.0;
            case HERALD -> 2.6;
            default -> 2.0;                    // mêlée (~175)
        };
    }

    private void combatTick() {
        long now = System.currentTimeMillis();
        var cm = LolPlugin.getInstance().getChampionManager();
        for (var it = AGGRO.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            UUID id = en.getKey();
            if (!(org.bukkit.Bukkit.getEntity(id) instanceof LivingEntity mob) || mob.isDead() || !mob.isValid()) {
                it.remove(); forget(id); continue;
            }
            MonsterType type = JungleManager.getMonsterType(mob);
            Player t = org.bukkit.Bukkit.getPlayer(en.getValue());
            Location camp = campOf(mob);
            boolean lost = type == null || t == null || !t.isOnline()
                    || t.getGameMode() == org.bukkit.GameMode.SPECTATOR || !cm.hasChampion(t)
                    || !t.getWorld().equals(mob.getWorld())
                    || t.getLocation().distanceSquared(mob.getLocation()) > 14 * 14
                    || (camp != null && horizontal(mob.getLocation(), camp) > LEASH)
                    || now - LAST_HURT.getOrDefault(id, now) > RESET_MS;
            if (lost) { it.remove(); RETURNING.put(id, now); continue; }

            Location ml = mob.getLocation(), tl = t.getLocation();
            if (mob instanceof org.bukkit.entity.Mob m && m.isAware()) m.setTarget(t);
            else mob.setRotation((float) Math.toDegrees(Math.atan2(-(tl.getX() - ml.getX()), tl.getZ() - ml.getZ())), 0f);
            double range = attackRange(type) + mob.getWidth() / 2;
            if (ml.distanceSquared(tl) > range * range) continue;
            Long next = NEXT_ATTACK.get(id);
            if (next != null && now < next) continue;
            NEXT_ATTACK.put(id, now + (long) (1000 / Math.max(0.1, fr.lolmc.util.MobCombat.getAttackSpeed(mob))));
            basicAttack(mob, type, t);
        }
        // Retour au camp : le monstre y marche puis récupère tous ses PV (LoL)
        for (var it = RETURNING.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (!(org.bukkit.Bukkit.getEntity(en.getKey()) instanceof LivingEntity mob) || mob.isDead()) {
                it.remove(); forget(en.getKey()); continue;
            }
            Location camp = campOf(mob);
            boolean home = camp == null || horizontal(mob.getLocation(), camp) < 1.5 || now - en.getValue() > 6000L
                    || !(mob instanceof org.bukkit.entity.Mob m && m.isAware());
            if (home) {
                if (mob instanceof org.bukkit.entity.Mob m) { m.setTarget(null); m.setAware(false); }
                fr.lolmc.util.VirtualHP.healFull(mob);
                it.remove(); NEXT_ATTACK.remove(en.getKey());
            } else if (mob instanceof org.bukkit.entity.Mob m) {
                m.setTarget(null);
                m.getPathfinder().moveTo(camp, 1.2);
            }
        }
    }

    /** Attaque de base : coup au corps à corps, ou projectile à tête chercheuse (dégâts à l'impact). */
    private static void basicAttack(LivingEntity mob, MonsterType type, Player t) {
        double ad = fr.lolmc.util.MobCombat.getAD(mob);
        if (ad <= 0) return;
        fr.lolmc.util.MobAnimator.triggerAttack(mob.getUniqueId());
        if (isRanged(type)) {
            Material shot = type == MonsterType.GROMP ? Material.SLIME_BALL
                    : type == MonsterType.BARON ? Material.PURPLE_STAINED_GLASS : dragonShot(type);
            Material burst = type == MonsterType.GROMP ? Material.LIME_STAINED_GLASS
                    : type == MonsterType.BARON ? Material.PURPLE_STAINED_GLASS : dragonGlass(type);
            fr.lolmc.util.TargetingUtil.launchHoming(null, mob.getLocation().add(0, mob.getHeight() * 0.7, 0), t,
                    type == MonsterType.GROMP ? 10.0 : 14.0, new org.bukkit.inventory.ItemStack(shot),
                    type == MonsterType.GROMP ? 0.6f : 0.9f, burst, false, () -> basicHit(t, ad));
            mob.getWorld().playSound(mob.getLocation(),
                    type == MonsterType.GROMP ? Sound.ENTITY_SLIME_SQUISH : Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.7f);
        } else {
            fr.lolmc.util.VisualEffectUtil.impact(t.getWorld(), t.getLocation().add(0, 1, 0),
                    Material.WHITE_STAINED_GLASS, 0.3f, 3L);
            mob.getWorld().playSound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 0.6f);
            basicHit(t, ad);
        }
    }

    private static void basicHit(Player t, double ad) {
        if (t.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || !LolPlugin.getInstance().getChampionManager().hasChampion(t)) return;
        DamageUtil.damage(null, t, ad, false, DamageUtil.Type.PHYSICAL);
    }

    private static Material dragonShot(MonsterType type) {
        return switch (type) {
            case DRAGON_INFERNAL -> Material.FIRE_CHARGE;
            case DRAGON_OCEAN -> Material.HEART_OF_THE_SEA;
            case DRAGON_MOUNTAIN -> Material.COBBLESTONE;
            case DRAGON_CLOUD -> Material.FEATHER;
            case DRAGON_CHEMTECH -> Material.SLIME_BALL;
            default -> Material.AMETHYST_SHARD; // Ancien
        };
    }

    private static Material dragonGlass(MonsterType type) {
        return switch (type) {
            case DRAGON_OCEAN -> Material.LIGHT_BLUE_STAINED_GLASS;
            case DRAGON_MOUNTAIN -> Material.BROWN_STAINED_GLASS;
            case DRAGON_CLOUD -> Material.WHITE_STAINED_GLASS;
            case DRAGON_CHEMTECH -> Material.LIME_STAINED_GLASS;
            case DRAGON_ELDER -> Material.PURPLE_STAINED_GLASS;
            default -> Material.ORANGE_STAINED_GLASS;
        };
    }

    private static Location campOf(LivingEntity mob) {
        var pdc = mob.getPersistentDataContainer();
        Double x = pdc.get(new org.bukkit.NamespacedKey(LolPlugin.getInstance(), "camp_x"), org.bukkit.persistence.PersistentDataType.DOUBLE);
        Double z = pdc.get(new org.bukkit.NamespacedKey(LolPlugin.getInstance(), "camp_z"), org.bukkit.persistence.PersistentDataType.DOUBLE);
        return x == null || z == null ? null : new Location(mob.getWorld(), x, mob.getLocation().getY(), z);
    }

    private static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void forget(UUID id) {
        LAST_HURT.remove(id); NEXT_ATTACK.remove(id); RETURNING.remove(id);
    }

    /** Cible actuelle du monstre (champion qui l'a réveillé), ou null s'il dort. */
    private static Player aggroTarget(LivingEntity mob) {
        UUID t = AGGRO.get(mob.getUniqueId());
        return t != null ? org.bukkit.Bukkit.getPlayer(t) : null;
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
        // Un monstre endormi n'utilise pas ses capacités (avant : il frappait tout champion
        // passant à côté) ; seul le crabe fuit dès qu'on l'approche
        if (type != MonsterType.SCUTTLE_CRAB && aggroTarget(mob) == null) return;
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
        Player aggro = aggroTarget(mob);
        Player nearest = aggro != null && targets.contains(aggro) ? aggro : nearest(mob, targets);
        if (nearest == null) return false;

        switch (type) {
            case SCUTTLE_CRAB -> { return crabFlee(mob, nearest); }
            case RED_BUFF -> { return redSmash(mob, targets); }
            case BLUE_BUFF -> { return blueManaBurn(mob, nearest); }
            case BARON -> { return baronNuke(mob, targets); }
            case HERALD -> { return heraldCharge(mob, nearest); }
            case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_MOUNTAIN,
                 DRAGON_CLOUD, DRAGON_CHEMTECH, DRAGON_ELDER -> {
                return dragonBreath(mob, type, targets, nearest);
            }
            // Camps neutres : pas de capacité, seulement leurs attaques de base
            default -> { return false; }
        }
    }

    // ── Crabe : fuit l'attaquant ──────────────────────────────────

    private boolean crabFlee(LivingEntity crab, Player threat) {
        Vector away = crab.getLocation().toVector()
                .subtract(threat.getLocation().toVector()).normalize().multiply(0.8);
        away.setY(0.1);
        crab.setVelocity(away);
        fr.lolmc.util.VisualEffectUtil.impactBurst(crab.getWorld(),
                crab.getLocation(), Material.LIGHT_BLUE_STAINED_GLASS, 0.2f, 0.3, 5, 6L);
        return false; // pas de cooldown (fuite continue)
    }

    // ── Buff Rouge : coup au sol qui brûle et ralentit ────────────

    private boolean redSmash(LivingEntity mob, List<Player> targets) {
        Location c = mob.getLocation();
        fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                c, Material.MAGMA_BLOCK, 0.3f, 1.5, 7, 10L);
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
                fr.lolmc.util.VisualEffectUtil.impact(victim.getWorld(),
                        victim.getLocation().add(0,1,0), Material.ORANGE_STAINED_GLASS, 0.22f, 4L);
                ticks++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);
    }

    // ── Buff Bleu : tir de mana-burn ──────────────────────────────

    private boolean blueManaBurn(LivingEntity mob, Player target) {
        fr.lolmc.util.TargetingUtil.launchHoming(null, mob.getEyeLocation(), target, 12.0,
                new org.bukkit.inventory.ItemStack(Material.LIGHT_BLUE_STAINED_GLASS), 0.7f,
                Material.LIGHT_BLUE_STAINED_GLASS, false, () -> {
            if (!LolPlugin.getInstance().getChampionManager().hasChampion(target)) return;
            DamageUtil.damage(null, target, 35, true, DamageUtil.Type.MAGICAL);
            // Brûle la ressource (mana/énergie)
            var champ = LolPlugin.getInstance().getChampionManager().getChampion(target);
            champ.getResourceSystem().consume(30);
            target.sendActionBar(Component.text("🔵 Mana brûlé!", NamedTextColor.BLUE));
        });
        return true;
    }

    // ── Baron : nuke de zone + repousse + debuff ──────────────────

    private boolean baronNuke(LivingEntity mob, List<Player> targets) {
        Location c = mob.getLocation();
        fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                c.clone().add(0,1,0), Material.PURPLE_STAINED_GLASS, 0.8f, 8L);
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
                // Effet réel (la Faiblesse vanilla n'agit pas sur les dégâts LoL) : -35 % de dégâts 3 s
                fr.lolmc.LolPlugin.getInstance().getSummonerSpellManager().exhaust(p, 3000L);
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

    private boolean dragonBreath(LivingEntity dragon, MonsterType type, List<Player> targets, Player aim) {
        Location origin = dragon.getEyeLocation();
        // Souffle orienté vers la cible (avant : la direction du regard, arbitraire pour un mob immobile)
        Vector facing = aim.getLocation().add(0, 1, 0).toVector().subtract(origin.toVector());
        facing = facing.lengthSquared() > 1e-6 ? facing.normalize() : origin.getDirection();
        Sound sound = Sound.ENTITY_ENDER_DRAGON_SHOOT;
        Material breathBlock = dragonBlockColor(type);

        // Tracer le cône de souffle
        for (double d = 1; d <= 8; d += 0.5) {
            Location point = origin.clone().add(facing.clone().multiply(d));
            fr.lolmc.util.VisualEffectUtil.impact(point.getWorld(), point, breathBlock, 0.3f, 3L);
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

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private Material dragonBlockColor(MonsterType type) {
        return switch (type) {
            case DRAGON_OCEAN -> Material.LIGHT_BLUE_STAINED_GLASS;
            case DRAGON_MOUNTAIN -> Material.BROWN_STAINED_GLASS;
            case DRAGON_CLOUD -> Material.WHITE_STAINED_GLASS;
            case DRAGON_CHEMTECH -> Material.LIME_STAINED_GLASS;
            case DRAGON_ELDER -> Material.PURPLE_STAINED_GLASS;
            default -> Material.ORANGE_STAINED_GLASS; // DRAGON_INFERNAL, et repli pour tout autre monstre
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
        AGGRO.remove(entityId);
        forget(entityId);
    }
}
