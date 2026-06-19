package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Gère les routes des sbires, tracées en peignant des blocs en jeu.
 *
 * Le joueur fait /lol road <lane> <blue|red>, reçoit un outil, et clique
 * sur des blocs pour peindre le chemin. Les blocs cliqués sont mémorisés
 * et regroupés en segments ; le centre de chaque segment devient un waypoint.
 * À /lol road end, les blocs reprennent leur apparence d'origine.
 *
 * Les sbires suivent ces waypoints mais peuvent dévier jusqu'à 10 blocs
 * pour attaquer, puis reviennent sur leur chemin.
 */
public class RoadManager {

    private final File roadFile;
    private FileConfiguration config;

    // Routes finalisées : lane → liste de waypoints (centres des segments)
    private final Map<String, List<Location>> roads = new HashMap<>();

    // Session de peinture en cours, par joueur
    private final Map<UUID, PaintSession> sessions = new HashMap<>();

    // Bloc utilisé pour peindre la route (visuel temporaire)
    private static final Material PAINT_BLOCK = Material.LIME_GLAZED_TERRACOTTA;

    public static class PaintSession {
        public final String lane;
        public final String teamHint;           // "blue" ou "red" (sens de tracé)
        // Blocs peints : position → matériau d'origine (pour restaurer)
        public final LinkedHashMap<Location, BlockData> painted = new LinkedHashMap<>();

        public PaintSession(String lane, String teamHint) {
            this.lane = lane;
            this.teamHint = teamHint;
        }
    }

