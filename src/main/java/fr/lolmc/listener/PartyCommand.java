package fr.lolmc.listener;

import fr.lolmc.matchmaking.MatchmakingManager;
import fr.lolmc.matchmaking.PartyManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Commandes /party (groupes) et /queue (file d'attente).
 */
public class PartyCommand implements CommandExecutor, TabCompleter {

    private final PartyManager partyManager;
    private final MatchmakingManager matchmaking;

    public PartyCommand(PartyManager partyManager, MatchmakingManager matchmaking) {
        this.partyManager = partyManager;
        this.matchmaking = matchmaking;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cCommande joueur uniquement.");
            return true;
        }

        // ── /queue ──
        if (cmd.getName().equalsIgnoreCase("queue")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
                matchmaking.leaveQueue(player);
            } else {
                matchmaking.joinQueue(player);
            }
            return true;
        }

        // ── /party ──
        if (args.length == 0) {
            showPartyInfo(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "invite" -> {
                if (args.length < 2) { player.sendMessage("§cUsage: /party invite <joueur>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    player.sendMessage(Component.text("❌ Joueur introuvable.", NamedTextColor.RED));
                    return true;
                }
                if (target.equals(player)) {
                    player.sendMessage(Component.text("❌ Tu ne peux pas t'inviter toi-même.", NamedTextColor.RED));
                    return true;
                }
                partyManager.invite(player, target);
            }
            case "accept" -> partyManager.acceptInvite(player);
            case "leave"  -> {
                partyManager.leaveParty(player, true);
                player.sendMessage(Component.text("Tu as quitté le groupe.", NamedTextColor.GRAY));
            }
            case "info"   -> showPartyInfo(player);
            default -> player.sendMessage(Component.text(
                    "Usage: /party <invite|accept|leave|info>", NamedTextColor.RED));
        }
        return true;
    }

    private void showPartyInfo(Player player) {
        var party = partyManager.getParty(player);
        if (party == null || party.size() <= 1) {
            player.sendMessage(Component.text("Tu n'es dans aucun groupe. ", NamedTextColor.GRAY)
                    .append(Component.text("/party invite <joueur>", NamedTextColor.AQUA)));
            return;
        }
        String members = party.members.stream()
                .map(id -> { Player p = Bukkit.getPlayer(id); return p != null ? p.getName() : "?"; })
                .collect(Collectors.joining(", "));
        player.sendMessage(Component.text("👥 Groupe (" + party.size() + "/5): ", NamedTextColor.GOLD)
                .append(Component.text(members, NamedTextColor.WHITE)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("queue")) {
            if (args.length == 1) return List.of("leave");
            return List.of();
        }
        if (args.length == 1) return List.of("invite", "accept", "leave", "info");
        if (args.length == 2 && args[0].equalsIgnoreCase("invite")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        }
        return List.of();
    }
}
