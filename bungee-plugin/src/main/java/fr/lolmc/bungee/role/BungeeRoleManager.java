package fr.lolmc.bungee.role;

import fr.lolmc.bungee.LolBungeePlugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.util.List;
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

    /** Retourne les rôles souhaités (liste, ex: ["TOP","ADC"]). */
    public List<String> getRoles(UUID uuid) {
        String raw = data.getString("players." + uuid + ".roles", "");
        if (raw.isEmpty()) return List.of("TOP","JUNGLE","MID","ADC","SUPPORT"); // FILL
        return List.of(raw.split(","));
    }

    public void setRoles(UUID uuid, List<String> roles) {
        data.set("players." + uuid + ".roles", String.join(",", roles));
        save();
    }
}
