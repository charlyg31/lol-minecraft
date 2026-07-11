package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.WorldContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Plantes de jungle fidèles à LoL :
 *  - HONEYFRUIT   : soigne le joueur qui la cueille (et ralentit légèrement, comme le miel)
 *  - BLASTCONE    : propulse les joueurs proches (saut/déplacement)
 *  - SCRYER       : révèle (glow) les ennemis proches pendant quelques secondes
 *
 * Les plantes ne sont PAS des monstres : positions fixes, déclenchées au clic droit
 * à proximité, puis repoussent après un délai. Indépendant du JungleManager.
 *
 * Câblage : instancier dans LolPlugin, enregistrer comme Listener, appeler spawnAll()
 * au démarrage de partie et clearAll() à la fin. Positions via /lol plant (voir patch).
 */
public class PlantManager implements Listener {

    public enum PlantType {
        HONEYFRUIT (Material.HONEYCOMB,        "🍯 Fruit de Miel",      30),
        BLASTCONE  (Material.FIRE_CHARGE,      "💥 Cône Explosif",      60),
        SCRYER     (Material.ENDER_EYE,        "👁 Fleur du Voyant",    40);

        public final Material icon;
        public final String displayName;
        public final int regrowSeconds;

        PlantType(Material icon, String displayName, int regrowSeconds) {
            this.icon = icon;
            this.displayName = displayName;
            this.regrowSeconds = regrowSeconds;
        }
    }

    /** Une plante posée sur la carte. */
    public static class Plant {
        public final String id;
        public final PlantType type;
        public final Location location;
        public boolean alive = false;
        public UUID markerId = null; // ArmorStand visuel

        public Plant(String id, PlantType type, Location location) {
            this.id = id;
            this.type = type;
            this.location = location.clone();
        }
    }

    private static final double TRIGGER_RADIUS = 3.0;   // distance pour cueillir
    private static final double EFFECT_RADIUS  = 4.0;   // rayon d'effet de zone

    private final Map<String, Plant> plants = new HashMap<>();
    private int counter = 0;

    // ──────────────────────────────────────────────────────────────
    // CONFIGURATION DES POSITIONS
    // ──────────────────────────────────────────────────────────────

    /** Ajoute une plante à une position (depuis la commande admin /lol plant). */
    public Plant addPlant(PlantType type, Location loc) {
        String id = type.name().toLowerCase() + "_" + (++counter);
        Plant p = new Plant(id, type, loc);
        plants.put(id, p);
        return p;
    }

    public Collection<Plant> getPlants() { return plants.values(); }

    public void clearConfig() { plants.clear(); counter = 0; }

    // ──────────────────────────────────────────────────────────────
    // CYCLE DE VIE
    // ──────────────────────────────────────────────────────────────

    /** Fait pousser toutes les plantes configurées (début de partie). */
    public void spawnAll() {
        for (Plant p : plants.values()) growPlant(p);
    }

    /** Retire tous les marqueurs (fin de partie). */
    public void clearAll() {
        for (Plant p : plants.values()) removeMarker(p);
    }

