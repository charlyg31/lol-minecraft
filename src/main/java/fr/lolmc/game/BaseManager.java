package fr.lolmc.game;

import fr.lolmc.LolPlugin;
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
    private static final double HEAL_RING_RADIUS = 8.0;

    public BaseManager() {
        startHealRingTask();
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
                player.getWorld().spawnParticle(Particle.PORTAL,
                        player.getLocation().add(0, 1, 0), 5, 0.3, 0.5, 0.3);

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
        player.teleport(spawn);
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

    private void startHealRingTask() {
        new BukkitRunnable() {
            @Override public void run() {
                var cm = LolPlugin.getInstance().getChampionManager();
                var tm = LolPlugin.getInstance().getTeamManager();
                var mm = LolPlugin.getInstance().getMapManager();

                for (Player p : LolPlugin.getInstance().getServer().getOnlinePlayers()) {
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
