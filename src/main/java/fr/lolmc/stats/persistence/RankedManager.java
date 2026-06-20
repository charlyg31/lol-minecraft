package fr.lolmc.stats.persistence;

import fr.lolmc.LolPlugin;

import java.util.List;
import java.util.UUID;

/**
 * Gère le matchmaking classé/amical et le calcul de l'Elo.
 *
 * - Mode CLASSÉ : compte dans le classement, ajuste l'Elo selon victoire/défaite,
 *   et le matchmaking cherche des joueurs de niveau (Elo) proche.
 * - Mode AMICAL : ne compte pas dans le classé, stats séparées.
 */
public class RankedManager {

    public enum Mode { RANKED, NORMAL }

    private final DatabaseManager database;

    public RankedManager(DatabaseManager database) {
        this.database = database;
    }

    // ── Calcul Elo (formule standard) ─────────────────────────────

    /**
     * Met à jour l'Elo des deux équipes après une partie classée.
     * @param winners équipe gagnante
     * @param losers  équipe perdante
     */
    public void updateEloAfterRanked(List<UUID> winners, List<UUID> losers) {
        double avgWinner = averageElo(winners);
        double avgLoser = averageElo(losers);

        // Probabilité attendue de victoire (formule Elo)
        double expectedWinner = 1.0 / (1.0 + Math.pow(10, (avgLoser - avgWinner) / 400.0));
        double expectedLoser = 1.0 - expectedWinner;

        int K = 32; // facteur K

        for (UUID id : winners) {
            PlayerStats s = database.getCached(id);
            if (s == null) continue;
            s.elo += (int) Math.round(K * (1.0 - expectedWinner));
            s.rankedGames++;
            s.rankedWins++;
            database.saveStats(s);
        }
        for (UUID id : losers) {
            PlayerStats s = database.getCached(id);
            if (s == null) continue;
            s.elo += (int) Math.round(K * (0.0 - expectedLoser));
            if (s.elo < 0) s.elo = 0;
            s.rankedGames++;
            database.saveStats(s);
        }
    }

    /** Met à jour les stats après une partie amicale (sans Elo). */
    public void updateAfterNormal(List<UUID> winners, List<UUID> losers) {
        for (UUID id : winners) {
            PlayerStats s = database.getCached(id);
            if (s == null) continue;
            s.normalGames++;
            s.normalWins++;
            database.saveStats(s);
        }
        for (UUID id : losers) {
            PlayerStats s = database.getCached(id);
            if (s == null) continue;
            s.normalGames++;
            database.saveStats(s);
        }
    }

    private double averageElo(List<UUID> players) {
        if (players.isEmpty()) return 1000;
        double sum = 0;
        int count = 0;
        for (UUID id : players) {
            PlayerStats s = database.getCached(id);
            if (s != null) { sum += s.elo; count++; }
        }
        return count == 0 ? 1000 : sum / count;
    }

    /**
     * Vérifie si deux joueurs ont un Elo assez proche pour jouer ensemble en classé.
     * Tolérance : 200 points d'écart.
     */
    public boolean eloCompatible(UUID a, UUID b) {
        PlayerStats sa = database.getCached(a);
        PlayerStats sb = database.getCached(b);
        if (sa == null || sb == null) return true;
        return Math.abs(sa.elo - sb.elo) <= 200;
    }

    // ── Enregistrement des stats de partie (KDA) ──────────────────

    public void recordKill(UUID killer, Mode mode) {
        PlayerStats s = database.getCached(killer);
        if (s == null) return;
        if (mode == Mode.RANKED) s.rankedKills++;
        else s.normalKills++;
    }

    public void recordDeath(UUID victim, Mode mode) {
        PlayerStats s = database.getCached(victim);
        if (s == null) return;
        if (mode == Mode.RANKED) s.rankedDeaths++;
        else s.normalDeaths++;
    }

    public void recordAssist(UUID assistant, Mode mode) {
        PlayerStats s = database.getCached(assistant);
        if (s == null) return;
        if (mode == Mode.RANKED) s.rankedAssists++;
        else s.normalAssists++;
    }
}
