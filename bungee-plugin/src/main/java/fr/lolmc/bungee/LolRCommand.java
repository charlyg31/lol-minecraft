package fr.lolmc.bungee;

import fr.lolmc.bungee.party.BungeePartyManager;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.*;

/**
 * /lolr — File ranked (classé) avec phase de pick + ban.
 *
 * Mêmes règles de rôles que /lol (minimum 2 rôles ou "all").
 * Force ranked=true dans la config au moment du lancement.
 *
 * Sous-commandes admin :
 *   /lolr on   — active les parties classées
 *   /lolr off  — désactive les parties classées (message aux joueurs)
 *
 * Conditions pour rejoindre la file ranked :
 *   - Les ranked doivent être activées (/lolr on)
 *   - Mêmes règles de rôles que /lol
 */
public class LolRCommand extends Command implements TabExecutor {

    private static final List<String> ROLES       = List.of("top","jungle","mid","adc","support");
    private static final Set<String>  VALID_ROLES = new HashSet<>(ROLES);

    // État global — ranked activées ou non
    private static volatile boolean rankedEnabled = true;

    private final LolBungeePlugin plugin;

    public LolRCommand(LolBungeePlugin plugin) {
        super("lolr", "lolmc.play");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        // ── Commandes admin (/lolr on / /lolr off) ──
        if (args.length == 1 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            if (!sender.hasPermission("lolmc.admin")) {
                sender.sendMessage(new TextComponent("§cPermission insuffisante."));
                return;
            }
            rankedEnabled = args[0].equalsIgnoreCase("on");
            String state = rankedEnabled ? "§a§lACTIVÉES" : "§c§lDÉSACTIVÉES";
            // Annoncer à tous les joueurs connectés
            for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
                p.sendMessage(new TextComponent("§6⚔ Parties classées : " + state));
            }
            sender.sendMessage(new TextComponent("§7Ranked " + state + "§7."));
            return;
        }

        // ── Commandes joueur ──
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(new TextComponent("§cRéservé aux joueurs."));
            return;
        }

        if (!rankedEnabled) {
            player.sendMessage(new TextComponent("§c⚔ Les parties classées sont désactivées pour le moment."));
            return;
        }

        if (args.length == 0) { sendHelp(player); return; }

        switch (args[0].toLowerCase()) {
            case "leave", "quitter" -> {
                if (plugin.getQueueManager().isInQueue(player.getUniqueId())) {
                    plugin.getQueueManager().leave(player);
                    player.sendMessage(new TextComponent("§7Tu as quitté la file classée."));
                } else {
                    player.sendMessage(new TextComponent("§7Tu n'es pas en file classée."));
                }
            }
            case "all" -> handleQueue(player, List.of("TOP","JUNGLE","MID","ADC","SUPPORT"));
            default    -> handleQueueArgs(player, args);
        }
    }

    private void handleQueueArgs(ProxiedPlayer player, String[] args) {
        List<String> roles = new ArrayList<>();
        for (String arg : args) {
            String r = arg.toLowerCase();
            if (!VALID_ROLES.contains(r)) {
                player.sendMessage(new TextComponent("§cRôle invalide : §f" + arg));
                player.sendMessage(new TextComponent(
                    "§7Valides : §ftop §7| §fjungle §7| §fmid §7| §fadc §7| §fsupport §7| §fall"));
                return;
            }
            if (!roles.contains(r.toUpperCase())) roles.add(r.toUpperCase());
        }
        int partySize = plugin.getPartyManager().getPartyMembers(player.getUniqueId()).size();
        int minRoles  = (partySize >= 5) ? 1 : 2;
        if (roles.size() < minRoles) {
            player.sendMessage(new TextComponent("§cMinimum " + minRoles + " rôle(s) requis."));
            player.sendMessage(new TextComponent("§7Exemple : §a/lolr top mid §7| §a/lolr all"));
            return;
        }
        handleQueue(player, roles);
    }

    private void handleQueue(ProxiedPlayer player, List<String> roles) {
        BungeePartyManager pm = plugin.getPartyManager();

        // Forcer ranked=true dans la config avant de lancer
        plugin.setRankedMode(true);

        if (pm.inParty(player.getUniqueId())) {
            boolean launchQueue = pm.setRolesAndCheckReady(player, roles);
            if (launchQueue) {
                for (UUID uid : pm.getPartyMembers(player.getUniqueId())) {
                    ProxiedPlayer member = ProxyServer.getInstance().getPlayer(uid);
                    if (member != null) plugin.getQueueManager().join(member);
                }
            }
        } else {
            plugin.getRoleManager().setRoles(player.getUniqueId(), roles);
            plugin.getQueueManager().join(player);
            player.sendMessage(new TextComponent("§6⚔ Classé — en recherche de partie..."));
        }
    }

    private void sendHelp(ProxiedPlayer p) {
        p.sendMessage(new TextComponent("§6§l⚔ LoL Classé — Aide"));
        p.sendMessage(new TextComponent("§7Rejoins la file classée avec §eminimum 2 rôles§7 :"));
        p.sendMessage(new TextComponent("  §a/lolr top adc"));
        p.sendMessage(new TextComponent("  §a/lolr mid jungle support"));
        p.sendMessage(new TextComponent("  §a/lolr all §7(tous les rôles)"));
        p.sendMessage(new TextComponent("§e/lolr leave §8— §7Quitter la file classée"));
        p.sendMessage(new TextComponent("§8(Pick + Ban en phase de sélection)"));
        if (!rankedEnabled)
            p.sendMessage(new TextComponent("§c⚠ Les parties classées sont actuellement désactivées."));
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            // Admin tab-complete
            if (args.length == 1)
                return filter(List.of("on","off"), args[0].toLowerCase());
            return List.of();
        }
        String cur = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        if (args.length == 1)
            return filter(List.of("top","jungle","mid","adc","support","all","leave"), cur);
        // Rôles supplémentaires
        String sub = args[0].toLowerCase();
        if (VALID_ROLES.contains(sub)) {
            Set<String> already = new HashSet<>();
            for (int i = 0; i < args.length - 1; i++) already.add(args[i].toLowerCase());
            return filter(ROLES.stream().filter(r -> !already.contains(r)).toList(), cur);
        }
        return List.of();
    }

    private List<String> filter(List<String> list, String prefix) {
        return list.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    public static boolean isRankedEnabled() { return rankedEnabled; }
}
