package fr.lolmc;

import fr.lolmc.listener.AbilityListener;
import fr.lolmc.item.ItemRegistry;
import fr.lolmc.item.PassiveManager;
import fr.lolmc.listener.ShopCommand;
import fr.lolmc.listener.ShopListener;
import fr.lolmc.shop.GoldManager;
import fr.lolmc.shop.ShopGUI;
import fr.lolmc.listener.HealthListener;
import fr.lolmc.listener.ChampionCommand;
import fr.lolmc.listener.GUIListener;
import fr.lolmc.manager.ChampionGUI;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HeadManager;
import org.bukkit.plugin.java.JavaPlugin;

public class LolPlugin extends JavaPlugin {

    private static LolPlugin instance;
    private ChampionManager championManager;
    private HeadManager headManager;
    private ChampionGUI championGUI;
    private GUIListener guiListener;
    private HUDManager hudManager;
    private ShopGUI shopGUI;
    private GoldManager goldManager;
    private ShopListener shopListener;
    private PassiveManager passiveManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        championManager = new ChampionManager();
        hudManager = new HUDManager(championManager);
        shopGUI = new ShopGUI();
        goldManager = new GoldManager();
        shopListener = new ShopListener(shopGUI, championManager, goldManager, hudManager);
        passiveManager = new PassiveManager(championManager, hudManager, shopListener);
        // Charger le registre d'items
        ItemRegistry.all(); // force le static init
        headManager     = new HeadManager(this);
        championGUI     = new ChampionGUI(championManager, headManager);
        guiListener     = new GUIListener(championGUI, championManager, headManager, hudManager);

        getServer().getPluginManager().registerEvents(new AbilityListener(championManager), this);
        getServer().getPluginManager().registerEvents(new HealthListener(championManager, hudManager), this);
        getServer().getPluginManager().registerEvents(shopListener, this);
        getServer().getPluginManager().registerEvents(guiListener, this);

        var cmd = getCommand("champion");
        if (cmd != null) {
            var champCmd = new ChampionCommand(championManager, championGUI);
            cmd.setExecutor(champCmd);
            cmd.setTabCompleter(champCmd);
        }

        var shopCmd = getCommand("shop");
        if (shopCmd != null) {
            var sc = new ShopCommand(shopGUI, championManager, goldManager);
            shopCmd.setExecutor(sc);
            shopCmd.setTabCompleter(sc);
        }
        getLogger().info("LoL MC activé — 20 champions + boutique chargés.");
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
    public HUDManager getHUDManager()           { return hudManager; }
    public ShopGUI getShopGUI()                 { return shopGUI; }
    public GoldManager getGoldManager()         { return goldManager; }
    public ShopListener getShopListener()       { return shopListener; }
    public PassiveManager getPassiveManager()   { return passiveManager; }
}
