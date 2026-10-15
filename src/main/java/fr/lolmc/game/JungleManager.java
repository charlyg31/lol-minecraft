package fr.lolmc.game;
import fr.lolmc.util.Compat;
import fr.lolmc.util.MobAppearance;
import fr.lolmc.util.MobModel;
import fr.lolmc.util.MobAnimator;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

/**
 * Gère tous les monstres de jungle LoL :
 *  - Camps neutres : Loup (Murkwolf), Golem bleu (Blue Sentinel), Gromp, Raptors, Krugs
 *  - Buffs : Rouge (Red Brambleback), Bleu (Blue Sentinel)
 *  - Épiques : Dragon, Baron Nashor, Héraut
 *
 * Chaque type a un mob vanilla réskinné, des stats, un délai de respawn,
 * et des récompenses (or + XP). Positions configurées par commande et par équipe.
 */
public class JungleManager {
    private fr.lolmc.instance.GameInstance gameInstance = null;
    public void setGameInstance(fr.lolmc.instance.GameInstance inst) { this.gameInstance = inst; }
    public fr.lolmc.instance.GameInstance getGameInstance() { return gameInstance; }

    /** Annonce aux seuls joueurs de cette partie. */
    private void announce(Component message) {
        fr.lolmc.util.WorldContext.broadcast(gameInstance != null ? gameInstance.getWorld()
                : fr.lolmc.util.WorldContext.getGameWorld(), message);
    }

    /** Résout un manager-frère : celui de cette instance si elle existe, sinon le global. */
    private RewardManager siblingRewardManager() {
        return gameInstance != null ? gameInstance.getRewardManager() : LolPlugin.getInstance().getRewardManager();
    }
    private MapManager siblingMapManager() {
        return gameInstance != null ? gameInstance.getMapManager() : LolPlugin.getInstance().getMapManager();
    }
    private FeatManager siblingFeatManager() {
        return gameInstance != null ? gameInstance.getFeatManager() : LolPlugin.getInstance().getFeatManager();
    }

    private static final Map<Object, String> dragonSoulType = new HashMap<>();

    // ── Rotation des dragons (LoL) : 3 premiers élémentaires DIFFÉRENTS,
    //    le 3e fige l'élément de la Faille ; après une âme → Elder ──
    private final List<MonsterType> spawnedDragonElements = new ArrayList<>();
    /** Forme du Baron, tirée au hasard une fois par partie (wiki) : 0 Chasseur, 1 Territorial, 2 Omniscient. */
    private int baronForm = -1;
    private static final String[] BARON_FORMS = {"Chasseur", "Territorial", "Omniscient"};
    /** Terrain de la fosse propre à chaque forme (enregistré en jeu, jungle.yml). */
    private final BaronPitTerrain baronPit = new BaronPitTerrain();
    public static NamespacedKey KEY_BARON_FORM;
    private MonsterType lockedDragonElement = null;

    private static final MonsterType[] DRAGON_POOL = {
        MonsterType.DRAGON_INFERNAL, MonsterType.DRAGON_OCEAN,
        MonsterType.DRAGON_MOUNTAIN, MonsterType.DRAGON_CLOUD,
        MonsterType.DRAGON_CHEMTECH, MonsterType.DRAGON_HEXTECH // Hextech : 6e élément (manquait)
    };

    /** Prochain dragon à faire apparaître dans la fosse (règles LoL S15). */
    private MonsterType nextDragonType() {
        // Une équipe a l'âme → Dragon Ancien
        for (int n : dragonsKilled.values()) {
            if (n >= 4) return MonsterType.DRAGON_ELDER;
        }
        if (lockedDragonElement != null) return lockedDragonElement;
        // Tirer un élément pas encore apparu
        List<MonsterType> pool = new ArrayList<>();
        for (MonsterType t : DRAGON_POOL)
            if (!spawnedDragonElements.contains(t)) pool.add(t);
        MonsterType pick = pool.get(new Random().nextInt(pool.size()));
        spawnedDragonElements.add(pick);
        if (spawnedDragonElements.size() == 3) {
            lockedDragonElement = pick;
            announce(Component.text(
                "🐉 La Faille se transforme : élément " + pick.displayName + " !",
                NamedTextColor.LIGHT_PURPLE));
        }
        return pick;
    }

    public static NamespacedKey KEY_MONSTER; // type du monstre
    public static NamespacedKey KEY_BUFF;    // buff accordé (red/blue/none)
    public static NamespacedKey KEY_GOLD;    // or individuel du mob

    private final File jungleFile;
    private final FileConfiguration config;

    // Camps configurés : id unique → CampSpawn
    private final Map<String, CampSpawn> camps = new HashMap<>();
    // Monstres vivants : entityUUID → MonsterType
    private final Map<UUID, MonsterType> liveMonsters = new HashMap<>();
    // Voidgrubs tués par équipe → bonus dégâts aux structures
    private final Map<Team, Integer> voidgrubKills
        = new EnumMap<>(Team.class);
    // Chrono de respawn des épiques : type → timestamp de respawn
    private final Map<String, Long> epicRespawnAt = new java.util.concurrent.ConcurrentHashMap<>();
    // Décorations : monstreUUID → liste des UUID des parties du modèle (nettoyage)
    private final Map<UUID, List<UUID>> monsterDeco = new HashMap<>();
    // Dragons tués par équipe (pour l'âme du Dragon au 4e)
    private final Map<Team, Integer> dragonsKilled = new HashMap<>();
    private boolean active = false;

    // ══════════════════════════════════════════════════════════════
    // DÉFINITION DES MONSTRES
    // ══════════════════════════════════════════════════════════════

    public enum MonsterType {
        // ── Camps neutres (certains en groupes) ──
        // Format: entity, maxHP, gold, xp, respawnSec, buff, groupCount, displayName
        // (PV/or/XP réels : statsFor(), selon le niveau moyen des champions ; ici le niveau 1)
        GROMP        (EntityType.SLIME,        2050,  80, 120, 135, "none", 1, "🐸 Gromp"),
        MURKWOLF     (EntityType.WOLF,         1600,  55,  50, 135, "none", 3, "🐺 Loups"),       // 1 grand + 2 petits
        RAPTOR       (EntityType.VEX,          1200,  35,  20, 135, "none", 6, "🦅 Raptors"),     // 1 grand + 5 petits
        KRUG         (EntityType.SILVERFISH,   1350,  15,  15, 135, "none", 2, "🪨 Krugs"),       // Ancien + Krug (se divisent)
        // ── Buffs ──
        RED_BUFF     (EntityType.MAGMA_CUBE,   2300,  90, 95, 300, "red",  1, "🔴 Sanglepince"),
        BLUE_BUFF    (EntityType.IRON_GOLEM,   2300,  90, 95, 300, "blue", 1, "🔵 Sentinelle bleue"),
        // ── Crabe (rivière) ──
        SCUTTLE_CRAB (EntityType.TURTLE,       1550,  55, 100,  150,"none", 1, "🦀 Crabe Pillargot"),
        // ── Dragons élémentaires (un seul à la fois sur la carte dans LoL) ──
        DRAGON_INFERNAL (EntityType.RAVAGER,   5106,  75, 160, 300, "drake_infernal", 1, "🔥 Dragon Infernal"),
        DRAGON_OCEAN    (EntityType.RAVAGER,   5106,  75, 160, 300, "drake_ocean",    1, "🌊 Dragon Océan"),
        DRAGON_MOUNTAIN (EntityType.RAVAGER,   5106,  75, 160, 300, "drake_mountain", 1, "⛰ Dragon Montagne"),
        DRAGON_CLOUD    (EntityType.RAVAGER,   5106,  75, 160, 300, "drake_cloud",    1, "☁ Dragon Foudre"),
        DRAGON_CHEMTECH (EntityType.RAVAGER,   5106,  75, 160, 300, "drake_chemtech", 1, "☣ Dragon Chimtech"),
        DRAGON_HEXTECH  (EntityType.RAVAGER,   5106,  75, 160, 300, "drake_hextech",  1, "⚡ Dragon Hextech"),
        DRAGON_ELDER    (EntityType.RAVAGER,  17796, 150, 650, 360, "drake_elder",    1, "🐲 Dragon Ancien"),
        // ── Épiques de la fosse ──
        VOIDGRUB     (EntityType.SILVERFISH,   2269,  30, 65,  0, "voidgrub", 3, "🐛 Nuée du Néant"),
        HERALD       (EntityType.RAVAGER,     11718, 100, 240,  0,  "none", 1, "👁 Héraut de la Faille"),
        BARON        (EntityType.WARDEN,      17792, 150, 650, 360, "baron",1, "🪱 Baron Nashor");

        public final EntityType entity;
        public final double maxHP;
        public final int gold;
        public final double xp;
        public final int respawnSeconds;
        public final String buff;
        public final int groupCount;
        public final String displayName;

        MonsterType(EntityType entity, double maxHP, int gold, double xp,
                    int respawnSeconds, String buff, int groupCount, String displayName) {
            this.entity = entity; this.maxHP = maxHP; this.gold = gold;
            this.xp = xp; this.respawnSeconds = respawnSeconds;
            this.buff = buff; this.groupCount = groupCount; this.displayName = displayName;
        }

        public boolean isDragon() {
            return name().startsWith("DRAGON_");
        }

        public boolean isEpic() {
            return isDragon() || this == HERALD || this == BARON;
        }
    }

    /** Taille d'un monstre dans son camp (grand, Krug moyen, petit). */
    public enum Size { LARGE, MEDIUM, SMALL }

    /**
     * Statistiques LoL d'un monstre (wiki, patch 26.01) : chaque paire va du niveau de départ au
     * niveau 18 (niveau = moyenne des champions de la partie, interpolation linéaire, le wiki ne
     * donnant que les bornes).
     */
    private record MStats(double hp0, double hp1, double ad0, double ad1, double ar0, double ar1,
                          double mr0, double mr1, double gold0, double gold1, double xp0, double xp1,
                          int startLevel) {}

