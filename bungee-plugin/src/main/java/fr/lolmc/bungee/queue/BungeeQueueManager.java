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
        player.sendMessage(new TextComponent((ranked
                ? "§6Vous êtes dans la file classée."
                : "§aVous êtes dans la file d'attente.")
                + " §7(" + available(ranked).size() + "/" + playersNeeded + ")"));
        checkAndStartGame(ranked);
    }

    public synchronized void leave(ProxiedPlayer player) {
        leave(player.getUniqueId());
    }

    /** Retire un joueur de la file (déconnexion, groupe modifié…). */
    public synchronized void leave(UUID uid) {
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
        if (!queue.contains(player.getUniqueId())) {
            releaseProposal(prop);
            player.sendMessage(new TextComponent("§7Tu n'es plus en file."));
            return;
        }
        for (UUID uid : prop.gamePlayers) {
            if (!queue.contains(uid) || ProxyServer.getInstance().getPlayer(uid) == null) {
                releaseProposal(prop);
                player.sendMessage(new TextComponent("§7La partie n'est plus disponible — tu restes en file."));
                checkAndStartGame(prop.ranked);
                return;
            }
        }
        player.sendMessage(new TextComponent("§a✔ Tu rejoins la partie !"));
        if (launchGame(prop.gamePlayers, player.getUniqueId(), prop.filledRole, prop.ranked))
            checkAndStartGame(prop.ranked); // il reste peut-être assez de monde pour une autre partie
    }

    public synchronized void refuseFill(ProxiedPlayer player) {
        FillProposal prop = pendingProposals.remove(player.getUniqueId());
        if (prop == null) {
            player.sendMessage(new TextComponent("§7Aucune proposition en cours."));
            return;
        }
        prop.cancel();
        reserved.remove(player.getUniqueId());
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
        // ── Étape 0 : une proposition attend son fill, et un nouveau joueur a justement ce rôle ──
        for (FillProposal prop : new ArrayList<>(new LinkedHashSet<>(pendingProposals.values()))) {
            if (prop.ranked != ranked) continue;
            for (UUID uid : available(ranked)) {
                if (partyManager.inParty(uid) || ProxyServer.getInstance().getPlayer(uid) == null) continue;
                Set<String> r = new HashSet<>(roleManager.getRoles(uid));
                if (!r.contains(prop.filledRole) && !r.containsAll(ROLES)) continue;
                // Annuler la proposition en cours et lancer avec ce joueur
                pendingProposals.remove(prop.solo);
                prop.cancel();
                releaseProposal(prop);
                ProxiedPlayer solo = ProxyServer.getInstance().getPlayer(prop.solo);
                if (solo != null) solo.sendMessage(new TextComponent("§7Le poste a été pourvu — tu restes en file."));
                ProxyServer.getInstance().getPlayer(uid).sendMessage(new TextComponent(
                        "§a✔ Tu rejoins une partie en tant que §l" + formatRole(prop.filledRole) + "§a !"));
                if (launchGame(prop.gamePlayers, uid, prop.filledRole, ranked)) checkAndStartGame(ranked);
                return;
            }
        }

        List<UUID> allInQueue = available(ranked);

        // ── Étape 1 : N joueurs complets ──────────────────────────────────
        List<UUID> full = findValidGroup(allInQueue, playersNeeded);
        if (full != null) {
            // Relancer : il reste peut-être assez de joueurs pour une autre partie
            if (launchGame(full, null, null, ranked)) checkAndStartGame(ranked);
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
                if (launchGame(gamePlayers, uid, missingRole, ranked)) checkAndStartGame(ranked);
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
        reserved.add(uid); // le solo aussi : sinon une autre partie pouvait le prendre pendant qu'il réfléchit
        FillProposal proposal = new FillProposal(gamePlayers, missingRole, newExcluded, ranked, uid);
        pendingProposals.put(uid, proposal);

        proposal.timeoutTask = ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            synchronized (BungeeQueueManager.this) {
                FillProposal p = pendingProposals.remove(uid);
                if (p != null) {
                    reserved.remove(uid);
                    solo.sendMessage(new TextComponent("§7Proposition expirée — tu restes en file."));
                    tryProposeFill(gamePlayers, missingRole, newExcluded, ranked);
                }
            }
        }, 20, TimeUnit.SECONDS);
    }

    /** Libère les joueurs réservés par une proposition abandonnée. */
    private void releaseProposal(FillProposal prop) {
        reserved.removeAll(prop.gamePlayers);
        reserved.remove(prop.solo);
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
    /** @return true si la partie est lancée (sinon les joueurs restent en file) */
    private boolean launchGame(List<UUID> gamePlayers, UUID fillUUID, String fillRole, boolean isRanked) {
        List<UUID> allPlayers = new ArrayList<>(gamePlayers);
        if (fillUUID != null) allPlayers.add(fillUUID);

        // Vérifier AVANT de retirer de la file (avant : joueurs perdus si le serveur manquait)
        ProxiedPlayer carrier = findCarrier(allPlayers);
        if (carrier == null) return false;
        ServerInfo gameServerInfo = ProxyServer.getInstance().getServerInfo(gameServer);
        if (gameServerInfo == null) {
            plugin.getLogger().severe("Serveur de jeu '" + gameServer + "' introuvable ! Les joueurs restent en file.");
            return false;
        }

        // Tous les joueurs lancés sortent de la file
        for (UUID uid : allPlayers) {
            queue.remove(uid);
            rankedOf.remove(uid);
            reserved.remove(uid);
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

        // Équipes : groupes jamais séparés, un joueur par rôle dans chaque équipe si possible
        Map<UUID, List<String>> override = fillUUID != null && fillRole != null
                ? Map.of(fillUUID, List.of(fillRole)) : Map.of();
        List<List<UUID>> split = splitTeams(allPlayers, override, true);
        if (split == null) split = splitTeams(allPlayers, override, false); // repli : tailles + groupes
        final List<UUID> blueTeam = split != null ? split.get(0) : allPlayers;
        final List<UUID> redTeam  = split != null ? split.get(1) : List.of();
        // Poste de chaque joueur dans son équipe (transmis au serveur de jeu)
        Map<UUID, String> roles = new LinkedHashMap<>();
        Map<UUID, String> blueRoles = assignTeamRoles(blueTeam, override, true);
        Map<UUID, String> redRoles  = assignTeamRoles(redTeam, override, true);
        if (blueRoles != null) roles.putAll(blueRoles);
        if (redRoles != null)  roles.putAll(redRoles);
        final String rolesCsv = String.join(",", roles.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toList());

        // Groupes partis en partie : statut « prêt » remis à zéro (sinon, après la partie, le
        // premier /lol relançait tout le groupe avec les anciens rôles)
        Set<UUID> leaders = new HashSet<>();
        for (UUID uid : allPlayers) {
            UUID lid = partyManager.getLeader(uid);
            if (lid != null) leaders.add(lid);
        }
        for (UUID lid : leaders) partyManager.resetReady(lid);

        // GAME_READY après 6s (2s connect + 4s chargement)
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            String json = "{"
                    + "\"type\":\"GAME_READY\","
                    + "\"ranked\":" + isRanked + ","
                    + "\"count\":" + allPlayers.size() + ","
                    + "\"players\":\""
                    + String.join(",", allPlayers.stream().map(UUID::toString).toList())
                    + "\",\"blue\":\""
                    + String.join(",", blueTeam.stream().map(UUID::toString).toList())
                    + "\",\"red\":\""
                    + String.join(",", redTeam.stream().map(UUID::toString).toList())
                    + "\",\"roles\":\"" + rolesCsv
                    + "\"}";
            sendPluginMessage(gameServer, json);
            plugin.getLogger().info("[Queue] GAME_READY envoyé pour " + allPlayers.size() + " joueurs (ranked=" + isRanked + ")");
        }, 6, TimeUnit.SECONDS);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ALGORITHME DE MATCHING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cherche `size` joueurs lançables (rôles répartissables, 2 équipes valides, groupes
     * entiers), en privilégiant l'ordre d'arrivée. Exploration complète des combinaisons de
     * groupes/solos, bornée par un budget (avant : seuls 2 ordres étaient essayés, parmi les
     * 2N premiers ; la file pouvait rester bloquée alors qu'une composition existait).
     */
    private List<UUID> findValidGroup(List<UUID> candidates, int size) {
        if (candidates.size() < size) return null;
        List<List<UUID>> units = buildUnits(candidates);
        int[] budget = {20_000};
        return searchGroup(units, 0, new ArrayList<>(), size, suffixSizes(units), budget, this::isLaunchable);
    }

    /**
     * Cherche N-1 joueurs qui, avec un fill sur le poste manquant, formeraient 2 équipes
     * valides ; retourne le groupe et le poste à pourvoir.
     */
    private GroupAndMissingRole findGroupWithOneMissing(List<UUID> candidates) {
        int target = playersNeeded - 1;
        if (candidates.size() < target) return null;
        List<List<UUID>> units = buildUnits(candidates);
        int[] budget = {20_000};
        String[] role = new String[1];
        List<UUID> group = searchGroup(units, 0, new ArrayList<>(), target, suffixSizes(units), budget, g -> {
            if (!hasValidMatching(g)) return false;
            // Essayer chaque poste pour le fill (un joueur fictif qui ne joue que ce poste)
            UUID dummy = new UUID(0L, 0L);
            for (String r : ROLES) {
                List<UUID> withFill = new ArrayList<>(g);
                withFill.add(dummy);
                if (splitTeams(withFill, Map.of(dummy, List.of(r)), true) != null) { role[0] = r; return true; }
            }
            return false;
        });
        return group != null ? new GroupAndMissingRole(group, role[0]) : null;
    }

    /** Unités indivisibles dans l'ordre d'arrivée : un groupe (restreint aux candidats) ou un solo. */
    private List<List<UUID>> buildUnits(List<UUID> ordered) {
        List<List<UUID>> units = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Set<UUID> pool = new HashSet<>(ordered);
        for (UUID uid : ordered) {
            if (seen.contains(uid)) continue;
            List<UUID> unit = new ArrayList<>();
            for (UUID m : partyManager.getPartyMembers(uid)) if (pool.contains(m)) unit.add(m);
            if (unit.isEmpty()) unit.add(uid);
            seen.addAll(unit);
            units.add(unit);
        }
        return units;
    }

    private static int[] suffixSizes(List<List<UUID>> units) {
        int[] suf = new int[units.size() + 1];
        for (int i = units.size() - 1; i >= 0; i--) suf[i] = suf[i + 1] + units.get(i).size();
        return suf;
    }

    /** DFS « inclure d'abord » (ordre d'arrivée prioritaire) avec élagage et budget. */
    private List<UUID> searchGroup(List<List<UUID>> units, int i, List<UUID> cur, int size, int[] suffix,
                                   int[] budget, java.util.function.Predicate<List<UUID>> accept) {
        if (cur.size() == size) {
            budget[0] -= 50; // tester une composition (répartition en équipes) coûte plus cher
            return accept.test(cur) ? new ArrayList<>(cur) : null;
        }
        if (i >= units.size() || budget[0]-- <= 0) return null;
        if (cur.size() + suffix[i] < size) return null; // plus assez de joueurs
        List<UUID> unit = units.get(i);
        if (cur.size() + unit.size() <= size) {
            cur.addAll(unit);
            var r = searchGroup(units, i + 1, cur, size, suffix, budget, accept);
            cur.subList(cur.size() - unit.size(), cur.size()).clear();
            if (r != null) return r;
        }
        return searchGroup(units, i + 1, cur, size, suffix, budget, accept);
    }

    /**
     * Partie complète : les rôles doivent pouvoir se répartir ET les joueurs se couper en
     * 2 équipes (groupes jamais séparés, un joueur par rôle dans chaque équipe).
     * Pour le groupe incomplet (N-1, en attente d'un fill), seul le matching global est vérifié.
     */
    private boolean isLaunchable(List<UUID> players) {
        if (!hasValidMatching(players)) return false;
        if (players.size() < playersNeeded) return true;
        return splitTeams(players, Map.of(), true) != null;
    }

    /**
     * Coupe les joueurs en deux équipes (bleue = ceil(n/2), rouge = le reste) sans jamais
     * séparer un groupe. En mode strict, chaque équipe doit aussi avoir une assignation de
     * rôles valide (un joueur par poste) ; sinon la première répartition de bonne taille
     * est acceptée (repli).
     *
     * @param roleOverride rôle imposé à certains joueurs (ex. le fill)
     * @return [bleus, rouges], ou null si impossible
     */
    private List<List<UUID>> splitTeams(List<UUID> players, Map<UUID, List<String>> roleOverride, boolean strict) {
        // Unités indivisibles : chaque groupe (restreint aux joueurs de la partie), sinon le solo
        List<List<UUID>> units = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Set<UUID> pool = new HashSet<>(players);
        for (UUID uid : players) {
            if (seen.contains(uid)) continue;
            List<UUID> unit = new ArrayList<>();
            for (UUID m : partyManager.getPartyMembers(uid)) if (pool.contains(m)) unit.add(m);
            if (unit.isEmpty()) unit.add(uid);
            seen.addAll(unit);
            units.add(unit);
        }
        int blueSize = (players.size() + 1) / 2;
        List<List<UUID>> fallback = new ArrayList<>(1);
        List<List<UUID>> found = splitRec(units, 0, new ArrayList<>(), new ArrayList<>(),
                blueSize, players.size() - blueSize, roleOverride, strict, fallback);
        if (found != null) return found;
        return strict ? null : (fallback.isEmpty() ? null : fallback);
    }

    private List<List<UUID>> splitRec(List<List<UUID>> units, int i, List<UUID> blue, List<UUID> red,
                                      int blueSize, int redSize, Map<UUID, List<String>> roleOverride,
                                      boolean strict, List<List<UUID>> fallback) {
        if (blue.size() > blueSize || red.size() > redSize) return null;
        if (i == units.size()) {
            if (blue.size() != blueSize || red.size() != redSize) return null;
            if (fallback.isEmpty()) { fallback.add(new ArrayList<>(blue)); fallback.add(new ArrayList<>(red)); }
            if (validTeam(blue, roleOverride) && validTeam(red, roleOverride))
                return List.of(new ArrayList<>(blue), new ArrayList<>(red));
            return null;
        }
        List<UUID> unit = units.get(i);
        blue.addAll(unit);
        var r = splitRec(units, i + 1, blue, red, blueSize, redSize, roleOverride, strict, fallback);
        blue.subList(blue.size() - unit.size(), blue.size()).clear();
        if (r != null) return r;
        if (i == 0) return null; // symétrie : le 1er groupe peut toujours aller en bleu
        red.addAll(unit);
        r = splitRec(units, i + 1, blue, red, blueSize, redSize, roleOverride, strict, fallback);
        red.subList(red.size() - unit.size(), red.size()).clear();
        return r;
    }

    /** Une équipe est valide si chaque joueur peut tenir un poste différent. */
    private boolean validTeam(List<UUID> team, Map<UUID, List<String>> roleOverride) {
        return assignTeamRoles(team, roleOverride, false) != null;
    }

    /**
     * Poste de chaque joueur de l'équipe (un par rôle, selon ses préférences).
     * @param complete true : les joueurs sans poste compatible reçoivent un poste resté libre
     *                 (repli) ; false : null si l'équipe ne peut pas être couverte
     */
    private Map<UUID, String> assignTeamRoles(List<UUID> team, Map<UUID, List<String>> roleOverride, boolean complete) {
        List<List<String>> playerRoles = new ArrayList<>();
        for (UUID uid : team) playerRoles.add(roleOverride.getOrDefault(uid, getRolesFor(uid)));
        int[] match = new int[ROLES.size()];
        Arrays.fill(match, -1);
        int matched = 0;
        for (int pi = 0; pi < playerRoles.size(); pi++)
            if (augmentRoles(pi, playerRoles, match, new boolean[ROLES.size()])) matched++;
        if (matched != team.size() && !complete) return null;
        Map<UUID, String> result = new LinkedHashMap<>();
        for (int ri = 0; ri < ROLES.size(); ri++) if (match[ri] != -1) result.put(team.get(match[ri]), ROLES.get(ri));
        for (UUID uid : team) {
            if (result.containsKey(uid)) continue;
            for (String r : ROLES) if (!result.containsValue(r)) { result.put(uid, r); break; }
        }
        return result;
    }

    private boolean augmentRoles(int pi, List<List<String>> playerRoles, int[] match, boolean[] visited) {
        for (int ri = 0; ri < ROLES.size(); ri++) {
            if (visited[ri] || !playerRoles.get(pi).contains(ROLES.get(ri))) continue;
            visited[ri] = true;
            if (match[ri] == -1 || augmentRoles(match[ri], playerRoles, match, visited)) {
                match[ri] = pi;
                return true;
            }
        }
        return false;
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
        final UUID                             solo;
        net.md_5.bungee.api.scheduler.ScheduledTask timeoutTask;

        FillProposal(List<UUID> gamePlayers, String filledRole, Set<UUID> excludedSolos, boolean ranked, UUID solo) {
            this.gamePlayers   = gamePlayers;
            this.filledRole    = filledRole;
            this.excludedSolos = excludedSolos;
            this.ranked        = ranked;
            this.solo          = solo;
        }

        void cancel() { if (timeoutTask != null) timeoutTask.cancel(); }
    }

    /** Résultat de findGroupWithOneMissing. */
    private record GroupAndMissingRole(List<UUID> players, String missingRole) {}
}
