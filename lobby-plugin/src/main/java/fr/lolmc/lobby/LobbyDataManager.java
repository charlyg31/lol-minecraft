package fr.lolmc.lobby;

import java.sql.*;

/** Accès SQLite pour les statistiques du lobby (ELO, historique...). */
public class LobbyDataManager {

    private final LobbyPlugin plugin;
    private Connection conn;

    public LobbyDataManager(LobbyPlugin plugin) {
        this.plugin = plugin;
        try {
            String path = plugin.getDataFolder().getAbsolutePath() + "/lolmc_lobby.db";
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS players ("
                    + "uuid TEXT PRIMARY KEY, name TEXT, elo INTEGER DEFAULT 1200, wins INTEGER DEFAULT 0, losses INTEGER DEFAULT 0)");
            }
            plugin.getLogger().info("[Data] Base de données lobby connectée.");
        } catch (SQLException e) {
            plugin.getLogger().warning("[Data] Erreur DB: " + e.getMessage());
        }
    }

    public int getElo(java.util.UUID uuid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT elo FROM players WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("elo");
        } catch (SQLException ignored) {}
        return 1200;
    }

    public void close() {
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
    }
}
