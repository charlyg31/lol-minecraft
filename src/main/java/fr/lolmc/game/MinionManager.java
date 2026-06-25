package fr.lolmc.game;
import fr.lolmc.util.Compat;
import fr.lolmc.util.MobAppearance;
import fr.lolmc.util.MobModel;
import fr.lolmc.util.MobAnimator;
import org.bukkit.Material;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

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

    // Vrais timers LoL (patch 14.x)
    // 1ère vague : exactement 1:05 après le début = 65s = 1300 ticks
    // Intervalle entre vagues : 30s = 600 ticks
    // ATTENTION : ces délais comptent depuis l'appel à startWaves() ;
    //             startWaves() doit être appelé exactement au début de la partie.
    private static final long WAVE_PERIOD      = 600L;   // 30s = 600 ticks
    private static final long FIRST_WAVE_DELAY = 1300L;  // 1:05 = 65s = 1300 ticks
    private static final int MINIONS_PER_WAVE = 6;
    private static final double MINION_HP = 477;
    private static final double CANNON_HP = 1257;
    private static final double MINION_SPEED = 0.25;

    private boolean spawning = false;
    private int waveCount = 0; // numéro de vague GLOBAL (incrémenté une fois par cycle)
    // Tâches stockées pour annulation propre
    private org.bukkit.scheduler.BukkitTask waveTask;
    private org.bukkit.scheduler.BukkitTask moveTask;
    // Inhibiteurs détruits → super-sbires sur cette lane (clé: "BLUE_top")
    private final java.util.Set<String> superMinionLanes = new java.util.HashSet<>();
    // Modèles custom : sbireUUID → liste des parties (pour nettoyage)
    private final Map<UUID, List<UUID>> minionDeco = new HashMap<>();

    /** Types de sbires (apparence façon LoL). */
    private enum MinionType { MELEE, CASTER, CANNON, SUPER }

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

        waveTask = new BukkitRunnable() {
            @Override public void run() {
                if (!spawning) { cancel(); return; }
                waveCount++; // 1 incrément par cycle de vague global
                spawnWave(Team.BLUE, waveCount);
                spawnWave(Team.RED, waveCount);
            }
        }.runTaskTimer(LolPlugin.getInstance(), FIRST_WAVE_DELAY, WAVE_PERIOD);

        // Tâche de déplacement des sbires (toutes les 10 ticks)
        moveTask = new BukkitRunnable() {
            @Override public void run() {
                if (!spawning) { cancel(); return; }
                moveMinions();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
    }

    public void stopWaves() {
        spawning = false;
        if (waveTask != null) { waveTask.cancel(); waveTask = null; }
        if (moveTask != null) { moveTask.cancel(); moveTask = null; }
        clearAllMinions();
        waveCount = 0;
        superMinionLanes.clear();
        minionDeco.clear();
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

    private void spawnWave(Team team, int wave) {
        // Spawn sur chaque lane configurée
        for (String lane : List.of("top", "mid", "bot")) {
            Location spawnPoint = getMinionSpawn(team, lane);
            if (spawnPoint == null) continue;

            for (int i = 0; i < MINIONS_PER_WAVE; i++) {
                final int delay = i * 8; // décaler chaque sbire de 0.4s
                // LoL : 3 sbires de mêlée puis 3 sbires à distance (mages)
                // 3e vague = un sbire canon à la place du 1er caster
                    final MinionType type;
                    if (wave % 3 == 0 && i == MINIONS_PER_WAVE / 2) {
                        type = MinionType.CANNON;
                    } else {
                        type = (i < MINIONS_PER_WAVE / 2) ? MinionType.MELEE : MinionType.CASTER;
                    }
                new BukkitRunnable() {
                    @Override public void run() {
                        if (spawning) spawnMinion(team, lane, spawnPoint, type);
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

    private void spawnMinion(Team team, String lane, Location loc, MinionType type) {
        Zombie minion = loc.getWorld().spawn(loc, Husk.class, z -> {
            z.setAdult(); // CORRECTION : Remplace le setBaby(false) déprécié
            z.setShouldBurnInDay(false);
            z.setCustomNameVisible(false);
            // Empêcher le zombie de cibler les joueurs tout seul (IA vanilla)
            z.setTarget(null);
            z.getAttribute(Compat.maxHealth()).setBaseValue(MINION_HP);
            z.setHealth(MINION_HP);
            z.getAttribute(Compat.movementSpeed()).setBaseValue(MINION_SPEED);
            // Tag PDC
            z.getPersistentDataContainer().set(KEY_MINION, PersistentDataType.BYTE, (byte) 1);
            z.getPersistentDataContainer().set(KEY_TEAM, PersistentDataType.STRING, team.name());
            z.getPersistentDataContainer().set(KEY_LANE, PersistentDataType.STRING, lane);
            z.customName(Component.text(team == Team.BLUE ? "🔵 Sbire" : "🔴 Sbire",
                    team == Team.BLUE ? NamedTextColor.BLUE : NamedTextColor.RED));
        });
        applyMinionModel(minion, type, team, type == MinionType.CASTER ? 0.7f : (type == MinionType.CANNON ? 1.0f : 0.8f));
            // HP du cannon plus élevé
            if (type == MinionType.CANNON) {
                var hpAttr = minion.getAttribute(fr.lolmc.util.Compat.maxHealth());
                if (hpAttr != null) { hpAttr.setBaseValue(CANNON_HP); minion.setHealth(CANNON_HP); }
            }
    }

    /** Rend le sbire invisible et lui pose un modèle custom anime (façon jungle). */
    private void applyMinionModel(LivingEntity minion, MinionType type, Team team, float hitboxScale) {
        MobAppearance.makeInvisible(minion);
        MobAppearance.setSilent(minion, true);
        List<UUID> parts = minionModel(type, team).spawnOn(minion);
        if (!parts.isEmpty()) minionDeco.put(minion.getUniqueId(), parts);
        var sc = minion.getAttribute(Compat.scale());
        if (sc != null) sc.setBaseValue(hitboxScale);
    }


    /** Spawn un super-sbire (Husk costaud, beaucoup de HP). */
    private void spawnSuperMinion(Team team, String lane, Location loc) {
        org.bukkit.entity.Husk superMinion = loc.getWorld().spawn(loc, org.bukkit.entity.Husk.class, h -> {
            h.setAdult(); // SÉCURITÉ : On s'assure également qu'il s'agit d'un adulte via l'API moderne
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
        });
        applyMinionModel(superMinion, MinionType.SUPER, team, 1.0f);
    }

    // ── Déplacement ───────────────────────────────────────────────

    // Distance max de déviation avant de forcer le retour sur le chemin
    private static final double MAX_DEVIATION = 10.0;

    private void moveMinions() {
        var roadManager = LolPlugin.getInstance().getRoadManager();
        java.util.List<org.bukkit.World> __worlds = WorldContext.getGameWorld() != null
                ? java.util.List.of(WorldContext.getGameWorld())
                : java.util.List.of();
            for (var world : __worlds) {
            for (Zombie z : world.getEntitiesByClass(Zombie.class)) {
                if (!isMinion(z)) continue;
                Team team = getMinionTeam(z);
                if (team == null) continue;
                String lane = getMinionLane(z);

                // Distance au chemin le plus proche
                Location roadPoint = roadManager.getClosestRoadPoint(lane, z.getLocation());
                boolean tooFar = roadPoint != null
                        && roadPoint.getWorld().equals(z.getLocation().getWorld())
                        && roadPoint.distance(z.getLocation()) > MAX_DEVIATION;

                // Couper toute cible vanilla parasite (zombie qui cible un joueur tout seul)
                if (z.getTarget() instanceof Player tp) {
                    var tmCheck = LolPlugin.getInstance().getTeamManager();
                    // Si la cible n'est pas un ennemi valide, l'annuler
                    if (tmCheck.getTeam(tp) == null || tmCheck.getTeam(tp) == team) {
                        z.setTarget(null);
                    }
                }

                // Chercher un ennemi proche (sbire ou champion)
                LivingEntity enemy = findNearbyEnemy(z, team);
                // Chercher une tourelle/structure ennemie à portée
                GameStructure enemyStructure = findNearbyEnemyStructure(z, team);

                if (tooFar) {
                    // Trop loin du chemin → revenir, ignorer tout le reste
                    z.setTarget(null);
                    z.getPathfinder().moveTo(roadPoint, 1.2);
                } else if (enemy != null) {
                    // Priorité 1 : attaquer un ennemi vivant (sbire/champion)
                    z.setTarget(enemy);
                } else if (enemyStructure != null) {
                    // Priorité 2 : attaquer la tourelle ennemie (structure, pas une entité)
                    z.setTarget(null);
                    attackStructure(z, enemyStructure);
                } else {
                    // Sinon : avancer le long du chemin vers la base ennemie
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

        // 1. Trouver l'INDEX du waypoint le plus proche du sbire
        int closestIdx = 0;
        double closestDist = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            Location wp = path.get(i);
            if (!wp.getWorld().equals(current.getWorld())) continue;
            double d = wp.distance(current);
            if (d < closestDist) { closestDist = d; closestIdx = i; }
        }

        // 2. Viser le waypoint SUIVANT (progression le long du chemin)
        //    Si on est proche du waypoint courant (<4 blocs), passer au suivant.
        int targetIdx = closestIdx;
        if (closestDist < 4.0) targetIdx = Math.min(closestIdx + 1, path.size() - 1);

        return path.get(targetIdx);
    }

    // Portée d'attaque d'un sbire sur une tourelle (blocs)
    private static final double MINION_TURRET_RANGE = 6.0;
    // Dégâts d'un sbire à une tourelle par coup (LoL: mêlée ~12, caster ~23 environ)
    private static final double MINION_DAMAGE_TO_TURRET = 14.0;
    // Cadence d'attaque sur les tourelles (cooldown en ms par sbire)
    private final Map<java.util.UUID, Long> lastTurretHit = new HashMap<>();
    private static final long TURRET_HIT_COOLDOWN = 1000L; // 1 coup/seconde

    /** Cherche une structure ENNEMIE à portée d'un sbire. */
    private GameStructure findNearbyEnemyStructure(Zombie minion, Team team) {
        var mapManager = LolPlugin.getInstance().getMapManager();
        GameStructure s = mapManager.getStructureAt(minion.getLocation(), MINION_TURRET_RANGE);
        if (s == null) return null;
        // La structure doit appartenir à l'équipe ADVERSE
        if (s.getTeam() == team) return null;
        return s;
    }

    /** Le sbire s'approche et inflige des dégâts à la structure (tourelle). */
    private void attackStructure(Zombie minion, GameStructure structure) {
        // Se déplacer vers la structure si pas assez proche
        Location target = structure.getCenter();
        double dist = minion.getLocation().distance(target);
        if (dist > 2.5) {
            minion.getPathfinder().moveTo(target, 1.0);
        }
        // Infliger des dégâts à cadence limitée
        long now = System.currentTimeMillis();
        Long last = lastTurretHit.get(minion.getUniqueId());
        if (last == null || (now - last) >= TURRET_HIT_COOLDOWN) {
            lastTurretHit.put(minion.getUniqueId(), now);
            MobAnimator.triggerAttack(minion.getUniqueId());
            // Animation : le sbire "frappe" + particule
            minion.swingMainHand();
            target.getWorld().spawnParticle(org.bukkit.Particle.CRIT,
                    target.clone().add(0.5, 1, 0.5), 5, 0.3, 0.3, 0.3);
            boolean phaseChanged = structure.takeDamage(MINION_DAMAGE_TO_TURRET);
            if (phaseChanged) {
                LolPlugin.getInstance().getMapManager().updateStructurePhase(structure);
            }
            // Si détruite, déclencher la logique de destruction
            if (structure.isDestroyed()) {
                LolPlugin.getInstance().getMapManager().updateStructurePhase(structure);
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

    // Méthode préservée pour usage futur (actuellement la logique est inline dans tickMinionMovement)
    @SuppressWarnings("unused")
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

    /** Modèle composite custom d'un sbire, aux couleurs de l'équipe (façon LoL). */
    private MobModel minionModel(MinionType type, Team team) {
        Material body = (team == Team.BLUE) ? Material.BLUE_CONCRETE : Material.RED_CONCRETE;
        Material trim = (team == Team.BLUE) ? Material.LIGHT_BLUE_CONCRETE : Material.ORANGE_CONCRETE;
        return switch (type) {
            // ⚔ Sbire de mêlée : petit corps + épée
            case CANNON -> new MobModel()
                    
                    
                    ;
                case MELEE -> new MobModel()
                    .box(body, 0f, 0.0f, 0.0f, 0.45f, 0.55f, 0.4f)      // corps
                    .box(body, 0f, 0.55f, 0.0f, 0.4f, 0.4f, 0.4f)       // tête
                    .cube(Material.WHITE_CONCRETE, -0.1f, 0.7f, 0.22f, 0.1f) // œil G
                    .cube(Material.WHITE_CONCRETE,  0.1f, 0.7f, 0.22f, 0.1f) // œil D
                    .box(Material.IRON_BLOCK, 0.32f, 0.3f, 0.0f, 0.1f, 0.55f, 0.1f); // épée

            // 🪄 Sbire mage : plus petit + bâton lumineux
            case CASTER -> new MobModel()
                    .box(body, 0f, 0.0f, 0.0f, 0.4f, 0.5f, 0.38f)       // corps
                    .box(body, 0f, 0.5f, 0.0f, 0.36f, 0.36f, 0.36f)     // tête
                    .cube(Material.WHITE_CONCRETE, -0.09f, 0.63f, 0.2f, 0.09f)
                    .cube(Material.WHITE_CONCRETE,  0.09f, 0.63f, 0.2f, 0.09f)
                    .box(Material.OAK_FENCE, 0.3f, 0.2f, 0.0f, 0.07f, 0.6f, 0.07f) // bâton
                    .cube(Material.SEA_LANTERN, 0.3f, 0.78f, 0.0f, 0.16f); // orbe lumineux

            // 🛡 Super-sbire : plus gros + armure + grande épée
            case SUPER -> new MobModel()
                    .box(body, 0f, 0.0f, 0.0f, 0.6f, 0.8f, 0.55f)       // corps
                    .box(trim, 0f, 0.55f, 0.0f, 0.62f, 0.18f, 0.57f)    // ceinture/armure
                    .box(body, 0f, 0.85f, 0.0f, 0.5f, 0.45f, 0.5f)      // tête
                    .cube(Material.WHITE_CONCRETE, -0.13f, 1.0f, 0.28f, 0.12f)
                    .cube(Material.WHITE_CONCRETE,  0.13f, 1.0f, 0.28f, 0.12f)
                    .box(Material.IRON_BLOCK, -0.42f, 0.55f, 0.0f, 0.18f, 0.3f, 0.5f) // épaule G
                    .box(Material.IRON_BLOCK,  0.42f, 0.55f, 0.0f, 0.18f, 0.3f, 0.5f) // épaule D
                    .box(Material.IRON_BLOCK,  0.5f, 0.35f, 0.0f, 0.13f, 0.85f, 0.13f); // grande épée
        };
    }

    /** Appelé à la mort d'un sbire : retire son modèle et son animation. */
    public void onMinionDeath(UUID minionId) {
        List<UUID> parts = minionDeco.remove(minionId);
        if (parts != null) {
            for (UUID pid : parts) {
                Entity d = org.bukkit.Bukkit.getEntity(pid);
                if (d != null) d.remove();
            }
        }
        MobAnimator.unregister(minionId);
    }

    public void clearAllMinions() {
        // Retirer les parties de modèle de chaque sbire + désenregistrer l'animation
        for (List<UUID> parts : minionDeco.values()) {
            for (UUID pid : parts) {
                Entity d = org.bukkit.Bukkit.getEntity(pid);
                if (d != null) d.remove();
            }
        }
        for (UUID id : minionDeco.keySet()) MobAnimator.unregister(id);
        minionDeco.clear();
        // Retirer les sbires eux-mêmes
        java.util.List<org.bukkit.World> __worlds = WorldContext.getGameWorld() != null
                ? java.util.List.of(WorldContext.getGameWorld())
                : java.util.List.of();
            for (var world : __worlds) {
            for (Entity e : world.getEntities()) {
                if (e instanceof LivingEntity le && isMinion(le)) {
                    e.remove();
                }
            }
        }
    }

    /** Désactive les super-sbires sur une lane (inhibiteur repoussé). */
    public void onInhibitorRespawned(String key) {
        // key format: "BLUE_top" ou "RED_mid"
        superMinionLanes.remove(key);
    }


    /** Retourne le type de sbire ("melee", "caster", "cannon", "super") ou null si inconnu. */
    public static String getMinionTypeTag(org.bukkit.entity.LivingEntity e) {
        if (!isMinion(e)) return null;
        var pdc = e.getPersistentDataContainer();
        return pdc.has(KEY_LANE, org.bukkit.persistence.PersistentDataType.STRING)
            ? pdc.get(KEY_LANE, org.bukkit.persistence.PersistentDataType.STRING) : "melee";
    }




    /** Spawn un sbire de test à la position donnée (admin /lol spawn). */
    public void spawnTestMinion(org.bukkit.Location loc, Team team, String typeStr) {
        MinionType type = switch (typeStr.toLowerCase()) {
            case "caster" -> MinionType.CASTER;
            case "cannon" -> MinionType.CANNON;
            case "super"  -> MinionType.SUPER;
            default       -> MinionType.MELEE;
        };
        if (type == MinionType.SUPER) { spawnSuperMinion(team, "mid", loc); return; }
        var e = type == MinionType.CASTER
            ? loc.getWorld().spawn(loc, org.bukkit.entity.WitherSkeleton.class)
            : loc.getWorld().spawn(loc, org.bukkit.entity.Zombie.class);
        var pdc = e.getPersistentDataContainer();
        pdc.set(KEY_MINION, org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
        pdc.set(KEY_TEAM,   org.bukkit.persistence.PersistentDataType.STRING, team.name());
        pdc.set(KEY_LANE,   org.bukkit.persistence.PersistentDataType.STRING, typeStr.toLowerCase());
        applyMinionModel(e, type, team, type == MinionType.CASTER ? 0.7f : 1.0f);
    }

    /** Force le spawn immédiat d'une vague (admin /lol wave). */
    public void forceWave() {
        waveCount++;
        spawnWave(Team.BLUE, waveCount);
        spawnWave(Team.RED, waveCount);
    }
}
