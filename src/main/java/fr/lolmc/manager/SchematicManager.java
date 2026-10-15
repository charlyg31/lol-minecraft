package fr.lolmc.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fr.lolmc.LolPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class SchematicManager {

    private final LolPlugin plugin;
    private final Gson gson;
    private final File schematicsFolder;
    /** Dernière schématique collée sur chaque four (monde:x:y:z). */
    private final java.util.Map<String, String> lastPasted = new java.util.concurrent.ConcurrentHashMap<>();

    public SchematicManager(LolPlugin plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.schematicsFolder = new File(plugin.getDataFolder(), "schematics");
        if (!schematicsFolder.exists() && !schematicsFolder.mkdirs()) {
            plugin.getLogger().warning("Impossible de créer le dossier schematics/.");
        }
    }

    /**
     * Scanne la zone, trouve le FOUR de référence, et sauvegarde tout par rapport à lui (le four devient le 0,0,0).
     */
    public void saveSchematicWithAnchor(String name, Location coin1, Location coin2) {
        int minX = Math.min(coin1.getBlockX(), coin2.getBlockX());
        int maxX = Math.max(coin1.getBlockX(), coin2.getBlockX());
        int minY = Math.min(coin1.getBlockY(), coin2.getBlockY());
        int maxY = Math.max(coin1.getBlockY(), coin2.getBlockY());
        int minZ = Math.min(coin1.getBlockZ(), coin2.getBlockZ());
        int maxZ = Math.max(coin1.getBlockZ(), coin2.getBlockZ());

        World world = coin1.getWorld();
        Location anchorLoc = null;

        // 1. Chercher le bloc de référence (Le Four)
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.FURNACE) {
                        anchorLoc = new Location(world, x, y, z);
                        break;
                    }
                }
            }
        }

        if (anchorLoc == null) {
            plugin.getLogger().severe("Impossible de sauvegarder : Aucun FOUR trouvé dans la zone pour servir d'ancre !");
            return;
        }

        List<SavedBlock> blocks = new ArrayList<>();

        // 2. Scanner et enregistrer les blocs relativement au Four
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.AIR) continue;

                    int relX = x - anchorLoc.getBlockX();
                    int relY = y - anchorLoc.getBlockY();
                    int relZ = z - anchorLoc.getBlockZ();

                    blocks.add(new SavedBlock(relX, relY, relZ, block.getBlockData().getAsString()));
                }
            }
        }

        // Sauvegarde asynchrone
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File file = new File(schematicsFolder, name + ".json");
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(blocks, writer);
                plugin.getLogger().info("Schématique '" + name + "' sauvegardée avec l'ancre Four !");
            } catch (IOException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Échec de sauvegarde de la schématique '" + name + "'", e);
            }
        });
    }

    /**
     * Colle la schématique JUSTE AU-DESSUS du four posé au sol, en respectant son orientation.
     */
    public void pasteSchematicAboveAnchor(String name, Location groundFurnaceLoc, int blocksPerTick) {
        File file = new File(schematicsFolder, name + ".json");
        if (!file.exists()) {
            plugin.getLogger().warning("Fichier de schématique introuvable : " + name);
            return;
        }

        Block targetBlock = groundFurnaceLoc.getBlock();
        if (targetBlock.getType() != Material.FURNACE) {
            plugin.getLogger().warning("Le bloc cible au sol n'est pas un FOUR. Impossible de l'utiliser comme repère.");
            return;
        }

        // 1. Récupérer l'orientation du four qui est déjà au sol
        Directional targetData = (Directional) targetBlock.getBlockData();
        BlockFace targetFacing = targetData.getFacing();

        String anchorKey = groundFurnaceLoc.getWorld().getName() + ":" + groundFurnaceLoc.getBlockX() + ":"
                + groundFurnaceLoc.getBlockY() + ":" + groundFurnaceLoc.getBlockZ();
        String previous = lastPasted.put(anchorKey, name);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (FileReader reader = new FileReader(file)) {
                Type listType = new TypeToken<ArrayList<SavedBlock>>() {}.getType();
                List<SavedBlock> blocks = gson.fromJson(reader, listType);

                // Blocs de la phase précédente que la nouvelle ne couvre pas : remis en air (les
                // schématiques n'enregistrent pas l'air, une phase plus petite laissait des restes)
                List<int[]> toClear = new ArrayList<>();
                if (previous != null && !previous.equals(name)) {
                    File prevFile = new File(schematicsFolder, previous + ".json");
                    if (prevFile.exists()) {
                        try (FileReader pr = new FileReader(prevFile)) {
                            List<SavedBlock> prevBlocks = gson.fromJson(pr, listType);
                            java.util.Set<String> kept = new java.util.HashSet<>();
                            StructureRotation rNew = calculateRotation(anchorFacing(blocks), targetFacing);
                            for (SavedBlock sb : blocks) { int[] r = rotate(sb, rNew); kept.add(r[0] + ":" + sb.getRelY() + ":" + r[1]); }
                            StructureRotation rOld = calculateRotation(anchorFacing(prevBlocks), targetFacing);
                            for (SavedBlock sb : prevBlocks) {
                                int[] r = rotate(sb, rOld);
                                if (!kept.contains(r[0] + ":" + sb.getRelY() + ":" + r[1])) toClear.add(new int[]{r[0], sb.getRelY(), r[1]});
                            }
                        }
                    }
                }
                if (!toClear.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> {
                    for (int[] c : toClear)
                        groundFurnaceLoc.clone().add(c[0], c[1] + 1, c[2]).getBlock().setType(Material.AIR, false);
                });

                // 2. Trouver le four d'origine dans le fichier (coordonnées 0, 0, 0) pour connaître son orientation de base
                BlockFace savedFacing = BlockFace.NORTH;
                for (SavedBlock sb : blocks) {
                    if (sb.getRelX() == 0 && sb.getRelY() == 0 && sb.getRelZ() == 0) {
                        BlockData bd = Bukkit.createBlockData(sb.getBlockData());
                        if (bd instanceof Directional) {
                            savedFacing = ((Directional) bd).getFacing();
                        }
                        break;
                    }
                }

                // 3. Calculer la rotation
                StructureRotation rotation = calculateRotation(savedFacing, targetFacing);

                // 4. Appliquer le collage sur le thread principal
                Bukkit.getScheduler().runTask(plugin, () -> {
                    new BukkitRunnable() {
                        int index = 0;

                        @Override
                        public void run() {
                            for (int i = 0; i < blocksPerTick; i++) {
                                if (index >= blocks.size()) {
                                    this.cancel();
                                    return;
                                }

                                SavedBlock sb = blocks.get(index);

                                // Rotation mathématique X et Z
                                int rotatedX = sb.getRelX();
                                int rotatedZ = sb.getRelZ();

                                switch (rotation) {
                                    case CLOCKWISE_90:
                                        rotatedX = -sb.getRelZ();
                                        rotatedZ = sb.getRelX();
                                        break;
                                    case CLOCKWISE_180:
                                        rotatedX = -sb.getRelX();
                                        rotatedZ = -sb.getRelZ();
                                        break;
                                    case COUNTERCLOCKWISE_90:
                                        rotatedX = sb.getRelZ();
                                        rotatedZ = -sb.getRelX();
                                        break;
                                    default: break;
                                }

                                // CHANGEMENT ICI : On ajoute +1 à l'axe Y (sb.getRelY() + 1)
                                // Le niveau 0 de la schématique (le four d'origine) se retrouve en Y+1 par rapport au sol
                                Location finalLoc = groundFurnaceLoc.clone().add(rotatedX, sb.getRelY() + 1, rotatedZ);

                                BlockData finalBlockData = Bukkit.createBlockData(sb.getBlockData());
                                finalBlockData.rotate(rotation); // Fait pivoter le bloc individuel

                                finalLoc.getBlock().setBlockData(finalBlockData, false);
                                index++;
                            }
                        }
                    }.runTaskTimer(plugin, 0L, 1L);
                });

            } catch (IOException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Échec de chargement de la schématique", e);
            }
        });
    }

    /** Orientation du four d'origine (0,0,0) d'une schématique (nord par défaut). */
    private static BlockFace anchorFacing(List<SavedBlock> blocks) {
        for (SavedBlock sb : blocks) {
            if (sb.getRelX() == 0 && sb.getRelY() == 0 && sb.getRelZ() == 0) {
                BlockData bd = Bukkit.createBlockData(sb.getBlockData());
                if (bd instanceof Directional d) return d.getFacing();
                break;
            }
        }
        return BlockFace.NORTH;
    }

    /** Position X/Z d'un bloc après rotation autour du four. */
    private static int[] rotate(SavedBlock sb, StructureRotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{-sb.getRelZ(), sb.getRelX()};
            case CLOCKWISE_180 -> new int[]{-sb.getRelX(), -sb.getRelZ()};
            case COUNTERCLOCKWISE_90 -> new int[]{sb.getRelZ(), -sb.getRelX()};
            default -> new int[]{sb.getRelX(), sb.getRelZ()};
        };
    }

    private StructureRotation calculateRotation(BlockFace snapshot, BlockFace target) {
        int snapshotIdx = getFaceIndex(snapshot);
        int targetIdx = getFaceIndex(target);
        int diff = (targetIdx - snapshotIdx + 4) % 4;

        switch (diff) {
            case 1: return StructureRotation.CLOCKWISE_90;
            case 2: return StructureRotation.CLOCKWISE_180;
            case 3: return StructureRotation.COUNTERCLOCKWISE_90;
            default: return StructureRotation.NONE;
        }
    }

    private int getFaceIndex(BlockFace face) {
        switch (face) {
            case EAST: return 1;
            case SOUTH: return 2;
            case WEST: return 3;
            default: return 0; // NORTH, et repli pour toute autre face
        }
    }
}