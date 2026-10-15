package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.team.TeamManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.*;

/**
 * Système de bushes (buissons) façon LoL.
 *
 * Règles :
 *  - Un bush = un ensemble de blocs identiques (configurés) collés entre eux.
 *  - Un joueur dans/sur un bush est INVISIBLE pour les ennemis uniquement.
 *  - Une équipe voit l'intérieur d'un bush si l'un de ses champions vivants y est, ou si l'une
 *    de ses wards y est posée (une ward hors du bush ne voit pas dedans, wiki LoL).
 *  - Attaquer ou lancer un sort ciblé révèle le joueur 2 secondes (prendre des dégâts, non).
 *  - Les alliés voient toujours le joueur.
 * Si le brouillard de guerre est actif, c'est lui qui applique la visibilité (avec hidesFrom) :
 * les deux tâches se contredisaient (le BushManager réaffichait tout ennemi hors bush).
 */
public class BushManager implements Listener {

    private final TeamManager teamManager;

    // Blocs qui constituent un bush (depuis la config)
    private final Set<Material> bushBlocks = new HashSet<>();

    // Joueur → identifiant du bush où il se trouve (null si hors bush)
    private final Map<UUID, String> playerBush = new HashMap<>();
    // CACHE : clé de bloc "world:x,y,z" → identifiant du bush (évite de refaire le flood-fill)
    private final Map<String, String> bushIdCache = new HashMap<>();
    // CACHE inverse : identifiant de bush → ensemble des clés de blocs (pour invalidation)
    private final Map<String, Set<String>> bushBlocksCache = new HashMap<>();
    // Joueur révélé suite à des dégâts → timestamp d'expiration
    private final Map<UUID, Long> revealedUntil = new HashMap<>();

    private static final long REVEAL_AFTER_ATTACK_MS = 2000L;

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
        for (Player p : WorldContext.getGamePlayers()) {
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

        // Brouillard de guerre actif : il gère toute la visibilité (bushes compris)
        var fog = LolPlugin.getInstance().getFogOfWarManager();
        if (fog != null && fog.isEnabled()) return;

        // 2. Recalculer la visibilité — uniquement pour les paires proches
        //    et seulement si la cible est dans un bush (sinon toujours visible).
        var players = new ArrayList<>(WorldContext.getGamePlayers());
        for (Player target : players) {
            if (!champMgr.hasChampion(target)) continue;
            boolean targetInBush = playerBush.containsKey(target.getUniqueId());

            for (Player viewer : players) {
                if (viewer.equals(target)) continue;
                if (!champMgr.hasChampion(viewer)) continue;

                // Si la cible n'est PAS dans un bush, elle est visible : on s'assure
                // juste qu'elle n'est pas restée masquée d'un tick précédent.
                if (!targetInBush) {
                    if (!viewer.canSee(target)) showTo(viewer, target);
                    continue;
                }

                // Optimisation distance : au-delà de 48 blocs, inutile de calculer
                if (!viewer.getWorld().equals(target.getWorld())
                        || viewer.getLocation().distanceSquared(target.getLocation()) > 48*48) {
                    continue;
                }

                updateVisibility(viewer, target);
            }
        }
    }

    /** Détermine si `target` doit être visible pour `viewer` (brouillard de guerre désactivé). */
    private void updateVisibility(Player viewer, Player target) {
        if (hidesFrom(viewer, target)) hideFrom(viewer, target);
        else showTo(viewer, target);
    }

    /**
     * Le bush cache-t-il {@code target} à {@code viewer} ? Oui si target est un ennemi dans un
     * bush, non révélé par une attaque, et que l'équipe du viewer n'a ni champion vivant ni
     * ward active dans ce bush.
     */
    public boolean hidesFrom(Player viewer, Player target) {
        if (!teamManager.areEnemies(viewer, target)) return false;
        return hidesFromTeam(teamManager.getTeam(viewer), target);
    }

    /**
     * Le bush cache-t-il {@code target} au sbire {@code viewer} ? Mêmes règles que pour un
     * champion, et un sbire dans le même bush que sa cible la voit (LoL).
     */
    public boolean hidesFromMinion(org.bukkit.entity.LivingEntity viewer, TeamManager.Team viewerTeam, Player target) {
        if (viewerTeam == null || viewerTeam == teamManager.getTeam(target)) return false;
        String targetBush = playerBush.get(target.getUniqueId());
        if (targetBush == null) return false;
        if (targetBush.equals(bushIdAt(viewer.getLocation()))) return false;
        return hidesFromTeam(viewerTeam, target);
    }

    private boolean hidesFromTeam(TeamManager.Team viewerTeam, Player target) {
        String targetBush = playerBush.get(target.getUniqueId());
        if (targetBush == null || isRevealed(target)) return false;
        for (var e : playerBush.entrySet()) {
            if (!targetBush.equals(e.getValue())) continue;
            Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (p != null && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && teamManager.getTeam(p) == viewerTeam) return false; // un allié est dans le bush
        }
        var wardMgr = LolPlugin.getInstance().getWardManager();
        return wardMgr == null || viewerTeam == null || !wardMgr.hasVisionAt(viewerTeam, target.getLocation());
    }

