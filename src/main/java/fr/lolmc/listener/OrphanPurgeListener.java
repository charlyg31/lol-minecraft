package fr.lolmc.listener;

import fr.lolmc.game.MinionManager;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Supprime les sbires et modèles restés dans le monde après un arrêt serveur. */
public class OrphanPurgeListener implements Listener {
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent ev) {
        for (var e : ev.getEntities()) MinionManager.purgeIfOrphan(e);
    }
}
