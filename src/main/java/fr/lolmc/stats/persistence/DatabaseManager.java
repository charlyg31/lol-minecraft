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
                    played_at BIGINT NOT NULL,
                    ranked BOOLEAN DEFAULT FALSE,
                    won BOOLEAN DEFAULT FALSE,
                    champion VARCHAR(32),
                    kills INT DEFAULT 0,
                    deaths INT DEFAULT 0,
                    assists INT DEFAULT 0,
                    cs INT DEFAULT 0,
                    gold INT DEFAULT 0,
                    damage_dealt BIGINT DEFAULT 0,
                    duration_seconds INT DEFAULT 0
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
            + "(uuid,played_at,ranked,won,champion,kills,deaths,assists,cs,gold,damage_dealt,duration_seconds)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, match.uuid.toString()); ps.setLong(2, match.playedAt);
            ps.setBoolean(3, match.ranked);         ps.setBoolean(4, match.won);
            ps.setString(5, match.champion);        ps.setInt(6, match.kills);
            ps.setInt(7, match.deaths);             ps.setInt(8, match.assists);
            ps.setInt(9, match.cs);                 ps.setInt(10, match.gold);
            ps.setLong(11, match.damageDealt);      ps.setInt(12, match.durationSeconds);
            ps.executeUpdate();
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("saveMatch: " + e.getMessage());
        }
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
