package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Système de vote d'abandon (FF) façon LoL.
 *
 * Règles :
 *  - Disponible à partir de 15 minutes (configurable)
 *  - 3/5 votes FOR dans les 60s → l'équipe abandonne
 *  - Une seule vote en cours par équipe à la fois
 *  - Timeout 60s si pas de majorité
 */
public class ForfeitManager {

    private static final long MIN_FF_TIME_MS = 15 * 60 * 1000L; // 15 minutes
    private static final int VOTE_TIMEOUT_S  = 60;
    private static final double VOTE_RATIO   = 3.0 / 5.0;       // 3/5

    private final Map<Team, Set<UUID>>   votes      = new EnumMap<>(Team.class);
    private final Map<Team, Long>        voteStart  = new EnumMap<>(Team.class);
    private final Map<Team, org.bukkit.scheduler.BukkitTask> timers = new EnumMap<>(Team.class);

    public void reset() {
        timers.values().forEach(t -> { if (t != null) t.cancel(); });
        votes.clear(); voteStart.clear(); timers.clear();
    }

    /**
     * Un joueur vote pour l'abandon.
     * Retourne false si le vote est impossible (trop tôt, déjà voté, pas en partie).
     */
    public boolean vote(Player player) {
        var gm = LolPlugin.getInstance().getGameManager();
        if (!gm.isGameRunning()) {
            player.sendMessage(Component.text("❌ Aucune partie en cours.", NamedTextColor.RED));
            return false;
        }

        // Vérifier le temps minimum
        long elapsed = gm.getElapsedSeconds() * 1000L;
        if (elapsed < MIN_FF_TIME_MS) {
            long remaining = (MIN_FF_TIME_MS - elapsed) / 60000L + 1;
            player.sendMessage(Component.text(
                String.format("❌ L'abandon n'est disponible qu'à partir de 15min (encore %dmin).", remaining),
                NamedTextColor.RED));
            return false;
        }

        var tm = LolPlugin.getInstance().getTeamManager();
        Team team = tm.getTeam(player);
        if (team == null) { player.sendMessage(Component.text("❌ Equipe inconnue.", NamedTextColor.RED)); return false; }

        // Démarrer un nouveau vote si aucun en cours
        if (!votes.containsKey(team)) {
            votes.put(team, new HashSet<>());
            voteStart.put(team, System.currentTimeMillis());
            broadcastTeam(team, Component.text(
                "🏳 Vote d'abandon lancé par " + player.getName() + " ! /l ff pour voter (60s)",
                NamedTextColor.YELLOW));
            // Timeout
            var task = new BukkitRunnable() {
                @Override public void run() {
                    if (votes.containsKey(team)) {
                        votes.remove(team); voteStart.remove(team); timers.remove(team);
                        broadcastTeam(team, Component.text(
                            "🏳 Vote d'abandon expiré.", NamedTextColor.GRAY));
                    }
                }
            };
            timers.put(team, task.runTaskLater(LolPlugin.getInstance(), VOTE_TIMEOUT_S * 20L));
        }

        Set<UUID> teamVotes = votes.get(team);
        if (teamVotes.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("Tu as déjà voté.", NamedTextColor.GRAY));
            return false;
        }

        teamVotes.add(player.getUniqueId());

        // Compter les membres de l'équipe en ligne
        int total = tm.getTeamMembers(team).stream()
            .mapToInt(id -> Bukkit.getPlayer(id) != null && Bukkit.getPlayer(id).isOnline() ? 1 : 0)
            .sum();
        int needed = (int) Math.ceil(total * VOTE_RATIO);

        broadcastTeam(team, Component.text(
            String.format("🏳 %s a voté l'abandon (%d/%d)", player.getName(), teamVotes.size(), needed),
            NamedTextColor.YELLOW));

        if (teamVotes.size() >= needed) {
            // Abandon !
            timers.get(team).cancel();
            votes.remove(team); voteStart.remove(team); timers.remove(team);
            Bukkit.broadcast(Component.text(
                "🏳 L'équipe " + (team == Team.BLUE ? "Bleue" : "Rouge") + " abandonne !",
                NamedTextColor.RED));
            Team winner = team == Team.BLUE ? Team.RED : Team.BLUE;
            LolPlugin.getInstance().getMatchScoreboard().showEndScreen(winner);
            LolPlugin.getInstance().getGameManager().stopGame();
        }
        return true;
    }

    private void broadcastTeam(Team team, Component msg) {
        var tm = LolPlugin.getInstance().getTeamManager();
        for (UUID id : tm.getTeamMembers(team)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) p.sendMessage(msg);
        }
    }
}
