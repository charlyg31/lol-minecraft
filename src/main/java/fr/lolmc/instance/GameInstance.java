package fr.lolmc.instance;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.*;
import fr.lolmc.item.PassiveManager;
import fr.lolmc.manager.SchematicManager;
import fr.lolmc.team.TeamManager;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Une instance de partie complètement isolée.
 *
 * Chaque GameInstance possède ses propres managers (GameManager, MinionManager,
 * JungleManager, etc.) et opère sur son propre monde Minecraft dédié.
 *
 * Convention de nommage des mondes :
 *   Template  : lolmc_template   (configuré une fois, jamais utilisé en jeu)
 *   Instance  : lolmc_game_1, lolmc_game_2, lolmc_game_3…
 *
 * Isolation garantie :
 *   - Chaque instance a son propre World → WorldContext filtre par monde
 *   - Les managers à état (stacks, timers, entités) sont instanciés séparément
 *   - InstanceManager.getInstanceOf(player) retrouve l'instance d'un joueur
 */
public class GameInstance {

    public enum State { CREATING, READY, RUNNING, FINISHED }

    private final int    id;
    private final String worldName;
    private final World  world;
    private State state = State.CREATING;

    // ── Managers propres à cette instance ─────────────────────────────────
    private final GameManager         gameManager;
    private final MapManager          mapManager;
    private final MinionManager       minionManager;
    private final TurretManager       turretManager;
    private final JungleManager       jungleManager;
    private final BaseManager         baseManager;
    private final FogOfWarManager     fogManager;
    private final RewardManager       rewardManager;
    private final PassiveManager      passiveManager;
    private final AnnouncementManager announcementManager;

    // Participants + équipes propres à cette instance
    private final Set<UUID>                      participants = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TeamManager.Team>    teams        = new ConcurrentHashMap<>();

    public GameInstance(int id, World world) {
        this.id        = id;
        this.worldName = world.getName();
        this.world     = world;

        SchematicManager schematics = new SchematicManager(LolPlugin.getInstance());
        LolPlugin lp = LolPlugin.getInstance();

        this.mapManager          = new MapManager(schematics, world);
        this.gameManager         = new GameManager();
        this.gameManager.setGameInstance(this); // lier à cette instance
        this.rewardManager       = new RewardManager(lp.getChampionManager(), lp.getGoldManager());
        this.minionManager       = new MinionManager(mapManager);
        this.turretManager       = new TurretManager(mapManager, lp.getChampionManager(), lp.getTeamManager());
        this.jungleManager       = new JungleManager(world);
        this.baseManager         = new BaseManager(world);
        this.fogManager          = new FogOfWarManager(lp.getTeamManager(), world);
        this.passiveManager      = new PassiveManager(lp.getChampionManager(), lp.getHUDManager(), lp.getShopListener());
        this.announcementManager = new AnnouncementManager();
    }

    // ── Joueurs ───────────────────────────────────────────────────────────

    public void addPlayer(Player player, TeamManager.Team team) {
        participants.add(player.getUniqueId());
        teams.put(player.getUniqueId(), team);
    }

    public boolean hasPlayer(UUID uuid) { return participants.contains(uuid); }
    public boolean hasPlayer(Player p)  { return hasPlayer(p.getUniqueId()); }

    public TeamManager.Team getTeam(UUID uuid) { return teams.get(uuid); }

    public Set<UUID> getParticipants() { return Collections.unmodifiableSet(participants); }

    public List<Player> getOnlinePlayers() {
        List<Player> result = new ArrayList<>();
        for (UUID uid : participants) {
            Player p = org.bukkit.Bukkit.getPlayer(uid);
            if (p != null && p.isOnline() && world.equals(p.getWorld())) result.add(p);
        }
        return result;
    }

    // ── Cycle de vie ──────────────────────────────────────────────────────

    public void start() {
        state = State.RUNNING;
        mapManager.resetAllStructures();
        gameManager.startGame();
        minionManager.startWaves();
        jungleManager.startJungle();
        baseManager.startHealRingTask();
        fogManager.startTask();
        passiveManager.startTasks();
    }

    public void stop() {
        state = State.FINISHED;
        gameManager.stopGame();
        minionManager.stopWaves();
        jungleManager.stopJungle();
        fogManager.stopTask();
        passiveManager.stopTasks();
        passiveManager.cleanupAll();
        participants.clear();
        teams.clear();
    }

    // ── Accesseurs ────────────────────────────────────────────────────────

    public int    getId()        { return id; }
    public String getWorldName() { return worldName; }
    public World  getWorld()     { return world; }
    public State  getState()     { return state; }
    public void   setState(State s) { this.state = s; }

    public GameManager         getGameManager()          { return gameManager; }
    public MapManager          getMapManager()           { return mapManager; }
    public MinionManager       getMinionManager()        { return minionManager; }
    public TurretManager       getTurretManager()        { return turretManager; }
    public JungleManager       getJungleManager()        { return jungleManager; }
    public BaseManager         getBaseManager()          { return baseManager; }
    public FogOfWarManager     getFogManager()           { return fogManager; }
    public RewardManager       getRewardManager()        { return rewardManager; }
    public PassiveManager      getPassiveManager()       { return passiveManager; }
    public AnnouncementManager getAnnouncementManager()  { return announcementManager; }
}
