package fr.lolmc.hook;

import codecrafter47.bungeetablistplus.api.bukkit.BungeeTabListPlusBukkitAPI;
import codecrafter47.bungeetablistplus.api.bukkit.Variable;
import fr.lolmc.LolPlugin;
import fr.lolmc.instance.GameInstance;
import fr.lolmc.team.TeamManager.Team;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Variables BungeeTabListPlus (via le bridge Bukkit) — valeurs par joueur,
 * donc indépendantes d'une instance à l'autre.
 *
 * Dans la config BTLP : ${player lolmc_gold}, ${player lolmc_kda}, ...
 *
 * BTLP lit les variables depuis des threads autres que le thread principal :
 * les valeurs sont donc calculées sur le thread principal (toutes les secondes)
 * et stockées dans un cache thread-safe que BTLP se contente de lire.
 *
 * Chargé uniquement si BungeeTabListPlus est présent (classe isolée).
 */
public final class BtlpHook {

    private static final String[] NAMES = {
        "lolmc_team", "lolmc_champion", "lolmc_kills", "lolmc_deaths", "lolmc_assists",
        "lolmc_kda", "lolmc_cs", "lolmc_gold", "lolmc_game"
    };

    /** uuid → (nom de variable → valeur) */
    private static final Map<UUID, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private BtlpHook() {}

    public static void register(LolPlugin plugin) {
        for (String name : NAMES) {
            BungeeTabListPlusBukkitAPI.registerVariable(plugin, new Variable(name) {
                @Override public String getReplacement(Player player) {
                    Map<String, String> m = CACHE.get(player.getUniqueId());
                    String v = m != null ? m.get(name) : null;
                    return v != null ? v : "";
                }
            });
        }
        // Rafraîchissement sur le thread principal, 1×/seconde
        Bukkit.getScheduler().runTaskTimer(plugin, BtlpHook::refresh, 20L, 20L);
        plugin.getLogger().info("[BTLP] Variables lolmc_* enregistrées.");
    }

    private static void refresh() {
        CACHE.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        var lp = LolPlugin.getInstance();
        var cm = lp.getChampionManager();
        var gm = lp.getGoldManager();
        var msb = lp.getMatchScoreboard();
        var im = lp.getInstanceManager();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                var s = msb != null ? msb.getStats().get(p.getUniqueId()) : null;
                int k = s != null ? s.kills : 0, d = s != null ? s.deaths : 0;
                int a = s != null ? s.assists : 0, cs = s != null ? s.cs : 0;
                GameInstance inst = im != null ? im.getInstanceOf(p) : null;
                Team t = inst != null ? inst.getTeam(p.getUniqueId()) : lp.getTeamManager().getTeam(p);
                Map<String, String> m = new ConcurrentHashMap<>();
                m.put("lolmc_team", t == null ? "" : (t == Team.BLUE ? "§9[B]" : "§c[R]"));
                m.put("lolmc_champion", cm != null && cm.hasChampion(p) ? cm.getChampion(p).getDisplayName() : "?");
                m.put("lolmc_kills", String.valueOf(k));
                m.put("lolmc_deaths", String.valueOf(d));
                m.put("lolmc_assists", String.valueOf(a));
                m.put("lolmc_kda", k + "/" + d + "/" + a);
                m.put("lolmc_cs", String.valueOf(cs));
                m.put("lolmc_gold", String.valueOf(gm != null ? gm.getGold(p.getUniqueId()) : 0));
                m.put("lolmc_game", inst != null ? String.valueOf(inst.getId()) : "0");
                CACHE.put(p.getUniqueId(), m);
            } catch (Exception ex) {
                lp.getLogger().log(java.util.logging.Level.WARNING, "[BTLP] refresh " + p.getName(), ex);
            }
        }
    }
}
