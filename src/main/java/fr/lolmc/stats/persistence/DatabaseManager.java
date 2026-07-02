package fr.lolmc.stats.persistence;

import fr.lolmc.LolPlugin;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Couche d'accès aux données configurable.
 * Le type de base est choisi dans config.yml : "sqlite", "mysql" ou "mongodb".
 *
 * Pour rester simple et robuste, MySQL et SQLite passent par JDBC.
 * MongoDB est géré séparément (driver mongo) — ici on fournit l'implémentation
 * JDBC complète et un fallback mémoire si la connexion échoue.
 */
public class DatabaseManager {

    private Connection connection;
    private String dbType;
    private boolean available = false;

    // Cache mémoire (toujours utilisé, persisté en base en arrière-plan)
    private final ConcurrentHashMap<UUID, PlayerStats> cache = new ConcurrentHashMap<>();

    public void init() {
        var config = LolPlugin.getInstance().getConfig();
        dbType = config.getString("database.type", "sqlite").toLowerCase();

        try {
            switch (dbType) {
                case "mysql" -> connectMySQL(config);
                case "mongodb" -> connectMongo(config);
                default -> connectSQLite();
            }
            available = true;
            createTables();
            LolPlugin.getInstance().getLogger().info("Base de données connectée: " + dbType);
        } catch (Exception e) {
            available = false;
            LolPlugin.getInstance().getLogger().warning(
                    "Connexion DB échouée (" + dbType + "): " + e.getMessage()
                    + " — mode mémoire activé (pas de persistance).");
        }
    }

