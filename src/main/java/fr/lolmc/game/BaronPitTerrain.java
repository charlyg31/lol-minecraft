package fr.lolmc.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Terrain de la fosse du Baron selon sa forme (wiki) : Territorial ajoute un petit mur devant la
 * fosse, Omniscient creuse un long tunnel sur le côté, Chasseur ne change rien.
 *
 * La forme de la fosse dépend de la carte : elle est enregistrée en jeu, comme une différence de
 * blocs par rapport au terrain normal (jungle.yml, {@code baron-pit.<forme>}).
 *  1. /lola schem pos1 et pos2 autour de la fosse
 *  2. /lola baronpit <territorial|omniscient> start : photo du terrain normal
 *  3. construire la variante, puis /lola baronpit <forme> save : seuls les blocs modifiés sont
 *     gardés (avant / après) et le terrain normal est remis en place
 * En partie, la variante est posée à l'apparition du Baron et retirée en fin de partie (et au
 * début de la suivante, au cas où le serveur se serait arrêté entre-temps).
 */
public final class BaronPitTerrain {

    /** Forme du Baron (index de JungleManager) → clé de configuration. */
    static String formKey(int form) {
        return switch (form) { case 1 -> "territorial"; case 2 -> "omniscient"; default -> null; };
    }

    /** Un bloc modifié par une forme. */
    private record Change(int x, int y, int z, String before, String after) {
        String encode() { return x + "|" + y + "|" + z + "|" + before + "|" + after; }
        static Change decode(String s) {
            String[] p = s.split("\\|", 5);
            if (p.length < 5) return null;
            try {
                return new Change(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3], p[4]);
            } catch (NumberFormatException e) { return null; }
        }
    }

    private static final String ROOT = "baron-pit";

    private final Map<String, List<Change>> forms = new HashMap<>();
    /** Photos du terrain normal en cours d'enregistrement, par administrateur et par forme. */
    private final Map<String, Map<Location, String>> snapshots = new HashMap<>();

    void load(FileConfiguration config) {
        forms.clear();
        ConfigurationSection sec = config.getConfigurationSection(ROOT);
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            List<Change> list = new ArrayList<>();
            for (String line : sec.getStringList(key)) {
                Change c = Change.decode(line);
                if (c != null) list.add(c);
            }
            forms.put(key, list);
        }
    }

    public boolean has(String form) { return forms.containsKey(form) && !forms.get(form).isEmpty(); }

    // ── Enregistrement (commande admin) ─────────────────────────

    /** Photographie le terrain normal entre deux coins. Retourne le nombre de blocs. */
    public int start(UUID admin, String form, Location a, Location b) {
        Map<Location, String> snap = new HashMap<>();
        World w = a.getWorld();
        for (int x = Math.min(a.getBlockX(), b.getBlockX()); x <= Math.max(a.getBlockX(), b.getBlockX()); x++)
            for (int y = Math.min(a.getBlockY(), b.getBlockY()); y <= Math.max(a.getBlockY(), b.getBlockY()); y++)
                for (int z = Math.min(a.getBlockZ(), b.getBlockZ()); z <= Math.max(a.getBlockZ(), b.getBlockZ()); z++)
                    snap.put(new Location(w, x, y, z), w.getBlockAt(x, y, z).getBlockData().getAsString());
        snapshots.put(admin + ":" + form, snap);
        return snap.size();
    }

    /**
     * Compare le terrain actuel à la photo, enregistre les blocs modifiés et remet le terrain
     * normal. Retourne le nombre de blocs modifiés, ou -1 sans photo.
     */
    public int save(UUID admin, String form, FileConfiguration config) {
        Map<Location, String> snap = snapshots.remove(admin + ":" + form);
        if (snap == null) return -1;
        List<Change> list = new ArrayList<>();
        for (var e : snap.entrySet()) {
            Block block = e.getKey().getBlock();
            String now = block.getBlockData().getAsString();
            if (now.equals(e.getValue())) continue;
            list.add(new Change(block.getX(), block.getY(), block.getZ(), e.getValue(), now));
            block.setBlockData(Bukkit.createBlockData(e.getValue()), false);
        }
        forms.put(form, list);
        config.set(ROOT + "." + form, list.stream().map(Change::encode).toList());
        return list.size();
    }

    public void clear(String form, FileConfiguration config) {
        forms.remove(form);
        config.set(ROOT + "." + form, null);
    }

    // ── En partie ───────────────────────────────────────────────

    /** Pose la variante d'une forme ; les entités prises dans un nouveau bloc sont remontées. */
    public void apply(String form, World world) {
        List<Change> list = form == null ? null : forms.get(form);
        if (list == null || world == null) return;
        for (Change c : list) world.getBlockAt(c.x, c.y, c.z).setBlockData(Bukkit.createBlockData(c.after), false);
        for (LivingEntity e : world.getLivingEntities()) {
            Block feet = e.getLocation().getBlock();
            if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()) continue;
            if (!touches(list, feet)) continue;
            Location up = e.getLocation();
            for (int i = 0; i < 16 && !(up.getBlock().isPassable() && up.clone().add(0, 1, 0).getBlock().isPassable()); i++)
                up.add(0, 1, 0);
            e.teleport(up);
        }
    }

    private static boolean touches(List<Change> list, Block b) {
        for (Change c : list)
            if (c.x == b.getX() && c.z == b.getZ() && (c.y == b.getY() || c.y == b.getY() + 1)) return true;
        return false;
    }

    /** Remet le terrain normal pour toutes les formes enregistrées. */
    public void restore(World world) {
        if (world == null) return;
        for (List<Change> list : forms.values())
            for (Change c : list) {
                Block b = world.getBlockAt(c.x, c.y, c.z);
                if (!b.getBlockData().getAsString().equals(c.before))
                    b.setBlockData(Bukkit.createBlockData(c.before), false);
            }
    }

    /** Pose une variante hors partie (vérification), ou remet le terrain normal si form est null. */
    public void preview(String form, World world) {
        restore(world);
        if (form != null) apply(form, world);
    }
}
