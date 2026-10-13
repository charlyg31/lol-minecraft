package fr.lolmc.bungee.queue;

import fr.lolmc.bungee.LolBungeePlugin;
import fr.lolmc.bungee.party.BungeePartyManager;
import fr.lolmc.bungee.role.BungeeRoleManager;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * File d'attente cross-serveur gérée depuis le proxy.
 *
 * Deux files indépendantes : normale (/lol) et classée (/lolr) — un joueur n'est jamais
 * mélangé à une partie d'un autre mode.
 *
 * Logique de remplissage :
 *   1. checkAndStartGame() : tente de former une partie de N joueurs
 *   2. Si N joueurs sont disponibles → lancer directement
 *   3. Si N-1 joueurs sont disponibles via un sous-ensemble cohérent
 *      ET qu'un joueur solo peut remplir le poste manquant →
 *      proposer le poste au premier solo éligible (timeout 20s).
 *      Les N-1 joueurs sont réservés pendant la proposition.
 *
 * Postes : chaque rôle a ceil(N / 5) places (2 pour une partie à 10 : un par équipe).
 */
public class BungeeQueueManager {

    private static final List<String> ROLES = List.of("TOP", "JUNGLE", "MID", "ADC", "SUPPORT");

    private final LolBungeePlugin    plugin;
    private final BungeePartyManager partyManager;
    private final BungeeRoleManager  roleManager;

    // File principale (ordre d'arrivée), tous modes confondus
    private final Deque<UUID> queue = new ConcurrentLinkedDeque<>();
    // Mode de chaque joueur en file : true = classé
    private final Map<UUID, Boolean> rankedOf = new ConcurrentHashMap<>();
    // Joueurs réservés par une proposition de fill en cours (exclus des autres matchs)
    private final Set<UUID> reserved = ConcurrentHashMap.newKeySet();

    // Propositions en attente : solo UUID → proposition
    private final Map<UUID, FillProposal> pendingProposals = new ConcurrentHashMap<>();

    private final int    playersNeeded;
    private final String gameServer;
    /** Postes disponibles dans une partie (chaque rôle répété ceil(N/5) fois). */
    private final List<String> slots;

    public BungeeQueueManager(LolBungeePlugin plugin, BungeePartyManager pm,
                               BungeeRoleManager rlm) {
        this.plugin        = plugin;
        this.partyManager  = pm;
        this.roleManager   = rlm;
        this.playersNeeded = Math.max(1, plugin.getConfig().getInt("players-per-game", 10));
        this.gameServer    = plugin.getConfig().getString("game-server", "lolmc-01");
        int perRole = (int) Math.ceil(playersNeeded / (double) ROLES.size());
        List<String> s = new ArrayList<>();
        for (int i = 0; i < perRole; i++) s.addAll(ROLES);
        this.slots = List.copyOf(s);
    }

    // ══════════════════════════════════════════════════════════════════════
    // REJOINDRE / QUITTER
    // ══════════════════════════════════════════════════════════════════════

    /** Rejoint la file normale. */
    public void join(ProxiedPlayer player) {
        join(player, false);
    }

    /** Rejoint la file (avec tout son groupe) dans le mode demandé. */
    public synchronized void join(ProxiedPlayer player, boolean ranked) {
        if (isInQueue(player.getUniqueId())) return;
        List<UUID> group = partyManager.getPartyMembers(player.getUniqueId());
        for (UUID uid : group) {
            if (!queue.contains(uid)) queue.add(uid);
            rankedOf.put(uid, ranked);
        }
        player.sendMessage(new TextComponent(ranked
                ? "§6Vous êtes dans la file classée."
                : "§aVous êtes dans la file d'attente."));
        checkAndStartGame(ranked);
    }

    public synchronized void leave(ProxiedPlayer player) {
        UUID uid = player.getUniqueId();
        queue.remove(uid);
        rankedOf.remove(uid);
        // Annuler une proposition en attente si le joueur quitte
        FillProposal prop = pendingProposals.remove(uid);
        if (prop != null) {
            prop.cancel();
            releaseProposal(prop);
        }
    }

