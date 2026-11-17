package fr.lolmc.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import fr.lolmc.LolPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dégradation visible des structures par paliers de 25 % (100 → 75 → 50 → 25 → ruines).
 *
 * Les schématiques ne couvrent qu'une partie des tourelles : le reste est construit dans la carte.
 * On enregistre donc UNE fois une « photo » de tous les blocs réels autour de chaque structure
 * intacte (plugins/LolMC/structure_snapshots/&lt;id&gt;.json). À chaque palier, la structure est
 * reconstruite depuis la photo puis une part croissante de blocs (choisis de façon déterministe,
 * du haut vers le bas) est retirée ou fissurée : chaque palier contient le précédent, sans jamais
 * remettre de blocs. Au reset de la partie et à la réapparition, la photo est restaurée.
 */
public final class StructureRuins {

    /** Un bloc de la photo, relatif au four d'ancrage de la structure. */
    private record SnapBlock(int dx, int dy, int dz, String data) {}

    private final Map<String, List<SnapBlock>> snapshots = new HashMap<>();
    private final File folder;
    private final Gson gson = new Gson();

    public StructureRuins() {
        folder = new File(LolPlugin.getInstance().getDataFolder(), "structure_snapshots");
    }

    private static int radius(GameStructure s) {
        return switch (s.getType()) {
            case TURRET, INHIBITOR -> 4;
            case NEXUS, NEXUS_BASE -> 6;
        };
    }
    private static final int HEIGHT = 26;

    /** Photo de la structure (chargée depuis le disque si besoin), ou null si aucune. */
    private List<SnapBlock> get(GameStructure s) {
        List<SnapBlock> snap = snapshots.get(s.getId());
        if (snap != null) return snap;
        File f = new File(folder, s.getId() + ".json");
        if (!f.exists()) return null;
        try (FileReader r = new FileReader(f)) {
            snap = gson.fromJson(r, new TypeToken<ArrayList<SnapBlock>>() {}.getType());
            if (snap != null) snapshots.put(s.getId(), snap);
            return snap;
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("Photo de structure illisible " + f.getName() + " : " + e.getMessage());
            return null;
        }
    }

    public boolean has(GameStructure s) { return get(s) != null; }

    /**
     * Enregistre la photo d'une structure intacte si elle n'existe pas encore (appelé au reset,
     * une fois la schématique de base collée). Ne prend jamais de photo d'une structure abîmée.
     */
    public void captureIfMissing(GameStructure s) {
        if (has(s) || s.isDestroyed() || s.getCurrentHP() < s.getMaxHP()) return;
        Location c = s.getCenter();
        if (c == null || c.getWorld() == null || !c.isChunkLoaded()) return;
        int r = radius(s);
        List<SnapBlock> snap = new ArrayList<>();
        World w = c.getWorld();
        for (int dy = 1; dy <= HEIGHT; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    Block b = w.getBlockAt(c.getBlockX() + dx, c.getBlockY() + dy, c.getBlockZ() + dz);
                    if (b.getType().isAir()) continue;
                    snap.add(new SnapBlock(dx, dy, dz, b.getBlockData().getAsString()));
                }
        if (snap.isEmpty()) return;
        snapshots.put(s.getId(), snap);
        folder.mkdirs();
        try (FileWriter fw = new FileWriter(new File(folder, s.getId() + ".json"))) {
            gson.toJson(snap, fw);
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("Photo de structure non enregistrée " + s.getId() + " : " + e.getMessage());
        }
    }

    /** Palier visuel selon les PV : 0 = intacte, 1 = ≤ 75 %, 2 = ≤ 50 %, 3 = ≤ 25 %, 4 = détruite. */
    public static int level(GameStructure s) {
        if (s.isDestroyed()) return 4;
        double pct = s.getHealthPercent();
        return pct <= 25 ? 3 : pct <= 50 ? 2 : pct <= 75 ? 1 : 0;
    }

