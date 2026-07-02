package fr.lolmc.bungee.queue;

import fr.lolmc.bungee.LolBungeePlugin;
import fr.lolmc.bungee.party.BungeePartyManager;
import fr.lolmc.bungee.role.BungeeRoleManager;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * File d'attente cross-serveur gérée depuis le proxy.
 *
 * Logique de remplissage :
 *   1. checkAndStartGame() : tente de former une partie de N joueurs
 *   2. Si N joueurs sont disponibles → lancer directement
 *   3. Si N-1 joueurs sont disponibles via un sous-ensemble cohérent
 *      ET qu'un joueur solo peut remplir le poste manquant →
 *      proposer le poste au premier solo éligible (timeout 20s)
 */
public class BungeeQueueManager {

    private final LolBungeePlugin    plugin;
    private final BungeePartyManager partyManager;
    private final BungeeRoleManager  roleManager;

    // File principale (ordre d'arrivée)
    private final Deque<UUID> queue = new ConcurrentLinkedDeque<>();

    // Propositions en attente : solo UUID → proposition
    private final Map<UUID, FillProposal> pendingProposals = new ConcurrentHashMap<>();

    private final int    playersNeeded;
    private final String gameServer;

    public BungeeQueueManager(LolBungeePlugin plugin, BungeePartyManager pm,
                               BungeeRoleManager rlm) {
        this.plugin        = plugin;
        this.partyManager  = pm;
        this.roleManager   = rlm;
        this.playersNeeded = plugin.getConfig().getInt("players-per-game", 10);
        this.gameServer    = plugin.getConfig().getString("game-server", "lolmc-01");
    }

    // ══════════════════════════════════════════════════════════════════════
    // REJOINDRE / QUITTER
    // ══════════════════════════════════════════════════════════════════════

    public void join(ProxiedPlayer player) {
        if (isInQueue(player.getUniqueId())) return;
        List<UUID> group = partyManager.getPartyMembers(player.getUniqueId());
        for (UUID uid : group) {
            if (!queue.contains(uid)) queue.add(uid);
        }
        player.sendMessage(new TextComponent("§aVous êtes dans la file d'attente."));
        checkAndStartGame();
    }

    public void leave(ProxiedPlayer player) {
        UUID uid = player.getUniqueId();
        queue.remove(uid);
        // Annuler une proposition en attente si le joueur quitte
        FillProposal prop = pendingProposals.remove(uid);
        if (prop != null) prop.cancel();
    }

    public boolean isInQueue(UUID uuid) { return queue.contains(uuid); }
    public int getQueueSize()           { return queue.size(); }
    public int getPlayersNeeded()       { return playersNeeded; }

    // ══════════════════════════════════════════════════════════════════════
    // ACCEPTER / REFUSER UNE PROPOSITION DE FILL
    // ══════════════════════════════════════════════════════════════════════

    public void acceptFill(ProxiedPlayer player) {
        FillProposal prop = pendingProposals.remove(player.getUniqueId());
        if (prop == null) {
            player.sendMessage(new TextComponent("§7Aucune proposition en cours."));
            return;
        }
        prop.cancel(); // annuler le timeout
        player.sendMessage(new TextComponent("§a✔ Tu rejoins la partie !"));
        launchGame(prop.gamePlayers, player.getUniqueId(), prop.filledRole);
    }

    public void refuseFill(ProxiedPlayer player) {
        FillProposal prop = pendingProposals.remove(player.getUniqueId());
        if (prop == null) {
            player.sendMessage(new TextComponent("§7Aucune proposition en cours."));
            return;
        }
        prop.cancel();
        player.sendMessage(new TextComponent("§7Proposition refusée. Tu restes en file."));
        // Proposer au prochain solo éligible
        tryProposeFill(prop.gamePlayers, prop.filledRole, prop.excludedSolos);
    }

    // ══════════════════════════════════════════════════════════════════════
    // LOGIQUE PRINCIPALE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Tente de former une partie complète depuis la file.
     *
     * Étape 1 : chercher N joueurs avec une assignation valide.
     * Étape 2 : si N-1 joueurs forment un groupe cohérent avec 1 poste
     *           libre, proposer ce poste aux solos éligibles dans la file.
     */
    private void checkAndStartGame() {
        List<UUID> allInQueue = new ArrayList<>(queue);

        // ── Étape 1 : N joueurs complets ──────────────────────────────────
        List<UUID> full = findValidGroup(allInQueue, playersNeeded);
        if (full != null) {
            for (UUID uid : full) queue.remove(uid);
            launchGame(full, null, null);
            return;
        }

        // ── Étape 2 : N-1 joueurs + 1 solo fill ──────────────────────────
        if (allInQueue.size() < playersNeeded - 1) return;

        // Chercher un sous-ensemble de N-1 avec assignation valide
        GroupAndMissingRole partial = findGroupWithOneMissing(allInQueue);
        if (partial == null) return;

        // Chercher un solo dans la file qui accepte ce rôle
        tryProposeFill(partial.players, partial.missingRole, new HashSet<>());
    }

