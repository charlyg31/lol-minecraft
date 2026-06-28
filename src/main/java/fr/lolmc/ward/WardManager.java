package fr.lolmc.ward;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère les wards posées sur la carte avec leur équipe propriétaire.
 *
 * Les wards sont des ArmorStand invisibles. Le nom flottant est visible
 * uniquement par les alliés (hideEntity sur les ennemis). Une ward furtive
 * peut être révélée par Control Ward ou Oracle Lens — l'entité devient alors
 * visible pour les ennemis qui peuvent la détruire en cliquant dessus.
 */
public class WardManager {

    private final TeamManager teamManager;
    private final List<Ward> wards = new ArrayList<>();
    private final Map<UUID, Long> revealedUntil = new HashMap<>();
    private final Map<UUID, Team> revealedToTeam = new HashMap<>();
    // Wards révélées aux ennemis : UUID entité → timestamp fin de révélation
    private final Map<UUID, Long> revealedWards = new HashMap<>();

    private static final double WARD_RADIUS = 9.0;
    private static final long REVEAL_DURATION_MS = 5000L;

    public WardManager(TeamManager teamManager) {
        this.teamManager = teamManager;
        startDetectionTask();
        startBeamTask();
    }

    // ── Une ward posée ────────────────────────────────────────────

    public static class Ward {
        public final Location location;
        public final Team team;
        public final UUID owner;
        public final long expireAt;
        public final UUID entityId; // ArmorStand représentant la ward

        public Ward(Location location, Team team, UUID owner, int durationSeconds, UUID entityId) {
            this.location = location;
            this.team = team;
            this.owner = owner;
            this.expireAt = System.currentTimeMillis() + durationSeconds * 1000L;
            this.entityId = entityId;
        }
    }

    /** Pose une ward pour l'équipe d'un joueur avec entité associée. */
    public void placeWard(Player owner, Location location, int durationSeconds, UUID entityId) {
        Team team = teamManager.getTeam(owner);
        if (team == null) team = Team.BLUE;
        Ward ward = new Ward(location.clone(), team, owner.getUniqueId(), durationSeconds, entityId);
        wards.add(ward);
        if (entityId != null) applyWardVisibility(ward, false);
    }

    /** Compatibilité ancienne signature sans entityId. */
    public void placeWard(Player owner, Location location, int durationSeconds) {
        placeWard(owner, location, durationSeconds, null);
    }

