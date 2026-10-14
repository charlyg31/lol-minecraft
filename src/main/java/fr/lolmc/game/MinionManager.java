package fr.lolmc.game;
import fr.lolmc.util.Compat;
import fr.lolmc.util.MobAppearance;
import fr.lolmc.util.MobModel;
import fr.lolmc.util.MobAnimator;
import org.bukkit.Material;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.team.TeamManager.Team;
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
 * <p>Les sbires sont des Zombies/Husks taggés via PDC.
 */
public class MinionManager {
    private fr.lolmc.instance.GameInstance gameInstance = null;
    public void setGameInstance(fr.lolmc.instance.GameInstance inst) { this.gameInstance = inst; }
    public fr.lolmc.instance.GameInstance getGameInstance() { return gameInstance; }

    /** Monde où évoluent les sbires : celui de l'instance, sinon le monde de jeu configuré. */
    private org.bukkit.World myWorld() {
        return gameInstance != null ? gameInstance.getWorld() : WorldContext.getGameWorld();
    }

    /** Route d'une lane avec ses points ramenés dans le monde de CETTE partie (copie du template). */
    private List<Location> roadFor(String lane) {
        List<Location> road = LolPlugin.getInstance().getRoadManager().getRoad(lane);
        org.bukkit.World w = myWorld();
        if (road == null || w == null) return road;
        List<Location> out = new ArrayList<>(road.size());
        for (Location l : road) {
            out.add(l.getWorld() != null && l.getWorld().equals(w) ? l : new Location(w, l.getX(), l.getY(), l.getZ()));
        }
        return out;
    }

    private Location closestRoadPoint(String lane, Location from) {
        List<Location> road = roadFor(lane);
        if (road == null || road.isEmpty()) return null;
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (Location wp : road) {
            if (!wp.getWorld().equals(from.getWorld())) continue;
            double d = wp.distance(from);
            if (d < bd) { bd = d; best = wp; }
        }
        return best;
    }

    private GameManager siblingGameManager() {
        return gameInstance != null ? gameInstance.getGameManager() : LolPlugin.getInstance().getGameManager();
    }
    private MapManager siblingMapManager() {
        return gameInstance != null ? gameInstance.getMapManager() : LolPlugin.getInstance().getMapManager();
    }

    /** UUID des sbires vivants de cette session (tout le reste = orphelins). */
    public static final java.util.Set<UUID> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static NamespacedKey KEY_MINION;
    public static NamespacedKey KEY_TEAM;
    public static NamespacedKey KEY_LANE;
    public static NamespacedKey KEY_TYPE;
    public static NamespacedKey KEY_ONHIT;

    /** Bonus contre les sbires d'un sbire (% des PV actuels de la cible). */
    public static double getOnHitPct(LivingEntity e) {
        Double v = KEY_ONHIT != null ? e.getPersistentDataContainer().get(KEY_ONHIT, PersistentDataType.DOUBLE) : null;
        return v != null ? v : 0;
    }

    // Waypoints par lane : "top" → liste de points que les sbires suivent
    private final Map<String, List<Location>> blueLaneWaypoints = new HashMap<>();
    private final Map<String, List<Location>> redLaneWaypoints = new HashMap<>();

    // Vrais timers LoL (patch 14.x)
    // 1ère vague : exactement 1:05 après le début = 65s = 1300 ticks
    // Intervalle entre vagues : 30s = 600 ticks
    // ATTENTION : ces délais comptent depuis l'appel à startWaves() ;
    //             startWaves() doit être appelé exactement au début de la partie.
    // Wiki LoL (26.01) : première vague à 0:30, puis toutes les 30 s, 25 s dès 14:00, 20 s dès 30:00
    private static final long FIRST_WAVE_DELAY = 600L;   // 0:30 = 600 ticks
    private static final int MINIONS_PER_WAVE = 6;
    /**
     * Statistiques d'un sbire à son apparition : formules du wiki LoL (infobox, patch 26.09),
     * n = numéro d'amélioration (1 à 0:30, puis +1 toutes les 90 s). {@code onHitPct} : bonus
     * contre les sbires, en % des PV actuels de la cible.
     */
    private record MinionStats(double hp, double ad, double attackSpeed, double armor, double mr,
                               int gold, double xp, double onHitPct) {}

