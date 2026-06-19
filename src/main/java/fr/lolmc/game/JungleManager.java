package fr.lolmc.game;
import fr.lolmc.util.Compat;

import fr.lolmc.LolPlugin;
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

    private final File jungleFile;
    private FileConfiguration config;

    // Camps configurés : id unique → CampSpawn
    private final Map<String, CampSpawn> camps = new HashMap<>();
    // Monstres vivants : entityUUID → MonsterType
    private final Map<UUID, MonsterType> liveMonsters = new HashMap<>();
    private boolean active = false;

    // ══════════════════════════════════════════════════════════════
    // DÉFINITION DES MONSTRES
    // ══════════════════════════════════════════════════════════════

    public enum MonsterType {
        // Camps neutres
        GROMP        (EntityType.SLIME,        500,  80,  60, 100, "none", "🐸 Gromp"),
        MURKWOLF     (EntityType.WOLF,         450,  85,  55, 100, "none", "🐺 Loup"),
        RAPTOR       (EntityType.VEX,          400,  75,  50,  95, "none", "🦅 Raptor"),
        KRUG         (EntityType.SILVERFISH,   350,  70,  45,  90, "none", "🪨 Krug"),
        // Buffs
        RED_BUFF     (EntityType.MAGMA_CUBE,   1100, 100, 90, 130, "red",  "🔴 Sanglepince"),
        BLUE_BUFF    (EntityType.IRON_GOLEM,   1200, 100, 90, 130, "blue", "🔵 Sentinelle bleue"),
        // Épiques
        DRAGON       (EntityType.RAVAGER,      3500, 150, 25, 100, "none", "🐉 Dragon"),
        HERALD       (EntityType.RAVAGER,      4000, 200, 25,  0,  "none", "👁 Héraut"),
        BARON        (EntityType.WARDEN,       8500, 300, 30, 100, "baron","🪱 Baron Nashor");

        public final EntityType entity;
        public final double maxHP;
        public final int gold;
        public final double xp;
        public final int respawnSeconds;
        public final String buff;
        public final String displayName;

        MonsterType(EntityType entity, double maxHP, int gold, double xp,
                    int respawnSeconds, String buff, String displayName) {
            this.entity = entity; this.maxHP = maxHP; this.gold = gold;
            this.xp = xp; this.respawnSeconds = respawnSeconds;
            this.buff = buff; this.displayName = displayName;
        }
    }

    /** Un emplacement de camp configuré. */
    public static class CampSpawn {
        public final String id;
        public final MonsterType type;
        public final Location location;
        public UUID liveEntity = null;   // entité actuellement vivante
        public long respawnAt = 0;        // timestamp de réapparition

        public CampSpawn(String id, MonsterType type, Location location) {
            this.id = id; this.type = type; this.location = location;
        }
    }

    public JungleManager() {
        KEY_MONSTER = new NamespacedKey(LolPlugin.getInstance(), "jungle_monster");
        KEY_BUFF = new NamespacedKey(LolPlugin.getInstance(), "jungle_buff");
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
        // Les épiques (Dragon/Baron/Héraut) sont neutres → pas d'équipe
        if (type == MonsterType.DRAGON || type == MonsterType.BARON || type == MonsterType.HERALD) {
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

        Entity entity = loc.getWorld().spawnEntity(loc, type.entity);
        if (!(entity instanceof LivingEntity mob)) { entity.remove(); return; }

        // Stats
        var hpAttr = mob.getAttribute(Compat.maxHealth());
        if (hpAttr != null) { hpAttr.setBaseValue(type.maxHP); mob.setHealth(type.maxHP); }
        mob.customName(net.kyori.adventure.text.Component.text(type.displayName));
        mob.setCustomNameVisible(true);
        mob.setRemoveWhenFarAway(false);
        if (mob instanceof Mob m) m.setAware(true);

        // Empêcher de bouger loin du camp (les monstres neutres restent sur place)
        var speedAttr = mob.getAttribute(Compat.movementSpeed());
        if (speedAttr != null && type != MonsterType.DRAGON && type != MonsterType.BARON) {
            speedAttr.setBaseValue(0.15);
        }

        // Tags PDC
        mob.getPersistentDataContainer().set(KEY_MONSTER, PersistentDataType.STRING, type.name());
        mob.getPersistentDataContainer().set(KEY_BUFF, PersistentDataType.STRING, type.buff);

        camp.liveEntity = mob.getUniqueId();
        liveMonsters.put(mob.getUniqueId(), type);
    }

    /** Appelé quand un monstre meurt (depuis le listener). */
    public void onMonsterDeath(UUID entityId, Player killer) {
        MonsterType type = liveMonsters.remove(entityId);
        if (type == null) return;

        // Trouver le camp et programmer le respawn
        for (CampSpawn camp : camps.values()) {
            if (entityId.equals(camp.liveEntity)) {
                camp.liveEntity = null;
                camp.respawnAt = System.currentTimeMillis() + type.respawnSeconds * 1000L;
                break;
            }
        }

        // Récompenses
        if (killer != null) {
            LolPlugin.getInstance().getRewardManager()
                    .onJungleMonsterKill(killer, type.gold, type.xp);
            // Appliquer le buff
            applyBuff(killer, type.buff);
            // Annonce pour les épiques
            if (type == MonsterType.DRAGON || type == MonsterType.BARON || type == MonsterType.HERALD) {
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
                    if (camp.liveEntity == null && camp.respawnAt > 0 && now >= camp.respawnAt) {
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

    public void clearAllMonsters() {
        for (var world : LolPlugin.getInstance().getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (isJungleMonster(e)) e.remove();
            }
        }
        liveMonsters.clear();
        for (CampSpawn camp : camps.values()) { camp.liveEntity = null; camp.respawnAt = 0; }
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
}
