package fr.lolmc.ability;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prévisualisation directionnelle des sorts (visible UNIQUEMENT par le lanceur).
 *
 * Deux modes :
 *  - SKILLSHOT : ligne de particules dans la direction du regard
 *  - GROUND    : cercle au point visé au sol
 *
 * Les particules sont envoyées via Player.spawnParticle() (côté joueur uniquement).
 * L'animation du sort au cast est elle visible par tous (world.spawnParticle).
 *
 * Activé quand le joueur tient un item de sort dans sa hotbar (slot 1-4).
 * Désactivé automatiquement au changement de slot ou au cast.
 */
public class AbilityPreview {

    public enum PreviewType { NONE, SKILLSHOT, GROUND }

    private final Map<UUID, BukkitTask> activePreviews = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>    previewSlot     = new ConcurrentHashMap<>();

    // ── Paramètres visuels ────────────────────────────────────────────────────
    private static final Particle LINE_PARTICLE   = Particle.END_ROD;
    private static final Particle GROUND_PARTICLE = Particle.DUST;
    private static final Particle.DustOptions GROUND_DUST
        = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 220, 60), 0.8f);
    private static final double LINE_STEP   = 0.6;   // espacement des particules en ligne
    private static final double GROUND_STEP = 0.35;  // espacement pour le cercle

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Démarre la prévisualisation pour un joueur sur un slot donné.
     * Si une préview existait déjà, elle est remplacée.
     */
    public void start(Player player, int slot) {
        if (!fr.lolmc.util.WorldContext.isInGameWorld(player)) return;

        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;
        BaseChampion champ = cm.getChampion(player);

        // Slot 1=Q, 2=W, 3=E, 4=R → ability index = slot
        var ability = champ.getAbility(slot);
        if (ability == null) return;

        PreviewType type = detectType(champ, slot);
        if (type == PreviewType.NONE) return;

        // Stopper la précédente si même slot
        Integer prev = previewSlot.get(player.getUniqueId());
        if (prev != null && prev == slot && activePreviews.containsKey(player.getUniqueId())) return;

        stop(player);

        double range = getRange(champ, slot);
        double width = getWidth(champ, slot);
        previewSlot.put(player.getUniqueId(), slot);

        BukkitTask task = new BukkitRunnable() {
            @Override public void run() {
                if (!player.isOnline() || !fr.lolmc.util.WorldContext.isInGameWorld(player)) {
                    stop(player); cancel(); return;
                }
                // Vérifier que le joueur tient toujours le bon slot
                if (player.getInventory().getHeldItemSlot() != slot) {
                    stop(player); cancel(); return;
                }
                switch (type) {
                    case SKILLSHOT -> drawSkillshot(player, range, width);
                    case GROUND    -> drawGround(player, range);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 3L); // 3 ticks = ~150ms

        activePreviews.put(player.getUniqueId(), task);
    }

    /** Arrête la prévisualisation d'un joueur. */
    public void stop(Player player) {
        BukkitTask task = activePreviews.remove(player.getUniqueId());
        if (task != null) task.cancel();
        previewSlot.remove(player.getUniqueId());
    }

    /** Arrête toutes les previsualisations (cleanup). */
    public void stopAll() {
        activePreviews.values().forEach(BukkitTask::cancel);
        activePreviews.clear();
        previewSlot.clear();
    }

    // ── Dessin ────────────────────────────────────────────────────────────────

    /**
     * Skillshot : ligne de particules depuis les yeux du joueur,
     * dans sa direction de regard, sur [range] blocs.
     * Une deuxième ligne est tracée si le sort vient de l'ombre de Zed.
     */
    private void drawSkillshot(Player player, double range, double width) {
        Location eye  = player.getEyeLocation();
        Vector   dir  = eye.getDirection().normalize();

        drawLine(player, eye, dir, range, LINE_PARTICLE, null);

        // Ombre de Zed : afficher aussi la trajectoire depuis l'ombre
        if (isZed(player)) {
            Location shadowLoc = fr.lolmc.champion.impl.mid.Zed.getShadowLocation(player.getUniqueId());
            if (shadowLoc != null) {
                // La direction de l'ombre est la même que celle de Zed
                Location shadowEye = shadowLoc.clone().add(0, 1.6, 0);
                shadowEye.setDirection(dir);
                drawLine(player, shadowEye, dir, range,
                    Particle.SMOKE, null);
            }
        }
    }

    private void drawLine(Player player, Location start, Vector dir,
                          double range, Particle particle, Object data) {
        Location pos = start.clone();
        double dist  = 0;
        while (dist < range) {
            pos.add(dir.clone().multiply(LINE_STEP));
            dist += LINE_STEP;
            // S'arrêter sur un bloc solide (simuler la collision)
            if (pos.getBlock().getType().isSolid()) break;
            // Envoyer la particule uniquement au joueur
            if (data != null) {
                player.spawnParticle(particle, pos, 1, 0, 0, 0, 0, data);
            } else {
                player.spawnParticle(particle, pos, 1, 0, 0, 0, 0);
            }
        }
        // Croix d'impact à l'extrémité
        player.spawnParticle(Particle.CRIT, pos, 3, 0.15, 0.15, 0.15, 0);
    }

    /**
     * Ground : cercle de particules au point visé au sol.
     */
    private void drawGround(Player player, double range) {
        // Calculer le point au sol visé
        Location ground = fr.lolmc.util.TargetingUtil.getAimedGroundLocation(player, range);
        if (ground == null) return;

        double radius = getGroundRadius(player);
        int steps = (int)(2 * Math.PI * radius / GROUND_STEP);
        for (int i = 0; i < steps; i++) {
            double angle = (2 * Math.PI * i) / steps;
            double x = ground.getX() + radius * Math.cos(angle);
            double z = ground.getZ() + radius * Math.sin(angle);
            Location p = new Location(ground.getWorld(), x, ground.getY() + 0.1, z);
            player.spawnParticle(GROUND_PARTICLE, p, 1, 0, 0, 0, 0, GROUND_DUST);
        }
        // Croix au centre
        player.spawnParticle(Particle.END_ROD, ground.clone().add(0,0.1,0), 2, 0.05, 0, 0.05, 0);
    }

    // ── Détection du type de sort ─────────────────────────────────────────────

    private PreviewType detectType(BaseChampion champ, int slot) {
        String id = champ.getId() + "_" + slotLetter(slot);
        return switch (id) {
            // Skillshots (ligne)
            case "zed_q",
                 "ashe_r",
                 "jinx_w", "jinx_r",
                 "janna_q",
                 "leona_e",
                 "blitzcrank_q",
                 "amumu_q",
                 "veigar_q",
                 "morgana_q"
                -> PreviewType.SKILLSHOT;
            // Ground (cercle au sol)
            case "veigar_w",
                 "annie_w", "annie_r",
                 "jinx_e",
                 "leona_r",
                 "malphite_r",
                 "yasuo_r"
                -> PreviewType.GROUND;
            default -> PreviewType.NONE;
        };
    }

    private double getRange(BaseChampion champ, int slot) {
        String id = champ.getId() + "_" + slotLetter(slot);
        return switch (id) {
            case "zed_q"         -> 12.0;
            case "ashe_r"        -> 25.0;
            case "jinx_w"        -> 14.0;
            case "jinx_r"        -> 30.0;
            case "janna_q"       -> 10.0;
            case "leona_e"       -> 9.0;
            case "blitzcrank_q"  -> 9.0;
            case "amumu_q"       -> 12.0;
            case "veigar_q"      -> 9.0;
            case "morgana_q"     -> 6.5;
            case "veigar_w"      -> 9.0;
            case "annie_w", "annie_r" -> 7.0;
            case "jinx_e"        -> 9.0;
            case "leona_r"       -> 10.0;
            case "malphite_r"    -> 10.0;
            case "yasuo_r"       -> 8.0;
            default              -> 8.0;
        };
    }

    private double getWidth(BaseChampion champ, int slot) {
        String id = champ.getId() + "_" + slotLetter(slot);
        return switch (id) {
            case "janna_q"  -> 1.2;
            case "leona_e"  -> 1.0;
            case "ashe_r"   -> 1.2;
            default         -> 1.0;
        };
    }

    private double getGroundRadius(Player player) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return 3.0;
        String id = cm.getChampion(player).getId();
        int slot = player.getInventory().getHeldItemSlot();
        String key = id + "_" + slotLetter(slot);
        return switch (key) {
            case "veigar_w"   -> 4.0;
            case "annie_w"    -> 2.5;
            case "annie_r"    -> 3.0;
            case "jinx_e"     -> 3.0;
            case "leona_r"    -> 4.0;
            case "malphite_r" -> 4.5;
            case "yasuo_r"    -> 4.0;
            default           -> 3.0;
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String slotLetter(int slot) {
        return switch (slot) {
            case 1 -> "q"; case 2 -> "w"; case 3 -> "e"; case 4 -> "r";
            default -> "?";
        };
    }

    private boolean isZed(Player player) {
        var cm = LolPlugin.getInstance().getChampionManager();
        return cm.hasChampion(player) && "zed".equals(cm.getChampion(player).getId());
    }
}
