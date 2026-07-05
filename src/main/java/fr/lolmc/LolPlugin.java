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
import fr.lolmc.game.FeatManager;
import fr.lolmc.game.DeathRecapManager;
import fr.lolmc.game.TabScoreboardManager;
import fr.lolmc.game.ForfeitManager;
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
    private fr.lolmc.game.AutoAttackManager autoAttackManager;
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
    private DeathRecapManager deathRecapManager;
    private TabScoreboardManager tabScoreboardManager;
    private ForfeitManager forfeitManager;
    private fr.lolmc.game.AggroManager aggroManager;
    private FeatManager featManager;
    private RewardManager rewardManager;
    private JungleManager jungleManager;
    private BushManager bushManager;
    private MonsterAbilities monsterAbilities;
    private GameManager gameManager;
    private BaseManager baseManager;
    private FogOfWarManager fogOfWarManager;
    private ShopNpcManager shopNpcManager;
    private fr.lolmc.listener.StructureDamageListener structureDamageListener;
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
    private fr.lolmc.bridge.BridgeManager bridgeManager;
    private fr.lolmc.ability.AbilityPreview abilityPreview;
    private fr.lolmc.instance.InstanceManager instanceManager;
    private fr.lolmc.game.PlantManager plantManager;
    private fr.lolmc.game.MinimapManager minimapManager;
    private fr.lolmc.manager.SkinManager skinManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResource("champions.yml", false);
        fr.lolmc.util.Balance.load();
        fr.lolmc.util.DebugLogger.init();

        initManagersAndListeners();
        registerCommands();

        this.abilityPreview   = new fr.lolmc.ability.AbilityPreview();
        this.instanceManager  = new fr.lolmc.instance.InstanceManager(this);
        this.plantManager     = new fr.lolmc.game.PlantManager();
        this.minimapManager   = new fr.lolmc.game.MinimapManager();
        this.skinManager      = new fr.lolmc.manager.SkinManager();
        // Bridge cross-serveur (BungeeCord)
        this.bridgeManager = new fr.lolmc.bridge.BridgeManager(this);

        getLogger().info("LoL MC activé — 20 champions + boutique chargés.");
    }

    /**
     * Bootstrap complet du plugin.
     * Ordre de dépendance strict :
     *   Core → Money → Map/Game → Shop → Queue → Listeners → Commands
     */
    private void initManagersAndListeners() {
        initCoreManagers();
//         initGameManagers();
//         initShopManagers();
//         initQueueManagers();
//         registerListenersInternal();
    }

    private void initCoreManagers() {
        // ── Core (sans dépendances) ────────────────────────────────────
        championManager = new ChampionManager();
        flashManager = new FlashManager();
        hotbarManager = new HotbarManager();
        teamManager = new TeamManager();
        wardManager = new WardManager(teamManager);
        partyManager = new PartyManager();
        matchmakingManager = new MatchmakingManager(partyManager, teamManager);

        // ── Money (dépend de rien) ─────────────────────────────────────
        shopGUI   = new ShopGUI();
        goldManager = new GoldManager();
        ccManager = new fr.lolmc.game.CCManager();

        chunkLoaderManager = new ChunkLoaderManager();
        chunkLoaderManager.runTaskTimer(this, 0L, 20L);
        // ── Map/Game (dépend de goldManager) ──────────────────────────
        schematicManager = new SchematicManager(this);
        mapManager = new MapManager(schematicManager);
        minionManager = new MinionManager(mapManager);
        turretManager = new TurretManager(mapManager, championManager, teamManager);
        roadManager = new RoadManager();
        roadManager.applyToMinionManager();
        deathRecapManager = new DeathRecapManager();
        tabScoreboardManager = new TabScoreboardManager();
        forfeitManager = new ForfeitManager();
        aggroManager = new fr.lolmc.game.AggroManager();
        featManager = new FeatManager();
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
        // Appliquer le ratio unités LoL → blocs depuis la config
        applyAAScaleFromConfig();
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
        // ── Shop/Passifs (dépend de goldManager + championManager) ────
        hudManager = new HUDManager(championManager);
        shopListener = new ShopListener(shopGUI, championManager, goldManager, hudManager);
        passiveManager = new PassiveManager(championManager, hudManager, shopListener);
        consumableManager = new ConsumableManager(championManager, hudManager);

        ItemRegistry.all();
        headManager     = new HeadManager(this);
        championGUI     = new ChampionGUI(championManager, headManager);
        guiListener     = new GUIListener(championGUI, championManager, headManager, hudManager);

        // ── Listeners (dépend de tout) ─────────────────────────────────
        abilityListener = new AbilityListener(championManager);
        getServer().getPluginManager().registerEvents(abilityListener, this);
        getServer().getPluginManager().registerEvents(new HealthListener(championManager, hudManager), this);
        getServer().getPluginManager().registerEvents(shopListener, this);
        getServer().getPluginManager().registerEvents(guiListener, this);
    }

    /** Branche les commandes (/champion, /shop, /lol, ...) et les derniers ecouteurs. */
    private void registerCommands() {
        // Seules deux commandes : /lol (joueurs) et /lola (admins)
        var lolCmd = new LolCommand(mapManager, roadManager);
        if (getCommand("l") != null) {
            getCommand("l").setExecutor(lolCmd);
            getCommand("l").setTabCompleter(lolCmd);
        }
        if (getCommand("lola") != null) {
            getCommand("lola").setExecutor(lolCmd);
            getCommand("lola").setTabCompleter(lolCmd);
        }
        getServer().getPluginManager().registerEvents(lolCmd, this);
                structureDamageListener = new fr.lolmc.listener.StructureDamageListener(mapManager, championManager, teamManager);
                getServer().getPluginManager().registerEvents(structureDamageListener, this);
        getServer().getPluginManager().registerEvents(new fr.lolmc.listener.ChatListener(), this);
        getServer().getPluginManager().registerEvents(
                new EntityDeathListener(championManager, rewardManager), this);
    }

    @Override
    public void onDisable() {
        // ── 1. Arrêt du runtime de partie (si en cours) ─────────────────────
        if (gameManager != null && gameManager.isRunning()) gameManager.stopGame();
        if (minionManager != null) minionManager.stopWaves();
        if (jungleManager != null) jungleManager.stopJungle();
        if (turretManager != null && turretManager instanceof fr.lolmc.game.TurretManager tm) tm.stopTasks();
        if (passiveManager != null) passiveManager.stopTasks();
        if (gameManager != null) gameManager.stopSystems();
        // ── 2. Nettoyage des états par joueur ────────────────────────────────
        if (passiveManager != null) passiveManager.cleanupAll();
        if (shopListener != null) shopListener.cleanupAll();
        fr.lolmc.util.ChampionStateReset.resetAll();
        // ── 3. Chunks + services externes ───────────────────────────────────
        if (chunkLoaderManager != null) chunkLoaderManager.clearAllForcedChunks();
        if (apiServer != null) apiServer.stop();
        if (databaseManager != null) databaseManager.close();
        if (instanceManager != null) instanceManager.shutdownAll();
        if (abilityPreview != null) abilityPreview.stopAll();
        if (bridgeManager != null) bridgeManager.disable();
        fr.lolmc.util.DebugLogger.close();
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
    public DeathRecapManager getDeathRecapManager()  { return deathRecapManager; }
    public TabScoreboardManager getTabScoreboard()    { return tabScoreboardManager; }
    public ForfeitManager getForfeitManager()          { return forfeitManager; }
    public fr.lolmc.game.AggroManager getAggroManager() { return aggroManager; }
    public FeatManager getFeatManager()          { return featManager; }
    public RewardManager getRewardManager()     { return rewardManager; }
    public JungleManager getJungleManager()     { return jungleManager; }
    public BushManager getBushManager()         { return bushManager; }
    public MonsterAbilities getMonsterAbilities() { return monsterAbilities; }
    public GameManager getGameManager()         { return gameManager; }
    public BaseManager getBaseManager()         { return baseManager; }
    public FogOfWarManager getFogOfWarManager() { return fogOfWarManager; }
    public ShopNpcManager getShopNpcManager()   { return shopNpcManager; }
    /**
     * Portées AA LoL en unités (patch 26).
     * Converties en blocs avec le ratio scale.lol-units-per-block de config.yml.
     */
    private static final java.util.Map<String, Double> LOL_AA_RANGES = java.util.Map.ofEntries(
        java.util.Map.entry("garen",       175.0),
        java.util.Map.entry("darius",      175.0),
        java.util.Map.entry("malphite",    150.0),
        java.util.Map.entry("nasus",       175.0),
        java.util.Map.entry("warwick",     175.0),
        java.util.Map.entry("amumu",       150.0),
        java.util.Map.entry("masteryi",    175.0),
        java.util.Map.entry("leesin",      175.0),
        java.util.Map.entry("annie",       625.0),
        java.util.Map.entry("veigar",      700.0),
        java.util.Map.entry("zed",         175.0),
        java.util.Map.entry("yasuo",       175.0),
        java.util.Map.entry("morgana",     900.0),
        java.util.Map.entry("leona",       175.0),
        java.util.Map.entry("blitzcrank",  175.0),
        java.util.Map.entry("janna",       550.0),
        java.util.Map.entry("ashe",        600.0),
        java.util.Map.entry("sivir",       500.0),
        java.util.Map.entry("jinx",        525.0),
        java.util.Map.entry("missfortune", 650.0)
    );

    public void applyAAScaleFromConfig() {
        double ratio = getConfig().getDouble("scale.lol-units-per-block", 65.0);
        for (var entry : LOL_AA_RANGES.entrySet()) {
            var champ = championManager.getPrototype(entry.getKey());
            if (champ != null) {
                double blocks = Math.round(entry.getValue() / ratio * 10.0) / 10.0;
                champ.setAutoAttackRange(blocks);
            }
        }
        getLogger().info("[LolMC] Portées AA recalculées (1 bloc = " + (int)ratio + " unités LoL)");
    }

    public AutoAttackManager getAutoAttackManager() { return autoAttackManager; }
    public fr.lolmc.listener.StructureDamageListener getStructureDamageListener() { return structureDamageListener; }
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

    public fr.lolmc.bridge.BridgeManager getBridgeManager() { return bridgeManager; }

    public fr.lolmc.ability.AbilityPreview getAbilityPreview() { return abilityPreview; }
    public fr.lolmc.instance.InstanceManager getInstanceManager() { return instanceManager; }
    public fr.lolmc.game.PlantManager getPlantManager()           { return plantManager; }
    public fr.lolmc.game.MinimapManager getMinimapManager()       { return minimapManager; }
    public fr.lolmc.manager.SkinManager getSkinManager()          { return skinManager; }
}
