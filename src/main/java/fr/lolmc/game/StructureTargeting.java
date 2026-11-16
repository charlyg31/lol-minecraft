package fr.lolmc.game;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Ciblage des structures (tourelles, inhibiteurs, nexus) façon LoL : la structure est visée quand
 * le viseur touche son emprise réelle (boîte de sa schématique), et la portée d'attaque se mesure
 * jusqu'à son bord, pas jusqu'au four d'ancrage (qui peut être n'importe où dans la schématique).
 */
public final class StructureTargeting {

    private StructureTargeting() {}

    /** Distance maximale à laquelle une structure peut être visée (le lock attend ensuite d'être à portée). */
    public static final double AIM_DISTANCE = 30.0;

    /** Emprise de repli quand la schématique est inconnue : 5×5 blocs, 8 de haut, au-dessus du four. */
    private static BoundingBox fallback(Location c) {
        return new BoundingBox(c.getBlockX() - 2, c.getBlockY() + 1, c.getBlockZ() - 2,
                               c.getBlockX() + 3, c.getBlockY() + 9, c.getBlockZ() + 3);
    }

    /** Boîte englobante de la structure dans le monde. */
    public static BoundingBox bounds(MapManager mm, GameStructure s) {
        BoundingBox bb = null;
        if (mm != null && mm.getSchematics() != null) {
            // Silhouette de la phase actuellement affichée (75 %, 50 %…)
            String schem = s.getCurrentSchematic();
            bb = mm.getSchematics().getBounds(schem, s.getCenter());
        }
        return bb != null ? bb : fallback(s.getCenter());
    }

    /** Point de la structure le plus proche de {@code from}. */
    public static Location nearestPoint(MapManager mm, GameStructure s, Location from) {
        BoundingBox bb = bounds(mm, s);
        return new Location(from.getWorld(),
                clamp(from.getX(), bb.getMinX(), bb.getMaxX()),
                clamp(from.getY(), bb.getMinY(), bb.getMaxY()),
                clamp(from.getZ(), bb.getMinZ(), bb.getMaxZ()));
    }

    /**
     * Distance horizontale entre le joueur et le bord de la structure (0 s'il est collé ou dedans).
     * Horizontale : une tourelle haute ne doit pas être hors de portée parce qu'on vise son sommet.
     */
    public static double edgeDistance(MapManager mm, GameStructure s, Location from) {
        BoundingBox bb = bounds(mm, s);
        double dx = from.getX() - clamp(from.getX(), bb.getMinX(), bb.getMaxX());
        double dz = from.getZ() - clamp(from.getZ(), bb.getMinZ(), bb.getMaxZ());
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Distance le long du viseur jusqu'à la structure, ou -1 si le viseur ne la touche pas.
     * La boîte est légèrement élargie pour que viser le bord compte.
     */
    public static double aimDistance(Player p, MapManager mm, GameStructure s) {
        if (!s.getCenter().getWorld().equals(p.getWorld())) return -1;
        Location eye = p.getEyeLocation();
        RayTraceResult r = bounds(mm, s).clone().expand(0.5)
                .rayTrace(eye.toVector(), eye.getDirection(), AIM_DISTANCE);
        if (r == null) return -1;
        return r.getHitPosition().distance(eye.toVector());
    }

    /** Structure (non détruite) sous le viseur, la plus proche ; null si aucune. */
    public static GameStructure aimed(Player p, MapManager mm, java.util.function.Predicate<GameStructure> filter) {
        if (mm == null) return null;
        GameStructure best = null;
        double bestDist = Double.MAX_VALUE;
        for (GameStructure s : mm.getStructures()) {
            if (s.isDestroyed() || !filter.test(s)) continue;
            double d = aimDistance(p, mm, s);
            if (d >= 0 && d < bestDist) { bestDist = d; best = s; }
        }
        return best;
    }

    /** True si le viseur du joueur passe sur l'entité (hitbox légèrement élargie). */
    public static boolean crosshairOn(Player p, org.bukkit.entity.Entity e) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        return e.getBoundingBox().clone().expand(0.4).rayTrace(eye.toVector(), dir, AIM_DISTANCE) != null;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
