package fr.lolmc.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.List;

/** /queue join|leave|status */
public class LobbyQueueCommand implements CommandExecutor, TabCompleter {

    private final LobbyPlugin plugin;
    private final LobbyQueueManager qm;
    public LobbyQueueCommand(LobbyPlugin p, LobbyQueueManager qm) { this.plugin=p; this.qm=qm; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        String sub = args.length > 0 ? args[0].toLowerCase() : "join";
        switch (sub) {
            case "leave" -> qm.leave(p);
            case "status" -> p.sendMessage(Component.text(
                "File: " + qm.getQueueSize() + " joueurs", NamedTextColor.YELLOW));
            default -> qm.join(p); // join par défaut
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        return List.of("join","leave","status");
    }
}
