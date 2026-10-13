package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Immobilise les joueurs tant que la partie n'a pas démarré (création de l'instance, sélection),
 * ainsi que les champions morts (spectateurs en attente de respawn, qui pouvaient sinon voler
 * partout et espionner l'équipe adverse).
 */
public class FreezeListener implements Listener {

    /** Champion mort dans une partie en cours. */
    private static boolean isDeadChampion(org.bukkit.entity.Player p) {
        if (p.getGameMode() != org.bukkit.GameMode.SPECTATOR) return false;
        if (!LolPlugin.getInstance().getChampionManager().hasChampion(p)) return false;
        return fr.lolmc.instance.InstanceHelper.gameManager(p).isGameRunning();
    }

    /** Un mort ne peut pas « entrer » dans un autre joueur ou une entité (vision adverse). */
    @EventHandler
    public void onSpectate(com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent e) {
        if (isDeadChampion(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        var csm = LolPlugin.getInstance().getChampSelectManager();
        boolean frozen = csm != null && csm.isFrozen(e.getPlayer().getUniqueId());
        if (!frozen && !isDeadChampion(e.getPlayer())) return;
        var from = e.getFrom();
        var to = e.getTo();
        // Position bloquée, la rotation de la tête reste libre
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            to.setX(from.getX());
            to.setY(from.getY());
            to.setZ(from.getZ());
            e.setTo(to);
        }
    }
}
