package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

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
 * La distance de vision est réglable dans config.yml (fog.vision-range).
 */
public class FogOfWarManager {

    private final TeamManager teamManager;
    private double visionRange;
    private boolean enabled;

    // World filtré (null = tous les mondes, mode legacy)
    private org.bukkit.World scopedWorld = null;
    private org.bukkit.scheduler.BukkitTask fogTask = null;

    public FogOfWarManager(TeamManager teamManager) {
        this.teamManager = teamManager;
        var config = LolPlugin.getInstance().getConfig();
        this.enabled = config.getBoolean("fog.enabled", true);
        this.visionRange = config.getDouble("fog.vision-range", 30.0);
        if (enabled) startFogTask();
    }

    /** Constructeur pour les instances : scoped à un World précis. */
    public FogOfWarManager(TeamManager teamManager, org.bukkit.World world) {
        this.teamManager  = teamManager;
        this.scopedWorld  = world;
        var config = LolPlugin.getInstance().getConfig();
        this.enabled = config.getBoolean("fog.enabled", true);
        this.visionRange = config.getDouble("fog.vision-range", 30.0);
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
     * Vérifie si un ennemi est visible par les tourelles alliées
     * (tourelles non détruites révèlent un rayon de 10 blocs).
     */
    private boolean revealedByAllyTurret(Player viewer, Player target) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(viewer);
        if (team == null) return false;
        var mapMgr = LolPlugin.getInstance().getMapManager();
        if (mapMgr == null) return false;
        for (var s : mapMgr.getStructuresForTeam(team)) {
            if (s.isDestroyed()) continue;
            if (s.getType() != fr.lolmc.game.GameStructure.Type.TURRET) continue;
            try {
                if (s.getCenter().getWorld().equals(target.getWorld())
                        && s.getCenter().distance(target.getLocation()) <= 10.0)
                    return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Vérifie si un ennemi est visible par les sbires alliés
     * (un sbire allié à portée révèle les ennemis proches).
     */
    private boolean revealedByAllyMinion(org.bukkit.entity.Player viewer,
                                          org.bukkit.entity.Player target) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(viewer);
        if (team == null) return false;
        // Chercher un sbire allié à portée de la cible ennemie
        for (var entity : target.getNearbyEntities(8, 4, 8)) {
            if (entity instanceof org.bukkit.entity.LivingEntity le && fr.lolmc.game.MinionManager.isMinion(le)) {
                var minionTeam = fr.lolmc.game.MinionManager.getMinionTeam(
                    (org.bukkit.entity.LivingEntity) entity);
                if (minionTeam == team) return true;
            }
        }
        return false;
    }

    private void updateVision() {
        var cm = LolPlugin.getInstance().getChampionManager();
        // Filtrer par monde si scoped (mode instance)
        java.util.Collection<org.bukkit.entity.Player> playerList =
            (scopedWorld != null) ? scopedWorld.getPlayers()
            : fr.lolmc.util.WorldContext.getGamePlayers();
        var wardMgr = LolPlugin.getInstance().getWardManager();
        var bushMgr = LolPlugin.getInstance().getBushManager();

        java.util.List<Player> players = new java.util.ArrayList<>();
        for (Player __p : WorldContext.getGamePlayers()) players.add(__p);

        for (Player viewer : players) {
            if (!cm.hasChampion(viewer)) continue;
            for (Player target : players) {
                if (viewer.equals(target)) continue;
                if (!cm.hasChampion(target)) continue;

                // Alliés toujours visibles
                // Un allié est toujours visible; un ennemi révélé par sbire allié aussi
                boolean revealedByMinion  = revealedByAllyMinion(viewer, target);
                boolean revealedByTurret  = revealedByAllyTurret(viewer, target);
                if (!teamManager.areEnemies(viewer, target) || revealedByMinion || revealedByTurret) {
                    viewer.showPlayer(LolPlugin.getInstance(), target);
                    continue;
                }

                // Le BushManager gère déjà l'invisibilité dans les bushes :
                // on ne touche pas un ennemi caché dans un bush.
                if (bushMgr != null && bushMgr.isInBush(target) && !bushMgr.isRevealed(target)) {
                    continue;
                }

                // Révélé par une ward → visible
                if (wardMgr != null && wardMgr.isRevealed(target)) {
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

            // Entité ennemie/neutre : visible si un allié est à portée de vision
            boolean visible = false;
            for (Player ally : players) {
                if (!ally.equals(viewer) && !tm.areAllies(viewer, ally)) continue;
                if (!ally.getWorld().equals(world)) continue;
                if (ally.getLocation().distanceSquared(entity.getLocation())
                        <= visionRange * visionRange) {
                    visible = true;
                    break;
                }
            }
            // Wards : une ward alliée proche révèle aussi
            if (!visible) {
                var wardMgr = plugin.getWardManager();
                if (wardMgr != null && wardMgr.hasWardNear(team, entity.getLocation(), 12.0))
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
        var tm = teamManager;
        for (Player ally : players) {
            // Allié du viewer (ou lui-même)
            if (!ally.equals(viewer) && !tm.areAllies(viewer, ally)) continue;
            if (!ally.getWorld().equals(target.getWorld())) continue;
            if (ally.getLocation().distanceSquared(target.getLocation()) <= visionRange * visionRange) {
                return true;
            }
        }
        return false;
    }

    public void setVisionRange(double range) { this.visionRange = range; }
    public double getVisionRange() { return visionRange; }
    public boolean isEnabled() { return enabled; }
}
