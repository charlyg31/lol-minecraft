package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
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
    /** BlockDisplay de l'animation de recall en cours, par joueur (12 anneau ext + 12 anneau int + pilier). */
    private final Map<UUID, java.util.List<org.bukkit.entity.BlockDisplay>> recallDisplays = new HashMap<>();

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

                var displays = recallDisplays.computeIfAbsent(player.getUniqueId(),
                        k -> initRecallDisplays(player));

                // Cercle au sol qui tourne (2 anneaux inversés) : displays[0..23]
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
                    displays.get(i).teleport(p1);
                    displays.get(points + i).teleport(p2);
                }
                // Pilier de lumière qui monte avec la progression : displays[24..30] (7 segments)
                double pillarHeight = 3.0 * progress;
                int pillarSlots = 7;
                for (int i = 0; i < pillarSlots; i++) {
                    var d = displays.get(2 * points + i);
                    double y = pillarHeight * (i + 1) / pillarSlots;
                    if (y <= pillarHeight) {
                        d.teleport(center.clone().add(0, y, 0));
                    } else {
                        d.teleport(center.clone().add(0, -256, 0)); // pas encore atteint : caché
                    }
                }
                // Spirale montante autour du joueur : displays[31]
                double spiralAngle = ticks * 0.35;
                double spiralY = (ticks % 30) / 30.0 * 2.5;
                displays.get(2 * points + pillarSlots).teleport(center.clone().add(
                        Math.cos(spiralAngle) * 0.8, spiralY, Math.sin(spiralAngle) * 0.8));
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
        // Explosion de départ au moment de la téléportation
        Location departLoc = player.getLocation();
        fr.lolmc.util.VisualEffectUtil.impactBurst(departLoc.getWorld(),
                departLoc.clone().add(0, 1, 0), Material.LIGHT_BLUE_STAINED_GLASS, 0.4f, 0.6, 10, 8L);
        departLoc.getWorld().playSound(departLoc, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.4f);

        player.teleport(spawn);
        // Effet d'arrivée
        fr.lolmc.util.VisualEffectUtil.impactBurst(spawn.getWorld(),
                spawn.clone().add(0, 1, 0), Material.LIGHT_BLUE_STAINED_GLASS, 0.35f, 0.5, 8, 6L);
        player.playSound(spawn, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        player.sendActionBar(Component.text("✔ De retour à la base!", NamedTextColor.GREEN));

        removeRecallDisplays(player);
    }

    public void cancelRecall(Player player, String reason) {
        BukkitRunnable task = activeRecalls.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            recallStartLoc.remove(player.getUniqueId());
            removeRecallDisplays(player);
            player.sendActionBar(Component.text("✖ " + reason, NamedTextColor.RED));
        }
    }

    /**
     * Crée les 32 BlockDisplay de l'animation de recall (12 anneau ext,
     * 12 anneau int, 7 segments de pilier, 1 spirale). Visibles par tous
     * les joueurs proches (recall = effet public en LoL).
     */
    private java.util.List<org.bukkit.entity.BlockDisplay> initRecallDisplays(Player player) {
        var list = new java.util.ArrayList<org.bukkit.entity.BlockDisplay>(32);
        Location loc = player.getLocation();
        for (int i = 0; i < 12; i++) {
            list.add(makeRecallBlock(loc, Material.LIGHT_BLUE_STAINED_GLASS, 0.18f));
        }
        for (int i = 0; i < 12; i++) {
            list.add(makeRecallBlock(loc, Material.BLUE_STAINED_GLASS, 0.15f));
        }
        for (int i = 0; i < 7; i++) {
            list.add(makeRecallBlock(loc, Material.WHITE_STAINED_GLASS, 0.14f));
        }
        list.add(makeRecallBlock(loc, Material.PURPLE_STAINED_GLASS, 0.2f));
        return list;
    }

    private org.bukkit.entity.BlockDisplay makeRecallBlock(Location loc, Material block, float size) {
        return loc.getWorld().spawn(loc, org.bukkit.entity.BlockDisplay.class, disp -> {
            disp.setBlock(block.createBlockData());
            disp.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(2);
            disp.setInterpolationDelay(0);
            disp.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(-size / 2f, -size / 2f, -size / 2f),
                    new org.joml.Quaternionf(),
                    new org.joml.Vector3f(size, size, size),
                    new org.joml.Quaternionf()));
        });
    }

    private void removeRecallDisplays(Player player) {
        var list = recallDisplays.remove(player.getUniqueId());
        if (list == null) return;
        for (var d : list) if (d != null && !d.isDead()) d.remove();
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
                        fr.lolmc.util.VisualEffectUtil.impact(p.getWorld(),
                                p.getLocation().add(0, 1.5, 0), Material.PINK_STAINED_GLASS, 0.25f, 8L);
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
        var list = recallDisplays.remove(uuid);
        if (list != null) {
            for (var d : list) if (d != null && !d.isDead()) d.remove();
        }
    }
}
