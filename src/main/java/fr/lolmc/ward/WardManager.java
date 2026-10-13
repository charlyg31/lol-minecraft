package fr.lolmc.ward;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère les wards posées sur la carte avec leur équipe propriétaire (règles du wiki LoL).
 *
 * <ul>
 *   <li>Ward furtive : 900 unités de vision, 90 à 120 s (niveau moyen), 3 coups, 3 posées max
 *       par joueur (la plus ancienne est remplacée), invisible pour les ennemis sauf révélée.</li>
 *   <li>Control Ward : 900 unités, durée illimitée, 4 coups, 1 posée max, visible ; révèle et
 *       désactive les wards ennemies dans sa portée (une ward désactivée ne donne plus de vision).</li>
 *   <li>Ward de vision lointaine : 500 unités, durée illimitée, 1 coup, visible, sans limite.</li>
 * </ul>
 * Une ward ne voit dans un bush que si elle est posée dans ce bush.
 */
public class WardManager {

    public enum WardType {
        STEALTH(9.0, 3, 10), CONTROL(9.0, 4, 30), FARSIGHT(5.0, 1, 15);

        /** Rayon de vision en blocs (900 / 500 unités LoL, 100 unités = 1 bloc). */
        public final double radius;
        public final int hits;
        public final int goldBounty;

        WardType(double radius, int hits, int goldBounty) {
            this.radius = radius; this.hits = hits; this.goldBounty = goldBounty;
        }
    }

    private final TeamManager teamManager;
    private final List<Ward> wards = new ArrayList<>();
    private final Map<UUID, Long> revealedUntil = new HashMap<>();
    private final Map<UUID, Team> revealedToTeam = new HashMap<>();
    /** BlockDisplay du faisceau de révélation par joueur révélé (10 segments + 1 anneau). */
    private final Map<UUID, java.util.List<org.bukkit.entity.BlockDisplay>> beamDisplays = new HashMap<>();
    // Wards révélées aux ennemis (Oracle Lens) : UUID entité → timestamp fin de révélation
    private final Map<UUID, Long> revealedWards = new HashMap<>();
    // Anti double-coup (le client envoie deux paquets d'interaction sur un ArmorStand)
    private final Map<String, Long> lastHit = new HashMap<>();

    /** Rayon de vision d'une ward furtive / Control Ward, en blocs. */
    public static final double WARD_RADIUS = WardType.STEALTH.radius;
    // La vision d'une ward s'arrête quand on quitte sa portée (tâche toutes les 0,5 s)
    private static final long REVEAL_DURATION_MS = 750L;
    private static final int MAX_STEALTH_PER_PLAYER = 3;
    private static final int MAX_CONTROL_PER_PLAYER = 1;

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
        public final long expireAt;   // Long.MAX_VALUE = illimitée
        public final UUID entityId;   // ArmorStand représentant la ward (null : ward sans entité)
        public final WardType type;
        public final boolean countsToLimit;
        public int hitsLeft;

