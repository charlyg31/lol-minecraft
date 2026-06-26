package fr.lolmc.lobby;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * LolMC Lobby Plugin — plugin léger pour le serveur lobby/hub.
 *
 * Fonctionnalités :
 *  - Menu pré-game GUI : rôle, runes, sorts d'invocateur, groupe, file
 *  - File d'attente cross-serveur (BungeeCord)
 *  - Synchronisation des données vers le serveur de jeu
 */
public class LobbyPlugin extends JavaPlugin {

    private static LobbyPlugin instance;

    private LobbyBridge        bridge;
    private LobbyPartyManager  partyManager;
    private LobbyQueueManager  queueManager;
    private LobbyRuneManager   runeManager;
    private LobbyRoleManager   roleManager;
    private LobbyDataManager   dataManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getDataFolder().mkdirs();

        // Init dans l'ordre de dépendance
        this.dataManager  = new LobbyDataManager(this);
        this.roleManager  = new LobbyRoleManager(this);
        this.runeManager  = new LobbyRuneManager(this);
        this.partyManager = new LobbyPartyManager(this);
        this.queueManager = new LobbyQueueManager(this, partyManager, null);
        this.bridge       = new LobbyBridge(this, partyManager, queueManager, runeManager);
        this.queueManager.setBridge(bridge);

        // Listeners
        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);
        getServer().getPluginManager().registerEvents(new LobbyGUIListener(this), this);

        // Commandes
        registerCmd("lollobby", new LobbyCommand(this));
        registerCmd("runes",    new LobbyCommand(this));
        registerCmd("party",    new LobbyPartyCommand(this, partyManager));
        registerCmd("queue",    new LobbyQueueCommand(this, queueManager));

        getLogger().info("LolMC Lobby activé → game-server: "
            + getConfig().getString("game-server", "?"));
    }

    @Override
    public void onDisable() {
        if (bridge != null)      bridge.disable();
        if (dataManager != null) dataManager.close();
        getLogger().info("LolMC Lobby désactivé.");
    }

    private void registerCmd(String name, org.bukkit.command.CommandExecutor exec) {
        var cmd = getCommand(name);
        if (cmd == null) return;
        cmd.setExecutor(exec);
        if (exec instanceof org.bukkit.command.TabCompleter tc)
            cmd.setTabCompleter(tc);
    }

    // ── Getters ────────────────────────────────────────────────────────────
    public static LobbyPlugin  getInstance()    { return instance; }
    public LobbyBridge         getBridge()      { return bridge; }
    public LobbyPartyManager   getPartyManager(){ return partyManager; }
    public LobbyQueueManager   getQueueManager(){ return queueManager; }
    public LobbyRuneManager    getRuneManager() { return runeManager; }
    public LobbyRoleManager    getRoleManager() { return roleManager; }
    public LobbyDataManager    getDataManager() { return dataManager; }
}
