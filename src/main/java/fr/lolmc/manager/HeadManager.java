package fr.lolmc.manager;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import fr.lolmc.LolPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Field;
import java.util.UUID;

public class HeadManager {

    private final LolPlugin plugin;

    public HeadManager(LolPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Crée un player head avec la texture base64 du champion.
     * La valeur base64 vient du config.yml.
     */
    public ItemStack getChampionHead(String championId) {
        FileConfiguration config = plugin.getConfig();
        String texture = config.getString("heads." + championId, "");

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (texture.isEmpty() || texture.equals("PASTE_VALUE_HERE")) {
            // Fallback : tête par défaut (Steve)
            return head;
        }

        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;

        try {
            GameProfile profile = new GameProfile(UUID.randomUUID(), null);
            profile.getProperties().put("textures", new Property("textures", texture));

            Field profileField = meta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(meta, profile);
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur texture head " + championId + ": " + e.getMessage());
        }

        head.setItemMeta(meta);
        return head;
    }

    /**
     * Vérifie si la texture est configurée pour ce champion.
     */
    public boolean hasTexture(String championId) {
        String val = plugin.getConfig().getString("heads." + championId, "PASTE_VALUE_HERE");
        return !val.equals("PASTE_VALUE_HERE") && !val.isEmpty();
    }
}
