package fr.lolmc.game;

import fr.lolmc.LolPlugin;

import fr.lolmc.team.TeamManager;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Brouillard de guerre (fog of war) : un joueur ne voit les ennemis
 * que s'ils sont dans la portée de vision de son équipe (distance réglable).
 *
 * Combine avec les wards (un ennemi révélé par ward reste visible) et
 * les bushes (déjà gérés par BushManager).
 *
 * Portées de vision (wiki LoL, 100 unités = 1 bloc) : champions et tourelles 1350 (13,5 blocs),
 * sbires 1200 (12 blocs), wards 900 / 500 (WardManager). La portée des champions est réglable
 * dans config.yml (fog.vision-range). Les morts ne donnent pas de vision.
 */
public class FogOfWarManager {
    private fr.lolmc.instance.GameInstance gameInstance = null;
    public void setGameInstance(fr.lolmc.instance.GameInstance inst) { this.gameInstance = inst; }
    public fr.lolmc.instance.GameInstance getGameInstance() { return gameInstance; }
    private MapManager siblingMapManager() {
        return gameInstance != null ? gameInstance.getMapManager() : LolPlugin.getInstance().getMapManager();
    }

    /** Vision des champions et des tourelles : 1350 unités LoL. */
    public static final double CHAMPION_SIGHT = 13.5;
    private static final double TURRET_SIGHT = 13.5;
    /** Vision des sbires : 1200 unités LoL. */
    private static final double MINION_SIGHT = 12.0;

    private final TeamManager teamManager;
    private double visionRange;
    private final boolean enabled;

    // World filtré (null = tous les mondes, mode legacy)
    private org.bukkit.World scopedWorld = null;
    private org.bukkit.scheduler.BukkitTask fogTask = null;

    /**
     * Révélations ponctuelles (ex: Faucon d'Ashe) : une équipe voit temporairement
     * une zone, indépendamment de la portée de vision normale.
     * Nettoyées automatiquement à expiration dans updateVision().
     */
    private record RevealedPoint(org.bukkit.World world, double x, double y, double z,
                                 double radiusSq, long expiresAtMillis) {}
    private final java.util.Map<TeamManager.Team, java.util.List<RevealedPoint>> revealedPoints
            = new java.util.concurrent.ConcurrentHashMap<>();

    public FogOfWarManager(TeamManager teamManager) {
        this.teamManager = teamManager;
        var config = LolPlugin.getInstance().getConfig();
        this.enabled = config.getBoolean("fog.enabled", true);
        this.visionRange = config.getDouble("fog.vision-range", CHAMPION_SIGHT);
        if (enabled) startFogTask();
    }

    /** Constructeur pour les instances : scoped à un World précis. */
    public FogOfWarManager(TeamManager teamManager, org.bukkit.World world) {
        this.teamManager  = teamManager;
        this.scopedWorld  = world;
        var config = LolPlugin.getInstance().getConfig();
        this.enabled = config.getBoolean("fog.enabled", true);
        this.visionRange = config.getDouble("fog.vision-range", CHAMPION_SIGHT);
        // Ne pas démarrer ici — démarré par GameInstance.start()
    }

    /** Démarre la tâche de brouillard (pour les instances). */
    public void startTask() { if (enabled && fogTask == null) startFogTask(); }

    /** Arrête la tâche de brouillard. */
    public void stopTask() {
        if (fogTask != null) { fogTask.cancel(); fogTask = null; }
    }

