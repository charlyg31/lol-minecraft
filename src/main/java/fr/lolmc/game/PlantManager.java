package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ResourceSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plantes de la jungle (wiki LoL, patch 26.20).
 *
 * Toutes : 1 PV, détruites par une attaque de base (portée d'attaque du champion), rayon 80.
 * Elles apparaissent en bourgeon inactif pendant 60 s, puis poussent.
 *
 * <ul>
 *   <li><b>Cône explosif</b> : explosion de rayon 325 qui projette toutes les unités (attaquant
 *       compris) jusqu'à 900 unités, par-dessus le décor. Intérieur : 1:15-1:25, repousse 5-7 min ;
 *       extérieur : 9:00-9:30, repousse 5,5-6,5 min.</li>
 *   <li><b>Fruit de miel</b> : lâche 5 fruits qui restent 30 s. Un fruit rend 6 % du mana max et
 *       max(3,5 % des PV max, 14-116 selon le niveau) PV, et ralentit de 35 % pendant 0,25 s.
 *       Première apparition 6:00-6:30, repousse 5,5-7 min.</li>
 *   <li><b>Bulbe révélateur</b> : pollen en cône dans la direction de l'attaquant : champions
 *       ennemis révélés 3 s, autres unités et balises ennemies 12 s. Première apparition 3:00-3:30,
 *       repousse 200-260 s (patch 26.1) ; emplacements supplémentaires dès 15:00, repousse 90-120 s.</li>
 * </ul>
 *
 * Les emplacements sont posés avec {@code /lola plant} et enregistrés dans plants.yml ; chaque
 * partie (monde unique ou instance) a ses propres plantes, programmées depuis le début de la partie.
 */
public class PlantManager {

    public enum PlantType {
        BLASTCONE  (Material.FIRE_CHARGE, "💥 Cône explosif",     NamedTextColor.RED),
        HONEYFRUIT (Material.HONEYCOMB,   "🍯 Fruit de miel",      NamedTextColor.GOLD),
        SCRYER     (Material.ENDER_EYE,   "👁 Bulbe révélateur",   NamedTextColor.AQUA);

        public final Material icon;
        public final String displayName;
        public final NamedTextColor color;

        PlantType(Material icon, String displayName, NamedTextColor color) {
            this.icon = icon; this.displayName = displayName; this.color = color;
        }
    }

    /**
     * Variante d'emplacement : cône intérieur / extérieur, bulbe normal / tardif (15:00).
     * Les fruits de miel n'ont qu'une variante.
     */
    public enum Variant { INNER, OUTER, NORMAL, LATE }

    /** Emplacement configuré (monde template). */
    public record Spot(String id, PlantType type, Variant variant, Location location) {}

    /** Plante d'une partie. */
    private static final class Plant {
        final Spot spot;
        final Location location;
        int stage; // 0 absente, 1 bourgeon, 2 mûre
        UUID markerId;
        final List<BukkitTask> tasks = new ArrayList<>();
        Plant(Spot spot, World world) {
            this.spot = spot;
            this.location = new Location(world, spot.location.getX(), spot.location.getY(), spot.location.getZ());
        }
    }

    /** Fruit de miel au sol. */
    private record Fruit(Location location, UUID markerId, long expiresAt) {}

    private static final int BUD_SECONDS = 60;
    /** Rayon d'explosion du Cône : 325 + rayon de collision (convention : +0,65 bloc). */
    private static final double BLAST_RADIUS = 3.25 + 0.65;
    /** Distance maximale de projection : 900 unités. */
    private static final double BLAST_DISTANCE = 9.0;
    /**
     * Cône du Bulbe : portée 1500 (indicateur de survol des données du jeu, CommunityDragon
     * « hoverIndicatorRadius »). L'angle n'est publié nulle part : 60° (approximation).
     */
    private static final double SCRYER_RANGE = 15.0;
    private static final double SCRYER_HALF_ANGLE_COS = Math.cos(Math.toRadians(30));
    /** Ramassage d'un fruit : rayon de collision du champion + du fruit (approximation). */
    private static final double FRUIT_PICKUP = 0.8;

    private final File file;
    private final FileConfiguration config;
    private final Map<String, Spot> spots = new LinkedHashMap<>();
    /** Plantes en jeu, par monde. */
    private final Map<String, List<Plant>> live = new HashMap<>();
    private final Map<String, List<Fruit>> fruits = new HashMap<>();
    private BukkitTask fruitTask;

    public PlantManager() {
        this.file = new File(LolPlugin.getInstance().getDataFolder(), "plants.yml");
        this.config = YamlConfiguration.loadConfiguration(file);
        load();
    }

    // ══════════════════════════════════════════════════════════════
    // CONFIGURATION (/lola plant)
    // ══════════════════════════════════════════════════════════════

    private void load() {
        spots.clear();
        var sec = config.getConfigurationSection("plants");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            String p = "plants." + id + ".";
            try {
                PlantType type = PlantType.valueOf(config.getString(p + "type"));
                Variant variant = Variant.valueOf(config.getString(p + "variant", defaultVariant(type).name()));
                World w = org.bukkit.Bukkit.getWorld(config.getString(p + "world", ""));
                Location loc = new Location(w, config.getDouble(p + "x"), config.getDouble(p + "y"), config.getDouble(p + "z"));
                spots.put(id, new Spot(id, type, variant, loc));
            } catch (Exception ex) {
                LolPlugin.getInstance().getLogger().warning("plants.yml : emplacement " + id + " ignoré (" + ex.getMessage() + ")");
            }
        }
    }

    private void save() {
        config.set("plants", null);
        for (Spot s : spots.values()) {
            String p = "plants." + s.id() + ".";
            config.set(p + "type", s.type().name());
            config.set(p + "variant", s.variant().name());
            config.set(p + "world", s.location().getWorld() != null ? s.location().getWorld().getName() : "");
            config.set(p + "x", s.location().getX());
            config.set(p + "y", s.location().getY());
            config.set(p + "z", s.location().getZ());
        }
        try { config.save(file); }
        catch (Exception e) { LolPlugin.getInstance().getLogger().warning("Erreur sauvegarde plants.yml : " + e.getMessage()); }
    }

    public static Variant defaultVariant(PlantType type) {
        return switch (type) {
            case BLASTCONE -> Variant.INNER;
            case HONEYFRUIT, SCRYER -> Variant.NORMAL;
        };
    }

    /** Variantes acceptées pour un type. */
    public static boolean accepts(PlantType type, Variant v) {
        return switch (type) {
            case BLASTCONE -> v == Variant.INNER || v == Variant.OUTER;
            case HONEYFRUIT -> v == Variant.NORMAL;
            case SCRYER -> v == Variant.NORMAL || v == Variant.LATE;
        };
    }

    public Spot addSpot(PlantType type, Variant variant, Location loc) {
        int n = 1;
        String base = type.name().toLowerCase() + "_";
        while (spots.containsKey(base + n)) n++;
        Location l = loc.getBlock().getLocation().add(0.5, 0, 0.5);
        Spot s = new Spot(base + n, type, variant, l);
        spots.put(s.id(), s);
        save();
        return s;
    }

    /** Supprime l'emplacement le plus proche (dans {@code maxDist} blocs). */
    public Spot removeNearest(Location loc, double maxDist) {
        Spot best = null;
        double bd = maxDist * maxDist;
        for (Spot s : spots.values()) {
            if (s.location().getWorld() == null || !s.location().getWorld().equals(loc.getWorld())) continue;
            double d = s.location().distanceSquared(loc);
            if (d <= bd) { bd = d; best = s; }
        }
        if (best != null) { spots.remove(best.id()); save(); }
        return best;
    }

    public int clearSpots() { int n = spots.size(); spots.clear(); save(); return n; }

    public Collection<Spot> getSpots() { return Collections.unmodifiableCollection(spots.values()); }

    /** Le template change de monde ({@code /lola template save}) : les emplacements le suivent. */
    public void rebindWorld(String worldName) {
        World w = org.bukkit.Bukkit.getWorld(worldName);
        List<Spot> copy = new ArrayList<>(spots.values());
        spots.clear();
        for (Spot s : copy) {
            Location l = s.location().clone();
            l.setWorld(w);
            spots.put(s.id(), new Spot(s.id(), s.type(), s.variant(), l));
        }
        config.set("plants", null);
        save();
        if (w == null) {
            // Monde non chargé : écrire le nom pour le prochain démarrage
            for (Spot s : spots.values()) config.set("plants." + s.id() + ".world", worldName);
            try { config.save(file); } catch (Exception ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CYCLE DE VIE D'UNE PARTIE
    // ══════════════════════════════════════════════════════════════

    /** Début de partie dans le monde de jeu configuré (mode partie unique). */
    public void spawnAll() {
        World w = fr.lolmc.util.WorldContext.getGameWorld();
        if (w != null) spawnAll(w);
    }

    /** Début de partie dans {@code world} : programme l'apparition de chaque plante (temps de jeu). */
    public void spawnAll(World world) {
        if (world == null) return;
        clearAll(world);
        List<Plant> list = new ArrayList<>();
        for (Spot s : spots.values()) {
            Plant p = new Plant(s, world);
            list.add(p);
            // Chunk gardé chargé pendant la partie : sinon le marqueur disparaît quand personne n'est près
            world.addPluginChunkTicket(p.location.getBlockX() >> 4, p.location.getBlockZ() >> 4, LolPlugin.getInstance());
            schedule(p, firstSpawnSeconds(s));
        }
        live.put(world.getName(), list);
        startFruitTask();
    }

    /** Fin de partie (mode partie unique). */
    public void clearAll() {
        World w = fr.lolmc.util.WorldContext.getGameWorld();
        if (w != null) clearAll(w);
    }

    /** Fin de partie dans {@code world} : retire plantes, fruits et tâches. */
    public void clearAll(World world) {
        if (world == null) return;
        List<Plant> list = live.remove(world.getName());
        if (list != null) for (Plant p : list) {
            for (BukkitTask t : p.tasks) t.cancel();
            p.tasks.clear();
            removeMarker(p.markerId);
            p.markerId = null;
            p.stage = 0;
            world.removePluginChunkTicket(p.location.getBlockX() >> 4, p.location.getBlockZ() >> 4, LolPlugin.getInstance());
        }
        List<Fruit> fl = fruits.remove(world.getName());
        if (fl != null) for (Fruit f : fl) removeMarker(f.markerId());
        if (live.isEmpty() && fruits.isEmpty() && fruitTask != null) { fruitTask.cancel(); fruitTask = null; }
    }

    private static int rand(int min, int max) { return ThreadLocalRandom.current().nextInt(min, max + 1); }

    /** Première apparition (bourgeon), en secondes de partie. */
    private static int firstSpawnSeconds(Spot s) {
        return switch (s.type()) {
            case BLASTCONE -> s.variant() == Variant.OUTER ? rand(540, 570) : rand(75, 85);
            case HONEYFRUIT -> rand(360, 390);
            case SCRYER -> s.variant() == Variant.LATE ? 900 : rand(180, 210);
        };
    }

    /** Délai avant le nouveau bourgeon après destruction, en secondes. */
    private static int respawnSeconds(Spot s) {
        return switch (s.type()) {
            case BLASTCONE -> s.variant() == Variant.OUTER ? rand(330, 390) : rand(300, 420);
            case HONEYFRUIT -> rand(330, 420);
            case SCRYER -> s.variant() == Variant.LATE ? rand(90, 120) : rand(200, 260);
        };
    }

    /** Bourgeon dans {@code seconds}, mûr 60 s plus tard. */
    private void schedule(Plant p, int seconds) {
        p.tasks.removeIf(BukkitTask::isCancelled);
        p.tasks.add(new BukkitRunnable() {
            @Override public void run() { setStage(p, 1); }
        }.runTaskLater(LolPlugin.getInstance(), seconds * 20L));
        p.tasks.add(new BukkitRunnable() {
            @Override public void run() { setStage(p, 2); }
        }.runTaskLater(LolPlugin.getInstance(), (seconds + BUD_SECONDS) * 20L));
    }

    private void setStage(Plant p, int stage) {
        if (p.location.getWorld() == null || !live.containsKey(p.location.getWorld().getName())) return;
        p.stage = stage;
        removeMarker(p.markerId);
        p.markerId = null;
        if (stage == 0) return;
        p.location.getChunk(); // le chunk doit être chargé pour poser le marqueur
        ArmorStand stand = (ArmorStand) p.location.getWorld()
                .spawnEntity(p.location.clone().add(0, stage == 1 ? -1.7 : -1.4, 0), EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setMarker(true);
        stand.setInvulnerable(true);
        stand.setPersistent(false);
        stand.setCustomNameVisible(true);
        stand.customName(stage == 1
                ? Component.text("🌱 " + p.spot.type().displayName.substring(p.spot.type().displayName.indexOf(' ') + 1)
                        + " (bourgeon)", NamedTextColor.DARK_GREEN)
                : Component.text(p.spot.type().displayName, p.spot.type().color));
        stand.getEquipment().setHelmet(new ItemStack(stage == 1 ? Material.OAK_SAPLING : p.spot.type().icon));
        stand.getScoreboardTags().add("lol_plant");
        p.markerId = stand.getUniqueId();
    }

    private static void removeMarker(UUID id) {
        if (id == null) return;
        Entity e = org.bukkit.Bukkit.getEntity(id);
        if (e != null) e.remove();
    }

    // ══════════════════════════════════════════════════════════════
    // DESTRUCTION : attaque de base d'un champion
    // ══════════════════════════════════════════════════════════════

    /**
     * Le champion vise-t-il une plante mûre à portée d'attaque ? Si oui, l'attaque la détruit.
     * Appelé par le clic d'attaque (comme les structures).
     */
    public boolean tryAttack(Player attacker) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return false;
        List<Plant> list = live.get(attacker.getWorld().getName());
        if (list == null) return false;
        double range = Math.max(cm.getChampion(attacker).getAutoAttackRange(), 1.5) + 0.8; // + rayon 80
        Location eye = attacker.getEyeLocation();
        Vector dir = eye.getDirection();
        Plant best = null;
        double bd = Double.MAX_VALUE;
        for (Plant p : list) {
            if (p.stage != 2) continue;
            Location c = p.location.clone().add(0, 0.5, 0);
            double d = attacker.getLocation().distance(p.location);
            if (d > range) continue;
            Vector to = c.toVector().subtract(eye.toVector());
            if (to.lengthSquared() > 1e-6 && dir.dot(to.normalize()) < 0.7) continue;
            if (d < bd) { bd = d; best = p; }
        }
        if (best == null) return false;
        var aam = LolPlugin.getInstance().getAutoAttackManager();
        if (!aam.canAutoAttack(attacker)) return true; // visée mais attaque en recharge
        aam.triggerCooldown(attacker);
        destroy(best, attacker);
        return true;
    }

    private void destroy(Plant p, Player attacker) {
        p.stage = 0;
        removeMarker(p.markerId);
        p.markerId = null;
        switch (p.spot.type()) {
            case BLASTCONE -> blast(p, attacker);
            case HONEYFRUIT -> dropFruits(p);
            case SCRYER -> pollen(p, attacker);
        }
        schedule(p, respawnSeconds(p.spot));
    }

    // ── Cône explosif ──────────────────────────────────────────────

    private void blast(Plant p, Player attacker) {
        Location c = p.location;
        World w = c.getWorld();
        if (w == null) return;
        for (LivingEntity e : w.getNearbyLivingEntities(c, BLAST_RADIUS)) {
            if (e instanceof ArmorStand || e.isDead()) continue;
            if (e instanceof Player pl && pl.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            Vector away = e.getLocation().toVector().subtract(c.toVector()).setY(0);
            double dist = away.length();
            if (dist < 0.05) away = attacker.getLocation().getDirection().setY(0).multiply(-1);
            if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
            away.normalize();
            // Atterrissage à 900 unités du cône (projection par-dessus le décor) : vol d'environ
            // 1,5 s avec vy = 1 ; en l'air, Minecraft freine de 9 % par tick → distance ≈ v × 10,4
            double travel = Math.max(1.0, BLAST_DISTANCE - dist);
            e.setVelocity(away.multiply(travel / 10.4).setY(1.0));
            e.setFallDistance(0);
            if (e instanceof Player pl) pl.sendActionBar(Component.text("💥 Projeté par le Cône explosif !", NamedTextColor.RED));
        }
        w.spawnParticle(org.bukkit.Particle.EXPLOSION, c.clone().add(0, 0.5, 0), 1);
        w.playSound(c, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.3f);
    }

    // ── Fruit de miel ──────────────────────────────────────────────

    private void dropFruits(Plant p) {
        World w = p.location.getWorld();
        if (w == null) return;
        List<Fruit> list = fruits.computeIfAbsent(w.getName(), _ -> new ArrayList<>());
        long expires = System.currentTimeMillis() + 30_000L;
        for (int i = 0; i < 5; i++) {
            double a = 2 * Math.PI * i / 5;
            Location l = p.location.clone().add(Math.cos(a) * 1.3, 0, Math.sin(a) * 1.3);
            ArmorStand s = (ArmorStand) w.spawnEntity(l.clone().add(0, -1.6, 0), EntityType.ARMOR_STAND);
            s.setVisible(false); s.setGravity(false); s.setMarker(true); s.setInvulnerable(true);
            s.setPersistent(false);
            s.getEquipment().setHelmet(new ItemStack(Material.HONEY_BOTTLE));
            s.getScoreboardTags().add("lol_plant");
            list.add(new Fruit(l, s.getUniqueId(), expires));
        }
        w.playSound(p.location, org.bukkit.Sound.BLOCK_HONEY_BLOCK_BREAK, 1f, 1f);
        startFruitTask();
    }

    private void startFruitTask() {
        if (fruitTask != null) return;
        fruitTask = new BukkitRunnable() {
            @Override public void run() { tickFruits(); }
        }.runTaskTimer(LolPlugin.getInstance(), 2L, 2L);
    }

    private void tickFruits() {
        var cm = LolPlugin.getInstance().getChampionManager();
        long now = System.currentTimeMillis();
        for (var entry : fruits.entrySet()) {
            World w = org.bukkit.Bukkit.getWorld(entry.getKey());
            Iterator<Fruit> it = entry.getValue().iterator();
            while (it.hasNext()) {
                Fruit f = it.next();
                if (w == null || now >= f.expiresAt()) { removeMarker(f.markerId()); it.remove(); continue; }
                for (Player pl : w.getPlayers()) {
                    if (!cm.hasChampion(pl) || pl.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                    if (pl.getLocation().distanceSquared(f.location()) > FRUIT_PICKUP * FRUIT_PICKUP) continue;
                    eatFruit(pl, cm.getChampion(pl));
                    removeMarker(f.markerId());
                    it.remove();
                    break;
                }
            }
        }
    }

    private void eatFruit(Player p, BaseChampion champ) {
        int lvl = champ.getLevelSystem().getLevel();
        double flat = 14 + 6.0 * (lvl - 1); // 14 → 116 du niveau 1 au niveau 18
        double heal = Math.max(0.035 * champ.getHPSystem().getMaxHP(), flat);
        champ.getHPSystem().heal(heal);
        var rs = champ.getResourceSystem();
        if (rs != null && rs.getType() == ResourceSystem.ResourceType.MANA) rs.addCurrent(0.06 * rs.getMax());
        LolPlugin.getInstance().getCCManager().slowPct(p, 0.35, 5);
        var hud = LolPlugin.getInstance().getHUDManager();
        if (hud != null) hud.updateHUD(p, champ);
        p.sendActionBar(Component.text(String.format("🍯 Fruit de miel : +%.0f PV", heal), NamedTextColor.GOLD));
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EAT, 0.8f, 1.2f);
    }

    // ── Bulbe révélateur ───────────────────────────────────────────

    private void pollen(Plant p, Player attacker) {
        World w = p.location.getWorld();
        if (w == null) return;
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(attacker);
        if (team == null) return;
        Vector axis = p.location.toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (axis.lengthSquared() < 1e-6) axis = attacker.getLocation().getDirection().setY(0);
        if (axis.lengthSquared() < 1e-6) axis = new Vector(1, 0, 0);
        axis.normalize();
        var fog = fr.lolmc.instance.InstanceHelper.fogOfWarManager(attacker);
        int champs = 0;
        for (LivingEntity e : w.getNearbyLivingEntities(p.location, SCRYER_RANGE)) {
            if (e instanceof ArmorStand || e.equals(attacker)) continue;
            Vector to = e.getLocation().toVector().subtract(p.location.toVector()).setY(0);
            if (to.lengthSquared() > 1e-6 && to.clone().normalize().dot(axis) < SCRYER_HALF_ANGLE_COS) continue;
            boolean enemy;
            boolean champion = false;
            if (e instanceof Player pl) {
                enemy = tm.areEnemies(attacker, pl);
                champion = true;
            } else if (MinionManager.isMinion(e)) {
                enemy = MinionManager.getMinionTeam(e) != team;
            } else {
                enemy = JungleManager.isJungleMonster(e); // monstres neutres : révélés aussi
            }
            if (!enemy) continue;
            if (champion) champs++;
            revealUnit(fog, team, e, champion ? 3 : 12);
        }
        // Balises ennemies dans le cône : révélées 12 s
        var wm = LolPlugin.getInstance().getWardManager();
        if (wm != null) for (double d = 3; d <= SCRYER_RANGE; d += 3) {
            Location c = p.location.clone().add(axis.clone().multiply(d));
            double r = Math.max(1.5, Math.tan(Math.acos(SCRYER_HALF_ANGLE_COS)) * d);
            wm.revealEnemyWards(attacker, c, r, 12_000L);
        }
        for (double d = 1; d <= SCRYER_RANGE; d += 1.5)
            w.spawnParticle(org.bukkit.Particle.END_ROD, p.location.clone().add(axis.clone().multiply(d)).add(0, 1, 0), 3, 0.4 * d / 3, 0.2, 0.4 * d / 3, 0);
        attacker.sendActionBar(Component.text("👁 Bulbe révélateur : " + champs + " champion(s) révélé(s)", NamedTextColor.AQUA));
    }

    /** Révèle une unité qui se déplace : une petite zone suit sa position pendant {@code seconds}. */
    private static void revealUnit(FogOfWarManager fog, fr.lolmc.team.TeamManager.Team team, LivingEntity e, int seconds) {
        if (fog == null) return;
        new BukkitRunnable() {
            int left = seconds * 4;
            @Override public void run() {
                if (left-- <= 0 || !e.isValid()) { cancel(); return; }
                fog.revealPoint(team, e.getLocation(), 1.5, 6);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);
    }
}
