package fr.lolmc.stats.persistence;

import java.util.UUID;

/**
 * Une partie jouée — une ligne dans match_history.
 * matchId regroupe les 10 joueurs d'une même partie.
 */
public class MatchRecord {
    public UUID   uuid;
    public String matchId;       // UUID de la partie (même valeur pour les 10 joueurs)
    public long   playedAt;      // timestamp Unix ms
    public boolean ranked;
    public boolean won;
    public String champion;
    public String team;          // "BLUE" ou "RED"
    public int kills, deaths, assists, cs, gold;
    public long damageDealt;
    public long damageTaken;
    public long healingDone;
    public int wardsPlaced;
    public int wardsKilled;
    public int largestKillingSpree;
    public boolean firstBlood;
    public String items;         // JSON array des item IDs: "[item1,item2,...]"
    public int durationSeconds;

    public double kda() {
        return deaths == 0 ? kills + assists : (kills + assists) / (double) deaths;
    }
    public double csPerMin() {
        return durationSeconds == 0 ? 0 : cs / (durationSeconds / 60.0);
    }
    public double damagePerMin() {
        return durationSeconds == 0 ? 0 : damageDealt / (durationSeconds / 60.0);
    }

    public String toJson() {
        return String.format(java.util.Locale.US,
            "{\"uuid\":\"%s\",\"match_id\":\"%s\",\"played_at\":%d,"
            + "\"ranked\":%b,\"won\":%b,\"team\":\"%s\","
            + "\"champion\":\"%s\",\"kills\":%d,\"deaths\":%d,\"assists\":%d,"
            + "\"kda\":%.2f,\"cs\":%d,\"cs_per_min\":%.1f,"
            + "\"gold\":%d,\"damage_dealt\":%d,\"damage_taken\":%d,"
            + "\"healing_done\":%d,\"wards_placed\":%d,\"wards_killed\":%d,"
            + "\"first_blood\":%b,\"largest_killing_spree\":%d,"
            + "\"items\":%s,\"duration_seconds\":%d,\"damage_per_min\":%.0f}",
            uuid, matchId != null ? matchId : "", playedAt,
            ranked, won, team != null ? team : "UNKNOWN",
            champion, kills, deaths, assists, kda(), cs, csPerMin(),
            gold, damageDealt, damageTaken, healingDone,
            wardsPlaced, wardsKilled, firstBlood, largestKillingSpree,
            items != null ? items : "[]", durationSeconds, damagePerMin());
    }
}