    private void connectSQLite() throws Exception {
        Class.forName("org.sqlite.JDBC");
        java.io.File dbFile = new java.io.File(LolPlugin.getInstance().getDataFolder(), "stats.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    private void connectMySQL(org.bukkit.configuration.file.FileConfiguration config) throws Exception {
        String host = config.getString("database.mysql.host", "localhost");
        int port = config.getInt("database.mysql.port", 3306);
        String db = config.getString("database.mysql.database", "lolmc");
        String user = config.getString("database.mysql.user", "root");
        String pass = config.getString("database.mysql.password", "");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&autoReconnect=true";
        connection = DriverManager.getConnection(url, user, pass);
    }

    private void connectMongo(org.bukkit.configuration.file.FileConfiguration config) throws Exception {
        // MongoDB est géré via son propre driver, hors JDBC.
        // Pour la cohérence on délègue à MongoStorage (chargé seulement si choisi).
        throw new UnsupportedOperationException(
                "MongoDB: utilise MongoStorage (à activer). Bascule sur sqlite/mysql pour le moment.");
    }

    private void createTables() throws SQLException {
        if (connection == null) return;
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_stats (
                    uuid VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(32),
                    elo INT DEFAULT 1000,
                    ranked_games INT DEFAULT 0, ranked_wins INT DEFAULT 0,
                    ranked_kills INT DEFAULT 0, ranked_deaths INT DEFAULT 0, ranked_assists INT DEFAULT 0,
                    normal_games INT DEFAULT 0, normal_wins INT DEFAULT 0,
                    normal_kills INT DEFAULT 0, normal_deaths INT DEFAULT 0, normal_assists INT DEFAULT 0
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS match_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    match_id VARCHAR(36),
                    played_at BIGINT NOT NULL,
                    ranked BOOLEAN DEFAULT FALSE,
                    won BOOLEAN DEFAULT FALSE,
                    team VARCHAR(8) DEFAULT 'UNKNOWN',
                    champion VARCHAR(32),
                    kills INT DEFAULT 0,
                    deaths INT DEFAULT 0,
                    assists INT DEFAULT 0,
                    cs INT DEFAULT 0,
                    gold INT DEFAULT 0,
                    damage_dealt BIGINT DEFAULT 0,
                    damage_taken BIGINT DEFAULT 0,
                    healing_done BIGINT DEFAULT 0,
                    wards_placed INT DEFAULT 0,
                    wards_killed INT DEFAULT 0,
                    largest_killing_spree INT DEFAULT 0,
                    first_blood BOOLEAN DEFAULT FALSE,
                    items TEXT DEFAULT '[]',
                    duration_seconds INT DEFAULT 0
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS champion_stats (
                    uuid VARCHAR(36) NOT NULL,
                    champion VARCHAR(32) NOT NULL,
                    games INT DEFAULT 0,
                    wins INT DEFAULT 0,
                    kills INT DEFAULT 0,
                    deaths INT DEFAULT 0,
                    assists INT DEFAULT 0,
                    total_cs INT DEFAULT 0,
                    total_damage BIGINT DEFAULT 0,
                    PRIMARY KEY (uuid, champion)
                )""");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ACCÈS AUX STATS
    // ══════════════════════════════════════════════════════════════

    public PlayerStats getStats(UUID uuid, String name) {
        return cache.computeIfAbsent(uuid, u -> loadOrCreate(u, name));
    }

    private PlayerStats loadOrCreate(UUID uuid, String name) {
        if (available && connection != null) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    PlayerStats s = new PlayerStats(uuid, rs.getString("name"));
                    s.elo = rs.getInt("elo");
                    s.rankedGames = rs.getInt("ranked_games");
                    s.rankedWins = rs.getInt("ranked_wins");
                    s.rankedKills = rs.getInt("ranked_kills");
                    s.rankedDeaths = rs.getInt("ranked_deaths");
                    s.rankedAssists = rs.getInt("ranked_assists");
                    s.normalGames = rs.getInt("normal_games");
                    s.normalWins = rs.getInt("normal_wins");
                    s.normalKills = rs.getInt("normal_kills");
                    s.normalDeaths = rs.getInt("normal_deaths");
                    s.normalAssists = rs.getInt("normal_assists");
                    return s;
                }
            } catch (SQLException e) {
                LolPlugin.getInstance().getLogger().warning("Erreur chargement stats: " + e.getMessage());
            }
        }
        return new PlayerStats(uuid, name);
    }

    public void saveStats(PlayerStats s) {
        cache.put(s.uuid, s);
        if (!available || connection == null) return;
        String sql = """
            INSERT INTO player_stats (uuid, name, elo, ranked_games, ranked_wins, ranked_kills,
                ranked_deaths, ranked_assists, normal_games, normal_wins, normal_kills,
                normal_deaths, normal_assists)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE name=VALUES(name), elo=VALUES(elo),
                ranked_games=VALUES(ranked_games), ranked_wins=VALUES(ranked_wins),
                ranked_kills=VALUES(ranked_kills), ranked_deaths=VALUES(ranked_deaths),
                ranked_assists=VALUES(ranked_assists), normal_games=VALUES(normal_games),
                normal_wins=VALUES(normal_wins), normal_kills=VALUES(normal_kills),
                normal_deaths=VALUES(normal_deaths), normal_assists=VALUES(normal_assists)
            """;
        // SQLite ne supporte pas ON DUPLICATE KEY → utiliser INSERT OR REPLACE
        if (dbType.equals("sqlite")) {
            sql = """
                INSERT OR REPLACE INTO player_stats (uuid, name, elo, ranked_games, ranked_wins,
                    ranked_kills, ranked_deaths, ranked_assists, normal_games, normal_wins,
                    normal_kills, normal_deaths, normal_assists)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, s.uuid.toString());
            ps.setString(2, s.name);
            ps.setInt(3, s.elo);
            ps.setInt(4, s.rankedGames);
            ps.setInt(5, s.rankedWins);
            ps.setInt(6, s.rankedKills);
            ps.setInt(7, s.rankedDeaths);
            ps.setInt(8, s.rankedAssists);
            ps.setInt(9, s.normalGames);
            ps.setInt(10, s.normalWins);
            ps.setInt(11, s.normalKills);
            ps.setInt(12, s.normalDeaths);
            ps.setInt(13, s.normalAssists);
            ps.executeUpdate();
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("Erreur sauvegarde stats: " + e.getMessage());
        }
    }

    public PlayerStats getCached(UUID uuid) {
        return cache.get(uuid);
    }

    public java.util.Collection<PlayerStats> getAllCached() {
        return cache.values();
    }