    /** Applique le palier correspondant aux PV actuels. @return false si la structure n'a pas de photo. */
    public boolean apply(GameStructure s) {
        List<SnapBlock> snap = get(s);
        if (snap == null) return false;
        Location c = s.getCenter();
        if (c == null || c.getWorld() == null) return false;
        int lvl = level(s);
        int hm = 1;
        for (SnapBlock b : snap) hm = Math.max(hm, b.dy());
        World w = c.getWorld();
        // Blocs tombés, du bas vers le haut : un bloc posé sur un bloc tombé tombe aussi
        // (sinon des morceaux du sommet restaient suspendus en l'air)
        java.util.Set<Long> present = new java.util.HashSet<>(), fallen = new java.util.HashSet<>();
        for (SnapBlock b : snap) present.add(key(b.dx(), b.dy(), b.dz()));
        if (lvl > 0) {
            List<SnapBlock> sorted = new ArrayList<>(snap);
            sorted.sort(java.util.Comparator.comparingInt(SnapBlock::dy));
            for (SnapBlock b : sorted) {
                if (b.dy() < 2) continue;
                long below = key(b.dx(), b.dy() - 1, b.dz());
                boolean unsupported = present.contains(below) && fallen.contains(below);
                if (unsupported || rand(s, b, 'f') < fall(lvl, (double) b.dy() / hm)) fallen.add(key(b.dx(), b.dy(), b.dz()));
            }
            // Morceaux qui ne touchent plus le sol par aucun bloc restant (bouts de bras, corniches) :
            // ils tombent aussi. Parcours depuis les blocs restants posés au sol (dy = 1).
            java.util.Set<Long> kept = new java.util.HashSet<>(present);
            kept.removeAll(fallen);
            java.util.Set<Long> grounded = new java.util.HashSet<>();
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            for (SnapBlock b : snap) {
                long k = key(b.dx(), b.dy(), b.dz());
                if (b.dy() == 1 && kept.contains(k) && grounded.add(k)) queue.add(k);
            }
            int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            while (!queue.isEmpty()) {
                long k = queue.poll();
                int dx = (int) ((k >> 32) & 0xFFFF) - 64, dy = (int) ((k >> 16) & 0xFFFF) - 64, dz = (int) (k & 0xFFFF) - 64;
                for (int[] d : dirs) {
                    long n = key(dx + d[0], dy + d[1], dz + d[2]);
                    if (kept.contains(n) && grounded.add(n)) queue.add(n);
                }
            }
            for (SnapBlock b : snap) {
                long k = key(b.dx(), b.dy(), b.dz());
                if (b.dy() >= 2 && kept.contains(k) && !grounded.contains(k)) fallen.add(k);
            }
        }
        boolean fire = false;
        for (SnapBlock b : snap) {
            String data = b.data();
            if (lvl > 0 && b.dy() >= 2) {
                if (fallen.contains(key(b.dx(), b.dy(), b.dz()))) data = "minecraft:air";
                else if (rand(s, b, 'c') < CRACK[lvl]) data = crack(data);
            } else if (lvl >= 3 && b.dy() == 1) {
                // Sol de la structure : un feu qui fume et des gravats
                if (!fire && Math.abs(b.dx()) == 2 && b.dz() == 0) {
                    data = "minecraft:campfire[lit=true,signal_fire=false,facing=north,waterlogged=false]";
                    fire = true;
                } else if (rand(s, b, 'r') < (lvl == 4 ? 0.3 : 0.12)) {
                    data = RUBBLE[(int) (rand(s, b, 't') * RUBBLE.length)];
                }
            }
            Block blk = w.getBlockAt(c.getBlockX() + b.dx(), c.getBlockY() + b.dy(), c.getBlockZ() + b.dz());
            if (blk.getBlockData().getAsString().equals(data)) continue;
            try {
                BlockData bd = Bukkit.createBlockData(data);
                blk.setBlockData(bd, false);
            } catch (IllegalArgumentException ignored) {}
        }
        return true;
    }

