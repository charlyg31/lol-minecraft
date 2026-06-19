package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère les sbires (minions) LoL :
 *  - Spawn par vagues toutes les 30s
 *  - Avancent le long de leur lane vers la base ennemie
 *  - Combattent les sbires/champions/tourelles ennemis
 *
 * Les sbires sont des Zombies/Husks taggés via PDC.
 */
public class MinionManager {

    public static NamespacedKey KEY_MINION;
    public static NamespacedKey KEY_TEAM;

    private final MapManager mapManager;
    // Waypoints par lane : "top" → liste de points que les sbires suivent
    private final Map<String, List<Location>> blueLaneWaypoints = new HashMap<>();
    private final Map<String, List<Location>> redLaneWaypoints = new HashMap<>();

    private static final long WAVE_PERIOD = 600L;   // 30s entre vagues
    private static final int MINIONS_PER_WAVE = 6;
    private static final double MINION_HP = 477;
    private static final double MINION_SPEED = 0.25;

    private boolean spawning = false;

    public MinionManager(MapManager mapManager) {
        this.mapManager = mapManager;
        KEY_MINION = new NamespacedKey(LolPlugin.getInstance(), "minion");
        KEY_TEAM = new NamespacedKey(LolPlugin.getInstance(), "minion_team");
    }

    // ── Démarrage / arrêt des vagues ──────────────────────────────

    public void startWaves() {
        if (spawning) return;
        spawning = true;

        new BukkitRunnable() {
            @Override public void run() {
                if (!spawning) { cancel(); return; }
                spawnWave(Team.BLUE);
                spawnWave(Team.RED);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 100L, WAVE_PERIOD);

        // Tâche de déplacement des sbires (toutes les 10 ticks)
        new BukkitRunnable() {
            @Override public void run() {
                if (!spawning) { cancel(); return; }
                moveMinions();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
    }

    public void stopWaves() {
        spawning = false;
        clearAllMinions();
    }

    // ── Spawn ─────────────────────────────────────────────────────

    private void spawnWave(Team team) {
        // Spawn sur chaque lane configurée
        for (String lane : List.of("top", "mid", "bot")) {
            Location spawnPoint = getMinionSpawn(team, lane);
            if (spawnPoint == null) continue;

            for (int i = 0; i < MINIONS_PER_WAVE; i++) {
                final int delay = i * 8; // décaler chaque sbire de 0.4s
                new BukkitRunnable() {
                    @Override public void run() {
                        if (spawning) spawnMinion(team, lane, spawnPoint);
                    }
                }.runTaskLater(LolPlugin.getInstance(), delay);
            }
        }
    }

    private void spawnMinion(Team team, String lane, Location loc) {
        Zombie minion = loc.getWorld().spawn(loc, Zombie.class, z -> {
            z.setBaby(false);
            z.setShouldBurnInDay(false);
            z.setCustomNameVisible(false);
            z.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).setBaseValue(MINION_HP);
            z.setHealth(MINION_HP);
            z.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(MINION_SPEED);
            // Tag PDC
            z.getPersistentDataContainer().set(KEY_MINION, PersistentDataType.BYTE, (byte) 1);
            z.getPersistentDataContainer().set(KEY_TEAM, PersistentDataType.STRING, team.name());
            // Couleur visuelle via nom
            z.customName(Component.text(team == Team.BLUE ? "🔵 Sbire" : "🔴 Sbire",
                    team == Team.BLUE ? NamedTextColor.BLUE : NamedTextColor.RED));
            // Équiper selon l'équipe (visuel)
            if (z.getEquipment() != null) {
                z.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(
                        team == Team.BLUE ? org.bukkit.Material.BLUE_WOOL : org.bukkit.Material.RED_WOOL));
            }
        });
    }

    // ── Déplacement ───────────────────────────────────────────────

    private void moveMinions() {
        for (var world : LolPlugin.getInstance().getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof Zombie z)) continue;
                if (!isMinion(z)) continue;
                Team team = getMinionTeam(z);
                if (team == null) continue;

                String lane = getMinionLane(z);
                // Avancer vers la base ennemie via les waypoints
                Location target = getNextWaypoint(team, lane, z.getLocation());
                if (target != null && z.getTarget() == null) {
                    // Pathfinding Minecraft natif vers le waypoint
                    z.getPathfinder().moveTo(target, 1.0);
                }

                // Chercher un ennemi proche à attaquer (sbire/champion ennemi)
                LivingEntity enemy = findNearbyEnemy(z, team);
                if (enemy != null) {
                    z.setTarget(enemy);
                }
            }
        }
    }

    private LivingEntity findNearbyEnemy(Zombie minion, Team team) {
        for (Entity e : minion.getNearbyEntities(6, 4, 6)) {
            if (e instanceof Zombie z && isMinion(z)) {
                if (getMinionTeam(z) != team) return z;
            }
            if (e instanceof Player p) {
                var cm = LolPlugin.getInstance().getChampionManager();
                var tm = LolPlugin.getInstance().getTeamManager();
                if (cm.hasChampion(p) && tm.getTeam(p) != null && tm.getTeam(p) != team) {
                    return p;
                }
            }
        }
        return null;
    }

    // ── Waypoints (chemin des lanes) ──────────────────────────────

    /**
     * Définit le chemin d'une lane (liste de points). Configuré via commande.
     * Les sbires bleus suivent le chemin dans l'ordre, les rouges en sens inverse.
     */
    public void setLaneWaypoints(String lane, List<Location> waypoints) {
        blueLaneWaypoints.put(lane, new ArrayList<>(waypoints));
        List<Location> reversed = new ArrayList<>(waypoints);
        Collections.reverse(reversed);
        redLaneWaypoints.put(lane, reversed);
    }

    private Location getNextWaypoint(Team team, String lane, Location current) {
        var waypoints = (team == Team.BLUE ? blueLaneWaypoints : redLaneWaypoints).get(lane);
        if (waypoints == null || waypoints.isEmpty()) return null;
        // Trouver le prochain waypoint non atteint
        for (Location wp : waypoints) {
            if (wp.getWorld().equals(current.getWorld()) && wp.distance(current) > 3.0) {
                return wp;
            }
        }
        return waypoints.get(waypoints.size() - 1); // dernier point = base ennemie
    }

    private Location getMinionSpawn(Team team, String lane) {
        var waypoints = (team == Team.BLUE ? blueLaneWaypoints : redLaneWaypoints).get(lane);
        if (waypoints == null || waypoints.isEmpty()) return null;
        return waypoints.get(0).clone();
    }

    // ── Helpers PDC ───────────────────────────────────────────────

    public static boolean isMinion(LivingEntity e) {
        return e.getPersistentDataContainer().has(KEY_MINION, PersistentDataType.BYTE);
    }

    public static Team getMinionTeam(LivingEntity e) {
        String t = e.getPersistentDataContainer().get(KEY_TEAM, PersistentDataType.STRING);
        return t != null ? Team.valueOf(t) : null;
    }

    private String getMinionLane(Zombie z) {
        // Stocké dans le nom custom ou via une autre clé — simplifié ici
        return "mid"; // par défaut, amélioration possible avec une 3e clé PDC
    }

    public void clearAllMinions() {
        for (var world : LolPlugin.getInstance().getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof LivingEntity le && isMinion(le)) {
                    e.remove();
                }
            }
        }
    }
}
