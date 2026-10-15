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
    public boolean afk;          // signalé absent (AFK) pendant la partie
    public int honors;           // honneurs reçus des alliés en fin de partie
    public String keystone;      // id de la keystone
    public String runes;         // JSON array des ids de runes actives
    public String spells;        // JSON array des sorts d'invocateur ("FLASH", "IGNITE"…)
    public int season = 1;
    public String name;          // pseudo (lu depuis player_stats, non stocké dans match_history)

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
            + "\"items\":%s,\"duration_seconds\":%d,\"damage_per_min\":%.0f,"
            + "\"name\":%s,\"afk\":%b,\"honors\":%d,\"keystone\":\"%s\","
            + "\"runes\":%s,\"spells\":%s,\"season\":%d}",
            uuid, Json.esc(matchId), playedAt,
            ranked, won, team != null ? Json.esc(team) : "UNKNOWN",
            Json.esc(champion), kills, deaths, assists, kda(), cs, csPerMin(),
            gold, damageDealt, damageTaken, healingDone,
            wardsPlaced, wardsKilled, firstBlood, largestKillingSpree,
            jsonArray(items), durationSeconds, damagePerMin(),
            name != null ? "\"" + Json.esc(name) + "\"" : "null", afk, honors,
            Json.esc(keystone), jsonArray(runes), jsonArray(spells), season);
    }

    /** Liste JSON produite par le plugin (ex. ["a","b"]), sinon []. */
    private static String jsonArray(String s) {
        return s != null && s.startsWith("[") && s.endsWith("]") ? s : "[]";
    }

    /** Construit une liste JSON de chaînes. */
    public static String toJsonArray(java.util.Collection<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (String v : values) {
            if (v == null) continue;
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(Json.esc(v)).append("\"");
        }
        return sb.append("]").toString();
    }

    /** Lit une liste JSON de chaînes produite par {@link #toJsonArray}. */
    public static java.util.List<String> parseJsonArray(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) return out;
        var m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(s);
        while (m.find()) out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        return out;
    }
}
