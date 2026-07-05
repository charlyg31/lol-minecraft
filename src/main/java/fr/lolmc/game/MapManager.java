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
    // HP tours LoL : T1 5000, T2 5300, T3 5500
    private static final double TURRET_T1_HP = 5000;
    private static final double TURRET_T2_HP = 5300;
    private static final double TURRET_T3_HP = 5500;
    private static final double INHIBITOR_HP = 4000;
    private static final double NEXUS_HP = 5000;
    private static final double NEXUS_BASE_HP = 5500;

    // World cible (null = utilise le monde de chaque Location stockée)
    private org.bukkit.World targetWorld = null;

    public MapManager(SchematicManager schematics) {
        this.schematics = schematics;
        this.mapFile = new File(LolPlugin.getInstance().getDataFolder(), "map.yml");
        if (!mapFile.exists()) {
            try { mapFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.config = YamlConfiguration.loadConfiguration(mapFile);
        load();
    }

    /**
     * Constructeur pour les instances : copie la config du template
     * mais remplace le World de toutes les Locations par instanceWorld.
     */
    public MapManager(SchematicManager schematics, org.bukkit.World instanceWorld) {
        this.schematics  = schematics;
        this.targetWorld = instanceWorld;
        // Utiliser la même map.yml que le template
        this.mapFile = new File(LolPlugin.getInstance().getDataFolder(), "map.yml");
        if (!mapFile.exists()) {
            try { mapFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.config = YamlConfiguration.loadConfiguration(mapFile);
        load();
        // Remapper toutes les Locations vers le monde de l'instance
        remapToWorld(instanceWorld);
    }

    /** Remplace le World de toutes les Locations par instanceWorld. */
    private void remapToWorld(org.bukkit.World w) {
        spawns.replaceAll((k, loc) -> loc == null ? null
            : new org.bukkit.Location(w, loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch()));
        for (GameStructure s : structures) {
            if (s.getCenter() != null) {
                org.bukkit.Location c = s.getCenter();
                s.setCenter(new org.bukkit.Location(w, c.getX(), c.getY(), c.getZ()));
            }
        }
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
            String lane  = config.getString(path + "lane");
            int index    = config.getInt(path + "index");

            // Nom de base avec fallback : TurretBlue_top_1 → TurretBlue_top → TurretBlue
            // Permet d'avoir des schémas différents par tour sans tout redéfinir.
            File folder = new File(LolPlugin.getInstance().getDataFolder(), "schematics");
            String baseName = schematicBaseName(type, team, lane, index, folder);

            // CORRECTION : Instanciation et extraction dynamique des objets GameStructure.Phase
            List<GameStructure.Phase> phases = new ArrayList<>();

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
                case TURRET -> switch (index) {
                    case 1  -> TURRET_T1_HP;
                    case 2  -> TURRET_T2_HP;
                    default -> TURRET_T3_HP;
                };
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

    /**
     * Résout le nom de base du schéma (SANS le seuil de HP).
     *
     * Priorité (Turret, Blue, top, index=1) :
     *   1. Turret_Blue_Top_1  ← tour précise
     *   2. Turret_Blue_Top    ← toutes les tours top bleues
     *   3. Turret_Blue        ← générique
     *
     * Les phases ajoutent ensuite le seuil : Turret_Blue_Top_1_75.json
     * Le générique utilise : Turret_Blue_75.json
     *
     * Le seuil est toujours le dernier suffixe numérique — il ne fait
     * jamais partie du nom de base, qui ne contient que des mots.
     */
    private String schematicBaseName(Type type, Team team, String lane, int index, File folder) {
        String teamName = (team == Team.BLUE) ? "Blue" : "Red";
        String typeName = switch (type) {
            case TURRET     -> "Turret";
            case INHIBITOR  -> "Inhibitor";
            case NEXUS      -> "Nexus";
            case NEXUS_BASE -> "NexusBase";
        };

        String root = typeName + "_" + teamName;
        String laneCap = (lane == null || lane.isEmpty()) ? "" :
            lane.substring(0, 1).toUpperCase() + lane.substring(1).toLowerCase();

        // Candidats du plus spécifique au plus générique (sans seuil)
        List<String> candidates = new ArrayList<>();
        if (!laneCap.isEmpty()) {
            candidates.add(root + "_" + laneCap + "_" + index); // Turret_Blue_Top_1
            candidates.add(root + "_" + laneCap);                // Turret_Blue_Top
        }
        candidates.add(root);                                    // Turret_Blue

        if (folder.exists() && folder.isDirectory()) {
            for (String candidate : candidates) {
                // Un fichier correspondant existe si : candidate.json
                // OU candidate_<number>.json (phase)
                File[] match = folder.listFiles((d, n) -> {
                    if (!n.endsWith(".json")) return false;
                    String base = n.substring(0, n.length() - 5); // sans .json
                    if (base.equals(candidate)) return true;
                    // Vérifier si c'est candidate_<number>
                    if (base.startsWith(candidate + "_")) {
                        String suffix = base.substring(candidate.length() + 1);
                        return suffix.matches("\\d+");
                    }
                    return false;
                });
                if (match != null && match.length > 0) return candidate;
            }
        }
        return root;
    }

    private String schematicBaseName(Type type, Team team) {
        String teamName = (team == Team.BLUE) ? "Blue" : "Red";
        return switch (type) {
            case TURRET     -> "Turret_"    + teamName;
            case INHIBITOR  -> "Inhibitor_" + teamName;
            case NEXUS      -> "Nexus_"     + teamName;
            case NEXUS_BASE -> "NexusBase_" + teamName;
        };
    }

    public List<GameStructure> getStructures() { return structures; }
    public SchematicManager getSchematics()    { return schematics; }


    /** Retourne toutes les structures d'une équipe (pour le TP). */
    public java.util.List<GameStructure> getStructuresForTeam(fr.lolmc.team.TeamManager.Team team) {
        var result = new java.util.ArrayList<GameStructure>();
        for (var s : structures) { if (s.getTeam() == team) result.add(s); }
        return result;
    }


    /** Remet un inhibiteur a pleine sante (appele apres le timer de 5 min). */
    public void respawnInhibitor(String key) {
        for (var s : structures) {
            String sKey = s.getType().name() + "_" + s.getTeam() + "_" + s.getLane();
            if (sKey.equals(key) && s.getType() == GameStructure.Type.INHIBITOR) {
                s.respawn();
                return;
            }
        }
    }
}
