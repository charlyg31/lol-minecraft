package fr.lolmc.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Change le skin d'un joueur pour celui de son champion, SANS NMS ni LibsDisguises.
 *
 * Principe (100% API Paper, robuste cross-version) :
 *  1. On édite la property "textures" du PlayerProfile (value + signature).
 *  2. On ré-applique le profil au joueur.
 *  3. On force les autres clients à recharger l'apparence via hidePlayer/showPlayer.
 *
 * Le joueur reste son propre avatar Minecraft (proportions Steve) → raccord parfait,
 * zéro désync, hitbox normale. Convient aux champions humanoïdes (la majorité).
 *
 * Les couples value/signature se génèrent sur https://mineskin.org (API) à partir
 * d'un PNG de skin de champion, puis se collent dans skins.yml :
 *
 *   skins:
 *     garen:
 *       value: "ewogICJ0aW1lc3RhbXAi..."
 *       signature: "Xzr8...=="
 *     darius:
 *       value: "..."
 *       signature: "..."
 */
public class SkinManager {

    private static final class Skin {
        final String value, signature;
        Skin(String v, String s) { this.value = v; this.signature = s; }
    }

    private final Map<String, Skin> skins = new HashMap<>();
    // Profil d'origine pour pouvoir réinitialiser à la fin de partie
    private final Map<UUID, PlayerProfile> originalProfiles = new HashMap<>();

    public SkinManager() { reload(); }

    /** Charge skins.yml (créé avec des entrées vides si absent). */
    public void reload() {
        skins.clear();
        File f = new File(LolPlugin.getInstance().getDataFolder(), "skins.yml");
        if (!f.exists()) {
            LolPlugin.getInstance().getDataFolder().mkdirs();
            try {
                YamlConfiguration def = new YamlConfiguration();
                // gabarit pour les 20 champions ; à remplir via mineskin.org
                for (String id : new String[]{
                        "garen","darius","malphite","nasus",
                        "warwick","amumu","masteryi","leesin",
                        "annie","veigar","zed","yasuo",
                        "morgana","leona","blitzcrank","janna",
                        "ashe","sivir","jinx","missfortune"}) {
                    def.set("skins." + id + ".value", "");
                    def.set("skins." + id + ".signature", "");
                }
                def.save(f);
            } catch (Exception ex) {
                LolPlugin.getInstance().getLogger().warning("skins.yml : " + ex.getMessage());
            }
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (cfg.isConfigurationSection("skins")) {
            for (String id : cfg.getConfigurationSection("skins").getKeys(false)) {
                String value = cfg.getString("skins." + id + ".value", "");
                String sig   = cfg.getString("skins." + id + ".signature", "");
                if (value != null && !value.isEmpty() && sig != null && !sig.isEmpty()) {
                    skins.put(id.toLowerCase(), new Skin(value, sig));
                }
            }
        }
        LolPlugin.getInstance().getLogger().info("SkinManager : " + skins.size() + " skins chargés.");
    }

    /** Applique le skin du champion du joueur (no-op si pas de skin configuré). */
    public void applyChampionSkin(Player player, BaseChampion champion) {
        if (champion == null) return;
        Skin skin = skins.get(champion.getId().toLowerCase());
        if (skin == null) return; // pas de skin pour ce champion → on garde l'avatar du joueur

        // Mémoriser le profil d'origine une seule fois
        originalProfiles.putIfAbsent(player.getUniqueId(), player.getPlayerProfile().clone());

        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        profile.setProperty(new ProfileProperty("textures", skin.value, skin.signature));
        player.setPlayerProfile(profile);

        refreshAppearance(player);
    }

    /** Restaure le skin d'origine du joueur (fin de partie). */
    public void resetSkin(Player player) {
        PlayerProfile original = originalProfiles.remove(player.getUniqueId());
        if (original != null) {
            player.setPlayerProfile(original);
            refreshAppearance(player);
        }
    }

    public void resetAll() {
        for (UUID id : new HashMap<>(originalProfiles).keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) resetSkin(p);
        }
        originalProfiles.clear();
    }

    /**
     * Force tous les clients à recharger l'apparence du joueur.
     * hide → (1 tick) → show déclenche un nouvel envoi du player info + respawn entité,
     * donc le nouveau skin s'affiche sans reconnexion.
     */
    private void refreshAppearance(Player player) {
        LolPlugin plugin = LolPlugin.getInstance();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(player)) viewer.hidePlayer(plugin, player);
        }
        new BukkitRunnable() {
            @Override public void run() {
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (!viewer.equals(player) && player.isOnline()) viewer.showPlayer(plugin, player);
                }
            }
        }.runTaskLater(plugin, 2L);
    }
}
