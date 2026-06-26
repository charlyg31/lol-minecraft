package fr.lolmc.lobby;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * Sauvegarde le rôle préféré de chaque joueur (TOP/JUNGLE/MID/ADC/SUPPORT).
 * Persisté dans roles_data.yml dans le dossier du plugin.
 */
public class LobbyRoleManager {

    private final LobbyPlugin plugin;
    private final File dataFile;
    private FileConfiguration data;

    public LobbyRoleManager(LobbyPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "roles_data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.data = YamlConfiguration.loadConfiguration(dataFile);
    }

    /** Retourne le rôle d'un joueur (FILL par défaut). */
    public String getRole(UUID uuid) {
        return data.getString("players." + uuid + ".role", "FILL");
    }

    /** Sauvegarde le rôle d'un joueur. */
    public void setRole(UUID uuid, String role) {
        data.set("players." + uuid + ".role", role);
        save();
    }

    private void save() {
        try { data.save(dataFile); }
        catch (Exception e) { plugin.getLogger().warning("Erreur sauvegarde rôles: " + e.getMessage()); }
    }
}