    private void growPlant(Plant p) {
        p.alive = true;
        if (p.location.getWorld() == null) return;
        if (!p.location.isChunkLoaded()) return; // zone déchargée : on repoussera quand chargé

        ArmorStand stand = (ArmorStand) p.location.getWorld()
                .spawnEntity(p.location.clone().add(0, -1.4, 0), EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setMarker(true);
        stand.setInvulnerable(true);
        stand.setCustomNameVisible(true);
        stand.customName(Component.text(p.type.displayName,
                p.type == PlantType.HONEYFRUIT ? NamedTextColor.GOLD
                        : p.type == PlantType.BLASTCONE ? NamedTextColor.RED
                        : NamedTextColor.AQUA));
        if (stand.getEquipment() != null)
            stand.getEquipment().setHelmet(new ItemStack(p.type.icon));
        stand.getScoreboardTags().add("lol_plant");
        p.markerId = stand.getUniqueId();
    }

    private void removeMarker(Plant p) {
        if (p.markerId != null) {
            var e = org.bukkit.Bukkit.getEntity(p.markerId);
            if (e != null) e.remove();
            p.markerId = null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // DÉCLENCHEMENT
    // ──────────────────────────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!e.getAction().name().startsWith("RIGHT_CLICK")) return;
        Player player = e.getPlayer();
        if (!WorldContext.isInGameWorld(player)) return;

        Plant target = nearestAlivePlant(player.getLocation());
        if (target == null) return;

        consume(target, player);
    }

    private Plant nearestAlivePlant(Location loc) {
        Plant best = null;
        double bestD = TRIGGER_RADIUS * TRIGGER_RADIUS;
        for (Plant p : plants.values()) {
            if (!p.alive) continue;
            if (p.location.getWorld() == null
                    || !p.location.getWorld().equals(loc.getWorld())) continue;
            double d = p.location.distanceSquared(loc);
            if (d <= bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private void consume(Plant p, Player picker) {
        p.alive = false;
        removeMarker(p);

        switch (p.type) {
            case HONEYFRUIT -> applyHoney(p, picker);
            case BLASTCONE  -> applyBlast(p, picker);
            case SCRYER     -> applyScryer(p, picker);
        }

        // Repousse après le délai
        new BukkitRunnable() {
            @Override public void run() { growPlant(p); }
        }.runTaskLater(LolPlugin.getInstance(), p.type.regrowSeconds * 20L);
    }

    // ── Effets ────────────────────────────────────────────────────

    private void applyHoney(Plant p, Player picker) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (cm.hasChampion(picker)) {
            BaseChampion champ = cm.getChampion(picker);
            double heal = champ.getHPSystem().getMaxHP() * 0.10; // 10% PV max
            champ.getHPSystem().heal(heal);
            var hud = LolPlugin.getInstance().getHUDManager();
            if (hud != null) hud.updateHUD(picker, champ);
        }
        picker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, false, true));
        picker.sendActionBar(Component.text("🍯 Fruit de Miel : soigné mais ralenti.", NamedTextColor.GOLD));
        if (p.location.getWorld() != null)
            fr.lolmc.util.VisualEffectUtil.impactBurst(p.location.getWorld(),
                    p.location, Material.HONEY_BLOCK, 0.28f, 0.5, 8, 8L);
    }

    private void applyBlast(Plant p, Player picker) {
        Location c = p.location;
        if (c.getWorld() == null) return;
        for (Player pl : c.getWorld().getPlayers()) {
            if (pl.getLocation().distanceSquared(c) > EFFECT_RADIUS * EFFECT_RADIUS) continue;
            Vector away = pl.getLocation().toVector().subtract(c.toVector());
            if (away.lengthSquared() < 0.01) away = new Vector(0, 1, 0);
            away.normalize().multiply(0.9).setY(1.0);
            pl.setVelocity(away);
        }
        picker.sendActionBar(Component.text("💥 Cône Explosif : propulsion !", NamedTextColor.RED));
        fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                c, Material.ORANGE_STAINED_GLASS, 0.35f, 0.8, 10, 6L);
        c.getWorld().playSound(c, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.3f);
    }

    private void applyScryer(Plant p, Player picker) {
        var tm = LolPlugin.getInstance().getTeamManager();
        if (p.location.getWorld() == null) return;
        int revealed = 0;
        for (Player enemy : p.location.getWorld().getPlayers()) {
            if (enemy.equals(picker)) continue;
            if (!tm.areEnemies(picker, enemy)) continue;
            if (enemy.getLocation().distanceSquared(p.location) > 12 * 12) continue;
            enemy.setGlowing(true);
            revealed++;
            final Player fe = enemy;
            new BukkitRunnable() {
                @Override public void run() { if (fe.isOnline()) fe.setGlowing(false); }
            }.runTaskLater(LolPlugin.getInstance(), 4 * 20L);
        }
        picker.sendActionBar(Component.text(
                "👁 Fleur du Voyant : " + revealed + " ennemi(s) révélé(s).", NamedTextColor.AQUA));
    }
}
