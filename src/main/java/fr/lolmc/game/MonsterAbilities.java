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
 * Mécaniques (wiki officiel, patch 26) :
 *  - Crabe : fuit l'attaquant
 *  - Dragons : Souffle du dragon sur les attaques, Prise d'envol, chaîne du Hextech
 *  - Baron : Corrosion et Corruption du Néant, Regard du Baron, une capacité toutes les 6 attaques
 *    (Bassin d'acide → Attraction → Jet d'acide → Tentacules)
 *  - Héraut : Force colossale, Charge, Balayage à 66 % et 33 % des PV, œil vulnérable dans le dos
 *
 * Combat de base (wiki) : un monstre ne fait rien tant qu'on ne l'a pas frappé. Frappé, tout
 * son camp se réveille et attaque le champion le plus proche à portée de sa laisse, à sa vitesse
 * d'attaque (mêlée, ou projectile pour Gromp, les dragons et le Baron). Patience : chaque
 * changement de cible en coûte un point (6 au total) ; à zéro, sans cible dans la laisse ou sans
 * coup reçu, il rentre au camp en récupérant 6 % de ses PV max par seconde, puis tous ses PV.
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
    /** Patience restante (changements de cible avant de rentrer, monstres non épiques). */
    private static final Map<UUID, Integer> PATIENCE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_PATIENCE = 6;
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
        // Réveil : le monstre vise le champion le plus proche (wiki), pas forcément l'attaquant
        Player first = nearestInLeash(mob, type);
        if (AGGRO.putIfAbsent(mob.getUniqueId(), (first != null ? first : attacker).getUniqueId()) == null) {
            PATIENCE.put(mob.getUniqueId(), MAX_PATIENCE);
            if (type.isDragon()) takingFlight(mob);
        }
        LAST_HURT.put(mob.getUniqueId(), now);
        RETURNING.remove(mob.getUniqueId());
        if (mob instanceof org.bukkit.entity.Mob m && chases(type)) m.setAware(true);
    }

    /** Prise d'envol (wiki) : en début de combat, le dragon repousse les ennemis à moins de 550 unités. */
    private static void takingFlight(LivingEntity dragon) {
        for (Entity e : dragon.getNearbyEntities(5.5, 3, 5.5)) {
            if (!(e instanceof Player p) || !LolPlugin.getInstance().getChampionManager().hasChampion(p)) continue;
            Vector push = p.getLocation().toVector().subtract(dragon.getLocation().toVector()).setY(0);
            if (push.lengthSquared() < 1e-6) continue;
            push.normalize().multiply(1.1).setY(0.35);
            p.setVelocity(push);
        }
        dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.2f, 0.8f);
    }

    /** Baron et dragons restent dans leur fosse ; les autres poursuivent leur cible. */
    private static boolean chases(MonsterType type) {
        return type != MonsterType.BARON && !type.isDragon();
    }

    /** Gromp (« monstre à distance », wiki), dragons et Baron attaquent avec un projectile. */
    private static boolean isRanged(MonsterType type) {
        return type == MonsterType.BARON || type == MonsterType.GROMP || type.isDragon();
    }

    /** Champion valide le plus proche du monstre et dans la laisse de son camp (Baron : 14 blocs). */
    private static Player nearestInLeash(LivingEntity mob, MonsterType type) {
        var cm = LolPlugin.getInstance().getChampionManager();
        Location camp = campOf(mob);
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : mob.getWorld().getPlayers()) {
            if (!cm.hasChampion(p) || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.isDead()) continue;
            double d = p.getLocation().distanceSquared(mob.getLocation());
            if (d > 14 * 14) continue;
            if (type != MonsterType.BARON && camp != null && horizontal(p.getLocation(), camp) > LEASH) continue;
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** Portée d'attaque (blocs, du centre du monstre au champion, hors largeur du monstre). */
    private static double attackRange(MonsterType type) {
        if (type.isDragon()) return 5.3;       // 500 unités
        return switch (type) {
            case BARON -> 9.5;                 // 955
            case HERALD -> 2.6;
            case GROMP -> 1.5;                 // 150
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
            Location camp = campOf(mob);
            // Cible : le champion le plus proche dans la laisse ; en changer coûte de la patience
            // (avant : il gardait le premier attaquant et rentrait se soigner s'il mourait, même
            // si un autre champion le frappait encore)
            Player t = type == null ? null : nearestInLeash(mob, type);
            boolean lost = t == null
                    || (type != MonsterType.BARON && camp != null && horizontal(mob.getLocation(), camp) > LEASH)
                    || now - LAST_HURT.getOrDefault(id, now) > RESET_MS;
            if (!lost && !t.getUniqueId().equals(en.getValue())) {
                if (!type.isEpic() && PATIENCE.merge(id, -1, Integer::sum) <= 0) lost = true;
                else en.setValue(t.getUniqueId());
            }
            if (lost) { it.remove(); PATIENCE.remove(id); RETURNING.put(id, now); continue; }

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
            } else {
                // Retour : 6 % des PV max par seconde (tâche toutes les 0.25 s), plein au camp
                fr.lolmc.util.VirtualHP.heal(mob, fr.lolmc.util.VirtualHP.getMax(mob) * 0.015);
                if (mob instanceof org.bukkit.entity.Mob m) {
                    m.setTarget(null);
                    m.getPathfinder().moveTo(camp, 1.2);
                }
            }
        }
    }

    /** Attaque de base : coup au corps à corps, ou projectile à tête chercheuse (dégâts à l'impact). */
    private static void basicAttack(LivingEntity mob, MonsterType type, Player t) {
        double ad = fr.lolmc.util.MobCombat.getAD(mob);
        if (ad <= 0) return;
        if (type == MonsterType.BARON) { baronAttack(mob, t, ad); return; }
        fr.lolmc.util.MobAnimator.triggerAttack(mob.getUniqueId());
        if (isRanged(type)) {
            Material shot = type == MonsterType.GROMP ? Material.SLIME_BALL
                    : type == MonsterType.BARON ? Material.PURPLE_STAINED_GLASS : dragonShot(type);
            Material burst = type == MonsterType.GROMP ? Material.LIME_STAINED_GLASS
                    : type == MonsterType.BARON ? Material.PURPLE_STAINED_GLASS : dragonGlass(type);
            final boolean hexChain = type == MonsterType.DRAGON_HEXTECH
                    && ATTACK_COUNT.merge(mob.getUniqueId(), 1, Integer::sum) % 4 == 0;
            fr.lolmc.util.TargetingUtil.launchHoming(null, mob.getLocation().add(0, mob.getHeight() * 0.7, 0), t,
                    type == MonsterType.GROMP ? 10.0 : 14.0, new org.bukkit.inventory.ItemStack(shot),
                    type == MonsterType.GROMP ? 0.6f : 0.9f, burst, false, () -> {
                        if (type == MonsterType.GROMP) { grompHit(t, ad); return; }
                        basicHit(t, ad + dragonBreath(type, t));
                        if (hexChain) hextechChain(mob, t, ad);
                    });
            mob.getWorld().playSound(mob.getLocation(),
                    type == MonsterType.GROMP ? Sound.ENTITY_SLIME_SQUISH : Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.7f);
        } else {
            fr.lolmc.util.VisualEffectUtil.impact(t.getWorld(), t.getLocation().add(0, 1, 0),
                    Material.WHITE_STAINED_GLASS, 0.3f, 3L);
            mob.getWorld().playSound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 0.6f);
            // Héraut, Force colossale : +20 % des PV actuels de la cible en dégâts physiques
            double bonus = 0;
            if (type == MonsterType.HERALD && LolPlugin.getInstance().getChampionManager().hasChampion(t))
                bonus = LolPlugin.getInstance().getChampionManager().getChampion(t).getHPSystem().getCurrentHP() * 0.20;
            basicHit(t, ad + bonus);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // BARON NASHOR (wiki)
    // ══════════════════════════════════════════════════════════════

    /** Corruption du Néant : effets cumulés par champion {nombre, fin, armure/RM retirées}. */
    private static final Map<UUID, double[]> VOID_CORRUPTION = new java.util.concurrent.ConcurrentHashMap<>();
    /** Regard du Baron : dernier champion frappé par une attaque de base, et fin de l'effet. */
    private static final Map<UUID, UUID> GAZE_TARGET = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long> GAZE_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();
    /** Rotation des capacités du Baron (indice de la prochaine). */
    private static final Map<UUID, Integer> BARON_ROTATION = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Attaque du Baron : projectile (100 % AD physique, 2 effets de Corruption) et Corrosion
     * (+35 % AD magique, 1 effet). Toutes les 6 attaques, une capacité : la 1re est tirée au hasard,
     * puis l'ordre est fixe (Bassin d'acide → Attraction → Jet d'acide → Tentacules).
     */
    private static void baronAttack(LivingEntity baron, Player t, double ad) {
        int n = ATTACK_COUNT.merge(baron.getUniqueId(), 1, Integer::sum);
        if (n % 6 == 0) {
            int idx = BARON_ROTATION.computeIfAbsent(baron.getUniqueId(), _ -> new Random().nextInt(4));
            BARON_ROTATION.put(baron.getUniqueId(), (idx + 1) % 4);
            // Plus d'unités derrière lui que devant : pics arrière (50 % AD, étourdit 0.5 s)
            if (behindCount(baron) > frontCount(baron)) { rearSpikes(baron, ad); return; }
            switch (idx) {
                case 0 -> acidPool(baron, t, ad);
                case 1 -> formAbility(baron, t, ad);
                case 2 -> acidShot(baron, t, ad);
                default -> tentacleKnockup(baron, t, ad);
            }
            return;
        }
        boolean behind = isBehind(baron, t);
        // Cible devant à moins de 250 unités : de temps en temps, frappe au corps à corps
        // (100 % AD à la cible, 50 % autour) ; cible derrière : pic arrière (100 % AD à l'arrivée)
        if (!behind && baron.getLocation().distance(t.getLocation()) <= 2.5 + baron.getWidth() / 2
                && new Random().nextInt(3) == 0) {
            GAZE_TARGET.put(baron.getUniqueId(), t.getUniqueId());
            GAZE_UNTIL.put(baron.getUniqueId(), System.currentTimeMillis() + 8000L);
            basicHit(t, ad);
            for (Player p : championsNear(t.getLocation(), 2.5)) if (!p.equals(t)) basicHit(p, ad * 0.5);
            baron.getWorld().playSound(baron.getLocation(), Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.2f, 0.6f);
            return;
        }
        if (behind) {
            Location at = t.getLocation().clone();
            fr.lolmc.util.VisualEffectUtil.impact(at.getWorld(), at.clone().add(0, 0.3, 0), Material.PURPLE_STAINED_GLASS, 0.5f, 10L);
            later(10, () -> { if (t.getLocation().distanceSquared(at) <= 2.25) basicHit(t, ad); });
            return;
        }
        GAZE_TARGET.put(baron.getUniqueId(), t.getUniqueId());
        GAZE_UNTIL.put(baron.getUniqueId(), System.currentTimeMillis() + 8000L);
        fr.lolmc.util.TargetingUtil.launchHoming(null, baron.getLocation().add(0, baron.getHeight() * 0.7, 0), t, 14.0,
                new org.bukkit.inventory.ItemStack(Material.PURPLE_STAINED_GLASS), 0.9f, Material.PURPLE_STAINED_GLASS, false, () -> {
                    basicHit(t, ad);
                    addVoidCorruption(t, 2);
                    if (t.getGameMode() != org.bukkit.GameMode.SPECTATOR && LolPlugin.getInstance().getChampionManager().hasChampion(t)) {
                        DamageUtil.damage(null, t, ad * 0.35, false, DamageUtil.Type.MAGICAL); // Corrosion
                        addVoidCorruption(t, 1);
                    }
                });
        baron.getWorld().playSound(baron.getLocation(), Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1f, 0.8f);
    }

    /** Corruption du Néant : -0.5 armure et RM par effet (100 max), 8 s, rafraîchie à chaque coup. */
    private static void addVoidCorruption(Player p, int stacks) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        var st = cm.getChampion(p).getStats();
        double[] v = VOID_CORRUPTION.computeIfAbsent(p.getUniqueId(), _ -> new double[3]);
        v[0] = Math.min(100, v[0] + stacks);
        v[1] = System.currentTimeMillis() + 8000L;
        double want = v[0] * 0.5;
        st.addBonusArmor(-(want - v[2]));
        st.addBonusMR(-(want - v[2]));
        v[2] = want;
    }

    /** Fin de la Corruption du Néant (8 s sans coup du Baron). */
    private static void tickVoidCorruption() {
        long now = System.currentTimeMillis();
        var cm = LolPlugin.getInstance().getChampionManager();
        for (var it = VOID_CORRUPTION.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getValue()[1] > now) continue;
            Player p = org.bukkit.Bukkit.getPlayer(en.getKey());
            if (p != null && cm.hasChampion(p)) {
                cm.getChampion(p).getStats().addBonusArmor(en.getValue()[2]);
                cm.getChampion(p).getStats().addBonusMR(en.getValue()[2]);
            }
            it.remove();
        }
    }

    /**
     * Dégâts subis par un monstre : Regard du Baron, -50 % de la part du dernier champion qu'il a
     * frappé d'une attaque de base (8 s).
     */
    public static double incomingMultiplier(LivingEntity target, Player attacker) {
        if (attacker == null || JungleManager.getMonsterType(target) != MonsterType.BARON) return 1.0;
        UUID gaze = GAZE_TARGET.get(target.getUniqueId());
        return attacker.getUniqueId().equals(gaze) && GAZE_UNTIL.getOrDefault(target.getUniqueId(), 0L) > System.currentTimeMillis()
                ? 0.5 : 1.0;
    }

    private static List<Player> championsNear(Location c, double r) {
        List<Player> out = new ArrayList<>();
        var cm = LolPlugin.getInstance().getChampionManager();
        for (Player p : c.getWorld().getPlayers())
            if (cm.hasChampion(p) && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && p.getLocation().distanceSquared(c) <= r * r) out.add(p);
        return out;
    }

    private static Vector flatDir(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        return v.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : v.normalize();
    }

    /** Bassin d'acide : après 2 s, 3 flaques en cône (100 % AD magique), puis 60 % de ralentissement 2.5 s. */
    private static void acidPool(LivingEntity baron, Player t, double ad) {
        Location o = baron.getLocation();
        Vector dir = flatDir(o, t.getLocation());
        later(40, () -> {
            for (int i = -1; i <= 1; i++) {
                Location pool = o.clone().add(dir.clone().rotateAroundY(Math.toRadians(25 * i)).multiply(6));
                fr.lolmc.util.VisualEffectUtil.groundRing(pool.getWorld(), pool, 2.0, Material.LIME_STAINED_GLASS, 14, 0.3f, 0.1f, 50L);
                for (Player p : championsNear(pool, 2.0)) DamageUtil.damage(null, p, ad, true, DamageUtil.Type.MAGICAL);
                for (int k = 0; k < 5; k++) later(10L * k, () -> {
                    for (Player p : championsNear(pool, 2.0)) LolPlugin.getInstance().getCCManager().slowPct(p, 0.60, 12);
                });
            }
        });
    }

    /** Jet d'acide : après 2 s, une ligne d'acide (100 % AD magique). */
    private static void acidShot(LivingEntity baron, Player t, double ad) {
        Location o = baron.getLocation();
        Vector dir = flatDir(o, t.getLocation());
        later(40, () -> {
            var hit = new HashSet<UUID>();
            for (double d = 1; d <= 12; d += 0.5) {
                Location pt = o.clone().add(dir.clone().multiply(d));
                fr.lolmc.util.VisualEffectUtil.impact(pt.getWorld(), pt.clone().add(0, 0.5, 0), Material.LIME_STAINED_GLASS, 0.25f, 6L);
                for (Player p : championsNear(pt, 1.3))
                    if (hit.add(p.getUniqueId())) DamageUtil.damage(null, p, ad, true, DamageUtil.Type.MAGICAL);
            }
        });
    }

    /** Tentacules : sous la cible, éruption après 1.25 s (100 % AD magique, projeté 1.25 s). */
    private static void tentacleKnockup(LivingEntity baron, Player t, double ad) {
        Location at = t.getLocation().clone();
        fr.lolmc.util.VisualEffectUtil.groundRing(at.getWorld(), at, 2.5, Material.PURPLE_STAINED_GLASS, 16, 0.3f, 0.1f, 25L);
        later(25, () -> {
            for (Player p : championsNear(at, 2.5)) {
                DamageUtil.damage(null, p, ad, true, DamageUtil.Type.MAGICAL);
                LolPlugin.getInstance().getCCManager().setAirborne(p, 25);
            }
        });
    }

    private static boolean isBehind(LivingEntity baron, Player p) {
        Vector facing = baron.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) return false;
        return facing.angle(flatDir(baron.getLocation(), p.getLocation())) > Math.toRadians(90);
    }
    private static int behindCount(LivingEntity baron) {
        int n = 0; for (Player p : championsNear(baron.getLocation(), 9.5)) if (isBehind(baron, p)) n++; return n;
    }
    private static int frontCount(LivingEntity baron) {
        int n = 0; for (Player p : championsNear(baron.getLocation(), 9.5)) if (!isBehind(baron, p)) n++; return n;
    }

    /** Pics arrière (wiki) : 50 % AD physique et 0.5 s d'étourdissement aux unités derrière le Baron. */
    private static void rearSpikes(LivingEntity baron, double ad) {
        for (Player p : championsNear(baron.getLocation(), 6)) {
            if (!isBehind(baron, p)) continue;
            DamageUtil.damage(null, p, ad * 0.5, false, DamageUtil.Type.PHYSICAL);
            LolPlugin.getInstance().getCCManager().stun(p, 10);
        }
        baron.getWorld().playSound(baron.getLocation(), Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1.5f, 0.6f);
    }

    /** Capacité de forme, selon la forme tirée pour la partie. */
    private static void formAbility(LivingEntity baron, Player t, double ad) {
        Integer form = baron.getPersistentDataContainer().get(JungleManager.KEY_BARON_FORM,
                org.bukkit.persistence.PersistentDataType.INTEGER);
        switch (form != null ? form : 1) {
            case 0 -> huntingLightning(baron);
            case 2 -> allSeeingRifts(baron);
            default -> territorialPull(baron, t, ad);
        }
    }

    /**
     * Forme Chasseur : la foudre frappe les champions (1re après 0.7 s, puis toutes les 0.1 s), 20 %
     * de leurs PV actuels en dégâts magiques, divisés par deux à chaque frappe suivante.
     */
    private static void huntingLightning(LivingEntity baron) {
        var targets = championsNear(baron.getLocation(), 20);
        var cm = LolPlugin.getInstance().getChampionManager();
        for (int i = 0; i < targets.size(); i++) {
            Player p = targets.get(i);
            double pct = 0.20 / Math.pow(2, i);
            later(14 + 2L * i, () -> {
                if (!cm.hasChampion(p) || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
                p.getWorld().strikeLightningEffect(p.getLocation());
                DamageUtil.damage(null, p, cm.getChampion(p).getHPSystem().getCurrentHP() * pct, false, DamageUtil.Type.MAGICAL);
            });
        }
    }

    /**
     * Forme Omniscient : faille du Néant sous les deux champions visibles les plus éloignés (22 blocs),
     * 3 s : 150 dégâts magiques puis 50 toutes les 0.166 s (1000 au total).
     */
    private static void allSeeingRifts(LivingEntity baron) {
        var targets = championsNear(baron.getLocation(), 22);
        targets.sort((a, b) -> Double.compare(b.getLocation().distanceSquared(baron.getLocation()),
                a.getLocation().distanceSquared(baron.getLocation())));
        for (int i = 0; i < Math.min(2, targets.size()); i++) {
            Location at = targets.get(i).getLocation().clone();
            fr.lolmc.util.VisualEffectUtil.groundRing(at.getWorld(), at, 2.0, Material.PURPLE_STAINED_GLASS, 16, 0.3f, 0.1f, 60L);
            for (int tick = 0; tick <= 18; tick++) {
                final double dmg = tick == 0 ? 150 : 50;
                later(Math.round(tick * 3.33), () -> {
                    for (Player p : championsNear(at, 2.0)) DamageUtil.damage(null, p, dmg, false, DamageUtil.Type.MAGICAL);
                });
            }
        }
    }

    /** Forme Territorial : après 1.5 s, attire les unités d'un cône de 3 blocs (140 % AD magique). */
    private static void territorialPull(LivingEntity baron, Player t, double ad) {
        Location o = baron.getLocation();
        Vector dir = flatDir(o, t.getLocation());
        later(30, () -> {
            for (Player p : championsNear(o, 10)) {
                Vector to = flatDir(o, p.getLocation());
                if (to.angle(dir) > Math.toRadians(35)) continue;
                DamageUtil.damage(null, p, ad * 1.40, true, DamageUtil.Type.MAGICAL);
                p.setVelocity(to.clone().multiply(-1.0).setY(0.2));
            }
        });
    }

    private static void later(long ticks, Runnable r) {
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), r, ticks);
    }

    // ══════════════════════════════════════════════════════════════
    // HÉRAUT DE LA FAILLE (wiki)
    // ══════════════════════════════════════════════════════════════

    /** Œil du Héraut : fermé jusqu'à, et dernière ouverture. */
    private static final Map<UUID, Long> EYE_CLOSED_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();
    /** Seuils de Balayage déjà déclenchés (66 % puis 33 %). */
    private static final Map<UUID, Integer> SWIPES_DONE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Attaque de base d'un champion sur le Héraut : dans son dos, l'œil subit 12 % de ses PV max en
     * dégâts bruts, puis se ferme 6 s (pas d'ouverture sous 15 % de PV).
     */
    public static void onChampionBasicAttack(Player attacker, LivingEntity mob) {
        if (JungleManager.getMonsterType(mob) != MonsterType.HERALD) return;
        long now = System.currentTimeMillis();
        if (EYE_CLOSED_UNTIL.getOrDefault(mob.getUniqueId(), 0L) > now) return;
        double max = fr.lolmc.util.VirtualHP.getMax(mob);
        if (fr.lolmc.util.VirtualHP.getCurrent(mob) < max * 0.15) return;
        Vector facing = mob.getLocation().getDirection().setY(0);
        Vector toAttacker = attacker.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
        if (facing.lengthSquared() < 1e-6 || toAttacker.lengthSquared() < 1e-6) return;
        if (facing.angle(toAttacker) < Math.toRadians(120)) return; // pas dans le dos
        EYE_CLOSED_UNTIL.put(mob.getUniqueId(), now + 6000L);
        fr.lolmc.util.MobCombat.damage(attacker, mob, max * 0.12, DamageUtil.Type.TRUE);
        attacker.sendActionBar(Component.text("👁 Œil du Héraut touché !", NamedTextColor.LIGHT_PURPLE));
    }

    /** Balayage : à 65.75 % et 32.75 % des PV, après 1.5 s, cône de 125 % AD physique. */
    private static void heraldSwipeCheck(LivingEntity mob, Player target) {
        double ratio = fr.lolmc.util.VirtualHP.getCurrent(mob) / Math.max(1, fr.lolmc.util.VirtualHP.getMax(mob));
        int done = SWIPES_DONE.getOrDefault(mob.getUniqueId(), 0);
        int due = ratio <= 0.3275 ? 2 : ratio <= 0.6575 ? 1 : 0;
        if (due <= done) return;
        SWIPES_DONE.put(mob.getUniqueId(), due);
        double ad = fr.lolmc.util.MobCombat.getAD(mob);
        Location o = mob.getLocation();
        Vector dir = flatDir(o, target.getLocation());
        later(30, () -> {
            for (Player p : championsNear(o, 4.5)) {
                if (flatDir(o, p.getLocation()).angle(dir) > Math.toRadians(60)) continue;
                DamageUtil.damage(null, p, ad * 1.25, false, DamageUtil.Type.PHYSICAL);
            }
            mob.getWorld().playSound(o, Sound.ENTITY_RAVAGER_ATTACK, 1f, 0.7f);
        });
    }

    private static final Map<UUID, Integer> ATTACK_COUNT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Souffle du dragon (wiki) : +5 % des PV actuels de la cible (4 % pour le Chimtech). */
    private static double dragonBreath(MonsterType type, Player t) {
        if (!type.isDragon()) return 0;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(t)) return 0;
        return cm.getChampion(t).getHPSystem().getCurrentHP() * (type == MonsterType.DRAGON_CHEMTECH ? 0.04 : 0.05);
    }

    /** Dragon Hextech : une attaque sur 4 rebondit sur 3 ennemis proches et les ralentit de 40 % (2 s). */
    private static void hextechChain(LivingEntity dragon, Player first, double ad) {
        var cc = LolPlugin.getInstance().getCCManager();
        int n = 0;
        for (Entity e : first.getNearbyEntities(4, 3, 4)) {
            if (n >= 3) break;
            if (!(e instanceof Player p) || p.equals(first) || !LolPlugin.getInstance().getChampionManager().hasChampion(p)
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            n++;
            basicHit(p, ad);
            if (cc != null) cc.slowDecaying(p, 0.40, 40);
        }
        if (cc != null) cc.slowDecaying(first, 0.40, 40);
    }

    /** Gromp (wiki) : 100 % AD + 5 % des PV actuels de la cible, en dégâts magiques. */
    private static void grompHit(Player t, double ad) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || !cm.hasChampion(t)) return;
        double bonus = cm.getChampion(t).getHPSystem().getCurrentHP() * 0.05;
        DamageUtil.damage(null, t, ad + bonus, false, DamageUtil.Type.MAGICAL);
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
            case DRAGON_HEXTECH -> Material.COPPER_INGOT;
            default -> Material.AMETHYST_SHARD; // Ancien
        };
    }

    private static Material dragonGlass(MonsterType type) {
        return switch (type) {
            case DRAGON_OCEAN -> Material.LIGHT_BLUE_STAINED_GLASS;
            case DRAGON_MOUNTAIN -> Material.BROWN_STAINED_GLASS;
            case DRAGON_CLOUD -> Material.WHITE_STAINED_GLASS;
            case DRAGON_CHEMTECH -> Material.LIME_STAINED_GLASS;
            case DRAGON_HEXTECH -> Material.CYAN_STAINED_GLASS;
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
        LAST_HURT.remove(id); NEXT_ATTACK.remove(id); RETURNING.remove(id); ATTACK_COUNT.remove(id); PATIENCE.remove(id);
    }

    /** Cible actuelle du monstre (champion qui l'a réveillé), ou null s'il dort. */
    private static Player aggroTarget(LivingEntity mob) {
        UUID t = AGGRO.get(mob.getUniqueId());
        return t != null ? org.bukkit.Bukkit.getPlayer(t) : null;
    }

    private void startAbilityLoop() {
        new BukkitRunnable() {
            @Override public void run() {
                tickVoidCorruption();
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
            case HERALD -> { heraldSwipeCheck(mob, nearest); return heraldCharge(mob, nearest); }
            // Buffs, dragons et camps : pas de capacité, seulement leurs attaques de base (LoL)
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

    // ── Héraut : charge qui repousse ──────────────────────────────

    /**
     * Charge (wiki) : après 2.5 s, le Héraut bondit vers la position de sa cible et inflige 200 % AD
     * physique aux champions sur sa trajectoire en les écartant.
     */
    private boolean heraldCharge(LivingEntity mob, Player target) {
        Location from = mob.getLocation().clone();
        Location to = target.getLocation().clone();
        mob.getWorld().playSound(from, Sound.ENTITY_RAVAGER_ROAR, 1f, 1f);
        double ad = fr.lolmc.util.MobCombat.getAD(mob);
        later(50, () -> {
            if (!mob.isValid() || mob.isDead()) return;
            Vector dir = flatDir(from, to);
            double len = Math.min(8, from.distance(to));
            var hit = new HashSet<UUID>();
            for (double d = 0; d <= len; d += 0.5) {
                Location pt = from.clone().add(dir.clone().multiply(d));
                for (Player p : championsNear(pt, 1.5)) {
                    if (!hit.add(p.getUniqueId())) continue;
                    DamageUtil.damage(null, p, ad * 2.0, false, DamageUtil.Type.PHYSICAL);
                    Vector side = new Vector(-dir.getZ(), 0.4, dir.getX());
                    p.setVelocity(side.multiply(0.8));
                }
            }
            mob.setVelocity(dir.clone().multiply(1.6).setY(0.2));
        });
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private double abilityRange(MonsterType type) {
        if (type.isEpic()) return 10;
        if (type == MonsterType.RED_BUFF || type == MonsterType.BLUE_BUFF) return 6;
        return 4;
    }

    private long abilityCooldown(MonsterType type) {
        return switch (type) {
            case BARON, DRAGON_ELDER -> 5000L;
            case HERALD -> 8000L; // charge : fenêtre de 2.5 s puis bond
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
        GAZE_TARGET.remove(entityId); GAZE_UNTIL.remove(entityId); BARON_ROTATION.remove(entityId);
        EYE_CLOSED_UNTIL.remove(entityId); SWIPES_DONE.remove(entityId);
        forget(entityId);
    }
}
