package fr.lolmc.ability;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prévisualisation directionnelle des sorts (visible UNIQUEMENT par le lanceur).
 * <p>
 * Implémentation en BlockDisplay (pas de particules) : de nombreux joueurs
 * désactivent les particules dans leurs options vidéo, ce qui rendrait cette
 * fonctionnalité invisible pour eux. Les BlockDisplay sont des entités serveur
 * classiques, non affectées par ce réglage.
 * <p>
 * Deux modes :
 *  • SKILLSHOT : une "règle" fine et longue (bloc étiré), orientée selon le
 *                regard du joueur, repositionnée à chaque tick.
 *  • GROUND    : un anneau fin composé de segments de bloc, à l'endroit visé.
 * <p>
 * Visibilité restreinte au lanceur via Entity#setVisibleByDefault(false) +
 * Player#showEntity() — le mécanisme standard documenté par Paper pour une
 * entité visible seulement de certains joueurs, sans paquet réseau custom.
 * <p>
 * Activé quand le joueur tient un item de sort dans sa hotbar (slot 1-4).
 * Désactivé automatiquement au changement de slot ou au cast.
 */
public class AbilityPreview {

    public enum PreviewType { NONE, SKILLSHOT, GROUND }

    private final Map<UUID, BukkitTask> activePreviews = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>    previewSlot     = new ConcurrentHashMap<>();
    /** Entités BlockDisplay actuellement affichées pour un joueur (à nettoyer au stop). */
    private final Map<UUID, List<BlockDisplay>> activeDisplays = new ConcurrentHashMap<>();

    // ── Paramètres visuels ────────────────────────────────────────────────────
    private static final Material LINE_BLOCK    = Material.YELLOW_STAINED_GLASS;
    private static final Material SHADOW_BLOCK  = Material.LIGHT_GRAY_STAINED_GLASS; // ombre de Zed
    private static final Material GROUND_BLOCK  = Material.YELLOW_STAINED_GLASS;
    private static final float BAND_THICKNESS   = 0.06f;  // épaisseur de la bande posée au sol
    private static final double BAND_LIFT       = 0.04;   // léger décollage pour éviter le z-fighting avec le sol
    private static final int   RING_SEGMENTS    = 24;     // segments composant l'anneau au sol
    private static final float RING_SEGMENT_LEN = 0.35f;  // longueur de chaque segment de l'anneau
    private static final float RING_THICKNESS   = 0.08f;

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

