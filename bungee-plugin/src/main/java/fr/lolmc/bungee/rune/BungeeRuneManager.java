package fr.lolmc.bungee.rune;

import fr.lolmc.bungee.LolBungeePlugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class BungeeRuneManager {

    private final LolBungeePlugin plugin;
    private final File dataFile;
    private Configuration data;

    public BungeeRuneManager(LolBungeePlugin plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "runes_data.yml");
        load();
    }

    private void load() {
        try {
            if (!dataFile.exists()) dataFile.createNewFile();
            data = ConfigurationProvider.getProvider(YamlConfiguration.class).load(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Erreur chargement runes: " + e.getMessage());
            data = ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(new java.io.StringReader(""));
        }
    }

    private void save() {
        try {
            ConfigurationProvider.getProvider(YamlConfiguration.class).save(data, dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Erreur sauvegarde runes: " + e.getMessage());
        }
    }

    public void savePage(UUID uuid, String keystone, String minors, String spell1, String spell2) {
        String base = "players." + uuid;
        data.set(base + ".keystone", keystone);
        data.set(base + ".minors",   minors);
        data.set(base + ".spell1",   spell1);
        data.set(base + ".spell2",   spell2);
        save();
    }

    public Map<String, String> getPage(UUID uuid) {
        String base = "players." + uuid;
        Map<String, String> r = new LinkedHashMap<>();
        r.put("keystone", data.getString(base + ".keystone", "conqueror"));
        r.put("minors",   data.getString(base + ".minors",   ""));
        r.put("spell1",   data.getString(base + ".spell1",   "FLASH"));
        r.put("spell2",   data.getString(base + ".spell2",   "IGNITE"));
        return r;
    }
}
