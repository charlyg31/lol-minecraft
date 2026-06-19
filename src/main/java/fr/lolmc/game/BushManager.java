package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Système de bushes (buissons) façon LoL.
 *
 * Règles :
 *  - Un bush = un ensemble de blocs identiques (configurés) collés entre eux.
 *  - Un joueur dans/sur un bush est INVISIBLE pour les ennemis uniquement.
 *  - Si un ennemi est dans le MÊME bush, le joueur redevient visible (pour cet ennemi).
 *  - Si le joueur prend des dégâts, il est révélé 5 secondes.
 *  - Les alliés voient toujours le joueur.
 */
public class BushManager {

    private final TeamManager teamManager;

    // Blocs qui constituent un bush (depuis la config)
    private final Set<Material> bushBlocks = new HashSet<>();

    // Joueur → identifiant du bush où il se trouve (null si hors bush)
    private final Map<UUID, String> playerBush = new HashMap<>();
    // Joueur révélé suite à des dégâts → timestamp d'expiration
    private final Map<UUID, Long> revealedUntil = new HashMap<>();

    private static final long REVEAL_AFTER_DAMAGE_MS = 5000L;

    public BushManager(TeamManager teamManager) {
        this.teamManager = teamManager;
        loadConfig();
        startBushTask();
    }