    /** Identifiant du bush à cette position (null hors bush). */
    public String bushIdAt(Location loc) {
        return loc.getWorld() == null ? null : getBushAt(loc);
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
        String startKey = blockKey(start);

        // 1. Cache hit : on a déjà calculé le bush de ce bloc
        String cached = bushIdCache.get(startKey);
        if (cached != null) {
            // Vérifier que le cache est encore valide (le bloc est toujours un bush)
            if (isBushBlock(start)) return cached;
            // Sinon invalider ce bush entier
            invalidateBush(cached);
        }

        // 2. Cache miss : flood-fill pour trouver tous les blocs connexes
        Material type = start.getType();
        Set<String> visited = new HashSet<>();
        Set<String> blockKeys = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        int minX = start.getX(), minZ = start.getZ();

        int safety = 0;
        while (!queue.isEmpty() && safety < 500) {
            Block b = queue.poll();
            String key = blockKey(b);
            if (visited.contains(key)) continue;
            visited.add(key);
            blockKeys.add(key);
            safety++;

            if (b.getX() < minX) minX = b.getX();
            if (b.getZ() < minZ) minZ = b.getZ();

            int[][] offsets = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
            for (int[] off : offsets) {
                Block n = b.getRelative(off[0], off[1], off[2]);
                String nKey = blockKey(n);
                if (!visited.contains(nKey) && n.getType() == type) {
                    queue.add(n);
                }
            }
        }

        String id = start.getWorld().getName() + ":" + minX + "," + minZ;

        // 3. Mettre en cache tous les blocs de ce bush
        for (String key : blockKeys) {
            bushIdCache.put(key, id);
        }
        bushBlocksCache.put(id, blockKeys);

        return id;
    }

    private String blockKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** Invalide le cache d'un bush (quand un de ses blocs change). */
    private void invalidateBush(String bushId) {
        Set<String> keys = bushBlocksCache.remove(bushId);
        if (keys != null) {
            for (String key : keys) bushIdCache.remove(key);
        }
    }

    /**
     * Invalide le cache autour d'une position (appelé quand un bloc est posé/cassé).
     * Public pour être branché sur les events block place/break.
     */
    public void invalidateAround(Location loc) {
        String key = loc.getWorld().getName() + ":"
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        String bushId = bushIdCache.get(key);
        if (bushId != null) invalidateBush(bushId);
        // Invalider aussi les voisins immédiats (un nouveau bloc peut fusionner 2 bushes)
        int[][] offsets = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
        for (int[] off : offsets) {
            String nKey = loc.getWorld().getName() + ":"
                    + (loc.getBlockX()+off[0]) + "," + (loc.getBlockY()+off[1]) + "," + (loc.getBlockZ()+off[2]);
            String nBush = bushIdCache.get(nKey);
            if (nBush != null) invalidateBush(nBush);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // RÉVÉLATION PAR DÉGÂTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Le joueur attaque (champion, sbire, monstre) ou lance un sort : il est révélé 2 secondes,
     * même dans un bush, si un champion ou un sbire ennemi est à proximité. Avant, c'était la
     * VICTIME qui était révélée 5 s, puis l'attaquant même sans aucun ennemi autour.
     */
    public void revealOnAttack(Player player) {
        if (!hasEnemyWitness(player)) return;
        revealedUntil.put(player.getUniqueId(),
                System.currentTimeMillis() + REVEAL_AFTER_ATTACK_MS);
    }

    // Rayon dans lequel un champion ou un sbire ennemi voit l'attaquant sortir du bush (blocs)
    private static final double REVEAL_WITNESS_RANGE = 10.0;

    /**
     * Un champion vivant ou un sbire ennemi est-il assez proche pour voir l'attaque ? Les
     * monstres de la jungle ne comptent pas : attaquer près d'eux ne révèle pas.
     */
    private boolean hasEnemyWitness(Player player) {
        var team = teamManager.getTeam(player);
        if (team == null) return false;
        var cm = LolPlugin.getInstance().getChampionManager();
        double r = REVEAL_WITNESS_RANGE;
        for (var e : player.getNearbyEntities(r, r, r)) {
            if (e.getLocation().distanceSquared(player.getLocation()) > r * r) continue;
            if (e instanceof Player p) {
                if (cm.hasChampion(p) && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                        && teamManager.getTeam(p) != null && teamManager.getTeam(p) != team) return true;
            } else if (e instanceof org.bukkit.entity.LivingEntity le && !le.isDead()
                    && fr.lolmc.game.MinionManager.isMinion(le)
                    && !fr.lolmc.game.JungleManager.isJungleMonster(le)) { // un monstre ne révèle pas
                var mt = fr.lolmc.game.MinionManager.getMinionTeam(le);
                if (mt != null && mt != team) return true;
            }
        }
        return false;
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
            for (Player other : WorldContext.getGamePlayers()) {
                if (!other.equals(p)) {
                    other.showPlayer(LolPlugin.getInstance(), p);
                    p.showPlayer(LolPlugin.getInstance(), other);
                }
            }
        }
    }

    // ── Invalidation du cache sur changement de blocs ─────────────

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isBushBlock(e.getBlock()) || bushBlocks.contains(e.getBlockReplacedState().getType())) {
            invalidateAround(e.getBlock().getLocation());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        invalidateAround(e.getBlock().getLocation());
    }

}
