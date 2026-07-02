package fr.lolmc.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import fr.lolmc.LolPlugin;
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
 * Change le skin d'un joueur pour celui de son champion/skin, SANS NMS ni LibsDisguises.
 *
 * Principe (100%% API Paper) :
 *  1. On édite la property "textures" du PlayerProfile (value + signature).
 *  2. On ré-applique le profil au joueur.
 *  3. On force les autres clients à recharger l'apparence via hidePlayer/showPlayer.
 *
 * Format skins.yml :
 *   skins:
 *     garen:              # skin de base du champion
 *       value: "..."
 *       signature: "..."
 *     garen_steel_legion: # skin spécifique (champId_skinId)
 *       value: "..."
 *       signature: "..."
 *
 * Les couples value/signature se génèrent sur https://mineskin.org
 * à partir d'un PNG 64×64 de skin Minecraft du champion.
 *
 * Ajout d'un skin :
 *   1. Prendre une capture du skin LoL en PNG 64×64 format Steve
 *   2. Uploader sur https://mineskin.org/generate
 *   3. Copier value et signature dans skins.yml
 *   4. /lola reload pour recharger sans redémarrer
 */
public class SkinManager {

    private record Skin(String value, String signature) {}

    // clé = "champId" (base) ou "champId_skinId" (skin spécifique)
    private final Map<String, Skin> skins = new HashMap<>();
    // Profil d'origine pour réinitialiser à la fin de partie
    private final Map<UUID, PlayerProfile> originalProfiles = new HashMap<>();

    public SkinManager() { reload(); }

    // ── Chargement ────────────────────────────────────────────────

    public void reload() {
        skins.clear();
        File f = new File(LolPlugin.getInstance().getDataFolder(), "skins.yml");
        if (!f.exists()) createDefaultFile(f);

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (!cfg.isConfigurationSection("skins")) {
            LolPlugin.getInstance().getLogger().warning("skins.yml : section \'skins\' manquante.");
            return;
        }
        for (String key : cfg.getConfigurationSection("skins").getKeys(false)) {
            String value = cfg.getString("skins." + key + ".value", "");
            String sig   = cfg.getString("skins." + key + ".signature", "");
            if (value != null && !value.isEmpty() && sig != null && !sig.isEmpty()) {
                skins.put(key.toLowerCase(), new Skin(value, sig));
            }
        }
        LolPlugin.getInstance().getLogger().info(
            "[SkinManager] " + skins.size() + " skin(s) chargé(s) depuis skins.yml");
    }

    // ── Application ───────────────────────────────────────────────

    /**
     * Applique le skin d'un champion + skin ID au joueur.
     * Ordre de résolution :
     *   1. "champId_skinId"  (ex: garen_steel_legion)
     *   2. "champId"         (skin de base du champion)
     *   3. no-op si rien → garde l'avatar Minecraft du joueur
     */
    public void applySkin(Player player, String champId, String skinId) {
        String key = (skinId == null || skinId.equals("base"))
            ? champId.toLowerCase()
            : champId.toLowerCase() + "_" + skinId.toLowerCase();

        Skin skin = skins.getOrDefault(key, skins.get(champId.toLowerCase()));
        if (skin == null) {
            LolPlugin.getInstance().getLogger().fine(
                "[SkinManager] Aucun skin pour " + key + " → avatar conservé.");
            return;
        }

        originalProfiles.putIfAbsent(player.getUniqueId(),
            player.getPlayerProfile().clone());

        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        profile.setProperty(new ProfileProperty("textures", skin.value, skin.signature));
        player.setPlayerProfile(profile);
        refreshAppearance(player);

        LolPlugin.getInstance().getLogger().info(
            "[SkinManager] " + player.getName() + " → skin '" + key + "'");
    }

    /** Compatibilité avec l'ancien SkinManager (skin de base seulement). */
    public void applyChampionSkin(Player player, fr.lolmc.champion.base.BaseChampion champion) {
        if (champion != null) applySkin(player, champion.getId(), "base");
    }

    /** Restaure le skin d'origine (fin de partie). */
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

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Force tous les clients à recharger l'apparence.
     * hide → (2 ticks) → show déclenche un nouvel envoi du player info + respawn entité.
     */
    private void refreshAppearance(Player player) {
        LolPlugin plugin = LolPlugin.getInstance();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(player)) viewer.hidePlayer(plugin, player);
        }
        new BukkitRunnable() {
            @Override public void run() {
                if (!player.isOnline()) return;
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (!viewer.equals(player)) viewer.showPlayer(plugin, player);
                }
            }
        }.runTaskLater(plugin, 2L);
    }

    private void createDefaultFile(File f) {
        LolPlugin.getInstance().getDataFolder().mkdirs();
        try {
            YamlConfiguration def = new YamlConfiguration();
            def.set("# Générer les skins sur https://mineskin.org", null);
            def.set("# Format : skins.<champId>.<value/signature>", null);
            def.set("# Pour un skin spécifique : skins.<champId>_<skinId>.<value/signature>", null);
            // Champions de base
            for (String id : new String[]{
                    "garen","darius","malphite","nasus",
                    "warwick","amumu","masteryi","leesin",
                    "annie","veigar","zed","yasuo",
                    "morgana","leona","blitzcrank","janna",
                    "ashe","sivir","jinx","missfortune"}) {
                def.set("skins." + id + ".value", "");
                def.set("skins." + id + ".signature", "");
            }
            // Exemples de skins spécifiques (à remplir)
            def.set("skins.garen_steel_legion.value", "");
            def.set("skins.garen_steel_legion.signature", "");
            def.set("skins.zed_project.value", "");
            def.set("skins.zed_project.signature", "");
            def.set("skins.jinx_arcane.value", "");
            def.set("skins.jinx_arcane.signature", "");
            def.save(f);
            LolPlugin.getInstance().getLogger().info("[SkinManager] skins.yml créé. Remplis les value/signature depuis mineskin.org");
        } catch (Exception ex) {
            LolPlugin.getInstance().getLogger().warning("[SkinManager] skins.yml : " + ex.getMessage());
        }
    }
}