    private void loadConfig() {
        var config = LolPlugin.getInstance().getConfig();
        List<String> blocks = config.getStringList("bushes.blocks");
        for (String name : blocks) {
            try {
                bushBlocks.add(Material.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                LolPlugin.getInstance().getLogger().warning("Bloc de bush invalide: " + name);
            }
        }
        if (bushBlocks.isEmpty()) {
            // Valeurs par défaut si rien de configuré
            bushBlocks.add(Material.TALL_GRASS);
            bushBlocks.add(Material.LARGE_FERN);
        }
        LolPlugin.getInstance().getLogger().info("Bushes: " + bushBlocks.size() + " types de blocs.");
    }

    // ══════════════════════════════════════════════════════════════
    // TÂCHE PRINCIPALE — détection et mise à jour de la visibilité
    // ══════════════════════════════════════════════════════════════

    private void startBushTask() {
        new BukkitRunnable() {
            @Override public void run() {
                updateAllPlayers();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 5L); // 4x par seconde
    }

    private void updateAllPlayers() {
        var champMgr = LolPlugin.getInstance().getChampionManager();
        long now = System.currentTimeMillis();

        // 1. Mettre à jour le bush de chaque joueur
        for (Player p : LolPlugin.getInstance().getServer().getOnlinePlayers()) {
            if (!champMgr.hasChampion(p)) {
                playerBush.remove(p.getUniqueId());
                continue;
            }
            String bushId = getBushAt(p.getLocation());
            if (bushId != null) {
                playerBush.put(p.getUniqueId(), bushId);
            } else {
                playerBush.remove(p.getUniqueId());
            }
        }

        // Nettoyer les révélations expirées
        revealedUntil.entrySet().removeIf(e -> now > e.getValue());

        // 2. Recalculer la visibilité pour chaque paire de joueurs
        var players = new ArrayList<>(LolPlugin.getInstance().getServer().getOnlinePlayers());
        for (Player viewer : players) {
            if (!champMgr.hasChampion(viewer)) continue;
            for (Player target : players) {
                if (viewer.equals(target)) continue;
                if (!champMgr.hasChampion(target)) continue;
                updateVisibility(viewer, target);
            }
        }
    }

    /**
     * Détermine si `target` doit être visible pour `viewer`.
     */
    private void updateVisibility(Player viewer, Player target) {
        // Les alliés (et soi-même) se voient toujours
        if (!teamManager.areEnemies(viewer, target)) {
            showTo(viewer, target);
            return;
        }

        // target est-il caché dans un bush ?
        String targetBush = playerBush.get(target.getUniqueId());
        if (targetBush == null) {
            // Pas dans un bush → visible
            showTo(viewer, target);
            return;
        }

        // target a-t-il été révélé par des dégâts récents ?
        if (isRevealed(target)) {
            showTo(viewer, target);
            return;
        }

        // L'ennemi (viewer) est-il dans le MÊME bush ?
        String viewerBush = playerBush.get(viewer.getUniqueId());
        if (targetBush.equals(viewerBush)) {
            // Même bush → visible
            showTo(viewer, target);
            return;
        }

        // Sinon : target est caché pour viewer
        hideFrom(viewer, target);
    }

    private void showTo(Player viewer, Player target) {
        viewer.showPlayer(LolPlugin.getInstance(), target);
    }

    private void hideFrom(Player viewer, Player target) {
        viewer.hidePlayer(LolPlugin.getInstance(), target);
    }

    // ══════════════════════════════════════════════════════════════
    // DÉTECTION DES BUSHES (blocs connexes)
    // ══════════════════════════════════════════════════════════════

    /**
     * Retourne l'identifiant du bush à une position (ou null).
     * On vérifie le bloc aux pieds et au niveau du corps.
     */
    private String getBushAt(Location loc) {
        Block feet = loc.getBlock();
        Block body = loc.clone().add(0, 1, 0).getBlock();

        if (isBushBlock(feet)) return bushId(feet);
        if (isBushBlock(body)) return bushId(body);
        return null;
    }

    private boolean isBushBlock(Block block) {
        return bushBlocks.contains(block.getType());
    }

    /**
     * Identifiant d'un bush = centre du groupe de blocs connexes.
     * Deux joueurs sur le même groupe de blocs auront le même ID.
     * On calcule via un flood-fill limité.
     */
    private String bushId(Block start) {
        // Flood-fill pour trouver tous les blocs connexes du même type
        Material type = start.getType();
        Set<String> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        int minX = start.getX(), minZ = start.getZ();

        int safety = 0;
        while (!queue.isEmpty() && safety < 200) {
            Block b = queue.poll();
            String key = b.getX() + "," + b.getY() + "," + b.getZ();
            if (visited.contains(key)) continue;
            visited.add(key);
            safety++;

            // Garder le coin minimal comme ancre d'identifiant
            if (b.getX() < minX) minX = b.getX();
            if (b.getZ() < minZ) minZ = b.getZ();

            // Voisins horizontaux (4 directions) et verticaux proches
            int[][] offsets = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
            for (int[] off : offsets) {
                Block n = b.getRelative(off[0], off[1], off[2]);
                String nKey = n.getX() + "," + n.getY() + "," + n.getZ();
                if (!visited.contains(nKey) && n.getType() == type) {
                    queue.add(n);
                }
            }
        }

        // L'ID du bush = monde + coin minimal (stable pour tout le groupe)
        return start.getWorld().getName() + ":" + minX + "," + minZ;
    }

    // ══════════════════════════════════════════════════════════════
    // RÉVÉLATION PAR DÉGÂTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Appelé quand un joueur prend des dégâts : le révèle 5s.
     */
    public void revealOnDamage(Player player) {
        revealedUntil.put(player.getUniqueId(),
                System.currentTimeMillis() + REVEAL_AFTER_DAMAGE_MS);
    }

    public boolean isRevealed(Player player) {
        Long until = revealedUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    public boolean isInBush(Player player) {
        return playerBush.containsKey(player.getUniqueId());
    }

    // ══════════════════════════════════════════════════════════════
    // NETTOYAGE
    // ══════════════════════════════════════════════════════════════

    public void cleanup(UUID uuid) {
        playerBush.remove(uuid);
        revealedUntil.remove(uuid);
        // S'assurer que ce joueur est de nouveau visible pour tous
        Player p = LolPlugin.getInstance().getServer().getPlayer(uuid);
        if (p != null) {
            for (Player other : LolPlugin.getInstance().getServer().getOnlinePlayers()) {
                if (!other.equals(p)) {
                    other.showPlayer(LolPlugin.getInstance(), p);
                    p.showPlayer(LolPlugin.getInstance(), other);
                }
            }
        }
    }
}
