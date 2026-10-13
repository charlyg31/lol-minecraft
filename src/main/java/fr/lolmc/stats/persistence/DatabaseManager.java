package fr.lolmc.stats.persistence;

import fr.lolmc.LolPlugin;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Couche d'accès aux données configurable.
 * Le type de base est choisi dans config.yml : "sqlite" (défaut) ou "mysql".
 *
 * Une seule connexion JDBC est partagée entre le thread principal, les tâches asynchrones
 * de fin de partie et les threads de l'API web : tous les accès à la base sont donc
 * synchronisés sur ce gestionnaire. Si la connexion échoue, un fallback mémoire est utilisé.
 */
public class DatabaseManager {

    private Connection connection;
    private String dbType;
    private volatile boolean available = false;

    // Cache mémoire (toujours utilisé, persisté en base)
    private final ConcurrentHashMap<UUID, PlayerStats> cache = new ConcurrentHashMap<>();

    public synchronized void init() {
        var config = LolPlugin.getInstance().getConfig();
        dbType = config.getString("database.type", "sqlite").toLowerCase();

        try {
            switch (dbType) {
                case "mysql" -> connectMySQL(config);
                case "sqlite" -> connectSQLite();
                default -> {
                    LolPlugin.getInstance().getLogger().warning(
                            "database.type « " + dbType + " » non supporté : utilisation de sqlite.");
                    dbType = "sqlite";
                    connectSQLite();
                }
            }
            createTables();
            available = true;
            LolPlugin.getInstance().getLogger().info("Base de données connectée: " + dbType);
        } catch (Exception e) {
            available = false;
            LolPlugin.getInstance().getLogger().warning(
                    "Connexion DB échouée (" + dbType + "): " + e.getMessage()
                    + " — mode mémoire activé (pas de persistance).");
        }
    }

