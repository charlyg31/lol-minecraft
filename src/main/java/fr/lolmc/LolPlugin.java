package fr.lolmc;

import fr.lolmc.listener.AbilityListener;
import fr.lolmc.item.ItemRegistry;
import fr.lolmc.item.PassiveManager;
import fr.lolmc.item.HotbarManager;
import fr.lolmc.item.FlashManager;
import fr.lolmc.team.TeamManager;
import fr.lolmc.ward.WardManager;
import fr.lolmc.matchmaking.PartyManager;
import fr.lolmc.matchmaking.MatchmakingManager;
import fr.lolmc.listener.PartyCommand;
import fr.lolmc.item.consumable.ConsumableManager;
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
    private ConsumableManager consumableManager;
    private HotbarManager hotbarManager;
    private FlashManager flashManager;
    private TeamManager teamManager;
    private WardManager wardManager;
    private PartyManager partyManager;
    private MatchmakingManager matchmakingManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        championManager = new ChampionManager();
        flashManager = new FlashManager();
        hotbarManager = new HotbarManager();
        teamManager = new TeamManager();
        wardManager = new WardManager(teamManager);
        partyManager = new PartyManager();
        matchmakingManager = new MatchmakingManager(partyManager, teamManager);
        hudManager = new HUDManager(championManager);
        shopGUI = new ShopGUI();
        goldManager = new GoldManager();
        shopListener = new ShopListener(shopGUI, championManager, goldManager, hudManager);
        passiveManager = new PassiveManager(championManager, hudManager, shopListener);
        consumableManager = new ConsumableManager(championManager, hudManager);
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
        var teamCmd = getCommand("team");
        if (teamCmd != null) {
            var tc = new fr.lolmc.listener.TeamCommand(teamManager);
            teamCmd.setExecutor(tc);
            teamCmd.setTabCompleter(tc);
        }
        var partyCmd = new PartyCommand(partyManager, matchmakingManager);
        if (getCommand("party") != null) {
            getCommand("party").setExecutor(partyCmd);
            getCommand("party").setTabCompleter(partyCmd);
        }
        if (getCommand("queue") != null) {
            getCommand("queue").setExecutor(partyCmd);
            getCommand("queue").setTabCompleter(partyCmd);
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
    public ConsumableManager getConsumableManager() { return consumableManager; }
    public HotbarManager getHotbarManager()     { return hotbarManager; }
    public FlashManager getFlashManager()       { return flashManager; }
    public TeamManager getTeamManager()         { return teamManager; }
    public WardManager getWardManager()         { return wardManager; }
    public PartyManager getPartyManager()       { return partyManager; }
    public MatchmakingManager getMatchmakingManager() { return matchmakingManager; }
}
