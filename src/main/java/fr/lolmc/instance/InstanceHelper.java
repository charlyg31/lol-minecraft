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
}
