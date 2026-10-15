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
 *   GET /api/player/{uuid}             → stats complètes par UUID
 *   GET /api/player/name/{name}        → stats par pseudo (recherche BDD)
 *   GET /api/player-champions/{uuid}   → stats du joueur par champion
 *   GET /api/leaderboard               → top 50 Elo classé
 *   GET /api/leaderboard/normal        → top 50 victoires normal
 *   GET /api/leaderboard/season/{n}    → top 50 d'une saison (victoires en classé)
 *   GET /api/seasons                   → saison en cours et saisons jouées
 *   GET /api/match/history/{uuid}      → historique (?limit=20&offset=0&mode=ranked|normal)
 *   GET /api/match/detail/{matchId}    → les 10 joueurs, objets, runes, sorts et frise de la partie
 *   GET /api/matches/recent            → dernières parties du serveur (?limit=20&offset=0)
 *   GET /api/champions                 → liste des champions
 *   GET /api/champions/stats           → taux de victoire / sélection par champion (?season=n)
 *   GET /api/items                     → objets (id, nom, coût)
 *   GET /api/runes                     → runes (id, nom, voie)
 *   GET /api/online                    → joueurs actuellement en ligne
 *   GET /api/status                    → état du serveur et des parties
 *   GET /                              → tableau de bord web (api.dashboard)
 *
 * Authentification optionnelle : header "X-Api-Key: <clé>" si api.key est défini.
 *
 * Config config.yml :
 *   api:
 *     enabled: false
 *     bind: "127.0.0.1"  # "0.0.0.0" pour écouter sur toutes les interfaces
 *     port: 8080
 *     key: ""            # laisser vide = pas d'auth
 *     cors-origin: "*"   # valeur de Access-Control-Allow-Origin ("" = en-tête absent)
 *     dashboard: true    # tableau de bord web sur "/" (api.key : la clé est demandée par la page)
 */
public class ApiServer {

    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final DatabaseManager database;
    private String apiKey;
    private String corsOrigin;

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
        String bind = config.getString("api.bind", "127.0.0.1").trim();
        apiKey     = config.getString("api.key", "").trim();
        corsOrigin = config.getString("api.cors-origin", "*").trim();

        try {
            server = HttpServer.create(bind.isEmpty() ? new InetSocketAddress(port)
                    : new InetSocketAddress(bind, port), 0);

            server.createContext("/api/player/name/",        this::handlePlayerByName);
            server.createContext("/api/player/",               this::handlePlayerByUuid);
            server.createContext("/api/player-champions/",     this::handlePlayerChampions);
            server.createContext("/api/leaderboard",           this::handleLeaderboard);
            server.createContext("/api/leaderboard/season/",   this::handleSeasonLeaderboard);
            server.createContext("/api/seasons",               this::handleSeasons);
            server.createContext("/api/match/detail/",         this::handleMatchDetail);
            server.createContext("/api/match/history/",        this::handleMatchHistory);
            server.createContext("/api/matches/recent",        this::handleRecentMatches);
            server.createContext("/api/champions",             this::handleChampionsList);
            server.createContext("/api/champions/stats",       this::handleChampionStats);
            server.createContext("/api/items",                 this::handleItems);
            server.createContext("/api/runes",                 this::handleRunes);
            if (config.getBoolean("api.dashboard", true))
                server.createContext("/",                      this::handleDashboard);
            server.createContext("/api/online",                this::handleOnline);
            server.createContext("/api/status",                this::handleStatus);

            executor = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "LolMC-API");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
            LolPlugin.getInstance().getLogger().info(
                "API web démarrée sur " + (bind.isEmpty() ? "*" : bind) + ":" + port
                + (apiKey.isEmpty() ? " (sans authentification)" : " (clé API requise)"));
            if (apiKey.isEmpty() && !bind.startsWith("127.") && !bind.equals("localhost"))
                LolPlugin.getInstance().getLogger().warning(
                    "API web exposée sans clé (api.key vide) : définis une clé ou api.bind: 127.0.0.1.");
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("Échec démarrage API: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) { server.stop(0); server = null; }
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }

    // ── Auth ──────────────────────────────────────────────────────

