package fr.lolmc.bungee.role;

import fr.lolmc.bungee.LolBungeePlugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class BungeeRoleManager {

    private final LolBungeePlugin plugin;
    private final File dataFile;
    private Configuration data;

    public BungeeRoleManager(LolBungeePlugin plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "roles_data.yml");
        load();
    }

    private void load() {
        try {
            if (!dataFile.exists()) dataFile.createNewFile();
            data = ConfigurationProvider.getProvider(YamlConfiguration.class).load(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Erreur chargement roles: " + e.getMessage());
            data = ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(new java.io.StringReader(""));
        }
    }

    private void save() {
        try {
            ConfigurationProvider.getProvider(YamlConfiguration.class).save(data, dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Erreur sauvegarde roles: " + e.getMessage());
        }
    }

    public String getRole(UUID uuid) {
        return data.getString("players." + uuid + ".role", "FILL");
    }

    public void setRole(UUID uuid, String role) {
        data.set("players." + uuid + ".role", role);
        save();
    }
}
