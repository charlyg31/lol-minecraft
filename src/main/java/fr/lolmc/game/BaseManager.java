package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère le retour à la base (recall), les portails de base, et les anneaux de soin.
 *
 * - Recall : canalisation de 8s qui téléporte à la base (interrompu par les dégâts).
 * - Anneau de soin : zone autour de la base qui régénère HP et ressource.
 */
public class BaseManager {

    // Recall en cours : UUID → tâche
    private final Map<UUID, BukkitRunnable> activeRecalls = new HashMap<>();
    // Position de recall mémorisée au début de la canalisation
    private final Map<UUID, Location> recallStartLoc = new HashMap<>();

    private static final int RECALL_SECONDS = 8;
    // Fontaine : 10 000 vrais dégâts/s sur les ennemis dans la zone
    private static final double FOUNTAIN_DAMAGE_PER_SEC = 10000.0;
    private static final double HEAL_RING_RADIUS = 8.0;

    private org.bukkit.World scopedWorld = null;
    private org.bukkit.scheduler.BukkitTask healTask = null;

    public BaseManager() {
        startHealRingTask();
    }

    /** Constructeur pour les instances : scoped à un World, tâche non démarrée. */
    public BaseManager(org.bukkit.World world) {
        this.scopedWorld = world;
        // Démarrage via startHealRingTask() appelé par GameInstance.start()
    }

    // ══════════════════════════════════════════════════════════════
    // RECALL
    // ══════════════════════════════════════════════════════════════

