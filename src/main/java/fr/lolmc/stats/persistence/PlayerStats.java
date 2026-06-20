package fr.lolmc.stats.persistence;

import java.util.UUID;

/**
 * Statistiques persistantes d'un joueur (une ligne en base).
 * Séparé entre parties classées (ranked) et amicales (normal).
 */
public class PlayerStats {

    public UUID uuid;
    public String name;

    // Classé
    public int rankedGames = 0;
    public int rankedWins = 0;
    public int rankedKills = 0;
    public int rankedDeaths = 0;
    public int rankedAssists = 0;
    public int elo = 1000;        // classement Elo (départ 1000)

    // Amical
    public int normalGames = 0;
    public int normalWins = 0;
    public int normalKills = 0;
    public int normalDeaths = 0;
    public int normalAssists = 0;

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public double rankedKDA() {
        return rankedDeaths == 0 ? rankedKills + rankedAssists
                : (rankedKills + rankedAssists) / (double) rankedDeaths;
    }

    public double rankedWinrate() {
        return rankedGames == 0 ? 0 : (rankedWins * 100.0) / rankedGames;
    }

    public double normalWinrate() {
        return normalGames == 0 ? 0 : (normalWins * 100.0) / normalGames;
    }

    /** Rang nommé selon l'Elo (façon LoL). */
    public String getTier() {
        if (elo < 800) return "Fer";
        if (elo < 1000) return "Bronze";
        if (elo < 1200) return "Argent";
        if (elo < 1400) return "Or";
        if (elo < 1600) return "Platine";
        if (elo < 1800) return "Émeraude";
        if (elo < 2000) return "Diamant";
        if (elo < 2200) return "Maître";
        return "Challenger";
    }

    /** Sérialisation JSON simple (pour l'API). */
    public String toJson() {
        return String.format(java.util.Locale.US,
            "{\"uuid\":\"%s\",\"name\":\"%s\",\"elo\":%d,\"tier\":\"%s\","
            + "\"ranked\":{\"games\":%d,\"wins\":%d,\"kills\":%d,\"deaths\":%d,\"assists\":%d,\"kda\":%.2f,\"winrate\":%.1f},"
            + "\"normal\":{\"games\":%d,\"wins\":%d,\"kills\":%d,\"deaths\":%d,\"assists\":%d,\"winrate\":%.1f}}",
            uuid, name, elo, getTier(),
            rankedGames, rankedWins, rankedKills, rankedDeaths, rankedAssists, rankedKDA(), rankedWinrate(),
            normalGames, normalWins, normalKills, normalDeaths, normalAssists, normalWinrate());
    }
}
