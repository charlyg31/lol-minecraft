package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.entity.Player;

/**
 * Interception du chat pour :
 *  - "/t <message>" → chat d'équipe
 *  - "/a <message>" → chat global (alias all)
 */
public class ChatListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage();

        if (msg.startsWith("/t ") || msg.startsWith("/team chat ")) {
            e.setCancelled(true);
            String text = msg.startsWith("/t ") ? msg.substring(3) : msg.substring(11);
            // Appeler sur le thread principal
            org.bukkit.Bukkit.getScheduler().runTask(LolPlugin.getInstance(), () -> {
                var teamCmd = LolPlugin.getInstance().getCommand("team");
                if (teamCmd != null) {
                    var exec = teamCmd.getExecutor();
                    if (exec instanceof fr.lolmc.listener.TeamCommand tc)
                        tc.sendTeamChat(p, text);
                }
            });
        }
    }
}
