package fr.lolmc.ward;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère les wards posées sur la carte avec leur équipe propriétaire.
 *
 * Quand un joueur ennemi entre dans le rayon d'une ward (~9 blocs),
 * il est "révélé" : un faisceau de particules de la couleur de SON équipe
 * apparaît au-dessus de sa tête, visible UNIQUEMENT par l'équipe adverse
 * (celle qui possède la ward). La révélation persiste 5s après qu'il
 * a quitté la zone, et le faisceau suit le joueur.
 */
public class WardManager {

    private final TeamManager teamManager;

    // Wards actives sur la carte
    private final List<Ward> wards = new ArrayList<>();
    // Révélations en cours : UUID joueur révélé → timestamp d'expiration
    private final Map<UUID, Long> revealedUntil = new HashMap<>();
    // Qui révèle qui : UUID révélé → équipe qui le voit
    private final Map<UUID, Team> revealedToTeam = new HashMap<>();

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

        public Ward(Location location, Team team, UUID owner, int durationSeconds) {
            this.location = location;
            this.team = team;
            this.owner = owner;
            this.expireAt = System.currentTimeMillis() + durationSeconds * 1000L;
        }
    }

    /**
     * Pose une ward pour l'équipe d'un joueur.
     */
    public void placeWard(Player owner, Location location, int durationSeconds) {
        Team team = teamManager.getTeam(owner);
        if (team == null) team = Team.BLUE; // fallback
        wards.add(new Ward(location.clone(), team, owner.getUniqueId(), durationSeconds));
    }

    /**
     * Détruit les wards ennemies dans un rayon (Control Ward / Oracle Lens).
     */
    public int destroyEnemyWards(Player player, Location center, double radius) {
        Team myTeam = teamManager.getTeam(player);
        if (myTeam == null) return 0;
        int destroyed = 0;
        Iterator<Ward> it = wards.iterator();
        while (it.hasNext()) {
            Ward w = it.next();
            if (w.team != myTeam && w.location.getWorld().equals(center.getWorld())
                    && w.location.distance(center) <= radius) {
                // Effet de destruction
                w.location.getWorld().spawnParticle(Particle.SMOKE,
                        w.location.clone().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3);
                it.remove();
                destroyed++;
            }
        }
        return destroyed;
    }

    // ── Tâche de détection (toutes les 10 ticks = 0.5s) ───────────

    private void startDetectionTask() {
        new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();

                // Nettoyer les wards expirées
                wards.removeIf(w -> now > w.expireAt);

                // Pour chaque ward, détecter les ennemis dans le rayon
                for (Ward ward : wards) {
                    if (ward.location.getWorld() == null) continue;
                    for (Player p : ward.location.getWorld().getPlayers()) {
                        if (!teamManager.hasTeam(p)) continue;
                        Team pTeam = teamManager.getTeam(p);
                        // Seuls les ENNEMIS de la ward sont révélés
                        if (pTeam == ward.team) continue;
                        if (p.getLocation().distance(ward.location) <= WARD_RADIUS) {
                            // Révéler ce joueur à l'équipe propriétaire de la ward
                            revealedUntil.put(p.getUniqueId(), now + REVEAL_DURATION_MS);
                            revealedToTeam.put(p.getUniqueId(), ward.team);
                        }
                    }
                }

                // Nettoyer les révélations expirées
                revealedUntil.entrySet().removeIf(e -> now > e.getValue());
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
    }

    // ── Tâche du faisceau (toutes les 2 ticks pour fluidité) ──────

    private void startBeamTask() {
        new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, Long> entry : revealedUntil.entrySet()) {
                    if (now > entry.getValue()) continue;
                    UUID revealedId = entry.getKey();
                    Player revealed = Bukkit.getPlayer(revealedId);
                    if (revealed == null || !revealed.isOnline()) continue;

                    Team viewerTeam = revealedToTeam.get(revealedId);
                    if (viewerTeam == null) continue;

                    // Couleur du faisceau = couleur de l'équipe du joueur RÉVÉLÉ
                    Team revealedTeam = teamManager.getTeam(revealed);
                    if (revealedTeam == null) continue;

                    drawBeam(revealed, revealedTeam, viewerTeam);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
    }

    /**
     * Dessine le faisceau (colonne + halo) au-dessus du joueur révélé,
     * envoyé UNIQUEMENT aux joueurs de l'équipe qui le voit.
     */
    private void drawBeam(Player revealed, Team revealedTeam, Team viewerTeam) {
        Location base = revealed.getLocation();
        Particle.DustOptions dust = revealedTeam.dust();

        // Colonne verticale au-dessus de la tête (de +2.5 à +8 blocs)
        List<Location> points = new ArrayList<>();
        for (double y = 2.5; y <= 8.0; y += 0.4) {
            points.add(base.clone().add(0, y, 0));
        }
        // Halo circulaire à la base de la colonne (rayon 0.6)
        double haloY = 2.4;
        for (int i = 0; i < 12; i++) {
            double angle = 2 * Math.PI * i / 12;
            points.add(base.clone().add(Math.cos(angle) * 0.6, haloY, Math.sin(angle) * 0.6));
        }

        // Envoyer les particules UNIQUEMENT aux membres de l'équipe qui voit
        for (UUID viewerId : teamManager.getTeamMembers(viewerTeam)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) continue;
            if (!viewer.getWorld().equals(revealed.getWorld())) continue;
            for (Location point : points) {
                viewer.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    public boolean isRevealed(Player player) {
        Long until = revealedUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    public int getActiveWardCount() {
        return wards.size();
    }

    public void cleanup(UUID uuid) {
        revealedUntil.remove(uuid);
        revealedToTeam.remove(uuid);
        wards.removeIf(w -> w.owner.equals(uuid));
    }
}
