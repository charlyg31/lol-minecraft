package fr.lolmc.bungee;

import fr.lolmc.bungee.LolBungeePlugin;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.*;

/**
 * /lol <rôle1> [rôle2] ... — rejoindre la file avec les rôles souhaités.
 *
 * Exemples :
 *   /lol top adc        → file en acceptant top OU adc
 *   /lol mid jungle     → file en acceptant mid OU jungle
 *   /lol top mid adc    → file en acceptant top, mid ou adc
 *   /lol all            → file en acceptant n'importe quel rôle (= FILL)
 *   /lol leave          → quitter la file
 *
 * Rôles valides : top, jungle, mid, adc, support, all
 * Minimum 1 rôle requis.
 */
public class LolCommand extends Command implements TabExecutor {

    private static final Set<String> VALID_ROLES = Set.of(
        "top", "jungle", "mid", "adc", "support", "all"
    );

    private final LolBungeePlugin plugin;

    public LolCommand(LolBungeePlugin plugin) {
        super("lol", "lolmc.play", "ll", "jouer");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(new TextComponent("§cCommande réservée aux joueurs."));
            return;
        }

        // /lol sans argument → aide
        if (args.length == 0) {
            sendHelp(player);
            return;
        }

        String first = args[0].toLowerCase();

        // /lol leave — quitter la file
        if (first.equals("leave") || first.equals("quitter") || first.equals("quit")) {
            if (plugin.getQueueManager().isInQueue(player.getUniqueId())) {
                plugin.getQueueManager().leave(player);
                player.sendMessage(new TextComponent("§7Tu as quitté la file."));
            } else {
                player.sendMessage(new TextComponent("§7Tu n'es pas en file."));
            }
            return;
        }

        // /lol party invite/accept/leave — gestion du groupe
        if (first.equals("party") || first.equals("p")) {
            handleParty(player, args);
            return;
        }

        // /lol runes — afficher les runes actuelles
        if (first.equals("runes")) {
            var runes = plugin.getRuneManager().getPage(player.getUniqueId());
            player.sendMessage(new TextComponent(
                "§5Runes : §f" + runes.get("keystone")
                + " §7| Sorts : §e" + runes.get("spell1") + " §7+ §e" + runes.get("spell2")));
            return;
        }

        // /lol <rôles...> — rejoindre la file
        List<String> roles = new ArrayList<>();
        for (String arg : args) {
            String r = arg.toLowerCase();
            if (!VALID_ROLES.contains(r)) {
                player.sendMessage(new TextComponent(
                    "§cRôle invalide : §f" + arg
                    + " §c— Valides : top, jungle, mid, adc, support, all"));
                return;
            }
            if (r.equals("all")) { roles = List.of("TOP","JUNGLE","MID","ADC","SUPPORT"); break; }
            roles.add(r.toUpperCase());
        }

        if (roles.isEmpty()) { sendHelp(player); return; }

        // Sauvegarder les rôles souhaités puis rejoindre la file
        plugin.getRoleManager().setRoles(player.getUniqueId(), roles);
        plugin.getQueueManager().join(player);
    }

    private void handleParty(ProxiedPlayer player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(new TextComponent(
                "§7Usage : §e/lol party <invite|accept|leave> [joueur]"));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "invite" -> {
                if (args.length < 3) {
                    player.sendMessage(new TextComponent("§7Usage : §e/lol party invite <joueur>"));
                    return;
                }
                ProxiedPlayer target = net.md_5.bungee.api.ProxyServer.getInstance()
                    .getPlayer(args[2]);
                if (target == null) {
                    player.sendMessage(new TextComponent("§cJoueur introuvable : " + args[2]));
                    return;
                }
                plugin.getPartyManager().invite(player, target);
            }
            case "accept" -> plugin.getPartyManager().accept(player);
            case "leave"  -> plugin.getPartyManager().leave(player);
            default -> player.sendMessage(new TextComponent(
                "§7Usage : §e/lol party <invite|accept|leave> [joueur]"));
        }
    }

    private void sendHelp(ProxiedPlayer player) {
        player.sendMessage(new TextComponent("§6§l⚔ LoL — Commandes"));
        player.sendMessage(new TextComponent("§e/lol <rôles>    §7Rejoindre la file"));
        player.sendMessage(new TextComponent("  §fExemples : §a/lol top adc §f| §a/lol mid §f| §a/lol all"));
        player.sendMessage(new TextComponent("§e/lol leave       §7Quitter la file"));
        player.sendMessage(new TextComponent("§e/lol party invite <joueur>  §7Inviter"));
        player.sendMessage(new TextComponent("§e/lol party accept           §7Accepter"));
        player.sendMessage(new TextComponent("§e/lol party leave            §7Quitter le groupe"));
        player.sendMessage(new TextComponent("§e/lol runes       §7Voir tes runes actuelles"));
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 0) return List.of();
        String cur = args[args.length - 1].toLowerCase();

        // Premier argument
        if (args.length == 1) {
            return List.of("top","jungle","mid","adc","support","all","leave","party","runes")
                .stream().filter(s -> s.startsWith(cur)).toList();
        }

        // Rôles suivants (si pas encore "all" ou "leave" ou "party")
        String first = args[0].toLowerCase();
        if (!first.equals("all") && !first.equals("leave")
            && !first.equals("party") && !first.equals("runes")) {
            Set<String> already = new HashSet<>(Arrays.asList(args).subList(0, args.length - 1));
            return List.of("top","jungle","mid","adc","support")
                .stream()
                .filter(s -> s.startsWith(cur) && !already.contains(s))
                .toList();
        }

        // /lol party <sous-commande>
        if (first.equals("party") && args.length == 2) {
            return List.of("invite","accept","leave")
                .stream().filter(s -> s.startsWith(cur)).toList();
        }

        // /lol party invite <nom>
        if (first.equals("party") && args.length == 3 && "invite".equals(args[1])) {
            return net.md_5.bungee.api.ProxyServer.getInstance().getPlayers()
                .stream().map(p -> p.getName())
                .filter(n -> n.toLowerCase().startsWith(cur)).toList();
        }

        return List.of();
    }
}
