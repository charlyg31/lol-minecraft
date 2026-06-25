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
import fr.lolmc.listener.LolCommand;
import fr.lolmc.manager.SchematicManager;
import fr.lolmc.game.MapManager;
import fr.lolmc.game.TurretManager;
import fr.lolmc.game.MinionManager;
import fr.lolmc.game.RoadManager;
import fr.lolmc.game.RewardManager;
import fr.lolmc.game.JungleManager;
import fr.lolmc.game.BushManager;
import fr.lolmc.game.MonsterAbilities;
import fr.lolmc.game.GameManager;
import fr.lolmc.game.BaseManager;
import fr.lolmc.game.FogOfWarManager;
import fr.lolmc.game.ShopNpcManager;
import fr.lolmc.game.AutoAttackManager;
import fr.lolmc.game.AnnouncementManager;
import fr.lolmc.game.SummonerSpellManager;
import fr.lolmc.game.ChunkLoaderManager; // AJOUT : Import du gestionnaire de chunks
import fr.lolmc.rune.RuneManager;
import fr.lolmc.game.ChampSelectManager;
import fr.lolmc.game.ChampSelectGUI;
import fr.lolmc.rune.RuneGUI;
import fr.lolmc.game.RoleQueueManager;
import fr.lolmc.game.PreGameGUI;
import fr.lolmc.game.MatchScoreboard;
import fr.lolmc.stats.persistence.DatabaseManager;
import fr.lolmc.stats.persistence.RankedManager;
import fr.lolmc.stats.persistence.ApiServer;
import fr.lolmc.listener.EntityDeathListener;
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
    private fr.lolmc.game.CCManager ccManager;
    private ShopListener shopListener;
    private PassiveManager passiveManager;
    private ConsumableManager consumableManager;
    private HotbarManager hotbarManager;
    private FlashManager flashManager;
    private TeamManager teamManager;
    private WardManager wardManager;
    private PartyManager partyManager;
    private MatchmakingManager matchmakingManager;
    private SchematicManager schematicManager;
    private MapManager mapManager;
    private TurretManager turretManager;
    private MinionManager minionManager;
    private RoadManager roadManager;
    private RewardManager rewardManager;
    private JungleManager jungleManager;
    private BushManager bushManager;
    private MonsterAbilities monsterAbilities;
    private GameManager gameManager;
    private BaseManager baseManager;
    private FogOfWarManager fogOfWarManager;
    private ShopNpcManager shopNpcManager;
    private AutoAttackManager autoAttackManager;
    private AnnouncementManager announcementManager;
    private SummonerSpellManager summonerSpellManager;
    private ChunkLoaderManager chunkLoaderManager; // AJOUT : Variable d'instance
    private RuneManager runeManager;
    private ChampSelectManager champSelectManager;
    private ChampSelectGUI champSelectGUI;
    private RuneGUI runeGUI;
    private RoleQueueManager roleQueueManager;
    private PreGameGUI preGameGUI;
    private MatchScoreboard matchScoreboard;
    private DatabaseManager databaseManager;
    private RankedManager rankedManager;
    private ApiServer apiServer;
    private AbilityListener abilityListener;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResource("champions.yml", false);
        fr.lolmc.util.Balance.load();
        fr.lolmc.util.DebugLogger.init();

        initManagersAndListeners();
        registerCommands();

        // AJOUT : Démarrage de la tâche de chargement forcé des chunks (toutes les 20 ticks = 1 seconde)
        this.chunkLoaderManager = new ChunkLoaderManager();
        this.chunkLoaderManager.runTaskTimer(this, 0L, 20L);

        getLogger().info("LoL MC activé — 20 champions + boutique chargés.");
    }

    /** Cree tous les managers (ordre de dependance preserve) et enregistre les ecouteurs lies. */
    private void initManagersAndListeners() {
        championManager = new ChampionManager();
        flashManager = new FlashManager();
        hotbarManager = new HotbarManager();
        teamManager = new TeamManager();
        wardManager = new WardManager(teamManager);
        partyManager = new PartyManager();
        matchmakingManager = new MatchmakingManager(partyManager, teamManager);

        schematicManager = new SchematicManager(this);

        mapManager = new MapManager(schematicManager);
        minionManager = new MinionManager(mapManager);
        turretManager = new TurretManager(mapManager, championManager, teamManager);
        roadManager = new RoadManager();
        roadManager.applyToMinionManager();
        rewardManager = new RewardManager(championManager, goldManager);
        jungleManager = new JungleManager();
        bushManager = new BushManager(teamManager);
        monsterAbilities = new MonsterAbilities();
        databaseManager = new DatabaseManager();
        databaseManager.init();
        rankedManager = new RankedManager(databaseManager);
        apiServer = new ApiServer(databaseManager);
        apiServer.start();
        gameManager = new GameManager();
        baseManager = new BaseManager();
        fogOfWarManager = new FogOfWarManager(teamManager);
        shopNpcManager = new ShopNpcManager();
        getServer().getPluginManager().registerEvents(shopNpcManager, this);
        autoAttackManager = new AutoAttackManager();
        announcementManager = new AnnouncementManager();
        summonerSpellManager = new SummonerSpellManager();
        runeManager = new RuneManager();
        champSelectManager = new ChampSelectManager();
        champSelectGUI = new ChampSelectGUI();
        getServer().getPluginManager().registerEvents(champSelectGUI, this);
        runeGUI = new RuneGUI();
        getServer().getPluginManager().registerEvents(runeGUI, this);
        roleQueueManager = new RoleQueueManager();
        preGameGUI = new PreGameGUI();
        getServer().getPluginManager().registerEvents(preGameGUI, this);
        matchScoreboard = new MatchScoreboard();
        getServer().getPluginManager().registerEvents(new fr.lolmc.listener.MonsterPassiveListener(), this);
        getServer().getPluginManager().registerEvents(bushManager, this);
        hudManager = new HUDManager(championManager);
        shopGUI = new ShopGUI();
        goldManager = new GoldManager();
        ccManager = new fr.lolmc.game.CCManager();
        shopListener = new ShopListener(shopGUI, championManager, goldManager, hudManager);
        passiveManager = new PassiveManager(championManager, hudManager, shopListener);
        consumableManager = new ConsumableManager(championManager, hudManager);

        ItemRegistry.all();
        headManager     = new HeadManager(this);
        championGUI     = new ChampionGUI(championManager, headManager);
        guiListener     = new GUIListener(championGUI, championManager, headManager, hudManager);

        abilityListener = new AbilityListener(championManager);
        getServer().getPluginManager().registerEvents(abilityListener, this);
        getServer().getPluginManager().registerEvents(new HealthListener(championManager, hudManager), this);
        getServer().getPluginManager().registerEvents(shopListener, this);
        getServer().getPluginManager().registerEvents(guiListener, this);
    }

    /** Branche les commandes (/champion, /shop, /lol, ...) et les derniers ecouteurs. */
    private void registerCommands() {
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
        var lolCmd = new LolCommand(mapManager, roadManager);
        if (getCommand("lol") != null) {
            getCommand("lol").setExecutor(lolCmd);
            getCommand("lol").setTabCompleter(lolCmd);
        }
        getServer().getPluginManager().registerEvents(lolCmd, this);
        var playerCmds = new fr.lolmc.listener.PlayerCommands();
        if (getCommand("recall") != null) getCommand("recall").setExecutor(playerCmds);
        if (getCommand("ping") != null) {
            getCommand("ping").setExecutor(playerCmds);
            getCommand("ping").setTabCompleter(playerCmds);
        }
        getServer().getPluginManager().registerEvents(
                new fr.lolmc.listener.StructureDamageListener(mapManager, championManager, teamManager), this);
        getServer().getPluginManager().registerEvents(
                new EntityDeathListener(championManager, rewardManager), this);
    }

    @Override
    public void onDisable() {
        // AJOUT : Nettoyage forcé de tous les chunks bloqués en mémoire avant l'arrêt
        if (this.chunkLoaderManager != null) {
            this.chunkLoaderManager.clearAllForcedChunks();
        }
        if (apiServer != null) apiServer.stop();
        if (databaseManager != null) databaseManager.close();
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
    public fr.lolmc.game.CCManager getCCManager()   { return ccManager; }
    public ShopListener getShopListener()       { return shopListener; }
    public PassiveManager getPassiveManager()   { return passiveManager; }
    public ConsumableManager getConsumableManager() { return consumableManager; }
    public HotbarManager getHotbarManager()     { return hotbarManager; }
    public FlashManager getFlashManager()       { return flashManager; }
    public TeamManager getTeamManager()         { return teamManager; }
    public WardManager getWardManager()         { return wardManager; }
    public PartyManager getPartyManager()       { return partyManager; }
    public MatchmakingManager getMatchmakingManager() { return matchmakingManager; }
    public SchematicManager getSchematicManager() { return schematicManager; }
    public MapManager getMapManager()           { return mapManager; }
    public TurretManager getTurretManager()     { return turretManager; }
    public MinionManager getMinionManager()     { return minionManager; }
    public RoadManager getRoadManager()         { return roadManager; }
    public RewardManager getRewardManager()     { return rewardManager; }
    public JungleManager getJungleManager()     { return jungleManager; }
    public BushManager getBushManager()         { return bushManager; }
    public MonsterAbilities getMonsterAbilities() { return monsterAbilities; }
    public GameManager getGameManager()         { return gameManager; }
    public BaseManager getBaseManager()         { return baseManager; }
    public FogOfWarManager getFogOfWarManager() { return fogOfWarManager; }
    public ShopNpcManager getShopNpcManager()   { return shopNpcManager; }
    public AutoAttackManager getAutoAttackManager() { return autoAttackManager; }
    public AnnouncementManager getAnnouncementManager() { return announcementManager; }
    public SummonerSpellManager getSummonerSpellManager() { return summonerSpellManager; }
    public ChunkLoaderManager getChunkLoaderManager() { return chunkLoaderManager; } // AJOUT : Getter associé
    public RuneManager getRuneManager()         { return runeManager; }
    public ChampSelectManager getChampSelectManager() { return champSelectManager; }
    public ChampSelectGUI getChampSelectGUI()   { return champSelectGUI; }
    public RuneGUI getRuneGUI()                 { return runeGUI; }
    public RoleQueueManager getRoleQueueManager() { return roleQueueManager; }
    public PreGameGUI getPreGameGUI()           { return preGameGUI; }
    public MatchScoreboard getMatchScoreboard() { return matchScoreboard; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public RankedManager getRankedManager()     { return rankedManager; }

    public AbilityListener getAbilityListener() { return abilityListener; }
}