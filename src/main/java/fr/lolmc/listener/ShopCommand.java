package fr.lolmc.listener;

import fr.lolmc.manager.ChampionManager;
import fr.lolmc.shop.GoldManager;
import fr.lolmc.shop.ShopGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public class ShopCommand implements CommandExecutor, TabCompleter {

    private final ShopGUI shopGUI;
    private final ChampionManager championManager;
    private final GoldManager goldManager;

    public ShopCommand(ShopGUI shopGUI, ChampionManager championManager, GoldManager goldManager) {
        this.shopGUI = shopGUI;
        this.championManager = championManager;
        this.goldManager = goldManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cCommande joueur uniquement.");
            return true;
        }

        if (!championManager.hasChampion(player)) {
            player.sendMessage(Component.text(
                "❌ Choisis d'abord un champion avec /champion gui",
                NamedTextColor.RED));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("gold")) {
            int gold = goldManager.getGold(player.getUniqueId());
            player.sendMessage(Component.text("💰 Or actuel: " + gold, NamedTextColor.GOLD));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("addgold") && player.isOp()) {
            if (args.length < 2) { player.sendMessage("§cUsage: /shop addgold <montant>"); return true; }
            try {
                int amount = Integer.parseInt(args[1]);
                goldManager.addGold(player.getUniqueId(), amount);
                player.sendMessage(Component.text("💰 +" + amount + " or ajouté! Total: "
                    + goldManager.getGold(player.getUniqueId()), NamedTextColor.GOLD));
            } catch (NumberFormatException e) {
                player.sendMessage("§cNombre invalide.");
            }
            return true;
        }

        // Ouvrir la boutique
        shopGUI.open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("gold", "addgold");
        return List.of();
    }
}