    public RoadManager() {
        this.roadFile = new File(LolPlugin.getInstance().getDataFolder(), "roads.yml");
        if (!roadFile.exists()) {
            try { roadFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.config = YamlConfiguration.loadConfiguration(roadFile);
        load();
    }

    // ══════════════════════════════════════════════════════════════
    // SESSION DE PEINTURE
    // ══════════════════════════════════════════════════════════════

    public void startPainting(UUID player, String lane, String teamHint) {
        sessions.put(player, new PaintSession(lane, teamHint));
    }

    public boolean isPainting(UUID player) {
        return sessions.containsKey(player);
    }

    /**
     * Peint un bloc (le change en bloc visuel, mémorise l'original).
     * @return true si le bloc a été ajouté, false s'il était déjà peint
     */
    public boolean paintBlock(UUID player, Block block) {
        PaintSession session = sessions.get(player);
        if (session == null) return false;
        Location loc = block.getLocation();
        if (session.painted.containsKey(loc)) return false;
        // Mémoriser l'état d'origine
        session.painted.put(loc, block.getBlockData().clone());
        // Peindre
        block.setType(PAINT_BLOCK);
        return true;
    }

    /**
     * Termine la peinture : calcule les waypoints (centres de segments),
     * restaure les blocs d'origine, et sauvegarde la route.
     */
    public int finishPainting(UUID player) {
        PaintSession session = sessions.remove(player);
        if (session == null) return 0;

        // Restaurer tous les blocs peints
        for (var entry : session.painted.entrySet()) {
            entry.getKey().getBlock().setBlockData(entry.getValue());
        }

        if (session.painted.isEmpty()) return 0;

        // Calculer les waypoints : regrouper les blocs peints en segments
        // et prendre le centre de chaque segment.
        List<Location> waypoints = computeWaypoints(new ArrayList<>(session.painted.keySet()));

        // Normalisation du sens : on stocke TOUJOURS dans le sens BASE BLEUE → BASE ROUGE.
        // Si le joueur a tracé depuis la base ROUGE (/lol road <lane> red), on inverse.
        if (session.teamHint.equals("red") || session.teamHint.equals("rouge")) {
            Collections.reverse(waypoints);
        }

        // Sauvegarder (sens interne unique : bleu → rouge)
        roads.put(session.lane, waypoints);
        saveRoad(session.lane, waypoints);

        // Mettre à jour le MinionManager
        LolPlugin.getInstance().getMinionManager().setLaneWaypoints(session.lane, waypoints);

        return waypoints.size();
    }

    public void cancelPainting(UUID player) {
        PaintSession session = sessions.remove(player);
        if (session == null) return;
        // Restaurer sans sauvegarder
        for (var entry : session.painted.entrySet()) {
            entry.getKey().getBlock().setBlockData(entry.getValue());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CALCUL DES WAYPOINTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Regroupe les blocs peints en segments connexes et calcule le centre
     * de chaque groupe. On parcourt les blocs dans l'ordre de peinture et
     * on crée un waypoint tous les ~4 blocs de distance, en moyennant la zone.
     */
    private List<Location> computeWaypoints(List<Location> paintedBlocks) {
        List<Location> waypoints = new ArrayList<>();
        if (paintedBlocks.isEmpty()) return waypoints;

        // On parcourt dans l'ordre de peinture (LinkedHashMap préserve l'ordre)
        // et on agrège les blocs proches en "zones", dont on prend le centre.
        List<Location> currentCluster = new ArrayList<>();
        currentCluster.add(paintedBlocks.get(0));

        for (int i = 1; i < paintedBlocks.size(); i++) {
            Location loc = paintedBlocks.get(i);
            Location clusterCenter = average(currentCluster);
            // Si le bloc est proche du cluster courant (≤3 blocs), l'ajouter
            if (loc.getWorld().equals(clusterCenter.getWorld())
                    && loc.distance(clusterCenter) <= 3.0) {
                currentCluster.add(loc);
            } else {
                // Finaliser le cluster courant → waypoint
                waypoints.add(average(currentCluster).add(0.5, 1, 0.5));
                currentCluster = new ArrayList<>();
                currentCluster.add(loc);
            }
        }
        // Dernier cluster
        if (!currentCluster.isEmpty()) {
            waypoints.add(average(currentCluster).add(0.5, 1, 0.5));
        }

        return waypoints;
    }

    /** Centre (moyenne) d'un groupe de positions. */
    private Location average(List<Location> locs) {
        double x = 0, y = 0, z = 0;
        for (Location l : locs) { x += l.getBlockX(); y += l.getBlockY(); z += l.getBlockZ(); }
        int n = locs.size();
        return new Location(locs.get(0).getWorld(), x / n, y / n, z / n);
    }

    // ══════════════════════════════════════════════════════════════
    // ACCÈS AUX ROUTES (pour les sbires)
    // ══════════════════════════════════════════════════════════════

    /** Waypoints d'une lane dans l'ordre de tracé (sens BLEU → ROUGE). */
    public List<Location> getRoad(String lane) {
        return roads.getOrDefault(lane, new ArrayList<>());
    }

    public boolean hasRoad(String lane) {
        return roads.containsKey(lane) && !roads.get(lane).isEmpty();
    }

    /**
     * Trouve le point du chemin le plus proche d'une position donnée.
     * Sert à faire revenir un sbire sur sa route après une déviation.
     */
    public Location getClosestRoadPoint(String lane, Location from) {
        List<Location> road = roads.get(lane);
        if (road == null || road.isEmpty()) return null;
        Location closest = null;
        double best = Double.MAX_VALUE;
        for (Location wp : road) {
            if (!wp.getWorld().equals(from.getWorld())) continue;
            double d = wp.distance(from);
            if (d < best) { best = d; closest = wp; }
        }
        return closest;
    }

    // ══════════════════════════════════════════════════════════════
    // PERSISTANCE
    // ══════════════════════════════════════════════════════════════

    private void saveRoad(String lane, List<Location> waypoints) {
        List<String> serialized = new ArrayList<>();
        for (Location l : waypoints) {
            serialized.add(l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ());
        }
        config.set("roads." + lane, serialized);
        try { config.save(roadFile); }
        catch (Exception e) { LolPlugin.getInstance().getLogger().warning("Erreur sauvegarde roads.yml: " + e.getMessage()); }
    }

    private void load() {
        var section = config.getConfigurationSection("roads");
        if (section == null) return;
        for (String lane : section.getKeys(false)) {
            List<String> serialized = config.getStringList("roads." + lane);
            List<Location> waypoints = new ArrayList<>();
            for (String s : serialized) {
                String[] parts = s.split(";");
                if (parts.length != 4) continue;
                var world = LolPlugin.getInstance().getServer().getWorld(parts[0]);
                if (world == null) continue;
                waypoints.add(new Location(world,
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3])));
            }
            if (!waypoints.isEmpty()) roads.put(lane, waypoints);
        }
    }

    /** Recharge les routes dans le MinionManager au démarrage. */
    public void applyToMinionManager() {
        for (var entry : roads.entrySet()) {
            LolPlugin.getInstance().getMinionManager().setLaneWaypoints(entry.getKey(), entry.getValue());
        }
    }

    public static Material getPaintBlock() { return PAINT_BLOCK; }
}