    public void startRecall(Player player) {
        fr.lolmc.util.DebugLogger.log("Recall", "startRecall appelé par " + player.getName());
        if (activeRecalls.containsKey(player.getUniqueId())) {
            cancelRecall(player, "Recall déjà en cours");
            return;
        }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;

        recallStartLoc.put(player.getUniqueId(), player.getLocation().clone());

        BukkitRunnable task = new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                // Annulation si le joueur a bougé significativement
                Location start = recallStartLoc.get(player.getUniqueId());
                if (start == null || player.getLocation().distance(start) > 1.5) {
                    cancelRecall(player, "Recall interrompu (mouvement)");
                    return;
                }
                ticks++;
                int secsLeft = RECALL_SECONDS - ticks / 20;
                player.sendActionBar(Component.text("🌀 Recall... " + secsLeft + "s", NamedTextColor.AQUA));

                // ── Recall visuel façon LoL : cercle rotatif + pilier ──
                Location center = player.getLocation();
                double progress = ticks / (double)(RECALL_SECONDS * 20); // 0 → 1
                // Cercle au sol qui tourne (2 anneaux inversés)
                double radius = 1.6;
                int points = 12;
                double rotation = ticks * 0.15; // vitesse de rotation
                for (int i = 0; i < points; i++) {
                    double angle1 = rotation + (2 * Math.PI * i / points);
                    double angle2 = -rotation + (2 * Math.PI * i / points);
                    Location p1 = center.clone().add(
                        Math.cos(angle1) * radius, 0.1, Math.sin(angle1) * radius);
                    Location p2 = center.clone().add(
                        Math.cos(angle2) * (radius * 0.7), 0.1, Math.sin(angle2) * (radius * 0.7));
                    player.getWorld().spawnParticle(Particle.DUST, p1, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(80, 180, 255), 1.0f));
                    player.getWorld().spawnParticle(Particle.DUST, p2, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(150, 220, 255), 0.8f));
                }
                // Pilier de lumière qui monte avec la progression
                double pillarHeight = 3.0 * progress;
                for (double y = 0; y <= pillarHeight; y += 0.4) {
                    player.getWorld().spawnParticle(Particle.END_ROD,
                        center.clone().add(0, y, 0), 1, 0.05, 0, 0.05, 0.01);
                }
                // Spirale montante autour du joueur
                double spiralAngle = ticks * 0.35;
                double spiralY = (ticks % 30) / 30.0 * 2.5;
                player.getWorld().spawnParticle(Particle.PORTAL,
                    center.clone().add(Math.cos(spiralAngle) * 0.8, spiralY,
                                       Math.sin(spiralAngle) * 0.8), 2, 0, 0, 0, 0.02);
                // Son de charge toutes les secondes
                if (ticks % 20 == 0) {
                    player.getWorld().playSound(center, Sound.BLOCK_BEACON_AMBIENT, 0.6f,
                        1.0f + (float) progress * 0.5f);
                }

                if (ticks >= RECALL_SECONDS * 20) {
                    completeRecall(player);
                    cancel();
                    activeRecalls.remove(player.getUniqueId());
                    recallStartLoc.remove(player.getUniqueId());
                }
            }
        };
        task.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);
        activeRecalls.put(player.getUniqueId(), task);
    }

    private void completeRecall(Player player) {
        Team team = LolPlugin.getInstance().getTeamManager().getTeam(player);
        fr.lolmc.util.DebugLogger.log("Recall", "completeRecall: team=" + team);
        if (team == null) {
            player.sendActionBar(Component.text("❌ Recall: aucune équipe", NamedTextColor.RED));
            return;
        }
        Location spawn = LolPlugin.getInstance().getMapManager().getSpawn(team, 1);
        fr.lolmc.util.DebugLogger.log("Recall", "completeRecall: spawn=" + spawn);
        if (spawn == null) {
            player.sendActionBar(Component.text(
                "❌ Recall: spawn équipe non défini (/lol position " + team.name().toLowerCase() + ")",
                NamedTextColor.RED));
            return;
        }
        // Explosion de particules au départ
        Location departLoc = player.getLocation();
        departLoc.getWorld().spawnParticle(Particle.FLASH, departLoc.clone().add(0, 1, 0), 1);
        departLoc.getWorld().spawnParticle(Particle.END_ROD, departLoc.clone().add(0, 1, 0),
            40, 0.5, 1.0, 0.5, 0.1);
        departLoc.getWorld().playSound(departLoc, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.4f);

        player.teleport(spawn);
        // Effet d'arrivée
        spawn.getWorld().spawnParticle(Particle.END_ROD, spawn.clone().add(0, 1, 0),
            30, 0.5, 1.0, 0.5, 0.05);
        player.playSound(spawn, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        player.sendActionBar(Component.text("✔ De retour à la base!", NamedTextColor.GREEN));
    }

    public void cancelRecall(Player player, String reason) {
        BukkitRunnable task = activeRecalls.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            recallStartLoc.remove(player.getUniqueId());
            player.sendActionBar(Component.text("✖ " + reason, NamedTextColor.RED));
        }
    }

    /** Appelé quand le joueur prend des dégâts → interrompt le recall. */
    public void onDamage(Player player) {
        if (activeRecalls.containsKey(player.getUniqueId())) {
            cancelRecall(player, "Recall interrompu (dégâts)");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ANNEAU DE SOIN (régénération à la base)
    // ══════════════════════════════════════════════════════════════

    public void startHealRingTask() {
        new BukkitRunnable() {
            @Override public void run() {
                var cm = LolPlugin.getInstance().getChampionManager();
                var tm = LolPlugin.getInstance().getTeamManager();
                var mm = LolPlugin.getInstance().getMapManager();

                for (Player p : WorldContext.getGamePlayers()) {
                    if (!cm.hasChampion(p)) continue;
                    Team team = tm.getTeam(p);
                    if (team == null) continue;

                    Location base = mm.getSpawn(team, 1);
                    if (base == null) continue;
                    if (!p.getWorld().equals(base.getWorld())) continue;

                    // Dans la zone de la base → soin rapide
                    if (p.getLocation().distance(base) <= HEAL_RING_RADIUS) {
                        BaseChampion champ = cm.getChampion(p);
                        double maxHP = champ.getHPSystem().getMaxHP();
                        // Soigne 5% PV max par seconde
                        champ.getHPSystem().heal(maxHP * 0.05);
                        champ.getResourceSystem().fill(); // ressource pleine à la base
                        p.getWorld().spawnParticle(Particle.HEART,
                                p.getLocation().add(0, 1.5, 0), 1, 0.3, 0.3, 0.3);
                        var hud = LolPlugin.getInstance().getHUDManager();
                        if (hud != null) hud.updateHUD(p, champ);
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L); // chaque seconde
    }

    public void cleanup(UUID uuid) {
        BukkitRunnable task = activeRecalls.remove(uuid);
        if (task != null) task.cancel();
        recallStartLoc.remove(uuid);
    }
}
