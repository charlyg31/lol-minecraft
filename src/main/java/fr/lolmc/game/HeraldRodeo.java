package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.VirtualHP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rodéo du Héraut (wiki, patch 26) : un champion allié monte sur le Héraut invoqué et le dirige
 * dans une charge.
 *  - Montée : clic droit sur le Héraut à 6 blocs ou moins, 0.75 s de préparation ; un seul
 *    cavalier, impossible pendant un bond sur une structure ou sous contrôle. Une fois à
 *    l'invocation, puis une fois par tour ennemie détruite à proximité (wiki et texte de l'Œil).
 *  - Charge : 0.75 s pour choisir la direction (le regard du cavalier), puis jusqu'à 12.5 s.
 *    Vitesse 150 → 600 (exponentielle sur 5 s), jusqu'à +75 % vers une structure ennemie (max
 *    1050). Le Héraut tourne vers le regard du cavalier, vite pendant 3 s puis de moins en moins
 *    (divisé par 2 toutes les 2 s).
 *  - Cavalier : invulnérable, ne peut ni lancer de sort ni attaquer, ne peut pas descendre.
 *  - Champions percutés : 250 dégâts bruts, projetés et étourdis 1 s.
 *  - Structure : dégâts du bond du Héraut (3000 bruts, dégressifs) attribués au cavalier,
 *    bouclier de 550 sur 4 s (décroît jusqu'à 50 %), 5 Voidmites + 1 par larve tuée (3 max).
 *    Le Héraut perd 66 % de ses PV actuels (13.2 % contre un mur).
 *  - Fin : le cavalier est éjecté de 2 blocs (7 contre une structure) + 75 % de sa portée.
 * Minecraft ne sait pas diriger une monture non chevauchable : le Héraut est déplacé par vélocité
 * chaque tick, dans la direction calculée à partir du regard du cavalier.
 */
public final class HeraldRodeo implements Listener {

    /** Héraut invoqué : état partagé avec son IA (JungleManager.summonHerald). */
    public static final class Summoned {
        final Ravager herald;
        final Team team;
        final double ad;
        final MapManager mm;
        double leapMult = 1.0;
        boolean leaping = false;
        boolean rodeoReady = true;
        UUID rider = null;
        Summoned(Ravager herald, Team team, double ad, MapManager mm) {
            this.herald = herald; this.team = team; this.ad = ad; this.mm = mm;
        }
        /** Monté (ou en cours de montée) : l'IA du Héraut ne doit plus le diriger. */
        boolean ridden() { return rider != null; }
    }

    private static final Map<UUID, Summoned> SUMMONED = new ConcurrentHashMap<>();
    private static final Set<UUID> RIDERS = ConcurrentHashMap.newKeySet();

    private static final double MOUNT_RANGE = 6.0;
    private static final int WINDUP_TICKS = 15;      // 0.75 s
    private static final int MAX_CHARGE_TICKS = 250; // 12.5 s

    static Summoned register(Ravager herald, Team team, double ad, MapManager mm) {
        Summoned s = new Summoned(herald, team, ad, mm);
        SUMMONED.put(herald.getUniqueId(), s);
        return s;
    }

    static void unregister(UUID heraldId) { SUMMONED.remove(heraldId); }

    /** Cavalier en charge : invulnérable et incapable d'agir. */
    public static boolean isRiding(UUID player) { return RIDERS.contains(player); }

    // ── Montée ──────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Summoned s = SUMMONED.get(e.getRightClicked().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        var plugin = LolPlugin.getInstance();
        if (plugin.getTeamManager().getTeam(p) != s.team || !plugin.getChampionManager().hasChampion(p)) return;
        String refusal = s.ridden() ? "Le Héraut a déjà un cavalier"
                : s.leaping ? "Le Héraut attaque une structure"
                : !s.rodeoReady ? "Une tour ennemie proche doit d'abord tomber"
                : p.getLocation().distance(s.herald.getLocation()) > MOUNT_RANGE ? "Trop loin du Héraut"
                : !plugin.getCCManager().canCastAbility(p.getUniqueId()) ? "Impossible sous contrôle"
                : null;
        if (refusal != null) {
            p.sendActionBar(Component.text("❌ " + refusal, NamedTextColor.RED));
            return;
        }
        s.rider = p.getUniqueId();
        s.rodeoReady = false;
        p.sendActionBar(Component.text("👁 Montée sur le Héraut…", NamedTextColor.LIGHT_PURPLE));
        new BukkitRunnable() {
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || !s.herald.isValid() || s.herald.isDead() || s.leaping
                        || p.getLocation().distance(s.herald.getLocation()) > MOUNT_RANGE + 2) {
                    s.rider = null;
                    s.rodeoReady = true; // montée interrompue : pas consommée
                    return;
                }
                mount(s, p);
            }
        }.runTaskLater(LolPlugin.getInstance(), WINDUP_TICKS);
    }

    private static void mount(Summoned s, Player p) {
        Ravager h = s.herald;
        h.getPathfinder().stopPathfinding();
        h.setTarget(null);
        h.setAware(false); // plus d'IA vanilla : déplacé à la main
        h.addPassenger(p);
        RIDERS.add(p.getUniqueId());
        p.setInvulnerable(true);
        h.getWorld().playSound(h.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.5f, 1.2f);
        p.sendActionBar(Component.text("👁 Vise avec la souris : la charge part dans 0.75 s",
                NamedTextColor.LIGHT_PURPLE));
        new Charge(s, p).runTaskTimer(LolPlugin.getInstance(), 1L, 1L);
    }

    // ── Charge ──────────────────────────────────────────────────

    private static final class Charge extends BukkitRunnable {
        final Summoned s;
        final Player rider;
        final Set<UUID> hitChampions = new HashSet<>();
        int ticks = 0, stuck = 0;
        float heading;
        Location last;
        double lastStep = 0;

        Charge(Summoned s, Player rider) {
            this.s = s; this.rider = rider;
            this.heading = rider.getLocation().getYaw();
        }

        @Override public void run() {
            Ravager h = s.herald;
            if (!h.isValid() || h.isDead()) {
                Location at = last != null ? last : h.getLocation();
                end(Outcome.HERALD_DIED, at);
                return;
            }
            if (!rider.isOnline() || rider.isDead()) { end(Outcome.EXPIRED, h.getLocation()); return; }
            ticks++;
            // Préparation : le Héraut s'oriente selon le regard du cavalier
            if (ticks <= WINDUP_TICKS) {
                heading = rider.getLocation().getYaw();
                h.setRotation(heading, 0);
                last = h.getLocation();
                return;
            }
            double t = (ticks - WINDUP_TICKS) / 20.0;
            if (t >= MAX_CHARGE_TICKS / 20.0) { end(Outcome.EXPIRED, h.getLocation()); return; }

            // Pilotage : rotation bornée vers le regard du cavalier
            float turn = t < 3 ? 10f : (float) (10 * Math.pow(0.5, Math.floor((t - 3) / 2) + 1));
            float diff = wrap(rider.getLocation().getYaw() - heading);
            heading = wrap(heading + Math.max(-turn, Math.min(turn, diff)));
            double rad = Math.toRadians(heading);
            Vector dir = new Vector(-Math.sin(rad), 0, Math.cos(rad));

            // Vitesse (unités LoL / s) : 150 → 600 en 5 s, bonus vers une structure ennemie
            double speed = Math.min(600, 150 * Math.pow(4, t / 5));
            GameStructure ahead = JungleManager.nearestEnemyStructure(s.mm, s.team, h.getLocation(), 25);
            if (ahead != null) {
                Vector to = ahead.getCenter().toVector().subtract(h.getLocation().toVector()).setY(0);
                if (to.lengthSquared() > 1e-6) speed *= 1 + 0.75 * Math.max(0, to.normalize().dot(dir));
            }
            speed = Math.min(1050, speed);
            double step = speed / 100.0 / 20.0; // blocs par tick

            // Mur : le Héraut n'avance plus alors qu'il devrait
            Location now = h.getLocation();
            if (last != null && lastStep > 0) {
                double moved = Math.hypot(now.getX() - last.getX(), now.getZ() - last.getZ());
                stuck = moved < lastStep * 0.35 ? stuck + 1 : 0;
                if (stuck >= 3 && ticks > WINDUP_TICKS + 5) { end(Outcome.TERRAIN, now); return; }
            }
            last = now.clone();
            lastStep = step;

            h.setRotation(heading, 0);
            h.setVelocity(dir.clone().multiply(step).setY(h.getVelocity().getY()));

            hitChampions(now);
            GameStructure hit = JungleManager.nearestEnemyStructure(s.mm, s.team, now, 3.5);
            if (hit != null) { structureHit(hit); end(Outcome.STRUCTURE, now); }
        }

        /** Champions ennemis percutés (une fois chacun) : 250 bruts, projetés, étourdis 1 s. */
        private void hitChampions(Location at) {
            var plugin = LolPlugin.getInstance();
            var tm = plugin.getTeamManager();
            var cc = plugin.getCCManager();
            for (Player p : at.getWorld().getPlayers()) {
                if (p.equals(rider) || hitChampions.contains(p.getUniqueId())) continue;
                if (tm.getTeam(p) == s.team || !plugin.getChampionManager().hasChampion(p)) continue;
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getLocation().distanceSquared(at) > 1.8 * 1.8) continue;
                hitChampions.add(p.getUniqueId());
                DamageUtil.damage(rider, p, 250, true, DamageUtil.Type.TRUE);
                if (cc != null) { cc.setAirborne(p, 20); cc.stun(p, 20); }
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ATTACK, 1f, 0.8f);
            }
        }

        /** Impact sur une structure : dégâts du bond attribués au cavalier, bouclier, Voidmites. */
        private void structureHit(GameStructure ft) {
            var plugin = LolPlugin.getInstance();
            boolean phaseChanged = ft.takeDamage(3000 * s.leapMult);
            s.leapMult *= 2.0 / 3.0;
            Location c = ft.getCenter();
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(), c.clone().add(0, 1, 0),
                    Material.PURPLE_STAINED_GLASS, 0.4f, 1.0, 12, 8L);
            c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
            if (phaseChanged) s.mm.updateStructurePhase(ft);
            if (ft.isDestroyed()) {
                var sdl = plugin.getStructureDamageListener();
                if (sdl != null) sdl.onStructureDestroyedBy(ft, rider);
            }
            shield(rider);
            var jm = fr.lolmc.instance.InstanceHelper.jungleManager(rider);
            var mm = fr.lolmc.instance.InstanceHelper.minionManager(rider);
            if (mm != null) {
                int mites = 5 + (jm != null ? Math.min(3, jm.getVoidgrubKills(s.team)) : 0);
                for (int i = 0; i < mites; i++) mm.spawnVoidmite(s.herald.getLocation(), s.team);
            }
        }

        private void end(Outcome outcome, Location at) {
            cancel();
            Ravager h = s.herald;
            RIDERS.remove(rider.getUniqueId()); // avant removePassenger : la descente n'est plus bloquée
            s.rider = null;
            if (h.isValid()) {
                h.removePassenger(rider);
                h.setVelocity(new Vector());
                h.setAware(true);
                double cur = VirtualHP.getCurrent(h);
                if (outcome == Outcome.STRUCTURE) VirtualHP.damage(h, cur * 0.66, null);
                else if (outcome == Outcome.TERRAIN) VirtualHP.damage(h, cur * 0.132, null);
            }
            // Invulnérabilité retirée 0.1 s après la fin
            LolPlugin.getInstance().getServer().getScheduler().runTaskLater(LolPlugin.getInstance(),
                    () -> rider.setInvulnerable(false), 2L);
            if (!rider.isOnline() || rider.isDead()) return;
            if (outcome == Outcome.HERALD_DIED) { rider.teleport(at.clone().setDirection(rider.getLocation().getDirection())); return; }
            // Éjection vers l'arrière : 2 blocs (7 après une structure) + 75 % de la portée d'attaque
            var cm = LolPlugin.getInstance().getChampionManager();
            double range = cm.hasChampion(rider) ? cm.getChampion(rider).getAutoAttackRange() : 0;
            double dist = (outcome == Outcome.STRUCTURE ? 7 : 2) + 0.75 * range;
            double rad = Math.toRadians(heading);
            Vector back = new Vector(Math.sin(rad), 0, -Math.cos(rad));
            Location dest = at.clone();
            for (double d = 0.5; d <= dist; d += 0.5) {
                Location next = at.clone().add(back.clone().multiply(d));
                if (!next.getBlock().isPassable() || !next.clone().add(0, 1, 0).getBlock().isPassable()) break;
                dest = next;
            }
            dest.setDirection(rider.getLocation().getDirection());
            rider.teleport(dest);
        }
    }

    private enum Outcome { STRUCTURE, TERRAIN, EXPIRED, HERALD_DIED }

    /** Bouclier de 550 pendant 4 s, qui décroît linéairement jusqu'à 50 % avant d'expirer. */
    private static void shield(Player p) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        var stats = cm.getChampion(p).getStats();
        stats.addShield(550);
        new BukkitRunnable() {
            double left = 550;
            int steps = 0;
            @Override public void run() {
                steps++;
                // 8 paliers de 0.5 s retirent 275 au total, le dernier retire le reste
                double remove = steps < 8 ? 275.0 / 8 : left;
                remove = Math.min(remove, Math.min(left, stats.getShield()));
                stats.addShield(-remove);
                left -= remove;
                if (steps >= 8 || left <= 0) cancel();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 10L, 10L);
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    // ── Descente et déconnexion ─────────────────────────────────

    /** Pendant la charge, le cavalier ne peut pas descendre (sneak). */
    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (RIDERS.contains(e.getEntity().getUniqueId()) && SUMMONED.containsKey(e.getDismounted().getUniqueId())
                && e.getDismounted().isValid() && !e.getDismounted().isDead())
            e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (RIDERS.remove(id)) {
            e.getPlayer().setInvulnerable(false);
            e.getPlayer().leaveVehicle();
        }
    }
}