    /**
     * Cherche le premier solo dans la file qui accepte le rôle manquant,
     * et lui envoie une proposition (timeout 20s).
     *
     * @param gamePlayers  Les N-1 joueurs déjà sélectionnés
     * @param missingRole  Le rôle manquant (ex: "SUPPORT")
     * @param excluded     UUIDs déjà refusés pour cette proposition
     */
    private void tryProposeFill(List<UUID> gamePlayers, String missingRole, Set<UUID> excluded) {
        Set<UUID> gameSet = new HashSet<>(gamePlayers);

        for (UUID uid : new ArrayList<>(queue)) {
            if (gameSet.contains(uid) || excluded.contains(uid)) continue;
            if (partyManager.inParty(uid)) continue;
            ProxiedPlayer solo = ProxyServer.getInstance().getPlayer(uid);
            if (solo == null) continue;

            List<String> roles = roleManager.getRoles(uid);
            boolean wantsRole = roles.contains(missingRole)
                || roles.containsAll(List.of("TOP","JUNGLE","MID","ADC","SUPPORT"));

            if (wantsRole) {
                // Cherche déjà ce rôle → ajout direct sans proposition
                queue.remove(uid);
                solo.sendMessage(new TextComponent(
                    "§a✔ Tu rejoins une partie en tant que §l" + formatRole(missingRole) + "§a !"));
                launchGame(gamePlayers, uid, missingRole);
                return;
            }
        }

        // Aucun solo ne cherche ce rôle → proposer au premier solo disponible
        for (UUID uid : new ArrayList<>(queue)) {
            if (gameSet.contains(uid) || excluded.contains(uid)) continue;
            if (partyManager.inParty(uid)) continue;
            ProxiedPlayer solo = ProxyServer.getInstance().getPlayer(uid);
            if (solo == null) continue;

            solo.sendMessage(new TextComponent("§6§l⚔ Proposition de partie !"));
            solo.sendMessage(new TextComponent(
                "§7Un groupe cherche un §e" + formatRole(missingRole) + "§7."));
            solo.sendMessage(new TextComponent(
                "§aTape §l/lol accepte §r§7ou §c§l/lol refuse §r§7(§720s§7)"));

            Set<UUID> newExcluded = new HashSet<>(excluded);
            newExcluded.add(uid);

            FillProposal proposal = new FillProposal(gamePlayers, missingRole, newExcluded);
            pendingProposals.put(uid, proposal);

            var task = ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
                FillProposal p = pendingProposals.remove(uid);
                if (p != null) {
                    solo.sendMessage(new TextComponent("§7Proposition expirée — tu restes en file."));
                    tryProposeFill(gamePlayers, missingRole, newExcluded);
                }
            }, 20, TimeUnit.SECONDS);