        // Pré-création des entités persistantes réutilisées à chaque tick
        // (au lieu de spawn/remove à chaque frame, on repositionne).
        List<BlockDisplay> displays = spawnDisplaysFor(player, type, width);
        activeDisplays.put(player.getUniqueId(), displays);

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
                    case SKILLSHOT -> updateSkillshot(player, displays, range, width);
                    case GROUND    -> updateGround(player, displays, range);
                    default        -> { }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L); // 2 ticks = 100ms (fluide, léger)

        activePreviews.put(player.getUniqueId(), task);
    }

    /** Arrête la prévisualisation d'un joueur et retire ses BlockDisplay. */
    public void stop(Player player) {
        BukkitTask task = activePreviews.remove(player.getUniqueId());
        if (task != null) task.cancel();
        previewSlot.remove(player.getUniqueId());

        List<BlockDisplay> displays = activeDisplays.remove(player.getUniqueId());
        if (displays != null) {
            for (BlockDisplay d : displays) {
                if (d != null && !d.isDead()) d.remove();
            }
        }
    }

    /** Arrête toutes les previsualisations (cleanup, ex: arrêt du plugin/partie). */
    public void stopAll() {
        activePreviews.values().forEach(BukkitTask::cancel);
        activePreviews.clear();
        previewSlot.clear();
        for (List<BlockDisplay> displays : activeDisplays.values()) {
            for (BlockDisplay d : displays) {
                if (d != null && !d.isDead()) d.remove();
            }
        }
        activeDisplays.clear();
    }

    // ── Création des entités (une fois par démarrage de preview) ──────────────

    /**
     * Crée les BlockDisplay nécessaires pour ce type de preview, invisibles
     * pour tout le monde sauf le lanceur.
     */
    private List<BlockDisplay> spawnDisplaysFor(Player player, PreviewType type, double width) {
        List<BlockDisplay> displays = new ArrayList<>();
        Location spawnLoc = player.getLocation().clone();
        spawnLoc.setYaw(0f);
        spawnLoc.setPitch(0f);

        switch (type) {
            case SKILLSHOT -> {
                // Une seule bande plate au sol, de la largeur du couloir.
                // La bande de l'ombre de Zed est créée à la demande (voir updateSkillshot).
                displays.add(makeLineDisplay(spawnLoc, LINE_BLOCK));
            }
            case GROUND -> {
                for (int i = 0; i < RING_SEGMENTS; i++) {
                    displays.add(makeRingSegment(spawnLoc));
                }
            }
            default -> { }
        }

        for (BlockDisplay d : displays) {
            d.setVisibleByDefault(false);
            player.showEntity(LolPlugin.getInstance(), d);
        }
        return displays;
    }

    private BlockDisplay makeLineDisplay(Location loc, Material block) {
        return loc.getWorld().spawn(loc, BlockDisplay.class, disp -> {
            disp.setBlock(block.createBlockData());
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(2);
            disp.setInterpolationDelay(0);
        });
    }

    private BlockDisplay makeRingSegment(Location loc) {
        return loc.getWorld().spawn(loc, BlockDisplay.class, disp -> {
            disp.setBlock(GROUND_BLOCK.createBlockData());
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(2);
            disp.setInterpolationDelay(0);
        });
    }

    // ── Mise à jour par tick (repositionnement + réorientation) ───────────────

    /**
     * Affiche le couloir du skillshot comme une bande plate posée au sol, devant
     * les pieds du joueur (bien plus lisible qu'une ligne partant de la tête).
     * La direction est projetée à l'horizontale et la bande s'arrête au premier
     * mur rencontré à hauteur de corps.
     */
    private void updateSkillshot(Player player, List<BlockDisplay> displays, double range, double width) {
        Vector dir = flatDirection(player.getEyeLocation().getDirection());
        Location feet = player.getLocation().clone();
        double length = raycastRange(feet.clone().add(0, 0.6, 0), dir, range);
        positionBand(displays.get(0), feet, dir, length, width);

        if (!isZed(player)) return;

        // Ombre de Zed : une deuxième bande, partant de l'ombre, créée seulement
        // tant qu'une ombre est active, puis supprimée.
        var shadowLoc = fr.lolmc.champion.impl.mid.Zed.getShadowLocation(player.getUniqueId());
        if (shadowLoc != null && shadowLoc.getWorld() != null) {
            if (displays.size() < 2) {
                BlockDisplay d = makeLineDisplay(shadowLoc, SHADOW_BLOCK);
                d.setVisibleByDefault(false);
                player.showEntity(LolPlugin.getInstance(), d);
                displays.add(d);
            }
            double shadowLen = raycastRange(shadowLoc.clone().add(0, 0.6, 0), dir, range);
            positionBand(displays.get(1), shadowLoc, dir, shadowLen, width);
        } else if (displays.size() > 1) {
            BlockDisplay d = displays.remove(1);
            if (d != null && !d.isDead()) d.remove();
        }
    }

    /** Direction horizontale normalisée (repli vers l'avant si le joueur regarde pile en haut/bas). */
    private Vector flatDirection(Vector dir) {
        Vector flat = new Vector(dir.getX(), 0, dir.getZ());
        if (flat.lengthSquared() < 1.0e-6) return new Vector(0, 0, 1);
        return flat.normalize();
    }

    /**
     * Pose une bande plate (largeur × longueur) au sol, de [origin] vers [dir].
     * L'entité garde une rotation neutre (yaw 0) ; l'orientation vient uniquement
     * de la transformation, pour éviter de cumuler deux rotations.
     */
    private void positionBand(BlockDisplay display, Location origin, Vector dir, double length, double width) {
        Location at = origin.clone();
        at.setY(origin.getY() + BAND_LIFT);
        at.setYaw(0f);
        at.setPitch(0f);
        display.teleport(at);

        float angle = (float) Math.atan2(dir.getX(), dir.getZ());
        Quaternionf rot = new Quaternionf().rotateY(angle);

        // X = largeur (centrée sur l'axe), Y = épaisseur, Z = longueur vers l'avant.
        // La translation est exprimée avant rotation : on recentre en X seulement.
        Vector3f translation = new Vector3f();
        new Vector3f((float) (-width / 2.0), 0f, 0f).rotate(rot, translation);
        Vector3f scale = new Vector3f((float) width, BAND_THICKNESS, (float) Math.max(0.1, length));

        display.setTransformation(new Transformation(translation, rot, scale, new Quaternionf()));
    }

    /**
     * Repositionne les segments de l'anneau au sol autour du point visé.
     */
    private void updateGround(Player player, List<BlockDisplay> displays, double range) {
        Location ground = fr.lolmc.util.TargetingUtil.getAimedGroundLocation(player, range);
        double radius = getGroundRadius(player);

        for (int i = 0; i < displays.size(); i++) {
            double angle = (2 * Math.PI * i) / displays.size();
            double x = ground.getX() + radius * Math.cos(angle);
            double z = ground.getZ() + radius * Math.sin(angle);
            Location segLoc = new Location(ground.getWorld(), x, ground.getY() + 0.05, z);

            // Chaque segment est orienté tangentiellement au cercle (perpendiculaire au rayon)
            float tangentYaw = (float) (angle + Math.PI / 2.0);
            Quaternionf rot = new Quaternionf().rotateY(tangentYaw);

            Vector3f translation = new Vector3f(-RING_SEGMENT_LEN / 2f, 0f, -RING_THICKNESS / 2f);
            Vector3f scale = new Vector3f(RING_SEGMENT_LEN, RING_THICKNESS, RING_THICKNESS);

            displays.get(i).teleport(segLoc);
            displays.get(i).setTransformation(new Transformation(translation, rot, scale, new Quaternionf()));
        }
    }

    /**
     * Lance un rayon simple bloc-par-bloc pour trouver la portée effective
     * avant collision (même logique que l'ancien rendu par particules).
     */
    private double raycastRange(Location origin, Vector dir, double maxRange) {
        Location pos = origin.clone();
        double step = 0.5;
        double dist = 0;
        while (dist < maxRange) {
            pos.add(dir.clone().multiply(step));
            dist += step;
            if (pos.getBlock().getType().isSolid()) return dist;
        }
        return maxRange;
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
            case "zed_q", "amumu_q" -> 12.0;
            case "ashe_r"         -> 25.0;
            case "jinx_w"         -> 14.0;
            case "jinx_r"         -> 30.0;
            case "janna_q", "leona_r", "malphite_r" -> 10.0;
            case "leona_e", "blitzcrank_q", "veigar_q", "veigar_w", "jinx_e" -> 9.0;
            case "morgana_q"      -> 6.5;
            case "annie_w", "annie_r" -> 7.0;
            default               -> 8.0; // yasuo_r n'a pas de case dédié, il retombe ici
        };
    }

    private double getWidth(BaseChampion champ, int slot) {
        String id = champ.getId() + "_" + slotLetter(slot);
        return switch (id) {
            case "janna_q", "ashe_r" -> 1.2;
            default                  -> 1.0; // leona_e et fallback partagent 1.0
        };
    }

    private double getGroundRadius(Player player) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return 3.0;
        String id = cm.getChampion(player).getId();
        int slot = player.getInventory().getHeldItemSlot();
        String key = id + "_" + slotLetter(slot);
        return switch (key) {
            case "veigar_w", "leona_r", "yasuo_r" -> 4.0;
            case "annie_w"    -> 2.5;
            case "malphite_r" -> 4.5;
            default           -> 3.0; // annie_r et jinx_e partagent 3.0
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
