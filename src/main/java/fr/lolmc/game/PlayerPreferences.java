package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RunePage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Préférences d'avant-partie d'un joueur, sauvegardées et réutilisées :
 *  - Rôles préférés (2 minimum) parmi top/jungle/mid/adc/support
 *  - Page de runes pré-configurée
 *  - Sorts d'invocateur pré-choisis
 *
 * Persisté dans preferences.yml pour être conservé entre les sessions.
 */
public class PlayerPreferences {

    public enum Role { TOP, JUNGLE, MID, ADC, SUPPORT }

    /** Préférences d'un seul joueur. */
    public static class Prefs {
        public final Set<Role> preferredRoles = new LinkedHashSet<>();
        public RunePage runePage = RunePage.defaultPage();
        public String spell1 = "FLASH";
        public String spell2 = "IGNITE";
    }

    private final Map<UUID, Prefs> prefs = new HashMap<>();
    private final File file;
    private FileConfiguration config;

    public PlayerPreferences() {
        this.file = new File(LolPlugin.getInstance().getDataFolder(), "preferences.yml");
        if (!file.exists()) {
            try { file.createNewFile(); } catch (Exception ignored) {}
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        load();
    }

    public Prefs get(UUID uuid) {
        return prefs.computeIfAbsent(uuid, u -> new Prefs());
    }

    // ── Rôles ─────────────────────────────────────────────────────

    public void toggleRole(UUID uuid, Role role) {
        Prefs p = get(uuid);
        if (p.preferredRoles.contains(role)) {
            p.preferredRoles.remove(role);
        } else {
            p.preferredRoles.add(role);
        }
        save(uuid);
    }

    public boolean hasEnoughRoles(UUID uuid) {
        return get(uuid).preferredRoles.size() >= 2;
    }

    public Set<Role> getRoles(UUID uuid) {
        return get(uuid).preferredRoles;
    }

    // ── Runes ─────────────────────────────────────────────────────

    public void setRunePage(UUID uuid, RunePage page) {
        get(uuid).runePage = page;
        save(uuid);
    }

    public RunePage getRunePage(UUID uuid) {
        return get(uuid).runePage;
    }

    // ── Sorts ─────────────────────────────────────────────────────

    public void setSpells(UUID uuid, String spell1, String spell2) {
        Prefs p = get(uuid);
        p.spell1 = spell1;
        p.spell2 = spell2;
        save(uuid);
    }

    // ══════════════════════════════════════════════════════════════
    // PERSISTANCE
    // ══════════════════════════════════════════════════════════════

    private void save(UUID uuid) {
        Prefs p = get(uuid);
        String path = "players." + uuid + ".";
        List<String> roleNames = new ArrayList<>();
        for (Role r : p.preferredRoles) roleNames.add(r.name());
        config.set(path + "roles", roleNames);
        config.set(path + "spell1", p.spell1);
        config.set(path + "spell2", p.spell2);
        // Runes : keystone + voies (sérialisation simple)
        if (p.runePage != null) {
            config.set(path + "rune.primary", p.runePage.primaryPath != null ? p.runePage.primaryPath.name() : null);
            config.set(path + "rune.secondary", p.runePage.secondaryPath != null ? p.runePage.secondaryPath.name() : null);
            config.set(path + "rune.keystone", p.runePage.keystone);
            config.set(path + "rune.primaryRunes", new ArrayList<>(p.runePage.primaryRunes));
            config.set(path + "rune.secondaryRunes", new ArrayList<>(p.runePage.secondaryRunes));
        }
        try { config.save(file); }
        catch (Exception e) { LolPlugin.getInstance().getLogger().warning("Erreur preferences.yml: " + e.getMessage()); }
    }

    private void load() {
        var section = config.getConfigurationSection("players");
        if (section == null) return;
        for (String uuidStr : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                Prefs p = new Prefs();
                String path = "players." + uuidStr + ".";
                for (String r : config.getStringList(path + "roles")) {
                    try { p.preferredRoles.add(Role.valueOf(r)); } catch (Exception ignored) {}
                }
                p.spell1 = config.getString(path + "spell1", "FLASH");
                p.spell2 = config.getString(path + "spell2", "IGNITE");
                // Runes
                RunePage page = new RunePage();
                String prim = config.getString(path + "rune.primary");
                String sec = config.getString(path + "rune.secondary");
                if (prim != null) try { page.primaryPath = fr.lolmc.rune.RuneRegistry.Path.valueOf(prim); } catch (Exception ignored) {}
                if (sec != null) try { page.secondaryPath = fr.lolmc.rune.RuneRegistry.Path.valueOf(sec); } catch (Exception ignored) {}
                page.keystone = config.getString(path + "rune.keystone");
                page.primaryRunes.addAll(config.getStringList(path + "rune.primaryRunes"));
                page.secondaryRunes.addAll(config.getStringList(path + "rune.secondaryRunes"));
                if (page.keystone != null) p.runePage = page;
                prefs.put(uuid, p);
            } catch (Exception ignored) {}
        }
    }
}
