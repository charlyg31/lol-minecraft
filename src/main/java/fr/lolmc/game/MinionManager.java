package fr.lolmc.game;
import fr.lolmc.util.Compat;

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
    public static NamespacedKey KEY_LANE;

    private final MapManager mapManager;
    // Waypoints par lane : "top" → liste de points que les sbires suivent
    private final Map<String, List<Location>> blueLaneWaypoints = new HashMap<>();
    private final Map<String, List<Location>> redLaneWaypoints = new HashMap<>();

    private static final long WAVE_PERIOD = 600L;   // 30s entre vagues
    private static final int MINIONS_PER_WAVE = 6;
    private static final double MINION_HP = 477;
    private static final double MINION_SPEED = 0.25;

    private boolean spawning = false;
    // Inhibiteurs détruits → super-sbires sur cette lane (clé: "BLUE_top")
    private final java.util.Set<String> superMinionLanes = new java.util.HashSet<>();

    public MinionManager(MapManager mapManager) {
        this.mapManager = mapManager;
        KEY_MINION = new NamespacedKey(LolPlugin.getInstance(), "minion");
        KEY_TEAM = new NamespacedKey(LolPlugin.getInstance(), "minion_team");
        KEY_LANE = new NamespacedKey(LolPlugin.getInstance(), "minion_lane");
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


    /**
     * Active les super-sbires sur une lane après destruction d'un inhibiteur.
     * @param losingTeam l'équipe dont l'inhibiteur est tombé (ses ennemis gagnent des super-sbires)
     */
    public void enableSuperMinions(Team losingTeam, String lane) {
        // L'équipe ADVERSE de losingTeam gagne des super-sbires sur cette lane
        Team attackingTeam = (losingTeam == Team.BLUE) ? Team.RED : Team.BLUE;
        superMinionLanes.add(attackingTeam.name() + "_" + lane);
    }

    public void disableSuperMinions(Team team, String lane) {
        superMinionLanes.remove(team.name() + "_" + lane);
    }

    private boolean hasSuperMinions(Team team, String lane) {
        return superMinionLanes.contains(team.name() + "_" + lane);
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
            // Super-sbire si l'inhibiteur ennemi est détruit sur cette lane
            if (hasSuperMinions(team, lane)) {
                final Location sp = spawnPoint;
                new BukkitRunnable() {
                    @Override public void run() {
                        if (spawning) spawnSuperMinion(team, lane, sp);
                    }
                }.runTaskLater(LolPlugin.getInstance(), MINIONS_PER_WAVE * 8 + 4);
            }
        }
    }

    private void spawnMinion(Team team, String lane, Location loc) {
        Zombie minion = loc.getWorld().spawn(loc, Zombie.class, z -> {
            z.setBaby(false);
            z.setShouldBurnInDay(false);
            z.setCustomNameVisible(false);
            z.getAttribute(Compat.maxHealth()).setBaseValue(MINION_HP);
            z.setHealth(MINION_HP);
            z.getAttribute(Compat.movementSpeed()).setBaseValue(MINION_SPEED);
            // Tag PDC
            z.getPersistentDataContainer().set(KEY_MINION, PersistentDataType.BYTE, (byte) 1);
            z.getPersistentDataContainer().set(KEY_TEAM, PersistentDataType.STRING, team.name());
            z.getPersistentDataContainer().set(KEY_LANE, PersistentDataType.STRING, lane);
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


    /** Spawn un super-sbire (Husk costaud, beaucoup de HP). */
    private void spawnSuperMinion(Team team, String lane, Location loc) {
        org.bukkit.entity.Husk superMinion = loc.getWorld().spawn(loc, org.bukkit.entity.Husk.class, h -> {
            h.setShouldBurnInDay(false);
            h.setCustomNameVisible(true);
            h.getAttribute(Compat.maxHealth()).setBaseValue(MINION_HP * 3);
            h.setHealth(MINION_HP * 3);
            h.getAttribute(Compat.movementSpeed()).setBaseValue(MINION_SPEED);
            h.getPersistentDataContainer().set(KEY_MINION, PersistentDataType.BYTE, (byte) 1);
            h.getPersistentDataContainer().set(KEY_TEAM, PersistentDataType.STRING, team.name());
            h.getPersistentDataContainer().set(KEY_LANE, PersistentDataType.STRING, lane);
            h.customName(net.kyori.adventure.text.Component.text(
                    (team == Team.BLUE ? "🔵 " : "🔴 ") + "Super-Sbire",
                    team == Team.BLUE ? net.kyori.adventure.text.format.NamedTextColor.BLUE
                            : net.kyori.adventure.text.format.NamedTextColor.RED));
            if (h.getEquipment() != null) {
                h.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_HELMET));
                h.getEquipment().setChestplate(new org.bukkit.inventory.ItemStack(
                        team == Team.BLUE ? org.bukkit.Material.DIAMOND_CHESTPLATE : org.bukkit.Material.NETHERITE_CHESTPLATE));
            }
        });
    }

    // ── Déplacement ───────────────────────────────────────────────

    // Distance max de déviation avant de forcer le retour sur le chemin
    private static final double MAX_DEVIATION = 10.0;

    private void moveMinions() {
        var roadManager = LolPlugin.getInstance().getRoadManager();
        for (var world : LolPlugin.getInstance().getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof Zombie z)) continue;
                if (!isMinion(z)) continue;
                Team team = getMinionTeam(z);
                if (team == null) continue;
                String lane = getMinionLane(z);

                // Distance au chemin le plus proche
                Location roadPoint = roadManager.getClosestRoadPoint(lane, z.getLocation());
                boolean tooFar = roadPoint != null
                        && roadPoint.getWorld().equals(z.getLocation().getWorld())
                        && roadPoint.distance(z.getLocation()) > MAX_DEVIATION;

                // Chercher un ennemi proche
                LivingEntity enemy = findNearbyEnemy(z, team);

                if (tooFar) {
                    // Trop loin du chemin → revenir, ignorer l'ennemi
                    z.setTarget(null);
                    z.getPathfinder().moveTo(roadPoint, 1.2);
                } else if (enemy != null) {
                    // Dévier pour attaquer (dans la limite des 10 blocs)
                    z.setTarget(enemy);
                } else {
                    // Avancer le long du chemin vers la base ennemie
                    z.setTarget(null);
                    Location next = getNextRoadWaypoint(team, lane, z.getLocation());
                    if (next != null) z.getPathfinder().moveTo(next, 1.0);
                }
            }
        }
    }

    /**
     * Prochain waypoint sur la route selon l'équipe.
     * BLEU parcourt la route dans l'ordre de tracé, ROUGE en sens inverse.
     */
    private Location getNextRoadWaypoint(Team team, String lane, Location current) {
        var road = LolPlugin.getInstance().getRoadManager().getRoad(lane);
        if (road == null || road.isEmpty()) return null;

        List<Location> path = new ArrayList<>(road);
        if (team == Team.RED) Collections.reverse(path); // rouge va dans l'autre sens

        // Trouver le 1er waypoint encore "devant" (non atteint)
        for (Location wp : path) {
            if (wp.getWorld().equals(current.getWorld()) && wp.distance(current) > 3.0) {
                return wp;
            }
        }
        return path.get(path.size() - 1); // dernier point = base ennemie
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
        var road = LolPlugin.getInstance().getRoadManager().getRoad(lane);
        if (road == null || road.isEmpty()) return null;
        // Bleu spawn au début de la route, rouge à la fin
        return team == Team.BLUE
                ? road.get(0).clone()
                : road.get(road.size() - 1).clone();
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
        String lane = z.getPersistentDataContainer().get(KEY_LANE, PersistentDataType.STRING);
        return lane != null ? lane : "mid";
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
