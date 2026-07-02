package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import io.papermc.paper.event.player.AsyncChatEvent; // MODIFICATION : Nouvel événement moderne de Paper
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer; // AJOUT : Pour convertir le Component en String
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;

/**
 * Interception du chat pour :
 *  - "/t <message>" → chat d'équipe
 *  - "/a <message>" → chat global (alias all)
 */
public class ChatListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent e) { // MODIFICATION : Utilisation de l'API moderne
        Player p = e.getPlayer();

        // MODIFICATION : Extraction du texte brut à partir du composant de chat moderne
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());

        // /t → chat d'équipe
        if (msg.startsWith("/t ") || msg.startsWith("/team chat ")) {
            e.setCancelled(true);
            String text = msg.startsWith("/t ") ? msg.substring(3) : msg.substring(11);
            org.bukkit.Bukkit.getScheduler().runTask(LolPlugin.getInstance(), () -> {
                var teamCmd = LolPlugin.getInstance().getCommand("team");
                if (teamCmd != null) {
                    var exec = teamCmd.getExecutor();
                    if (exec instanceof fr.lolmc.listener.TeamCommand tc)
                        tc.sendTeamChat(p, text);
                }
            });
            return;
        }

        // /a → chat global (tous les joueurs en partie)
        if (msg.startsWith("/a ") || msg.startsWith("/all ")) {
            e.setCancelled(true);
            String text = msg.startsWith("/a ") ? msg.substring(3) : msg.substring(5);
            org.bukkit.Bukkit.getScheduler().runTask(LolPlugin.getInstance(), () -> {
                String formatted = "§7[Tous] §f" + p.getName() + "§7: " + text;
                for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (fr.lolmc.util.WorldContext.isInGameWorld(online))
                        online.sendMessage(net.kyori.adventure.text.Component.text(formatted));
                }
            });
            return;
        }

        // Message normal : envoyer uniquement à l'équipe
        var tm = LolPlugin.getInstance().getTeamManager();
        if (tm != null && tm.hasTeam(p) && fr.lolmc.util.WorldContext.isInGameWorld(p)) {
            e.setCancelled(true);
            String text2 = msg;
            org.bukkit.Bukkit.getScheduler().runTask(LolPlugin.getInstance(), () -> {
                var teamCmd = LolPlugin.getInstance().getCommand("team");
                if (teamCmd != null) {
                    var exec = teamCmd.getExecutor();
                    if (exec instanceof fr.lolmc.listener.TeamCommand tc)
                        tc.sendTeamChat(p, text2);
                }
            });
        }
    }
}