    /** Test : lance une partie immédiatement pour un seul joueur, sans passer par la file. */
    public synchronized void launchSolo(ProxiedPlayer player, String role) {
        queue.remove(player.getUniqueId());
        rankedOf.remove(player.getUniqueId());
        plugin.getRoleManager().setRoles(player.getUniqueId(), List.of(role));
        launchGame(new ArrayList<>(List.of(player.getUniqueId())), null, null, false);
    }

    public boolean isInQueue(UUID uuid) { return queue.contains(uuid); }
    public int getQueueSize()           { return queue.size(); }
    public int getPlayersNeeded()       { return playersNeeded; }

    // ══════════════════════════════════════════════════════════════════════
    // ACCEPTER / REFUSER UNE PROPOSITION DE FILL
    // ══════════════════════════════════════════════════════════════════════

    public synchronized void acceptFill(ProxiedPlayer player) {
        FillProposal prop = pendingProposals.remove(player.getUniqueId());
        if (prop == null) {
            player.sendMessage(new TextComponent("§7Aucune proposition en cours."));
            return;
        }
        prop.cancel(); // annuler le timeout
        // Le groupe a pu se défaire pendant la proposition (déconnexion, sortie de file)
        for (UUID uid : prop.gamePlayers) {
            if (!queue.contains(uid) || ProxyServer.getInstance().getPlayer(uid) == null) {
                releaseProposal(prop);
                player.sendMessage(new TextComponent("§7La partie n'est plus disponible — tu restes en file."));
                checkAndStartGame(prop.ranked);
                return;
            }
        }
        player.sendMessage(new TextComponent("§a✔ Tu rejoins la partie !"));
        launchGame(prop.gamePlayers, player.getUniqueId(), prop.filledRole, prop.ranked);
    }

    public synchronized void refuseFill(ProxiedPlayer player) {
        FillProposal prop = pendingProposals.remove(player.getUniqueId());
        if (prop == null) {
            player.sendMessage(new TextComponent("§7Aucune proposition en cours."));
            return;
        }
        prop.cancel();
        player.sendMessage(new TextComponent("§7Proposition refusée. Tu restes en file."));
        // Proposer au prochain solo éligible
        tryProposeFill(prop.gamePlayers, prop.filledRole, prop.excludedSolos, prop.ranked);
    }

    // ══════════════════════════════════════════════════════════════════════
    // LOGIQUE PRINCIPALE
    // ══════════════════════════════════════════════════════════════════════

    /** Joueurs disponibles du mode donné (ordre d'arrivée, hors réservés). */
    private List<UUID> available(boolean ranked) {
        List<UUID> list = new ArrayList<>();
        for (UUID uid : queue) {
            if (reserved.contains(uid)) continue;
            if (rankedOf.getOrDefault(uid, false) != ranked) continue;
            list.add(uid);
        }
        return list;
    }

    /**
     * Tente de former une partie complète depuis la file du mode donné.
     *
     * Étape 1 : chercher N joueurs avec une assignation valide.
     * Étape 2 : si N-1 joueurs forment un groupe cohérent avec 1 poste
     *           libre, proposer ce poste aux solos éligibles dans la file.
     */
    private void checkAndStartGame(boolean ranked) {
        List<UUID> allInQueue = available(ranked);

        // ── Étape 1 : N joueurs complets ──────────────────────────────────
        List<UUID> full = findValidGroup(allInQueue, playersNeeded);
        if (full != null) {
            launchGame(full, null, null, ranked);
            return;
        }

        // ── Étape 2 : N-1 joueurs + 1 solo fill ──────────────────────────
        if (playersNeeded < 2 || allInQueue.size() < playersNeeded - 1) return;

        GroupAndMissingRole partial = findGroupWithOneMissing(allInQueue);
        if (partial == null) return;

        tryProposeFill(partial.players, partial.missingRole, new HashSet<>(), ranked);
    }

