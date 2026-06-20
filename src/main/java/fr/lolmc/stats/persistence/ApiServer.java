package fr.lolmc.stats.persistence;

import com.sun.net.httpserver.HttpServer;
import fr.lolmc.LolPlugin;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Serveur HTTP intégré exposant les statistiques des joueurs via une API REST.
 *
 * Routes :
 *   GET /api/player/{uuid}        → stats complètes (classé + amical)
 *   GET /api/leaderboard         → top joueurs par Elo (classé)
 *   GET /api/leaderboard/normal  → top par victoires (amical)
 *
 * IMPORTANT : nécessite un port ouvert sur l'hébergeur pour être accessible
 * depuis l'extérieur (un site web). Si le port est fermé, l'API ne répond
 * qu'en local.
 */
public class ApiServer {

    private HttpServer server;
    private final DatabaseManager database;

    public ApiServer(DatabaseManager database) {
        this.database = database;
    }

    public void start() {
        var config = LolPlugin.getInstance().getConfig();
        if (!config.getBoolean("api.enabled", false)) {
            LolPlugin.getInstance().getLogger().info("API web désactivée (api.enabled: false).");
            return;
        }
        int port = config.getInt("api.port", 8080);

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/api/player/", this::handlePlayer);
            server.createContext("/api/leaderboard", this::handleLeaderboard);

            server.setExecutor(null); // exécuteur par défaut
            server.start();
            LolPlugin.getInstance().getLogger().info("API web démarrée sur le port " + port);
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("Échec démarrage API web: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ── Routes ────────────────────────────────────────────────────

    private void handlePlayer(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        String uuidStr = path.substring("/api/player/".length());
        try {
            UUID uuid = UUID.fromString(uuidStr);
            PlayerStats stats = database.getCached(uuid);
            if (stats == null) {
                respond(exchange, 404, "{\"error\":\"player not found\"}");
                return;
            }
            respond(exchange, 200, stats.toJson());
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, "{\"error\":\"invalid uuid\"}");
        }
    }

    private void handleLeaderboard(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        boolean normal = path.endsWith("/normal");

        var all = new java.util.ArrayList<>(database.getAllCached());
        if (normal) {
            all.sort((a, b) -> b.normalWins - a.normalWins);
        } else {
            all.sort((a, b) -> b.elo - a.elo);
        }

        StringBuilder json = new StringBuilder("[");
        int limit = Math.min(all.size(), 50);
        for (int i = 0; i < limit; i++) {
            if (i > 0) json.append(",");
            json.append(all.get(i).toJson());
        }
        json.append("]");
        respond(exchange, 200, json.toString());
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