    private boolean isSqlite() { return "sqlite".equals(dbType); }

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
        boolean ssl = config.getBoolean("database.mysql.ssl", false);
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=" + ssl + "&autoReconnect=true&characterEncoding=utf8";
        connection = DriverManager.getConnection(url, user, pass);
    }

    private void createTables() throws SQLException {
        if (connection == null) return;
        String autoId = isSqlite()
                ? "id INTEGER PRIMARY KEY AUTOINCREMENT"
                : "id BIGINT PRIMARY KEY AUTO_INCREMENT";
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
            st.execute("CREATE TABLE IF NOT EXISTS match_history (" + autoId + ","
                + """
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
                    items VARCHAR(1024) DEFAULT '[]',
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
        // Colonnes ajoutées après la première version : ajoutées aux bases existantes.
        addColumnIfMissing("player_stats", "ranked_damage_dealt", "BIGINT DEFAULT 0");
        addColumnIfMissing("player_stats", "ranked_damage_taken", "BIGINT DEFAULT 0");
        addColumnIfMissing("player_stats", "ranked_wards_placed", "INT DEFAULT 0");
        addColumnIfMissing("player_stats", "ranked_wards_killed", "INT DEFAULT 0");
        addColumnIfMissing("player_stats", "ranked_total_cs", "INT DEFAULT 0");
        addColumnIfMissing("player_stats", "normal_damage_dealt", "BIGINT DEFAULT 0");
        addColumnIfMissing("player_stats", "normal_total_cs", "INT DEFAULT 0");
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            if (rs.next()) return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ACCÈS AUX STATS
    // ══════════════════════════════════════════════════════════════

    /** Stats du joueur (cache, sinon base, sinon nouvelle fiche). */
    public PlayerStats getStats(UUID uuid, String name) {
        PlayerStats s = cache.computeIfAbsent(uuid, u -> loadOrCreate(u, name));
        if (name != null && !name.isEmpty()) s.name = name; // suivre les changements de pseudo
        return s;
    }

    /** Variante sans pseudo connu : le pseudo est retrouvé via le serveur. */
    public PlayerStats getStats(UUID uuid) {
        String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
        return getStats(uuid, name != null ? name : uuid.toString().substring(0, 8));
    }

    private synchronized PlayerStats loadOrCreate(UUID uuid, String name) {
        PlayerStats loaded = loadFromDb(uuid);
        if (loaded != null) return loaded;
        return new PlayerStats(uuid, name != null ? name : uuid.toString().substring(0, 8));
    }

    public synchronized void saveStats(PlayerStats s) {
        cache.put(s.uuid, s);
        if (!available || connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(upsertPlayerStatsSql())) {
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
            ps.setLong(14, s.rankedDamageDealt);
            ps.setLong(15, s.rankedDamageTaken);
            ps.setInt(16, s.rankedWardsPlaced);
            ps.setInt(17, s.rankedWardsKilled);
            ps.setInt(18, s.rankedTotalCS);
            ps.setLong(19, s.normalDamageDealt);
            ps.setInt(20, s.normalTotalCS);
            ps.executeUpdate();
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("Erreur sauvegarde stats: " + e.getMessage());
        }
    }

    private static final String PLAYER_STATS_COLUMNS =
            "uuid, name, elo, ranked_games, ranked_wins, ranked_kills, ranked_deaths, ranked_assists,"
            + " normal_games, normal_wins, normal_kills, normal_deaths, normal_assists,"
            + " ranked_damage_dealt, ranked_damage_taken, ranked_wards_placed, ranked_wards_killed,"
            + " ranked_total_cs, normal_damage_dealt, normal_total_cs";
    private static final String PLAYER_STATS_PARAMS = "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?";

    /** Requête d'upsert des stats joueur : SQLite n'a pas ON DUPLICATE KEY, on y utilise INSERT OR REPLACE. */
    private String upsertPlayerStatsSql() {
        if (isSqlite()) {
            return "INSERT OR REPLACE INTO player_stats (" + PLAYER_STATS_COLUMNS + ") VALUES (" + PLAYER_STATS_PARAMS + ")";
        }
        StringBuilder sb = new StringBuilder("INSERT INTO player_stats (" + PLAYER_STATS_COLUMNS
                + ") VALUES (" + PLAYER_STATS_PARAMS + ") ON DUPLICATE KEY UPDATE ");
        boolean first = true;
        for (String col : PLAYER_STATS_COLUMNS.split(",")) {
            col = col.trim();
            if (col.equals("uuid")) continue;
            if (!first) sb.append(", ");
            sb.append(col).append("=VALUES(").append(col).append(")");
            first = false;
        }
        return sb.toString();
    }

    public PlayerStats getCached(UUID uuid) {
        return cache.get(uuid);
    }

    public java.util.Collection<PlayerStats> getAllCached() {
        return cache.values();
    }

    /** Charge un joueur directement depuis la BDD (sans cache), avec ses stats par champion. */
    public synchronized PlayerStats loadFromDb(UUID uuid) {
        if (!available || connection == null) return null;
        PlayerStats s = null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM player_stats WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) s = mapRow(rs);
            }
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("loadFromDb: " + e.getMessage());
        }
        if (s != null) {
            for (PlayerStats.ChampionStats cs : getChampionStats(uuid)) s.champStats.put(cs.championId, cs);
        }
        return s;
    }

    /** Cherche un joueur par son pseudo (insensible à la casse). */
    public synchronized PlayerStats findByName(String name) {
        for (PlayerStats s : cache.values())
            if (s.name != null && s.name.equalsIgnoreCase(name)) return s;
        if (!available || connection == null) return null;
        UUID found = null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid FROM player_stats WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) found = UUID.fromString(rs.getString("uuid"));
            }
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("findByName: " + e.getMessage());
        }
        return found != null ? loadFromDb(found) : null;
    }

    /**
     * Meilleurs joueurs pour le classement.
     * @param normal true = tri par victoires en amical, false = tri par Elo
     */
    public synchronized java.util.List<PlayerStats> getTopPlayers(boolean normal, int limit) {
        java.util.List<PlayerStats> result = new java.util.ArrayList<>();
        if (!available || connection == null) return result;
        String order = normal ? "normal_wins DESC" : "elo DESC";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM player_stats ORDER BY " + order + " LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("getTopPlayers: " + e.getMessage());
        }
        return result;
    }

    /** Nombre de joueurs enregistrés en base. */
    public synchronized int countPlayers() {
        if (!available || connection == null) return cache.size();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM player_stats")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("countPlayers: " + e.getMessage());
        }
        return 0;
    }

    /** Sauvegarde une partie dans l'historique. */
    public synchronized void saveMatch(MatchRecord match) {
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
        String sql = isSqlite()
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
    public synchronized java.util.List<PlayerStats.ChampionStats> getChampionStats(UUID uuid) {
        java.util.List<PlayerStats.ChampionStats> result = new java.util.ArrayList<>();
        if (!available || connection == null) return result;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM champion_stats WHERE uuid=? ORDER BY games DESC")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerStats.ChampionStats cs = new PlayerStats.ChampionStats(rs.getString("champion"));
                    cs.games = rs.getInt("games"); cs.wins = rs.getInt("wins");
                    cs.kills = rs.getInt("kills"); cs.deaths = rs.getInt("deaths");
                    cs.assists = rs.getInt("assists"); cs.totalCS = rs.getInt("total_cs");
                    cs.totalDamage = rs.getLong("total_damage");
                    result.add(cs);
                }
            }
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("getChampionStats: " + e.getMessage());
        }
        return result;
    }

    /** Tous les joueurs d'une même partie (par matchId). */
    public synchronized java.util.List<MatchRecord> getMatchPlayers(String matchId) {
        java.util.List<MatchRecord> result = new java.util.ArrayList<>();
        if (!available || connection == null) return result;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM match_history WHERE match_id=? ORDER BY team,kills DESC")) {
            ps.setString(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapMatchRow(rs));
            }
        } catch (SQLException e) {
            LolPlugin.getInstance().getLogger().warning("getMatchPlayers: " + e.getMessage());
        }
        return result;
    }

    private MatchRecord mapMatchRow(ResultSet rs) throws SQLException {
        MatchRecord r = new MatchRecord();
        r.uuid = UUID.fromString(rs.getString("uuid"));
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
    public synchronized java.util.List<MatchRecord> getMatchHistory(UUID uuid, int limit) {
        java.util.List<MatchRecord> result = new java.util.ArrayList<>();
        if (!available || connection == null) return result;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM match_history WHERE uuid=? ORDER BY played_at DESC LIMIT ?")) {
            ps.setString(1, uuid.toString()); ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapMatchRow(rs));
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
        s.rankedDamageDealt = rs.getLong("ranked_damage_dealt");
        s.rankedDamageTaken = rs.getLong("ranked_damage_taken");
        s.rankedWardsPlaced = rs.getInt("ranked_wards_placed");
        s.rankedWardsKilled = rs.getInt("ranked_wards_killed");
        s.rankedTotalCS     = rs.getInt("ranked_total_cs");
        s.normalDamageDealt = rs.getLong("normal_damage_dealt");
        s.normalTotalCS     = rs.getInt("normal_total_cs");
        return s;
    }

    public synchronized void close() {
        available = false;
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ex) {
            LolPlugin.getInstance().getLogger().log(java.util.logging.Level.WARNING, "[DatabaseManager] fermeture de la connexion", ex);
        }
    }
}