    /** Nombre d'améliorations des sbires depuis le début de la partie. */
    private int upgrades() {
        long s = siblingGameManager() != null ? siblingGameManager().getElapsedSeconds() : 0;
        return s < 30 ? 0 : (int) ((s - 30) / 90) + 1;
    }

    private MinionStats statsFor(MinionType type) {
        int n = Math.max(1, upgrades());
        // Or du canon et du super-sbire : 50 à 0:30 puis +1 par amélioration (59 à 14:00, 66 à 25:00)
        int bigGold = 49 + n;
        return switch (type) {
            // PV 430 + 35n (max 1550) ; AD 11 jusqu'à n = 5 puis +3 (max 80) ;
            // armure 0 jusqu'à n = 5 puis 0,085 × (n-6)(n-5)/2 (max 20)
            case MELEE  -> new MinionStats(Math.min(1550, 430 + 35.0 * n),
                    Math.min(80, 11 + 3.0 * Math.max(0, n - 5)), 1.25,
                    n <= 5 ? 0 : Math.min(20, 0.085 * (n - 6) * (n - 5) / 2.0), 0, 20, 62, 0.02);
            // PV 275 + 9n (max 600) ; AD 19,5 + 1,5n jusqu'à n = 5 puis +4 (max 125)
            case CASTER -> new MinionStats(Math.min(600, 275 + 9.0 * n),
                    Math.min(125, 19.5 + 1.5 * Math.min(n, 5) + 4.0 * Math.max(0, n - 5)), 0.667, 0, 0, 14, 31, 0.035);
            // PV 750 + 85n (max 5850) ; AD 36 + 1,5n, +4 à partir de la 15e (max 126)
            case CANNON -> new MinionStats(Math.min(5850, 750 + 85.0 * n),
                    Math.min(126, 36 + 1.5 * Math.min(n, 14) + 4.0 * Math.max(0, n - 14)), 1.0, 0, 0, bigGold, 75, 0.05);
            // PV 1500 + 100n (max 7500) ; AD 180 au départ puis +5 (max 480)
            case SUPER  -> new MinionStats(Math.min(7500, 1500 + 100.0 * n),
                    Math.min(480, 175 + 5.0 * n), 0.85, 100, -30, bigGold, 75, 0);
        };
    }

    /** Applique les stats LoL : PV virtuels (au-delà du plafond Minecraft), AD, résistances, or, XP. */
    private void applyStats(LivingEntity minion, MinionType type, String label) {
        MinionStats st = statsFor(type);
        fr.lolmc.util.VirtualHP.init(minion, st.hp());
        fr.lolmc.util.MobCombat.setStats(minion, st.ad(), st.attackSpeed(), st.armor(), st.mr(), st.gold(), st.xp());
        minion.getPersistentDataContainer().set(KEY_TYPE, PersistentDataType.STRING, type.name().toLowerCase());
        minion.getPersistentDataContainer().set(KEY_ONHIT, PersistentDataType.DOUBLE, st.onHitPct());
        fr.lolmc.util.HealthBar.apply(minion, st.hp(), st.hp(), label);
        minion.setCustomNameVisible(true);
    }
    private static final double MINION_SPEED = 0.25;

    private boolean spawning = false;
    private int waveCount = 0; // numéro de vague GLOBAL (incrémenté une fois par cycle)
    public int getWaveCount() { return waveCount; }
    // Tâches stockées pour annulation propre
    private org.bukkit.scheduler.BukkitTask waveTask;
    private org.bukkit.scheduler.BukkitTask moveTask;
    // Inhibiteurs détruits → super-sbires sur cette lane (clé: "BLUE_top")
    private final java.util.Set<String> superMinionLanes = new java.util.HashSet<>();
    // Modèles custom : sbireUUID → liste des parties (pour nettoyage)
    private final Map<UUID, List<UUID>> minionDeco = new HashMap<>();

    /** Types de sbires (apparence façon LoL). */
    private enum MinionType { MELEE, CASTER, CANNON, SUPER }

