package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.AnnouncementManager.PingType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * Commandes joueur : /recall (retour base) et /ping (alerte équipe).
 */
public class PlayerCommands implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§cJoueur uniquement."); return true; }

        switch (cmd.getName().toLowerCase()) {
            case "recall" -> {
                if (!LolPlugin.getInstance().getChampionManager().hasChampion(player)) {
                    player.sendMessage(Component.text("❌ Tu n'as pas de champion.", NamedTextColor.RED));
                    return true;
                }
                LolPlugin.getInstance().getBaseManager().startRecall(player);
            }
            case "roles", "lobby", "play" -> {
                LolPlugin.getInstance().getPreGameGUI().open(player);
            }
            case "queue" -> {
                LolPlugin.getInstance().getRoleQueueManager().joinQueue(player);
            }
            case "pick" -> {
                if (args.length < 1) { player.sendMessage("§cUsage: /pick <champion>"); return true; }
                LolPlugin.getInstance().getChampSelectManager().chooseChampion(player, args[0].toLowerCase());
            }
            case "runes" -> {
                LolPlugin.getInstance().getRuneGUI().open(player);
            }
            case "spell" -> {
                if (args.length < 2) { player.sendMessage("§cUsage: /spell <sort1> <sort2>"); return true; }
                LolPlugin.getInstance().getChampSelectManager().chooseSpells(player, args[0], args[1]);
            }
            case "lock" -> {
                LolPlugin.getInstance().getChampSelectManager().lock(player);
            }
            case "ping" -> {
                // /ping seul = ping générique d'alerte
                if (args.length < 1) {
                    LolPlugin.getInstance().getAnnouncementManager().sendPing(player, PingType.DANGER);
                    return true;
                }
                PingType type = switch (args[0].toLowerCase()) {
                    case "danger", "warn" -> PingType.DANGER;
                    case "omw", "onmyway" -> PingType.ON_MY_WAY;
                    case "miss", "missing", "mia" -> PingType.MISSING;
                    case "assist", "help" -> PingType.ASSIST;
                    case "enemy", "here" -> PingType.ENEMY;
                    default -> null;
                };
                if (type == null) {
                    player.sendMessage("§cType: danger, omw, missing, assist, enemy");
                    return true;
                }
                LolPlugin.getInstance().getAnnouncementManager().sendPing(player, type);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("ping") && args.length == 1) {
            return Arrays.asList("danger", "omw", "miss", "missing", "assist", "help", "enemy");
        }
        return List.of();
    }
}
