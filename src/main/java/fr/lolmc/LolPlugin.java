package fr.lolmc;

import fr.lolmc.listener.AbilityListener;
import fr.lolmc.listener.ChampionCommand;
import fr.lolmc.listener.GUIListener;
import fr.lolmc.manager.ChampionGUI;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HeadManager;
import org.bukkit.plugin.java.JavaPlugin;

public class LolPlugin extends JavaPlugin {

    private static LolPlugin instance;
    private ChampionManager championManager;
    private HeadManager headManager;
    private ChampionGUI championGUI;
    private GUIListener guiListener;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        championManager = new ChampionManager();
        headManager     = new HeadManager(this);
        championGUI     = new ChampionGUI(championManager, headManager);
        guiListener     = new GUIListener(championGUI, championManager, headManager);

        getServer().getPluginManager().registerEvents(new AbilityListener(championManager), this);
        getServer().getPluginManager().registerEvents(guiListener, this);

        var cmd = getCommand("champion");
        if (cmd != null) {
            var champCmd = new ChampionCommand(championManager, championGUI);
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
    public HeadManager getHeadManager()         { return headManager; }
    public ChampionGUI getChampionGUI()         { return championGUI; }
    public GUIListener getGUIListener()         { return guiListener; }
}
