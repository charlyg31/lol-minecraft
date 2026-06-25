package fr.lolmc.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.List;

/** Commande /lol — ouvre le menu principal du lobby. */
public class LobbyCommand implements CommandExecutor, TabCompleter {

    private final LobbyPlugin plugin;
    public LobbyCommand(LobbyPlugin p) { this.plugin = p; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        p.sendMessage(Component.text("=== LoL Lobby ===", NamedTextColor.GOLD));
        p.sendMessage(Component.text("/runes — Configurer tes runes", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("/party — Gérer ton groupe", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("/queue — Rejoindre la file", NamedTextColor.GREEN));
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) { return List.of(); }
}