            proposal.timeoutTask = task;
            return;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LANCEMENT DE LA PARTIE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Lance la partie avec les joueurs sélectionnés.
     *
     * @param gamePlayers  Les N-1 joueurs du groupe
     * @param fillUUID     Le solo fill (null si partie complète sans fill)
     * @param fillRole     Le rôle assigné au fill
     */
    private void launchGame(List<UUID> gamePlayers, UUID fillUUID, String fillRole) {
        List<UUID> allPlayers = new ArrayList<>(gamePlayers);
        if (fillUUID != null) {
            allPlayers.add(fillUUID);
            queue.remove(fillUUID);
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
            sendPlayerData(carrier, p, assignedRole);
        }

        // Connecter après 2s puis envoyer GAME_READY après 5s (temps de connexion)
        boolean isRanked = plugin.getConfig().getBoolean("ranked-mode", false);
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            for (UUID uid : allPlayers) {
                ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
                if (p != null) p.connect(gameServerInfo);
            }
        }, 2, TimeUnit.SECONDS);

        // GAME_READY après 6s (2s connect + 4s chargement)
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"type\":\"GAME_READY\",");
            sb.append("\"ranked\":").append(isRanked).append(",");
            sb.append("\"count\":").append(allPlayers.size()).append(",");
            sb.append("\"players\":\"");
            sb.append(String.join(",", allPlayers.stream().map(UUID::toString).toList()));
            sb.append("\"}");
            sendPluginMessage(carrier, gameServer, sb.toString());
            plugin.getLogger().info("[Queue] GAME_READY envoyé pour " + allPlayers.size() + " joueurs (ranked=" + isRanked + ")");
            // Remettre ranked-mode à false après usage (par défaut non-ranked)
            if (isRanked) plugin.setRankedMode(false);
        }, 6, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ALGORITHME DE MATCHING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cherche un groupe de `size` joueurs dans la file avec une assignation
     * de rôles valide (matching bipartite).
     * Retourne la liste des UUIDs sélectionnés, ou null si impossible.
     */
    private List<UUID> findValidGroup(List<UUID> candidates, int size) {
        if (candidates.size() < size) return null;
        // Essayer les N premiers (ordre d'arrivée)
        List<UUID> subset = candidates.subList(0, Math.min(size * 2, candidates.size()));
        return findMatchingSubset(subset, size);
    }

    /**
     * Cherche un groupe de N-1 joueurs avec assignation valide,
     * et retourne le groupe + le rôle manquant.
     */
    private GroupAndMissingRole findGroupWithOneMissing(List<UUID> candidates) {
        int target = playersNeeded - 1;
        if (candidates.size() < target) return null;

        List<UUID> subset = candidates.subList(0, Math.min(target * 2, candidates.size()));
        List<UUID> group = findMatchingSubset(subset, target);
        if (group == null) return null;

        // Trouver le rôle manquant parmi les 5 postes
        List<String> allRoles = List.of("TOP","JUNGLE","MID","ADC","SUPPORT");
        List<List<String>> playerRoles = new ArrayList<>();
        for (UUID uid : group) playerRoles.add(getRolesFor(uid));

        // L'assignation actuelle — trouver le poste non couvert
        int[] match = new int[allRoles.size()];
        Arrays.fill(match, -1);
        for (int i = 0; i < playerRoles.size(); i++) {
            boolean[] visited = new boolean[allRoles.size()];
            augment(i, playerRoles, allRoles, match, visited);
        }
        // Le poste non assigné est le rôle manquant
        for (int ri = 0; ri < allRoles.size(); ri++) {
            if (match[ri] == -1) {
                return new GroupAndMissingRole(group, allRoles.get(ri));
            }
        }
        return null;
    }

    /** Cherche un sous-ensemble de `size` dans candidates avec matching valide. */
    private List<UUID> findMatchingSubset(List<UUID> candidates, int size) {
        if (candidates.size() < size) return null;
        // Essai simple : les `size` premiers
        List<UUID> attempt = new ArrayList<>(candidates.subList(0, size));
        if (hasValidMatching(attempt)) return attempt;

        // Si les solos sont mélangés avec des groupes, tenter d'abord
        // les groupes complets puis compléter avec des solos
        // (heuristique — pas exhaustif pour garder O(n) raisonnable)
        List<UUID> groups = new ArrayList<>();
        List<UUID> solos  = new ArrayList<>();
        for (UUID uid : candidates) {
            if (partyManager.inParty(uid)) groups.add(uid);
            else solos.add(uid);
        }
        List<UUID> mixed = new ArrayList<>(groups);
        mixed.addAll(solos);
        if (mixed.size() < size) return null;
        List<UUID> attempt2 = new ArrayList<>(mixed.subList(0, size));
        return hasValidMatching(attempt2) ? attempt2 : null;
    }

    /** Vérifie qu'il existe une assignation valide (matching bipartite). */
    private boolean hasValidMatching(List<UUID> players) {
        List<String> allRoles = List.of("TOP","JUNGLE","MID","ADC","SUPPORT");
        List<List<String>> playerRoles = new ArrayList<>();
        for (UUID uid : players) playerRoles.add(getRolesFor(uid));

        int[] match = new int[allRoles.size()];
        Arrays.fill(match, -1);
        int matched = 0;
        for (int i = 0; i < playerRoles.size(); i++) {
            boolean[] visited = new boolean[allRoles.size()];
            if (augment(i, playerRoles, allRoles, match, visited)) matched++;
        }
        return matched == players.size();
    }

    private boolean augment(int pi, List<List<String>> playerRoles,
                            List<String> allRoles, int[] match, boolean[] visited) {
        for (String role : playerRoles.get(pi)) {
            int ri = allRoles.indexOf(role);
            if (ri < 0 || visited[ri]) continue;
            visited[ri] = true;
            if (match[ri] == -1 || augment(match[ri], playerRoles, allRoles, match, visited)) {
                match[ri] = pi;
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

    private void sendPlayerData(ProxiedPlayer carrier, ProxiedPlayer player, String assignedRole) {
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

        sendPluginMessage(carrier, gameServer, sb.toString());
    }

    private void sendPluginMessage(ProxiedPlayer carrier, String targetServer, String json) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("Forward");
            out.writeUTF(targetServer);
            out.writeUTF("lolmc:bridge");
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            out.writeShort(data.length);
            out.write(data);
            carrier.sendData("BungeeCord", baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Erreur PluginMessage: " + e.getMessage());
        }
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
        net.md_5.bungee.api.scheduler.ScheduledTask timeoutTask;

        FillProposal(List<UUID> gamePlayers, String filledRole, Set<UUID> excludedSolos) {
            this.gamePlayers   = gamePlayers;
            this.filledRole    = filledRole;
            this.excludedSolos = excludedSolos;
        }

        void cancel() { if (timeoutTask != null) timeoutTask.cancel(); }
    }

    /** Résultat de findGroupWithOneMissing. */
    private record GroupAndMissingRole(List<UUID> players, String missingRole) {}
}