    private boolean isAuthorized(HttpExchange ex) {
        if (apiKey == null || apiKey.isEmpty()) return true;
        String header = ex.getRequestHeaders().getFirst("X-Api-Key");
        if (header == null) return false;
        return java.security.MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
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
        List<PlayerStats> all = database.getTopPlayers(normal, 50);
        if (all.isEmpty()) {
            all = new ArrayList<>(database.getAllCached());
            if (normal) all.sort((a, b) -> b.normalWins - a.normalWins);
            else        all.sort((a, b) -> b.elo - a.elo);
        }
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
        var q = query(ex);
        int limit = intParam(q, "limit", 20, 1, 100);
        int offset = intParam(q, "offset", 0, 0, Integer.MAX_VALUE);
        String mode = q.getOrDefault("mode", "");
        Boolean ranked = mode.equals("ranked") ? Boolean.TRUE : mode.equals("normal") ? Boolean.FALSE : null;
        try {
            UUID uuid = UUID.fromString(uuidStr);
            List<MatchRecord> history = database.getMatchHistory(uuid, limit, offset, ranked);
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
        json.append("\"match_id\":\"").append(Json.esc(matchId)).append("\",");
        json.append("\"played_at\":").append(players.getFirst().playedAt).append(",");
        json.append("\"ranked\":").append(players.getFirst().ranked).append(",");
        json.append("\"duration_seconds\":").append(players.getFirst().durationSeconds).append(",");
        json.append("\"season\":").append(players.getFirst().season).append(",");
        String winner = "";
        for (var p : players) if (p.won) { winner = p.team; break; }
        json.append("\"winner\":\"").append(Json.esc(winner)).append("\",");
        json.append("\"events\":[");
        var events = database.getEvents(matchId);
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) json.append(",");
            json.append(events.get(i).toJson());
        }
        json.append("],");
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
                java.util.Locale.US,
                "{\"id\":\"%s\",\"name\":\"%s\",\"role\":\"%s\",\"aa_range\":%.1f}",
                Json.esc(champ.getId()), Json.esc(champ.getDisplayName()),
                champ.getRole() != null ? champ.getRole().name() : "UNKNOWN",
                champ.getAutoAttackRange()));
        }
        json.append("]");
        respond(ex, 200, json.toString());
    }

    /** Instantané de l'état du jeu, pris sur le thread principal. */
    private record StatusSnap(boolean anyRunning, long elapsed, String instances, int online) {}

    /** Exécute {@code c} sur le thread principal du serveur (3 s max). */
    private <T> T onMain(java.util.concurrent.Callable<T> c) throws Exception {
        return org.bukkit.Bukkit.getScheduler()
                .callSyncMethod(LolPlugin.getInstance(), c).get(3, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** GET /api/status → état du serveur */
    private void handleStatus(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        var plugin = LolPlugin.getInstance();
        // L'état du jeu (Bukkit) ne doit être lu que depuis le thread principal du serveur.
        StatusSnap snap;
        try {
            snap = onMain(() -> {
                var gm = plugin.getGameManager();
                boolean anyRunning = gm.isGameRunning();
                long elapsed = anyRunning ? gm.getElapsedSeconds() : 0;
                // Parties en instances : une entrée par instance
                StringBuilder inst = new StringBuilder("[");
                var im = plugin.getInstanceManager();
                if (im != null) {
                    boolean first = true;
                    for (var gi : im.getAllInstances()) {
                        var igm = gi.getGameManager();
                        boolean running = igm.isGameRunning();
                        long el = running ? igm.getElapsedSeconds() : 0;
                        if (running) { anyRunning = true; elapsed = Math.max(elapsed, el); }
                        if (!first) inst.append(",");
                        first = false;
                        inst.append(String.format(
                            "{\"id\":%d,\"world\":\"%s\",\"state\":\"%s\",\"game_running\":%b,"
                            + "\"game_elapsed_seconds\":%d,\"players\":%d}",
                            gi.getId(), Json.esc(gi.getWorldName()), gi.getState(), running, el,
                            gi.getWorld().getPlayers().size()));
                    }
                }
                inst.append("]");
                return new StatusSnap(anyRunning, elapsed, inst.toString(),
                        org.bukkit.Bukkit.getOnlinePlayers().size());
            });
        } catch (Exception e) {
            respond(ex, 503, err("server busy"));
            return;
        }
        boolean anyRunning = snap.anyRunning();
        long elapsed = snap.elapsed();
        StringBuilder inst = new StringBuilder(snap.instances());
        int online = snap.online();
        var db = plugin.getDatabaseManager();
        int totalPlayers = db.countPlayers();
        // game_running / game_elapsed_seconds : vrai si AU MOINS une partie tourne (globale ou instance),
        // durée de la plus longue — champs conservés pour compatibilité ; le détail est dans "instances".
        String json = String.format(
            "{\"online_players\":%d,\"game_running\":%b,\"game_elapsed_seconds\":%d,"
            + "\"total_registered_players\":%d,\"server_version\":\"%s\",\"instances\":%s}",
            online, anyRunning, elapsed, totalPlayers,
            Json.esc(org.bukkit.Bukkit.getVersion()), inst);
        respond(ex, 200, json);
    }

    /** GET /api/online */
    private void handleOnline(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String body;
        try {
            // Lecture de l'état du jeu sur le thread principal du serveur
            body = onMain(() -> {
                StringBuilder json = new StringBuilder("{\"online\":[");
                boolean first = true;
                var cm = LolPlugin.getInstance().getChampionManager();
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (!first) json.append(",");
                    first = false;
                    String champ = cm.hasChampion(p) ? cm.getChampion(p).getId() : null;
                    var gi = fr.lolmc.instance.InstanceHelper.instanceOf(p);
                    json.append(String.format(
                        "{\"name\":\"%s\",\"uuid\":\"%s\",\"champion\":%s,\"inGame\":%b,\"instance\":%d}",
                        Json.esc(p.getName()), p.getUniqueId(),
                        champ != null ? "\"" + champ + "\"" : "null",
                        fr.lolmc.instance.InstanceHelper.gameManager(p).isGameRunning(),
                        gi != null ? gi.getId() : 0));
                }
                json.append("]}");
                return json.toString();
            });
        } catch (Exception e) {
            respond(ex, 503, err("server busy"));
            return;
        }
        respond(ex, 200, body);
    }

    /** GET /api/matches/recent?limit=20&offset=0 */
    private void handleRecentMatches(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        var q = query(ex);
        var list = database.getRecentMatches(intParam(q, "limit", 20, 1, 100),
                intParam(q, "offset", 0, 0, Integer.MAX_VALUE));
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(",");
            json.append(list.get(i).toJson());
        }
        respond(ex, 200, json.append("]").toString());
    }

    /** GET /api/champions/stats?season=n */
    private void handleChampionStats(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        var q = query(ex);
        Integer season = q.containsKey("season") ? intParam(q, "season", 1, 0, Integer.MAX_VALUE) : null;
        var list = database.getChampionAggregates(season);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(",");
            json.append(list.get(i).toJson());
        }
        respond(ex, 200, json.append("]").toString());
    }

    /** GET /api/leaderboard/season/{n} */
    private void handleSeasonLeaderboard(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String rest = ex.getRequestURI().getPath().substring("/api/leaderboard/season/".length());
        int season;
        try { season = Integer.parseInt(rest.replaceAll("/.*", "")); }
        catch (NumberFormatException e) { respond(ex, 400, err("invalid season")); return; }
        var list = database.getSeasonLeaderboard(season, 50);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(",");
            json.append(list.get(i).toJson());
        }
        respond(ex, 200, json.append("]").toString());
    }

    /** GET /api/seasons */
    private void handleSeasons(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        int current = LolPlugin.getInstance().getConfig().getInt("stats.season", 1);
        StringBuilder json = new StringBuilder("{\"current\":").append(current).append(",\"seasons\":[");
        boolean first = true;
        for (var e : database.getSeasons().entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"season\":").append(e.getKey()).append(",\"matches\":").append(e.getValue()).append("}");
        }
        respond(ex, 200, json.append("]}").toString());
    }

    /** GET /api/items */
    private void handleItems(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        fr.lolmc.item.ItemRegistry.ensureLoaded();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (var item : fr.lolmc.item.ItemRegistry.all()) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"id\":\"").append(Json.esc(item.getId()))
                .append("\",\"name\":\"").append(Json.esc(item.getDisplayName()))
                .append("\",\"cost\":").append(item.getGoldCost()).append("}");
        }
        respond(ex, 200, json.append("]").toString());
    }

    /** GET /api/runes */
    private void handleRunes(HttpExchange ex) throws java.io.IOException {
        if (!isAuthorized(ex)) { respond(ex, 401, err("unauthorized")); return; }
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (var rune : fr.lolmc.rune.RuneRegistry.all()) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"id\":\"").append(Json.esc(rune.id()))
                .append("\",\"name\":\"").append(Json.esc(rune.name()))
                .append("\",\"path\":\"").append(rune.path().name())
                .append("\",\"keystone\":").append(rune.slot() == 0).append("}");
        }
        respond(ex, 200, json.append("]").toString());
    }

    // ── Tableau de bord web ───────────────────────────────────────

    /** Fichiers du tableau de bord (liste fermée : pas d'accès arbitraire au disque). */
    private static final Map<String, String> DASHBOARD_FILES = Map.of(
            "index.html", "text/html; charset=utf-8",
            "app.js",     "text/javascript; charset=utf-8",
            "style.css",  "text/css; charset=utf-8");

    /** GET / → tableau de bord (plugins/LolMC/web/ prioritaire sur les fichiers du plugin). */
    private void handleDashboard(HttpExchange ex) throws java.io.IOException {
        String path = ex.getRequestURI().getPath();
        String file = path.equals("/") ? "index.html" : path.substring(1);
        String type = DASHBOARD_FILES.get(file);
        if (type == null || !"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 404, err("not found"));
            return;
        }
        byte[] bytes = null;
        java.io.File override = new java.io.File(new java.io.File(LolPlugin.getInstance().getDataFolder(), "web"), file);
        if (override.isFile()) bytes = java.nio.file.Files.readAllBytes(override.toPath());
        if (bytes == null) {
            try (var in = LolPlugin.getInstance().getResource("web/" + file)) {
                if (in != null) bytes = in.readAllBytes();
            }
        }
        if (bytes == null) { respond(ex, 404, err("not found")); return; }
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /** Paramètres de la query string (décodés). */
    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static int intParam(Map<String, String> q, String key, int def, int min, int max) {
        try { return Math.clamp(Long.parseLong(q.getOrDefault(key, "")), min, max); }
        catch (NumberFormatException e) { return def; }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void respond(HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (corsOrigin != null && !corsOrigin.isEmpty())
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String err(String msg) {
        return "{\"error\":\"" + Json.esc(msg) + "\"}";
    }
}