    public MinionManager() {
        KEY_MINION = new NamespacedKey(LolPlugin.getInstance(), "minion");
        KEY_TEAM = new NamespacedKey(LolPlugin.getInstance(), "minion_team");
        KEY_LANE = new NamespacedKey(LolPlugin.getInstance(), "minion_lane");
        KEY_TYPE = new NamespacedKey(LolPlugin.getInstance(), "minion_type");
        KEY_ONHIT = new NamespacedKey(LolPlugin.getInstance(), "minion_onhit");
    }

    // ── Démarrage / arrêt des vagues ──────────────────────────────

    public void startWaves() {
        if (spawning) return;
        purgeWorld(myWorld());
        spawning = true;

        scheduleNextWave(FIRST_WAVE_DELAY);

        // Tâche de déplacement des sbires (toutes les 10 ticks)
        moveTask = new BukkitRunnable() {
            @Override public void run() {
                if (!spawning) { cancel(); return; }
                moveMinions();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
    }

    /** Programme la prochaine vague ; l'intervalle raccourcit avec le temps de jeu (LoL). */
    private void scheduleNextWave(long delayTicks) {
        waveTask = new BukkitRunnable() {
            @Override public void run() {
                if (!spawning) return;
                waveCount++; // 1 incrément par cycle de vague global
                spawnWave(Team.BLUE, waveCount);
                spawnWave(Team.RED, waveCount);
                long s = siblingGameManager() != null ? siblingGameManager().getElapsedSeconds() : 0;
                scheduleNextWave(s >= 30 * 60 ? 400L : s >= 14 * 60 ? 500L : 600L);
            }
        }.runTaskLater(LolPlugin.getInstance(), delayTicks);
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

            // LoL : 3 mêlées, (canon), 3 casters ; le canon S'AJOUTE à la vague (avant : il
            // remplaçait un caster) et n'apparaît pas dans une vague qui a un super-sbire
            boolean cannon = isCannonWave(wave) && !hasSuperMinions(team, lane);
            int count = MINIONS_PER_WAVE + (cannon ? 1 : 0);
            for (int i = 0; i < count; i++) {
                final int delay = i * 8; // décaler chaque sbire de 0.4s
                final MinionType type = i < 3 ? MinionType.MELEE
                        : (cannon && i == 3) ? MinionType.CANNON : MinionType.CASTER;
                new BukkitRunnable() {
                    @Override public void run() {
                        if (spawning) spawnMinion(team, lane, spawnPoint, type);
                    }
                }.runTaskLater(LolPlugin.getInstance(), delay);
            }
            // Super-sbire si l'inhibiteur ennemi de cette lane est détruit ; 2 par lane quand les
            // 3 inhibiteurs ennemis sont tombés (wiki LoL)
            if (hasSuperMinions(team, lane)) {
                final Location sp = spawnPoint;
                boolean allDown = hasSuperMinions(team, "top") && hasSuperMinions(team, "mid")
                        && hasSuperMinions(team, "bot");
                for (int k = 0; k < (allDown ? 2 : 1); k++) {
                    new BukkitRunnable() {
                        @Override public void run() {
                            if (spawning) spawnSuperMinion(team, lane, sp);
                        }
                    }.runTaskLater(LolPlugin.getInstance(), count * 8 + 4 + k * 8L);
                }
            }
        }
    }

    // Cadence canon LoL (wiki) : 3e vague (1:30), puis toutes les 3 vagues avant 14:00,
    // toutes les 2 entre 14:00 et 25:00, chaque vague ensuite
    private boolean isCannonWave(int wave) {
        long elapsedS = siblingGameManager() != null
            ? siblingGameManager().getElapsedSeconds() : 0;
        int cannonEvery = elapsedS >= 25 * 60 ? 1 : (elapsedS >= 14 * 60 ? 2 : 3);
        return wave % cannonEvery == 0;
    }

    private void spawnMinion(Team team, String lane, Location loc, MinionType type) {
        Zombie minion = loc.getWorld().spawn(loc, Husk.class, z -> {
            z.setAdult(); // CORRECTION : Remplace le setBaby(false) déprécié
            z.setShouldBurnInDay(false);
            // Empêcher le zombie de cibler les joueurs tout seul (IA vanilla)
            z.setTarget(null);
            var msAttr = z.getAttribute(Compat.movementSpeed());
            if (msAttr != null) msAttr.setBaseValue(MINION_SPEED);
            // Tag PDC
            z.getPersistentDataContainer().set(KEY_MINION, PersistentDataType.BYTE, (byte) 1);
            z.getPersistentDataContainer().set(KEY_TEAM, PersistentDataType.STRING, team.name());
            z.getPersistentDataContainer().set(KEY_LANE, PersistentDataType.STRING, lane);
            // Supprimer l'équipement vanilla (armes, armures)
            var eq = z.getEquipment();
            eq.setItemInMainHand(null);
            eq.setItemInOffHand(null);
            eq.setHelmet(null);
            eq.setChestplate(null);
            eq.setLeggings(null);
            eq.setBoots(null);
        });
        applyMinionModel(minion, type, team, type == MinionType.CASTER ? 0.7f : (type == MinionType.CANNON ? 1.0f : 0.8f));
        // Stats + barre de vie APRÈS makeInvisible (forcer visible=true car Paper cache le name
        // sur les entités invisibles). Avant, la barre affichait 60 PV pour un sbire qui en avait 477.
        applyStats(minion, type, team == Team.BLUE ? "🔵 Sbire" : "🔴 Sbire");
    }

    /** Rend le sbire invisible et lui pose un modèle custom anime (façon jungle). */
    private void applyMinionModel(LivingEntity minion, MinionType type, Team team, float hitboxScale) {
        LIVE.add(minion.getUniqueId());
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
            var msAttr = h.getAttribute(Compat.movementSpeed());
            if (msAttr != null) msAttr.setBaseValue(MINION_SPEED);
            h.getPersistentDataContainer().set(KEY_MINION, PersistentDataType.BYTE, (byte) 1);
            h.getPersistentDataContainer().set(KEY_TEAM, PersistentDataType.STRING, team.name());
            h.getPersistentDataContainer().set(KEY_LANE, PersistentDataType.STRING, lane);
            h.customName(net.kyori.adventure.text.Component.text(
                    (team == Team.BLUE ? "🔵 " : "🔴 ") + "Super-Sbire",
                    team == Team.BLUE ? net.kyori.adventure.text.format.NamedTextColor.BLUE
                            : net.kyori.adventure.text.format.NamedTextColor.RED));
        });
        applyMinionModel(superMinion, MinionType.SUPER, team, 1.0f);
        applyStats(superMinion, MinionType.SUPER, team == Team.BLUE ? "🔵 Super-Sbire" : "🔴 Super-Sbire");
    }

    // ── Déplacement ───────────────────────────────────────────────

    // Distance max de déviation avant de forcer le retour sur le chemin
    private static final double MAX_DEVIATION = 10.0;

    private void moveMinions() {
        java.util.List<org.bukkit.World> __worlds = myWorld() != null
                ? java.util.List.of(myWorld())
                : java.util.List.of();
            for (var world : __worlds) {
            for (Zombie z : world.getEntitiesByClass(Zombie.class)) {
                if (!isMinion(z)) continue;
                Team team = getMinionTeam(z);
                if (team == null) continue;
                String lane = getMinionLane(z);
                baronAura(z);

                // Distance au chemin le plus proche
                Location roadPoint = closestRoadPoint(lane, z.getLocation());
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

                // Mage / canon : attaque à distance (projectile), sans la frappe vanilla au contact
                if (!tooFar && isRangedMinion(z)) {
                    LivingEntity foe = rangedAggroTarget(z);
                    if (foe == null) foe = findNearbyEnemy(z, team);
                    if (foe != null) { rangedAttack(z, team, foe); continue; }
                }

                // Aggro forcée en cours (champion attaqué) → ne pas retarget
                if (fr.lolmc.game.AggroManager.hasMinionAggro(z)
                        && z.getTarget() instanceof Player) {
                    continue; // garder la cible aggro pendant 3s (return figeait TOUS les autres sbires)
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

    private static NamespacedKey BARON_MS;

    /**
     * Main du Baron (wiki) : sbires de mêlée et super-sbires renforcés +50 % de vitesse de
     * déplacement (super-sbires : +25 % de vitesse d'attaque en plus). Retiré hors de l'aura.
     */
    private static void baronAura(LivingEntity m) {
        var attr = m.getAttribute(Compat.movementSpeed());
        if (attr == null) return;
        if (BARON_MS == null) BARON_MS = new NamespacedKey(LolPlugin.getInstance(), "baron_aura_ms");
        String t = getMinionTypeTag(m);
        boolean emp = ("melee".equals(t) || "super".equals(t)) && JungleBuffs.isEmpowered(m);
        var cur = attr.getModifier(BARON_MS);
        if (emp && cur == null) {
            attr.addTransientModifier(new org.bukkit.attribute.AttributeModifier(BARON_MS, 0.5,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR));
            if ("super".equals(t)) fr.lolmc.util.MobCombat.setAttackSpeedMult(m, 1.25);
        } else if (!emp && cur != null) {
            attr.removeModifier(BARON_MS);
            if ("super".equals(t)) fr.lolmc.util.MobCombat.setAttackSpeedMult(m, 1.0);
        }
    }

    // Prochain tir et cible d'aggro (champion qui a frappé un allié) des sbires à distance
    private final Map<UUID, Long> nextShot = new HashMap<>();
    private final Map<UUID, UUID> aggroFoe = new HashMap<>();

    private static boolean isRangedMinion(LivingEntity m) {
        String t = getMinionTypeTag(m);
        return "caster".equals(t) || "cannon".equals(t);
    }

    /** Champion à punir pendant l'aggro (la cible vanilla est effacée pour ne pas frapper au contact). */
    private LivingEntity rangedAggroTarget(Zombie z) {
        if (!fr.lolmc.game.AggroManager.hasMinionAggro(z)) { aggroFoe.remove(z.getUniqueId()); return null; }
        if (z.getTarget() instanceof Player ap) aggroFoe.put(z.getUniqueId(), ap.getUniqueId());
        UUID id = aggroFoe.get(z.getUniqueId());
        Player p = id != null ? org.bukkit.Bukkit.getPlayer(id) : null;
        if (p == null || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || !p.getWorld().equals(z.getWorld())
                || p.getLocation().distanceSquared(z.getLocation()) > 100) return null;
        return p;
    }

    /**
     * Mage (portée 550, projectile 650 unités/s) et canon (portée 300, 1200 unités/s) : ils
     * s'arrêtent à portée et tirent à leur vitesse d'attaque ; les dégâts tombent à l'impact.
     * Avant, ils marchaient jusqu'au contact et frappaient comme des sbires de mêlée.
     */
    private void rangedAttack(Zombie z, Team team, LivingEntity foe) {
        boolean cannon = "cannon".equals(getMinionTypeTag(z));
        // Main du Baron : portée et AD bonus des sbires à distance renforcés (mage +100 / +20, canon +750 / +50)
        double[] empower = JungleBuffs.rangedMinionBonus(z);
        double range = (cannon ? 4.3 : 6.8) + empower[1];
        z.setTarget(null);
        Location from = z.getLocation(), to = foe.getLocation();
        if (from.distanceSquared(to) > range * range) { z.getPathfinder().moveTo(to, 1.0); return; }
        z.getPathfinder().stopPathfinding();
        z.setRotation((float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ())), 0f);
        long now = System.currentTimeMillis();
        Long next = nextShot.get(z.getUniqueId());
        if (next != null && now < next) return;
        nextShot.put(z.getUniqueId(), now + (long) (1000 / Math.max(0.1, fr.lolmc.util.MobCombat.getAttackSpeed(z))));
        MobAnimator.triggerAttack(z.getUniqueId());
        Material glass = team == Team.BLUE ? Material.LIGHT_BLUE_STAINED_GLASS : Material.ORANGE_STAINED_GLASS;
        double ad = fr.lolmc.util.MobCombat.getAD(z) + empower[0], onHit = getOnHitPct(z);
        fr.lolmc.util.TargetingUtil.launchHoming(null, from.clone().add(0, cannon ? 0.6 : 0.9, 0), foe,
                cannon ? 12.0 : 6.5, new org.bukkit.inventory.ItemStack(cannon ? Material.FIRE_CHARGE : glass),
                cannon ? 0.5f : 0.4f, glass, false, () -> minionShotHit(foe, ad, onHit));
        z.getWorld().playSound(from, cannon ? org.bukkit.Sound.ENTITY_GENERIC_EXPLODE : org.bukkit.Sound.ENTITY_BLAZE_SHOOT,
                cannon ? 0.3f : 0.4f, cannon ? 1.6f : 1.8f);
    }

    /** Impact d'un tir de sbire : 60 % de l'AD contre un champion, AD + % des PV actuels contre un sbire. */
    private static void minionShotHit(LivingEntity foe, double ad, double onHitPct) {
        if (foe.isDead() || !foe.isValid()) return;
        if (foe instanceof Player p) {
            if (!LolPlugin.getInstance().getChampionManager().hasChampion(p)
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
            fr.lolmc.util.DamageUtil.damage(null, p, ad * MINION_DAMAGE_VS_CHAMPIONS_STRUCTURES, false,
                    fr.lolmc.util.DamageUtil.Type.PHYSICAL);
        } else if (isMinion(foe) && !fr.lolmc.util.VirtualHP.isDying(foe)) {
            fr.lolmc.util.MobCombat.damage(null, foe, ad + onHitPct * fr.lolmc.util.VirtualHP.getCurrent(foe),
                    fr.lolmc.util.DamageUtil.Type.PHYSICAL);
        }
    }

    /**
     * Prochain waypoint sur la route selon l'équipe.
     * BLEU parcourt la route dans l'ordre de tracé, ROUGE en sens inverse.
     */
    private Location getNextRoadWaypoint(Team team, String lane, Location current) {
        var road = roadFor(lane);
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
    // Contre les champions et les structures, les sbires infligent 60 % de leurs dégâts (LoL)
    public static final double MINION_DAMAGE_VS_CHAMPIONS_STRUCTURES = 0.60;
    // Cadence d'attaque sur les tourelles (cooldown en ms par sbire)
    private final Map<java.util.UUID, Long> lastTurretHit = new HashMap<>();
    private static final long TURRET_HIT_COOLDOWN = 1000L; // 1 coup/seconde

    /** Cherche une structure ENNEMIE à portée d'un sbire. */
    private GameStructure findNearbyEnemyStructure(Zombie minion, Team team) {
        var mapManager = siblingMapManager();
        GameStructure s = mapManager.getStructureAt(minion.getLocation(), MINION_TURRET_RANGE);
        if (s == null) return null;
        // La structure doit appartenir à l'équipe ADVERSE et ne pas être protégée (anti-backdoor)
        if (s.getTeam() == team || mapManager.isInvulnerable(s)) return null;
        return s;
    }

    /** Le sbire s'approche et inflige des dégâts à la structure (tourelle). */
    private void attackStructure(Zombie minion, GameStructure structure) {
        // Même règle que pour les champions : structure protégée (anti-backdoor)
        if (siblingMapManager().isInvulnerable(structure)) return;
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
            fr.lolmc.util.VisualEffectUtil.impact(target.getWorld(),
                    target.clone().add(0.5, 1, 0.5), Material.WHITE_STAINED_GLASS, 0.26f, 3L);
            boolean wasDestroyed = structure.isDestroyed();
            // Une frappe par seconde : AD × vitesse d'attaque × 60 % (canon : 84 %, patch 26.1)
            double vsStructure = "cannon".equals(getMinionTypeTag(minion)) ? 0.84 : MINION_DAMAGE_VS_CHAMPIONS_STRUCTURES;
            double dmg = fr.lolmc.util.MobCombat.getAD(minion) * fr.lolmc.util.MobCombat.getAttackSpeed(minion) * vsStructure;
            var turrets = gameInstance != null ? gameInstance.getTurretManager() : LolPlugin.getInstance().getTurretManager();
            boolean phaseChanged = structure.takeDamage(dmg);
            // Plaques tombées sous les coups des sbires : perdues sans or (aucun champion)
            if (turrets != null && TurretManager.hasPlatesTier(structure)) turrets.dropPlatesSilently(structure);
            if (phaseChanged) {
                siblingMapManager().updateStructurePhase(structure);
            }
            // Si détruite par ce coup : récompenses, super-sbires, victoire (comme pour un champion)
            if (!wasDestroyed && structure.isDestroyed()) {
                siblingMapManager().updateStructurePhase(structure);
                var sdl = LolPlugin.getInstance().getStructureDamageListener();
                if (sdl != null) sdl.onStructureDestroyedByMinions(structure, minion.getWorld());
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
                // Pas les champions morts (spectateurs en attente de respawn)
                if (cm.hasChampion(p) && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                        && tm.getTeam(p) != null && tm.getTeam(p) != team) {
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
        return waypoints.getLast(); // dernier point = base ennemie
    }

    private Location getMinionSpawn(Team team, String lane) {
        var road = roadFor(lane);
        if (road == null || road.isEmpty()) return null;
        // Bleu spawn au début de la route, rouge à la fin
        return team == Team.BLUE
                ? road.getFirst().clone()
                : road.getLast().clone();
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
            // 💣 Sbire canon : chariot sur roues + fût (avant : modèle vide, sbire invisible)
            case CANNON -> new MobModel()
                    .box(body, 0f, 0.15f, 0.0f, 0.75f, 0.4f, 0.95f)                 // châssis
                    .box(trim, 0f, 0.55f, -0.1f, 0.55f, 0.35f, 0.6f)                // tourelle
                    .box(Material.POLISHED_BLACKSTONE, 0f, 0.62f, 0.45f, 0.24f, 0.24f, 0.75f) // fût
                    .cube(Material.BLACK_CONCRETE, -0.42f, 0.0f,  0.3f, 0.3f)       // roues
                    .cube(Material.BLACK_CONCRETE,  0.42f, 0.0f,  0.3f, 0.3f)
                    .cube(Material.BLACK_CONCRETE, -0.42f, 0.0f, -0.3f, 0.3f)
                    .cube(Material.BLACK_CONCRETE,  0.42f, 0.0f, -0.3f, 0.3f);
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
        LIVE.remove(minionId);
        nextShot.remove(minionId);
        aggroFoe.remove(minionId);
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
        // Uniquement CETTE partie : ses sbires (monde de l'instance) et ses modèles.
        purgeWorld(myWorld());
        for (List<UUID> parts : minionDeco.values()) {
            for (UUID pid : parts) {
                Entity d = org.bukkit.Bukkit.getEntity(pid);
                if (d != null) d.remove();
            }
        }
        for (UUID id : minionDeco.keySet()) { MobAnimator.unregister(id); LIVE.remove(id); }
        minionDeco.clear();
    }

    /** Supprime sbires et modèles d'UN monde. */
    public static void purgeWorld(org.bukkit.World world) {
        if (world == null || KEY_MINION == null) return;
        for (Entity e : world.getEntities()) {
            if (e instanceof LivingEntity le && isMinion(le)) {
                LIVE.remove(e.getUniqueId());
                e.remove();
            } else if (e instanceof org.bukkit.entity.Display
                    && fr.lolmc.util.MobAppearance.isDecoration(e)
                    && (e.getVehicle() == null
                        || (e.getVehicle() instanceof LivingEntity v && isMinion(v)))) {
                e.remove();
            }
        }
    }

    /** Supprime une entité chargée si c'est un sbire ou un modèle orphelin (pas de cette session). */
    public static void purgeIfOrphan(Entity e) {
        if (KEY_MINION == null) return;
        if (e instanceof LivingEntity le && isMinion(le)) {
            if (!LIVE.contains(e.getUniqueId())) e.remove();
        } else if (e instanceof org.bukkit.entity.Display
                && fr.lolmc.util.MobAppearance.isDecoration(e)) {
            Entity v = e.getVehicle();
            if (v == null || (v instanceof LivingEntity vl && isMinion(vl) && !LIVE.contains(v.getUniqueId()))) {
                e.remove();
            }
        }
    }

    public static void purgeAllWorlds() {
        if (KEY_MINION == null) return;
        for (var world : org.bukkit.Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof LivingEntity le && isMinion(le)) {
                    e.remove();
                } else if (e instanceof org.bukkit.entity.Display
                        && fr.lolmc.util.MobAppearance.isDecoration(e)
                        && (e.getVehicle() == null
                            || (e.getVehicle() instanceof LivingEntity v && isMinion(v)))) {
                    e.remove();
                }
            }
        }
    }

    /**
     * Inhibiteur repoussé : l'équipe ADVERSE de son propriétaire perd ses super-sbires sur cette
     * lane. Clé « INHIBITOR_RED_top » (équipe propriétaire). Avant, la clé était mal découpée
     * (« INHIBITOR » lu comme équipe) et les super-sbires ne s'arrêtaient jamais.
     */
    public void onInhibitorRespawned(String key) {
        String[] parts = key.split("_", 3);
        if (parts.length != 3) return;
        try {
            Team owner = Team.valueOf(parts[1]);
            disableSuperMinions(owner.opposite(), parts[2]);
        } catch (IllegalArgumentException ignored) {}
    }


    /** Retourne le type de sbire ("melee", "caster", "cannon", "super") ou null si inconnu. */
    /**
     * Type du sbire : "melee", "caster", "cannon" ou "super". Avant, renvoyait la LANE
     * (top/mid/bot) : tous les sbires étaient traités comme des mêlées (or, XP, dégâts).
     */
    public static String getMinionTypeTag(org.bukkit.entity.LivingEntity e) {
        if (!isMinion(e)) return null;
        String t = KEY_TYPE != null
                ? e.getPersistentDataContainer().get(KEY_TYPE, org.bukkit.persistence.PersistentDataType.STRING) : null;
        return t != null ? t : "melee";
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
        pdc.set(KEY_LANE,   org.bukkit.persistence.PersistentDataType.STRING, "mid");
        applyMinionModel(e, type, team, type == MinionType.CASTER ? 0.7f : 1.0f);
        applyStats(e, type, team == Team.BLUE ? "🔵 Sbire" : "🔴 Sbire");
    }

    /**
     * Voidmite allié (Faim du Néant, wiki) : sbire de mêlée sans armure ni RM, 40 % de sa vitesse
     * d'attaque, 150 % de sa vitesse de déplacement, sur la voie la plus proche. Contre une tourelle
     * extérieure ou intérieure, il perd 100 PV toutes les 5 s.
     */
    public void spawnVoidmite(Location loc, Team team) {
        String lane = "mid"; double best = Double.MAX_VALUE;
        for (String l : new String[]{"top", "mid", "bot"}) {
            Location rp = closestRoadPoint(l, loc);
            if (rp == null || !rp.getWorld().equals(loc.getWorld())) continue;
            double d = rp.distanceSquared(loc);
            if (d < best) { best = d; lane = l; }
        }
        var e = loc.getWorld().spawn(loc, org.bukkit.entity.Zombie.class);
        var pdc = e.getPersistentDataContainer();
        pdc.set(KEY_MINION, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_TEAM, PersistentDataType.STRING, team.name());
        pdc.set(KEY_LANE, PersistentDataType.STRING, lane);
        applyMinionModel(e, MinionType.MELEE, team, 0.7f);
        applyStats(e, MinionType.MELEE, "🐛 Voidmite");
        fr.lolmc.util.MobCombat.setStats(e, fr.lolmc.util.MobCombat.getAD(e), fr.lolmc.util.MobCombat.getAttackSpeed(e) * 0.40,
                0, 0, 0, 0);
        var ms = e.getAttribute(fr.lolmc.util.Compat.movementSpeed());
        if (ms != null) ms.setBaseValue(MINION_SPEED * 1.5);
        LIVE.add(e.getUniqueId());
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (!e.isValid() || e.isDead()) { cancel(); return; }
                var s = findNearbyEnemyStructure(e, team);
                if (s != null && s.getType() == GameStructure.Type.TURRET && s.getIndex() <= 2)
                    fr.lolmc.util.VirtualHP.damage(e, 100, null);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 100L, 100L);
    }

    /** Force le spawn immédiat d'une vague (admin /lol wave). */
    public void forceWave() {
        waveCount++;
        spawnWave(Team.BLUE, waveCount);
        spawnWave(Team.RED, waveCount);
    }
}
