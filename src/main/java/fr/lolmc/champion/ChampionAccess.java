package fr.lolmc.champion;

import fr.lolmc.LolPlugin;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Accès aux champions : un joueur ne joue que les champions qu'il possède.
 *
 * Posséder un champion = avoir {@code lolmc.champion.<id>} (ou {@code lolmc.champion.*}, qui les
 * donne tous ; les opérateurs les ont par défaut). Comme dans LoL, les champions de la rotation
 * gratuite ({@code champions.free-rotation} dans config.yml) sont jouables par tout le monde.
 */
public final class ChampionAccess {

    private ChampionAccess() {}

    public static String permission(String championId) {
        return "lolmc.champion." + championId.toLowerCase(Locale.ROOT);
    }

    /** Le joueur peut-il jouer ce champion ? */
    public static boolean canPlay(Player player, String championId) {
        if (player == null || championId == null) return false;
        return player.hasPermission(permission(championId)) || isFreeRotation(championId);
    }

    /** Le joueur possède-t-il ce champion (hors rotation gratuite) ? */
    public static boolean owns(Player player, String championId) {
        return player != null && championId != null && player.hasPermission(permission(championId));
    }

    public static boolean isFreeRotation(String championId) {
        for (String id : LolPlugin.getInstance().getConfig().getStringList("champions.free-rotation"))
            if (id.equalsIgnoreCase(championId)) return true;
        return false;
    }

    /** Le joueur peut-il jouer au moins un champion ? (sinon il ne peut pas entrer en file) */
    public static boolean canPlayAny(Player player) {
        for (var c : LolPlugin.getInstance().getChampionManager().getAllChampions())
            if (canPlay(player, c.getId())) return true;
        return false;
    }
}
