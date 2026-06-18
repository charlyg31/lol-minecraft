package fr.lolmc;

import fr.lolmc.listener.AbilityListener;
import fr.lolmc.listener.ChampionCommand;
import fr.lolmc.manager.ChampionManager;
import org.bukkit.plugin.java.JavaPlugin;

public class LolPlugin extends JavaPlugin {

    private static LolPlugin instance;
    private ChampionManager championManager;

    @Override
    public void onEnable() {
        instance = this;

        // Initialiser le manager
        championManager = new ChampionManager();

        // Enregistrer les listeners
        getServer().getPluginManager().registerEvents(
            new AbilityListener(championManager), this
        );

        // Enregistrer la commande
        var cmd = getCommand("champion");
        if (cmd != null) {
            var champCmd = new ChampionCommand(championManager);
            cmd.setExecutor(champCmd);
            cmd.setTabCompleter(champCmd);
        }

        getLogger().info("LoL MC activé — 20 champions chargés.");
    }

    @Override
    public void onDisable() {
        getLogger().info("LoL MC désactivé.");
    }

    public static LolPlugin getInstance() { return instance; }
    public ChampionManager getChampionManager() { return championManager; }
}
