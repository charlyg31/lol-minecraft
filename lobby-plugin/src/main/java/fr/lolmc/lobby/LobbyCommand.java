package fr.lolmc.lobby;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.List;

/**
 * /lol (alias /ll, /lollobby, /runes) — ouvre le menu principal.
 */
public class LobbyCommand implements CommandExecutor, TabCompleter {

    private final LobbyPlugin plugin;
    public LobbyCommand(LobbyPlugin p) { this.plugin = p; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cCommande réservée aux joueurs.");
            return true;
        }
        LobbyMainMenu.openMain(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        return List.of();
    }
}
