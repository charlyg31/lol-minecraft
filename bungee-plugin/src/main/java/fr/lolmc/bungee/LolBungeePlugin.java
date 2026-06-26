package fr.lolmc.bungee;

import fr.lolmc.bungee.bridge.BungeeMessageListener;
import fr.lolmc.bungee.party.BungeePartyManager;
import fr.lolmc.bungee.queue.BungeeQueueManager;
import fr.lolmc.bungee.role.BungeeRoleManager;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * LolMC-Bungee — Plugin proxy BungeeCord.
 *
 * Gère depuis le proxy :
 *  - File d'attente (queue) — tous les serveurs
 *  - Groupes (party) — cross-serveur
 *  - Runes + sorts d'invocateur — persistés dans /plugins/LolMC-Bungee/
 *  - Rôles — persistés dans /plugins/LolMC-Bungee/
 *  - Serveur d'origine — pour le retour après la partie
 *
 * Le menu GUI reste sur chaque serveur Spigot (plugin léger LolMC-Menu.jar).
 * Le proxy reçoit les actions du joueur via PluginMessage (canal lolmc:bridge).
 *
 * Installation :
 *   → LolMC-Bungee.jar dans /plugins/ du PROXY uniquement
 *   → LolMC-Menu.jar dans /plugins/ de CHAQUE serveur Spigot (survie, skyblock, etc.)
 *   → LolMC.jar dans /plugins/ du serveur de jeu uniquement
 */
public class LolBungeePlugin extends Plugin {

    private static LolBungeePlugin instance;
    private net.md_5.bungee.config.Configuration config;

    private BungeeQueueManager  queueManager;
    private BungeePartyManager  partyManager;
    private BungeeRoleManager   roleManager;
    private OriginTracker       originTracker;

    @Override
    public void onEnable() {
        instance = this;
        getDataFolder().mkdirs();
        loadConfig();

        // Init managers
        this.roleManager   = new BungeeRoleManager(this);
        this.partyManager  = new BungeePartyManager(this);
        this.queueManager  = new BungeeQueueManager(this, partyManager, roleManager);
        this.originTracker = new OriginTracker(this);

        // Commandes
        getProxy().getPluginManager().registerCommand(this, new LolCommand(this));

        // Listeners
        getProxy().getPluginManager().registerListener(this, new ServerSwitchListener(this, originTracker));
        getProxy().getPluginManager().registerListener(this, new BungeeMessageListener(this));

        // Canaux PluginMessage
        getProxy().registerChannel("lolmc:bridge");

        getLogger().info("LolMC-Bungee activé — file et groupes gérés depuis le proxy.");
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel("lolmc:bridge");
        getLogger().info("LolMC-Bungee désactivé.");
    }

    public static LolBungeePlugin getInstance() { return instance; }

    private void loadConfig() {
        java.io.File configFile = new java.io.File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try (java.io.InputStream in = getResourceAsStream("config.yml")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, configFile.toPath());
                } else {
                    configFile.createNewFile();
                }
            } catch (java.io.IOException e) {
                getLogger().warning("Erreur création config.yml: " + e.getMessage());
            }
        }
        try {
            config = net.md_5.bungee.config.ConfigurationProvider
                .getProvider(net.md_5.bungee.config.YamlConfiguration.class)
                .load(configFile);
        } catch (java.io.IOException e) {
            getLogger().warning("Erreur chargement config.yml: " + e.getMessage());
            config = net.md_5.bungee.config.ConfigurationProvider
                .getProvider(net.md_5.bungee.config.YamlConfiguration.class)
                .load(new java.io.StringReader(""));
        }
    }

    public net.md_5.bungee.config.Configuration getConfig() { return config; }

    public BungeeQueueManager  getQueueManager()  { return queueManager; }
    public BungeePartyManager  getPartyManager()  { return partyManager; }
    public BungeeRoleManager   getRoleManager()   { return roleManager; }
    public OriginTracker       getOriginTracker() { return originTracker; }
}
