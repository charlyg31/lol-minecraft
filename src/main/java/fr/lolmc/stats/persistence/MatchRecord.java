package fr.lolmc.stats.persistence;

import java.util.UUID;

/** Une partie jouée — une ligne dans match_history. */
public class MatchRecord {
    public UUID uuid;
    public long playedAt;       // timestamp Unix ms
    public boolean ranked;
    public boolean won;
    public String champion;
    public int kills, deaths, assists, cs, gold;
    public long damageDealt;
    public int durationSeconds;

    public String toJson() {
        return String.format(java.util.Locale.US,
            "{\"uuid\":\"%s\",\"played_at\":%d,\"ranked\":%b,\"won\":%b,"
            + "\"champion\":\"%s\",\"kills\":%d,\"deaths\":%d,\"assists\":%d,"
            + "\"cs\":%d,\"gold\":%d,\"damage_dealt\":%d,\"duration_seconds\":%d,"
            + "\"kda\":\"%.1f\"}",
            uuid, playedAt, ranked, won, champion,
            kills, deaths, assists, cs, gold, damageDealt, durationSeconds,
            deaths == 0 ? kills + assists : (kills + assists) / (double) deaths);
    }
}
