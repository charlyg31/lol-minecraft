package fr.lolmc.listener;
import fr.lolmc.LolPlugin;

import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public class TeamCommand implements CommandExecutor, TabCompleter {

    private final TeamManager teamManager;

    public TeamCommand(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cCommande joueur uniquement.");
            return true;
        }

        if (args.length == 0) {
            // Afficher l'équipe actuelle
            Team team = teamManager.getTeam(player);
            if (team == null) {
                player.sendMessage(Component.text("Tu n'as pas d'équipe. Utilise ", NamedTextColor.GRAY)
                        .append(Component.text("/team bleu", NamedTextColor.BLUE))
                        .append(Component.text(" ou ", NamedTextColor.GRAY))
                        .append(Component.text("/team rouge", NamedTextColor.RED)));
            } else {
                player.sendMessage(Component.text("Ton équipe : ", NamedTextColor.GRAY)
                        .append(Component.text(team == Team.BLUE ? "BLEU" : "ROUGE", team.chatColor)));
            }
            return true;
        }

        String arg = args[0].toLowerCase();
        switch (arg) {
            case "bleu", "blue" -> {
                teamManager.setTeam(player, Team.BLUE);
                player.sendMessage(Component.text("✔ Tu as rejoint l'équipe ", NamedTextColor.GRAY)
                        .append(Component.text("BLEUE", NamedTextColor.BLUE)));
            }
            case "rouge", "red" -> {
                teamManager.setTeam(player, Team.RED);
                player.sendMessage(Component.text("✔ Tu as rejoint l'équipe ", NamedTextColor.GRAY)
                        .append(Component.text("ROUGE", NamedTextColor.RED)));
            }
            case "auto" -> {
                Team t = teamManager.autoAssign(player);
                player.sendMessage(Component.text("✔ Assigné à l'équipe ", NamedTextColor.GRAY)
                        .append(Component.text(t == Team.BLUE ? "BLEUE" : "ROUGE", t.chatColor)));
            }
            default -> player.sendMessage(Component.text("Usage: /team <bleu|rouge|auto>", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("bleu", "rouge", "auto");
        return List.of();
    }


    /** Sous-commande: /team chat <message> — envoie un message à l'équipe. */
    // Note: aussi accessible via /t (alias)
    public void sendTeamChat(org.bukkit.entity.Player sender, String message) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(sender);
        if (team == null) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Tu n'as pas d'équipe.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        net.kyori.adventure.text.Component msg = net.kyori.adventure.text.Component.text(
            "[" + team.name() + "] " + sender.getName() + ": " + message,
            team.chatColor);
        for (java.util.UUID id : tm.getTeamMembers(team)) {
            var p = LolPlugin.getInstance().getServer().getPlayer(id);
            if (p != null && p.isOnline()) p.sendMessage(msg);
        }
        // Log pour le joueur lui-même (confirmé)
        if (tm.getTeamMembers(team).isEmpty()) sender.sendMessage(msg);
    }
}
