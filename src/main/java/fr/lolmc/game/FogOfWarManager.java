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
 * La distance de vision est réglable dans config.yml (fog.vision-range).
 */
public class FogOfWarManager {

    private final TeamManager teamManager;
    private double visionRange;
    private boolean enabled;

    public FogOfWarManager(TeamManager teamManager) {
        this.teamManager = teamManager;
        var config = LolPlugin.getInstance().getConfig();
        this.enabled = config.getBoolean("fog.enabled", true);
        this.visionRange = config.getDouble("fog.vision-range", 30.0);
        if (enabled) startFogTask();
    }

    private void startFogTask() {
        new BukkitRunnable() {
            @Override public void run() {
                updateVision();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L); // 2x/seconde
    }

    private void updateVision() {
        var cm = LolPlugin.getInstance().getChampionManager();
        var wardMgr = LolPlugin.getInstance().getWardManager();
        var bushMgr = LolPlugin.getInstance().getBushManager();

        var players = new java.util.ArrayList<>(LolPlugin.getInstance().getServer().getOnlinePlayers());

        for (Player viewer : players) {
            if (!cm.hasChampion(viewer)) continue;
            for (Player target : players) {
                if (viewer.equals(target)) continue;
                if (!cm.hasChampion(target)) continue;

                // Alliés toujours visibles
                if (!teamManager.areEnemies(viewer, target)) {
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
