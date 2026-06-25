package fr.lolmc.lobby;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * LolMC Lobby Plugin — plugin léger pour le serveur lobby/hub.
 *
 * Fonctionnalités :
 *  - Configuration des runes (GUI identique au serveur de jeu)
 *  - Gestion des groupes (party)
 *  - File d'attente cross-serveur
 *  - Envoi des données vers le serveur de jeu via BungeeCord
 *
 * Installation :
 *  1. Mettre ce .jar dans /plugins du serveur LOBBY
 *  2. Configurer config.yml (game-server = nom BungeeCord du serveur LoL)
 *  3. BungeeCord doit avoir "bungeecord: true" dans spigot.yml des deux serveurs
 */
public class LobbyPlugin extends JavaPlugin {

    private static LobbyPlugin instance;
    private LobbyBridge bridge;
    private LobbyPartyManager partyManager;
    private LobbyQueueManager queueManager;
    private LobbyRuneManager runeManager;
    private LobbyDataManager dataManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Init dans l'ordre de dépendance
        this.dataManager  = new LobbyDataManager(this);
        this.partyManager = new LobbyPartyManager(this);
        this.runeManager  = new LobbyRuneManager(this);
        this.queueManager = new LobbyQueueManager(this, partyManager, bridge);
        this.bridge       = new LobbyBridge(this, partyManager, queueManager, runeManager);
        this.queueManager.setBridge(bridge);

        // Enregistrer les listeners et commandes
        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);
        getServer().getPluginManager().registerEvents(new LobbyGUIListener(this), this);

        registerCommand("lollobby", new LobbyCommand(this));
        registerCommand("runes",    new LobbyCommand(this));
        registerCommand("party",    new LobbyPartyCommand(this, partyManager));
        registerCommand("queue",    new LobbyQueueCommand(this, queueManager));

        getLogger().info("LolMC Lobby activé → serveur de jeu: "
            + getConfig().getString("game-server", "?"));
    }

    @Override
    public void onDisable() {
        if (bridge != null) bridge.disable();
        if (dataManager != null) dataManager.close();
        getLogger().info("LolMC Lobby désactivé.");
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor exec) {
        var cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(exec);
            if (exec instanceof org.bukkit.command.TabCompleter tc)
                cmd.setTabCompleter(tc);
        }
    }

    public static LobbyPlugin getInstance() { return instance; }
    public LobbyBridge getBridge()         { return bridge; }
    public LobbyPartyManager getPartyManager() { return partyManager; }
    public LobbyQueueManager getQueueManager() { return queueManager; }
    public LobbyRuneManager getRuneManager()   { return runeManager; }
    public LobbyDataManager getDataManager()   { return dataManager; }
}
