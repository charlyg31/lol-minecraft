package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.UUID;

/**
 * Scoreboard Tab (touche Tab) façon LoL.
 *
 * Affiche pour chaque joueur :
 *   <Équipe> <Nom>  <Champion>  K/D/A  CS  Or
 *
 * Mis à jour toutes les 2 secondes.
 */
public class TabScoreboardManager {

    private BukkitTask updateTask;
    private org.bukkit.scoreboard.Scoreboard board;
    private Objective objective;

    public void start() {
        var sm = Bukkit.getScoreboardManager();
        board = sm.getNewScoreboard();

        objective = board.registerNewObjective(
            "lolmc_tab", Criteria.DUMMY,
            Component.text("⚔ LolMC", NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.PLAYER_LIST);

        // Assigner le scoreboard à tous les joueurs en partie
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (fr.lolmc.util.WorldContext.isInGameWorld(p))
                p.setScoreboard(board);
        }

        updateTask = new BukkitRunnable() {
            @Override public void run() { update(); }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 40L); // toutes les 2s
    }

    public void stop() {
        if (updateTask != null) { updateTask.cancel(); updateTask = null; }
        // Remettre le scoreboard par défaut
        var def = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(def);
    }

    private void update() {
        if (objective == null) return;
        var msb = LolPlugin.getInstance().getMatchScoreboard();
        var cm  = LolPlugin.getInstance().getChampionManager();
        var tm  = LolPlugin.getInstance().getTeamManager();
        var gm  = LolPlugin.getInstance().getGoldManager();
        if (msb == null) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!fr.lolmc.util.WorldContext.isInGameWorld(p)) continue;
            p.setScoreboard(board);

            var stats = msb.getStats().get(p.getUniqueId());
            int cs   = stats != null ? stats.cs    : 0;
            int kills = stats != null ? stats.kills : 0;
            int deaths= stats != null ? stats.deaths: 0;
            int assists=stats != null ? stats.assists:0;
            int gold = gm != null ? gm.getGold(p.getUniqueId()) : 0;

            String champName = cm.hasChampion(p)
                ? cm.getChampion(p).getDisplayName() : "?";

            // Valeur affichée dans la liste Tab (or du joueur)
            Score score = objective.getScore(p.getName());
            score.setScore(gold);

            // Nom affiché dans la team avec équipe colorée
            Team team = tm.getTeam(p);
            String prefix = team == Team.BLUE ? "§9[B] " : "§c[R] ";
            String suffix = String.format(" §7%s §f%d/%d/%d §eCS%d",
                champName, kills, deaths, assists, cs);

            // Utiliser une team Bukkit pour le prefix/suffix
            String teamName = (team == Team.BLUE ? "b_" : "r_") + p.getName().substring(0, Math.min(10, p.getName().length()));
            org.bukkit.scoreboard.Team bTeam = board.getTeam(teamName);
            if (bTeam == null) bTeam = board.registerNewTeam(teamName);
            bTeam.prefix(Component.text(prefix));
            bTeam.suffix(Component.text(suffix.length() > 16 ? suffix.substring(0, 16) : suffix));
            bTeam.addPlayer(p);
        }
    }

    public void onPlayerJoin(Player p) {
        if (board != null) p.setScoreboard(board);
    }
}
