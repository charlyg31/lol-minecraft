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
import org.bukkit.attribute.Attribute;
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
    private static final java.util.Map<Object, String> dragonSoulType = new java.util.HashMap<>();

    public static NamespacedKey KEY_MONSTER; // type du monstre
    public static NamespacedKey KEY_BUFF;    // buff accordé (red/blue/none)
    public static NamespacedKey KEY_GOLD;    // or individuel du mob

    private final File jungleFile;
    private FileConfiguration config;

    // Camps configurés : id unique → CampSpawn
    private final Map<String, CampSpawn> camps = new HashMap<>();
    // Monstres vivants : entityUUID → MonsterType
    private final Map<UUID, MonsterType> liveMonsters = new HashMap<>();
    // Voidgrubs tués par équipe → bonus dégâts aux structures
    private final java.util.Map<fr.lolmc.team.TeamManager.Team, Integer> voidgrubKills
        = new java.util.EnumMap<>(fr.lolmc.team.TeamManager.Team.class);
    // Chrono de respawn des épiques : type → timestamp de respawn
    private final java.util.Map<String, Long> epicRespawnAt = new java.util.concurrent.ConcurrentHashMap<>();
    // Décorations : monstreUUID → liste des UUID des parties du modèle (nettoyage)
    private final Map<UUID, List<UUID>> monsterDeco = new HashMap<>();
    // Dragons tués par équipe (pour l'âme du Dragon au 4e)
    private final Map<fr.lolmc.team.TeamManager.Team, Integer> dragonsKilled = new HashMap<>();
    private boolean active = false;

    // ══════════════════════════════════════════════════════════════
    // DÉFINITION DES MONSTRES
    // ══════════════════════════════════════════════════════════════

    public enum MonsterType {
        // ── Camps neutres (certains en groupes) ──
        // Format: entity, maxHP, gold, xp, respawnSec, buff, groupCount, displayName
        GROMP        (EntityType.SLIME,        500,  80,  60, 135, "none", 1, "🐸 Gromp"),
        MURKWOLF     (EntityType.WOLF,         450,  85,  55, 135, "none", 3, "🐺 Loups"),       // 1 gros + 2 petits
        RAPTOR       (EntityType.VEX,          400,  75,  50, 135, "none", 6, "🦅 Raptors"),     // 1 gros + 5 petits
        KRUG         (EntityType.SILVERFISH,   350,  70,  45, 135, "none", 2, "🪨 Krugs"),       // 1 gros + 1 petit (se divise)
        // ── Buffs ──
        RED_BUFF     (EntityType.MAGMA_CUBE,   1100,  90, 90, 300, "red",  1, "🔴 Sanglepince"),
        BLUE_BUFF    (EntityType.IRON_GOLEM,   1200,  90, 90, 300, "blue", 1, "🔵 Sentinelle bleue"),
        // ── Crabe (rivière) ──
        SCUTTLE_CRAB (EntityType.TURTLE,       400,  55,  75,  150,"none", 1, "🦀 Crabe Pillargot"),
        // ── Dragons élémentaires (un seul à la fois sur la carte dans LoL) ──
        DRAGON_INFERNAL (EntityType.RAVAGER,   3500,  25, 150, 300, "drake_infernal", 1, "🔥 Dragon Infernal"),
        DRAGON_OCEAN    (EntityType.RAVAGER,   3500,  25, 150, 300, "drake_ocean",    1, "🌊 Dragon Océan"),
        DRAGON_MOUNTAIN (EntityType.RAVAGER,   3500,  25, 150, 300, "drake_mountain", 1, "⛰ Dragon Montagne"),
        DRAGON_CLOUD    (EntityType.RAVAGER,   3500,  25, 150, 300, "drake_cloud",    1, "☁ Dragon Foudre"),
        DRAGON_CHEMTECH (EntityType.RAVAGER,   3500,  25, 150, 300, "drake_chemtech", 1, "☣ Dragon Chimtech"),
        DRAGON_ELDER    (EntityType.RAVAGER,   5000,  25, 250, 360, "drake_elder",    1, "🐲 Dragon Ancien"),
        // ── Épiques de la fosse ──
        VOIDGRUB     (EntityType.SILVERFISH,    350,  50, 20,  0, "voidgrub", 3, "🐛 Nuée du Néant"),
        ATAKHAN      (EntityType.WARDEN,       9000, 250, 35,  0, "atakhan",  1, "🌺 Atakhan"),
        HERALD       (EntityType.RAVAGER,      4000, 200, 25,  0,  "none", 1, "👁 Héraut de la Faille"),
        BARON        (EntityType.WARDEN,       8500, 300, 30, 360, "baron",1, "🪱 Baron Nashor");

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
            return isDragon() || this == HERALD || this == BARON || this == ATAKHAN;
        }
    }

    /** Un emplacement de camp configuré. */
    public static class CampSpawn {
        public final String id;
        public final MonsterType type;
        public final Location location;
        public final Set<UUID> liveEntities = new HashSet<>();  // toutes les entités du camp
        public long respawnAt = 0;        // timestamp de réapparition
        public UUID respawnHologramId = null; // TextDisplay du compte à rebours

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
        this.jungleFile = new File(LolPlugin.getInstance().getDataFolder(), "jungle.yml");
        if (!jungleFile.exists()) {
            try { jungleFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.config = YamlConfiguration.loadConfiguration(jungleFile);
        load();
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
        String id = campId(type, team);
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
        // Les épiques (dragons/Baron/Héraut) et le crabe sont neutres → pas d'équipe
        if (type.isEpic() || type == MonsterType.SCUTTLE_CRAB) {
            return type.name().toLowerCase();
        }
        return type.name().toLowerCase() + "_" + (team != null ? team.name().toLowerCase() : "neutral");
    }

    /** Constructeur instance : scoped à un World précis. */
    public JungleManager(org.bukkit.World world) {
        this();
        this.scopedWorld = world;
    }

    // ══════════════════════════════════════════════════════════════
    // SPAWN / RESPAWN
    // ══════════════════════════════════════════════════════════════

    /** Lance la jungle : fait apparaître tous les camps. */
    // Tâches de premier spawn programmé (annulées au stop)
    private final java.util.List<org.bukkit.scheduler.BukkitTask> initialSpawnTasks
        = new java.util.ArrayList<>();

    /** Délai de PREMIER spawn en secondes, fidèle LoL S15. */
    private static int initialSpawnDelay(MonsterType type) {
        return switch (type) {
            case SCUTTLE_CRAB -> 210;   // 3:30
            case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_MOUNTAIN,
                 DRAGON_CLOUD, DRAGON_CHEMTECH, DRAGON_ELDER -> 300; // 5:00
            case VOIDGRUB     -> 360;   // 6:00
            case HERALD       -> 960;   // 16:00
            case ATAKHAN      -> 1200;  // 20:00
            case BARON        -> 1500;  // 25:00
            default           -> 90;    // camps + buffs : 1:30
        };
    }

    public void startJungle() {
        active = true;
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
        clearAllMonsters();
        hideAllRespawnTimers();
    }

    private void spawnCamp(CampSpawn camp) {
        MonsterType type = camp.type;
        Location loc = camp.location;
        if (loc.getWorld() == null) return;

        camp.liveEntities.clear();

        // Spawn de tous les mobs du groupe (groupCount)
        for (int i = 0; i < type.groupCount; i++) {
            // Décaler légèrement les mobs du groupe autour du centre
            double angle = 2 * Math.PI * i / Math.max(1, type.groupCount);
            double radius = type.groupCount > 1 ? 1.5 : 0;
            Location spawnLoc = loc.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);

            Entity entity = spawnLoc.getWorld().spawnEntity(spawnLoc, type.entity);
            if (!(entity instanceof LivingEntity mob)) { entity.remove(); continue; }

            // Le premier mob du groupe est le "gros" (plus de HP), les autres sont des petits
            boolean isLarge = (i == 0);
            double hp = isLarge ? type.maxHP : type.maxHP * 0.35;
            int gold = isLarge ? type.gold : Math.max(5, type.gold / 4);

            var hpAttr = mob.getAttribute(Compat.maxHealth());
            if (hpAttr != null) { hpAttr.setBaseValue(Math.min(hp, 1024.0)); mob.setHealth(Math.min(hp, 1024.0)); }
            // HP virtuels au-delà de la limite Minecraft (Baron 8500, Atakhan 9000...)
            if (hp > 1024.0) fr.lolmc.util.VirtualHP.init(mob, hp);

            String name = type.groupCount > 1
                    ? type.displayName + (isLarge ? " (Grand)" : " (Petit)")
                    : type.displayName;
            mob.customName(net.kyori.adventure.text.Component.text(name));
            mob.setCustomNameVisible(true);
            mob.setRemoveWhenFarAway(false);
            // Monstre passif : ne poursuit pas, reste à son camp (réveillé quand attaqué)
            if (mob instanceof Mob m) {
                m.setAware(false);  // ne se déplace pas tout seul
                m.setTarget(null);
            }
            // Mémoriser la position du camp pour le reset
            mob.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(LolPlugin.getInstance(), "camp_x"),
                org.bukkit.persistence.PersistentDataType.DOUBLE, mob.getLocation().getX());
            mob.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(LolPlugin.getInstance(), "camp_z"),
                org.bukkit.persistence.PersistentDataType.DOUBLE, mob.getLocation().getZ());

            // Les épiques mobiles (dragons/baron) peuvent bouger, le reste est statique
            var speedAttr = mob.getAttribute(Compat.movementSpeed());
            if (speedAttr != null && !type.isEpic()) {
                speedAttr.setBaseValue(0.15);
            }

            mob.getPersistentDataContainer().set(KEY_MONSTER, PersistentDataType.STRING, type.name());
            mob.getPersistentDataContainer().set(KEY_BUFF, PersistentDataType.STRING, type.buff);
            // Stocker l'or individuel du mob
            mob.getPersistentDataContainer().set(KEY_GOLD, PersistentDataType.INTEGER, gold);

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
        if (camp != null && camp.liveEntities.isEmpty()) {
            // Tout le camp est nettoyé → programmer le respawn
            camp.respawnAt = System.currentTimeMillis() + type.respawnSeconds * 1000L;
        }

        // Récompenses (or individuel du mob, lu depuis le PDC si dispo)
        if (killer != null) {
            int gold = type.gold;
            Entity ent = LolPlugin.getInstance().getServer().getEntity(entityId);
            // (l'entité est déjà morte, on utilise l'or du type par défaut)
            LolPlugin.getInstance().getRewardManager()
                    .onJungleMonsterKill(killer, gold, type.xp);
            // Passifs items de jungle (Gustwalker, Mosstomper, Scorchclaw)
            var pm = LolPlugin.getInstance().getPassiveManager();
            if (pm != null) pm.onJungleKill(killer);

            // Buffs d'objectifs : les épiques buffent TOUTE l'équipe (Dragon/Baron),
            // les camps rouge/bleu restent des buffs personnels.
            if (!type.buff.equals("none")) {
                if (type.isEpic()) {
                    // Héraut : donner l'Œil du Héraut au tueur
                    if (type == MonsterType.HERALD) {
                        giveHeraldEye(killer);
                    }
                    String buffId = type.buff;
                    // Atakhan : forme selon l'agressivité de la partie (kills totaux)
                    if (type == MonsterType.ATAKHAN) {
                        int totalKills = 0;
                        var msb2 = LolPlugin.getInstance().getMatchScoreboard();
                        if (msb2 != null)
                            for (var st : msb2.getStats().values()) totalKills += st.kills;
                        buffId = totalKills >= 15 ? "atakhan_voracious" : "atakhan";
                    }
                    applyTeamBuff(killer, buffId);
                    // Bounty d'objectif (comeback) sur les épiques
                    if (type.isEpic()) {
                        var ktm = LolPlugin.getInstance().getTeamManager().getTeam(killer);
                        if (ktm != null) {
                            int objBounty = LolPlugin.getInstance().getRewardManager()
                                .getObjectiveBounty(ktm);
                            if (objBounty > 0) {
                                LolPlugin.getInstance().getGoldManager()
                                    .addGold(killer.getUniqueId(), objBounty);
                                killer.sendActionBar(net.kyori.adventure.text.Component.text(
                                    "💰 Bounty d'objectif +" + objBounty + " or (comeback)!",
                                    net.kyori.adventure.text.format.NamedTextColor.GOLD));
                            }
                        }
                    }
                    if (type.isDragon()) {
                        var dtm = LolPlugin.getInstance().getTeamManager();
                        var dteam = dtm.getTeam(killer);
                        if (dteam != null) {
                            // Enregistrer le type de dragon si c'est le premier
                            dragonSoulType.putIfAbsent(dteam, type.buff.replace("drake_", ""));
                            int n = dragonsKilled.merge(dteam, 1, Integer::sum);
                            if (n >= 4) {
                                String soul = dragonSoulType.getOrDefault(dteam, "infernal");
                                applyTeamBuff(killer, "dragon_soul_" + soul);
                                LolPlugin.getInstance().getServer().broadcast(Component.text(
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
                    if (grubs == 3 || grubs == 6) {
                        for (UUID mid : LolPlugin.getInstance().getTeamManager().getTeamMembers(kteam)) {
                            Player mp = org.bukkit.Bukkit.getPlayer(mid);
                            if (mp != null) mp.sendMessage(net.kyori.adventure.text.Component.text(
                                "🐛 " + grubs + " Nuées du Néant — +" + (grubs == 3 ? "6" : "14")
                                + "% dégâts aux structures!", net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE));
                        }
                    }
                }
            }

            if (type.isEpic() && (camp == null || camp.liveEntities.isEmpty())) {
                LolPlugin.getInstance().getServer().broadcast(Component.text(
                        "🌟 " + type.displayName + " tué par " + killer.getName() + "!",
                        NamedTextColor.GOLD));
                // Feat of Strength : Premier Objectif Épique (Dragon ou Héraut)
                if (type.isDragon() || type == MonsterType.HERALD) {
                    var kt = LolPlugin.getInstance().getTeamManager().getTeam(killer);
                    if (kt != null)
                        LolPlugin.getInstance().getFeatManager().claim(
                            fr.lolmc.game.FeatManager.Feat.FIRST_EPIC, kt, killer);
                }
            }
        }
    }

    private void startRespawnTask() {
        new BukkitRunnable() {
            @Override public void run() {
                if (!active) return;
                long now = System.currentTimeMillis();
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
        for (UUID id : tm.getTeamMembers(team)) {
            Player m = LolPlugin.getInstance().getServer().getPlayer(id);
            if (m != null && m.isOnline()) applyBuff(m, buff);
        }
        currentObjectiveKiller = null;
    }

    private void applyBuff(Player player, String buff) {
        if (buff == null || buff.equals("none")) return;
        if (!LolPlugin.getInstance().getChampionManager().hasChampion(player)) return;
        BaseChampion champ = LolPlugin.getInstance().getChampionManager().getChampion(player);

        switch (buff) {
            case "red" -> {
                // Buff Rouge : dégâts bonus + ralentissement sur les attaques (120s)
                champ.getStats().addBonusAD(15);
                player.sendActionBar(Component.text(
                        "🔴 Buff Rouge! +dégâts et ralentissement sur tes attaques (90s)",
                        NamedTextColor.RED));
                scheduleBuffRemoval(player, () -> champ.getStats().addBonusAD(-15), 90);
            }
            case "blue" -> {
                // Buff Bleu : régén mana + réduction des cooldowns (120s)
                champ.getStats().addBonusAbilityHaste(20);
                player.sendActionBar(Component.text(
                        "🔵 Buff Bleu! +20 hâte de compétence et régén ressource (90s)",
                        NamedTextColor.BLUE));
                scheduleBuffRemoval(player, () -> champ.getStats().addBonusAbilityHaste(-20), 90);
            }
            case "drake_infernal" -> {
                champ.getStats().addBonusAD(8);
                champ.getStats().addBonusAP(12);
                player.sendActionBar(Component.text("🔥 Dragon Infernal: +dégâts!", NamedTextColor.RED));
            }
            case "drake_ocean" -> {
                champ.getHPSystem().setHpRegen(champ.getHPSystem().getHpRegenPer5s() + 5);
                player.sendActionBar(Component.text("🌊 Dragon Océan: +régénération!", NamedTextColor.AQUA));
            }
            case "drake_mountain" -> {
                champ.getStats().addBonusArmor(10);
                champ.getStats().addBonusMR(10);
                player.sendActionBar(Component.text("⛰ Dragon Montagne: +résistances!", NamedTextColor.GOLD));
            }
            case "drake_cloud" -> {
                champ.getStats().addBonusMoveSpeed(0.02);
                player.sendActionBar(Component.text("☁ Dragon Foudre: +vitesse!", NamedTextColor.WHITE));
            }
            case "drake_chemtech" -> {
                champ.getStats().multiplyHP(1.06);
                player.sendActionBar(Component.text("☣ Dragon Chimtech: +6% PV!", NamedTextColor.GREEN));
            }
            case "drake_elder" -> {
                champ.getStats().addBonusAD(20);
                champ.getStats().addBonusAP(30);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 150*20, 0, false, true));
                player.sendActionBar(Component.text("🐲 Dragon Ancien: bénédiction puissante!", NamedTextColor.LIGHT_PURPLE));
                scheduleBuffRemoval(player, () -> {
                    champ.getStats().addBonusAD(-20);
                    champ.getStats().addBonusAP(-30);
                }, 150);
            }
            case "baron" -> {
                // Buff Baron : AD/AP bonus + renforce les sbires (180s)
                champ.getStats().addBonusAD(24);
                champ.getStats().addBonusAP(40);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 180 * 20, 0, false, true));
                player.sendActionBar(Component.text(
                        "🪱 Buff du Baron! +24 AD +40 AP (3min)", NamedTextColor.LIGHT_PURPLE));
                // Particules violettes autour du joueur pendant 180s
                new BukkitRunnable() {
                    int ticks = 0;
                    @Override public void run() {
                        if (ticks >= 180*20/10 || !player.isOnline()) { cancel(); return; }
                        player.getWorld().spawnParticle(org.bukkit.Particle.DUST,
                            player.getLocation().add(0,1,0), 5, 0.4,0.6,0.4, 0,
                            new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(128,0,128), 1.2f));
                        ticks++;
                    }
                }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
                scheduleBuffRemoval(player, () -> {
                    champ.getStats().addBonusAD(-24);
                    champ.getStats().addBonusAP(-40);
                }, 180);
            }
            // ── Âmes du Dragon (permanentes, distinctes par élément) ──
            // ── Atakhan Ruineux (partie calme) : buff équipe permanent ──
            case "atakhan" -> {
                champ.getStats().addBonusAD(15);
                champ.getStats().addBonusAP(25);
                champ.getStats().multiplyHP(1.05);
                player.sendActionBar(Component.text(
                    "🌺 ATAKHAN RUINEUX! +15 AD +25 AP +5% PV permanents", NamedTextColor.LIGHT_PURPLE));
            }
            // ── Atakhan Vorace (partie sanglante ≥15 kills) : résurrection + or ──
            case "atakhan_voracious" -> {
                boolean isKiller = player.equals(currentObjectiveKiller);
                // Le tueur reçoit une résurrection unique (Pétales Sanglants)
                var pmv = LolPlugin.getInstance().getPassiveManager();
                if (pmv != null && isKiller) pmv.grantAtakhanRevive(player);
                // Toute l'équipe : +150 or (pétales)
                LolPlugin.getInstance().getGoldManager().addGold(player.getUniqueId(), 150);
                player.sendActionBar(Component.text(
                    isKiller ? "🌺 ATAKHAN VORACE! Résurrection + 150 or"
                             : "🌺 ATAKHAN VORACE! +150 or (Pétales Sanglants)",
                    NamedTextColor.DARK_RED));
            }
            case "dragon_soul_infernal" -> {
                // Infernal : burst AoE — +AD/AP forts
                champ.getStats().addBonusAD(30);
                champ.getStats().addBonusAP(45);
                player.sendActionBar(Component.text(
                    "🔥 ÂME INFERNALE! +30 AD +45 AP permanents", NamedTextColor.RED));
            }
            case "dragon_soul_ocean" -> {
                // Océan : régénération puissante
                champ.getStats().addBonusAD(10);
                champ.getStats().multiplyHP(1.05);
                player.addPotionEffect(new PotionEffect(
                    PotionEffectType.REGENERATION, Integer.MAX_VALUE, 1, false, false));
                player.sendActionBar(Component.text(
                    "🌊 ÂME OCÉANIQUE! Régénération permanente +5% PV", NamedTextColor.AQUA));
            }
            case "dragon_soul_mountain" -> {
                // Montagne : bouclier + résistances
                champ.getStats().addBonusArmor(25);
                champ.getStats().addBonusMR(25);
                champ.getStats().multiplyHP(1.10);
                player.sendActionBar(Component.text(
                    "⛰ ÂME MONTAGNEUSE! +25 Armure +25 RM +10% PV", NamedTextColor.GOLD));
            }
            case "dragon_soul_cloud", "dragon_soul_chemtech" -> {
                // Nuage : vitesse de déplacement massive
                champ.getStats().addBonusAD(15);
                champ.getStats().addBonusAP(20);
                player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
                player.sendActionBar(Component.text(
                    "☁ ÂME DES NUAGES! +Vitesse permanente +15 AD +20 AP", NamedTextColor.WHITE));
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

    /** Donne l'Œil du Héraut : clic droit → invoque un Héraut qui charge la tour. */
    private void giveHeraldEye(Player killer) {
        var eye = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ENDER_EYE);
        var meta = eye.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(
            "👁 Œil du Héraut", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        meta.lore(java.util.List.of(net.kyori.adventure.text.Component.text(
            "Clic droit près d'une tour ennemie pour invoquer le Héraut",
            net.kyori.adventure.text.format.NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(LolPlugin.getInstance(), "herald_eye"),
            org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        eye.setItemMeta(meta);
        killer.getInventory().addItem(eye);
        killer.sendMessage(net.kyori.adventure.text.Component.text(
            "👁 Tu as reçu l'Œil du Héraut! Clic droit près d'une tour ennemie.",
            net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
    }

    /**
     * Invoque un Héraut allié qui charge la tour ennemie la plus proche.
     * Le Héraut inflige 400 dégâts à la structure puis meurt (comme LoL).
     */
    public boolean summonHerald(Player summoner) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(summoner);
        if (team == null) return false;
        var mm = LolPlugin.getInstance().getMapManager();
        if (mm == null) return false;

        // Tour ennemie la plus proche (max 30 blocs)
        fr.lolmc.game.GameStructure target = null;
        double best = 30 * 30;
        for (var s : mm.getStructures()) {
            if (s.isDestroyed() || s.getTeam() == team) continue;
            if (s.getType() != fr.lolmc.game.GameStructure.Type.TURRET) continue;
            double d = s.getCenter().distanceSquared(summoner.getLocation());
            if (d < best) { best = d; target = s; }
        }
        if (target == null) {
            summoner.sendActionBar(net.kyori.adventure.text.Component.text(
                "❌ Aucune tour ennemie à proximité (30 blocs)",
                net.kyori.adventure.text.format.NamedTextColor.RED));
            return false;
        }

        // Spawn du Héraut allié
        var spawn = summoner.getLocation().clone().add(1, 0, 1);
        var herald = (org.bukkit.entity.Ravager) spawn.getWorld()
            .spawnEntity(spawn, org.bukkit.entity.EntityType.RAVAGER);
        herald.customName(net.kyori.adventure.text.Component.text(
            "👁 Héraut de la Faille", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        herald.setCustomNameVisible(true);
        var maxHpAttr = herald.getAttribute(fr.lolmc.util.Compat.maxHealth());
        if (maxHpAttr != null) { maxHpAttr.setBaseValue(500); herald.setHealth(500); }

        final var ft = target;
        // Le Héraut charge : avance vers la tour, puis frappe pour 400
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!herald.isValid() || ft.isDestroyed()) { 
                    if (herald.isValid()) herald.remove();
                    cancel(); return; 
                }
                ticks++;
                if (ticks > 20 * 15) { herald.remove(); cancel(); return; } // timeout 15s
                double dist = herald.getLocation().distance(ft.getCenter());
                if (dist > 3.5) {
                    herald.getPathfinder().moveTo(ft.getCenter(), 1.6);
                } else {
                    // CHARGE ! 400 dégâts à la structure puis mort du Héraut
                    boolean phaseChanged = ft.takeDamage(400);
                    ft.getCenter().getWorld().spawnParticle(
                        org.bukkit.Particle.EXPLOSION_EMITTER, ft.getCenter().clone().add(0,1,0), 2);
                    ft.getCenter().getWorld().playSound(ft.getCenter(),
                        org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
                    if (phaseChanged) mm.updateStructurePhase(ft);
                    if (ft.isDestroyed()) {
                        var sdl = LolPlugin.getInstance().getStructureDamageListener();
                        // Détruire proprement via le flux normal
                        LolPlugin.getInstance().getServer().broadcast(
                            net.kyori.adventure.text.Component.text(
                                "👁 Le Héraut a détruit une tourelle!",
                                net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
                    }
                    herald.setHealth(0.01);
                    herald.damage(1000); // mort du Héraut après la charge
                    cancel();
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);

        summoner.sendActionBar(net.kyori.adventure.text.Component.text(
            "👁 Héraut invoqué — il charge la tour!",
            net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    public int getVoidgrubKills(fr.lolmc.team.TeamManager.Team team) {
        return voidgrubKills.getOrDefault(team, 0);
    }
    public double getVoidgrubDamageBonus(fr.lolmc.team.TeamManager.Team team) {
        int g = getVoidgrubKills(team);
        if (g >= 6) return 1.14; if (g >= 3) return 1.06; return 1.0;
    }
    public java.util.Map<String, Long> getEpicRespawnAt() { return epicRespawnAt; }

    public static boolean isJungleMonster(Entity e) {
        if (!(e instanceof LivingEntity)) return false;
        return e.getPersistentDataContainer().has(KEY_MONSTER, PersistentDataType.STRING);
    }

    public static MonsterType getMonsterType(Entity e) {
        String t = e.getPersistentDataContainer().get(KEY_MONSTER, PersistentDataType.STRING);
        return t != null ? MonsterType.valueOf(t) : null;
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

            case ATAKHAN -> new MobModel()
                    .box(Material.RED_CONCRETE,    0f, 0.0f, 0f, 2.0f, 2.5f, 1.8f)
                    .cube(Material.ORANGE_CONCRETE, 0f, 2.4f, 0f, 0.7f)
                    .cube(Material.YELLOW_CONCRETE, 0f, 2.6f, 0.5f, 0.3f);

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
            case MURKWOLF     -> 1.0f;
            case RAPTOR       -> 1.0f;
            case KRUG         -> 2.5f;   // silverfish minuscule -> grossir
            case RED_BUFF     -> 2.0f;   // magma cube petit
            case BLUE_BUFF    -> 0.8f;   // iron golem deja grand -> reduire un peu
            case SCUTTLE_CRAB -> 1.0f;
            case DRAGON_INFERNAL, DRAGON_OCEAN, DRAGON_MOUNTAIN,
                 DRAGON_CLOUD, DRAGON_CHEMTECH, DRAGON_ELDER -> 0.85f; // ravager -> modele plus fin
            case HERALD       -> 0.85f;
            case BARON        -> 0.9f;
            case VOIDGRUB     -> 0.8f;
            case ATAKHAN      -> 1.0f;
        };
    }

    public void clearAllMonsters() {
        java.util.List<org.bukkit.World> __worlds = WorldContext.getGameWorld() != null
                ? java.util.List.of(WorldContext.getGameWorld())
                : java.util.List.of();
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
            var world = LolPlugin.getInstance().getServer().getWorld(config.getString(path + "world"));
            if (world == null) continue;
            MonsterType type = MonsterType.valueOf(config.getString(path + "type"));
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


    /** Spawn un monstre de jungle de test à la position donnée. */
    public void spawnTestMonster(org.bukkit.Location loc, String monsterTypeName) {
        try {
            MonsterType type = MonsterType.valueOf(monsterTypeName.toUpperCase());
            var entity = loc.getWorld().spawn(loc, org.bukkit.entity.Zombie.class);
            if (entity instanceof org.bukkit.entity.LivingEntity le) {
                var pdc = le.getPersistentDataContainer();
                pdc.set(KEY_MONSTER, org.bukkit.persistence.PersistentDataType.STRING, type.name());
                var hpAttr = le.getAttribute(fr.lolmc.util.Compat.maxHealth());
                if (hpAttr != null) { hpAttr.setBaseValue(Math.min(type.maxHP, 1024.0)); le.setHealth(Math.min(type.maxHP, 1024.0)); }
                if (type.maxHP > 1024.0) fr.lolmc.util.VirtualHP.init(le, type.maxHP);
                liveMonsters.put(le.getUniqueId(), type);
                // Apparence appliquée par applyMonsterAppearance si configurée
            }
        } catch (IllegalArgumentException e) {
            LolPlugin.getInstance().getLogger().warning("Monster inconnu: " + monsterTypeName);
        }
    }

    /** Expose applyBuff() en public pour les commandes admin. */
    public void applyBuffPublic(org.bukkit.entity.Player player, String buff) {
        applyBuff(player, buff);
    }

    // ── Hologrammes de respawn (TextDisplay) ─────────────────────────────────

    private void updateRespawnHologram(CampSpawn camp, long secondsLeft) {
        org.bukkit.World w = camp.location.getWorld();
        if (w == null) return;
        String txt = "§e⏱ " + camp.type.displayName + "\n§7Réapparition : §f"
                + (secondsLeft / 60) + ":" + String.format("%02d", secondsLeft % 60);

        org.bukkit.entity.TextDisplay td = null;
        if (camp.respawnHologramId != null) {
            org.bukkit.entity.Entity e = w.getEntity(camp.respawnHologramId);
            if (e instanceof org.bukkit.entity.TextDisplay t) td = t;
        }
        if (td == null) {
            td = w.spawn(camp.location.clone().add(0, 1.6, 0), org.bukkit.entity.TextDisplay.class, t -> {
                t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                t.setSeeThrough(true);
                t.getScoreboardTags().add("lol_respawn_holo");
            });
            camp.respawnHologramId = td.getUniqueId();
        }
        td.text(net.kyori.adventure.text.Component.text(txt));
    }

    private void removeRespawnHologram(CampSpawn camp) {
        if (camp.respawnHologramId == null) return;
        org.bukkit.World w = camp.location.getWorld();
        if (w != null) {
            org.bukkit.entity.Entity e = w.getEntity(camp.respawnHologramId);
            if (e != null) e.remove();
        }
        camp.respawnHologramId = null;
    }

    private void hideAllRespawnTimers() {
        for (CampSpawn camp : camps.values()) removeRespawnHologram(camp);
    }
}
