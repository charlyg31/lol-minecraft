package fr.lolmc.matchmaking;

import fr.lolmc.LolPlugin;
import fr.lolmc.matchmaking.PartyManager.Party;
import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * File d'attente et matchmaking.
 *
 * Règles :
 *  - Les groupes (PartyManager) entrent en file ensemble.
 *  - Une partie démarre quand 10 joueurs sont en attente.
 *  - Le matchmaking forme 2 équipes de 5 en gardant les groupes intacts
 *    si possible, mais peut séparer un groupe pour équilibrer (selon le choix).
 */
public class MatchmakingManager {

    private static final int PLAYERS_PER_TEAM = 5;
    private static final int TOTAL_PLAYERS = PLAYERS_PER_TEAM * 2;

    private final PartyManager partyManager;
    private final TeamManager teamManager;

    // File d'attente : liste de groupes (chaque groupe = liste d'UUID)
    private final List<List<UUID>> queue = new ArrayList<>();
    // Joueurs actuellement en file (pour éviter les doublons)
    private final Set<UUID> inQueue = new HashSet<>();

    public MatchmakingManager(PartyManager partyManager, TeamManager teamManager) {
        this.partyManager = partyManager;
        this.teamManager = teamManager;
    }

    // ── Entrer / sortir de la file ────────────────────────────────

    public void joinQueue(Player player) {
        if (inQueue.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("Tu es déjà en file d'attente.", NamedTextColor.YELLOW));
            return;
        }

        // Récupérer le groupe du joueur (ou solo)
        Party party = partyManager.getParty(player);
        List<UUID> group;
        if (party != null) {
            // Seul le chef peut lancer la recherche pour le groupe
            if (!party.leader.equals(player.getUniqueId())) {
                player.sendMessage(Component.text("❌ Seul le chef du groupe lance la recherche.", NamedTextColor.RED));
                return;
            }
            group = new ArrayList<>(party.members);
        } else {
            group = new ArrayList<>(List.of(player.getUniqueId()));
        }

        // Vérifier qu'aucun membre n'est déjà en file
        for (UUID id : group) {
            if (inQueue.contains(id)) {
                player.sendMessage(Component.text("❌ Un membre est déjà en file.", NamedTextColor.RED));
                return;
            }
        }

        queue.add(group);
        inQueue.addAll(group);

