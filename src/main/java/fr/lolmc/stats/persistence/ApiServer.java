package fr.lolmc.stats.persistence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.lolmc.LolPlugin;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Serveur HTTP REST exposant les statistiques LoLMC.
 *
 * Routes :
 *   GET /api/player/{uuid}           → stats complètes par UUID
 *   GET /api/player/name/{name}      → stats par pseudo (recherche BDD)
 *   GET /api/leaderboard             → top 50 Elo classé
 *   GET /api/leaderboard/normal      → top 50 victoires normal
 *   GET /api/match/history/{uuid}    → historique des 20 dernières parties
 *   GET /api/online                  → joueurs actuellement en ligne
 *
 * Authentification optionnelle : header "X-Api-Key: <clé>" si api.key est défini.
 *
 * Config config.yml :
 *   api:
 *     enabled: false
 *     port: 8080
 *     key: ""        # laisser vide = pas d'auth
 */
public class ApiServer {

    private HttpServer server;
    private final DatabaseManager database;
    private String apiKey;

    public ApiServer(DatabaseManager database) {
        this.database = database;
    }

    public void start() {
        var config = LolPlugin.getInstance().getConfig();
        if (!config.getBoolean("api.enabled", false)) {
            LolPlugin.getInstance().getLogger().info("API web désactivée (api.enabled: false).");
            return;
        }
        int port   = config.getInt("api.port", 8080);
        apiKey     = config.getString("api.key", "").trim();

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/api/player/name/",        this::handlePlayerByName);
            server.createContext("/api/player/",               this::handlePlayerByUuid);
            server.createContext("/api/player-champions/",     this::handlePlayerChampions);
            server.createContext("/api/leaderboard",           this::handleLeaderboard);
            server.createContext("/api/match/detail/",         this::handleMatchDetail);
            server.createContext("/api/match/history/",        this::handleMatchHistory);
            server.createContext("/api/champions",             this::handleChampionsList);
            server.createContext("/api/online",                this::handleOnline);
            server.createContext("/api/status",                this::handleStatus);

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.start();
            LolPlugin.getInstance().getLogger().info(
                "API web démarrée sur le port " + port
                + (apiKey.isEmpty() ? " (sans authentification)" : " (clé API requise)"));
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("Échec démarrage API: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) { server.stop(0); server = null; }
    }

    // ── Auth ──────────────────────────────────────────────────────

    private boolean isAuthorized(HttpExchange ex) {
        if (apiKey == null || apiKey.isEmpty()) return true;
        String header = ex.getRequestHeaders().getFirst("X-Api-Key");
        return apiKey.equals(header);
    }

    // ── Routes ────────────────────────────────────────────────────

    /** GET /api/player/{uuid} */
    private void handlePlayerByUuid(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String path = ex.getRequestURI().getPath();
        String uuidStr = path.substring("/api/player/".length()).replaceAll("/.*", "");
        try {
            UUID uuid = UUID.fromString(uuidStr);
            // Chercher en cache d'abord, puis en BDD
            PlayerStats stats = database.getCached(uuid);
            if (stats == null) stats = database.loadFromDb(uuid);
            if (stats == null) { respond(ex, 404, err("player not found")); return; }
            respond(ex, 200, stats.toJson());
        } catch (IllegalArgumentException e) {
            respond(ex, 400, err("invalid uuid"));
        }
    }

    /** GET /api/player/name/{name} */
    private void handlePlayerByName(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String path = ex.getRequestURI().getPath();
        String name = path.substring("/api/player/name/".length());
        if (name.isEmpty()) { respond(ex, 400, err("missing name")); return; }
        PlayerStats stats = database.findByName(name);
        if (stats == null) { respond(ex, 404, err("player not found")); return; }
        respond(ex, 200, stats.toJson());
    }

    /** GET /api/leaderboard[/normal] */
    private void handleLeaderboard(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String path = ex.getRequestURI().getPath();
        boolean normal = path.endsWith("/normal");
        // Lire depuis la BDD pour avoir tous les joueurs (pas seulement le cache)
        List<PlayerStats> all = database.getAllFromDb();
        if (all.isEmpty()) all = new ArrayList<>(database.getAllCached());
        if (normal) all.sort((a, b) -> b.normalWins - a.normalWins);
        else        all.sort((a, b) -> b.elo - a.elo);
        StringBuilder json = new StringBuilder("[");
        int limit = Math.min(all.size(), 50);
        for (int i = 0; i < limit; i++) {
            if (i > 0) json.append(",");
            json.append(all.get(i).toJson());
        }
        json.append("]");
        respond(ex, 200, json.toString());
    }

    /** GET /api/match/history/{uuid} */
    private void handleMatchHistory(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String path = ex.getRequestURI().getPath();
        String uuidStr = path.substring("/api/match/history/".length());
        try {
            UUID uuid = UUID.fromString(uuidStr);
            List<MatchRecord> history = database.getMatchHistory(uuid, 20);
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) json.append(",");
                json.append(history.get(i).toJson());
            }
            json.append("]");
            respond(ex, 200, json.toString());
        } catch (IllegalArgumentException e) {
            respond(ex, 400, err("invalid uuid"));
        }
    }

    /** GET /api/player-champions/{uuid} */
    private void handlePlayerChampions(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String path = ex.getRequestURI().getPath();
        String uuidStr = path.substring("/api/player-champions/".length());
        try {
            UUID uuid = UUID.fromString(uuidStr);
            var list = database.getChampionStats(uuid);
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) json.append(",");
                json.append(list.get(i).toJson());
            }
            json.append("]");
            respond(ex, 200, json.toString());
        } catch (IllegalArgumentException e) { respond(ex, 400, err("invalid uuid")); }
    }

    /** GET /api/match/detail/{matchId} → tous les joueurs de la partie */
    private void handleMatchDetail(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String path = ex.getRequestURI().getPath();
        String matchId = path.substring("/api/match/detail/".length());
        var players = database.getMatchPlayers(matchId);
        if (players.isEmpty()) { respond(ex, 404, err("match not found")); return; }
        StringBuilder json = new StringBuilder("{");
        json.append("\"match_id\":\"").append(matchId).append("\",");
        json.append("\"played_at\":").append(players.get(0).playedAt).append(",");
        json.append("\"ranked\":").append(players.get(0).ranked).append(",");
        json.append("\"duration_seconds\":").append(players.get(0).durationSeconds).append(",");
        // Séparer les équipes
        json.append("\"blue\":[");
        boolean firstB = true;
        for (var p : players) {
            if (!"BLUE".equals(p.team)) continue;
            if (!firstB) json.append(","); firstB = false;
            json.append(p.toJson());
        }
        json.append("],\"red\":[");
        boolean firstR = true;
        for (var p : players) {
            if (!"RED".equals(p.team)) continue;
            if (!firstR) json.append(","); firstR = false;
            json.append(p.toJson());
        }
        json.append("]}");
        respond(ex, 200, json.toString());
    }

    /** GET /api/champions → liste de tous les champions avec leurs stats de base */
    private void handleChampionsList(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        var cm = LolPlugin.getInstance().getChampionManager();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (var champ : cm.getAllChampions()) {
            if (!first) json.append(","); first = false;
            json.append(String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"role\":\"%s\",\"aa_range\":%.1f}",
                champ.getId(), champ.getDisplayName(),
                champ.getRole() != null ? champ.getRole().name() : "UNKNOWN",
                champ.getAutoAttackRange()));
        }
        json.append("]");
        respond(ex, 200, json.toString());
    }

    /** GET /api/status → état du serveur */
    private void handleStatus(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        var gm = LolPlugin.getInstance().getGameManager();
        long elapsed = gm.isGameRunning() ? gm.getElapsedSeconds() : 0;
        int online = org.bukkit.Bukkit.getOnlinePlayers().size();
        var db = LolPlugin.getInstance().getDatabaseManager();
        int totalPlayers = db.getAllFromDb().size();
        String json = String.format(
            "{\"online_players\":%d,\"game_running\":%b,\"game_elapsed_seconds\":%d,"
            + "\"total_registered_players\":%d,\"server_version\":\"%s\"}",
            online, gm.isGameRunning(), elapsed, totalPlayers,
            org.bukkit.Bukkit.getVersion());
        respond(ex, 200, json);
    }

    /** GET /api/online */
    private void handleOnline(HttpExchange ex) throws java.io.IOException {    /** GET /api/online */
    private void handleOnline(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        StringBuilder json = new StringBuilder("{\"online\":[");
        boolean first = true;
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!first) json.append(",");
            first = false;
            var cm = LolPlugin.getInstance().getChampionManager();
            String champ = cm.hasChampion(p) ? cm.getChampion(p).getId() : null;
            json.append(String.format(
                "{\"name\":\"%s\",\"uuid\":\"%s\",\"champion\":%s,\"inGame\":%b}",
                p.getName(), p.getUniqueId(),
                champ != null ? "\"" + champ + "\"" : "null",
                LolPlugin.getInstance().getGameManager().isGameRunning()));
        }
        json.append("]}");
        respond(ex, 200, json.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void respond(HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String err(String msg) {
        return "{\"error\":\"" + msg + "\"}";
    }
}