    /** Charge un joueur directement depuis la BDD (sans cache). */
    public PlayerStats loadFromDb(UUID uuid) {
        if (!available || connection == null) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM player_stats WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("loadFromDb: " + e.getMessage());
        }
        return null;
    }

    /** Cherche un joueur par son pseudo (insensible à la casse). */
    public PlayerStats findByName(String name) {
        for (PlayerStats s : cache.values())
            if (s.name.equalsIgnoreCase(name)) return s;
        if (!available || connection == null) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM player_stats WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("findByName: " + e.getMessage());
        }
        return null;
    }

    /** Charge tous les joueurs depuis la BDD (leaderboard complet). */
    public java.util.List<PlayerStats> getAllFromDb() {
        java.util.List<PlayerStats> result = new java.util.ArrayList<>();
        if (!available || connection == null) return result;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM player_stats ORDER BY elo DESC")) {
            while (rs.next()) result.add(mapRow(rs));
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("getAllFromDb: " + e.getMessage());
        }
        return result;
    }

    /** Sauvegarde une partie dans l'historique. */
    public void saveMatch(MatchRecord match) {
        if (!available || connection == null) return;
        String sql = "INSERT INTO match_history "
            + "(uuid,match_id,played_at,ranked,won,team,champion,kills,deaths,assists,"
            + "cs,gold,damage_dealt,damage_taken,healing_done,wards_placed,wards_killed,"
            + "largest_killing_spree,first_blood,items,duration_seconds)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, match.uuid.toString());
            ps.setString(2, match.matchId != null ? match.matchId : "");
            ps.setLong(3, match.playedAt);          ps.setBoolean(4, match.ranked);
            ps.setBoolean(5, match.won);            ps.setString(6, match.team != null ? match.team : "UNKNOWN");
            ps.setString(7, match.champion);        ps.setInt(8, match.kills);
            ps.setInt(9, match.deaths);             ps.setInt(10, match.assists);
            ps.setInt(11, match.cs);                ps.setInt(12, match.gold);
            ps.setLong(13, match.damageDealt);      ps.setLong(14, match.damageTaken);
            ps.setLong(15, match.healingDone);      ps.setInt(16, match.wardsPlaced);
            ps.setInt(17, match.wardsKilled);       ps.setInt(18, match.largestKillingSpree);
            ps.setBoolean(19, match.firstBlood);    ps.setString(20, match.items != null ? match.items : "[]");
            ps.setInt(21, match.durationSeconds);
            ps.executeUpdate();
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("saveMatch: " + e.getMessage());
        }
        // Mettre à jour champion_stats
        if (match.champion != null && !match.champion.isEmpty()) saveChampionStats(match);
    }

    private void saveChampionStats(MatchRecord m) {
        if (!available || connection == null) return;
        String sql = dbType.equals("sqlite")
            ? "INSERT INTO champion_stats (uuid,champion,games,wins,kills,deaths,assists,total_cs,total_damage) VALUES (?,?,1,?,?,?,?,?,?) ON CONFLICT(uuid,champion) DO UPDATE SET games=games+1,wins=wins+excluded.wins,kills=kills+excluded.kills,deaths=deaths+excluded.deaths,assists=assists+excluded.assists,total_cs=total_cs+excluded.total_cs,total_damage=total_damage+excluded.total_damage"
            : "INSERT INTO champion_stats (uuid,champion,games,wins,kills,deaths,assists,total_cs,total_damage) VALUES (?,?,1,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE games=games+1,wins=wins+VALUES(wins),kills=kills+VALUES(kills),deaths=deaths+VALUES(deaths),assists=assists+VALUES(assists),total_cs=total_cs+VALUES(total_cs),total_damage=total_damage+VALUES(total_damage)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, m.uuid.toString()); ps.setString(2, m.champion);
            ps.setInt(3, m.won ? 1 : 0);       ps.setInt(4, m.kills);
            ps.setInt(5, m.deaths);             ps.setInt(6, m.assists);
            ps.setInt(7, m.cs);                 ps.setLong(8, m.damageDealt);
            ps.executeUpdate();
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("saveChampionStats: " + e.getMessage());
        }
    }

    /** Stats par champion d'un joueur. */
    public java.util.List<PlayerStats.ChampionStats> getChampionStats(java.util.UUID uuid) {
        java.util.List<PlayerStats.ChampionStats> result = new java.util.ArrayList<>();
        if (!available || connection == null) return result;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM champion_stats WHERE uuid=? ORDER BY games DESC")) {
            ps.setString(1, uuid.toString());
            java.sql.ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                PlayerStats.ChampionStats cs = new PlayerStats.ChampionStats(rs.getString("champion"));
                cs.games = rs.getInt("games"); cs.wins = rs.getInt("wins");
                cs.kills = rs.getInt("kills"); cs.deaths = rs.getInt("deaths");
                cs.assists = rs.getInt("assists"); cs.totalCS = rs.getInt("total_cs");
                cs.totalDamage = rs.getLong("total_damage");
                result.add(cs);
            }
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("getChampionStats: " + e.getMessage());
        }
        return result;
    }

    /** Tous les joueurs d'une même partie (par matchId). */
    public java.util.List<MatchRecord> getMatchPlayers(String matchId) {
        java.util.List<MatchRecord> result = new java.util.ArrayList<>();
        if (!available || connection == null) return result;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM match_history WHERE match_id=? ORDER BY team,kills DESC")) {
            ps.setString(1, matchId);
            java.sql.ResultSet rs = ps.executeQuery();
            while (rs.next()) result.add(mapMatchRow(rs));
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("getMatchPlayers: " + e.getMessage());
        }
        return result;
    }

    private MatchRecord mapMatchRow(java.sql.ResultSet rs) throws SQLException {
        MatchRecord r = new MatchRecord();
        r.uuid = java.util.UUID.fromString(rs.getString("uuid"));
        r.matchId = rs.getString("match_id");
        r.playedAt = rs.getLong("played_at");        r.ranked = rs.getBoolean("ranked");
        r.won = rs.getBoolean("won");                r.team = rs.getString("team");
        r.champion = rs.getString("champion");        r.kills = rs.getInt("kills");
        r.deaths = rs.getInt("deaths");              r.assists = rs.getInt("assists");
        r.cs = rs.getInt("cs");                      r.gold = rs.getInt("gold");
        r.damageDealt = rs.getLong("damage_dealt");  r.damageTaken = rs.getLong("damage_taken");
        r.healingDone = rs.getLong("healing_done");  r.wardsPlaced = rs.getInt("wards_placed");
        r.wardsKilled = rs.getInt("wards_killed");
        r.largestKillingSpree = rs.getInt("largest_killing_spree");
        r.firstBlood = rs.getBoolean("first_blood"); r.items = rs.getString("items");
        r.durationSeconds = rs.getInt("duration_seconds");
        return r;
    }

    /** Retourne les N dernières parties d'un joueur. */
    public java.util.List<MatchRecord> getMatchHistory(UUID uuid, int limit) {
        java.util.List<MatchRecord> result = new java.util.ArrayList<>();
        if (!available || connection == null) return result;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM match_history WHERE uuid=? ORDER BY played_at DESC LIMIT ?")) {
            ps.setString(1, uuid.toString()); ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                MatchRecord r = new MatchRecord();
                r.uuid = UUID.fromString(rs.getString("uuid"));
                r.playedAt = rs.getLong("played_at"); r.ranked = rs.getBoolean("ranked");
                r.won = rs.getBoolean("won");         r.champion = rs.getString("champion");
                r.kills = rs.getInt("kills");         r.deaths = rs.getInt("deaths");
                r.assists = rs.getInt("assists");     r.cs = rs.getInt("cs");
                r.gold = rs.getInt("gold");           r.damageDealt = rs.getLong("damage_dealt");
                r.durationSeconds = rs.getInt("duration_seconds");
                result.add(r);
            }
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("getMatchHistory: " + e.getMessage());
        }
        return result;
    }

    private PlayerStats mapRow(ResultSet rs) throws SQLException {
        PlayerStats s = new PlayerStats(
            UUID.fromString(rs.getString("uuid")), rs.getString("name"));
        s.elo = rs.getInt("elo");
        s.rankedGames   = rs.getInt("ranked_games");  s.rankedWins    = rs.getInt("ranked_wins");
        s.rankedKills   = rs.getInt("ranked_kills");  s.rankedDeaths  = rs.getInt("ranked_deaths");
        s.rankedAssists = rs.getInt("ranked_assists");
        s.normalGames   = rs.getInt("normal_games");  s.normalWins    = rs.getInt("normal_wins");
        s.normalKills   = rs.getInt("normal_kills");  s.normalDeaths  = rs.getInt("normal_deaths");
        s.normalAssists = rs.getInt("normal_assists");
        return s;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
