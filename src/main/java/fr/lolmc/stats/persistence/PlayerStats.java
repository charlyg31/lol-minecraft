package fr.lolmc.stats.persistence;

import java.util.*;

/**
 * Statistiques persistantes d'un joueur.
 * Séparé entre classé (ranked) et amical (normal).
 * Inclut les stats par champion pour un profil type OP.gg.
 */
public class PlayerStats {

    public UUID uuid;
    public String name;

    // ── Classé ────────────────────────────────────────────────────
    public int rankedGames = 0, rankedWins = 0;
    public int rankedKills = 0, rankedDeaths = 0, rankedAssists = 0;
    public long rankedDamageDealt = 0, rankedDamageTaken = 0;
    public int rankedWardsPlaced = 0, rankedWardsKilled = 0;
    public int rankedTotalCS = 0;
    public int elo = 1000;

    // ── Normal ────────────────────────────────────────────────────
    public int normalGames = 0, normalWins = 0;
    public int normalKills = 0, normalDeaths = 0, normalAssists = 0;
    public long normalDamageDealt = 0;
    public int normalTotalCS = 0;

    // ── Stats par champion (champion_id → ChampionStats) ──────────
    public final Map<String, ChampionStats> champStats = new HashMap<>();

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ── Calculs dérivés ───────────────────────────────────────────

    public double rankedKDA() {
        return rankedDeaths == 0 ? rankedKills + rankedAssists
            : (rankedKills + rankedAssists) / (double) rankedDeaths;
    }
    public double rankedWinrate()  { return rankedGames == 0 ? 0 : rankedWins * 100.0 / rankedGames; }
    public double normalWinrate()  { return normalGames == 0 ? 0 : normalWins * 100.0 / normalGames; }
    public double rankedAvgCS()    { return rankedGames == 0 ? 0 : rankedTotalCS / (double) rankedGames; }
    public double rankedAvgDmg()   { return rankedGames == 0 ? 0 : rankedDamageDealt / (double) rankedGames; }

    /** Rang LoL selon l'Elo. */
    public String getTier() {
        if (elo < 800)  return "Fer";
        if (elo < 1000) return "Bronze";
        if (elo < 1200) return "Argent";
        if (elo < 1400) return "Or";
        if (elo < 1600) return "Platine";
        if (elo < 1800) return "Émeraude";
        if (elo < 2000) return "Diamant";
        if (elo < 2200) return "Maître";
        return "Challenger";
    }

    /** Enregistre les stats d'une partie terminée pour ce joueur. */
    public void recordMatch(MatchRecord mr) {
        if (mr.ranked) {
            rankedGames++; if (mr.won) rankedWins++;
            rankedKills += mr.kills; rankedDeaths += mr.deaths; rankedAssists += mr.assists;
            rankedDamageDealt += mr.damageDealt; rankedDamageTaken += mr.damageTaken;
            rankedWardsPlaced += mr.wardsPlaced; rankedWardsKilled += mr.wardsKilled;
            rankedTotalCS += mr.cs;
        } else {
            normalGames++; if (mr.won) normalWins++;
            normalKills += mr.kills; normalDeaths += mr.deaths; normalAssists += mr.assists;
            normalDamageDealt += mr.damageDealt;
            normalTotalCS += mr.cs;
        }
        if (mr.champion != null && !mr.champion.isEmpty()) {
            champStats.computeIfAbsent(mr.champion, ChampionStats::new).record(mr);
        }
    }

    /** Sérialise en JSON complet pour l'API. */
    public String toJson() {
        // Top 3 champions par parties jouées
        var topChamps = champStats.values().stream()
            .sorted((a,b) -> b.games - a.games)
            .limit(3).toList();
        StringBuilder champJson = new StringBuilder("[");
        for (int i = 0; i < topChamps.size(); i++) {
            if (i > 0) champJson.append(",");
            champJson.append(topChamps.get(i).toJson());
        }
        champJson.append("]");

        return String.format(java.util.Locale.US,
            "{\"uuid\":\"%s\",\"name\":\"%s\",\"elo\":%d,\"tier\":\"%s\"," +
            "\"ranked\":{\"games\":%d,\"wins\":%d,\"losses\":%d," +
            "\"kills\":%d,\"deaths\":%d,\"assists\":%d,\"kda\":%.2f," +
            "\"winrate\":%.1f,\"avg_cs\":%.1f,\"avg_damage\":%.0f," +
            "\"wards_placed\":%d,\"wards_killed\":%d}," +
            "\"normal\":{\"games\":%d,\"wins\":%d,\"losses\":%d," +
            "\"kills\":%d,\"deaths\":%d,\"assists\":%d,\"winrate\":%.1f}," +
            "\"top_champions\":%s}",
            uuid, name, elo, getTier(),
            rankedGames, rankedWins, rankedGames - rankedWins,
            rankedKills, rankedDeaths, rankedAssists, rankedKDA(),
            rankedWinrate(), rankedAvgCS(), rankedAvgDmg(),
            rankedWardsPlaced, rankedWardsKilled,
            normalGames, normalWins, normalGames - normalWins,
            normalKills, normalDeaths, normalAssists, normalWinrate(),
            champJson);
    }

    // ── Stats par champion ─────────────────────────────────────────

    public static class ChampionStats {
        public final String championId;
        public int games, wins, kills, deaths, assists, totalCS;
        public long totalDamage;

        public ChampionStats(String id) { this.championId = id; }

        public void record(MatchRecord mr) {
            games++; if (mr.won) wins++;
            kills += mr.kills; deaths += mr.deaths; assists += mr.assists;
            totalCS += mr.cs; totalDamage += mr.damageDealt;
        }
        public double kda()      { return deaths == 0 ? kills+assists : (kills+assists)/(double)deaths; }
        public double winrate()  { return games == 0 ? 0 : wins*100.0/games; }
        public double avgCS()    { return games == 0 ? 0 : totalCS/(double)games; }

        public String toJson() {
            return String.format(java.util.Locale.US,
                "{\"champion\":\"%s\",\"games\":%d,\"wins\":%d," +
                "\"kda\":%.2f,\"winrate\":%.1f,\"avg_cs\":%.1f}",
                championId, games, wins, kda(), winrate(), avgCS());
        }
    }
}
