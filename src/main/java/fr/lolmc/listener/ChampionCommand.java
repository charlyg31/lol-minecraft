package fr.lolmc.listener;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class ChampionCommand implements CommandExecutor, TabCompleter {

    private final ChampionManager manager;

    public ChampionCommand(ChampionManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cCommande joueur uniquement.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "pick" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /champion pick <nom>");
                    return true;
                }
                manager.assignChampion(player, args[1].toLowerCase());
            }
            case "list" -> sendList(player, args.length > 1 ? args[1] : null);
            case "info" -> {
                if (!manager.hasChampion(player)) {
                    player.sendMessage("§cAucun champion actif.");
                    return true;
                }
                sendChampionInfo(player);
            }
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("══ LoL MC - Champions ══", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true));
        player.sendMessage(Component.text("/champion pick <nom>", NamedTextColor.YELLOW)
                .append(Component.text(" - Choisir un champion", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/champion list [role]", NamedTextColor.YELLOW)
                .append(Component.text(" - Lister les champions", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/champion info", NamedTextColor.YELLOW)
                .append(Component.text(" - Infos sur ton champion actif", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("Rôles: top, jungle, mid, support, adc", NamedTextColor.DARK_GRAY));
    }

    private void sendList(Player player, String roleFilter) {
        player.sendMessage(Component.text("══ Champions disponibles ══", NamedTextColor.GOLD));

        BaseChampion.ChampionRole[] roles = BaseChampion.ChampionRole.values();
        for (BaseChampion.ChampionRole role : roles) {
            if (roleFilter != null && !role.name().equalsIgnoreCase(roleFilter)) continue;

            NamedTextColor roleColor = switch (role) {
                case TOP     -> NamedTextColor.RED;
                case JUNGLE  -> NamedTextColor.GREEN;
                case MID     -> NamedTextColor.BLUE;
                case SUPPORT -> NamedTextColor.AQUA;
                case ADC     -> NamedTextColor.YELLOW;
            };

            List<BaseChampion> champs = manager.getChampionsByRole(role);
            StringBuilder names = new StringBuilder();
            for (BaseChampion c : champs) {
                names.append(c.getDisplayName()).append(", ");
            }
            if (names.length() > 2) names.setLength(names.length() - 2);

            player.sendMessage(Component.text("[" + role.name() + "] ", roleColor)
                    .decoration(TextDecoration.BOLD, true)
                    .append(Component.text(names.toString(), NamedTextColor.WHITE)
                            .decoration(TextDecoration.BOLD, false)));
        }
    }

    private void sendChampionInfo(Player player) {
        BaseChampion champ = manager.getChampion(player);
        var stats = champ.getStats();

        player.sendMessage(Component.text("══ " + champ.getDisplayName() + " ══", NamedTextColor.GOLD));
        player.sendMessage(Component.text(String.format(
                "HP: %.0f | AD: %.0f | AP: %.0f",
                stats.getFinalMaxHP(), stats.getFinalAD(), stats.getFinalAP()),
                NamedTextColor.WHITE));
        player.sendMessage(Component.text(String.format(
                "Armure: %.0f | MR: %.0f | AS: %.2f",
                stats.getFinalArmor(), stats.getFinalMagicResist(), stats.getFinalAttackSpeed()),
                NamedTextColor.WHITE));
        player.sendMessage(Component.text(String.format(
                "Portée: %.0f | Vitesse: %.0f | AH: %.0f",
                stats.getFinalRange(), stats.getFinalMovementSpeed(), stats.getFinalAbilityHaste()),
                NamedTextColor.WHITE));

        player.sendMessage(Component.text("── Sorts ──", NamedTextColor.DARK_GRAY));
        String[] slotNames = {"AA", "Q", "W", "E", "R"};
        var abilities = champ.getAbilities();
        for (int i = 0; i < 5; i++) {
            if (abilities[i] == null) continue;
            double cd = abilities[i].getCurrentCooldown(stats);
            player.sendMessage(Component.text(
                    String.format("[%s] %s - CD: %.1fs", slotNames[i], abilities[i].getName(), cd),
                    NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.addAll(List.of("pick", "list", "info"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("pick")) {
                for (BaseChampion c : manager.getAllChampions()) {
                    completions.add(c.getId());
                }
            } else if (args[0].equalsIgnoreCase("list")) {
                for (BaseChampion.ChampionRole r : BaseChampion.ChampionRole.values()) {
                    completions.add(r.name().toLowerCase());
                }
            }
        }
        String input = args[args.length - 1].toLowerCase();
        completions.removeIf(s -> !s.toLowerCase().startsWith(input));
        return completions;
    }
}
