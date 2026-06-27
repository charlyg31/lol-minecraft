package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager;
import fr.lolmc.util.WorldContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;

import java.util.*;

/**
 * Minimap affichée dans la main secondaire (carte vanilla = filled_map).
 *
 * - Le FOND est le rendu terrain natif de Minecraft (on garde le renderer par défaut),
 *   centré et zoomé sur la carte LoL → vraie minimap du terrain, gratuitement.
 * - Par-dessus, un renderer ajoute des CURSEURS par joueur :
 *     • soi          → curseur PLAYER
 *     • alliés       → curseur bleu
 *     • ennemis VUS  → curseur rouge (uniquement ceux que le joueur peut voir, via le
 *                       fog of war : on s'appuie sur Player#canSee qui reflète déjà
 *                       les hidePlayer/showPlayer du FogOfWarManager)
 *     • pings        → curseur cible temporaire (par équipe)
 *
 * Les "têtes de joueurs" ne sont pas lisibles à 128px : les curseurs natifs sont nets
 * et colorés, c'est le bon outil ici.
 *
 * Config (config.yml) :
 *   minimap:
 *     center-x: 0
 *     center-z: 0
 *     scale: FARTHEST   # CLOSEST=128b, CLOSE=256, NORMAL=512, FAR=1024, FARTHEST=2048
 */
public class MinimapManager implements Listener {

    // ── Types de curseurs (centralisés : ajuste si un nom diffère sur ta version) ──
    private static final MapCursor.Type CURSOR_SELF  = MapCursor.Type.PLAYER;
    private static final MapCursor.Type CURSOR_ALLY  = MapCursor.Type.BLUE_MARKER;
    private static final MapCursor.Type CURSOR_ENEMY = MapCursor.Type.RED_MARKER;
    private static final MapCursor.Type CURSOR_PING  = MapCursor.Type.TARGET_X;

    private static final long PING_DURATION_MS = 5000L;

    private int centerX = 0, centerZ = 0;
    private MapView.Scale scale = MapView.Scale.FARTHEST;

    private MapView sharedView = null;

    // Pings actifs par équipe
    private static final class Ping {
        final Location loc; final long expiry;
        Ping(Location loc, long expiry) { this.loc = loc; this.expiry = expiry; }
    }
    private final Map<TeamManager.Team, List<Ping>> pings = new EnumMap<>(TeamManager.Team.class);

    public MinimapManager() {
        var cfg = LolPlugin.getInstance().getConfig();
        centerX = cfg.getInt("minimap.center-x", 0);
        centerZ = cfg.getInt("minimap.center-z", 0);
        try {
            scale = MapView.Scale.valueOf(cfg.getString("minimap.scale", "FARTHEST"));
        } catch (IllegalArgumentException ignored) { scale = MapView.Scale.FARTHEST; }
    }

    // ──────────────────────────────────────────────────────────────
    // PINGS  (appelé depuis AnnouncementManager.sendPing — voir patch)
    // ──────────────────────────────────────────────────────────────
    public void registerPing(TeamManager.Team team, Location loc) {
        if (team == null || loc == null) return;
        pings.computeIfAbsent(team, k -> new ArrayList<>())
             .add(new Ping(loc.clone(), System.currentTimeMillis() + PING_DURATION_MS));
    }

    // ──────────────────────────────────────────────────────────────
    // DISTRIBUTION DE LA CARTE
    // ──────────────────────────────────────────────────────────────

    /** Donne la minimap dans la main secondaire du joueur. */
    public void giveMinimap(Player player) {
        World world = player.getWorld();
        if (sharedView == null || !sharedView.getWorld().equals(world)) {
            sharedView = Bukkit.createMap(world);
            sharedView.setScale(scale);
            sharedView.setCenterX(centerX);
            sharedView.setCenterZ(centerZ);
            sharedView.setTrackingPosition(true);
            sharedView.setUnlimitedTracking(false);
            // On GARDE le renderer terrain par défaut, on ajoute juste l'overlay curseurs
            sharedView.addRenderer(new CursorRenderer());
        }
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(sharedView);
        map.setItemMeta(meta);
        player.getInventory().setItemInOffHand(map);
    }

    /** Retire la minimap de la main secondaire si présente. */
    public void removeMinimap(Player player) {
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && off.getType() == Material.FILLED_MAP) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    /** Auto-câblage : donne/retire la carte selon le monde. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (WorldContext.isInGameWorld(p)) giveMinimap(p);
        else removeMinimap(p);
    }

    // ──────────────────────────────────────────────────────────────
    // RENDU DES CURSEURS (par joueur)
    // ──────────────────────────────────────────────────────────────
    private class CursorRenderer extends MapRenderer {
        CursorRenderer() { super(true); } // contextual = rendu par joueur

        @Override
        public void render(MapView map, MapCanvas canvas, Player viewer) {
            MapCursorCollection cursors = canvas.getCursors();
            while (cursors.size() > 0) cursors.removeCursor(cursors.getCursor(0));

            TeamManager tm = LolPlugin.getInstance().getTeamManager();
            TeamManager.Team myTeam = tm.getTeam(viewer);

            for (Player other : viewer.getWorld().getPlayers()) {
                MapCursor.Type type;
                if (other.equals(viewer)) {
                    type = CURSOR_SELF;
                } else if (!tm.areEnemies(viewer, other)) {
                    type = CURSOR_ALLY;
                } else {
                    // ennemi : visible seulement si le fog of war ne le cache pas
                    if (!viewer.canSee(other)) continue;
                    type = CURSOR_ENEMY;
                }
                addCursor(cursors, other.getLocation(), directionFromYaw(other.getLocation().getYaw()), type);
            }

            // Pings de mon équipe
            if (myTeam != null) {
                List<Ping> list = pings.get(myTeam);
                if (list != null) {
                    long now = System.currentTimeMillis();
                    list.removeIf(p -> p.expiry < now);
                    for (Ping p : list) addCursor(cursors, p.loc, (byte) 0, CURSOR_PING);
                }
            }
        }

        private void addCursor(MapCursorCollection cursors, Location loc, byte dir, MapCursor.Type type) {
            int px = worldToCursor(loc.getBlockX(), centerX);
            int pz = worldToCursor(loc.getBlockZ(), centerZ);
            if (px < -128 || px > 127 || pz < -128 || pz > 127) return; // hors carte
            cursors.addCursor(new MapCursor((byte) px, (byte) pz, dir, type, true, (net.kyori.adventure.text.Component) null));
        }

        /** Convertit une coordonnée monde en coordonnée curseur (-128..127). */
        private int worldToCursor(int worldCoord, int center) {
            int blocksPerPixel = switch (scale) {
                case CLOSEST -> 1; case CLOSE -> 2; case NORMAL -> 4; case FAR -> 8; default -> 16;
            };
            // 128 px sur la carte, curseur en demi-pixels (256 unités) → *2/blocksPerPixel
            return (int) Math.round((double) (worldCoord - center) / blocksPerPixel * 2.0);
        }

        private byte directionFromYaw(float yaw) {
            // 16 directions ; 0 = sud (référentiel cartes Minecraft)
            return (byte) (((int) ((yaw + 360) / 22.5)) & 0x0F);
        }
    }
}