        public Ward(Location location, Team team, UUID owner, int durationSeconds, UUID entityId,
                    WardType type, boolean countsToLimit) {
            this.location = location;
            this.team = team;
            this.owner = owner;
            this.expireAt = durationSeconds <= 0 ? Long.MAX_VALUE
                    : System.currentTimeMillis() + durationSeconds * 1000L;
            this.entityId = entityId;
            this.type = type;
            this.countsToLimit = countsToLimit;
            this.hitsLeft = type.hits;
        }
    }

    /**
     * Pose une ward. {@code durationSeconds} ≤ 0 = durée illimitée. Au-delà de la limite du
     * joueur (3 furtives, 1 Control Ward), sa ward la plus ancienne de ce type est retirée.
     */
    public void placeWard(Player owner, Location location, int durationSeconds, UUID entityId, WardType type) {
        Team team = teamManager.getTeam(owner);
        if (team == null) team = Team.BLUE;
        int max = type == WardType.STEALTH ? MAX_STEALTH_PER_PLAYER
                : type == WardType.CONTROL ? MAX_CONTROL_PER_PLAYER : Integer.MAX_VALUE;
        List<Ward> mine = new ArrayList<>();
        for (Ward w : wards)
            if (w.countsToLimit && w.type == type && w.owner.equals(owner.getUniqueId())) mine.add(w);
        while (mine.size() >= max) {
            Ward oldest = mine.removeFirst(); // liste dans l'ordre de pose
            removeWardEntity(oldest);
            wards.remove(oldest);
        }
        Ward ward = new Ward(location.clone(), team, owner.getUniqueId(), durationSeconds, entityId, type, true);
        wards.add(ward);
        if (entityId != null) applyWardVisibility(ward, type != WardType.STEALTH);
    }

    /** Pose une ward furtive avec entité (compatibilité). */
    public void placeWard(Player owner, Location location, int durationSeconds, UUID entityId) {
        placeWard(owner, location, durationSeconds, entityId, WardType.STEALTH);
    }

    /**
     * Ward sans entité (Ward Zombie, Poro Fantôme, sanctuaire du Crabe…) : vision seulement,
     * hors limite de wards posées (LoL).
     */
    public void placeWard(Player owner, Location location, int durationSeconds) {
        Team team = teamManager.getTeam(owner);
        if (team == null) team = Team.BLUE;
        wards.add(new Ward(location.clone(), team, owner.getUniqueId(), durationSeconds, null, WardType.STEALTH, false));
    }

    /**
     * Applique la visibilité de l'entité de la ward selon l'équipe.
     * revealed=true → visible par TOUS les joueurs ; false → visible uniquement par les alliés.
     */
    public void applyWardVisibility(Ward ward, boolean revealed) {
        Entity ent = ward.entityId != null ? Bukkit.getEntity(ward.entityId) : null;
        if (ent == null) return;
        for (Player p : ent.getWorld().getPlayers()) {
            boolean ally = teamManager.hasTeam(p) && teamManager.getTeam(p) == ward.team;
            if (ally || revealed) p.showEntity(LolPlugin.getInstance(), ent);
            else p.hideEntity(LolPlugin.getInstance(), ent);
        }
    }

    /** Ward désactivée : une Control Ward ennemie la couvre (elle ne donne plus de vision). */
    public boolean isDisabled(Ward w) {
        if (w.type == WardType.CONTROL) return false; // une Control Ward ne peut pas être désactivée
        for (Ward c : wards) {
            if (c.type != WardType.CONTROL || c.team == w.team) continue;
            if (!c.location.getWorld().equals(w.location.getWorld())) continue;
            if (c.location.distanceSquared(w.location) <= c.type.radius * c.type.radius) return true;
        }
        return false;
    }

    /** Révélée aux ennemis : par une Control Ward ennemie, par Oracle Lens, ou de type visible. */
    private boolean isVisibleToEnemies(Ward w) {
        if (w.type != WardType.STEALTH) return true;
        Long until = w.entityId != null ? revealedWards.get(w.entityId) : null;
        return (until != null && until > System.currentTimeMillis()) || isDisabled(w);
    }

    /**
     * Révèle les wards ennemies dans un rayon (Oracle Lens).
     * Elles deviennent visibles par les ennemis et peuvent être détruites.
     */
    public int revealEnemyWards(Player player, Location center, double radius, long durMs) {
        Team myTeam = teamManager.getTeam(player);
        if (myTeam == null) return 0;
        int count = 0;
        for (Ward w : wards) {
            if (w.team == myTeam || w.entityId == null) continue;
            if (w.location.getWorld() == null || !w.location.getWorld().equals(center.getWorld())) continue;
            if (w.location.distance(center) > radius) continue;
            revealedWards.put(w.entityId, System.currentTimeMillis() + durMs);
            applyWardVisibility(w, true);
            fr.lolmc.util.VisualEffectUtil.impact(center.getWorld(),
                    w.location.clone().add(0,0.5,0), Material.WHITE_STAINED_GLASS, 0.28f, 5L);
            count++;
        }
        return count;
    }

    /** Détruit les wards ennemies dans un rayon (effets d'objets). */
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
     * Frappe la ward dont l'entité correspond à entityId : 1 dégât par coup (3 pour une furtive,
     * 4 pour une Control Ward, 1 pour une ward de vision lointaine). Seul un ennemi peut la
     * frapper, et une ward furtive doit être révélée.
     */
    public boolean destroyWardByEntity(UUID entityId, Player attacker) {
        Team myTeam = teamManager.getTeam(attacker);
        Iterator<Ward> it = wards.iterator();
        while (it.hasNext()) {
            Ward w = it.next();
            if (!entityId.equals(w.entityId)) continue;
            if (w.team == myTeam) return false; // pas sa propre ward
            if (!isVisibleToEnemies(w)) {
                attacker.sendActionBar(Component.text("👁 Ward non révélée! Utilisez Oracle Lens ou Control Ward.", NamedTextColor.GRAY));
                return false;
            }
            long now = System.currentTimeMillis();
            String hitKey = attacker.getUniqueId() + ":" + entityId;
            Long last = lastHit.get(hitKey);
            if (last != null && now - last < 400) return false;
            lastHit.put(hitKey, now);
            if (--w.hitsLeft > 0) {
                attacker.sendActionBar(Component.text("🔨 Ward : " + w.hitsLeft + " coup(s) restant(s)", NamedTextColor.YELLOW));
                attacker.getWorld().playSound(w.location, Sound.BLOCK_WOOD_HIT, 1f, 1.2f);
                return true;
            }
            removeWardEntity(w);
            it.remove();
            LolPlugin.getInstance().getGoldManager().addGold(attacker.getUniqueId(), w.type.goldBounty);
            // Rune Zombie Ward : la destruction crée une ward amie au même endroit
            var rm = fr.lolmc.LolPlugin.getInstance().getRuneManager();
            boolean zombieWard = rm != null && rm.getPage(attacker.getUniqueId()).has("zombie_ward");
            if (zombieWard) placeWard(attacker, w.location, 90);
            attacker.sendActionBar(Component.text(
                    (zombieWard ? "💥 Ward détruite ! 🧟 Ward Zombie posée!" : "💥 Ward ennemie détruite!")
                    + " +" + w.type.goldBounty + " or", NamedTextColor.GREEN));
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
            fr.lolmc.util.VisualEffectUtil.impact(w.location.getWorld(),
                    w.location.clone().add(0,0.5,0), Material.GRAY_STAINED_GLASS, 0.26f, 5L);
        if (w.entityId != null) {
            Entity ent = Bukkit.getEntity(w.entityId);
            if (ent != null) ent.remove();
            revealedWards.remove(w.entityId);
        }
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
                revealedWards.entrySet().removeIf(e -> now > e.getValue());
                lastHit.entrySet().removeIf(e -> now - e.getValue() > 5000L);

                // Visibilité des entités de ward (aussi pour les joueurs arrivés après la pose)
                for (Ward ward : wards)
                    if (ward.entityId != null) applyWardVisibility(ward, isVisibleToEnemies(ward));

                // Détecter les ennemis vus par les wards
                for (Ward ward : wards) {
                    if (ward.location.getWorld() == null || isDisabled(ward)) continue;
                    for (Player p : ward.location.getWorld().getPlayers()) {
                        if (!teamManager.hasTeam(p)) continue;
                        if (teamManager.getTeam(p) == ward.team) continue;
                        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                        if (!wardSees(ward, p.getLocation())) continue;
                        boolean wasRevealed = revealedUntil.containsKey(p.getUniqueId())
                                && now < revealedUntil.get(p.getUniqueId());
                        revealedUntil.put(p.getUniqueId(), now + REVEAL_DURATION_MS);
                        revealedToTeam.put(p.getUniqueId(), ward.team);
                        if (!wasRevealed) playDetectionSound(ward.team, ward.location.getWorld());
                    }
                }
                revealedUntil.entrySet().removeIf(e -> now > e.getValue());
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
    }

    /**
     * La ward voit-elle cette position ? Portée de la ward, et règle des bushes : une position
     * dans un bush n'est vue que par une ward posée dans ce même bush.
     */
    private boolean wardSees(Ward ward, Location loc) {
        if (!ward.location.getWorld().equals(loc.getWorld())) return false;
        if (ward.location.distanceSquared(loc) > ward.type.radius * ward.type.radius) return false;
        var bush = LolPlugin.getInstance().getBushManager();
        if (bush == null) return true;
        String locBush = bush.bushIdAt(loc);
        return locBush == null || locBush.equals(bush.bushIdAt(ward.location));
    }

    private void playDetectionSound(Team team, org.bukkit.World world) {
        for (UUID memberId : teamManager.getTeamMembers(team, world)) {
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
                var expired = new java.util.ArrayList<UUID>();
                for (Map.Entry<UUID, Long> entry : revealedUntil.entrySet()) {
                    if (now > entry.getValue()) { expired.add(entry.getKey()); continue; }
                    Player revealed = Bukkit.getPlayer(entry.getKey());
                    if (revealed == null || !revealed.isOnline()) continue;
                    Team viewerTeam = revealedToTeam.get(entry.getKey());
                    if (viewerTeam == null) continue;
                    Team revealedTeam = teamManager.getTeam(revealed);
                    if (revealedTeam == null) continue;
                    drawBeam(revealed, revealedTeam, viewerTeam);
                }
                // Faisceaux des joueurs qui ne sont plus révélés
                for (UUID id : new java.util.ArrayList<>(beamDisplays.keySet()))
                    if (!revealedUntil.containsKey(id)) expired.add(id);
                for (UUID id : expired) {
                    revealedUntil.remove(id);
                    revealedToTeam.remove(id);
                    removeBeamDisplays(id);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
    }

    /**
     * Faisceau de révélation, visible uniquement par l'équipe qui a détecté
     * (viewerTeam). 10 segments verticaux fixes + un point d'anneau tournant.
     */
    private void drawBeam(Player revealed, Team revealedTeam, Team viewerTeam) {
        Location base = revealed.getLocation();
        var displays = beamDisplays.get(revealed.getUniqueId());

        if (displays == null) {
            displays = new java.util.ArrayList<>(11);
            for (int i = 0; i < 11; i++) {
                var d = base.getWorld().spawn(base, org.bukkit.entity.BlockDisplay.class, disp -> {
                    disp.setBlock(revealedTeam.blockColor().createBlockData());
                    disp.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                    disp.setPersistent(false);
                    disp.setInterpolationDuration(3);
                    disp.setInterpolationDelay(0);
                    float size = 0.16f;
                    disp.setTransformation(new org.bukkit.util.Transformation(
                            new org.joml.Vector3f(-size / 2f, -size / 2f, -size / 2f),
                            new org.joml.Quaternionf(),
                            new org.joml.Vector3f(size, size, size),
                            new org.joml.Quaternionf()));
                });
                d.setVisibleByDefault(false);
                displays.add(d);
            }
            beamDisplays.put(revealed.getUniqueId(), displays);
        }

        // 10 segments verticaux (2.5 → 12.0 blocs de haut)
        for (int i = 0; i < 10; i++) {
            double y = 2.5 + i * (9.5 / 9.0);
            displays.get(i).teleport(base.clone().add(0, y, 0));
        }
        // Anneau tournant à la base du faisceau
        double spin = (System.currentTimeMillis() % 2000L) / 2000.0 * 2 * Math.PI;
        displays.get(10).teleport(base.clone().add(Math.cos(spin) * 0.8, 2.4, Math.sin(spin) * 0.8));

        // Visibilité par-viewer : uniquement les joueurs de viewerTeam
        for (UUID viewerId : teamManager.getTeamMembers(viewerTeam, revealed.getWorld())) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline() || !viewer.getWorld().equals(revealed.getWorld())) continue;
            for (var d : displays) viewer.showEntity(LolPlugin.getInstance(), d);
        }
    }

    /** Retire les BlockDisplay du faisceau d'un joueur (fin de révélation). */
    private void removeBeamDisplays(UUID revealedId) {
        var displays = beamDisplays.remove(revealedId);
        if (displays == null) return;
        for (var d : displays) if (d != null && !d.isDead()) d.remove();
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Joueur actuellement vu par une ward ennemie. */
    public boolean isRevealed(Player player) {
        Long until = revealedUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    /** Une ward active (non désactivée) de {@code team} voit-elle cette position (bushes compris) ? */
    public boolean hasVisionAt(Team team, Location loc) {
        for (Ward w : wards) {
            if (w.team != team || w.location.getWorld() == null) continue;
            if (isDisabled(w)) continue;
            if (wardSees(w, loc)) return true;
        }
        return false;
    }

    public int getActiveWardCount() { return wards.size(); }

    /** Toutes les wards actives (pour minimap et fog of war). */
    public java.util.List<Ward> getAllWards() { return new java.util.ArrayList<>(wards); }

    /** True si une ward de cette équipe est à moins de radius blocs de la position. */
    public boolean hasWardNear(Team team, Location loc, double radius) {
        for (Ward w : wards) {
            if (w.team != team) continue;
            if (!w.location.getWorld().equals(loc.getWorld())) continue;
            if (w.location.distanceSquared(loc) <= radius * radius) return true;
        }
        return false;
    }

    /** True si cette ward est visible des ennemis (pour la minimap). */
    public boolean isRevealed(Ward ward) {
        return isVisibleToEnemies(ward);
    }

    /** Retire toutes les wards d'un monde (fermeture d'une instance, avant son déchargement). */
    public void clearWorld(org.bukkit.World world) {
        wards.removeIf(w -> {
            if (!world.getName().equals(w.location.getWorld() != null ? w.location.getWorld().getName() : null)) return false;
            removeWardEntity(w);
            return true;
        });
    }

    /** Le joueur quitte le jeu : ses wards et ses révélations disparaissent (entités comprises). */
    public void cleanup(UUID uuid) {
        revealedUntil.remove(uuid);
        revealedToTeam.remove(uuid);
        removeBeamDisplays(uuid);
        wards.removeIf(w -> {
            if (!w.owner.equals(uuid)) return false;
            removeWardEntity(w); // avant : l'ArmorStand restait sur la carte
            return true;
        });
    }
}
