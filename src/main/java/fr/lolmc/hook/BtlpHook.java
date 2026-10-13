package fr.lolmc.hook;

import codecrafter47.bungeetablistplus.api.bukkit.BungeeTabListPlusBukkitAPI;
import codecrafter47.bungeetablistplus.api.bukkit.Variable;
import fr.lolmc.LolPlugin;
import fr.lolmc.instance.GameInstance;
import fr.lolmc.team.TeamManager.Team;
import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * Variables BungeeTabListPlus (via le bridge Bukkit) — valeurs par joueur,
 * donc indépendantes d'une instance à l'autre.
 *
 * Dans la config BTLP : ${player lolmc_gold}, ${player lolmc_kda}, ...
 * Chargé uniquement si BungeeTabListPlus est présent (classe isolée).
 */
public final class BtlpHook {

    private BtlpHook() {}

    public static void register(LolPlugin plugin) {
        reg(plugin, "lolmc_team",     p -> teamPrefix(p));
        reg(plugin, "lolmc_champion", p -> {
            var cm = LolPlugin.getInstance().getChampionManager();
            return cm != null && cm.hasChampion(p) ? cm.getChampion(p).getDisplayName() : "?";
        });
        reg(plugin, "lolmc_kills",   p -> String.valueOf(stat(p, 0)));
        reg(plugin, "lolmc_deaths",  p -> String.valueOf(stat(p, 1)));
        reg(plugin, "lolmc_assists", p -> String.valueOf(stat(p, 2)));
        reg(plugin, "lolmc_kda",     p -> stat(p, 0) + "/" + stat(p, 1) + "/" + stat(p, 2));
        reg(plugin, "lolmc_cs",      p -> String.valueOf(stat(p, 3)));
        reg(plugin, "lolmc_gold",    p -> {
            var gm = LolPlugin.getInstance().getGoldManager();
            return String.valueOf(gm != null ? gm.getGold(p.getUniqueId()) : 0);
        });
        // Identifiant de partie : permet de grouper/filtrer par instance dans la config BTLP
        reg(plugin, "lolmc_game", p -> {
            GameInstance inst = LolPlugin.getInstance().getInstanceManager().getInstanceOf(p);
            return inst != null ? String.valueOf(inst.getId()) : "0";
        });
        plugin.getLogger().info("[BTLP] Variables lolmc_* enregistrées.");
    }

    private static void reg(LolPlugin plugin, String name, Function<Player, String> f) {
        BungeeTabListPlusBukkitAPI.registerVariable(plugin, new Variable(name) {
            @Override public String getReplacement(Player player) {
                try { return f.apply(player); } catch (Exception e) { return ""; }
            }
        });
    }

    private static String teamPrefix(Player p) {
        GameInstance inst = LolPlugin.getInstance().getInstanceManager().getInstanceOf(p);
        Team t = inst != null ? inst.getTeam(p.getUniqueId())
                              : LolPlugin.getInstance().getTeamManager().getTeam(p);
        if (t == null) return "";
        return t == Team.BLUE ? "§9[B]" : "§c[R]";
    }

    /** 0=kills 1=deaths 2=assists 3=cs */
    private static int stat(Player p, int k) {
        var msb = LolPlugin.getInstance().getMatchScoreboard();
        if (msb == null) return 0;
        var s = msb.getStats().get(p.getUniqueId());
        if (s == null) return 0;
        return switch (k) { case 0 -> s.kills; case 1 -> s.deaths; case 2 -> s.assists; default -> s.cs; };
    }
}
