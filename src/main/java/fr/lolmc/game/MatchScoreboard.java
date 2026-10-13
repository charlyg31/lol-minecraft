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

    /** Joueurs dont la partie en cours est classée (par joueur, pour des parties simultanées). */
    private final Set<UUID> rankedPlayers = new HashSet<>();

    /** Démarre une partie pour TOUS les joueurs (mode partie unique / tests). */
    public void startMatch(boolean ranked) {
        this.ranked = ranked;
        stats.clear();
        rankedPlayers.clear();
    }

    /** Démarre une partie pour ce groupe uniquement : les stats des autres parties restent intactes. */
    public void startMatch(boolean ranked, Collection<UUID> group) {
        this.ranked = ranked;
        if (group == null) { startMatch(ranked); return; }
        for (UUID id : group) {
            stats.remove(id);
            if (ranked) rankedPlayers.add(id); else rankedPlayers.remove(id);
        }
    }

    public boolean isRanked() { return ranked; }
    public boolean isRanked(UUID id) { return rankedPlayers.contains(id) || (rankedPlayers.isEmpty() && ranked); }

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

    /** Ajoute à {@code lines} l'entête et les statistiques de chaque joueur d'une équipe. */
    private void appendTeamScores(List<Component> lines, Team team, fr.lolmc.team.TeamManager tm, Set<UUID> group) {
        // Entête équipe
        String teamName = team == Team.BLUE ? "§9═══ Équipe Bleue ═══" : "§c═══ Équipe Rouge ═══";
        lines.add(Component.text(teamName));
        // En-tête colonnes
        lines.add(Component.text(
            String.format("  %-16s %5s %5s %5s %5s %8s %8s",
                "Joueur", "K", "D", "A", "CS", "Or", "Dégâts"),
            NamedTextColor.GRAY));
        for (UUID id : tm.getTeamMembers(team)) {
            if (group != null && !group.contains(id)) continue;
            var ms = stats.get(id);
            if (ms == null) continue;
            var cm = LolPlugin.getInstance().getChampionManager();
            var scoreboardPlayer = Bukkit.getPlayer(id);
            String champName = (scoreboardPlayer != null && cm.hasChampion(scoreboardPlayer))
                ? cm.getChampion(scoreboardPlayer).getDisplayName() : "?";
            lines.add(Component.text(String.format(
                "  %-8s %-8s %5d %5d %5d %5d %8d %8d",
                ms.name.length() > 8 ? ms.name.substring(0,8) : ms.name,
                champName.length() > 8 ? champName.substring(0,8) : champName,
                ms.kills, ms.deaths, ms.assists, ms.cs, ms.gold, (int)ms.damageDealt),
                team == Team.BLUE ? NamedTextColor.AQUA : NamedTextColor.RED));
        }
    }

    /** Recopie le total de soins de chaque champion dans les statistiques de la partie. */
    private void syncHealingStats(Set<UUID> group) {
        var cm = LolPlugin.getInstance().getChampionManager();
        for (var entry : stats.entrySet()) {
            if (group != null && !group.contains(entry.getKey())) continue;
            Player p = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (p != null && cm.hasChampion(p)) {
                entry.getValue().healingDone = (long) cm.getChampion(p).getHPSystem().getTotalHealed();
            }
        }
    }

    /** Affiche le tableau de score à tous les joueurs et persiste en base. */
    public void showEndScreen(Team winner) {
        showEndScreen(winner, null, null);
    }

    /**
     * Fin de partie pour un groupe : n'affiche et ne persiste que les joueurs de {@code players}
     * (null = tous), avec le chrono et les feats de {@code gm} (null = partie globale).
     */
    public void showEndScreen(Team winner, Collection<? extends Player> players, GameManager gm) {
        var tm = LolPlugin.getInstance().getTeamManager();
        Set<UUID> group = null;
        if (players != null) {
            group = new HashSet<>();
            for (Player gp : players) group.add(gp.getUniqueId());
        }
        syncHealingStats(group);
        var feat = (gm != null && gm.getGameInstance() != null)
                ? gm.getGameInstance().getFeatManager()
                : LolPlugin.getInstance().getFeatManager();
        GameManager timeSrc = gm != null ? gm : LolPlugin.getInstance().getGameManager();

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

        appendTeamScores(lines, Team.BLUE, tm, group);
        lines.add(Component.empty());
        appendTeamScores(lines, Team.RED, tm, group);

        Collection<? extends Player> audience = players != null ? players : Bukkit.getOnlinePlayers();
        for (Player p : audience) {
            for (Component line : lines) p.sendMessage(line);
        }

        persistResults(winner, tm, group, timeSrc);

        // Notifier le lobby (BungeeCord) de la fin de partie
        var bridge = LolPlugin.getInstance().getBridgeManager();
        if (bridge != null && bridge.isEnabled()) {
            long duration = timeSrc.getElapsedSeconds();
            List<fr.lolmc.stats.persistence.PlayerStats> endStats = new ArrayList<>();
            for (UUID id : stats.keySet()) {
                if (group != null && !group.contains(id)) continue;
                endStats.add(new fr.lolmc.stats.persistence.PlayerStats(id, ""));
            }
            bridge.notifyGameEnd(winner.name(), duration, endStats);
        }
    }

    private void persistResults(Team winner, fr.lolmc.team.TeamManager tm, Set<UUID> group, GameManager timeSrc) {
        var db = LolPlugin.getInstance().getDatabaseManager();
        var ranked = LolPlugin.getInstance().getRankedManager();
        if (db == null || ranked == null) return;

        List<UUID> winners = new ArrayList<>(tm.getTeamMembers(winner));
        List<UUID> losers = new ArrayList<>(tm.getTeamMembers(winner.opposite()));
        if (group != null) { winners.retainAll(group); losers.retainAll(group); }

        // Reporter les KDA de la partie dans les stats persistantes
        long duration = timeSrc.getElapsedSeconds();
        String matchId = java.util.UUID.randomUUID().toString(); // ID unique pour cette partie
        long now = System.currentTimeMillis();
        var tm2 = LolPlugin.getInstance().getTeamManager();
        var cm2 = LolPlugin.getInstance().getChampionManager();
        for (var entry : stats.entrySet()) {
            if (group != null && !group.contains(entry.getKey())) continue;
            MatchStats s = entry.getValue();
            boolean isRk = isRanked(entry.getKey());
            var mode = isRk ? fr.lolmc.stats.persistence.RankedManager.Mode.RANKED
                    : fr.lolmc.stats.persistence.RankedManager.Mode.NORMAL;
            org.bukkit.entity.Player mp = org.bukkit.Bukkit.getPlayer(entry.getKey());
            // Construire MatchRecord enrichi
            var mr = new fr.lolmc.stats.persistence.MatchRecord();
            mr.uuid    = entry.getKey();
            mr.matchId = matchId;
            mr.playedAt = now;
            mr.ranked  = isRk;
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
        boolean groupRanked = this.ranked;
        if (group != null) {
            groupRanked = false;
            for (UUID id : group) if (isRanked(id)) { groupRanked = true; break; }
        }
        if (groupRanked) {
            ranked.updateEloAfterRanked(winners, losers);
        } else {
            ranked.updateAfterNormal(winners, losers);
        }
    }

    public Map<UUID, MatchStats> getStats() { return stats; }
}
