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
 * Commande /lol — accessible depuis n'importe quel serveur du réseau.
 *
 * Sous-commandes :
 *
 *   /lol <rôle1> <rôle2> ...   Rejoindre la file avec les rôles souhaités
 *   /lol all                    Rejoindre la file FILL (tous les rôles)
 *   /lol leave                  Quitter la file
 *
 *   /lol party invite <joueur>  Inviter un joueur (le chef est celui qui invite en 1er)
 *   /lol party accept           Accepter une invitation
 *   /lol party decline          Refuser une invitation
 *   /lol party leave            Quitter le groupe
 *   /lol party kick <joueur>    Exclure un membre (chef seulement)
 *   /lol party disband          Dissoudre le groupe (chef seulement)
 *   /lol party promote <joueur> Transférer le chef (chef seulement)
 *   /lol party info             Voir le statut du groupe
 *
 * Règles de rôles en groupe :
 *   - Minimum 2 rôles (ou "all")
 *   - Pas de doublon
 *   - Postes distincts couverts >= taille du groupe
 *   - La file se lance automatiquement quand tout le groupe est prêt
 */
public class LolCommand extends Command implements TabExecutor {

    private static final List<String> ROLES      = List.of("top","jungle","mid","adc","support");
    private static final Set<String>  VALID_ROLES = new HashSet<>(ROLES);

    private final LolBungeePlugin plugin;

    public LolCommand(LolBungeePlugin plugin) {
        super("lol", "lolmc.play", "ll", "jouer");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(new TextComponent("§cRéservé aux joueurs."));
            return;
        }

        if (args.length == 0) { sendHelp(player); return; }

