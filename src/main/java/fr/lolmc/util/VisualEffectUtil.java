package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Effets visuels sans particules, remplaçant les org.bukkit.Particle
 * dans tout le plugin.
 * <p>
 * Raison : les particules sont désactivables côté client (options vidéo),
 * ce qui les rend invisibles pour une partie des joueurs — inacceptable
 * pour des effets ayant une fonction de communication de gameplay
 * (impact de sort, indicateur de zone, trajectoire de projectile).
 * <p>
 * Les BlockDisplay/ItemDisplay sont des entités serveur classiques,
 * toujours rendues côté client quel que soit ce réglage.
 */
public final class VisualEffectUtil {

    private VisualEffectUtil() {}

    /**
     * Fait apparaître un effet d'impact bref (bloc qui grossit/disparaît),
     * visible par tous les joueurs proches. Remplace un spawnParticle()
     * ponctuel classique (CRIT, ENCHANT, HEART, etc.).
     *
     * @param world         monde où apparaît l'effet
     * @param loc           position (centrée sur le point d'impact)
     * @param block         matériau du bloc affiché
     * @param size          taille du cube en blocs (ex: 0.3f)
     * @param lifetimeTicks durée avant disparition automatique
     */
    public static void impact(World world, Location loc, Material block, float size, long lifetimeTicks) {
        // Animé (interpolation des Display, sans particules) : naît à 15 % de sa taille, grossit en
        // tournant, puis se rétracte avant de disparaître
        Quaternionf spin = randomRotation();
        var d = world.spawn(loc, BlockDisplay.class, disp -> {
            disp.setBlock(block.createBlockData());
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setTransformation(cube(size * 0.15f, new Vector3f(), new Quaternionf()));
        });
        animateLife(d, lifetimeTicks,
                cube(size, new Vector3f(), spin),
                cube(0f, new Vector3f(), new Quaternionf(spin).rotateY((float) Math.PI / 2)));
    }

    /**
     * Variante avec léger décalage aléatoire de position (pour simuler
     * plusieurs particules dispersées avec un seul bloc, quand la densité
     * exacte importe peu — impacts de zone, soins, etc.).
     */
    public static void impactBurst(World world, Location center, Material block,
                                    float size, double spread, int count, long lifetimeTicks) {
        // Les éclats partent du centre et s'envolent vers l'extérieur (avant : cubes figés)
        for (int i = 0; i < count; i++) {
            Vector3f off = new Vector3f((float) ((Math.random() - 0.5) * 2 * spread),
                    (float) ((Math.random() - 0.5) * spread + spread * 0.3),
                    (float) ((Math.random() - 0.5) * 2 * spread));
            Quaternionf spin = randomRotation();
            var d = world.spawn(center, BlockDisplay.class, disp -> {
                disp.setBlock(block.createBlockData());
                disp.setBrightness(new Display.Brightness(15, 15));
                disp.setPersistent(false);
                disp.setTransformation(cube(size * 0.3f, new Vector3f(), new Quaternionf()));
            });
            animateLife(d, lifetimeTicks,
                    cube(size, off, spin),
                    cube(0f, new Vector3f(off).mul(1.4f), new Quaternionf(spin).rotateX((float) Math.PI)));
        }
    }

    /**
     * Anneau au sol composé de segments de bloc (remplace un cercle de
     * particules). Retourne les entités créées pour un éventuel nettoyage
     * manuel (sinon elles se retirent seules après lifetimeTicks).
     */
    public static void groundRing(World world, Location center, double radius, Material block,
                                   int segments, float segLen, float thickness, long lifetimeTicks) {
        for (int i = 0; i < segments; i++) {
            double angle = (2 * Math.PI * i) / segments;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            float tangentYaw = (float) (angle + Math.PI / 2.0);
            Quaternionf rot = new Quaternionf().rotateY(tangentYaw);
            // Le segment part du centre (20 % du rayon) et s'écarte jusqu'au rayon, puis s'affine
            Vector3f startOff = new Vector3f((float) (x - center.getX()) * 0.2f, 0f, (float) (z - center.getZ()) * 0.2f);
            Vector3f endOff = new Vector3f((float) (x - center.getX()), 0f, (float) (z - center.getZ()));
            var d = world.spawn(center, BlockDisplay.class, disp -> {
                disp.setBlock(block.createBlockData());
                disp.setBrightness(new Display.Brightness(15, 15));
                disp.setPersistent(false);
                disp.setTransformation(segment(startOff, rot, segLen * 0.3f, thickness));
            });
            animateLife(d, lifetimeTicks, segment(endOff, rot, segLen, thickness),
                    segment(new Vector3f(endOff).mul(1.08f), rot, segLen, 0f));
        }
    }

