package fr.lolmc.game;

import fr.lolmc.manager.SchematicManager;
import fr.lolmc.LolPlugin;
import fr.lolmc.game.GameStructure.Type;
import fr.lolmc.team.TeamManager.Team;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Gère la carte de jeu : positions des tourelles/nexus, spawns des joueurs,
 * reset des structures au lancement, et la règle de protection du Nexus.
 *
 * Tout est persisté dans map.yml.
 */
public class MapManager {

    private final SchematicManager schematics;
    private final File mapFile;
    private FileConfiguration config;

    // Structures actives en jeu (rechargées au reset)
    private final List<GameStructure> structures = new ArrayList<>();
    // Spawns : "blue_1" → Location
    private final Map<String, Location> spawns = new HashMap<>();

    // HP par défaut selon le type
    private static final double TURRET_HP = 3000;
    private static final double INHIBITOR_HP = 4000;
    private static final double NEXUS_HP = 5000;
    private static final double NEXUS_BASE_HP = 5500;

    public MapManager(SchematicManager schematics) {
        this.schematics = schematics;
        this.mapFile = new File(LolPlugin.getInstance().getDataFolder(), "map.yml");
        if (!mapFile.exists()) {
            try { mapFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.config = YamlConfiguration.loadConfiguration(mapFile);
        load();
    }

    // ══════════════════════════════════════════════════════════════
    // PLACEMENT (commandes /lol set)
    // ══════════════════════════════════════════════════════════════

    public void setStructure(Type type, Team team, String lane, int index, Location center, int angle) {
        String key = structureKey(type, team, lane, index);
        config.set("structures." + key + ".world", center.getWorld().getName());
        config.set("structures." + key + ".x", center.getBlockX());
        config.set("structures." + key + ".y", center.getBlockY());
        config.set("structures." + key + ".z", center.getBlockZ());
        config.set("structures." + key + ".type", type.name());
        config.set("structures." + key + ".team", team.name());
        config.set("structures." + key + ".lane", lane);
        config.set("structures." + key + ".index", index);
        config.set("structures." + key + ".angle", angle);
        save();
    }

    public void setSpawn(Team team, int position, Location loc) {
        String key = team.name().toLowerCase() + "_" + position;
        spawns.put(key, loc.clone());
        config.set("spawns." + key + ".world", loc.getWorld().getName());
        config.set("spawns." + key + ".x", loc.getX());
        config.set("spawns." + key + ".y", loc.getY());
        config.set("spawns." + key + ".z", loc.getZ());
        config.set("spawns." + key + ".yaw", loc.getYaw());
        config.set("spawns." + key + ".pitch", loc.getPitch());
        save();
    }

    public void setStructureTier(Type type, Team team, String lane, int index, GameStructure.TurretTier tier) {
        String key = structureKey(type, team, lane, index);
        config.set("structures." + key + ".tier", tier.name());
        save();
    }

    public Location getSpawn(Team team, int position) {
        return spawns.get(team.name().toLowerCase() + "_" + position);
    }

    // ══════════════════════════════════════════════════════════════
    // RESET (au lancement de partie)
    // ══════════════════════════════════════════════════════════════

    /**
     * Réinitialise toutes les structures : recharge depuis la config,
     * remet les HP au max, et colle les schématiques de base (100%).
     */
    public void resetAllStructures() {
        structures.clear();

        var section = config.getConfigurationSection("structures");
        if (section == null) {
            LolPlugin.getInstance().getLogger().warning("Aucune structure configurée!");
            return;
        }

        for (String key : section.getKeys(false)) {
            String path = "structures." + key + ".";
            String worldName = config.getString(path + "world");
            var world = LolPlugin.getInstance().getServer().getWorld(worldName);
            if (world == null) continue;

            Location center = new Location(world,
                    config.getInt(path + "x"),
                    config.getInt(path + "y"),
                    config.getInt(path + "z"));
            Type type = Type.valueOf(config.getString(path + "type"));
            Team team = Team.valueOf(config.getString(path + "team"));
            String lane = config.getString(path + "lane");
            int index = config.getInt(path + "index");

            String baseName = schematicBaseName(type, team);

            // CORRECTION : Instanciation et extraction dynamique des objets GameStructure.Phase
            List<GameStructure.Phase> phases = new ArrayList<>();
            File folder = new File(LolPlugin.getInstance().getDataFolder(), "schematics");

            if (folder.exists() && folder.isDirectory()) {
                File[] files = folder.listFiles((dir, name) -> name.startsWith(baseName) && name.endsWith(".json"));
                if (files != null) {
                    for (File f : files) {
                        String schemName = f.getName().substring(0, f.getName().length() - 5); // Retire ".json"
                        String suffix = schemName.substring(baseName.length()); // Récupère ce qui suit (ex: "75", "50" ou "")

                        int threshold = 100; // Par défaut, si aucun chiffre (ex: TurretBlue.json), c'est 100%
                        if (!suffix.isEmpty()) {
                            try {
                                threshold = Integer.parseInt(suffix);
                            } catch (NumberFormatException ignored) {}
                        }

                        phases.add(new GameStructure.Phase(threshold, schemName));
                    }
                }
            }

            // Si le dossier est vide, on ajoute au moins la phase par défaut à 100%
            if (phases.isEmpty()) {
                phases.add(new GameStructure.Phase(100, baseName));
            } else {
                // Tri décroissant obligatoire exigé par GameStructure : [100, 75, 50, 25]
                phases.sort((p1, p2) -> Integer.compare(p2.threshold(), p1.threshold()));
            }

            double maxHP = switch (type) {
                case TURRET -> TURRET_HP;
                case INHIBITOR -> INHIBITOR_HP;
                case NEXUS -> NEXUS_HP;
                case NEXUS_BASE -> NEXUS_BASE_HP;
            };

            GameStructure structure = new GameStructure(type, team, lane, index, center, maxHP, phases);
            int angle = config.getInt(path + "angle", 0);
            structure.setAngle(angle);

            String tierName = config.getString(path + "tier");
            if (tierName != null && type == Type.TURRET) {
                try {
                    structure.setTier(GameStructure.TurretTier.valueOf(tierName));
                } catch (IllegalArgumentException ignored) {}
            }
            structures.add(structure);

            String schem = structure.getCurrentSchematic();
            if (schem != null) {
                schematics.pasteSchematicAboveAnchor(schem, center, 200);
            }
        }

        LolPlugin.getInstance().getLogger().info("Reset: " + structures.size() + " structures rechargées.");
    }

    /**
     * Met à jour la schématique d'une structure quand elle change de phase.
     */
    public void updateStructurePhase(GameStructure structure) {
        String schem = structure.getCurrentSchematic();
        if (schem != null) {
            schematics.pasteSchematicAboveAnchor(schem, structure.getCenter(), 200);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // RÈGLE DE PROTECTION DU NEXUS & HELPERS
    // ══════════════════════════════════════════════════════════════

    public boolean canAttackBaseNexus(Team team) {
        boolean aNexusDown = structures.stream()
                .filter(s -> s.getTeam() == team && s.getType() == Type.NEXUS)
                .anyMatch(GameStructure::isDestroyed);

        List<GameStructure> baseTurrets = structures.stream()
                .filter(s -> s.getTeam() == team && s.getType() == Type.TURRET && "base".equals(s.getLane()))
                .toList();
        boolean allBaseTurretsDown = !baseTurrets.isEmpty()
                && baseTurrets.stream().allMatch(GameStructure::isDestroyed);

        return aNexusDown && allBaseTurretsDown;
    }

    public GameStructure getBaseNexus(Team team) {
        return structures.stream()
                .filter(s -> s.getTeam() == team && s.getType() == Type.NEXUS_BASE)
                .findFirst().orElse(null);
    }

    public GameStructure getStructureAt(Location loc, double radius) {
        for (GameStructure s : structures) {
            if (s.isDestroyed()) continue;
            if (!s.getCenter().getWorld().equals(loc.getWorld())) continue;
            if (s.getCenter().distance(loc) <= radius) return s;
        }
        return null;
    }

    private void load() {
        var section = config.getConfigurationSection("spawns");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String path = "spawns." + key + ".";
                var world = LolPlugin.getInstance().getServer().getWorld(config.getString(path + "world"));
                if (world == null) continue;
                Location loc = new Location(world,
                        config.getDouble(path + "x"),
                        config.getDouble(path + "y"),
                        config.getDouble(path + "z"),
                        (float) config.getDouble(path + "yaw"),
                        (float) config.getDouble(path + "pitch"));
                spawns.put(key, loc);
            }
        }
    }

    private void save() {
        try { config.save(mapFile); }
        catch (Exception e) { LolPlugin.getInstance().getLogger().warning("Erreur sauvegarde map.yml: " + e.getMessage()); }
    }

    private String structureKey(Type type, Team team, String lane, int index) {
        return type.name().toLowerCase() + "_" + team.name().toLowerCase() + "_" + lane + "_" + index;
    }

    private String schematicBaseName(Type type, Team team) {
        String teamName = (team == Team.BLUE) ? "Blue" : "Red";
        return switch (type) {
            case TURRET -> "Turret" + teamName;
            case INHIBITOR -> "Inhibitor" + teamName;
            case NEXUS -> "Nexus" + teamName;
            case NEXUS_BASE -> "NexusBase" + teamName;
        };
    }

    public List<GameStructure> getStructures() { return structures; }
    public SchematicManager getSchematics()    { return schematics; }
}