package fr.lolmc.instance;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.*;
import fr.lolmc.item.PassiveManager;
import org.bukkit.entity.Player;

/**
 * Utilitaire pour retrouver le bon manager selon le contexte
 * (instance ou mode singleton).
 *
 * Usage dans les listeners/managers :
 *   GameManager gm = InstanceHelper.gameManager(player);
 *   MinionManager mm = InstanceHelper.minionManager(player);
 */
public final class InstanceHelper {

    private InstanceHelper() {}

    public static GameInstance instanceOf(Player p) {
        return LolPlugin.getInstance().getInstanceManager().getInstanceOf(p);
    }

    /** Retrouve l'instance à partir du monde d'une entité — utile quand aucun joueur n'est
     *  disponible (ex. mort d'un monstre/sbire sans tueur identifié). */
    public static GameInstance instanceOf(org.bukkit.entity.Entity entity) {
        return LolPlugin.getInstance().getInstanceManager().getInstanceByWorldName(entity.getWorld().getName());
    }

    public static GameManager gameManager(org.bukkit.entity.Entity e) {
        GameInstance inst = instanceOf(e);
        return inst != null ? inst.getGameManager() : LolPlugin.getInstance().getGameManager();
    }

    public static MinionManager minionManager(org.bukkit.entity.Entity e) {
        GameInstance inst = instanceOf(e);
        return inst != null ? inst.getMinionManager() : LolPlugin.getInstance().getMinionManager();
    }

    public static JungleManager jungleManager(org.bukkit.entity.Entity e) {
        GameInstance inst = instanceOf(e);
        return inst != null ? inst.getJungleManager() : LolPlugin.getInstance().getJungleManager();
    }

    public static RewardManager rewardManager(org.bukkit.entity.Entity e) {
        GameInstance inst = instanceOf(e);
        return inst != null ? inst.getRewardManager() : LolPlugin.getInstance().getRewardManager();
    }

    public static AnnouncementManager announcementManager(org.bukkit.entity.Entity e) {
        GameInstance inst = instanceOf(e);
        return inst != null ? inst.getAnnouncementManager() : LolPlugin.getInstance().getAnnouncementManager();
    }

    public static GameManager gameManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getGameManager() : LolPlugin.getInstance().getGameManager();
    }

    public static MapManager mapManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getMapManager() : LolPlugin.getInstance().getMapManager();
    }

    public static MinionManager minionManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getMinionManager() : LolPlugin.getInstance().getMinionManager();
    }

    public static RewardManager rewardManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getRewardManager() : LolPlugin.getInstance().getRewardManager();
    }

    public static AnnouncementManager announcementManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getAnnouncementManager() : LolPlugin.getInstance().getAnnouncementManager();
    }

    public static TurretManager turretManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getTurretManager() : LolPlugin.getInstance().getTurretManager();
    }

    public static JungleManager jungleManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getJungleManager() : LolPlugin.getInstance().getJungleManager();
    }

    public static PassiveManager passiveManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getPassiveManager() : LolPlugin.getInstance().getPassiveManager();
    }

    public static BaseManager baseManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getBaseManager() : LolPlugin.getInstance().getBaseManager();
    }

    public static FogOfWarManager fogOfWarManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getFogManager() : LolPlugin.getInstance().getFogOfWarManager();
    }

    public static FeatManager featManager(Player p) {
        GameInstance inst = instanceOf(p);
        return inst != null ? inst.getFeatManager() : LolPlugin.getInstance().getFeatManager();
    }
}