    private static MStats flat(double hp0, double hp1, double ad0, double ad1, double res,
                               double gold, double xp0, double xp1) {
        return new MStats(hp0, hp1, ad0, ad1, res, res, res, res, gold, gold, xp0, xp1, 1);
    }

    private static MStats statsFor(MonsterType t, Size size) {
        return switch (t) {
            case GROMP -> flat(2050, 4817.5, 70, 210, 42, 80, 120, 180);
            case MURKWOLF -> size == Size.LARGE ? flat(1600, 3760, 30, 90, 42, 55, 50, 75)
                                                : flat(630, 1480.5, 10, 30, 20, 15, 15, 22.5);
            case RAPTOR -> size == Size.LARGE ? flat(1200, 2820, 17, 51, 42, 35, 20, 30)
                                              : flat(500, 1175, 7, 21, 20, 8, 10, 15);
            case KRUG -> switch (size) {
                case LARGE  -> flat(1350, 3172.5, 57, 171, 42, 15, 15, 22.5); // Ancien Krug
                case MEDIUM -> flat(650, 1527.5, 20, 60, 20, 10, 10, 15);     // Krug
                case SMALL  -> flat(60, 141, 13, 39, 20, 14, 16, 24);         // Mini Krug
            };
            case RED_BUFF, BLUE_BUFF -> flat(2300, 6210, 66, 198, 42, 90, 95, 142.5);
            case SCUTTLE_CRAB -> new MStats(1550, 5270, 0, 0, 42, 42, 42, 42, 55, 121, 100, 150, 1);
            // Dragons élémentaires : niveau de départ 6 (wiki ; avant : 1). AD propre à chaque élément,
            // Montagne +15 % de PV (données du jeu)
            case DRAGON_INFERNAL, DRAGON_OCEAN -> drake(70, 1.0);
            case DRAGON_MOUNTAIN -> drake(105, 1.15);
            case DRAGON_CLOUD -> drake(35, 1.0);
            case DRAGON_CHEMTECH, DRAGON_HEXTECH -> drake(47, 1.0);
            case DRAGON_ELDER -> new MStats(17796.25, 21275, 105, 105, 121.6, 170, 75.8, 100, 150, 150, 650, 650, 13);
            case VOIDGRUB -> new MStats(2269, 4700, 21.69, 46, 72.76, 170, 51.38, 100, 30, 30, 65, 65, 7);
            // Niveaux de départ 9 et 11 (wiki ; avant : 1, donc des PV et des dégâts trop faibles)
            case HERALD -> new MStats(11718, 18900, 99.5, 250, 87.92, 170, 58.96, 100, 100, 100, 240, 240, 9);
            case BARON -> new MStats(17791.75, 19190, 350.5, 515, 104.2, 170, 67.1, 100, 150, 150, 650, 650, 11);
        };
    }

    private static MStats drake(double ad, double hpMult) {
        return new MStats(5106.25 * hpMult, 10000 * hpMult, ad, ad, 65.6, 170, 47.8, 100, 75, 75, 160, 400, 6);
    }

    /** Vitesse d'attaque LoL du monstre (wiki). */
    private static double attackSpeedFor(MonsterType t, Size size) {
        return switch (t) {
            case GROMP -> 0.425;
            case MURKWOLF -> 0.625;
            case RAPTOR -> size == Size.LARGE ? 0.667 : 1.0;
            case KRUG -> 0.613;
            case RED_BUFF, BLUE_BUFF -> 0.493;
            case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_CHEMTECH, DRAGON_ELDER -> 0.5;
            case DRAGON_MOUNTAIN -> 0.25;
            case DRAGON_CLOUD -> 1.0;
            case DRAGON_HEXTECH -> 0.75;
            case VOIDGRUB -> 0.5;
            case HERALD -> 0.4;
            case BARON -> 0.625;
            case SCUTTLE_CRAB -> 1.0;
        };
    }

    /** Fin du buff du Dragon Ancien (il est perdu à la mort, LoL). */
    public static void clearElderBuff(UUID player) { elderUntil.remove(player); }

    /** Niveau d'un camp (wiki) : moyenne des niveaux des champions, arrondie à l'entier le plus proche. */
    private double averageChampionLevel() {
        var cm = LolPlugin.getInstance().getChampionManager();
        double sum = 0; int n = 0;
        for (Player p : gameInstance != null ? gameInstance.getOnlinePlayers()
                                             : fr.lolmc.util.WorldContext.getGamePlayers()) {
            if (!cm.hasChampion(p)) continue;
            sum += cm.getChampion(p).getLevelSystem().getLevel();
            n++;
        }
        return n > 0 ? Math.round(sum / n) : 1;
    }

    /**
     * Applique les stats LoL à un monstre : PV virtuels, AD, armure, RM, or et XP (lus à sa mort).
     * {@code levelOffset} : -1 pour les mini-krugs (un niveau sous le monstre qui se divise).
     */
    private void applyMonsterStats(LivingEntity mob, MonsterType type, Size size, int levelOffset, boolean firstScuttle) {
        MStats st = statsFor(type, size);
        double lvl = Math.max(1, averageChampionLevel() + levelOffset);
        double f = Math.max(0, Math.min(1, (lvl - st.startLevel()) / (18.0 - st.startLevel())));
        double hp   = st.hp0()   + (st.hp1()   - st.hp0())   * f;
        double ad   = st.ad0()   + (st.ad1()   - st.ad0())   * f;
        double ar   = st.ar0()   + (st.ar1()   - st.ar0())   * f;
        double mr   = st.mr0()   + (st.mr1()   - st.mr0())   * f;
        double gold = st.gold0() + (st.gold1() - st.gold0()) * f;
        double xp   = st.xp0()   + (st.xp1()   - st.xp0())   * f;
        if (firstScuttle) { hp *= 0.65; xp *= 0.20; } // premier crabe : -35 % PV, -80 % XP (wiki)
        fr.lolmc.util.VirtualHP.init(mob, hp);
        fr.lolmc.util.MobCombat.setStats(mob, ad, attackSpeedFor(type, size), ar, mr, (int) Math.round(gold), xp);
        mob.getPersistentDataContainer().set(KEY_GOLD, PersistentDataType.INTEGER, (int) Math.round(gold));
    }

    /** Un emplacement de camp configuré. */
    public static class CampSpawn {
        public final String id;
        public MonsterType type; // mutable : la fosse du dragon change d'élément (rotation LoL)
        public final Location location;
        public final Set<UUID> liveEntities = new HashSet<>();  // toutes les entités du camp
        public long respawnAt = 0;        // timestamp de réapparition
        public UUID respawnHologramId = null; // TextDisplay du compte à rebours
        public int spawnCount = 0;            // nombre d'apparitions (premier crabe affaibli)

        public CampSpawn(String id, MonsterType type, Location location) {
            this.id = id; this.type = type; this.location = location;
        }

        public boolean isAlive() { return !liveEntities.isEmpty(); }
    }

    private org.bukkit.World scopedWorld = null;

    public JungleManager() {
        KEY_MONSTER = new NamespacedKey(LolPlugin.getInstance(), "jungle_monster");
        KEY_BUFF = new NamespacedKey(LolPlugin.getInstance(), "jungle_buff");
        KEY_GOLD = new NamespacedKey(LolPlugin.getInstance(), "jungle_gold");
        KEY_BARON_FORM = new NamespacedKey(LolPlugin.getInstance(), "baron_form");
        this.jungleFile = new File(LolPlugin.getInstance().getDataFolder(), "jungle.yml");
        if (!jungleFile.exists()) {
            try {
                if (!jungleFile.createNewFile()) {
                    LolPlugin.getInstance().getLogger().warning("Impossible de créer jungle.yml.");
                }
            } catch (Exception e) {
                LolPlugin.getInstance().getLogger().warning("Échec de création de jungle.yml : " + e.getMessage());
            }
        }
        this.config = YamlConfiguration.loadConfiguration(jungleFile);
        load();
        baronPit.load(config);
        startRespawnTask();
    }

    // ══════════════════════════════════════════════════════════════
    // CONFIGURATION (commandes)
    // ══════════════════════════════════════════════════════════════

    /**
     * Définit l'emplacement d'un camp pour une équipe (ou neutre).
     * @param type   type de monstre
     * @param team   équipe propriétaire de la zone (BLUE/RED), ou null si épique neutre
     * @param location position de spawn
     */
    public void setCamp(MonsterType type, Team team, Location location) {
        setCamp(type, team, 0, location);
    }

    /** {@code river} : crabe de la rivière du haut (1) ou du bas (2) ; 0 pour les autres camps. */
    public void setCamp(MonsterType type, Team team, int river, Location location) {
        String id = campId(type, team);
        // Deux crabes dans LoL (avant : un seul emplacement, le second écrasait le premier)
        if (type == MonsterType.SCUTTLE_CRAB && river > 0) {
            id += river == 1 ? "_top" : "_bot";
            if (config.contains("camps." + campId(type, team))) { // ancien emplacement unique
                config.set("camps." + campId(type, team), null);
                camps.remove(campId(type, team));
            }
        }
        camps.put(id, new CampSpawn(id, type, location.clone()));
        // Persister
        String path = "camps." + id + ".";
        config.set(path + "type", type.name());
        config.set(path + "world", location.getWorld().getName());
        config.set(path + "x", location.getX());
        config.set(path + "y", location.getY());
        config.set(path + "z", location.getZ());
        save();
    }

    private String campId(MonsterType type, Team team) {
        // Les épiques (dragons/Baron/Héraut), les larves et le crabe sont neutres → pas d'équipe
        if (type.isEpic() || type == MonsterType.SCUTTLE_CRAB || type == MonsterType.VOIDGRUB) {
            return type.name().toLowerCase();
        }
        return type.name().toLowerCase() + "_" + (team != null ? team.name().toLowerCase() : "neutral");
    }

