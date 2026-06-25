package fr.lolmc.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.List;

/** /party invite|accept|leave|info */
public class LobbyPartyCommand implements CommandExecutor, TabCompleter {

    private final LobbyPlugin plugin;
    private final LobbyPartyManager pm;
    public LobbyPartyCommand(LobbyPlugin p, LobbyPartyManager pm) { this.plugin=p; this.pm=pm; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) { p.sendMessage(Component.text("/party invite|accept|leave|info", NamedTextColor.GRAY)); return true; }
        switch (args[0].toLowerCase()) {
            case "invite" -> {
                if (args.length < 2) { p.sendMessage(Component.text("Usage: /party invite <joueur>", NamedTextColor.RED)); break; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { p.sendMessage(Component.text("Joueur introuvable.", NamedTextColor.RED)); break; }
                pm.invite(p, target);
            }
            case "accept" -> pm.accept(p);
            case "leave"  -> pm.leave(p);
            case "info"   -> {
                var members = pm.getPartyMembers(p.getUniqueId());
                p.sendMessage(Component.text("Groupe (" + members.size() + " joueurs):", NamedTextColor.GOLD));
                for (var uuid : members) {
                    Player m = Bukkit.getPlayer(uuid);
                    p.sendMessage(Component.text("  • " + (m != null ? m.getName() : uuid.toString()), NamedTextColor.WHITE));
                }
            }
            default -> p.sendMessage(Component.text("Sous-commande inconnue.", NamedTextColor.RED));
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return List.of("invite","accept","leave","info");
        return List.of();
    }
}