    private void startFogTask() {
        fogTask = new BukkitRunnable() {
            @Override public void run() {
                updateVision();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
    }

    /**
     * Vérifie si une position est vue par les tourelles alliées (non détruites).
     */
    private boolean revealedByAllyTurret(Player viewer, org.bukkit.Location loc) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(viewer);
        if (team == null) return false;
        var mapMgr = siblingMapManager();
        if (mapMgr == null) return false;
        for (var s : mapMgr.getStructuresForTeam(team)) {
            if (s.isDestroyed()) continue;
            if (s.getType() != fr.lolmc.game.GameStructure.Type.TURRET) continue;
            try {
                if (s.getCenter().getWorld().equals(loc.getWorld())
                        && s.getCenter().distanceSquared(loc) <= TURRET_SIGHT * TURRET_SIGHT)
                    return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Vérifie si un ennemi est visible par les sbires alliés
     * (un sbire allié à portée révèle les ennemis proches).
     */
    private boolean revealedByAllyMinion(org.bukkit.entity.Player viewer, org.bukkit.entity.Entity target) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(viewer);
        if (team == null) return false;
        // Chercher un sbire allié à portée de vision de la cible
        for (var entity : target.getNearbyEntities(MINION_SIGHT, 4, MINION_SIGHT)) {
            if (entity instanceof org.bukkit.entity.LivingEntity le && fr.lolmc.game.MinionManager.isMinion(le)
                    && fr.lolmc.game.MinionManager.getMinionTeam(le) == team
                    && le.getLocation().distanceSquared(target.getLocation()) <= MINION_SIGHT * MINION_SIGHT)
                return true;
        }
        return false;
    }

    private void updateVision() {
        cleanupExpiredReveals();
        var cm = LolPlugin.getInstance().getChampionManager();
        // Filtrer par monde si scoped (mode instance)
        java.util.Collection<org.bukkit.entity.Player> playerList =
                (scopedWorld != null) ? scopedWorld.getPlayers()
                        : fr.lolmc.util.WorldContext.getGamePlayers();
        var wardMgr = LolPlugin.getInstance().getWardManager();
        var bushMgr = LolPlugin.getInstance().getBushManager();

        java.util.List<Player> players = new java.util.ArrayList<>(playerList);
        // Brouillard global : les joueurs d'une instance sont gérés par le brouillard de leur instance
        if (scopedWorld == null) {
            var im = LolPlugin.getInstance().getInstanceManager();
            if (im != null) players.removeIf(p -> im.getInstanceOf(p) != null);
        }

        for (Player viewer : players) {
            if (!cm.hasChampion(viewer)) continue;
            for (Player target : players) {
                if (viewer.equals(target)) continue;
                if (!cm.hasChampion(target)) continue;
                if (!viewer.getWorld().equals(target.getWorld())) continue; // autre partie

                // Alliés toujours visibles
                if (!teamManager.areEnemies(viewer, target)) {
                    viewer.showPlayer(LolPlugin.getInstance(), target);
                    continue;
                }

                // Bush : caché sauf si l'équipe du viewer y a un champion ou une ward, ou si la
                // cible vient d'attaquer (avant, le BushManager et ce test se contredisaient)
                if (bushMgr != null && bushMgr.hidesFrom(viewer, target)) {
                    viewer.hidePlayer(LolPlugin.getInstance(), target);
                    continue;
                }

                // Ennemi vu par un sbire ou une tourelle alliée
                if (revealedByAllyMinion(viewer, target) || revealedByAllyTurret(viewer, target.getLocation())) {
                    viewer.showPlayer(LolPlugin.getInstance(), target);
                    continue;
                }

                // Vu par une ward de l'équipe du viewer → visible
                var viewerTeam = teamManager.getTeam(viewer);
                if (wardMgr != null && viewerTeam != null && wardMgr.hasVisionAt(viewerTeam, target.getLocation())) {
                    viewer.showPlayer(LolPlugin.getInstance(), target);
                    continue;
                }

                // Révélé par un effet ponctuel (Faucon d'Ashe, etc.) → visible
                if (viewerTeam != null && isRevealedByPoint(viewerTeam, target.getLocation())) {
                    viewer.showPlayer(LolPlugin.getInstance(), target);
                    continue;
                }

                // Sinon : visible seulement si un allié (ou le viewer) est assez proche
                boolean visible = hasVisionOf(viewer, target, players);
                if (visible) {
                    viewer.showPlayer(LolPlugin.getInstance(), target);
                } else {
                    viewer.hidePlayer(LolPlugin.getInstance(), target);
                }
            }

            // ── Sbires et monstres ennemis : cachés hors vision ──
            updateEntityVision(viewer, players);
        }
    }

    /**
     * Cache les sbires ennemis hors de la vision de l'équipe du viewer.
     * Les monstres de jungle neutres sont cachés au-delà de la portée de vision.
     * Utilise Player#hideEntity / showEntity (Paper API).
     */
    private void updateEntityVision(Player viewer, java.util.List<Player> players) {
        var world = viewer.getWorld();
        var plugin = LolPlugin.getInstance();
        var tm = teamManager;
        var team = tm.getTeam(viewer);
        if (team == null) return;

        for (var entity : world.getLivingEntities()) {
            if (entity instanceof Player) continue; // joueurs gérés au-dessus

            boolean isMinion  = fr.lolmc.game.MinionManager.isMinion(entity);
            boolean isMonster = fr.lolmc.game.JungleManager.isJungleMonster(entity);
            if (!isMinion && !isMonster) continue; // ne toucher que nos entités de jeu

            // Sbires alliés : toujours visibles
            if (isMinion) {
                var minionTeam = fr.lolmc.game.MinionManager.getMinionTeam(entity);
                if (minionTeam == team) {
                    viewer.showEntity(plugin, entity);
                    continue;
                }
            }

            // Entité ennemie/neutre : visible si un allié vivant est à portée de vision
            boolean visible = false;
            for (Player ally : players) {
                if (!ally.equals(viewer) && !tm.areAllies(viewer, ally)) continue;
                if (!ally.getWorld().equals(world)) continue;
                if (ally.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue; // mort
                if (ally.getLocation().distanceSquared(entity.getLocation())
                        <= visionRange * visionRange) {
                    visible = true;
                    break;
                }
            }
            // Wards, sbires et tourelles alliés (avant : les sbires ennemis qui se battaient
            // contre les vôtres restaient invisibles sans champion à côté)
            if (!visible) {
                var wardMgr = plugin.getWardManager();
                if (wardMgr != null && wardMgr.hasVisionAt(team, entity.getLocation()))
                    visible = true;
            }
            if (!visible && (revealedByAllyMinion(viewer, entity) || revealedByAllyTurret(viewer, entity.getLocation())))
                visible = true;
            // Révélation ponctuelle (Faucon d'Ashe, etc.)
            if (!visible && isRevealedByPoint(team, entity.getLocation())) {
                visible = true;
            }

            if (visible) viewer.showEntity(plugin, entity);
            else         viewer.hideEntity(plugin, entity);
        }
    }

    /**
     * L'équipe du viewer a-t-elle vision de la cible ?
     * (un allié du viewer, y compris lui-même, est à portée de vision)
     */
    private boolean hasVisionOf(Player viewer, Player target, java.util.List<Player> players) {
        for (Player ally : players) {
            // Allié du viewer (ou lui-même), vivant
            if (!ally.equals(viewer) && !teamManager.areAllies(viewer, ally)) continue;
            if (!ally.getWorld().equals(target.getWorld())) continue;
            if (ally.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue; // mort : pas de vision
            if (ally.getLocation().distanceSquared(target.getLocation()) <= visionRange * visionRange) {
                return true;
            }
        }
        return false;
    }

    public void setVisionRange(double range) { this.visionRange = range; }
    public double getVisionRange() { return visionRange; }
    public boolean isEnabled() { return enabled; }

    /**
     * Révèle temporairement une zone pour une équipe (ex: Faucon d'Ashe,
     * Œil du Héraut, tout effet de vision ponctuelle).
     * @param team           équipe qui gagne la vision
     * @param location       centre de la zone révélée
     * @param radius         rayon en blocs
     * @param durationTicks  durée avant expiration (20 ticks = 1s)
     */
    public void revealPoint(TeamManager.Team team, org.bukkit.Location location,
                            double radius, long durationTicks) {
        if (team == null || location.getWorld() == null) return;
        long expiresAt = System.currentTimeMillis() + (durationTicks * 50L); // 1 tick = 50ms
        revealedPoints.computeIfAbsent(team, _ -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new RevealedPoint(location.getWorld(), location.getX(), location.getY(),
                        location.getZ(), radius * radius, expiresAt));
    }

    /** L'équipe a-t-elle une révélation ponctuelle active couvrant cette position ? */
    private boolean isRevealedByPoint(TeamManager.Team team, org.bukkit.Location loc) {
        var points = revealedPoints.get(team);
        if (points == null || points.isEmpty()) return false;
        long now = System.currentTimeMillis();
        boolean found = false;
        for (var p : points) {
            if (p.expiresAtMillis() < now) continue; // expiré, nettoyé plus bas
            if (!p.world().equals(loc.getWorld())) continue;
            double dx = p.x() - loc.getX(), dy = p.y() - loc.getY(), dz = p.z() - loc.getZ();
            if (dx*dx + dy*dy + dz*dz <= p.radiusSq()) { found = true; break; }
        }
        return found;
    }

    /** Purge les révélations ponctuelles expirées (appelé périodiquement dans updateVision). */
    private void cleanupExpiredReveals() {
        long now = System.currentTimeMillis();
        for (var points : revealedPoints.values()) {
            points.removeIf(p -> p.expiresAtMillis() < now);
        }
    }
}