    /** Constructeur instance : scoped à un World précis. */
    public JungleManager(org.bukkit.World world) {
        this();
        this.scopedWorld = world;
        if (world != null) {
            // Recharge les camps directement dans le monde de l'instance (indépendant du monde enregistré)
            camps.clear();
            load();
        }
    }

    /** Réécrit le monde de tous les camps dans jungle.yml puis recharge (sauvegarde d'un nouveau template). */
    public void rebindWorld(String worldName) {
        var sec = config.getConfigurationSection("camps");
        if (sec != null) for (String id : sec.getKeys(false)) config.set("camps." + id + ".world", worldName);
        save();
        camps.clear();
        load();
    }

    // ══════════════════════════════════════════════════════════════
    // SPAWN / RESPAWN
    // ══════════════════════════════════════════════════════════════

    /** Lance la jungle : fait apparaître tous les camps. */
    // Tâches de premier spawn programmé (annulées au stop)
    private final List<org.bukkit.scheduler.BukkitTask> initialSpawnTasks
        = new ArrayList<>();

    /** Délai de PREMIER spawn en secondes (wiki LoL, patch 26.01). */
    private static int initialSpawnDelay(MonsterType type) {
        return switch (type) {
            case SCUTTLE_CRAB -> 175;   // 2:55
            case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_MOUNTAIN,
                 DRAGON_CLOUD, DRAGON_CHEMTECH, DRAGON_HEXTECH, DRAGON_ELDER -> 300; // 5:00
            case VOIDGRUB     -> 480;   // 8:00
            case HERALD       -> 900;   // 15:00
            case BARON        -> 1200;  // 20:00
            case GROMP, KRUG  -> 67;    // 1:07
            default           -> 55;    // loups, raptors, buffs : 0:55
        };
    }

    public void startJungle() {
        active = true;
        baronPit.restore(baronPitWorld()); // partie précédente interrompue avant la fin
        for (CampSpawn camp : camps.values()) {
            int delay = initialSpawnDelay(camp.type);
            if (delay <= 0) { spawnCamp(camp); continue; }
            // Chrono visible dans la BossBar pour les épiques
            if (camp.type.isEpic())
                epicRespawnAt.put(camp.type.name(), System.currentTimeMillis() + delay * 1000L);
            final CampSpawn fc = camp;
            var task = new BukkitRunnable() {
                @Override public void run() {
                    if (!active) return;
                    epicRespawnAt.remove(fc.type.name());
                    spawnCamp(fc);
                }
            }.runTaskLater(LolPlugin.getInstance(), delay * 20L);
            initialSpawnTasks.add(task);
        }
    }

    public void stopJungle() {
        active = false;
        for (var t : initialSpawnTasks) { if (t != null && !t.isCancelled()) t.cancel(); }
        initialSpawnTasks.clear();
        epicRespawnAt.clear();
        spawnedDragonElements.clear();
        lockedDragonElement = null;
        baronForm = -1;
        baronPit.restore(baronPitWorld());
        dragonsKilled.clear();
        dragonSoulType.clear();
        clearAllMonsters();
        hideAllRespawnTimers();
    }

