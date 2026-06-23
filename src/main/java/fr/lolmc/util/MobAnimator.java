package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anime les modèles de monstres (faits de BlockDisplay) sans resource pack,
 * en mettant à jour périodiquement la transformation de chaque partie avec
 * interpolation (le client lisse le mouvement).
 *
 * Animations VERTICALES uniquement (montée/descente, pulse de taille) : elles
 * sont fiables quelle que soit l'orientation du mob (contrairement à un dash
 * directionnel, dont le rendu dépend de l'orientation incertaine des passagers).
 *  - repos      : légère respiration (bob lent de faible amplitude)
 *  - déplacement : sautillement plus marqué et plus rapide
 *  - attaque     : bref sursaut + grossissement (déclenché par MonsterPassiveListener)
 *
 * Perf : un seul task partagé, intervalle modéré + interpolation, et on
 * n'anime QUE les monstres proches d'un joueur (les camps lointains sont au repos).
 */
public final class MobAnimator {

    private MobAnimator() {}

    /** Toutes les N ticks (avec interpolation de même durée → mouvement lissé). */
    private static final int INTERVAL = 4;
    /** Distance max à un joueur pour animer (sinon inutile). */
    private static final double NEAR = 32.0;

    private static final Map<UUID, Anim> ANIMS = new ConcurrentHashMap<>();
    private static int taskId = -1;
    private static int tick = 0;

    private static final class Anim {
        final LivingEntity mob;
        final List<BlockDisplay> parts;
        final List<Transformation> base;
        Location lastLoc;
        long attackUntil = 0L;
        Anim(LivingEntity mob, List<BlockDisplay> parts, List<Transformation> base) {
            this.mob = mob; this.parts = parts; this.base = base;
        }
    }

    public static void register(LivingEntity mob, List<BlockDisplay> parts, List<Transformation> base) {
        ANIMS.put(mob.getUniqueId(), new Anim(mob, parts, base));
        ensureTask();
    }

    public static void unregister(UUID mobId) {
        ANIMS.remove(mobId);
    }

    public static void clearAll() {
        ANIMS.clear();
    }

    /** Déclenche une animation d'attaque (bref sursaut) sur un monstre. */
    public static void triggerAttack(UUID mobId) {
        Anim a = ANIMS.get(mobId);
        if (a != null) a.attackUntil = System.currentTimeMillis() + 350L;
    }

    private static void ensureTask() {
        if (taskId != -1) return;
        taskId = Bukkit.getScheduler().runTaskTimer(
                LolPlugin.getInstance(), MobAnimator::tickAll, 0L, INTERVAL).getTaskId();
    }

    private static void tickAll() {
        tick++;
        if (ANIMS.isEmpty()) return;
        var it = ANIMS.entrySet().iterator();
        while (it.hasNext()) {
            Anim a = it.next().getValue();
            if (a.mob == null || a.mob.isDead() || !a.mob.isValid()) { it.remove(); continue; }
            if (!playerNear(a.mob)) continue; // perf : seulement proche d'un joueur
            animate(a);
        }
    }

    private static boolean playerNear(LivingEntity mob) {
        double r2 = NEAR * NEAR;
        Location ml = mob.getLocation();
        for (Player p : mob.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(ml) <= r2) return true;
        }
        return false;
    }

    private static void animate(Anim a) {
        a.mob.setRotation(a.mob.getLocation().getYaw(), a.mob.getLocation().getPitch());
        Location loc = a.mob.getLocation();
        boolean moving = a.lastLoc != null
                && a.lastLoc.getWorld().equals(loc.getWorld())
                && a.lastLoc.distanceSquared(loc) > 0.0009; // ~0.03 bloc
        a.lastLoc = loc.clone();
        boolean attacking = System.currentTimeMillis() < a.attackUntil;

        double t = tick * (INTERVAL / 20.0);
        double amp = moving ? 0.12 : 0.05;
        double speed = moving ? 10.0 : 4.0;
        double bobY = Math.sin(t * speed) * amp;
        float scalePulse = 1.0f;
        if (attacking) {
            bobY += 0.18;       // sursaut
            scalePulse = 1.15f; // grossissement bref
        }

        for (int i = 0; i < a.parts.size(); i++) {
            BlockDisplay d = a.parts.get(i);
            if (d == null || d.isDead() || !d.isValid()) continue;
            Transformation b = a.base.get(i);
            Vector3f tr = new Vector3f(b.getTranslation()).add(0f, (float) bobY, 0f);
            Vector3f sc = new Vector3f(b.getScale()).mul(scalePulse);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(INTERVAL);
            d.setTransformation(new Transformation(tr, b.getLeftRotation(), sc, b.getRightRotation()));
        }
    }
}