    /**
     * Applique la visibilité de la ward selon l'équipe.
     * revealed=true → visible par TOUS les joueurs (Control Ward / Oracle Lens).
     * revealed=false → visible uniquement par les alliés.
     */
    public void applyWardVisibility(Ward ward, boolean revealed) {
        Entity ent = ward.entityId != null ? Bukkit.getEntity(ward.entityId) : null;
        if (ent == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!teamManager.hasTeam(p)) continue;
            boolean ally = teamManager.getTeam(p) == ward.team;
            if (ally || revealed) {
                p.showEntity(LolPlugin.getInstance(), ent);
            } else {
                p.hideEntity(LolPlugin.getInstance(), ent);
            }
        }
    }

    /**
     * Révèle les wards ennemies dans un rayon (Oracle Lens / Control Ward).
     * Elles deviennent visibles par les ennemis et peuvent être détruites.
     */
    public int revealEnemyWards(Player player, Location center, double radius, long durMs) {
        Team myTeam = teamManager.getTeam(player);
        if (myTeam == null) return 0;
        int count = 0;
        for (Ward w : wards) {
            if (w.team == myTeam) continue;
            if (w.location.getWorld() == null || !w.location.getWorld().equals(center.getWorld())) continue;
            if (w.location.distance(center) > radius) continue;
            applyWardVisibility(w, true);
            revealedWards.put(w.entityId, System.currentTimeMillis() + durMs);
            center.getWorld().spawnParticle(Particle.CRIT, w.location.clone().add(0,0.5,0), 10, 0.3,0.3,0.3);
            count++;
        }
        return count;
    }

    /** Détruit les wards ennemies dans un rayon (Control Ward). */
    public int destroyEnemyWards(Player player, Location center, double radius) {
        Team myTeam = teamManager.getTeam(player);
        if (myTeam == null) return 0;
        int destroyed = 0;
        Iterator<Ward> it = wards.iterator();
        while (it.hasNext()) {
            Ward w = it.next();
            if (w.team == myTeam) continue;
            if (w.location.getWorld() == null || !w.location.getWorld().equals(center.getWorld())) continue;
            if (w.location.distance(center) > radius) continue;
            removeWardEntity(w);
            it.remove();
            destroyed++;
        }
        return destroyed;
    }

    /**
     * Détruit la ward dont l'entité correspond à entityId.
     * Seul un ennemi peut détruire, et seulement si la ward est révélée.
     */
    public boolean destroyWardByEntity(UUID entityId, Player attacker) {
        Team myTeam = teamManager.getTeam(attacker);
        Iterator<Ward> it = wards.iterator();
        while (it.hasNext()) {
            Ward w = it.next();
            if (!entityId.equals(w.entityId)) continue;
            if (w.team == myTeam) return false; // pas sa propre ward
            Long rev = revealedWards.get(entityId);
            if (rev == null || System.currentTimeMillis() > rev) {
                attacker.sendActionBar(Component.text("👁 Ward non révélée! Utilisez Oracle Lens ou Control Ward.", NamedTextColor.GRAY));
                return false;
            }
            removeWardEntity(w);
            it.remove();
            attacker.sendActionBar(Component.text("💥 Ward ennemie détruite!", NamedTextColor.GREEN));
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.5f);
            // Tracker dans le scoreboard
            var msb = fr.lolmc.LolPlugin.getInstance().getMatchScoreboard();
            if (msb != null) msb.addWardKilled(attacker);
            return true;
        }
        return false;
    }

    private void removeWardEntity(Ward w) {
        if (w.location.getWorld() != null)
            w.location.getWorld().spawnParticle(Particle.SMOKE, w.location.clone().add(0,0.5,0), 15, 0.3,0.3,0.3);
        if (w.entityId != null) {
            Entity ent = Bukkit.getEntity(w.entityId);
            if (ent != null) ent.remove();
        }
        revealedWards.remove(w.entityId);
    }

    // ── Tâche de détection (toutes les 10 ticks = 0.5s) ───────────

    private void startDetectionTask() {
        new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();

                // Nettoyer les wards expirées
                wards.removeIf(w -> {
                    if (now > w.expireAt) { removeWardEntity(w); return true; }
                    return false;
                });

                // Révélations de wards expirées → re-masquer aux ennemis
                revealedWards.entrySet().removeIf(e -> {
                    if (now > e.getValue()) {
                        wards.stream().filter(w -> e.getKey().equals(w.entityId))
                             .findFirst().ifPresent(w -> applyWardVisibility(w, false));
                        return true;
                    }
                    return false;
                });

                // Détecter les ennemis dans les rayons des wards
                for (Ward ward : wards) {
                    if (ward.location.getWorld() == null) continue;
                    for (Player p : ward.location.getWorld().getPlayers()) {
                        if (!teamManager.hasTeam(p)) continue;
                        if (teamManager.getTeam(p) == ward.team) continue;
                        if (p.getLocation().distance(ward.location) <= WARD_RADIUS) {
                            boolean wasRevealed = revealedUntil.containsKey(p.getUniqueId())
                                    && now < revealedUntil.get(p.getUniqueId());
                            revealedUntil.put(p.getUniqueId(), now + REVEAL_DURATION_MS);
                            revealedToTeam.put(p.getUniqueId(), ward.team);
                            if (!wasRevealed) playDetectionSound(ward.team);
                        }
                    }
                }
                revealedUntil.entrySet().removeIf(e -> now > e.getValue());
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
    }

    private void playDetectionSound(Team team) {
        for (UUID memberId : teamManager.getTeamMembers(team)) {
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline()) continue;
            member.playSound(member.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
            member.sendActionBar(Component.text("👁 Ennemi détecté par une ward!", NamedTextColor.YELLOW));
        }
    }

    // ── Tâche du faisceau (toutes les 2 ticks) ────────────────────

    private void startBeamTask() {
        new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, Long> entry : revealedUntil.entrySet()) {
                    if (now > entry.getValue()) continue;
                    Player revealed = Bukkit.getPlayer(entry.getKey());
                    if (revealed == null || !revealed.isOnline()) continue;
                    Team viewerTeam = revealedToTeam.get(entry.getKey());
                    if (viewerTeam == null) continue;
                    Team revealedTeam = teamManager.getTeam(revealed);
                    if (revealedTeam == null) continue;
                    drawBeam(revealed, revealedTeam, viewerTeam);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
    }

    private void drawBeam(Player revealed, Team revealedTeam, Team viewerTeam) {
        Location base = revealed.getLocation();
        Particle.DustOptions dust = new Particle.DustOptions(revealedTeam.particleColor, 3.0f);
        List<Location> points = new ArrayList<>();
        for (double y = 2.5; y <= 12.0; y += 0.25) {
            points.add(base.clone().add(0, y, 0));
            points.add(base.clone().add(0.25, y, 0));
            points.add(base.clone().add(-0.25, y, 0));
            points.add(base.clone().add(0, y, 0.25));
            points.add(base.clone().add(0, y, -0.25));
        }
        for (double r : new double[]{0.6, 1.0}) {
            int cnt = r > 0.8 ? 20 : 14;
            for (int i = 0; i < cnt; i++) {
                double a = 2 * Math.PI * i / cnt;
                points.add(base.clone().add(Math.cos(a)*r, 2.4, Math.sin(a)*r));
            }
        }
        double spin = (System.currentTimeMillis() % 2000L) / 2000.0 * 2 * Math.PI;
        for (int i = 0; i < 8; i++) {
            double a = spin + 2 * Math.PI * i / 8;
            points.add(base.clone().add(Math.cos(a)*0.8, 6.0, Math.sin(a)*0.8));
        }
        for (UUID viewerId : teamManager.getTeamMembers(viewerTeam)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline() || !viewer.getWorld().equals(revealed.getWorld())) continue;
            for (Location point : points)
                viewer.spawnParticle(Particle.DUST, point, 2, 0.05, 0.05, 0.05, 0, dust);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    public boolean isRevealed(Player player) {
        Long until = revealedUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    public int getActiveWardCount() { return wards.size(); }

    public void cleanup(UUID uuid) {
        revealedUntil.remove(uuid);
        revealedToTeam.remove(uuid);
        wards.removeIf(w -> w.owner.equals(uuid));
    }
}