    private void spawnCamp(CampSpawn camp) {
        // Fosse du dragon : l'élément suit la rotation LoL
        if (camp.type.isDragon()) camp.type = nextDragonType();
        MonsterType type = camp.type;
        Location loc = camp.location;
        if (loc.getWorld() == null) return;

        camp.liveEntities.clear();
        boolean firstScuttle = type == MonsterType.SCUTTLE_CRAB && camp.spawnCount == 0;
        camp.spawnCount++;

        // Spawn de tous les mobs du groupe (groupCount)
        for (int i = 0; i < type.groupCount; i++) {
            // Décaler légèrement les mobs du groupe autour du centre
            double angle = 2 * Math.PI * i / type.groupCount;
            double radius = type.groupCount > 1 ? 1.5 : 0;
            Location spawnLoc = loc.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);

            Entity entity = spawnLoc.getWorld().spawnEntity(spawnLoc, type.entity);
            if (!(entity instanceof LivingEntity mob)) { entity.remove(); continue; }

            // Le premier mob du groupe est le grand ; les autres sont petits (le 2e Krug est moyen)
            boolean isLarge = (i == 0);
            Size size = isLarge ? Size.LARGE : (type == MonsterType.KRUG ? Size.MEDIUM : Size.SMALL);
            // Stats LoL (avant : petits = 35 % des PV du grand, et l'or/XP du grand à leur mort)
            applyMonsterStats(mob, type, size, 0, firstScuttle);

            String name = type.groupCount > 1
                    ? type.displayName + (isLarge ? " (Grand)" : " (Petit)")
                    : type.displayName;
            mob.customName(Component.text(name));
            mob.setCustomNameVisible(true);
            if (isLarge && type.groupCount > 1)
                mob.getPersistentDataContainer().set(new NamespacedKey(LolPlugin.getInstance(), "camp_large"),
                    PersistentDataType.BYTE, (byte) 1);
            if (type == MonsterType.KRUG)
                mob.getPersistentDataContainer().set(
                    new NamespacedKey(LolPlugin.getInstance(), isLarge ? "krug_large" : "krug_medium"),
                    PersistentDataType.BYTE, (byte) 1);
            mob.setRemoveWhenFarAway(false);
            // Monstre passif : ne poursuit pas, reste à son camp (réveillé quand attaqué)
            if (mob instanceof Mob m) {
                m.setAware(false);  // ne se déplace pas tout seul
                m.setTarget(null);
            }
            // Mémoriser la position du camp pour le reset
            mob.getPersistentDataContainer().set(
                new NamespacedKey(LolPlugin.getInstance(), "camp_x"),
                PersistentDataType.DOUBLE, mob.getLocation().getX());
            mob.getPersistentDataContainer().set(
                new NamespacedKey(LolPlugin.getInstance(), "camp_z"),
                PersistentDataType.DOUBLE, mob.getLocation().getZ());

            // Les épiques mobiles (dragons/baron) peuvent bouger, le reste est statique
            var speedAttr = mob.getAttribute(Compat.movementSpeed());
            if (speedAttr != null && !type.isEpic()) {
                speedAttr.setBaseValue(0.15);
            }

            mob.getPersistentDataContainer().set(KEY_MONSTER, PersistentDataType.STRING, type.name());
            if (type == MonsterType.BARON) {
                if (baronForm < 0) {
                    baronForm = new Random().nextInt(3);
                    announce(Component.text("🪱 Le Baron Nashor prend sa forme « " + BARON_FORMS[baronForm] + " »",
                            NamedTextColor.DARK_PURPLE));
                    baronPit.apply(BaronPitTerrain.formKey(baronForm), loc.getWorld());
                }
                mob.getPersistentDataContainer().set(KEY_BARON_FORM, PersistentDataType.INTEGER, baronForm);
            }
            mob.getPersistentDataContainer().set(KEY_BUFF, PersistentDataType.STRING, type.buff);

            // ── APPARENCE CUSTOM : modèle composite + mob de base invisible ──
            // Le mob vanilla devient invisible : on ne voit que le modèle (technique datapack).
            MobAppearance.makeInvisible(mob);
            MobAppearance.setSilent(mob, true);
            MobModel model = modelFor(type, isLarge);
            if (model != null) {
                monsterDeco.put(mob.getUniqueId(), model.spawnOn(mob));
            }
            // Faire correspondre approximativement la hitbox a la taille du modele.
            // (Minecraft : hitbox = boite unique. On ajuste la taille globale, pas la forme.)
            float hbScale = hitboxScaleFor(type);
            if (!isLarge) hbScale *= 0.6f; // petits monstres de groupe
            var scaleAttr = mob.getAttribute(Compat.scale());
            if (scaleAttr != null) scaleAttr.setBaseValue(hbScale);

            camp.liveEntities.add(mob.getUniqueId());
            liveMonsters.put(mob.getUniqueId(), type);
        }
    }

    /** Mini-krug issu de la division d'un Krug (un niveau sous lui, wiki). */
    private LivingEntity spawnKrugMini(CampSpawn camp, Location loc) {
        MonsterType type = MonsterType.KRUG;
        Entity entity = loc.getWorld().spawnEntity(loc, type.entity);
        if (!(entity instanceof LivingEntity mob)) { entity.remove(); return null; }
        mob.customName(Component.text(type.displayName + " (Mini)"));
        mob.setCustomNameVisible(true);
        mob.setRemoveWhenFarAway(false);
        if (mob instanceof Mob mo) { mo.setAware(false); mo.setTarget(null); }
        var pdc = mob.getPersistentDataContainer();
        pdc.set(KEY_MONSTER, PersistentDataType.STRING, type.name());
        pdc.set(KEY_BUFF, PersistentDataType.STRING, type.buff);
        applyMonsterStats(mob, type, Size.SMALL, -1, false);
        var speedAttr = mob.getAttribute(Compat.movementSpeed());
        if (speedAttr != null) speedAttr.setBaseValue(0.15);
        MobAppearance.makeInvisible(mob);
        MobAppearance.setSilent(mob, true);
        MobModel model = modelFor(type, false);
        if (model != null) monsterDeco.put(mob.getUniqueId(), model.spawnOn(mob));
        camp.liveEntities.add(mob.getUniqueId());
        liveMonsters.put(mob.getUniqueId(), type);
        fr.lolmc.util.VisualEffectUtil.impactBurst(loc.getWorld(),
            loc.clone().add(0, 0.5, 0), Material.STONE, 0.25f, 0.3, 5, 6L);
        return mob;
    }

    /**
     * Shrine du Crabe (LoL) : à sa mort, zone de vision (ward 60s pour
     * l'équipe du tueur) + boost de vitesse aux alliés qui la traversent.
     */
    private void spawnScuttleShrine(Player killer, Location loc) {
        var wm = LolPlugin.getInstance().getWardManager();
        if (wm != null) wm.placeWard(killer, loc.clone().add(0, 0.5, 0), 90); // 90 s (wiki ; avant : 60)
        var team = LolPlugin.getInstance().getTeamManager().getTeam(killer);
        var shrineDisplays = new ArrayList<BlockDisplay>(3);
        for (int i = 0; i < 3; i++) {
            shrineDisplays.add(loc.getWorld().spawn(loc, BlockDisplay.class, disp -> {
                disp.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
                disp.setBrightness(new Display.Brightness(15, 15));
                disp.setPersistent(false);
                disp.setInterpolationDuration(3);
                disp.setInterpolationDelay(0);
                float size = 0.2f;
                disp.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(-size / 2f, 0f, -size / 2f),
                        new org.joml.Quaternionf(),
                        new org.joml.Vector3f(size, size, size),
                        new org.joml.Quaternionf()));
            }));
        }
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 90 * 20) {
                    for (var d : shrineDisplays) if (!d.isDead()) d.remove();
                    cancel(); return;
                }
                // Anneau aqua discret
                double a = ticks * 0.2;
                for (int i = 0; i < 3; i++) {
                    double ang = a + 2 * Math.PI * i / 3;
                    shrineDisplays.get(i).teleport(loc.clone().add(
                            Math.cos(ang) * 2.5, 0.2, Math.sin(ang) * 2.5));
                }
                // Boost MS aux alliés dans la zone
                if (ticks % 20 == 0 && team != null) {
                    for (Player p : loc.getWorld().getPlayers()) {
                        if (LolPlugin.getInstance().getTeamManager().getTeam(p) != team) continue;
                        if (p.getLocation().distanceSquared(loc) <= 2.5 * 2.5)
                            LolPlugin.getInstance().getCCManager().speedPct(p, 0.30, 30); // +30 % 1.5 s (wiki)
                    }
                }
                ticks += 4;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 4L);
    }

    /** Id du buff d'équipe d'un épique. */
    private String epicBuffId(MonsterType type) {
        return type.buff;
    }

    /** Appelé quand un monstre meurt (depuis le listener). */
    public void onMonsterDeath(UUID entityId, Player killer) {
        MonsterType type = liveMonsters.remove(entityId);
        if (type == null) return;

        // Retirer toutes les parties du modèle custom de ce monstre
        List<UUID> partIds = monsterDeco.remove(entityId);
        if (partIds != null) {
            for (UUID pid : partIds) {
                Entity d = Bukkit.getEntity(pid);
                if (d != null) d.remove();
            }
        }
        MobAnimator.unregister(entityId);

        // Retirer ce mob du camp ; respawn seulement quand TOUT le groupe est mort
        CampSpawn camp = null;
        for (CampSpawn c : camps.values()) {
            if (c.liveEntities.remove(entityId)) { camp = c; break; }
        }

        Entity deadEnt = LolPlugin.getInstance().getServer().getEntity(entityId);
        Location deathLoc = deadEnt != null ? deadEnt.getLocation()
                : (camp != null ? camp.location : null);

        // ── Krugs : l'Ancien se divise en 4 mini-krugs, le Krug en 2 (wiki) ──
        if (type == MonsterType.KRUG && camp != null && deathLoc != null && deadEnt != null) {
            var kpdc = deadEnt.getPersistentDataContainer();
            int minis = kpdc.has(new NamespacedKey(LolPlugin.getInstance(), "krug_large"), PersistentDataType.BYTE) ? 4
                      : kpdc.has(new NamespacedKey(LolPlugin.getInstance(), "krug_medium"), PersistentDataType.BYTE) ? 2 : 0;
            for (int i = 0; i < minis; i++) {
                double a = 2 * Math.PI * i / minis;
                LivingEntity mini = spawnKrugMini(camp, deathLoc.clone().add(Math.cos(a) * 0.8, 0, Math.sin(a) * 0.8));
                // Les mini-krugs naissent en combat (avant : ils attendaient d'être frappés)
                if (mini != null && killer != null) MonsterAbilities.onDamaged(mini, killer);
            }
        }

        // Loups et raptors : les petits monstres meurent 10 s après leur grand monstre (wiki)
        if ((type == MonsterType.MURKWOLF || type == MonsterType.RAPTOR) && camp != null && deadEnt != null
                && deadEnt.getPersistentDataContainer().has(new NamespacedKey(LolPlugin.getInstance(), "camp_large"),
                        PersistentDataType.BYTE)
                && !camp.liveEntities.isEmpty()) {
            final Set<UUID> marked = new HashSet<>(camp.liveEntities);
            final CampSpawn fc = camp;
            new BukkitRunnable() {
                @Override public void run() {
                    for (UUID id : marked) {
                        if (!fc.liveEntities.contains(id)) continue;
                        if (Bukkit.getEntity(id) instanceof LivingEntity le && !le.isDead())
                            fr.lolmc.util.VirtualHP.kill(le, null);
                    }
                }
            }.runTaskLater(LolPlugin.getInstance(), 200L);
        }

        // ── Scuttle : shrine de vision + vitesse 60s pour l'équipe du tueur ──
        if (type == MonsterType.SCUTTLE_CRAB && killer != null && deathLoc != null) {
            spawnScuttleShrine(killer, deathLoc);
        }

        if (camp != null && camp.liveEntities.isEmpty()) {
            // Tout le camp est nettoyé → programmer le respawn. Larves et Héraut n'apparaissent
            // qu'une fois (avant : délai de 0 s, ils réapparaissaient aussitôt, à l'infini)
            camp.respawnAt = type.respawnSeconds > 0 ? System.currentTimeMillis() + type.respawnSeconds * 1000L : 0;
        }

        // Récompenses (or individuel du mob, lu depuis le PDC si dispo)
        if (killer != null) {
            // Or/XP propres à ce monstre (taille, niveau) ; avant : ceux du grand pour chaque petit
            LivingEntity deadMob = deadEnt instanceof LivingEntity dl ? dl : null;
            int gold = deadMob != null ? fr.lolmc.util.MobCombat.getGold(deadMob, type.gold) : type.gold;
            double xp = deadMob != null ? fr.lolmc.util.MobCombat.getXP(deadMob, type.xp) : type.xp;
            var rewards = siblingRewardManager();
            switch (type) {
                // Dragons : or au tueur, XP partagée entre les alliés proches
                case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_MOUNTAIN, DRAGON_CLOUD, DRAGON_CHEMTECH, DRAGON_HEXTECH ->
                        rewards.onEpicKill(killer, deathLoc, gold, 0, xp, 0);
                // Ancien : 150 or et 650 XP à toute l'équipe, +100 or au tueur (données du jeu)
                case DRAGON_ELDER -> rewards.onEpicKill(killer, deathLoc, 100, gold, 0, xp);
                // Baron : 150 or et 650 XP à toute l'équipe, +100 or au tueur
                case BARON -> rewards.onEpicKill(killer, deathLoc, 100, gold, 0, xp);
                // Héraut : or au tueur, XP partagée entre les proches
                case HERALD -> rewards.onEpicKill(killer, deathLoc, gold, 0, xp, 0);
                default -> rewards.onJungleMonsterKill(killer, gold, xp);
            }
            // Passifs items de jungle (Gustwalker, Mosstomper, Scorchclaw)
            var pm = gameInstance != null ? gameInstance.getPassiveManager() : LolPlugin.getInstance().getPassiveManager();
            if (pm != null) pm.onJungleKill(killer, deadMob);

            // Buffs d'objectifs : les épiques buffent TOUTE l'équipe (Dragon/Baron),
            // les camps rouge/bleu restent des buffs personnels.
            if (!type.buff.equals("none")) {
                if (type.isEpic()) {
                    // Héraut : donner l'Œil du Héraut au tueur
                    if (type == MonsterType.HERALD) {
                        dropHeraldEye(killer, deathLoc != null ? deathLoc : killer.getLocation());
                    }
                    applyTeamBuff(killer, epicBuffId(type));
                    // Bounty d'objectif (comeback) sur les épiques
                    if (type.isEpic()) {
                        var ktm = LolPlugin.getInstance().getTeamManager().getTeam(killer);
                        if (ktm != null) {
                            int objBounty = siblingRewardManager()
                                .getObjectiveBounty(ktm);
                            if (objBounty > 0) {
                                LolPlugin.getInstance().getGoldManager()
                                    .addGold(killer.getUniqueId(), objBounty);
                                killer.sendActionBar(Component.text(
                                    "💰 Bounty d'objectif +" + objBounty + " or (comeback)!",
                                    NamedTextColor.GOLD));
                            }
                        }
                    }
                    // Dragons élémentaires seulement : l'Ancien ne compte pas pour l'âme (avant : le
                    // tuer redonnait l'âme et refaisait l'annonce)
                    if (type.isDragon() && type != MonsterType.DRAGON_ELDER) {
                        var dtm = LolPlugin.getInstance().getTeamManager();
                        var dteam = dtm.getTeam(killer);
                        if (dteam != null) {
                            // Enregistrer le type de dragon si c'est le premier
                            dragonSoulType.putIfAbsent(dteam, type.buff.replace("drake_", ""));
                            int n = dragonsKilled.merge(dteam, 1, Integer::sum);
                            if (n == 4) {
                                String soul = lockedDragonElement != null
                                    ? lockedDragonElement.buff.replace("drake_", "")
                                    : dragonSoulType.getOrDefault(dteam, "infernal");
                                applyTeamBuff(killer, "dragon_soul_" + soul);
                                announce(Component.text(
                                        "🐉 L'équipe de " + killer.getName() + " a obtenu l'ÂME DU DRAGON!",
                                        NamedTextColor.LIGHT_PURPLE));
                            }
                        }
                    }
                } else {
                    applyBuff(killer, type.buff);
                }
            }

            // Annonce pour les épiques (uniquement quand le camp est vidé)
            // Voidgrubs : compter les kills et appliquer bonus
            if (type == MonsterType.VOIDGRUB) {
                var kteam = LolPlugin.getInstance().getTeamManager().getTeam(killer);
                if (kteam != null) {
                    int grubs = voidgrubKills.merge(kteam, 1, Integer::sum);
                    for (UUID mid : LolPlugin.getInstance().getTeamManager().getTeamMembers(kteam, killer.getWorld())) {
                        Player mp = Bukkit.getPlayer(mid);
                        if (mp != null) mp.sendMessage(Component.text(grubs >= 3
                                ? "🐛 Faim du Néant : tes attaques sur les structures invoquent des Voidmites"
                                : "🐛 Toucher du Néant (" + grubs + ") : tes attaques brûlent les structures",
                                NamedTextColor.DARK_PURPLE));
                    }
                }
            }

            if (type.isEpic() && (camp == null || camp.liveEntities.isEmpty())) {
                announce(Component.text(
                        "🌟 " + type.displayName + " tué par " + killer.getName() + "!",
                        NamedTextColor.GOLD));
                // Feat of Strength : Premier Objectif Épique (Dragon ou Héraut)
                if (type.isDragon() || type == MonsterType.HERALD) {
                    var kt = LolPlugin.getInstance().getTeamManager().getTeam(killer);
                    if (kt != null)
                        siblingFeatManager().claim(
                            FeatManager.Feat.FIRST_EPIC, kt, killer);
                }
            }
        }
    }

    private org.bukkit.scheduler.BukkitTask respawnTask;

    /** Libère la tâche de réapparition des camps (fin d'une instance : le manager est jeté). */
    public void dispose() {
        stopJungle();
        if (respawnTask != null) { respawnTask.cancel(); respawnTask = null; }
    }

    private void startRespawnTask() {
        if (respawnTask != null) return;
        respawnTask = new BukkitRunnable() {
            @Override public void run() {
                if (!active) return;
                long now = System.currentTimeMillis();
                despawnExpired();
                for (CampSpawn camp : camps.values()) {
                    if (!camp.isAlive() && camp.respawnAt > 0) {
                        long secLeft = Math.max(0, (camp.respawnAt - now) / 1000L);
                        if (camp.location.isChunkLoaded()) {
                            updateRespawnHologram(camp, secLeft);
                        }
                    }
                    if (!camp.isAlive() && camp.respawnAt > 0 && now >= camp.respawnAt) {
                        removeRespawnHologram(camp);
                        spawnCamp(camp);
                        camp.respawnAt = 0;
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }

    /** Larves du Néant à 14:45 et Héraut à 19:45 (wiki) : retirés sans récompense, ne reviennent pas. */
    private void despawnExpired() {
        var gm = gameInstance != null ? gameInstance.getGameManager() : LolPlugin.getInstance().getGameManager();
        if (gm == null || !gm.isGameRunning()) return;
        long t = gm.getElapsedSeconds();
        for (CampSpawn camp : camps.values()) {
            long limit = camp.type == MonsterType.VOIDGRUB ? 885 : camp.type == MonsterType.HERALD ? 1185 : -1;
            if (limit < 0 || t < limit || camp.liveEntities.isEmpty()) continue;
            for (UUID id : new ArrayList<>(camp.liveEntities)) {
                liveMonsters.remove(id);
                List<UUID> parts = monsterDeco.remove(id);
                if (parts != null) for (UUID pid : parts) { Entity d = Bukkit.getEntity(pid); if (d != null) d.remove(); }
                MobAnimator.unregister(id);
                Entity e = Bukkit.getEntity(id);
                if (e != null) e.remove();
            }
            camp.liveEntities.clear();
            camp.respawnAt = 0;
            announce(Component.text("⌛ " + camp.type.displayName + " a quitté la fosse.", NamedTextColor.GRAY));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // BUFFS (Rouge / Bleu / Baron)
    // ══════════════════════════════════════════════════════════════

    // Tueur de l'objectif en cours d'application (pour les buffs qui le distinguent)
    private Player currentObjectiveKiller = null;

    /** Applique un buff à TOUTE l'équipe du tueur (objectifs : Dragon, Baron, âme...). */
    private void applyTeamBuff(Player killer, String buff) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(killer);
        currentObjectiveKiller = killer;
        if (team == null) { applyBuff(killer, buff); currentObjectiveKiller = null; return; }
        for (UUID id : tm.getTeamMembers(team, killer.getWorld())) {
            Player m = LolPlugin.getInstance().getServer().getPlayer(id);
            if (m != null && m.isOnline()) applyBuff(m, buff);
        }
        currentObjectiveKiller = null;
    }

    /** Fin du buff du Dragon Ancestral par joueur (exécution sous 20 % PV). */
    private static final Map<UUID, Long> elderUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /** Vrai si {@code player} a le buff du Dragon Ancestral actif. */
    public static boolean hasElderBuff(Player player) {
        Long until = elderUntil.get(player.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) { elderUntil.remove(player.getUniqueId()); return false; }
        return true;
    }

    private void applyBuff(Player player, String buff) {
        if (buff == null || buff.equals("none")) return;
        if (!LolPlugin.getInstance().getChampionManager().hasChampion(player)) return;
        BaseChampion champ = LolPlugin.getInstance().getChampionManager().getChampion(player);

        switch (buff) {
            case "red" -> JungleBuffs.grantRed(player);    // 120 s, brûlure + ralentissement (wiki)
            case "blue" -> JungleBuffs.grantBlue(player);  // 120 s, 10-20 accélération + mana (wiki)
            // Pourfendeur de dragons : un effet par drake (avant : bonus fixes inventés)
            case "drake_infernal", "drake_ocean", "drake_mountain", "drake_cloud", "drake_chemtech", "drake_hextech" -> {
                String el = buff.substring("drake_".length());
                JungleBuffs.addDrakeStack(player, el);
                player.sendActionBar(Component.text("🐉 Pourfendeur de dragons : " + el, NamedTextColor.LIGHT_PURPLE));
            }
            case "drake_elder" -> {
                // Aspect du dragon : 150 s, brûlure et exécution sous 20 % (DamageUtil.elderEffects).
                // Avant : +20 AD +30 AP et vitesse, absents du jeu
                elderUntil.put(player.getUniqueId(), System.currentTimeMillis() + 150_000L);
                player.sendActionBar(Component.text("🐲 Aspect du dragon (2 min 30) : brûlure et exécution sous 20 %",
                        NamedTextColor.LIGHT_PURPLE));
            }
            case "baron" -> {
                // Main du Baron : 180 s, 12-48 AD / 20-80 AP selon la minute, rappel accéléré, perdue
                // à la mort (avant : +24 AD +40 AP fixes, potion de vitesse, gardée en mourant)
                JungleBuffs.grantBaron(player);
                // Marqueur violet autour du joueur pendant 180s (visible par tous, comme en LoL)
                var baronDisplay = player.getWorld().spawn(player.getLocation().add(0,1,0),
                        BlockDisplay.class, disp -> {
                    disp.setBlock(Material.PURPLE_STAINED_GLASS.createBlockData());
                    disp.setBrightness(new Display.Brightness(15, 15));
                    disp.setPersistent(false);
                    disp.setInterpolationDuration(3);
                    disp.setInterpolationDelay(0);
                    float size = 0.3f;
                    disp.setTransformation(new org.bukkit.util.Transformation(
                            new org.joml.Vector3f(-size / 2f, -size / 2f, -size / 2f),
                            new org.joml.Quaternionf(),
                            new org.joml.Vector3f(size, size, size),
                            new org.joml.Quaternionf()));
                });
                new BukkitRunnable() {
                    int ticks = 0;
                    @Override public void run() {
                        if (ticks >= 180*20/10 || !player.isOnline() || !JungleBuffs.hasBaron(player)) {
                            if (!baronDisplay.isDead()) baronDisplay.remove();
                            cancel(); return;
                        }
                        baronDisplay.teleport(player.getLocation().add(0,1,0));
                        ticks++;
                    }
                }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
            }
            // ── Âmes du Dragon (permanentes) : effets du wiki gérés par JungleBuffs ──
            case "dragon_soul_infernal", "dragon_soul_ocean", "dragon_soul_mountain",
                 "dragon_soul_cloud", "dragon_soul_chemtech", "dragon_soul_hextech" -> {
                String el = buff.substring("dragon_soul_".length());
                JungleBuffs.setSoul(player, el);
                player.sendActionBar(Component.text("🐉 ÂME DU DRAGON : " + el.toUpperCase(), NamedTextColor.LIGHT_PURPLE));
            }
        }
        var hud = LolPlugin.getInstance().getHUDManager();
        if (hud != null) hud.updateHUD(player, champ);
    }

    private void scheduleBuffRemoval(Player player, Runnable removal, int seconds) {
        new BukkitRunnable() {
            @Override public void run() {
                removal.run();
                if (player.isOnline()) {
                    player.sendActionBar(Component.text("⏱ Buff expiré.", NamedTextColor.GRAY));
                    var cm = LolPlugin.getInstance().getChampionManager();
                    var hud = LolPlugin.getInstance().getHUDManager();
                    if (cm.hasChampion(player) && hud != null)
                        hud.updateHUD(player, cm.getChampion(player));
                }
            }
        }.runTaskLater(LolPlugin.getInstance(), seconds * 20L);
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    public static final String HERALD_EYE_TEAM = "herald_eye_team";

    /**
     * Œil du Héraut (wiki) : posé au sol à la mort du Héraut pendant 20 s ; un membre de l'équipe
     * du tueur peut le ramasser (avant : donné directement au tueur).
     */
    private void dropHeraldEye(Player killer, Location at) {
        var team = LolPlugin.getInstance().getTeamManager().getTeam(killer);
        var eye = heraldEyeItem();
        var item = at.getWorld().dropItem(at.clone().add(0, 0.5, 0), eye);
        item.setUnlimitedLifetime(true);
        item.setGlowing(true);
        if (team != null) item.getPersistentDataContainer().set(
                new NamespacedKey(LolPlugin.getInstance(), HERALD_EYE_TEAM), PersistentDataType.STRING, team.name());
        new BukkitRunnable() { @Override public void run() { if (item.isValid()) item.remove(); } }
                .runTaskLater(LolPlugin.getInstance(), 20 * 20L);
        announce(Component.text("👁 L'Œil du Héraut est tombé (20 s pour le ramasser)", NamedTextColor.LIGHT_PURPLE));
    }

    /** Objet Œil du Héraut : clic droit près d'une tour ennemie → invoque un Héraut qui la charge. */
    private static org.bukkit.inventory.ItemStack heraldEyeItem() {
        var eye = new org.bukkit.inventory.ItemStack(Material.ENDER_EYE);
        var meta = eye.getItemMeta();
        meta.displayName(Component.text(
            "👁 Œil du Héraut", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(Component.text(
            "Clic droit près d'une tour ennemie pour invoquer le Héraut",
            NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(
            new NamespacedKey(LolPlugin.getInstance(), "herald_eye"),
            PersistentDataType.BYTE, (byte) 1);
        eye.setItemMeta(meta);
        return eye;
    }

    /**
     * Héraut invoqué (wiki, patch 26) : niveau moyen des champions (9 minimum), 4320-7090 PV, 80-200 AD,
     * 87.92-170 armure, 58.96-100 RM, 0.5 attaque/s. Données du client : PV 2500 + 270, armure 34 + 8,
     * RM 32 + 4 par niveau, avec la courbe de croissance LoL (avant : linéaire depuis le niveau 1,
     * sans armure ni RM, 0.75 attaque/s). Il
     * avance vers la structure ennemie la plus proche en frappant les sbires (AD + 1.75 % de ses PV
     * actuels, les repousse). Au contact d'une structure, après 2.5 s, il bondit : 3000 dégâts à la
     * structure (puis 66.67 %, 44.44 %… pour les charges suivantes), 200 % AD aux champions ennemis
     * proches qu'il repousse, et il perd 66 % de ses PV actuels. Attaquable par les ennemis (or : 25).
     * Avant : 2000 PV, une seule charge à 1500 dégâts, puis il mourait.
     */
    public boolean summonHerald(Player summoner) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(summoner);
        if (team == null) return false;
        var mm = siblingMapManager();
        if (mm == null) return false;
        if (nearestEnemyTurret(mm, team, summoner.getLocation(), 60) == null) {
            summoner.sendActionBar(Component.text("❌ Aucune tour ennemie à proximité (60 blocs)", NamedTextColor.RED));
            return false;
        }

        double lvl = Math.max(9, Math.min(18, averageChampionLevel()));
        double g = growth(lvl);
        double maxHp = 2500 + 270 * g;
        double armor = 34 + 8 * g, mr = 32 + 4 * g;
        double ad = 80 + 120 * (g - growth(9)) / (growth(18) - growth(9)); // 80 au niveau 9, 200 au 18

        var spawn = summoner.getLocation().clone().add(1, 0, 1);
        var herald = (Ravager) spawn.getWorld().spawnEntity(spawn, EntityType.RAVAGER);
        herald.customName(Component.text("👁 Héraut de la Faille", NamedTextColor.LIGHT_PURPLE));
        herald.setCustomNameVisible(true);
        fr.lolmc.util.VirtualHP.init(herald, maxHp);
        fr.lolmc.util.MobCombat.setStats(herald, ad, 0.5, armor, mr, 25, 0);
        // Unité alliée de l'équipe : ciblable par les ennemis et les tourelles comme un sbire
        var pdc = herald.getPersistentDataContainer();
        pdc.set(MinionManager.KEY_MINION, PersistentDataType.BYTE, (byte) 1);
        pdc.set(MinionManager.KEY_TEAM, PersistentDataType.STRING, team.name());
        pdc.set(MinionManager.KEY_TYPE, PersistentDataType.STRING, "herald");
        MinionManager.LIVE.add(herald.getUniqueId());
        var state = HeraldRodeo.register(herald, team, ad, mm);

        new BukkitRunnable() {
            int ticks = 0;
            long nextHit = 0, leapAt = 0;
            GameStructure leapTarget = null;
            int destroyedNearby = destroyedEnemyTurretsNear(mm, team, herald.getLocation());
            @Override public void run() {
                ticks += 5;
                // Rodéo de nouveau possible quand le Héraut a aidé à détruire une tour ennemie (wiki ;
                // le texte de l'Œil dit « structure ennemie proche » : tour détruite à proximité)
                if (herald.isValid()) {
                    int d = destroyedEnemyTurretsNear(mm, team, herald.getLocation());
                    if (d > destroyedNearby) state.rodeoReady = true;
                    destroyedNearby = d;
                }
                if (!herald.isValid() || herald.isDead() || ticks > 240 * 20) {
                    if (herald.isValid()) herald.remove();
                    MinionManager.LIVE.remove(herald.getUniqueId());
                    HeraldRodeo.unregister(herald.getUniqueId());
                    cancel(); return;
                }
                if (state.ridden()) { leapTarget = null; state.leaping = false; return; } // dirigé par son cavalier
                herald.setTarget(null); // pas d'IA vanilla : il ne vise que sbires et structures
                long now = System.currentTimeMillis();
                // Bond en préparation (2.5 s)
                if (leapTarget != null) {
                    if (now < leapAt) return;
                    leap(herald, leapTarget, team, ad, state);
                    leapTarget = null;
                    state.leaping = false;
                    return;
                }
                GameStructure target = nearestEnemyTurret(mm, team, herald.getLocation(), 60);
                if (target == null) target = nearestEnemyStructure(mm, team, herald.getLocation(), 60);
                if (target != null && herald.getLocation().distance(target.getCenter()) <= 4.0) {
                    herald.getPathfinder().stopPathfinding();
                    leapTarget = target;
                    state.leaping = true;
                    leapAt = now + 2500L;
                    herald.getWorld().playSound(herald.getLocation(), org.bukkit.Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.7f);
                    return;
                }
                // Sbire ennemi à portée : le frapper (et le repousser)
                LivingEntity minion = null;
                for (var e : herald.getNearbyEntities(3, 2, 3))
                    if (e instanceof LivingEntity le && MinionManager.isMinion(le) && MinionManager.getMinionTeam(le) != team) { minion = le; break; }
                if (minion != null) {
                    herald.getPathfinder().stopPathfinding();
                    if (now >= nextHit) {
                        nextHit = now + (long) (1000 / 0.75);
                        fr.lolmc.util.MobCombat.damage(null, minion,
                                ad + fr.lolmc.util.VirtualHP.getCurrent(herald) * 0.0175, fr.lolmc.util.DamageUtil.Type.PHYSICAL);
                        org.bukkit.util.Vector push = minion.getLocation().toVector().subtract(herald.getLocation().toVector()).setY(0);
                        if (push.lengthSquared() > 1e-6) minion.setVelocity(push.normalize().multiply(0.8).setY(0.3));
                    }
                    return;
                }
                if (target != null) herald.getPathfinder().moveTo(target.getCenter(), 1.4);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);

        summoner.sendActionBar(Component.text("👁 Héraut invoqué — clic droit dessus pour le monter et charger!",
                NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    /** Croissance d'une statistique par niveau (formule LoL) : (n-1) × (0.7025 + 0.0175 × (n-1)). */
    private static double growth(double level) {
        double n = level - 1;
        return n * (0.7025 + 0.0175 * n);
    }

    /** Tours ennemies détruites à 15 blocs ou moins (rayon de « proche » estimé, non publié). */
    private static int destroyedEnemyTurretsNear(MapManager mm, Team team, Location at) {
        int n = 0;
        for (var s : mm.getStructures())
            if (s.isDestroyed() && s.getTeam() != team && s.getType() == GameStructure.Type.TURRET && s.getCenter().getWorld().equals(at.getWorld())
                    && s.getCenter().distanceSquared(at) <= 15 * 15) n++;
        return n;
    }

    /** Bond du Héraut invoqué sur une structure (3000 bruts, puis 66.67 % à chaque bond suivant). */
    private void leap(Ravager herald, GameStructure ft, Team team, double ad, HeraldRodeo.Summoned state) {
        var mm = siblingMapManager();
        if (ft.isDestroyed() || (mm != null && mm.isInvulnerable(ft))) return;
        boolean wasDestroyed = ft.isDestroyed();
        boolean phaseChanged = ft.takeDamage(3000 * state.leapMult);
        state.leapMult *= 2.0 / 3.0;
        fr.lolmc.util.VisualEffectUtil.impactBurst(ft.getCenter().getWorld(),
            ft.getCenter().clone().add(0, 1, 0), Material.GRAY_STAINED_GLASS, 0.4f, 1.0, 10, 8L);
        ft.getCenter().getWorld().playSound(ft.getCenter(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
        // Champions ennemis proches : 200 % AD et repoussés
        var tm = LolPlugin.getInstance().getTeamManager();
        for (Player p : herald.getWorld().getPlayers()) {
            if (tm.getTeam(p) == team || !LolPlugin.getInstance().getChampionManager().hasChampion(p)) continue;
            if (p.getLocation().distanceSquared(herald.getLocation()) > 9) continue;
            fr.lolmc.util.DamageUtil.damage(null, p, ad * 2.0, false, fr.lolmc.util.DamageUtil.Type.PHYSICAL);
            org.bukkit.util.Vector push = p.getLocation().toVector().subtract(herald.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1e-6) p.setVelocity(push.normalize().multiply(1.2).setY(0.4));
        }
        if (phaseChanged && mm != null) mm.updateStructurePhase(ft);
        if (!wasDestroyed && ft.isDestroyed()) {
            announce(Component.text("👁 Le Héraut a détruit une structure!", NamedTextColor.LIGHT_PURPLE));
            var sdl = LolPlugin.getInstance().getStructureDamageListener();
            if (sdl != null) sdl.onStructureDestroyedByMinions(ft, ft.getCenter().getWorld());
        }
        // Il perd 66 % de ses PV actuels
        double cur = fr.lolmc.util.VirtualHP.getCurrent(herald);
        fr.lolmc.util.VirtualHP.damage(herald, cur * 0.66, null);
    }

    private static GameStructure nearestEnemyTurret(MapManager mm, Team team, Location from, double max) {
        GameStructure best = null; double bestD = max * max;
        for (var s : mm.getStructures()) {
            if (s.isDestroyed() || s.getTeam() == team || s.getType() != GameStructure.Type.TURRET || mm.isInvulnerable(s)) continue;
            if (!s.getCenter().getWorld().equals(from.getWorld())) continue;
            double d = s.getCenter().distanceSquared(from);
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    static GameStructure nearestEnemyStructure(MapManager mm, Team team, Location from, double max) {
        GameStructure best = null; double bestD = max * max;
        for (var s : mm.getStructures()) {
            if (s.isDestroyed() || s.getTeam() == team || mm.isInvulnerable(s)) continue;
            if (!s.getCenter().getWorld().equals(from.getWorld())) continue;
            double d = s.getCenter().distanceSquared(from);
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    public int getVoidgrubKills(Team team) {
        return voidgrubKills.getOrDefault(team, 0);
    }

    public Map<String, Long> getEpicRespawnAt() { return epicRespawnAt; }

    public static boolean isJungleMonster(Entity e) {
        if (!(e instanceof LivingEntity)) return false;
        return e.getPersistentDataContainer().has(KEY_MONSTER, PersistentDataType.STRING);
    }

    public static MonsterType getMonsterType(Entity e) {
        String t = e.getPersistentDataContainer().get(KEY_MONSTER, PersistentDataType.STRING);
        if (t == null) return null;
        try { return MonsterType.valueOf(t); } catch (IllegalArgumentException ex) { return null; }
    }

    /**
     * Construit le modèle composite (silhouette en blocs) de chaque monstre,
     * inspiré des vrais monstres LoL. Repère : X gauche/droite, Y haut, Z avant.
     * Dimensions à ajuster visuellement en jeu si besoin.
     */
    private MobModel modelFor(MonsterType type, boolean large) {
        MobModel m = switch (type) {

            // 🐸 Gromp : gros crapaud vert à grosse bouche
            case GROMP -> new MobModel()
                    .box(Material.GREEN_CONCRETE,  0f, 0.0f,  0.0f, 1.3f, 0.9f, 1.2f)   // corps trapu
                    .box(Material.LIME_CONCRETE,   0f, 0.85f, 0.1f, 1.0f, 0.5f, 0.9f)   // haut/tête
                    .box(Material.BLACK_CONCRETE,  0f, 0.5f,  0.62f, 0.85f, 0.14f, 0.1f) // bouche
                    .cube(Material.WHITE_CONCRETE, -0.35f, 1.05f, 0.55f, 0.28f)         // œil G
                    .cube(Material.WHITE_CONCRETE,  0.35f, 1.05f, 0.55f, 0.28f)         // œil D
                    .cube(Material.BLACK_CONCRETE, -0.35f, 1.10f, 0.66f, 0.14f)         // pupille G
                    .cube(Material.BLACK_CONCRETE,  0.35f, 1.10f, 0.66f, 0.14f);        // pupille D

            // 🐺 Loups : corps gris, yeux rouges
            case MURKWOLF -> new MobModel()
                    .box(Material.GRAY_CONCRETE,  0f, 0.2f,  0.0f, 0.5f, 0.5f, 1.0f)    // corps
                    .box(Material.GRAY_CONCRETE,  0f, 0.5f,  0.45f, 0.4f, 0.4f, 0.4f)   // tête
                    .box(Material.BLACK_CONCRETE, 0f, 0.45f, -0.5f, 0.15f, 0.3f, 0.4f)  // queue
                    .cube(Material.RED_CONCRETE,  -0.12f, 0.6f, 0.62f, 0.1f)            // œil G
                    .cube(Material.RED_CONCRETE,   0.12f, 0.6f, 0.62f, 0.1f);           // œil D

            // 🦅 Raptors : petits oiseaux bruns avec bec
            case RAPTOR -> new MobModel()
                    .box(Material.BROWN_CONCRETE,    0f, 0.1f,  0.0f, 0.5f, 0.5f, 0.9f) // corps
                    .box(Material.BROWN_TERRACOTTA,  0f, 0.45f, 0.35f, 0.35f, 0.4f, 0.35f) // tête
                    .box(Material.ORANGE_CONCRETE,   0f, 0.4f,  0.6f, 0.15f, 0.12f, 0.25f) // bec
                    .box(Material.BROWN_CONCRETE,    0f, 0.35f, -0.5f, 0.2f, 0.5f, 0.4f); // queue

            // 🪨 Krugs : golems de pierre
            case KRUG -> new MobModel()
                    .box(Material.COBBLED_DEEPSLATE, 0f, 0.0f, 0.0f, 0.9f, 1.1f, 0.8f)  // torse
                    .box(Material.STONE,             0f, 1.05f, 0.0f, 0.6f, 0.5f, 0.55f) // tête
                    .box(Material.COBBLESTONE,    -0.6f, 0.3f, 0.0f, 0.35f, 0.8f, 0.35f) // bras G
                    .box(Material.COBBLESTONE,     0.6f, 0.3f, 0.0f, 0.35f, 0.8f, 0.35f) // bras D
                    .cube(Material.AMETHYST_BLOCK, 0f, 0.5f, 0.42f, 0.2f);              // rune

            // 🔴 Sanglepince (Red Brambleback) : lézard rouge avec lave
            case RED_BUFF -> new MobModel()
                    .box(Material.RED_CONCRETE,   0f, 0.1f,  0.0f, 0.9f, 0.7f, 1.3f)    // corps
                    .box(Material.RED_CONCRETE,   0f, 0.45f, 0.7f, 0.5f, 0.5f, 0.5f)    // tête
                    .box(Material.MAGMA_BLOCK,    0f, 0.75f, 0.0f, 0.5f, 0.2f, 0.9f)    // crête de lave
                    .box(Material.RED_CONCRETE,   0f, 0.3f, -0.8f, 0.4f, 0.4f, 0.6f)    // queue
                    .cube(Material.ORANGE_CONCRETE, -0.18f, 0.6f, 0.92f, 0.12f)         // œil G
                    .cube(Material.ORANGE_CONCRETE,  0.18f, 0.6f, 0.92f, 0.12f);        // œil D

            // 🔵 Sentinelle bleue (Blue Sentinel) : golem de cristal bleu
            case BLUE_BUFF -> new MobModel()
                    .box(Material.LAPIS_BLOCK,    0f, 0.0f, 0.0f, 1.0f, 1.2f, 0.9f)     // torse
                    .box(Material.LAPIS_BLOCK,    0f, 1.15f, 0.0f, 0.6f, 0.5f, 0.55f)   // tête
                    .box(Material.BLUE_ICE,    -0.65f, 0.3f, 0.0f, 0.4f, 0.9f, 0.4f)    // bras G
                    .box(Material.BLUE_ICE,     0.65f, 0.3f, 0.0f, 0.4f, 0.9f, 0.4f)    // bras D
                    .cube(Material.SEA_LANTERN,   0f, 0.55f, 0.46f, 0.25f)              // cœur lumineux
                    .cube(Material.DIAMOND_BLOCK, 0f, 1.5f, 0.0f, 0.3f);               // cristal

            // 🦀 Crabe Pillargot : carapace turquoise + pinces
            case SCUTTLE_CRAB -> new MobModel()
                    .box(Material.PRISMARINE,        0f, 0.1f,  0.0f, 1.0f, 0.4f, 0.9f) // carapace
                    .box(Material.DARK_PRISMARINE,   0f, 0.35f, 0.0f, 0.7f, 0.25f, 0.6f) // dôme
                    .box(Material.PRISMARINE_BRICKS, 0f, 0.05f, 0.5f, 0.3f, 0.25f, 0.25f) // tête
                    .box(Material.PRISMARINE,    -0.55f, 0.05f, 0.3f, 0.25f, 0.2f, 0.3f, 30f) // pince G
                    .box(Material.PRISMARINE,     0.55f, 0.05f, 0.3f, 0.25f, 0.2f, 0.3f, -30f); // pince D

            // 👁 Héraut de la Faille : corps violet avec gros œil
            case VOIDGRUB -> new MobModel()
                    .cube(Material.PURPLE_CONCRETE,  0f, 0.0f, 0f, 0.4f)
                    .cube(Material.BLACK_CONCRETE,   0f, 0.4f, 0f, 0.2f);


            case HERALD -> new MobModel()
                    .box(Material.PURPLE_CONCRETE,  0f, 0.1f, 0.0f, 0.9f, 1.1f, 0.9f)   // corps
                    .box(Material.MAGENTA_CONCRETE, 0f, 1.0f, 0.0f, 0.6f, 0.5f, 0.6f)   // tête
                    .cube(Material.WHITE_CONCRETE,  0f, 0.65f, 0.5f, 0.5f)              // gros œil
                    .cube(Material.PURPLE_CONCRETE, 0f, 0.7f, 0.72f, 0.3f)             // iris
                    .cube(Material.BLACK_CONCRETE,  0f, 0.7f, 0.85f, 0.16f)            // pupille
                    .cube(Material.SCULK_CATALYST,  0f, 1.5f, 0.0f, 0.3f);             // sommet

            // 🪱 Baron Nashor : énorme serpent du néant violet avec crocs et pics
            case BARON -> new MobModel()
                    .box(Material.PURPLE_CONCRETE,  0f, 0.3f, -0.2f, 1.0f, 1.0f, 1.6f)  // corps
                    .box(Material.MAGENTA_CONCRETE, 0f, 0.7f, 0.9f, 0.9f, 0.9f, 0.7f)   // tête
                    .box(Material.WHITE_CONCRETE,   0f, 0.58f, 1.25f, 0.7f, 0.12f, 0.15f) // crocs haut
                    .box(Material.WHITE_CONCRETE,   0f, 0.38f, 1.25f, 0.7f, 0.12f, 0.15f) // crocs bas
                    .cube(Material.MAGENTA_CONCRETE, -0.3f, 1.05f, 1.0f, 0.18f)         // œil G
                    .cube(Material.MAGENTA_CONCRETE,  0.3f, 1.05f, 1.0f, 0.18f)         // œil D
                    .box(Material.BLACK_CONCRETE,   0f, 1.3f, 0.2f, 0.12f, 0.5f, 0.12f)  // pic central
                    .box(Material.BLACK_CONCRETE, -0.35f, 1.15f, -0.2f, 0.12f, 0.4f, 0.12f) // pic G
                    .box(Material.BLACK_CONCRETE,  0.35f, 1.15f, -0.2f, 0.12f, 0.4f, 0.12f) // pic D
                    .box(Material.PURPLE_CONCRETE,  0f, 0.2f, -1.3f, 0.5f, 0.5f, 0.8f); // queue

            // 🐉 Dragons élémentaires : même structure, couleurs selon l'élément
            case DRAGON_INFERNAL -> dragonModel(Material.RED_CONCRETE,   Material.MAGMA_BLOCK,    Material.RED_TERRACOTTA);
            case DRAGON_OCEAN    -> dragonModel(Material.PRISMARINE,     Material.LAPIS_BLOCK,    Material.CYAN_TERRACOTTA);
            case DRAGON_MOUNTAIN -> dragonModel(Material.DEEPSLATE,      Material.IRON_BLOCK,     Material.GRAY_TERRACOTTA);
            case DRAGON_CLOUD    -> dragonModel(Material.WHITE_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.WHITE_TERRACOTTA);
            case DRAGON_CHEMTECH -> dragonModel(Material.LIME_CONCRETE,  Material.SLIME_BLOCK,    Material.GREEN_TERRACOTTA);
            case DRAGON_HEXTECH  -> dragonModel(Material.LIGHT_BLUE_CONCRETE, Material.COPPER_BLOCK, Material.CYAN_TERRACOTTA);
            case DRAGON_ELDER    -> dragonModel(Material.CALCITE,        Material.AMETHYST_BLOCK, Material.PURPLE_TERRACOTTA);
        };
        // Les petits monstres de groupe (loups/raptors/krugs secondaires) sont réduits
        if (!large) m.scaleAll(0.6f);
        return m;
    }

    /** Modèle générique de dragon (corps + tête + corne + queue + 2 ailes), couleurs paramétrables. */
    private MobModel dragonModel(Material body, Material accent, Material wing) {
        return new MobModel()
                .box(body,   0f, 0.3f,  0.0f, 0.6f, 0.6f, 1.4f)        // corps
                .box(body,   0f, 0.55f, 0.8f, 0.45f, 0.45f, 0.5f)      // tête
                .box(accent, 0f, 0.9f,  0.85f, 0.15f, 0.3f, 0.15f)     // corne
                .box(body,   0f, 0.3f, -0.9f, 0.3f, 0.3f, 0.7f)        // queue
                .box(wing, -0.8f, 0.6f, 0.0f, 0.9f, 0.06f, 0.7f, 35f)  // aile G
                .box(wing,  0.8f, 0.6f, 0.0f, 0.9f, 0.06f, 0.7f, -35f); // aile D
    }

    /**
     * Taille de hitbox approximative par monstre (multiplie la hitbox naturelle
     * de la base pour la rapprocher du modele). Valeurs a ajuster visuellement.
     * Les epiques restent proches de 1.0 pour ne pas trop changer leur portee.
     */
    private float hitboxScaleFor(MonsterType type) {
        return switch (type) {
            case GROMP        -> 2.0f;   // slime petit -> grossir
            case MURKWOLF, RAPTOR, SCUTTLE_CRAB -> 1.0f;
            case KRUG         -> 2.5f;   // silverfish minuscule -> grossir
            case RED_BUFF     -> 2.0f;   // magma cube petit
            case BLUE_BUFF    -> 0.8f;   // iron golem deja grand -> reduire un peu
            case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_MOUNTAIN,
                 DRAGON_CLOUD, DRAGON_CHEMTECH, DRAGON_HEXTECH, DRAGON_ELDER -> 0.85f; // ravager -> modele plus fin
            case HERALD       -> 0.85f;
            case BARON        -> 0.9f;
            case VOIDGRUB     -> 0.8f;
        };
    }

    public void clearAllMonsters() {
        // Instance : on nettoie SON monde, jamais le monde de jeu principal.
        org.bukkit.World target = scopedWorld != null ? scopedWorld : WorldContext.getGameWorld();
        List<org.bukkit.World> __worlds = target != null ? List.of(target) : List.of();
            for (var world : __worlds) {
            for (Entity e : world.getEntities()) {
                if (isJungleMonster(e) || MobAppearance.isDecoration(e)) e.remove();
            }
        }
        liveMonsters.clear();
        monsterDeco.clear();
        dragonsKilled.clear();
        MobAnimator.clearAll();
        for (CampSpawn camp : camps.values()) { camp.liveEntities.clear(); camp.respawnAt = 0; }
    }

    // ── Persistance ───────────────────────────────────────────────

    private void load() {
        var section = config.getConfigurationSection("camps");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            String path = "camps." + id + ".";
            String worldName = config.getString(path + "world");
            var world = scopedWorld != null ? scopedWorld
                    : (worldName == null ? null : LolPlugin.getInstance().getServer().getWorld(worldName));
            if (world == null) continue;
            MonsterType type;
            try { type = MonsterType.valueOf(String.valueOf(config.getString(path + "type"))); }
            catch (IllegalArgumentException ex) { continue; } // type retiré de LoL ou inconnu
            Location loc = new Location(world,
                    config.getDouble(path + "x"),
                    config.getDouble(path + "y"),
                    config.getDouble(path + "z"));
            camps.put(id, new CampSpawn(id, type, loc));
        }
    }

    private void save() {
        try { config.save(jungleFile); }
        catch (Exception e) { LolPlugin.getInstance().getLogger().warning("Erreur jungle.yml: " + e.getMessage()); }
    }

    public Map<String, CampSpawn> getCamps() { return camps; }

    public BaronPitTerrain getBaronPit() { return baronPit; }

    /** Sauvegarde jungle.yml après un enregistrement du terrain de la fosse. */
    public void saveBaronPit() { save(); }

    public org.bukkit.configuration.file.FileConfiguration getJungleConfig() { return config; }

    private org.bukkit.World baronPitWorld() {
        CampSpawn baron = camps.get(MonsterType.BARON.name().toLowerCase());
        return baron != null ? baron.location.getWorld() : null;
    }


    /** Spawn un monstre de jungle de test à la position donnée. */
    public void spawnTestMonster(Location loc, String monsterTypeName) {
        try {
            MonsterType type = MonsterType.valueOf(monsterTypeName.toUpperCase());
            var entity = loc.getWorld().spawn(loc, Zombie.class);
            var pdc = entity.getPersistentDataContainer();
            pdc.set(KEY_MONSTER, PersistentDataType.STRING, type.name());
            applyMonsterStats(entity, type, Size.LARGE, 0, false);
            liveMonsters.put(entity.getUniqueId(), type);
            // Apparence appliquée par applyMonsterAppearance si configurée
        } catch (IllegalArgumentException e) {
            LolPlugin.getInstance().getLogger().warning("Monster inconnu: " + monsterTypeName);
        }
    }

    /** Expose applyBuff() en public pour les commandes admin. */
    public void applyBuffPublic(Player player, String buff) {
        applyBuff(player, buff);
    }

    // ── Hologrammes de respawn (TextDisplay) ─────────────────────────────────

    private void updateRespawnHologram(CampSpawn camp, long secondsLeft) {
        org.bukkit.World w = camp.location.getWorld();
        if (w == null) return;
        String txt = "§e⏱ " + camp.type.displayName + "\n§7Réapparition : §f"
                + (secondsLeft / 60) + ":" + String.format("%02d", secondsLeft % 60);

        TextDisplay td = null;
        if (camp.respawnHologramId != null) {
            Entity e = w.getEntity(camp.respawnHologramId);
            if (e instanceof TextDisplay t) td = t;
        }
        if (td == null) {
            td = w.spawn(camp.location.clone().add(0, 1.6, 0), TextDisplay.class, t -> {
                t.setBillboard(Display.Billboard.CENTER);
                t.setSeeThrough(true);
                t.getScoreboardTags().add("lol_respawn_holo");
            });
            camp.respawnHologramId = td.getUniqueId();
        }
        td.text(Component.text(txt));
    }

    private void removeRespawnHologram(CampSpawn camp) {
        if (camp.respawnHologramId == null) return;
        org.bukkit.World w = camp.location.getWorld();
        if (w != null) {
            Entity e = w.getEntity(camp.respawnHologramId);
            if (e != null) e.remove();
        }
        camp.respawnHologramId = null;
    }

    private void hideAllRespawnTimers() {
        for (CampSpawn camp : camps.values()) removeRespawnHologram(camp);
    }
}