    /** Remet la structure intacte (reset de partie, réapparition). */
    public void restore(GameStructure s) {
        List<SnapBlock> snap = get(s);
        Location c = s.getCenter();
        if (snap == null || c == null || c.getWorld() == null) return;
        World w = c.getWorld();
        for (SnapBlock b : snap) {
            Block blk = w.getBlockAt(c.getBlockX() + b.dx(), c.getBlockY() + b.dy(), c.getBlockZ() + b.dz());
            if (blk.getBlockData().getAsString().equals(b.data())) continue;
            try { blk.setBlockData(Bukkit.createBlockData(b.data()), false); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private static long key(int dx, int dy, int dz) {
        return ((long) (dx + 64) << 32) | ((long) (dy + 64) << 16) | (dz + 64);
    }

    // ── Règles de dégradation (chaque palier contient le précédent) ──

    private static final double[] CRACK = {0, 0.3, 0.5, 0.65, 0.8};
    private static final String[] RUBBLE = {"minecraft:cobblestone", "minecraft:mossy_cobblestone",
            "minecraft:cobbled_deepslate", "minecraft:andesite"};
    private static final Map<String, String> CRACKED = Map.ofEntries(
            Map.entry("minecraft:stone_bricks", "minecraft:cracked_stone_bricks"),
            Map.entry("minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks"),
            Map.entry("minecraft:deepslate_tiles", "minecraft:cracked_deepslate_tiles"),
            Map.entry("minecraft:polished_blackstone_bricks", "minecraft:cracked_polished_blackstone_bricks"),
            Map.entry("minecraft:stone", "minecraft:cobblestone"),
            Map.entry("minecraft:smooth_stone", "minecraft:cobblestone"),
            Map.entry("minecraft:cobblestone", "minecraft:mossy_cobblestone"),
            Map.entry("minecraft:cobblestone_wall", "minecraft:mossy_cobblestone_wall"),
            Map.entry("minecraft:cobblestone_stairs", "minecraft:mossy_cobblestone_stairs"),
            Map.entry("minecraft:cobblestone_slab", "minecraft:mossy_cobblestone_slab"),
            Map.entry("minecraft:stone_brick_wall", "minecraft:mossy_stone_brick_wall"),
            Map.entry("minecraft:stone_brick_stairs", "minecraft:mossy_stone_brick_stairs"),
            Map.entry("minecraft:stone_brick_slab", "minecraft:mossy_stone_brick_slab"));

    /** Probabilité qu'un bloc soit tombé, selon le palier et sa hauteur relative (0 = sol, 1 = sommet). */
    private static double fall(int lvl, double h) {
        return switch (lvl) {
            case 1 -> h > 0.6 ? 0.18 : 0;
            case 2 -> h > 0.75 ? 0.6 : h > 0.4 ? 0.25 : 0;
            case 3 -> h > 0.55 ? 0.85 : h > 0.3 ? 0.4 : 0;
            default -> h > 0.35 ? 1 : h > 0.15 ? 0.3 : 0;
        };
    }

    private static String crack(String data) {
        int i = data.indexOf('[');
        String name = i < 0 ? data : data.substring(0, i);
        String to = CRACKED.get(name);
        if (to == null) return data;
        // Murets/escaliers/dalles : mêmes propriétés ; blocs pleins : pas de propriétés
        return i < 0 || !(to.endsWith("_wall") || to.endsWith("_stairs") || to.endsWith("_slab")) ? to : to + data.substring(i);
    }

    /** Hasard déterministe par bloc (le même bloc tombe toujours au même palier). */
    private static double rand(GameStructure s, SnapBlock b, char salt) {
        long h = 1469598103934665603L;
        String k = s.getId() + ':' + b.dx() + ':' + b.dy() + ':' + b.dz() + ':' + salt;
        for (int i = 0; i < k.length(); i++) { h ^= k.charAt(i); h *= 1099511628211L; }
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }
}
