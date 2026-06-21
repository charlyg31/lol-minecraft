package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.JungleManager;
import fr.lolmc.game.MinionManager;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.team.TeamManager.Team;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Moteur de ciblage façon League of Legends.
 *
 * Fournit les différents modes de visée :
 *  - cible unique (raycast sur l'ennemi visé)
 *  - skillshot (ligne droite jusqu'à toucher un ennemi ou un mur)
 *  - zone au sol (toutes les entités dans un rayon autour d'un point)
 *  - zone autour du lanceur (cône / cercle)
 *
 * Toutes les méthodes touchent TOUTES les entités ennemies (joueurs, sbires,
 * monstres de jungle), pas seulement les joueurs.
 */
public final class TargetingUtil {

    private TargetingUtil() {}

    // ══════════════════════════════════════════════════════════════
    // IDENTIFICATION DES ENNEMIS
    // ══════════════════════════════════════════════════════════════

    /** Une entité est-elle un ennemi attaquable du lanceur ? */
    public static boolean isEnemy(Player caster, LivingEntity entity) {
        if (entity == null || entity.equals(caster) || entity.isDead()) return false;
        var tm = LolPlugin.getInstance().getTeamManager();
        Team myTeam = tm.getTeam(caster);

        // Autre joueur
        if (entity instanceof Player other) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (!cm.hasChampion(other)) return false;
            Team otherTeam = tm.getTeam(other);
            return otherTeam == null || otherTeam != myTeam;
        }
        // Sbire
        if (MinionManager.isMinion(entity)) {
            Team minionTeam = MinionManager.getMinionTeam(entity);
            return minionTeam == null || minionTeam != myTeam;
        }
        // Monstre de jungle = toujours attaquable
        if (JungleManager.isJungleMonster(entity)) return true;

        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // MODE 1 : CIBLE UNIQUE (raycast sur l'ennemi visé)
    // ══════════════════════════════════════════════════════════════

    /**
     * Trouve l'ennemi visé par le lanceur (dans la direction du regard).
     * @param maxRange portée maximale en blocs
     * @return l'ennemi le plus proche dans le viseur, ou null
     */
    public static LivingEntity getTargetedEnemy(Player caster, double maxRange) {
        Location eye = caster.getEyeLocation();
        Vector dir = eye.getDirection();
        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (var entity : caster.getWorld().getNearbyLivingEntities(eye, maxRange)) {
            if (!isEnemy(caster, entity)) continue;
            Vector toTarget = entity.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            double dist = toTarget.length();
            if (dist > maxRange) continue;
            double dot = toTarget.normalize().dot(dir);
            // ~20° de tolérance, plus permissif pour viser facilement
            if (dot > 0.94 && dist < closestDist) {
                closest = entity;
                closestDist = dist;
            }
        }
        return closest;
    }

    /** Ennemi le plus proche du lanceur (sans visée, pour les sorts auto). */
    public static LivingEntity getNearestEnemy(Player caster, double maxRange) {
        Location loc = caster.getLocation();
        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (var entity : caster.getWorld().getNearbyLivingEntities(loc, maxRange)) {
            if (!isEnemy(caster, entity)) continue;
            double dist = entity.getLocation().distance(loc);
            if (dist < closestDist) { closest = entity; closestDist = dist; }
        }
        return closest;
    }

    // ══════════════════════════════════════════════════════════════
    // MODE 2 : SKILLSHOT (ligne droite jusqu'à un ennemi ou un mur)
    // ══════════════════════════════════════════════════════════════

    /**
     * Lance un skillshot en ligne droite depuis le lanceur.
     * Touche le PREMIER ennemi rencontré (ou tous, selon pierce).
     * @param range portée max en blocs
     * @param width demi-largeur du projectile (tolérance latérale)
     * @param pierce si true, traverse et touche tous les ennemis sur la ligne
     * @return liste des ennemis touchés
     */
    public static List<LivingEntity> skillshot(Player caster, double range, double width, boolean pierce) {
        List<LivingEntity> hit = new ArrayList<>();
        Location start = caster.getEyeLocation();
        Vector dir = start.getDirection().normalize();

        // Liste des ennemis candidats dans la portée
        List<LivingEntity> candidates = new ArrayList<>();
        for (var e : caster.getWorld().getNearbyLivingEntities(start, range + 2)) {
            if (isEnemy(caster, e)) candidates.add(e);
        }

        // Avancer le long de la ligne par pas de 0.5 bloc
        double step = 0.5;
        for (double d = 0; d <= range; d += step) {
            Location point = start.clone().add(dir.clone().multiply(d));
            // Mur ? on arrête
            if (point.getBlock().getType().isSolid()) break;
            // Particule de trajectoire
            point.getWorld().spawnParticle(org.bukkit.Particle.CRIT, point, 1, 0, 0, 0, 0);
            // Ennemi touché ?
            for (var e : candidates) {
                if (hit.contains(e)) continue;
                if (e.getLocation().add(0, 1, 0).distance(point) <= width + 0.5) {
                    hit.add(e);
                    if (!pierce) return hit; // s'arrête au premier
                }
            }
        }
        return hit;
    }

    // ══════════════════════════════════════════════════════════════
    // MODE 3 : ZONE AU SOL (autour d'un point visé)
    // ══════════════════════════════════════════════════════════════

    /** Point au sol visé par le lanceur (jusqu'à maxRange), pour les zones. */
    public static Location getAimedGroundLocation(Player caster, double maxRange) {
        Location eye = caster.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double step = 0.5;
        for (double d = 0; d <= maxRange; d += step) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            if (point.getBlock().getType().isSolid()) {
                return point;
            }
        }
        // Aucun bloc touché : point à maxRange devant
        return eye.clone().add(dir.clone().multiply(maxRange));
    }

    /** Toutes les entités ennemies dans un rayon autour d'un point. */
    public static List<LivingEntity> entitiesInRadius(Player caster, Location center, double radius) {
        List<LivingEntity> hit = new ArrayList<>();
        for (var e : center.getWorld().getNearbyLivingEntities(center, radius)) {
            if (isEnemy(caster, e)) hit.add(e);
        }
        return hit;
    }

    // ══════════════════════════════════════════════════════════════
    // MODE 4 : AUTOUR DU LANCEUR (cercle / cône)
    // ══════════════════════════════════════════════════════════════

    /** Ennemis dans un cercle autour du lanceur. */
    public static List<LivingEntity> enemiesAround(Player caster, double radius) {
        return entitiesInRadius(caster, caster.getLocation(), radius);
    }

    /** Ennemis dans un cône devant le lanceur (angle en degrés). */
    public static List<LivingEntity> enemiesInCone(Player caster, double range, double angleDeg) {
        List<LivingEntity> hit = new ArrayList<>();
        Vector dir = caster.getEyeLocation().getDirection().normalize();
        double cosLimit = Math.cos(Math.toRadians(angleDeg / 2));
        for (var e : caster.getWorld().getNearbyLivingEntities(caster.getLocation(), range)) {
            if (!isEnemy(caster, e)) continue;
            Vector toTarget = e.getLocation().toVector().subtract(caster.getLocation().toVector()).normalize();
            if (toTarget.dot(dir) >= cosLimit) hit.add(e);
        }
        return hit;
    }

    // ══════════════════════════════════════════════════════════════
    // APPLICATION DES DÉGÂTS (universelle : joueur, sbire, monstre)
    // ══════════════════════════════════════════════════════════════

    public enum DmgType { PHYSICAL, MAGICAL, TRUE }

    /**
     * Applique des dégâts à n'importe quelle entité vivante.
     * Pour les joueurs : passe par DamageUtil (résistances, boucliers, runes).
     * Pour les sbires/monstres : dégâts directs sur les PV.
     */
    public static void dealDamage(Player caster, LivingEntity target, double amount, DmgType type) {
        if (target == null || target.isDead()) return;

        if (target instanceof Player victim) {
            DamageUtil.Type dt = switch (type) {
                case PHYSICAL -> DamageUtil.Type.PHYSICAL;
                case MAGICAL -> DamageUtil.Type.MAGICAL;
                case TRUE -> DamageUtil.Type.TRUE;
            };
            DamageUtil.damage(caster, victim, amount, true, dt);
        } else {
            // Sbire ou monstre : dégât direct (pas de système de résistance LoL)
            double newHealth = Math.max(0, target.getHealth() - amount);
            target.setHealth(newHealth);
            target.getWorld().spawnParticle(org.bukkit.Particle.CRIT,
                    target.getLocation().add(0, 1, 0), 5, 0.3, 0.3, 0.3);
        }
    }

    /** Applique des dégâts à toute une liste d'entités. */
    public static void dealDamageAll(Player caster, List<LivingEntity> targets, double amount, DmgType type) {
        for (LivingEntity t : targets) dealDamage(caster, t, amount, type);
    }
}
