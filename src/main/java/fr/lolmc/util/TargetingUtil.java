package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.JungleManager;
import fr.lolmc.game.MinionManager;
import fr.lolmc.team.TeamManager.Team;
import org.bukkit.Location;
import org.bukkit.Material;
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
            if (other.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false; // mort
            Team otherTeam = tm.getTeam(other);
            return otherTeam == null || otherTeam != myTeam;
        }
        // Sbire
        if (MinionManager.isMinion(entity)) {
            Team minionTeam = MinionManager.getMinionTeam(entity);
            return minionTeam == null || minionTeam != myTeam;
        }
        // Monstre de jungle = toujours attaquable
        return JungleManager.isJungleMonster(entity);
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
        return skillshotFrom(caster, caster.getEyeLocation(), caster.getEyeLocation().getDirection(), range, width, pierce);
    }

    /** Skillshot lancé depuis une autre position (ex. l'ombre de Zed), dans une direction donnée. */
    public static List<LivingEntity> skillshotFrom(Player caster, Location origin, Vector direction,
                                                   double range, double width, boolean pierce) {
        List<LivingEntity> hit = new ArrayList<>();
        Location start = origin.clone();
        Vector dir = direction.clone().normalize();

        // Liste des ennemis candidats dans la portée
        List<LivingEntity> candidates = new ArrayList<>();
        for (var e : caster.getWorld().getNearbyLivingEntities(start, range + 2)) {
            if (isEnemy(caster, e)) candidates.add(e);
        }

        // Avancer le long de la ligne par pas de 0.5 bloc (détection pure,
        // sans rendu à chaque pas — un seul BlockDisplay étiré est posé
        // après coup sur toute la distance effective, cf. plus bas).
        double step = 0.5;
        double stopDist = range;
        Location prev = start.clone();
        for (double d = 0; d <= range; d += step) {
            Location point = start.clone().add(dir.clone().multiply(d));
            // Mur ? on arrête
            if (point.getBlock().getType().isSolid()) { stopDist = d; break; }
            // Mur du Vent ennemi (Yasuo) : le projectile est détruit
            if (fr.lolmc.champion.impl.mid.Yasuo.blocksProjectile(caster, prev, point)) { stopDist = d; break; }
            prev = point;
            // Ennemi touché ?
            for (var e : candidates) {
                if (hit.contains(e)) continue;
                if (e.getLocation().add(0, 1, 0).distance(point) <= width + 0.5) {
                    hit.add(e);
                    if (!pierce) { stopDist = d; break; }
                }
            }
            if (!pierce && !hit.isEmpty()) break;
        }

        // Un seul BlockDisplay fin et étiré sur toute la trajectoire effective
        // (remplace l'ancienne trainée de 15-20 particules/entités par tir).
        if (stopDist > 0.1) {
            VisualEffectUtil.skillshotLine(start, dir, stopDist,
                    Material.WHITE_STAINED_GLASS, (float) Math.min(width, 1.0), 4L);
        }
        return hit;
    }

    /**
     * Skillshot en projectile RÉEL (LoL) : il avance à {@code speed} blocs/s, peut être esquivé,
     * s'arrête sur un mur ou un Mur du Vent ennemi, et appelle {@code onHit} au contact de chaque
     * ennemi (le premier seulement si {@code pierce} est faux), puis {@code onEnd} avec la liste
     * des ennemis touchés. Avant, tous les skillshots touchaient instantanément.
     *
     * @param origin    point de départ (ex. yeux du lanceur, ombre de Zed)
     * @param visual    objet affiché (ItemDisplay) ; null = aucun
     */
    public static void launchSkillshot(Player caster, Location origin, Vector direction, double range,
                                       double width, boolean pierce, double speed,
                                       org.bukkit.inventory.ItemStack visual, float size, Material burst,
                                       java.util.function.Consumer<LivingEntity> onHit,
                                       java.util.function.Consumer<List<LivingEntity>> onEnd) {
        launchSkillshot(caster, origin, direction, range, width, pierce, speed, visual, size, burst, null, onHit,
                (hits, at) -> { if (onEnd != null) onEnd.accept(hits); });
    }

    /**
     * Variante : {@code canHit} restreint les cibles percutées (ex. champions seulement pour les
     * ultimes d'Ashe et de Jinx, qui traversent les sbires) et {@code onEnd} reçoit aussi le point
     * où le projectile s'est arrêté (retour de la Lame Boomerang de Sivir).
     */
    public static void launchSkillshot(Player caster, Location origin, Vector direction, double range,
                                       double width, boolean pierce, double speed,
                                       org.bukkit.inventory.ItemStack visual, float size, Material burst,
                                       java.util.function.Predicate<LivingEntity> canHit,
                                       java.util.function.Consumer<LivingEntity> onHit,
                                       java.util.function.BiConsumer<List<LivingEntity>, Location> onEnd) {
        Location start = origin.clone();
        Vector dir = direction.clone().normalize();
        List<LivingEntity> hit = new ArrayList<>();
        var proj = visual != null ? VisualEffectUtil.spawnProjectile(start, dir, visual, size) : null;
        new org.bukkit.scheduler.BukkitRunnable() {
            double travelled = 0;
            int age = 0;
            Location pos = start.clone();

            @Override public void run() {
                if (!caster.isOnline()) { finish(false); return; }
                double perTick = speed / 20.0, moved = 0;
                while (moved < perTick && travelled < range) {
                    double st = Math.min(0.5, Math.min(perTick - moved, range - travelled));
                    Location next = pos.clone().add(dir.clone().multiply(st));
                    if (next.getBlock().getType().isSolid()
                            || fr.lolmc.champion.impl.mid.Yasuo.blocksProjectile(caster, pos, next)) {
                        finish(true); return; // s'arrête devant l'obstacle
                    }
                    pos = next; moved += st; travelled += st;
                    for (var e : pos.getWorld().getNearbyLivingEntities(pos, width + 1.5)) {
                        if (hit.contains(e) || !isEnemy(caster, e) || (canHit != null && !canHit.test(e))) continue;
                        if (e.getLocation().add(0, 1, 0).distance(pos) > width + 0.5) continue;
                        hit.add(e);
                        if (onHit != null) onHit.accept(e);
                        if (!pierce) { finish(true); return; }
                    }
                }
                if (proj != null) VisualEffectUtil.moveProjectile(proj, pos, dir, size, age++);
                if (travelled >= range) finish(false);
            }

            private void finish(boolean impact) {
                cancel();
                if (proj != null) VisualEffectUtil.endProjectile(proj, pos, impact ? burst : null);
                if (onEnd != null) onEnd.accept(hit, pos.clone());
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);
    }

    /**
     * Attire une cible jusqu'à {@code stopDist} blocs de {@code puller} en la faisant glisser à
     * {@code speed} blocs/s (grappin de Blitzcrank, Appréhension de Darius) ; elle monte les marches
     * et s'arrête contre un mur. Avant, une simple poussée la laissait souvent à mi-chemin.
     */
    public static void pullTo(LivingEntity target, Player puller, double stopDist, double speed) {
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (target.isDead() || !target.isValid() || !puller.isOnline()
                        || !target.getWorld().equals(puller.getWorld()) || t++ > 30) { cancel(); return; }
                Location from = target.getLocation();
                Vector d = puller.getLocation().toVector().subtract(from.toVector()).setY(0);
                double dist = d.length();
                if (dist <= stopDist) { cancel(); return; }
                Location next = from.clone().add(d.normalize().multiply(Math.min(speed / 20.0, dist - stopDist)));
                if (blocked(next)) {
                    next.add(0, 1, 0);
                    if (blocked(next)) { cancel(); return; }
                }
                target.teleport(next);
            }
            private boolean blocked(Location l) {
                return l.getBlock().getType().isSolid() || l.clone().add(0, 1, 0).getBlock().getType().isSolid();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);
    }

    /**
     * Projectile à tête chercheuse (LoL : attaques à distance, sorts ciblés, tirs de tourelle) :
     * il suit sa cible à {@code speed} blocs/s et applique {@code onArrive} à l'impact. Il se
     * perd si la cible meurt ou disparaît, et, si {@code blockable}, s'arrête sur un Mur du Vent
     * ennemi. {@code caster} peut être null (tourelle).
     */
    public static void launchHoming(Player caster, Location origin, LivingEntity target, double speed,
                                    org.bukkit.inventory.ItemStack visual, float size, Material burst,
                                    boolean blockable, Runnable onArrive) {
        Location start = origin.clone();
        var proj = visual != null ? VisualEffectUtil.spawnProjectile(start,
                target.getLocation().add(0, 1, 0).toVector().subtract(start.toVector()), visual, size) : null;
        new org.bukkit.scheduler.BukkitRunnable() {
            Location pos = start.clone();
            int age = 0;
            @Override public void run() {
                if (target.isDead() || !target.isValid() || !target.getWorld().equals(pos.getWorld())
                        || (caster != null && !caster.isOnline()) || age > 200) {
                    end(false); return;
                }
                Location aim = target.getLocation().add(0, 1, 0);
                Vector to = aim.toVector().subtract(pos.toVector());
                double dist = to.length(), step = speed / 20.0;
                Location next = dist <= step ? aim : pos.clone().add(to.clone().normalize().multiply(step));
                if (blockable && caster != null
                        && fr.lolmc.champion.impl.mid.Yasuo.blocksProjectile(caster, pos, next)) {
                    pos = next; end(false); return;
                }
                pos = next;
                if (proj != null) VisualEffectUtil.moveProjectile(proj, pos, to.lengthSquared() > 1e-6 ? to : new Vector(0, 0, 1), size, age);
                age++;
                if (dist <= step) {
                    if (onArrive != null) onArrive.run();
                    end(true);
                }
            }
            private void end(boolean impact) {
                cancel();
                if (proj != null) VisualEffectUtil.endProjectile(proj, pos, impact ? burst : null);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);
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
            // Sbire ou monstre : armure/RM, bonus anti-sbires et PV virtuels
            DamageUtil.Type dt = switch (type) {
                case PHYSICAL -> DamageUtil.Type.PHYSICAL;
                case MAGICAL -> DamageUtil.Type.MAGICAL;
                case TRUE -> DamageUtil.Type.TRUE;
            };
            if (MobCombat.damage(caster, target, amount, dt)) return;
            VisualEffectUtil.impact(target.getWorld(),
                    target.getLocation().add(0, 1, 0), Material.WHITE_STAINED_GLASS, 0.28f, 4L);
        }
    }

    /**
     * HP réels à l'échelle LoL :
     *  - Joueur : HPSystem (la vie vanilla n'est qu'un affichage proportionnel)
     *  - Entité à HP virtuels (Baron, Elder, canon) : PDC
     *  - Autre entité : vie vanilla (déjà à l'échelle LoL, ex. sbire 477)
     */
    public static double getRealHealth(LivingEntity target) {
        if (target instanceof Player p) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm != null && cm.hasChampion(p))
                return cm.getChampion(p).getHPSystem().getCurrentHP();
            return p.getHealth();
        }
        return VirtualHP.has(target) ? VirtualHP.getCurrent(target) : target.getHealth();
    }

    /** HP max réels à l'échelle LoL (pendant de getRealHealth). */
    public static double getRealMaxHealth(LivingEntity target) {
        if (target instanceof Player p) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm != null && cm.hasChampion(p))
                return cm.getChampion(p).getHPSystem().getMaxHP();
            var attr = p.getAttribute(Compat.maxHealth());
            return attr != null ? attr.getValue() : 20.0;
        }
        if (VirtualHP.has(target)) return VirtualHP.getMax(target);
        return Compat.maxHealthOf(target);
    }

    /** Applique des dégâts à toute une liste d'entités. */
    public static void dealDamageAll(Player caster, List<LivingEntity> targets, double amount, DmgType type) {
        for (LivingEntity t : targets) dealDamage(caster, t, amount, type);
    }
}
