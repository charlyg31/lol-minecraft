package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.game.GameStructure.Type;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère le comportement des tourelles :
 *  - Zone d'attaque (rayon = portée tourelle, > AA d'ADC de 2 blocs)
 *  - Priorité : tape les sbires, SAUF si un ennemi attaque un allié champion dans la zone
 *  - Dégâts basés sur le jeu de base (montants progressifs)
 */
public class TurretManager {

    private final MapManager mapManager;
    private final ChampionManager championManager;
    private final TeamManager teamManager;

    // Cadence de tir : 1 tir par seconde (20 ticks)
    private static final long ATTACK_PERIOD = 20L;

    // Valeurs lues depuis la config (mêmes pour toutes les tours)
    private final double attackRadius;     // rayon horizontal du cylindre (blocs)
    private final double detectionHeight;  // hauteur de la zone de détection (blocs)
    private final double beamHeight;       // hauteur d'où part l'animation du tir (blocs)
    private final double baseDamage;       // dégâts de base d'un tir
    // Turret Plating : 5 plaques par tourelle, actives jusqu'à 14min
    private final java.util.Map<String, Integer> platingLeft = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_PLATING = 5;
    private static final long PLATING_END_MS = 14 * 60 * 1000L;
    private long gameStartMs = 0;
    public void setGameStart() { gameStartMs = System.currentTimeMillis(); }

    // Aggro tourelle : cible actuelle de chaque tourelle (UUID joueur visé)
    private final Map<String, UUID> turretAggro = new HashMap<>();
    // Compteur de stacks de dégâts (la tourelle tape de plus en plus fort sur la même cible)
    private final Map<String, Integer> turretStacks = new HashMap<>();

    public TurretManager(MapManager mapManager, ChampionManager championManager, TeamManager teamManager) {
        this.mapManager = mapManager;
        this.championManager = championManager;
        this.teamManager = teamManager;
        var config = LolPlugin.getInstance().getConfig();
        this.attackRadius = config.getDouble("turrets.attack-radius", 8.0);
        this.detectionHeight = config.getDouble("turrets.detection-height", 6.0);
        this.beamHeight = config.getDouble("turrets.beam-height", 6.0);
        this.baseDamage = config.getDouble("turrets.base-damage", 150.0);
        startTurretTaskTracked();
    }

    private void processTurret(GameStructure turret) {
        // Centre de détection : point de pose + hauteur de détection (pour le cylindre)
        Location center = turret.getCenter().clone().add(0.5, detectionHeight, 0.5);
        // Origine de l'animation : point de pose + hauteur du faisceau
        Location beamOrigin = turret.getCenter().clone().add(0.5, beamHeight, 0.5);
        Team turretTeam = turret.getTeam();
        String turretId = turret.getId();

        // 1. Chercher une cible prioritaire : ennemi qui attaque un allié champion dans la zone
        Player priorityTarget = findPriorityTarget(center, turretTeam);

        if (priorityTarget != null) {
            // Aggro sur le joueur ennemi
            fireAtPlayer(turret, beamOrigin, priorityTarget);
            return;
        }

        // 2. Sinon, taper les sbires ennemis dans la zone (priorité par défaut)
        LivingEntity minion = findEnemyMinion(center, turretTeam);
        if (minion != null) {
            fireAtMinion(turret, beamOrigin, minion);
            turretAggro.remove(turretId);
            return;
        }

        // 3. Sinon, taper un champion ennemi qui traîne dans la zone
        Player champTarget = findEnemyChampion(center, turretTeam);
        if (champTarget != null) {
            fireAtPlayer(turret, beamOrigin, champTarget);
        } else {
            turretAggro.remove(turretId);
            turretStacks.remove(turretId);
        }
    }


    // ══════════════════════════════════════════════════════════════
    // DÉTECTION CYLINDRIQUE (rayon horizontal + hauteur)
    // ══════════════════════════════════════════════════════════════

