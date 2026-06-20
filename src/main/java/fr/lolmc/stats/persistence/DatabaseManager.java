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
        String sql = """
            CREATE TABLE IF NOT EXISTS player_stats (
                uuid VARCHAR(36) PRIMARY KEY,
                name VARCHAR(32),
                elo INT DEFAULT 1000,
                ranked_games INT DEFAULT 0, ranked_wins INT DEFAULT 0,
                ranked_kills INT DEFAULT 0, ranked_deaths INT DEFAULT 0, ranked_assists INT DEFAULT 0,
                normal_games INT DEFAULT 0, normal_wins INT DEFAULT 0,
                normal_kills INT DEFAULT 0, normal_deaths INT DEFAULT 0, normal_assists INT DEFAULT 0
            )
            """;
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
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

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
