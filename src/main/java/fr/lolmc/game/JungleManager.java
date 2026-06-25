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

    public static NamespacedKey KEY_MONSTER; // type du monstre
    public static NamespacedKey KEY_BUFF;    // buff accordé (red/blue/none)
    public static NamespacedKey KEY_GOLD;    // or individuel du mob

    private final File jungleFile;
    private FileConfiguration config;

    // Camps configurés : id unique → CampSpawn
    private final Map<String, CampSpawn> camps = new HashMap<>();
    // Monstres vivants : entityUUID → MonsterType
    private final Map<UUID, MonsterType> liveMonsters = new HashMap<>();
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
        RED_BUFF     (EntityType.MAGMA_CUBE,   1100, 100, 90, 300, "red",  1, "🔴 Sanglepince"),
        BLUE_BUFF    (EntityType.IRON_GOLEM,   1200, 100, 90, 300, "blue", 1, "🔵 Sentinelle bleue"),
        // ── Crabe (rivière) ──
        SCUTTLE_CRAB (EntityType.TURTLE,       400,  55,  40,  150,"none", 1, "🦀 Crabe Pillargot"),
        // ── Dragons élémentaires (un seul à la fois sur la carte dans LoL) ──
        DRAGON_INFERNAL (EntityType.RAVAGER,   3500, 150, 25, 300, "drake_infernal", 1, "🔥 Dragon Infernal"),
        DRAGON_OCEAN    (EntityType.RAVAGER,   3500, 150, 25, 300, "drake_ocean",    1, "🌊 Dragon Océan"),
        DRAGON_MOUNTAIN (EntityType.RAVAGER,   3500, 150, 25, 300, "drake_mountain", 1, "⛰ Dragon Montagne"),
        DRAGON_CLOUD    (EntityType.RAVAGER,   3500, 150, 25, 300, "drake_cloud",    1, "☁ Dragon Foudre"),
        DRAGON_CHEMTECH (EntityType.RAVAGER,   3500, 150, 25, 300, "drake_chemtech", 1, "☣ Dragon Chimtech"),
        DRAGON_ELDER    (EntityType.RAVAGER,   5000, 200, 40, 360, "drake_elder",    1, "🐲 Dragon Ancien"),
        // ── Épiques de la fosse ──
        HERALD       (EntityType.RAVAGER,      4000, 200, 25,  0,  "none", 1, "👁 Héraut de la Faille"),
        BARON        (EntityType.WARDEN,       8500, 300, 30, 420, "baron",1, "🪱 Baron Nashor");

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

    /** Un emplacement de camp configuré. */
    public static class CampSpawn {
        public final String id;
        public final MonsterType type;
        public final Location location;
        public final Set<UUID> liveEntities = new HashSet<>();  // toutes les entités du camp
        public long respawnAt = 0;        // timestamp de réapparition

        public CampSpawn(String id, MonsterType type, Location location) {
            this.id = id; this.type = type; this.location = location;
        }

        public boolean isAlive() { return !liveEntities.isEmpty(); }
    }

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

    // ══════════════════════════════════════════════════════════════
    // SPAWN / RESPAWN
    // ══════════════════════════════════════════════════════════════

    /** Lance la jungle : fait apparaître tous les camps. */
    public void startJungle() {
        active = true;
        for (CampSpawn camp : camps.values()) {
            spawnCamp(camp);
        }
    }

    public void stopJungle() {
        active = false;
        clearAllMonsters();
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
            if (hpAttr != null) { hpAttr.setBaseValue(hp); mob.setHealth(hp); }

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

            // Buffs d'objectifs : les épiques buffent TOUTE l'équipe (Dragon/Baron),
            // les camps rouge/bleu restent des buffs personnels.
            if (!type.buff.equals("none")) {
                if (type.isEpic()) {
                    applyTeamBuff(killer, type.buff);
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
            if (type.isEpic() && (camp == null || camp.liveEntities.isEmpty())) {
                LolPlugin.getInstance().getServer().broadcast(Component.text(
                        "🌟 " + type.displayName + " tué par " + killer.getName() + "!",
                        NamedTextColor.GOLD));
            }
        }
    }

    private void startRespawnTask() {
        new BukkitRunnable() {
            @Override public void run() {
                if (!active) return;
                long now = System.currentTimeMillis();
                for (CampSpawn camp : camps.values()) {
                    if (!camp.isAlive() && camp.respawnAt > 0 && now >= camp.respawnAt) {
                        spawnCamp(camp);
                        camp.respawnAt = 0;
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L); // vérifie chaque seconde
    }

    // ══════════════════════════════════════════════════════════════
    // BUFFS (Rouge / Bleu / Baron)
    // ══════════════════════════════════════════════════════════════

    /** Applique un buff à TOUTE l'équipe du tueur (objectifs : Dragon, Baron, âme...). */
    private void applyTeamBuff(Player killer, String buff) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(killer);
        if (team == null) { applyBuff(killer, buff); return; }
        for (UUID id : tm.getTeamMembers(team)) {
            Player m = LolPlugin.getInstance().getServer().getPlayer(id);
            if (m != null && m.isOnline()) applyBuff(m, buff);
        }
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
                        "🔴 Buff Rouge! +dégâts et ralentissement sur tes attaques (2min)",
                        NamedTextColor.RED));
                scheduleBuffRemoval(player, () -> champ.getStats().addBonusAD(-15), 120);
            }
            case "blue" -> {
                // Buff Bleu : régén mana + réduction des cooldowns (120s)
                champ.getStats().addBonusAbilityHaste(20);
                player.sendActionBar(Component.text(
                        "🔵 Buff Bleu! +20 hâte de compétence et régén ressource (2min)",
                        NamedTextColor.BLUE));
                scheduleBuffRemoval(player, () -> champ.getStats().addBonusAbilityHaste(-20), 120);
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
                scheduleBuffRemoval(player, () -> {
                    champ.getStats().addBonusAD(-24);
                    champ.getStats().addBonusAP(-40);
                }, 180);
            }
            case "dragon_soul" -> {
                // Âme du Dragon : bénédiction PERMANENTE d'équipe
                champ.getStats().addBonusAD(20);
                champ.getStats().addBonusAP(30);
                champ.getStats().multiplyHP(1.08);
                player.sendActionBar(Component.text(
                        "🐉 ÂME DU DRAGON! Bénédiction permanente (+20 AD, +30 AP, +8% PV)",
                        NamedTextColor.LIGHT_PURPLE));
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
            var entity = loc.getWorld().spawn(loc, type.entityType.getEntityClass());
            if (entity instanceof org.bukkit.entity.LivingEntity le) {
                var pdc = le.getPersistentDataContainer();
                pdc.set(KEY_MONSTER, org.bukkit.persistence.PersistentDataType.STRING, type.name());
                var hpAttr = le.getAttribute(fr.lolmc.util.Compat.maxHealth());
                if (hpAttr != null) { hpAttr.setBaseValue(type.maxHP); le.setHealth(type.maxHP); }
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
}
