package fr.lolmc.game;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import fr.lolmc.LolPlugin;
import org.bukkit.Location;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Charge les schématiques depuis plugins/lol-minecraft/schematics/
 * et les colle dans le monde via FAWE.
 *
 * Détection automatique des phases par nom de fichier :
 *   TurretBlue.schem      → seuil 100
 *   TurretBlue75.schem    → seuil 75
 *   TurretBlue50.schem    → seuil 50
 *   TurretBlue25.schem    → seuil 25
 */
public class SchematicManager {

    private final File schematicsFolder;
    // Cache des clipboards chargés : nom → Clipboard
    private final Map<String, Clipboard> cache = new HashMap<>();

    // Pattern pour extraire le seuil : "TurretBlue75" → base="TurretBlue", seuil=75
    private static final Pattern PHASE_PATTERN = Pattern.compile("^(.*?)(\\d{1,3})?$");

    public SchematicManager() {
        this.schematicsFolder = new File(LolPlugin.getInstance().getDataFolder(), "schematics");
        if (!schematicsFolder.exists()) schematicsFolder.mkdirs();
    }

    /**
     * Trouve toutes les phases pour un nom de base donné (ex: "TurretBlue").
     * Retourne la liste triée par seuil décroissant : [100, 75, 50, 25].
     */
    public List<GameStructure.Phase> findPhases(String baseName) {
        List<GameStructure.Phase> phases = new ArrayList<>();
        File[] files = schematicsFolder.listFiles((dir, name) ->
                name.endsWith(".schem") || name.endsWith(".schematic"));
        if (files == null) return phases;

        for (File f : files) {
            String name = f.getName().replaceFirst("\\.(schem|schematic)$", "");
            // Le nom doit commencer par baseName
            if (!name.startsWith(baseName)) continue;
            String suffix = name.substring(baseName.length());

            if (suffix.isEmpty()) {
                // Schématique de base = 100%
                phases.add(new GameStructure.Phase(100, name));
            } else if (suffix.matches("\\d{1,3}")) {
                // Suffixe numérique = seuil
                int threshold = Integer.parseInt(suffix);
                phases.add(new GameStructure.Phase(threshold, name));
            }
            // sinon (ex: "TurretBlueFoo") on ignore : ce n'est pas une phase de cette structure
        }

        // Trier par seuil décroissant (100 d'abord)
        phases.sort((a, b) -> b.threshold() - a.threshold());
        return phases;
    }

    /**
     * Charge un clipboard depuis un fichier schématique (avec cache).
     */
    public Clipboard loadSchematic(String name) {
        if (cache.containsKey(name)) return cache.get(name);

        File file = new File(schematicsFolder, name + ".schem");
        if (!file.exists()) file = new File(schematicsFolder, name + ".schematic");
        if (!file.exists()) {
            LolPlugin.getInstance().getLogger().warning("Schématique introuvable: " + name);
            return null;
        }

        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            LolPlugin.getInstance().getLogger().warning("Format schématique inconnu: " + name);
            return null;
        }

        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            anchorOnFurnace(clipboard); // four = origine + pivot, puis retiré
            cache.put(name, clipboard);
            return clipboard;
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("Erreur lecture schématique " + name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Cherche le four le plus bas de la schématique, le définit comme point
     * d'origine (= ancrage et pivot de rotation), puis le remplace par de l'air
     * (le four est un simple marqueur, il n'apparaît pas dans la structure finie).
     * Si aucun four : on garde l'origine d'origine de WorldEdit (repli).
     */
    private void anchorOnFurnace(Clipboard clipboard) {
        BlockVector3 furnace = null;
        for (BlockVector3 pos : clipboard.getRegion()) {
            if (clipboard.getBlock(pos).getBlockType() == BlockTypes.FURNACE) {
                if (furnace == null || pos.getY() < furnace.getY()) furnace = pos;
            }
        }
        if (furnace == null) {
            LolPlugin.getInstance().getLogger().warning(
                    "Schématique sans four marqueur : origine WorldEdit par défaut utilisée.");
            return;
        }
        clipboard.setOrigin(furnace);
    }

    /**
     * Colle une schématique à un emplacement (centrée sur le point d'origine du clipboard).
     */
    public void pasteSchematic(String name, Location location) {
        pasteSchematic(name, location, 0);
    }

    /**
     * Colle une schématique avec une rotation (0/90/180/270°).
     * WorldEdit fait tourner correctement les blocs orientés (escaliers, troncs...).
     */
    public void pasteSchematic(String name, Location location, int angleDegrees) {
        Clipboard clipboard = loadSchematic(name);
        if (clipboard == null) return;

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(
                BukkitAdapter.adapt(location.getWorld()))) {
            ClipboardHolder holder = new ClipboardHolder(clipboard);
            if (angleDegrees != 0) {
                holder.setTransform(new AffineTransform().translate(-0.5, 0, -0.5).rotateY(angleDegrees).translate(0.5, 0, 0.5));
            }
            Operation operation = holder
                    .createPaste(editSession)
                    .to(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()))
                    .ignoreAirBlocks(false)
                    .build();
            Operations.complete(operation);
            editSession.setBlock(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()), BlockTypes.AIR.getDefaultState());
            editSession.setBlock(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()), BlockTypes.AIR.getDefaultState());
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("Erreur collage schématique " + name + ": " + e.getMessage());
        }
    }

    /**
     * Liste les noms de base disponibles (sans les suffixes de phase).
     * Ex: si on a TurretBlue, TurretBlue75 → retourne juste "TurretBlue".
     */
    public Set<String> listBaseNames() {
        Set<String> bases = new HashSet<>();
        File[] files = schematicsFolder.listFiles((dir, name) ->
                name.endsWith(".schem") || name.endsWith(".schematic"));
        if (files == null) return bases;
        for (File f : files) {
            String name = f.getName().replaceFirst("\\.(schem|schematic)$", "");
            // Retirer le suffixe numérique éventuel
            String base = name.replaceFirst("\\d{1,3}$", "");
            bases.add(base);
        }
        return bases;
    }

    public boolean schematicExists(String baseName) {
        File[] files = schematicsFolder.listFiles((dir, name) -> name.startsWith(baseName));
        return files != null && files.length > 0;
    }

    public File getSchematicsFolder() { return schematicsFolder; }
}