    /**
     * Vérifie qu'une position est dans le cylindre d'attaque autour du centre.
     * Le centre est au niveau du canon (point de pose + hauteur).
     * Cylindre : distance horizontale <= rayon ET écart vertical <= hauteur.
     */
    private boolean inCylinder(Location turretCanon, Location target) {
        if (!turretCanon.getWorld().equals(target.getWorld())) return false;
        double dx = target.getX() - turretCanon.getX();
        double dz = target.getZ() - turretCanon.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist > attackRadius) return false;
        // Écart vertical : la zone s'étend de (centre - hauteur) à (centre + hauteur)
        double dy = Math.abs(target.getY() - turretCanon.getY());
        return dy <= detectionHeight;
    }

    /** Boîte de pré-filtrage pour getNearbyEntities (on affine ensuite avec inCylinder). */
    private double boxRange() {
        return Math.max(attackRadius, detectionHeight) + 1;
    }

    // ── Recherche de cibles ───────────────────────────────────────

    /**
     * Cherche un ennemi qui attaque un allié champion dans la zone de la tourelle.
     * (Détecté via le système de combat : un joueur ayant infligé des dégâts récemment.)
     */
    private Player findPriorityTarget(Location center, Team turretTeam) {
        for (var entity : center.getWorld().getNearbyEntities(center, boxRange(), boxRange(), boxRange())) {
            if (!(entity instanceof Player p)) continue;
            if (!championManager.hasChampion(p)) continue;
            if (!inCylinder(center, p.getLocation())) continue;
            Team pTeam = teamManager.getTeam(p);
            if (pTeam == null || pTeam == turretTeam) continue; // pas un ennemi

            BaseChampion champ = championManager.getChampion(p);
            if (champ.getHPSystem().isInCombat() && hasAlliedChampionNearby(center, turretTeam)) {
                return p;
            }
        }
        return null;
    }

    private boolean hasAlliedChampionNearby(Location center, Team turretTeam) {
        for (var entity : center.getWorld().getNearbyEntities(center, boxRange(), boxRange(), boxRange())) {
            if (entity instanceof Player p && championManager.hasChampion(p)) {
                if (!inCylinder(center, p.getLocation())) continue;
                if (teamManager.getTeam(p) == turretTeam) return true;
            }
        }
        return false;
    }

    private LivingEntity findEnemyMinion(Location center, Team turretTeam) {
        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (var entity : center.getWorld().getNearbyEntities(center, boxRange(), boxRange(), boxRange())) {
            if (entity instanceof LivingEntity le && fr.lolmc.game.MinionManager.isMinion(le)) {
                if (!inCylinder(center, le.getLocation())) continue;
                Team minionTeam = fr.lolmc.game.MinionManager.getMinionTeam(le);
                if (minionTeam != null && minionTeam != turretTeam) {
                    double d = le.getLocation().distance(center);
                    if (d < closestDist) { closestDist = d; closest = le; }
                }
            }
        }
        return closest;
    }

    private Player findEnemyChampion(Location center, Team turretTeam) {
        Player closest = null;
        double closestDist = Double.MAX_VALUE;
        for (var entity : center.getWorld().getNearbyEntities(center, boxRange(), boxRange(), boxRange())) {
            if (entity instanceof Player p && championManager.hasChampion(p)) {
                if (!inCylinder(center, p.getLocation())) continue;
                Team pTeam = teamManager.getTeam(p);
                if (pTeam != null && pTeam != turretTeam) {
                    double d = p.getLocation().distance(center);
                    if (d < closestDist) { closestDist = d; closest = p; }
                }
            }
        }
        return closest;
    }

    // ── Tir ───────────────────────────────────────────────────────

    private void fireAtPlayer(GameStructure turret, Location beamFrom, Player target) {
        String turretId = turret.getId();
        // Stacks : +40% dégâts par tir consécutif sur un champion (comme LoL)
        UUID lastTarget = turretAggro.get(turretId);
        int stacks = lastTarget != null && lastTarget.equals(target.getUniqueId())
                ? turretStacks.getOrDefault(turretId, 0) + 1 : 0;
        turretAggro.put(turretId, target.getUniqueId());
        turretStacks.put(turretId, Math.min(stacks, 3));

        double damage = baseDamage * (1.0 + stacks * 0.40);

        // Projectile visuel
        shootBeam(beamFrom, target.getLocation().add(0, 1, 0));

        // Dégâts de tourelle : physiques, réduits par l'armure du champion
        if (championManager.hasChampion(target)) {
            BaseChampion champ = championManager.getChampion(target);
            // Réduction d'armure : dégâts × 100/(100+armure) comme LoL
            double armor = champ.getStats().getFinalArmor();
            double reduced = damage * 100.0 / (100.0 + armor);
            champ.getHPSystem().takeDamage(reduced);
            var hud = LolPlugin.getInstance().getHUDManager();
            if (hud != null) hud.updateHUD(target, champ);
            target.sendActionBar(Component.text(
                    String.format("🗼 Tourelle! -%.0f (armure: %.0f)", reduced, armor),
                    NamedTextColor.RED));
        }
    }

    private void fireAtMinion(GameStructure turret, Location beamFrom, LivingEntity minion) {
        shootBeam(beamFrom, minion.getLocation().add(0, 0.5, 0));
        // Les sbires meurent en ~1-2 coups de tourelle
        minion.damage(baseDamage);
    }

    private void shootBeam(Location from, Location to) {
        var dir = to.toVector().subtract(from.toVector()).normalize();
        double dist = from.distance(to);
        for (double d = 0; d < dist; d += 0.5) {
            Location point = from.clone().add(dir.clone().multiply(d));
            from.getWorld().spawnParticle(Particle.CRIT, point, 2, 0.05, 0.05, 0.05, 0);
        }
        from.getWorld().playSound(from, Sound.ENTITY_ARROW_SHOOT, 0.6f, 0.8f);
        to.getWorld().spawnParticle(Particle.EXPLOSION, to, 1);
    }

    public double getTurretRange() { return attackRadius; }

    public boolean hasPlating(String key) {
        if (gameStartMs == 0) return false;
        if (System.currentTimeMillis() - gameStartMs > PLATING_END_MS) return false;
        return platingLeft.getOrDefault(key, MAX_PLATING) > 0;
    }

    /**
     * Décrémente une plaque. L'or est distribué par RewardManager.onTurretHit()
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
                    if (turret.getType() != Type.TURRET || turret.isDestroyed()) continue;
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