    /**
     * Cherche le premier solo dans la file qui accepte le rôle manquant,
     * et lui envoie une proposition (timeout 20s).
     *
     * @param gamePlayers  Les N-1 joueurs déjà sélectionnés
     * @param missingRole  Le rôle manquant (ex: "SUPPORT")
     * @param excluded     UUIDs déjà refusés pour cette proposition
     */
    private void tryProposeFill(List<UUID> gamePlayers, String missingRole, Set<UUID> excluded, boolean ranked) {
        Set<UUID> gameSet = new HashSet<>(gamePlayers);
        List<UUID> candidates = new ArrayList<>();
        for (UUID uid : available(ranked)) {
            if (gameSet.contains(uid) || excluded.contains(uid)) continue;
            if (partyManager.inParty(uid)) continue;
            if (pendingProposals.containsKey(uid)) continue;
            if (ProxyServer.getInstance().getPlayer(uid) == null) continue;
            candidates.add(uid);
        }

        for (UUID uid : candidates) {
            Set<String> rolesSet = new HashSet<>(roleManager.getRoles(uid));
            if (rolesSet.contains(missingRole) || rolesSet.containsAll(ROLES)) {
                // Cherche déjà ce rôle → ajout direct sans proposition
                ProxyServer.getInstance().getPlayer(uid).sendMessage(new TextComponent(
                    "§a✔ Tu rejoins une partie en tant que §l" + formatRole(missingRole) + "§a !"));
                reserved.removeAll(gamePlayers);
                launchGame(gamePlayers, uid, missingRole, ranked);
                return;
            }
        }

        if (candidates.isEmpty()) {
            // Plus personne à qui proposer : le groupe redevient disponible
            reserved.removeAll(gamePlayers);
            return;
        }

        // Aucun solo ne cherche ce rôle → proposer au premier solo disponible
        UUID uid = candidates.get(0);
        ProxiedPlayer solo = ProxyServer.getInstance().getPlayer(uid);
        solo.sendMessage(new TextComponent("§6§l⚔ Proposition de partie !"));
        solo.sendMessage(new TextComponent(
            "§7Un groupe cherche un §e" + formatRole(missingRole) + "§7."));
        solo.sendMessage(new TextComponent(
            "§aTape §l/lol accepte §r§7ou §c§l/lol refuse §r§7(§720s§7)"));

        Set<UUID> newExcluded = new HashSet<>(excluded);
        newExcluded.add(uid);

        reserved.addAll(gamePlayers);
        FillProposal proposal = new FillProposal(gamePlayers, missingRole, newExcluded, ranked);
        pendingProposals.put(uid, proposal);

        proposal.timeoutTask = ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            synchronized (BungeeQueueManager.this) {
                FillProposal p = pendingProposals.remove(uid);
                if (p != null) {
                    solo.sendMessage(new TextComponent("§7Proposition expirée — tu restes en file."));
                    tryProposeFill(gamePlayers, missingRole, newExcluded, ranked);
                }
            }
        }, 20, TimeUnit.SECONDS);
    }

    /** Libère les joueurs réservés par une proposition abandonnée. */
    private void releaseProposal(FillProposal prop) {
        reserved.removeAll(prop.gamePlayers);
    }

    // ══════════════════════════════════════════════════════════════════════
    // LANCEMENT DE LA PARTIE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Lance la partie avec les joueurs sélectionnés.
     *
     * @param gamePlayers  Les joueurs du groupe
     * @param fillUUID     Le solo fill (null si partie complète sans fill)
     * @param fillRole     Le rôle assigné au fill
     * @param isRanked     Partie classée
     */
    private void launchGame(List<UUID> gamePlayers, UUID fillUUID, String fillRole, boolean isRanked) {
        List<UUID> allPlayers = new ArrayList<>(gamePlayers);
        if (fillUUID != null) allPlayers.add(fillUUID);
        // Tous les joueurs lancés sortent de la file
        for (UUID uid : allPlayers) {
            queue.remove(uid);
            rankedOf.remove(uid);
            reserved.remove(uid);
        }

        ProxiedPlayer carrier = findCarrier(allPlayers);
        if (carrier == null) return;

        ServerInfo gameServerInfo = ProxyServer.getInstance().getServerInfo(gameServer);
        if (gameServerInfo == null) {
            plugin.getLogger().severe("Serveur de jeu '" + gameServer + "' introuvable !");
            return;
        }

        // Envoyer les données de chaque joueur
        for (UUID uid : allPlayers) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            if (p == null) continue;
            // Si c'est le fill, son rôle assigné est fillRole
            String assignedRole = uid.equals(fillUUID) ? fillRole : null;
            sendPlayerData(p, assignedRole);
        }

        // Connecter après 2s puis envoyer GAME_READY après 6s (temps de connexion)
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            for (UUID uid : allPlayers) {
                ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
                if (p != null) p.connect(gameServerInfo);
            }
        }, 2, TimeUnit.SECONDS);

        // GAME_READY après 6s (2s connect + 4s chargement)
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            String json = "{"
                    + "\"type\":\"GAME_READY\","
                    + "\"ranked\":" + isRanked + ","
                    + "\"count\":" + allPlayers.size() + ","
                    + "\"players\":\""
                    + String.join(",", allPlayers.stream().map(UUID::toString).toList())
                    + "\"}";
            sendPluginMessage(gameServer, json);
            plugin.getLogger().info("[Queue] GAME_READY envoyé pour " + allPlayers.size() + " joueurs (ranked=" + isRanked + ")");
        }, 6, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ALGORITHME DE MATCHING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cherche un groupe de `size` joueurs dans la file avec une assignation
     * de postes valide (matching bipartite joueurs ↔ postes).
     * Retourne la liste des UUIDs sélectionnés, ou null si impossible.
     */
    private List<UUID> findValidGroup(List<UUID> candidates, int size) {
        if (candidates.size() < size) return null;
        // Essayer parmi les 2N premiers (ordre d'arrivée)
        List<UUID> subset = candidates.subList(0, Math.min(size * 2, candidates.size()));
        return findMatchingSubset(subset, size);
    }

    /**
     * Cherche un groupe de N-1 joueurs avec assignation valide,
     * et retourne le groupe + le rôle du poste resté libre.
     */
    private GroupAndMissingRole findGroupWithOneMissing(List<UUID> candidates) {
        int target = playersNeeded - 1;
        if (candidates.size() < target) return null;

        List<UUID> subset = candidates.subList(0, Math.min(target * 2, candidates.size()));
        List<UUID> group = findMatchingSubset(subset, target);
        if (group == null) return null;

        int[] match = computeMatching(group);
        // Premier poste non assigné (parmi les N postes de la partie) = rôle manquant
        for (int si = 0; si < playersNeeded && si < slots.size(); si++) {
            if (match[si] == -1) return new GroupAndMissingRole(group, slots.get(si));
        }
        return null;
    }

    /** Cherche un sous-ensemble de `size` dans candidates avec matching valide. */
    private List<UUID> findMatchingSubset(List<UUID> candidates, int size) {
        if (candidates.size() < size) return null;
        // Essai simple : les `size` premiers — sans couper un groupe en deux
        List<UUID> attempt = takeWholeParties(candidates, size);
        if (attempt != null && hasValidMatching(attempt)) return attempt;

        // Heuristique (pas exhaustive) : groupes d'abord, puis solos
        List<UUID> groups = new ArrayList<>();
        List<UUID> solos  = new ArrayList<>();
        for (UUID uid : candidates) {
            if (partyManager.inParty(uid)) groups.add(uid);
            else solos.add(uid);
        }
        List<UUID> mixed = new ArrayList<>(groups);
        mixed.addAll(solos);
        List<UUID> attempt2 = takeWholeParties(mixed, size);
        return attempt2 != null && hasValidMatching(attempt2) ? attempt2 : null;
    }

    /**
     * Prend `size` joueurs dans l'ordre en gardant les groupes entiers
     * (un groupe qui ne rentre plus est sauté). Null si impossible.
     */
    private List<UUID> takeWholeParties(List<UUID> ordered, int size) {
        List<UUID> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Set<UUID> pool = new HashSet<>(ordered);
        for (UUID uid : ordered) {
            if (seen.contains(uid)) continue;
            List<UUID> unit = new ArrayList<>();
            for (UUID m : partyManager.getPartyMembers(uid)) if (pool.contains(m)) unit.add(m);
            if (unit.isEmpty()) unit.add(uid);
            seen.addAll(unit);
            if (result.size() + unit.size() > size) continue;
            result.addAll(unit);
            if (result.size() == size) return result;
        }
        return null;
    }

    /** Vérifie qu'il existe une assignation valide de chaque joueur à un poste. */
    private boolean hasValidMatching(List<UUID> players) {
        if (players.size() > slots.size()) return false;
        int[] match = computeMatching(players);
        int matched = 0;
        for (int m : match) if (m != -1) matched++;
        return matched == players.size();
    }

    /** Matching bipartite maximal joueurs ↔ postes : match[poste] = index du joueur, -1 si libre. */
    private int[] computeMatching(List<UUID> players) {
        List<List<String>> playerRoles = new ArrayList<>();
        for (UUID uid : players) playerRoles.add(getRolesFor(uid));
        int[] match = new int[slots.size()];
        Arrays.fill(match, -1);
        for (int i = 0; i < playerRoles.size(); i++) {
            augment(i, playerRoles, match, new boolean[slots.size()]);
        }
        return match;
    }

    private boolean augment(int pi, List<List<String>> playerRoles, int[] match, boolean[] visited) {
        List<String> roles = playerRoles.get(pi);
        for (int si = 0; si < slots.size(); si++) {
            if (visited[si] || !roles.contains(slots.get(si))) continue;
            visited[si] = true;
            if (match[si] == -1 || augment(match[si], playerRoles, match, visited)) {
                match[si] = pi;
                return true;
            }
        }
        return false;
    }

    private List<String> getRolesFor(UUID uid) {
        if (partyManager.inParty(uid))
            return partyManager.getMemberRoles(uid);
        return roleManager.getRoles(uid);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRANSMISSION AU SERVEUR DE JEU
    // ══════════════════════════════════════════════════════════════════════

    private void sendPlayerData(ProxiedPlayer player, String assignedRole) {
        List<String> roles = getRolesFor(player.getUniqueId());
        String role = assignedRole != null ? assignedRole : String.join(",", roles);
        List<UUID> party = partyManager.getPartyMembers(player.getUniqueId());
        String origin = plugin.getOriginTracker().getPreviousServer(player.getUniqueId());

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"type\":\"QUEUE_JOIN\",");
        sb.append("\"uuid\":\"").append(player.getUniqueId()).append("\",");
        sb.append("\"name\":\"").append(player.getName()).append("\",");
        sb.append("\"role\":\"").append(role).append("\",");
        if (origin != null) sb.append("\"origin_server\":\"").append(origin).append("\",");
        sb.append("\"party\":\"");
        sb.append(String.join(",", party.stream().map(UUID::toString).toList()));
        sb.append("\"}");

        sendPluginMessage(gameServer, sb.toString());
    }

    /**
     * Envoie le JSON directement au serveur de jeu sur le canal lolmc:bridge.
     * (ProxiedPlayer#sendData écrit vers le CLIENT, pas vers un serveur : le message
     * "Forward" n'atteignait donc jamais le serveur de jeu.)
     * Le message est mis en file si aucun joueur n'est encore connecté à ce serveur.
     */
    private void sendPluginMessage(String targetServer, String json) {
        ServerInfo info = ProxyServer.getInstance().getServerInfo(targetServer);
        if (info == null) {
            plugin.getLogger().warning("Serveur introuvable pour PluginMessage: " + targetServer);
            return;
        }
        info.sendData("lolmc:bridge", json.getBytes(StandardCharsets.UTF_8), true);
    }

    private ProxiedPlayer findCarrier(List<UUID> players) {
        for (UUID uid : players) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            if (p != null) return p;
        }
        return null;
    }

    private String formatRole(String role) {
        return switch (role) {
            case "TOP"     -> "Top";
            case "JUNGLE"  -> "Jungle";
            case "MID"     -> "Mid";
            case "ADC"     -> "ADC";
            case "SUPPORT" -> "Support";
            default        -> role;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // STRUCTURES INTERNES
    // ══════════════════════════════════════════════════════════════════════

    /** Proposition de fill envoyée à un solo. */
    private static class FillProposal {
        final List<UUID>                       gamePlayers;
        final String                           filledRole;
        final Set<UUID>                        excludedSolos;
        final boolean                          ranked;
        net.md_5.bungee.api.scheduler.ScheduledTask timeoutTask;

        FillProposal(List<UUID> gamePlayers, String filledRole, Set<UUID> excludedSolos, boolean ranked) {
            this.gamePlayers   = gamePlayers;
            this.filledRole    = filledRole;
            this.excludedSolos = excludedSolos;
            this.ranked        = ranked;
        }

        void cancel() { if (timeoutTask != null) timeoutTask.cancel(); }
    }

    /** Résultat de findGroupWithOneMissing. */
    private record GroupAndMissingRole(List<UUID> players, String missingRole) {}
}
