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

        // Former 2 équipes de 5
        List<UUID> blue = new ArrayList<>();
        List<UUID> red = new ArrayList<>();

        // Trier les groupes du plus grand au plus petit (placer les gros groupes d'abord)
        List<List<UUID>> sorted = new ArrayList<>(queue);
        sorted.sort((a, b) -> b.size() - a.size());

        // Placer chaque groupe dans l'équipe avec le plus de place
        // (en gardant les groupes intacts tant que possible)
        List<UUID> leftovers = new ArrayList<>();
        for (List<UUID> group : sorted) {
            if (blue.size() + group.size() <= PLAYERS_PER_TEAM
                    && (blue.size() <= red.size() || red.size() + group.size() > PLAYERS_PER_TEAM)) {
                blue.addAll(group);
            } else if (red.size() + group.size() <= PLAYERS_PER_TEAM) {
                red.addAll(group);
            } else {
                // Le groupe ne rentre pas entier → on le sépare (autorisé)
                leftovers.addAll(group);
            }
        }

        // Compléter les équipes avec les leftovers (joueurs séparés)
        for (UUID id : leftovers) {
            if (blue.size() < PLAYERS_PER_TEAM) blue.add(id);
            else if (red.size() < PLAYERS_PER_TEAM) red.add(id);
        }

        // Vérifier qu'on a bien 5v5
        if (blue.size() != PLAYERS_PER_TEAM || red.size() != PLAYERS_PER_TEAM) {
            // Sécurité : si l'algo n'a pas équilibré, on remet tout à plat
            List<UUID> all = new ArrayList<>();
            for (List<UUID> g : sorted) all.addAll(g);
            blue.clear(); red.clear();
            for (int i = 0; i < all.size() && i < TOTAL_PLAYERS; i++) {
                if (i % 2 == 0) blue.add(all.get(i));
                else red.add(all.get(i));
            }
        }

        // Retirer ces joueurs de la file
        Set<UUID> matched = new HashSet<>();
        matched.addAll(blue);
        matched.addAll(red);
        queue.removeIf(group -> group.stream().anyMatch(matched::contains));
        inQueue.removeAll(matched);

        // Assigner les équipes
        for (UUID id : blue) teamManager.setTeam(Bukkit.getPlayer(id), Team.BLUE);
        for (UUID id : red)  teamManager.setTeam(Bukkit.getPlayer(id), Team.RED);

        startMatch(blue, red);
    }

    private void startMatch(List<UUID> blue, List<UUID> red) {
        announce(blue, red);
        // Le système de partie (téléportation, spawn) sera branché ici plus tard.
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
