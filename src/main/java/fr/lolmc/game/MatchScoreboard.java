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
        public long damageDealt = 0;    // dégâts infligés aux champions
        public long damageTaken = 0;    // dégâts subis
        public long healingDone = 0;    // soins prodigués (self + alliés)
        public int wardsPlaced = 0;     // wards posées
        public int wardsKilled = 0;     // wards détruites

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
    public void addDamageDealt(Player p, double dmg) { get(p).damageDealt += (long) dmg; }
    public void addDamageTaken(Player p, double dmg) { get(p).damageTaken += (long) dmg; }
    public void addHealing(Player p, double heal)    { get(p).healingDone += (long) heal; }
    public void addWardPlaced(Player p)  { get(p).wardsPlaced++; }
    public void addWardKilled(Player p)  { get(p).wardsKilled++; }

    // ══════════════════════════════════════════════════════════════
    // AFFICHAGE DE FIN DE PARTIE
    // ══════════════════════════════════════════════════════════════

    /**
     * Affiche le tableau de score à tous les joueurs et persiste en base.
     * @param winner équipe gagnante
     */
    private void appendTeamScores(List<Component> lines, Team team, fr.lolmc.team.TeamManager tm) {
        // Entête équipe
        String teamName = team == Team.BLUE ? "§9═══ Équipe Bleue ═══" : "§c═══ Équipe Rouge ═══";
        lines.add(Component.text(teamName));
        // En-tête colonnes
        lines.add(Component.text(
            String.format("  %-16s %5s %5s %5s %5s %8s %8s",
                "Joueur", "K", "D", "A", "CS", "Or", "Dégâts"),
            NamedTextColor.GRAY));
        for (UUID id : tm.getTeamMembers(team)) {
            var ms = stats.get(id);
            if (ms == null) continue;
            var cm = LolPlugin.getInstance().getChampionManager();
            String champName = cm.hasChampion(Bukkit.getPlayer(id))
                ? cm.getChampion(Bukkit.getPlayer(id)).getDisplayName() : "?";
            lines.add(Component.text(String.format(
                "  %-8s %-8s %5d %5d %5d %5d %8d %8d",
                ms.name.length() > 8 ? ms.name.substring(0,8) : ms.name,
                champName.length() > 8 ? champName.substring(0,8) : champName,
                ms.kills, ms.deaths, ms.assists, ms.cs, ms.gold, (int)ms.damageDealt),
                team == Team.BLUE ? NamedTextColor.AQUA : NamedTextColor.RED));
        }
    }

    private void appendTeamScores_OLD(List<Component> lines, Team team, fr.lolmc.team.TeamManager tm) {
        lines.add(Component.text("── Équipe " + (team == Team.BLUE ? "Bleue" : "Rouge") + " ──",
                team.chatColor));
        for (UUID id : tm.getTeamMembers(team)) {
            MatchStats s = stats.get(id);
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (s == null) {
                lines.add(Component.text("  " + name + " : 0/0/0", NamedTextColor.GRAY));
            } else {
                lines.add(Component.text(String.format(
                    "  %s : %d/%d/%d  CS %d  %dor",
                    name, s.kills, s.deaths, s.assists, s.cs, s.gold),
                    NamedTextColor.WHITE));
                lines.add(Component.text(String.format(
                    "    DMG: %,d infligés / %,d subis  Soins: %,d  Wards: %d posées %d détruites",
                    s.damageDealt, s.damageTaken, s.healingDone, s.wardsPlaced, s.wardsKilled),
                    NamedTextColor.GRAY));
            }
        }
    }

    /** Affiche le tableau de score à tous les joueurs et persiste en base. */
    public void showEndScreen(Team winner) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var feat = LolPlugin.getInstance().getFeatManager();

        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("═══════ FIN DE PARTIE ═══════", NamedTextColor.GOLD));
        lines.add(Component.text("🏆 Victoire : équipe " + (winner == Team.BLUE ? "BLEUE" : "ROUGE"),
                winner.chatColor));
        lines.add(Component.empty());

        // Feats of Strength
        if (feat != null) {
            lines.add(Component.text("⚡ Feats of Strength : " + feat.getSummary(),
                    NamedTextColor.YELLOW));
            lines.add(Component.empty());
        }

        appendTeamScores(lines, Team.BLUE, tm);
        lines.add(Component.empty());
        appendTeamScores(lines, Team.RED, tm);

        for (Player p : Bukkit.getOnlinePlayers()) {
            for (Component line : lines) p.sendMessage(line);
        }

        persistResults(winner, tm);
    }

    private void persistResults(Team winner, fr.lolmc.team.TeamManager tm) {
        var db = LolPlugin.getInstance().getDatabaseManager();
        var ranked = LolPlugin.getInstance().getRankedManager();
        if (db == null || ranked == null) return;

        List<UUID> winners = new ArrayList<>(tm.getTeamMembers(winner));
        List<UUID> losers = new ArrayList<>(tm.getTeamMembers(winner.opposite()));

        // Reporter les KDA de la partie dans les stats persistantes
        long duration = LolPlugin.getInstance().getGameManager().getElapsedSeconds();
        String matchId = java.util.UUID.randomUUID().toString(); // ID unique pour cette partie
        long now = System.currentTimeMillis();
        var tm2 = LolPlugin.getInstance().getTeamManager();
        var cm2 = LolPlugin.getInstance().getChampionManager();
        var mode = this.ranked ? fr.lolmc.stats.persistence.RankedManager.Mode.RANKED
                : fr.lolmc.stats.persistence.RankedManager.Mode.NORMAL;
        for (var entry : stats.entrySet()) {
            MatchStats s = entry.getValue();
            org.bukkit.entity.Player mp = org.bukkit.Bukkit.getPlayer(entry.getKey());
            // Construire MatchRecord enrichi
            var mr = new fr.lolmc.stats.persistence.MatchRecord();
            mr.uuid    = entry.getKey();
            mr.matchId = matchId;
            mr.playedAt = now;
            mr.ranked  = this.ranked;
            mr.won     = winners.contains(entry.getKey());
            mr.team    = (mp != null && tm2.getTeam(mp) != null)
                         ? tm2.getTeam(mp).name() : "UNKNOWN";
            mr.champion = (mp != null && cm2.hasChampion(mp))
                         ? cm2.getChampion(mp).getId() : "?";
            mr.kills   = s.kills;     mr.deaths       = s.deaths;
            mr.assists = s.assists;   mr.cs            = s.cs;
            mr.gold    = s.gold;      mr.damageDealt   = s.damageDealt;
            mr.damageTaken  = s.damageTaken;
            mr.healingDone  = s.healingDone;
            mr.wardsPlaced  = s.wardsPlaced;
            mr.wardsKilled  = s.wardsKilled;
            mr.durationSeconds = (int) duration;
            // Items équipés
            if (mp != null) {
                var inv = LolPlugin.getInstance().getShopListener().getOrCreate(mp);
                StringBuilder items = new StringBuilder("[");
                boolean fi = true;
                for (fr.lolmc.item.LolItem item : inv.getEquippedItems()) {
                    if (item == null) continue;
                    if (!fi) items.append(","); fi = false;
                    items.append("\"").append(item.getId()).append("\"");
                }
                items.append("]");
                mr.items = items.toString();
            }
            db.saveMatch(mr);
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
