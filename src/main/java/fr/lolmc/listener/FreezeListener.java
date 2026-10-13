package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/** Immobilise les joueurs tant que la partie n'a pas démarré (création de l'instance, sélection). */
public class FreezeListener implements Listener {
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        var csm = LolPlugin.getInstance().getChampSelectManager();
        if (csm == null || !csm.isFrozen(e.getPlayer().getUniqueId())) return;
        var from = e.getFrom();
        var to = e.getTo();
        if (to == null) return;
        // Position bloquée, la rotation de la tête reste libre
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            to.setX(from.getX());
            to.setY(from.getY());
            to.setZ(from.getZ());
            e.setTo(to);
        }
    }
}