        // Notifier le groupe
        for (UUID id : group) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Component.text("🔍 Recherche de partie... (" + countInQueue()
                        + "/" + TOTAL_PLAYERS + " joueurs)", NamedTextColor.AQUA));
            }
        }

        tryStartMatch();
    }

    public void leaveQueue(Player player) {
        // Retirer le groupe entier du joueur
        List<UUID> toRemove = null;
        for (List<UUID> group : queue) {
            if (group.contains(player.getUniqueId())) { toRemove = group; break; }
        }
        if (toRemove != null) {
            queue.remove(toRemove);
            inQueue.removeAll(toRemove);
            for (UUID id : toRemove) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(Component.text("Recherche annulée.", NamedTextColor.GRAY));
            }
        } else {
            player.sendMessage(Component.text("Tu n'es pas en file d'attente.", NamedTextColor.GRAY));
        }
    }

    // ── Matchmaking ───────────────────────────────────────────────

    private void tryStartMatch() {
        if (countInQueue() < TOTAL_PLAYERS) return;

        // Trouver une combinaison de groupes formant 2 équipes de 5 EXACTEMENT,
        // sans jamais séparer un groupe (bin packing).
        List<List<UUID>> groups = new ArrayList<>(queue);
        Result result = findTwoTeams(groups);
        if (result == null) {
            // 10+ joueurs en file mais aucune combinaison ne forme 5+5
            // sans séparer de groupe (ex: 3+3+3+1). On informe et on attend.
            for (UUID id : inQueue) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendActionBar(net.kyori.adventure.text.Component.text(
                    "⏳ En attente d'une composition d'équipes compatible...",
                    net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            }
            return;
        }

        // Retirer de la file tous les groupes utilisés
        Set<List<UUID>> used = new HashSet<>();
        used.addAll(result.blueGroups);
        used.addAll(result.redGroups);
        queue.removeAll(used);
        for (List<UUID> g : used) inQueue.removeAll(g);

        // Aplatir en listes de joueurs
        List<UUID> blue = new ArrayList<>();
        List<UUID> red = new ArrayList<>();
        for (List<UUID> g : result.blueGroups) blue.addAll(g);
        for (List<UUID> g : result.redGroups)  red.addAll(g);

        // Assigner les équipes
        for (UUID id : blue) { Player p = Bukkit.getPlayer(id); if (p != null) teamManager.setTeam(p, Team.BLUE); }
        for (UUID id : red)  { Player p = Bukkit.getPlayer(id); if (p != null) teamManager.setTeam(p, Team.RED); }

        startMatch(blue, red);

        // Relancer au cas où il resterait assez de monde pour une 2e partie
        if (countInQueue() >= TOTAL_PLAYERS) tryStartMatch();
    }

    /** Résultat du matchmaking : les groupes de chaque équipe. */
    private static class Result {
        final List<List<UUID>> blueGroups;
        final List<List<UUID>> redGroups;
        Result(List<List<UUID>> b, List<List<UUID>> r) { blueGroups = b; redGroups = r; }
    }

    /**
     * Cherche 2 sous-ensembles disjoints de groupes faisant chacun PLAYERS_PER_TEAM (5),
     * sans séparer aucun groupe.
     * 1) Trouve un 1er sous-ensemble = 5 (équipe bleue)
     * 2) Parmi le reste, trouve un 2e sous-ensemble = 5 (équipe rouge)
     */
    private Result findTwoTeams(List<List<UUID>> groups) {
        // Toutes les combinaisons de groupes dont la somme = 5
        List<List<Integer>> combos = subsetsSummingTo(groups, PLAYERS_PER_TEAM);
        for (List<Integer> blueIdx : combos) {
            // Groupes restants après avoir retiré l'équipe bleue
            Set<Integer> usedBlue = new HashSet<>(blueIdx);
            List<List<UUID>> remaining = new ArrayList<>();
            List<Integer> remapIndex = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                if (!usedBlue.contains(i)) { remaining.add(groups.get(i)); remapIndex.add(i); }
            }
            // Chercher une équipe rouge = 5 dans le reste
            List<List<Integer>> redCombos = subsetsSummingTo(remaining, PLAYERS_PER_TEAM);
            if (!redCombos.isEmpty()) {
                List<List<UUID>> blueG = new ArrayList<>();
                for (int i : blueIdx) blueG.add(groups.get(i));
                List<List<UUID>> redG = new ArrayList<>();
                for (int i : redCombos.get(0)) redG.add(remaining.get(i));
                return new Result(blueG, redG);
            }
        }
        return null;
    }

    /**
     * Retourne toutes les combinaisons d'indices de groupes dont la somme des tailles = target.
     * (backtracking)
     */
    private List<List<Integer>> subsetsSummingTo(List<List<UUID>> groups, int target) {
        List<List<Integer>> results = new ArrayList<>();
        backtrack(groups, target, 0, new ArrayList<>(), 0, results);
        return results;
    }

    private void backtrack(List<List<UUID>> groups, int target, int start,
                           List<Integer> current, int currentSum, List<List<Integer>> results) {
        if (currentSum == target) {
            results.add(new ArrayList<>(current));
            return;
        }
        if (currentSum > target) return;
        for (int i = start; i < groups.size(); i++) {
            current.add(i);
            backtrack(groups, target, i + 1, current, currentSum + groups.get(i).size(), results);
            current.remove(current.size() - 1);
        }
    }

    // Positions sauvegardées avant l'envoi en partie
    private final java.util.Map<UUID, org.bukkit.Location> preGameLocations
        = new java.util.concurrent.ConcurrentHashMap<>();

    private void startMatch(List<UUID> blue, List<UUID> red) {
        announce(blue, red);
        // Notifier le bridge (lobby cross-serveur)
        var bridge = LolPlugin.getInstance().getBridgeManager();
        if (bridge != null && bridge.isEnabled()) {
            java.util.List<java.util.UUID> allPlayers = new java.util.ArrayList<>(blue);
            allPlayers.addAll(red);
            bridge.notifyGameStart(allPlayers);
        }
        // Sauvegarder les positions avant la partie
        for (UUID id : blue) { Player p = Bukkit.getPlayer(id); if (p != null) preGameLocations.put(id, p.getLocation().clone()); }
        for (UUID id : red)  { Player p = Bukkit.getPlayer(id); if (p != null) preGameLocations.put(id, p.getLocation().clone()); }

        // Créer une instance isolée (copie du monde template)
        final List<UUID> blueF = blue, redF = red;
        LolPlugin.getInstance().getInstanceManager().createInstance(blue, red, instance -> {
            if (instance == null) {
                LolPlugin.getInstance().getLogger().severe("[Match] Impossible de créer l'instance!");
                // Notifier les joueurs
                for (UUID id : blueF) { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage("§cErreur: impossible de créer la partie. Réessaie."); }
                for (UUID id : redF)  { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage("§cErreur: impossible de créer la partie. Réessaie."); }
                return;
            }
            // Téléporter les joueurs dans l'instance
            var mapMgr = instance.getMapManager();
            for (UUID id : blueF) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    org.bukkit.Location spawn = mapMgr.getSpawn(fr.lolmc.team.TeamManager.Team.BLUE, 0);
                    if (spawn != null) p.teleport(spawn);
                    LolPlugin.getInstance().getMinimapManager().giveMinimap(p);
                    p.sendMessage(net.kyori.adventure.text.Component.text(
                        "⚔ Instance #" + instance.getId() + " — Partie trouvée!",
                        net.kyori.adventure.text.format.NamedTextColor.GOLD));
                }
            }
            for (UUID id : redF) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    org.bukkit.Location spawn = mapMgr.getSpawn(fr.lolmc.team.TeamManager.Team.RED, 0);
                    if (spawn != null) p.teleport(spawn);
                    LolPlugin.getInstance().getMinimapManager().giveMinimap(p);
                    p.sendMessage(net.kyori.adventure.text.Component.text(
                        "⚔ Instance #" + instance.getId() + " — Partie trouvée!",
                        net.kyori.adventure.text.format.NamedTextColor.GOLD));
                }
            }
            // Phase de ban → pick → démarrage (3s après téléportation)
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() {
                    LolPlugin.getInstance().getChampSelectManager().startBanPhase();
                }
            }.runTaskLater(LolPlugin.getInstance(), 60L);
        });
    }

    /** Renvoie un joueur à sa position d'avant la partie (appelé à la fin). */
    public void returnPlayerToPreGameLocation(Player player) {
        org.bukkit.Location loc = preGameLocations.remove(player.getUniqueId());
        if (loc != null && loc.getWorld() != null) {
            player.teleport(loc);
        }
    }

    /** Renvoie tous les joueurs à leur position d'avant la partie. */
    public void returnAllToPreGameLocations() {
        for (UUID id : new java.util.HashSet<>(preGameLocations.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) returnPlayerToPreGameLocation(p);
            else preGameLocations.remove(id);
        }
    }

    private void announce(List<UUID> blue, List<UUID> red) {
        Component header = Component.text("⚔ PARTIE TROUVÉE! ", NamedTextColor.GOLD);
        for (UUID id : blue) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(header);
                p.sendMessage(Component.text("Tu es dans l'équipe ", NamedTextColor.GRAY)
                        .append(Component.text("BLEUE", NamedTextColor.BLUE)));
            }
        }
        for (UUID id : red) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(header);
                p.sendMessage(Component.text("Tu es dans l'équipe ", NamedTextColor.GRAY)
                        .append(Component.text("ROUGE", NamedTextColor.RED)));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    public int countInQueue() {
        return inQueue.size();
    }

    public boolean isInQueue(Player player) {
        return inQueue.contains(player.getUniqueId());
    }

    public void cleanup(UUID uuid) {
        if (!inQueue.contains(uuid)) return;
        List<UUID> group = null;
        for (List<UUID> g : queue) if (g.contains(uuid)) { group = g; break; }
        if (group != null) {
            group.remove(uuid);
            if (group.isEmpty()) queue.remove(group);
        }
        inQueue.remove(uuid);
    }
}
