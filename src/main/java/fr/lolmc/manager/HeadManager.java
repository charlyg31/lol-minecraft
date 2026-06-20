package fr.lolmc.manager;

import fr.lolmc.LolPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;

public class HeadManager {

    private final LolPlugin plugin;

    public HeadManager(LolPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Crée un player head avec la texture base64 du champion.
     * Utilise l'API Paper moderne (PlayerProfile / PlayerTextures),
     * sans dépendance NMS (com.mojang.authlib).
     */
    public ItemStack getChampionHead(String championId) {
        FileConfiguration config = plugin.getConfig();
        String texture = config.getString("heads." + championId, "");

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (texture.isEmpty() || texture.equals("PASTE_VALUE_HERE")) {
            return head; // tête par défaut
        }

        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;

        try {
            // La valeur base64 encode un JSON {"textures":{"SKIN":{"url":"..."}}}
            String decoded = new String(Base64.getDecoder().decode(texture));
            String url = extractUrl(decoded);
            if (url != null) {
                PlayerProfile profile = plugin.getServer().createPlayerProfile(UUID.randomUUID());
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create(url).toURL());
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur texture head " + championId + ": " + e.getMessage());
        }

        head.setItemMeta(meta);
        return head;
    }

    /** Extrait l'URL de skin du JSON décodé (sans librairie JSON externe). */
    private String extractUrl(String json) {
        int idx = json.indexOf("\"url\"");
        if (idx < 0) return null;
        int start = json.indexOf("http", idx);
        if (start < 0) return null;
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    public boolean hasTexture(String championId) {
        String val = plugin.getConfig().getString("heads." + championId, "PASTE_VALUE_HERE");
        return !val.equals("PASTE_VALUE_HERE") && !val.isEmpty();
    }
}