    /**
     * Crée un projectile mobile (ItemDisplay) qui voyage de [start] à [end]
     * en ligne droite, à raison de [blocksPerTick] par tick, puis appelle
     * [onImpact] à l'arrivée. Remplace une traînée de particules animée
     * par BukkitRunnable.
     */
    public static void travelingProjectile(World world, Location start, Location end,
                                            Material block, float size, double blocksPerTick,
                                            Runnable onImpact) {
        double totalDist = start.distance(end);
        int totalSteps = Math.max(2, (int) (totalDist / blocksPerTick));
        Vector step = end.toVector().subtract(start.toVector())
                .normalize().multiply(totalDist / totalSteps);

        var proj = world.spawn(start, ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(block));
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(1);
            disp.setInterpolationDelay(0);
            disp.setTransformation(centeredCube(size));
        });

        new BukkitRunnable() {
            int step_ = 0;
            final Location current = start.clone();
            @Override public void run() {
                if (step_ >= totalSteps) {
                    proj.remove();
                    if (onImpact != null) onImpact.run();
                    cancel();
                    return;
                }
                current.add(step);
                proj.teleport(current);
                step_++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);
    }

    /**
     * Crée un BlockDisplay visible UNIQUEMENT par [viewer] (pattern
     * standard Paper : setVisibleByDefault(false) + showEntity()).
     * Utile pour les indicateurs privés (lock de cible, preview de sort).
     */
    public static BlockDisplay privateMarker(Player viewer, Location loc, Material block, float size) {
        var d = loc.getWorld().spawn(loc, BlockDisplay.class, disp -> {
            disp.setBlock(block.createBlockData());
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(2);
            disp.setInterpolationDelay(0);
            disp.setTransformation(centeredCube(size));
            disp.setVisibleByDefault(false);
        });
        viewer.showEntity(LolPlugin.getInstance(), d);
        return d;
    }

    /**
     * Trace un skillshot en un seul BlockDisplay fin et étiré, orienté selon
     * [dir], sur une longueur de [length] blocs. Remplace une trainée de
     * particules/displays répétés le long de la trajectoire — un flash bref
     * de la ligne entière suffit visuellement pour un coût bien moindre
     * (1 entité au lieu de 15-20 pour un skillshot de portée classique).
     */
    public static void skillshotLine(Location origin, Vector dir, double length,
                                      Material block, float thickness, long lifetimeTicks) {
        float yaw = (float) Math.toRadians(-Math.toDegrees(Math.atan2(dir.getX(), dir.getZ())));
        Quaternionf rot = new Quaternionf().rotateY(yaw);
        Vector3f translation = new Vector3f(-thickness / 2f, -thickness / 2f, 0f);
        var d = origin.getWorld().spawn(origin, BlockDisplay.class, disp -> {
            disp.setBlock(block.createBlockData());
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setTransformation(new Transformation(translation, rot,
                    new Vector3f(thickness, thickness, 0.01f), new Quaternionf()));
        });
        // Le trait s'étire de l'origine jusqu'au bout, puis s'affine avant de disparaître
        animateLife(d, lifetimeTicks,
                new Transformation(translation, rot, new Vector3f(thickness, thickness, (float) length), new Quaternionf()),
                new Transformation(new Vector3f(), rot, new Vector3f(0f, 0f, (float) length), new Quaternionf()));
    }

    /**
     * Repositionne un anneau de BlockDisplay persistants (déjà créés une
     * fois) autour de [center] à distance [radius]. Contrairement à
     * groundRing (qui crée des segments éphémères à chaque appel), cette
     * méthode réutilise les mêmes entités — adaptée à un affichage mis à
     * jour en boucle (indicateur de portée, par exemple).
     */
    public static void repositionRing(java.util.List<BlockDisplay> ring, Location center, double radius) {
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            ring.get(i).teleport(new Location(center.getWorld(), x, center.getY() + 0.05, z));
        }
    }

    /**
     * Crée un anneau de [segments] BlockDisplay persistants, invisibles
     * pour tout le monde sauf [viewer] (pattern showEntity). À utiliser
     * avec repositionRing() pour le repositionnement périodique.
     */
    public static java.util.List<BlockDisplay> createPrivateRing(Player viewer, Location loc,
                                                                   Material block, int segments, float size) {
        var list = new java.util.ArrayList<BlockDisplay>(segments);
        for (int i = 0; i < segments; i++) {
            list.add(privateMarker(viewer, loc, block, size));
        }
        return list;
    }

    // ── Projectiles de sort (TargetingUtil.launchSkillshot) ─────────────────

    /**
     * Crée l'objet visuel d'un projectile : ItemDisplay orienté dans la direction du tir, dont
     * les déplacements sont lissés par le client (teleportDuration) et qui tourne sur lui-même.
     */
    public static ItemDisplay spawnProjectile(Location start, Vector dir, ItemStack item, float size) {
        Location at = start.clone().setDirection(dir);
        return start.getWorld().spawn(at, ItemDisplay.class, disp -> {
            disp.setItemStack(item);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setTeleportDuration(1);
            disp.setTransformation(itemTf(size * 0.4f, new Quaternionf()));
        });
    }

    /** Avance le projectile (déplacement lissé) et le fait tourner autour de son axe. */
    public static void moveProjectile(ItemDisplay proj, Location pos, Vector dir, float size, int age) {
        if (proj.isDead()) return;
        proj.teleport(pos.clone().setDirection(dir));
        proj.setInterpolationDelay(0);
        proj.setInterpolationDuration(1);
        proj.setTransformation(itemTf(age < 2 ? size * 0.4f + size * 0.3f * age : size,
                new Quaternionf().rotateZ(age * 0.6f)));
    }

    /** Fin du projectile : il se rétracte, avec un petit éclat à l'endroit de l'impact. */
    public static void endProjectile(ItemDisplay proj, Location pos, Material burst) {
        if (proj.isDead()) return;
        proj.setInterpolationDelay(0);
        proj.setInterpolationDuration(3);
        proj.setTransformation(itemTf(0f, new Quaternionf().rotateZ(3f)));
        new BukkitRunnable() { @Override public void run() { if (!proj.isDead()) proj.remove(); } }
                .runTaskLater(LolPlugin.getInstance(), 3L);
        if (burst != null) impactBurst(pos.getWorld(), pos, burst, 0.18f, 0.4, 5, 7L);
    }

    // ── Helpers internes ────────────────────────────────────────────────────

    /**
     * Cycle de vie animé d'un Display : 1 tick après l'apparition, interpolation vers
     * {@code full} ; peu avant la fin, interpolation vers {@code gone} ; puis suppression.
     */
    private static void animateLife(Display d, long lifetimeTicks, Transformation full, Transformation gone) {
        long life = Math.max(3, lifetimeTicks);
        int grow = (int) Math.max(1, Math.min(4, life / 3));
        int fade = (int) Math.max(1, Math.min(4, life / 3));
        new BukkitRunnable() { @Override public void run() {
            if (d.isDead()) return;
            d.setInterpolationDelay(0); d.setInterpolationDuration(grow); d.setTransformation(full);
        }}.runTaskLater(LolPlugin.getInstance(), 1L);
        new BukkitRunnable() { @Override public void run() {
            if (d.isDead()) return;
            d.setInterpolationDelay(0); d.setInterpolationDuration(fade); d.setTransformation(gone);
        }}.runTaskLater(LolPlugin.getInstance(), Math.max(2, life - fade));
        new BukkitRunnable() { @Override public void run() { if (!d.isDead()) d.remove(); } }
                .runTaskLater(LolPlugin.getInstance(), life + 1);
    }

    /** Transformation d'un ItemDisplay (déjà centré sur son origine). */
    private static Transformation itemTf(float size, Quaternionf rot) {
        return new Transformation(new Vector3f(), rot, new Vector3f(size, size, size), new Quaternionf());
    }

    private static Quaternionf randomRotation() {
        return new Quaternionf().rotateXYZ((float) (Math.random() * Math.PI), (float) (Math.random() * Math.PI),
                (float) (Math.random() * Math.PI));
    }

    /** Cube centré sur (offset), tourné de {@code rot}. */
    private static Transformation cube(float size, Vector3f offset, Quaternionf rot) {
        // La rotation s'applique autour du centre du cube : on centre via la translation
        Vector3f half = new Vector3f(-size / 2f, -size / 2f, -size / 2f);
        new Quaternionf(rot).transform(half);
        return new Transformation(new Vector3f(offset).add(half), rot, new Vector3f(size, size, size), new Quaternionf());
    }

    /** Segment d'anneau (longueur {@code len}, épaisseur {@code th}) centré sur {@code offset}. */
    private static Transformation segment(Vector3f offset, Quaternionf rot, float len, float th) {
        Vector3f half = new Vector3f(-len / 2f, 0f, -th / 2f);
        new Quaternionf(rot).transform(half);
        return new Transformation(new Vector3f(offset).add(half), rot, new Vector3f(len, th, th), new Quaternionf());
    }

    private static Transformation centeredCube(float size) {
        return new Transformation(
                new Vector3f(-size / 2f, -size / 2f, -size / 2f),
                new Quaternionf(),
                new Vector3f(size, size, size),
                new Quaternionf());
    }

    private static void despawnAfter(BlockDisplay d, long lifetimeTicks) {
        new BukkitRunnable() {
            @Override public void run() {
                if (!d.isDead()) d.remove();
            }
        }.runTaskLater(LolPlugin.getInstance(), lifetimeTicks);
    }
}
