package fr.lolmc.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.List;

/** /lols [rôle] — test : simule « partie trouvée » en solo (défaut : mid). */
public class LolSoloCommand extends Command implements TabExecutor {

    private static final List<String> ROLES = List.of("top", "jungle", "mid", "adc", "support");
    private final LolBungeePlugin plugin;

    public LolSoloCommand(LolBungeePlugin plugin) {
        super("lols", "lolmc.admin"); // commande de test : réservée aux admins
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(new TextComponent("§cRéservé aux joueurs."));
            return;
        }
        String role = args.length > 0 ? args[0].toLowerCase() : "mid";
        if (!ROLES.contains(role)) {
            player.sendMessage(new TextComponent("§cRôle invalide. §7Valides : " + String.join(", ", ROLES)));
            return;
        }
        player.sendMessage(new TextComponent("§6⚔ Partie trouvée (test solo) — rôle §f" + role + "§6, connexion..."));
        plugin.getQueueManager().launchSolo(player, role.toUpperCase());
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        return args.length <= 1 ? ROLES : List.of();
    }
}
