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
        var d = world.spawn(loc, BlockDisplay.class, disp -> {
            disp.setBlock(block.createBlockData());
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(2);
            disp.setInterpolationDelay(0);
            disp.setTransformation(centeredCube(size));
        });
        despawnAfter(d, lifetimeTicks);
    }

    /**
     * Variante avec léger décalage aléatoire de position (pour simuler
     * plusieurs particules dispersées avec un seul bloc, quand la densité
     * exacte importe peu — impacts de zone, soins, etc.).
     */
    public static void impactBurst(World world, Location center, Material block,
                                    float size, double spread, int count, long lifetimeTicks) {
        for (int i = 0; i < count; i++) {
            double ox = (Math.random() - 0.5) * 2 * spread;
            double oy = (Math.random() - 0.5) * 2 * spread * 0.5;
            double oz = (Math.random() - 0.5) * 2 * spread;
            impact(world, center.clone().add(ox, oy, oz), block, size, lifetimeTicks);
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
            Location segLoc = new Location(world, x, center.getY(), z);
            float tangentYaw = (float) (angle + Math.PI / 2.0);

            var d = world.spawn(segLoc, BlockDisplay.class, disp -> {
                disp.setBlock(block.createBlockData());
                disp.setBrightness(new Display.Brightness(15, 15));
                disp.setPersistent(false);
                disp.setInterpolationDuration(2);
                disp.setInterpolationDelay(0);
                Quaternionf rot = new Quaternionf().rotateY(tangentYaw);
                disp.setTransformation(new Transformation(
                        new Vector3f(-segLen / 2f, 0f, -thickness / 2f),
                        rot,
                        new Vector3f(segLen, thickness, thickness),
                        new Quaternionf()));
            });
            despawnAfter(d, lifetimeTicks);
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
            Location current = start.clone();
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
        var d = origin.getWorld().spawn(origin, BlockDisplay.class, disp -> {
            disp.setBlock(block.createBlockData());
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(0);
            disp.setInterpolationDelay(0);

            float yaw = (float) Math.toRadians(-Math.toDegrees(Math.atan2(dir.getX(), dir.getZ())));
            Quaternionf rot = new Quaternionf().rotateY(yaw);
            Vector3f translation = new Vector3f(-thickness / 2f, -thickness / 2f, 0f);
            Vector3f scale = new Vector3f(thickness, thickness, (float) length);
            disp.setTransformation(new Transformation(translation, rot, scale, new Quaternionf()));
        });
        despawnAfter(d, lifetimeTicks);
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
            list.add(d);
        }
        return list;
    }

    // ── Helpers internes ────────────────────────────────────────────────────

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
