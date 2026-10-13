package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.GameStructure.TurretTier;
import fr.lolmc.game.GameStructure.Type;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Comportement des tourelles (wiki LoL, Faille de l'invocateur) :
 * <ul>
 *   <li>0,833 attaque/s, portée 750 unités (config), dégâts selon le niveau et la minute.</li>
 *   <li>La tourelle garde sa cible tant qu'elle est valide ; nouvelle cible par priorité :
 *       canon/super-sbire, mêlée, caster, puis champions (le plus proche de chaque classe).</li>
 *   <li>Appel à l'aide : si elle ne tire pas sur un champion, elle passe sur un champion ennemi
 *       qui vient de blesser un champion allié à moins de 1400 unités.</li>
 *   <li>Montée en puissance : +50 % par tir touchant un champion (max +150 %), remise à zéro
 *       5 s après le dernier tir sur un champion (pas au changement de cible).</li>
 *   <li>Armure 60 (avec 30 % de pénétration de ses tirs) ; tourelles extérieures : -10 armure
 *       par minute de 11:00 à 15:00 (-40 max) ; Rempart à chaque plaque.</li>
 *   <li>Plaques (patch 26.1) : 5 par tourelle sauf celles du nexus, permanentes, qui tombent à
 *       90 / 75 / 55 / 30 / 0 % des PV.</li>
 *   <li>Tourelles d'inhibiteur : +3 PV/s ; tourelles de nexus : +6 PV/s, réapparition 3 min
 *       après leur destruction à 40 % des PV (paliers de régénération du wiki).</li>
 *   <li>Croissance cristalline : voir {@link #consumeOvergrowth}.</li>
 * </ul>
 */
public class TurretManager {

    private final MapManager mapManager;
    private final ChampionManager championManager;
    private final TeamManager teamManager;

    // 0,833 attaque par seconde → un tir toutes les 1,2 s (24 ticks)
    private static final long ATTACK_PERIOD = 24L;
    private static final double SECONDS_PER_SHOT = ATTACK_PERIOD / 20.0;
    /** Portée de l'appel à l'aide : 1400 unités. */
    private static final double CALL_FOR_HELP_RANGE = 14.0;
    private static final double TURRET_ARMOR = 60.0;
    private static final double TURRET_SHOT_ARMOR_PEN = 0.30;
    private static final long WARMUP_RESET_MS = 5000L;
    private static final long NEXUS_TURRET_RESPAWN_MS = 180_000L;

    // Valeurs lues depuis la config (mêmes pour toutes les tours)
    private final double attackRadius;     // rayon horizontal du cylindre (blocs)
    private final double detectionHeight;  // hauteur de la zone de détection (blocs)
    private final double beamHeight;       // hauteur d'où part l'animation du tir (blocs)
    // Plaques restantes par tourelle (permanentes depuis le patch 26.1)
    private final java.util.Map<String, Integer> platingLeft = new java.util.concurrent.ConcurrentHashMap<>();
    public static final int MAX_PLATING = 5;
    /** PV restants (en part des PV max) sous lesquels chaque plaque tombe. */
    private static final double[] PLATE_THRESHOLDS = {0.90, 0.75, 0.55, 0.30, 0.0};
    private long gameStartMs = 0;
    /** Début de partie : remet toutes les plaques et les états des tourelles. */
    public void setGameStart() {
        gameStartMs = System.currentTimeMillis();
        platingLeft.clear(); target.clear(); warmup.clear(); lastChampionHit.clear();
        bulwark.clear(); destroyedAt.clear(); overgrowthSince.clear(); overgrowthNext.clear();
    }

    /** Tourelle à plaques : toutes sauf celles du nexus (patch 26.1). */
    public static boolean hasPlatesTier(GameStructure s) {
        return s.getType() == Type.TURRET && s.getTier() != TurretTier.NEXUS;
    }

    /** Nombre de plaques qui doivent être tombées pour ces PV (paliers de 26.1). */
    public static int platesFallen(GameStructure s) {
        double ratio = s.getCurrentHP() / s.getMaxHP();
        int fallen = 0;
        for (double t : PLATE_THRESHOLDS) if (ratio <= t + 1e-9) fallen++;
        return fallen;
    }

    /** Tourelle de lane (pas de base) : soumise à la Croissance cristalline. */
    private static boolean isLaneTurret(GameStructure s) {
        return s.getType() == Type.TURRET && !"base".equals(s.getLane());
    }

    /**
     * Croissance cristalline (wiki LoL) : à partir de 1:40, les tourelles de lane se couvrent de
     * cristaux. Le prochain champion ennemi qui attaque la tourelle les consomme et inflige des
     * vrais dégâts bonus, en % des PV max de la tourelle selon le niveau moyen de son équipe :
     * 2 à 3,3 % au niveau 1, 8,8 à 18,9 % au niveau 18. La valeur reste au minimum 60 s, monte
     * linéairement pendant 240 s, puis reste au maximum. Pas consommée sous Armure renforcée.
     * Notes du patch 26.1 : apparition après « 90 s de démarrage/délai » → les cristaux
     * repoussent 90 s après leur consommation. Si une unité ennemie est près de la tourelle
     * quand ils devraient apparaître, ils attendent qu'elle parte, puis leur croissance est
     * rattrapée (elle compte depuis l'heure prévue).
     *
     * @return les vrais dégâts bonus (0 si la tourelle n'est pas cristallisée)
     */
    public double consumeOvergrowth(GameStructure turret, Team attackingTeam) {
        if (!isLaneTurret(turret)) return 0;
        Long since = overgrowthSince.get(turret.getId());
        if (since == null) return 0;
        long now = System.currentTimeMillis();
        overgrowthSince.remove(turret.getId());
        overgrowthNext.put(turret.getId(), now + OVERGROWTH_COOLDOWN_MS);
        double growth = Math.max(0, Math.min(1, ((now - since) / 1000.0 - 60) / 240.0));
        double lvl = 1, n = 0;
        double sum = 0;
        for (Player p : turret.getCenter().getWorld().getPlayers()) {
            if (!championManager.hasChampion(p) || teamManager.getTeam(p) != attackingTeam) continue;
            sum += championManager.getChampion(p).getLevelSystem().getLevel();
            n++;
        }
        if (n > 0) lvl = sum / n;
        double f = Math.max(0, Math.min(1, (lvl - 1) / 17.0));
        double minPct = 0.02 + (0.088 - 0.02) * f;
        double maxPct = 0.033 + (0.189 - 0.033) * f;
        return turret.getMaxHP() * (minPct + (maxPct - minPct) * growth);
    }

    /** Fait apparaître les cristaux (1:40, puis 90 s après consommation) et les affiche. */
    private void tickOvergrowth(GameStructure turret) {
        if (!isLaneTurret(turret) || gameStartMs == 0) return;
        String id = turret.getId();
        if (!overgrowthSince.containsKey(id)) {
            long due = overgrowthNext.getOrDefault(id, gameStartMs + OVERGROWTH_START_MS);
            if (System.currentTimeMillis() < due) return;
            if (enemyUnitNear(turret)) return; // différé tant qu'un ennemi est là
            overgrowthSince.put(id, due);      // croissance rattrapée depuis l'heure prévue
        }
        fr.lolmc.util.VisualEffectUtil.impact(turret.getCenter().getWorld(),
                turret.getCenter().clone().add(0.5, 1.5, 0.5), Material.AMETHYST_BLOCK, 0.3f, 6L);
    }

    /** Une unité ennemie (champion vivant ou sbire) est-elle à portée de la tourelle ? */
    private boolean enemyUnitNear(GameStructure turret) {
        Location center = turret.getCenter().clone().add(0.5, detectionHeight, 0.5);
        for (var e : center.getWorld().getNearbyEntities(center, boxRange(), boxRange(), boxRange()))
            if (e instanceof LivingEntity le && isValidTarget(center, turret.getTeam(), le)) return true;
        return false;
    }

    // Cible actuelle de chaque tourelle (UUID d'entité)
    private final Map<String, UUID> target = new HashMap<>();
    // Montée en puissance (nombre de tirs sur champion) et dernier tir sur champion
    private final Map<String, Integer> warmup = new HashMap<>();
    private final Map<String, Long> lastChampionHit = new HashMap<>();
    // Rempart (plaques) : tourelle → liste {fin, bonus d'armure}
    private final Map<String, List<double[]>> bulwark = new HashMap<>();
    // Tourelles de nexus détruites → moment de la destruction (réapparition)
    private final Map<String, Long> destroyedAt = new HashMap<>();
    // Croissance cristalline : tourelle → début de la croissance (absent = pas encore / consommée)
    private final Map<String, Long> overgrowthSince = new HashMap<>();
    // Prochaine apparition des cristaux (1:40, puis 90 s après chaque consommation)
    private final Map<String, Long> overgrowthNext = new HashMap<>();
    private static final long OVERGROWTH_START_MS = 100_000L; // 1:40
    private static final long OVERGROWTH_COOLDOWN_MS = 90_000L;

    public TurretManager(MapManager mapManager, ChampionManager championManager, TeamManager teamManager) {
        this.mapManager = mapManager;
        this.championManager = championManager;
        this.teamManager = teamManager;
        var config = LolPlugin.getInstance().getConfig();
        this.attackRadius = config.getDouble("turrets.attack-radius", 7.5);
        this.detectionHeight = config.getDouble("turrets.detection-height", 6.0);
        this.beamHeight = config.getDouble("turrets.beam-height", 6.0);
        startTurretTaskTracked();
    }

    private double minutes() {
        return gameStartMs == 0 ? 0 : (System.currentTimeMillis() - gameStartMs) / 60000.0;
    }

    /** Dégâts d'un tir sur un champion selon le niveau de la tourelle et la minute (wiki). */
    private double shotDamage(GameStructure turret) {
        int m = (int) Math.floor(minutes());
        return switch (turret.getTier()) {
            // 182 + 12 à 0:30 puis chaque minute, max 350 à 13:30
            case OUTER -> Math.min(350, 182 + 12.0 * Math.max(0, minutes() >= 0.5 ? (int) Math.floor(minutes() - 0.5) + 1 : 0));
            // 187 + 16 à 3:00 puis chaque minute, max 427 à 17:00
            case INNER, INHIBITOR -> Math.min(427, 187 + 16.0 * Math.max(0, m - 2));
            // 165 + 16 à 3:00 puis chaque minute, max 405 à 17:00
            case NEXUS -> Math.min(405, 165 + 16.0 * Math.max(0, m - 2));
        };
    }

    /**
     * Armure d'une tourelle face aux champions : 60, moins 10 par minute de 11:00 à 15:00 pour
     * les tourelles extérieures (-40 au total ; la RM, elle, baisse de 15/min jusqu'à -60), plus
     * le Rempart des plaques.
     */
    public double getArmor(GameStructure turret) {
        double armor = TURRET_ARMOR;
        if (turret.getTier() == TurretTier.OUTER && minutes() > 11)
            armor -= Math.min(40, 10 * Math.floor(minutes() - 10));
        var stacks = bulwark.get(turret.getId());
        if (stacks != null) {
            long now = System.currentTimeMillis();
            stacks.removeIf(st -> st[0] < now);
            for (double[] st : stacks) armor += st[1];
        }
        return Math.max(0, armor);
    }

    /**
     * Rempart : à chaque plaque perdue (90 / 75 / 55 / 30 % des PV), la tourelle gagne 20 s de
     * 30 à 50 armure selon le nombre de champions ennemis proches (patch 26.3, durées cumulables).
     */
    public void onTurretDamaged(GameStructure turret, double hpBefore, Team attackingTeam) {
        if (!hasPlatesTier(turret)) return;
        double before = hpBefore / turret.getMaxHP(), after = turret.getCurrentHP() / turret.getMaxHP();
        int enemies = 0;
        for (Player p : turret.getCenter().getWorld().getPlayers())
            if (championManager.hasChampion(p) && teamManager.getTeam(p) == attackingTeam
                    && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && p.getLocation().distanceSquared(turret.getCenter()) <= 12 * 12) enemies++;
        double bonus = Math.max(30, Math.min(50, 25 + 5.0 * enemies));
        for (double th : new double[]{0.90, 0.75, 0.55, 0.30}) {
            if (before > th && after <= th)
                bulwark.computeIfAbsent(turret.getId(), _ -> new ArrayList<>())
                       .add(new double[]{System.currentTimeMillis() + 20_000L, bonus});
        }
    }

    /** Clé des plaques : propre à chaque tourelle (avant : partagée par toute la lane). */
    public static String platingKey(GameStructure s) {
        return s.getId();
    }

    private void processTurret(GameStructure turret) {
        Location center = turret.getCenter().clone().add(0.5, detectionHeight, 0.5);
        Location beamOrigin = turret.getCenter().clone().add(0.5, beamHeight, 0.5);
        Team turretTeam = turret.getTeam();
        String id = turret.getId();

        // Montée en puissance perdue 5 s après le dernier tir sur un champion
        Long last = lastChampionHit.get(id);
        if (last != null && System.currentTimeMillis() - last > WARMUP_RESET_MS) {
            warmup.remove(id); lastChampionHit.remove(id);
        }

        // Cible actuelle encore valide ?
        LivingEntity current = null;
        UUID curId = target.get(id);
        if (curId != null) {
            Entity e = Bukkit.getEntity(curId);
            if (e instanceof LivingEntity le && isValidTarget(center, turretTeam, le)) current = le;
        }
        // Appel à l'aide : prioritaire si la tourelle ne tire pas déjà sur un champion
        if (!(current instanceof Player)) {
            Player helper = findCallForHelp(turret, center, turretTeam);
            if (helper != null) current = helper;
        }
        if (current == null) current = findNewTarget(center, turretTeam);

        if (current == null) { target.remove(id); return; }
        target.put(id, current.getUniqueId());
        if (current instanceof Player p) fireAtPlayer(turret, beamOrigin, p);
        else fireAtMinion(turret, beamOrigin, current);
    }

    // ══════════════════════════════════════════════════════════════
    // DÉTECTION CYLINDRIQUE (rayon horizontal + hauteur)
    // ══════════════════════════════════════════════════════════════

    /** Cylindre d'attaque : distance horizontale ≤ rayon ET écart vertical ≤ hauteur. */
    private boolean inCylinder(Location turretCanon, Location target) {
        if (!turretCanon.getWorld().equals(target.getWorld())) return false;
        double dx = target.getX() - turretCanon.getX();
        double dz = target.getZ() - turretCanon.getZ();
        if (Math.sqrt(dx * dx + dz * dz) > attackRadius) return false;
        return Math.abs(target.getY() - turretCanon.getY()) <= detectionHeight;
    }

    private double boxRange() {
        return Math.max(attackRadius, detectionHeight) + 1;
    }

    private boolean isValidTarget(Location center, Team turretTeam, LivingEntity le) {
        if (le.isDead() || !inCylinder(center, le.getLocation())) return false;
        if (le instanceof Player p) {
            return championManager.hasChampion(p) && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && teamManager.getTeam(p) != null && teamManager.getTeam(p) != turretTeam;
        }
        if (!MinionManager.isMinion(le)) return false;
        Team mt = MinionManager.getMinionTeam(le);
        return mt != null && mt != turretTeam;
    }

    /** Priorité LoL d'une nouvelle cible (plus petit = plus prioritaire). */
    private static int priority(LivingEntity le) {
        if (le instanceof Player) return 4;
        return switch (String.valueOf(MinionManager.getMinionTypeTag(le))) {
            case "cannon", "super" -> 1;
            case "caster" -> 3;
            default -> 2; // mêlée
        };
    }

    private LivingEntity findNewTarget(Location center, Team turretTeam) {
        LivingEntity best = null;
        int bestPrio = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (var entity : center.getWorld().getNearbyEntities(center, boxRange(), boxRange(), boxRange())) {
            if (!(entity instanceof LivingEntity le) || !isValidTarget(center, turretTeam, le)) continue;
            int prio = priority(le);
            double d = le.getLocation().distanceSquared(center);
            if (prio < bestPrio || (prio == bestPrio && d < bestDist)) {
                best = le; bestPrio = prio; bestDist = d;
            }
        }
        return best;
    }

    /** Champion ennemi à portée qui vient de blesser un champion allié à moins de 1400 unités. */
    private Player findCallForHelp(GameStructure turret, Location center, Team turretTeam) {
        var aggro = LolPlugin.getInstance().getAggroManager();
        if (aggro == null) return null;
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : center.getWorld().getPlayers()) {
            if (!isValidTarget(center, turretTeam, p)) continue;
            Player victim = aggro.getRecentChampionVictim(p);
            if (victim == null || teamManager.getTeam(victim) != turretTeam) continue;
            if (!victim.getWorld().equals(center.getWorld())
                    || victim.getLocation().distanceSquared(turret.getCenter()) > CALL_FOR_HELP_RANGE * CALL_FOR_HELP_RANGE)
                continue;
            double d = p.getLocation().distanceSquared(center);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    // ── Tir ───────────────────────────────────────────────────────

    private void fireAtPlayer(GameStructure turret, Location beamFrom, Player victim) {
        String id = turret.getId();
        int stacks = warmup.getOrDefault(id, 0);
        double damage = shotDamage(turret) * (1.0 + Math.min(3, stacks) * 0.50);
        warmup.put(id, Math.min(3, stacks + 1));
        lastChampionHit.put(id, System.currentTimeMillis());

        // Tir à tête chercheuse (1200 unités/s, imblocable) : dégâts à l'impact
        fr.lolmc.util.TargetingUtil.launchHoming(null, beamFrom, victim, 12.0,
                new org.bukkit.inventory.ItemStack(Material.WHITE_STAINED_GLASS), 0.7f, Material.WHITE_STAINED_GLASS, false,
                () -> turretShotHit(victim, damage));
        beamFrom.getWorld().playSound(beamFrom, Sound.ENTITY_ARROW_SHOOT, 0.6f, 0.8f);
    }

    private void turretShotHit(Player victim, double damage) {
        if (victim.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        // Dégâts physiques (30 % de pénétration d'armure), puis DamageUtil en vrais dégâts pour
        // les boucliers, Zhonya, le récap et la MORT (avant : PV retirés directement, sans
        // jamais déclencher la mort du champion)
        var champ = championManager.getChampion(victim);
        if (champ == null) return;
        double armor = champ.getStats().getFinalArmor();
        double effArmor = armor > 0 ? armor * (1 - TURRET_SHOT_ARMOR_PEN) : armor;
        double reduced = damage * fr.lolmc.util.MobCombat.resistMultiplier(effArmor);
        fr.lolmc.util.DamageUtil.damage(null, victim, reduced, false, fr.lolmc.util.DamageUtil.Type.TRUE);
        var drm = LolPlugin.getInstance().getDeathRecapManager();
        if (drm != null) drm.record(victim.getUniqueId(), "Tourelle", "physical", reduced);
        victim.sendActionBar(Component.text(String.format("🗼 Tourelle! -%.0f", reduced), NamedTextColor.RED));
    }

    private void fireAtMinion(GameStructure turret, Location beamFrom, LivingEntity minion) {
        // Un tir retire un % des PV max selon le type (wiki LoL) : mêlée 3 tirs, caster 2,
        // canon 8, super-sbire 20 — appliqué à l'impact du tir à tête chercheuse
        double pct = switch (String.valueOf(MinionManager.getMinionTypeTag(minion))) {
            case "caster" -> 0.70;
            case "cannon" -> 0.14;
            case "super"  -> 0.05;
            default       -> 0.45;
        };
        fr.lolmc.util.TargetingUtil.launchHoming(null, beamFrom, minion, 12.0,
                new org.bukkit.inventory.ItemStack(Material.WHITE_STAINED_GLASS), 0.6f, Material.WHITE_STAINED_GLASS, false,
                () -> fr.lolmc.util.MobCombat.damage(null, minion,
                        fr.lolmc.util.VirtualHP.getMax(minion) * pct, fr.lolmc.util.DamageUtil.Type.TRUE));
    }

    private void shootBeam(Location from, Location to) {
        var dir = to.toVector().subtract(from.toVector()).normalize();
        double dist = from.distance(to);
        fr.lolmc.util.VisualEffectUtil.skillshotLine(from, dir, dist,
                Material.WHITE_STAINED_GLASS, 0.12f, 4L);
        from.getWorld().playSound(from, Sound.ENTITY_ARROW_SHOOT, 0.6f, 0.8f);
        fr.lolmc.util.VisualEffectUtil.impact(to.getWorld(), to, Material.WHITE_STAINED_GLASS, 0.3f, 4L);
    }

    // ── Régénération des tourelles de base, réapparition des tourelles de nexus ──

    /** Palier de régénération : on ne remonte qu'au palier de la tranche actuelle (wiki). */
    private static double regenCap(double ratio, double low, double mid) {
        return ratio < low ? low : ratio < mid ? mid : 1.0;
    }

    private void regenAndRespawn(GameStructure s) {
        if (s.getType() != Type.TURRET) return;
        TurretTier tier = s.getTier();
        if (tier != TurretTier.INHIBITOR && tier != TurretTier.NEXUS) return;
        String id = s.getId();
        if (s.isDestroyed()) {
            if (tier != TurretTier.NEXUS) return;
            long now = System.currentTimeMillis();
            Long at = destroyedAt.putIfAbsent(id, now);
            if (at != null && now - at >= NEXUS_TURRET_RESPAWN_MS) {
                destroyedAt.remove(id);
                s.reviveAt(0.40);
                mapManager.updateStructurePhase(s);
                fr.lolmc.util.WorldContext.broadcast(s.getCenter().getWorld(), Component.text(
                        "🗼 Une tourelle de nexus " + (s.getTeam() == Team.BLUE ? "bleue" : "rouge")
                        + " est réapparue.", NamedTextColor.YELLOW));
            }
            return;
        }
        double ratio = s.getCurrentHP() / s.getMaxHP();
        double cap = tier == TurretTier.NEXUS ? regenCap(ratio, 0.40, 0.70) : regenCap(ratio, 0.30, 0.75);
        double perSec = tier == TurretTier.NEXUS ? 6 : 3;
        if (s.heal(perSec * SECONDS_PER_SHOT, cap)) mapManager.updateStructurePhase(s);
    }

    public double getTurretRange() { return attackRadius; }

    public boolean hasPlating(String key) {
        if (gameStartMs == 0) return false;
        return platingLeft.getOrDefault(key, MAX_PLATING) > 0;
    }

    /** Retire les plaques tombées sans verser d'or (dégâts des sbires). */
    public void dropPlatesSilently(GameStructure s) {
        String key = platingKey(s);
        int remain = MAX_PLATING - platesFallen(s);
        while (hasPlating(key) && getPlatingLeft(key) > remain) tickPlating(key);
    }

    /** Valeur d'une plaque : 120 or ; tourelles extérieures : -10 or par minute dès 11:00 (max -40). */
    public int plateGold(GameStructure s) {
        int gold = fr.lolmc.game.RewardManager.GOLD_TURRET_PLATE;
        if (s.getTier() == TurretTier.OUTER && minutes() >= 11)
            gold -= (int) Math.min(40, 10 * (Math.floor(minutes()) - 10));
        return gold;
    }

    /**
     * Décrémente une plaque. L'or est distribué par RewardManager.onPlateDestroyed()
     * pour éviter la duplication.
     */
    public void tickPlating(String key) {
        if (!hasPlating(key)) return;
        int left = platingLeft.getOrDefault(key, MAX_PLATING);
        platingLeft.put(key, Math.max(0, left - 1));
    }

    /** Rétrocompatibilité. */
    public void tickPlating(String key, Player attacker) {
        tickPlating(key);
    }

    public int getPlatingLeft(String key) {
        return platingLeft.getOrDefault(key, MAX_PLATING);
    }

    private org.bukkit.scheduler.BukkitTask turretTask;

    private void startTurretTaskTracked() {
        if (turretTask != null) turretTask.cancel();
        turretTask = new BukkitRunnable() {
            @Override public void run() {
                for (GameStructure turret : mapManager.getStructures()) {
                    // Inhibiteurs (15 PV/s) et Nexus (20 PV/s) régénèrent jusqu'au maximum (wiki)
                    if ((turret.getType() == Type.INHIBITOR || turret.getType() == Type.NEXUS_BASE)
                            && gameStartMs != 0 && !turret.isDestroyed()) {
                        double perSec = turret.getType() == Type.INHIBITOR ? 15 : 20;
                        if (turret.heal(perSec * SECONDS_PER_SHOT, 1.0)) mapManager.updateStructurePhase(turret);
                    }
                    if (turret.getType() != Type.TURRET) continue;
                    if (gameStartMs != 0) regenAndRespawn(turret);
                    if (turret.isDestroyed()) continue;
                    tickOvergrowth(turret);
                    processTurret(turret);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, ATTACK_PERIOD);
    }

    /** Arrête uniquement la tâche de tir des tourelles. */
    public void stopTasks() {
        if (turretTask != null) { turretTask.cancel(); turretTask = null; }
    }
}
