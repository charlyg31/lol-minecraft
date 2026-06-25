package fr.lolmc.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

/**
 * Sauvegarde et charge les pages de runes + sorts d'invocateur
 * pour chaque joueur dans un fichier YAML local.
 * Les données sont ensuite transmises au serveur de jeu via BridgeManager.
 */
public class LobbyRuneManager {

    private final LobbyPlugin plugin;
    private final File dataFile;
    private FileConfiguration data;

    public LobbyRuneManager(LobbyPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "runes_data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.data = YamlConfiguration.loadConfiguration(dataFile);
    }

    /** Sauvegarde la page de runes d'un joueur. */
    public void savePage(UUID uuid, String keystone, List<String> minors, String spell1, String spell2) {
        String base = "players." + uuid + ".";
        data.set(base + "keystone", keystone);
        data.set(base + "minors", minors);
        data.set(base + "spell1", spell1);
        data.set(base + "spell2", spell2);
        try { data.save(dataFile); } catch (Exception e) {
            plugin.getLogger().warning("Erreur sauvegarde runes: " + e.getMessage());
        }
    }

    /** Retourne les données de runes sous forme de Map (pour transmission au jeu). */
    public Map<String, String> getPageJson(UUID uuid) {
        String base = "players." + uuid + ".";
        Map<String, String> result = new LinkedHashMap<>();
        result.put("keystone", data.getString(base + "keystone", "conqueror"));
        List<?> minors = data.getList(base + "minors", List.of());
        result.put("minors", String.join(",", minors.stream().map(Object::toString).toList()));
        result.put("spell1", data.getString(base + "spell1", "FLASH"));
        result.put("spell2", data.getString(base + "spell2", "IGNITE"));
        return result;
    }
}
