package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Collecte les statistiques de la partie en cours (KDA, CS, or) et
 * affiche le tableau de score à la fin. Persiste les résultats en base.
 */
public class MatchScoreboard {

    /** Stats d'un joueur pour la partie en cours. */
    public static class MatchStats {
        public final UUID uuid;
        public final String name;
        public int kills, deaths, assists, cs, gold;

        public MatchStats(UUID uuid, String name) {
            this.uuid = uuid; this.name = name;
        }
        public double kda() {
            return deaths == 0 ? kills + assists : (kills + assists) / (double) deaths;
        }
    }

    private final Map<UUID, MatchStats> stats = new HashMap<>();
    private boolean ranked = false;

    public void startMatch(boolean ranked) {
        this.ranked = ranked;
        stats.clear();
    }

    public boolean isRanked() { return ranked; }

    private MatchStats get(Player p) {
        return stats.computeIfAbsent(p.getUniqueId(), u -> new MatchStats(u, p.getName()));
    }

    // ── Enregistrement des événements ─────────────────────────────

    public void addKill(Player p)   { get(p).kills++; }
    public void addDeath(Player p)  { get(p).deaths++; }
    public void addAssist(Player p) { get(p).assists++; }
    public void addCS(Player p)     { get(p).cs++; }
    public void addGold(Player p, int amount) { get(p).gold += amount; }

    // ══════════════════════════════════════════════════════════════
    // AFFICHAGE DE FIN DE PARTIE
    // ══════════════════════════════════════════════════════════════

    /**
     * Affiche le tableau de score à tous les joueurs et persiste en base.
     * @param winner équipe gagnante
     */
    public void showEndScreen(Team winner) {
        var tm = LolPlugin.getInstance().getTeamManager();

        // Construire l'affichage
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("═══════ FIN DE PARTIE ═══════", NamedTextColor.GOLD));
        lines.add(Component.text("🏆 Victoire : équipe " + (winner == Team.BLUE ? "BLEUE" : "ROUGE"),
                winner.chatColor));
        lines.add(Component.empty());

        appendTeamScores(lines, Team.BLUE, tm);
        lines.add(Component.empty());
        appendTeamScores(lines, Team.RED, tm);

        for (Player p : Bukkit.getOnlinePlayers()) {
            for (Component line : lines) p.sendMessage(line);
        }

        // Persister en base (KDA + résultat) et mettre à jour le classement
        persistResults(winner, tm);
    }

    private void appendTeamScores(List<Component> lines, Team team, fr.lolmc.team.TeamManager tm) {
        lines.add(Component.text("── Équipe " + (team == Team.BLUE ? "Bleue" : "Rouge") + " ──",
                team.chatColor));
        for (UUID id : tm.getTeamMembers(team)) {
            MatchStats s = stats.get(id);
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (s == null) {
                lines.add(Component.text("  " + name + " : 0/0/0", NamedTextColor.GRAY));
            } else {
                lines.add(Component.text(String.format("  %s : %d/%d/%d  (CS %d, %d or)",
                        name, s.kills, s.deaths, s.assists, s.cs, s.gold), NamedTextColor.WHITE));
            }
        }
    }

    private void persistResults(Team winner, fr.lolmc.team.TeamManager tm) {
        var db = LolPlugin.getInstance().getDatabaseManager();
        var ranked = LolPlugin.getInstance().getRankedManager();
        if (db == null || ranked == null) return;

        List<UUID> winners = new ArrayList<>(tm.getTeamMembers(winner));
        List<UUID> losers = new ArrayList<>(tm.getTeamMembers(winner.opposite()));

        // Reporter les KDA de la partie dans les stats persistantes
        var mode = this.ranked ? fr.lolmc.stats.persistence.RankedManager.Mode.RANKED
                : fr.lolmc.stats.persistence.RankedManager.Mode.NORMAL;
        for (var entry : stats.entrySet()) {
            MatchStats s = entry.getValue();
            var ps = db.getCached(entry.getKey());
            if (ps == null) continue;
            if (mode == fr.lolmc.stats.persistence.RankedManager.Mode.RANKED) {
                ps.rankedKills += s.kills;
                ps.rankedDeaths += s.deaths;
                ps.rankedAssists += s.assists;
            } else {
                ps.normalKills += s.kills;
                ps.normalDeaths += s.deaths;
                ps.normalAssists += s.assists;
            }
            db.saveStats(ps);
        }

        // Mettre à jour Elo (classé) ou stats (amical)
        if (this.ranked) {
            ranked.updateEloAfterRanked(winners, losers);
        } else {
            ranked.updateAfterNormal(winners, losers);
        }
    }

    public Map<UUID, MatchStats> getStats() { return stats; }
}