        switch (args[0].toLowerCase()) {
            case "party", "p"        -> handleParty(player, args);
            case "leave", "quitter"  -> handleLeave(player);
            case "all"               -> handleQueue(player, List.of("TOP","JUNGLE","MID","ADC","SUPPORT"));
            default                  -> handleQueueArgs(player, args);
        }
    }

    // ── /lol leave ───────────────────────────────────────────────────────

    private void handleLeave(ProxiedPlayer player) {
        if (plugin.getQueueManager().isInQueue(player.getUniqueId())) {
            plugin.getQueueManager().leave(player);
            player.sendMessage(new TextComponent("§7Tu as quitté la file."));
        } else {
            player.sendMessage(new TextComponent("§7Tu n'es pas en file."));
        }
    }

    // ── /lol <rôles...> ──────────────────────────────────────────────────

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
        // Groupe de 5 → 1 rôle suffit, sinon minimum 2
        int partySize = plugin.getPartyManager().getPartyMembers(player.getUniqueId()).size();
        int minRoles  = (partySize >= 5) ? 1 : 2;
        if (roles.size() < minRoles) {
            if (minRoles == 1)
                player.sendMessage(new TextComponent("§cChoisis au moins un rôle."));
            else {
                player.sendMessage(new TextComponent("§cMinimum 2 rôles requis."));
                player.sendMessage(new TextComponent(
                    "§7Exemple : §a/lol top adc §7| §a/lol mid jungle §7| §a/lol all"));
            }
            return;
        }
        handleQueue(player, roles);
    }

    /**
     * Traite le choix de rôles d'un joueur.
     * Si le joueur est dans un groupe : attend que tout le monde soit prêt.
     * Sinon : rejoint directement la file.
     */
    private void handleQueue(ProxiedPlayer player, List<String> roles) {
        BungeePartyManager pm = plugin.getPartyManager();

        if (pm.inParty(player.getUniqueId())) {
            // Mode groupe : signaler qu'on est prêt et vérifier les conflits
            boolean launchQueue = pm.setRolesAndCheckReady(player, roles);
            if (launchQueue) {
                // Tout le groupe est prêt et sans conflit → rejoindre la file
                for (UUID uid : pm.getPartyMembers(player.getUniqueId())) {
                    ProxiedPlayer member = ProxyServer.getInstance().getPlayer(uid);
                    if (member != null) plugin.getQueueManager().join(member);
                }
            }
        } else {
            // Joueur solo : rejoindre directement la file
            plugin.getRoleManager().setRoles(player.getUniqueId(), roles);
            plugin.getQueueManager().join(player);
        }
    }

    // ── /lol party <sous-commande> ───────────────────────────────────────

    private void handleParty(ProxiedPlayer player, String[] args) {
        if (args.length < 2) { sendPartyHelp(player); return; }
        BungeePartyManager pm = plugin.getPartyManager();

        switch (args[1].toLowerCase()) {

            case "invite", "inv" -> {
                if (args.length < 3) { player.sendMessage(new TextComponent("§7Usage : §e/lol party invite <joueur>")); return; }
                ProxiedPlayer target = ProxyServer.getInstance().getPlayer(args[2]);
                if (target == null) { player.sendMessage(new TextComponent("§cJoueur introuvable : §f" + args[2])); return; }
                if (target.equals(player)) { player.sendMessage(new TextComponent("§cTu ne peux pas t'inviter toi-même.")); return; }
                pm.invite(player, target);
            }

            case "accept", "oui", "yes" -> pm.accept(player);

            case "decline", "non", "no", "refuse" -> pm.decline(player);

            case "leave", "quitter", "quit" -> pm.leave(player);

            case "kick", "exclure", "exclude" -> {
                if (args.length < 3) { player.sendMessage(new TextComponent("§7Usage : §e/lol party kick <joueur>")); return; }
                pm.kick(player, args[2]);
            }

            case "disband", "dissoudre" -> pm.disband(player);

            case "promote", "chef", "transfer" -> {
                if (args.length < 3) { player.sendMessage(new TextComponent("§7Usage : §e/lol party promote <joueur>")); return; }
                pm.transferLeader(player, args[2]);
            }

            case "info", "status", "statut", "list", "liste" -> pm.sendPartyInfo(player);

            default -> sendPartyHelp(player);
        }
    }

    // ── Aide ─────────────────────────────────────────────────────────────

    private void sendHelp(ProxiedPlayer p) {
        p.sendMessage(new TextComponent("§6§l⚔ LoL — Aide"));
        p.sendMessage(new TextComponent("§7Rejoins la file avec §eminimum 2 rôles§7 :"));
        p.sendMessage(new TextComponent("  §a/lol top adc"));
        p.sendMessage(new TextComponent("  §a/lol mid jungle support"));
        p.sendMessage(new TextComponent("  §a/lol all §7(tous les rôles)"));
        p.sendMessage(new TextComponent("§7Rôles : §ftop §8| §fjungle §8| §fmid §8| §fadc §8| §fsupport"));
        p.sendMessage(new TextComponent("§e/lol leave §8— §7Quitter la file"));
        p.sendMessage(new TextComponent("§e/lol party §8— §7Gestion du groupe"));
    }

    private void sendPartyHelp(ProxiedPlayer p) {
        p.sendMessage(new TextComponent("§6§l⚔ LoL — Groupe"));
        p.sendMessage(new TextComponent("§e/lol party invite <joueur>  §8— §7Inviter (crée le groupe si besoin)"));
        p.sendMessage(new TextComponent("§e/lol party accept           §8— §7Accepter une invitation"));
        p.sendMessage(new TextComponent("§e/lol party decline          §8— §7Refuser une invitation"));
        p.sendMessage(new TextComponent("§e/lol party leave            §8— §7Quitter le groupe"));
        p.sendMessage(new TextComponent("§e/lol party kick <joueur>    §8— §7Exclure (chef)"));
        p.sendMessage(new TextComponent("§e/lol party promote <joueur> §8— §7Donner le chef (chef)"));
        p.sendMessage(new TextComponent("§e/lol party disband          §8— §7Dissoudre le groupe (chef)"));
        p.sendMessage(new TextComponent("§e/lol party info             §8— §7Voir le statut du groupe"));
        p.sendMessage(new TextComponent("§7Une fois prêts, chaque membre fait §e/lol <rôles>§7."));
        p.sendMessage(new TextComponent("§7La file se lance automatiquement quand tout le monde est prêt."));
    }

    // ── Tab-completion ────────────────────────────────────────────────────

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) return List.of();
        String cur = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        if (args.length == 1) {
            return filter(List.of("top","jungle","mid","adc","support","all",
                "leave","party"), cur);
        }

        String sub = args[0].toLowerCase();

        // Rôles supplémentaires
        if (VALID_ROLES.contains(sub)) {
            Set<String> already = new HashSet<>();
            for (int i = 0; i < args.length - 1; i++) already.add(args[i].toLowerCase());
            return filter(ROLES.stream().filter(r -> !already.contains(r)).toList(), cur);
        }

        // Party sous-commandes
        if ((sub.equals("party") || sub.equals("p")) && args.length == 2) {
            return filter(List.of("invite","accept","decline","leave",
                "kick","promote","disband","info"), cur);
        }

        // Party invite/kick/promote → noms des joueurs en ligne
        if ((sub.equals("party") || sub.equals("p")) && args.length == 3
            && List.of("invite","kick","promote").contains(args[1].toLowerCase())) {
            return ProxyServer.getInstance().getPlayers().stream()
                .map(ProxiedPlayer::getName)
                .filter(n -> n.toLowerCase().startsWith(cur))
                .toList();
        }

        return List.of();
    }

    private List<String> filter(List<String> list, String prefix) {
        return list.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
