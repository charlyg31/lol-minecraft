package fr.lolmc.instance;

import fr.lolmc.LolPlugin;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/**
 * Version instance-aware de WorldContext.
 *
 * Remplace WorldContext.getGamePlayers() dans les managers qui ont
 * besoin de filtrer par instance plutôt que par monde fixe.
 *
 * Usage dans un manager d'instance :
 *
 *   // Au lieu de WorldContext.getGamePlayers() :
 *   InstanceWorldContext.getPlayersIn(instance)
 *
 * WorldContext reste utilisé pour les systèmes non-instanciés
 * (lobby, commandes admin, etc.).
 */
public final class InstanceWorldContext {

    private InstanceWorldContext() {}

    /** Joueurs en ligne dans le monde d'une instance donnée. */
    public static Collection<Player> getPlayersIn(GameInstance instance) {
        if (instance == null || instance.getWorld() == null)
            return List.of();
        return instance.getWorld().getPlayers();
    }

    /** Vrai si le joueur est dans le monde de cette instance. */
    public static boolean isInInstance(Player player, GameInstance instance) {
        if (instance == null) return false;
        return instance.getWorld().equals(player.getWorld());
    }

    /**
     * Retourne l'instance dont fait partie le joueur.
     * Null si le joueur n'est dans aucune instance active.
     */
    public static GameInstance getInstanceOf(Player player) {
        return LolPlugin.getInstance().getInstanceManager().getInstanceOf(player);
    }

    /**
     * Vrai si le joueur est dans n'importe quel monde d'instance LoL.
     * Utile pour bloquer les actions hors-partie.
     */
    public static boolean isInAnyInstance(Player player) {
        return getInstanceOf(player) != null;
    }

    /**
     * Vrai si le joueur est dans le monde template (configuration admin).
     */
    public static boolean isInTemplateWorld(Player player) {
        String template = LolPlugin.getInstance().getConfig()
            .getString("world.template", "lolmc_template");
        World w = player.getWorld();
        return w != null && w.getName().equals(template);
    }
}
