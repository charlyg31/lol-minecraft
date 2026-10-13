package fr.lolmc.stats.persistence;


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
     * Les compteurs de parties/victoires sont déjà mis à jour par {@link PlayerStats#recordMatch}.
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
            PlayerStats s = database.getStats(id);
            s.elo += (int) Math.round(K * (1.0 - expectedWinner));
            database.saveStats(s);
        }
        for (UUID id : losers) {
            PlayerStats s = database.getStats(id);
            s.elo += (int) Math.round(K * (0.0 - expectedLoser));
            if (s.elo < 0) s.elo = 0;
            database.saveStats(s);
        }
    }

    private double averageElo(List<UUID> players) {
        if (players.isEmpty()) return 1000;
        double sum = 0;
        int count = 0;
        for (UUID id : players) {
            sum += database.getStats(id).elo;
            count++;
        }
        return count == 0 ? 1000 : sum / count;
    }
}
