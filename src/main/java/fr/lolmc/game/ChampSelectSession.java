package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RunePage;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import fr.lolmc.game.ChampSelectManager.Phase;

import java.util.*;

/**
 * Gère la phase de sélection avant une partie (Champion Select de LoL) :
 *  - Un lobby attend les joueurs
 *  - Une phase de sélection avec timer où chacun choisit champion, runes, sorts
 *  - Au bout du timer (ou quand tout le monde a verrouillé), la partie démarre
 *
 * Les choix sont stockés ici puis appliqués au lancement.
 */
public class ChampSelectSession {


    private Phase phase = Phase.IDLE;

    // Joueurs dans la sélection
    private final Set<UUID> participants = new HashSet<>();
    // Choix de chaque joueur
    private final Map<UUID, String> chosenChampion = new HashMap<>();
    private final Map<UUID, RunePage> chosenRunes = new HashMap<>();
    private final Map<UUID, String> chosenSkin = new HashMap<>();
    private final Map<UUID, String[]> chosenSpells = new HashMap<>(); // [spell1, spell2]
    private final Set<UUID> locked = new HashSet<>(); // joueurs ayant verrouillé

    private int timeLeft = 0;
    private BossBar selectBar;
    private BukkitRunnable timerTask;

    private static final int SELECT_DURATION = 60; // 60s de sélection
    private static final int BAN_DURATION    = 30; // 30s de ban (classé)

    private final java.util.Set<String> bannedChampions = new java.util.LinkedHashSet<>();
    private BukkitRunnable banTask;

    // ── Draft classé : bans puis picks à tour de rôle ──
    private final List<UUID> draftOrder = new ArrayList<>(); // ordre des bans, puis des picks
    private int turnIndex = 0;
    private int turnTimeLeft = 0;
    private boolean draftPicking = false;   // classé : phase de pick tour par tour en cours
    private static final int BAN_TURN = 15;       // s par ban
    private static final int PICK_TURN = 25;      // s par pick
    private static final int FINAL_DURATION = 15; // s pour runes/sorts après le dernier pick
    private static final int BANS_PER_TEAM = 3;

    // ══════════════════════════════════════════════════════════════
    // DÉMARRAGE DE LA SÉLECTION
    // ══════════════════════════════════════════════════════════════

    /** Alias utilisé par MatchmakingManager et PreGameGUI — lance la sélection.
     *  Reste sur le GameManager global : sans joueur de référence, il n'y a pas de
     *  moyen fiable de choisir une instance précise parmi plusieurs en cours. */
    public void startBanPhase() {
        var gm = LolPlugin.getInstance().getGameManager();
        if (gm != null) startSelection(gm.getParticipants());
    }


    /** Surcharge avec mode ranked (pick+ban) ou normal (pick seul). */
    public void startSelection(Collection<UUID> players, boolean ranked) {
        this.ranked = ranked;
        prepareTeamsAndPlace(players);
        this.bannedChampions.clear();
        if (ranked) {
            startBanPhaseInternal(players);
        } else {
            startPickPhase(players);
        }
    }

    private boolean ranked = false;


    /**
     * Mode partie unique : place chaque joueur dans une équipe (alternées, BLEU d'abord) et
     * le téléporte à son point de départ dans le monde de jeu. Sans ça, un joueur arrivé du
     * proxy reste dans le monde par défaut : pas d'équipe, donc ni HUD, ni chrono, ni sorts.
     */
    private void prepareTeamsAndPlace(Collection<UUID> players) {
        var plugin = LolPlugin.getInstance();
        var im = plugin.getInstanceManager();
        var tm = plugin.getTeamManager();
        var map = plugin.getMapManager();
        int i = 0;
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (im != null && im.getInstanceOf(p) != null) continue; // instance : déjà placé
            fr.lolmc.team.TeamManager.Team team = tm.hasTeam(p) ? tm.getTeam(p)
                    : (i % 2 == 0 ? fr.lolmc.team.TeamManager.Team.BLUE : fr.lolmc.team.TeamManager.Team.RED);
            if (!tm.hasTeam(p)) tm.setTeam(p, team);
            i++;
            var spawn = map.getSpawn(team, 0);
            if (spawn == null) spawn = map.getSpawn(team, 1);
            if (spawn != null) p.teleport(spawn);
            var gw = fr.lolmc.util.WorldContext.getGameWorld();
            if (gw == null || !gw.equals(p.getWorld())) {
                String msg = "⚠ Monde de jeu ≠ monde du joueur : config world.name=\""
                        + fr.lolmc.util.WorldContext.getGameWorldName() + "\", tu es dans \""
                        + p.getWorld().getName() + "\". Chrono/HUD/tab inactifs.";
                p.sendMessage(Component.text(msg, NamedTextColor.RED));
                LolPlugin.getInstance().getLogger().warning(msg);
            }
            plugin.getMinimapManager().giveMinimap(p);
        }
    }

    public void startSelection(Collection<UUID> players) {
        startSelection(players, false);
    }

    // ── Phase de BAN (classé uniquement) ──────────────────────────────

    private void startBanPhaseInternal(Collection<UUID> players) {
        if (phase != Phase.IDLE && phase != Phase.LOBBY) return;
        phase = Phase.SELECTING;
        participants.clear();
        participants.addAll(players);

        // Ordre : Bleu 1, Rouge 1, Bleu 2, Rouge 2, Bleu 3, Rouge 3
        draftOrder.clear();
        List<UUID> blue = teamList(fr.lolmc.team.TeamManager.Team.BLUE);
        List<UUID> red  = teamList(fr.lolmc.team.TeamManager.Team.RED);
        for (int i = 0; i < BANS_PER_TEAM; i++) {
            if (i < blue.size()) draftOrder.add(blue.get(i));
            if (i < red.size())  draftOrder.add(red.get(i));
        }
        turnIndex = 0;
        if (draftOrder.isEmpty()) { startPickPhase(participants); return; }

        selectBar = BossBar.bossBar(Component.text("⛔ Phase de BAN", NamedTextColor.RED),
                1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.showBossBar(selectBar);
            p.sendMessage(Component.text("═══ PHASE DE BAN — CLASSÉ ═══", NamedTextColor.RED));
            p.sendMessage(Component.text("Les bans se font à tour de rôle (" + BAN_TURN + "s chacun).",
                    NamedTextColor.YELLOW));
        }
        announceBanTurn();

        banTask = new BukkitRunnable() {
            @Override public void run() {
                turnTimeLeft--;
                UUID cur = currentTurn();
                if (selectBar != null) {
                    selectBar.name(Component.text("⛔ BAN — tour de " + nameOf(cur) + " — " + turnTimeLeft + "s | Bannis: "
                            + bannedChampions.size(), NamedTextColor.RED));
                    selectBar.progress(Math.max(0f, (float) turnTimeLeft / BAN_TURN));
                }
                if (turnTimeLeft <= 0) {
                    broadcast(Component.text("⏱ " + nameOf(cur) + " n'a pas banni (temps écoulé).", NamedTextColor.GRAY));
                    nextBanTurn();
                }
            }
        };
        banTask.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }

    private void announceBanTurn() {
        turnTimeLeft = BAN_TURN;
        UUID cur = currentTurn();
        broadcast(Component.text("⛔ Au tour de " + nameOf(cur) + " (" + teamName(cur) + ") de bannir.", NamedTextColor.RED));
        Player p = cur != null ? Bukkit.getPlayer(cur) : null;
        if (p != null) LolPlugin.getInstance().getChampSelectGUI().openBanMenu(p);
    }

    private void nextBanTurn() {
        Player prev = currentTurn() != null ? Bukkit.getPlayer(currentTurn()) : null;
        if (prev != null) prev.closeInventory();
        turnIndex++;
        if (turnIndex >= draftOrder.size()) {
            if (banTask != null) { banTask.cancel(); banTask = null; }
            if (selectBar != null) for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(selectBar);
            }
            startPickPhase(participants);
            return;
        }
        announceBanTurn();
    }

    /**
     * Appelé depuis ChampSelectGUI quand un joueur bannit un champion.
     * @return true si le ban est accepté (c'était bien son tour)
     */
    public boolean onBanClick(Player player, String championId) {
        if (phase != Phase.SELECTING || draftPicking || banTask == null) return false;
        if (!player.getUniqueId().equals(currentTurn())) {
            player.sendActionBar(Component.text("⏳ Ce n'est pas ton tour de bannir.", NamedTextColor.RED));
            return false;
        }
        if (bannedChampions.contains(championId)) {
            player.sendActionBar(Component.text("❌ " + championId + " est déjà banni.", NamedTextColor.RED));
            return false;
        }
        bannedChampions.add(championId);
        broadcast(Component.text("⛔ " + player.getName() + " a banni " + championId + " !", NamedTextColor.RED));
        nextBanTurn();
        return true;
    }

    public java.util.Set<String> getBannedChampions() { return bannedChampions; }

    // ── Phase de PICK ──────────────────────────────────────────────────

    private void startPickPhase(Collection<UUID> players) {
        if (phase != Phase.IDLE && phase != Phase.LOBBY && phase != Phase.SELECTING) return;
        phase = Phase.SELECTING;
        participants.clear();
        participants.addAll(players);
        chosenChampion.clear();
        chosenRunes.clear();
        chosenSpells.clear();
        chosenSkin.clear();
        locked.clear();
        timeLeft = SELECT_DURATION;

        if (ranked) {
            // Ordre de pick « serpent » : B, R R, B B, R R, B B, R
            draftOrder.clear();
            List<UUID> blue = teamList(fr.lolmc.team.TeamManager.Team.BLUE);
            List<UUID> red  = teamList(fr.lolmc.team.TeamManager.Team.RED);
            int bi = 0, ri = 0;
            if (bi < blue.size()) draftOrder.add(blue.get(bi++));
            boolean redTurn = true;
            while (bi < blue.size() || ri < red.size()) {
                for (int k = 0; k < 2; k++) {
                    if (redTurn && ri < red.size()) draftOrder.add(red.get(ri++));
                    else if (!redTurn && bi < blue.size()) draftOrder.add(blue.get(bi++));
                }
                redTurn = !redTurn;
            }
            // Joueurs sans équipe (ne devrait pas arriver) : à la fin
            for (UUID id : participants) if (!draftOrder.contains(id)) draftOrder.add(id);
            turnIndex = 0;
            draftPicking = true;
        }

        selectBar = BossBar.bossBar(Component.text("Sélection — choisis ton champion!"),
                1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);

        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.showBossBar(selectBar);
            p.sendMessage(Component.text("═══ SÉLECTION DE CHAMPION ═══", NamedTextColor.LIGHT_PURPLE));
            p.sendMessage(Component.text("Menu de sélection ouvert — /pick pour le rouvrir.",
                    NamedTextColor.AQUA));
            p.sendMessage(Component.text(ranked
                    ? "Classé : les picks se font à tour de rôle (" + PICK_TURN + "s chacun). Un champion pris est indisponible pour tous."
                    : "Clique un champion puis « Verrouiller ». Tu as " + SELECT_DURATION + "s. Un champion ne peut être pris qu'une fois par équipe.",
                    NamedTextColor.GRAY));
            // Ouvrir le menu après un court délai (le joueur vient d'arriver / de se téléporter),
            // puis une 2e fois si une téléportation l'a fermé entre-temps.
            final UUID pid = id;
            for (long delay : new long[]{20L, 60L}) {
                Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
                    Player q = Bukkit.getPlayer(pid);
                    if (q == null || phase != Phase.SELECTING || locked.contains(pid)) return;
                    if (q.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                        LolPlugin.getInstance().getChampSelectGUI().openChampionMenu(q);
                    }
                }, delay);
            }
        }

        if (ranked) { announcePickTurn(); startDraftTimer(); }
        else startTimer();
    }

    /** Minuteur du draft classé : un tour de pick par joueur, puis la finalisation. */
    private void startDraftTimer() {
        timerTask = new BukkitRunnable() {
            @Override public void run() {
                if (draftPicking) {
                    turnTimeLeft--;
                    UUID cur = currentTurn();
                    if (selectBar != null) {
                        selectBar.name(Component.text("⏱ PICK — tour de " + nameOf(cur) + " — " + turnTimeLeft + "s ("
                                + (turnIndex + 1) + "/" + draftOrder.size() + ")", NamedTextColor.LIGHT_PURPLE));
                        selectBar.progress(Math.max(0f, (float) turnTimeLeft / PICK_TURN));
                    }
                    if (turnTimeLeft <= 0) {
                        Player p = cur != null ? Bukkit.getPlayer(cur) : null;
                        if (p != null) autoLock(p);
                        nextPickTurn();
                    }
                } else {
                    timeLeft--;
                    if (selectBar != null) {
                        selectBar.name(Component.text("⏱ Finalisation (runes / sorts) — " + timeLeft + "s",
                                NamedTextColor.LIGHT_PURPLE));
                        selectBar.progress(Math.max(0f, (float) timeLeft / FINAL_DURATION));
                    }
                    if (timeLeft <= 0) { cancel(); finishSelection(); }
                }
            }
        };
        timerTask.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }

    private void announcePickTurn() {
        turnTimeLeft = PICK_TURN;
        UUID cur = currentTurn();
        broadcast(Component.text("🎯 Au tour de " + nameOf(cur) + " (" + teamName(cur) + ") de choisir.", NamedTextColor.LIGHT_PURPLE));
        Player p = cur != null ? Bukkit.getPlayer(cur) : null;
        if (p != null) LolPlugin.getInstance().getChampSelectGUI().openChampionMenu(p);
    }

    private void nextPickTurn() {
        turnIndex++;
        refreshMenus();
        if (turnIndex >= draftOrder.size()) {
            draftPicking = false;
            timeLeft = FINAL_DURATION;
            broadcast(Component.text("✔ Tous les champions sont choisis — " + FINAL_DURATION
                    + "s pour finaliser tes runes et sorts.", NamedTextColor.GREEN));
            return;
        }
        announcePickTurn();
    }

    /** Fin de tour sans verrouillage : on prend le champion survolé s'il est libre, sinon le premier disponible. */
    private void autoLock(Player p) {
        UUID id = p.getUniqueId();
        if (locked.contains(id)) return;
        String champ = chosenChampion.get(id);
        if (champ == null || !isAvailable(id, champ)) champ = firstAvailable(id, Set.of());
        if (champ == null) return;
        chosenChampion.put(id, champ);
        locked.add(id);
        p.closeInventory();
        broadcast(Component.text("⏱ " + p.getName() + " → " + champ + " (attribué automatiquement).", NamedTextColor.GRAY));
    }

    private void startTimer() {
        timerTask = new BukkitRunnable() {
            @Override public void run() {
                timeLeft--;
                if (selectBar != null) {
                    selectBar.name(Component.text("⏱ Sélection — " + timeLeft + "s ("
                                    + locked.size() + "/" + participants.size() + " prêts)",
                            NamedTextColor.LIGHT_PURPLE));
                    selectBar.progress(Math.max(0f, (float) timeLeft / SELECT_DURATION));
                }
                // Fin si temps écoulé ou tout le monde verrouillé
                if (timeLeft <= 0 || (locked.size() >= participants.size() && !participants.isEmpty())) {
                    cancel();
                    finishSelection();
                }
            }
        };
        timerTask.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }

    // ══════════════════════════════════════════════════════════════
    // CHOIX DES JOUEURS
    // ══════════════════════════════════════════════════════════════

    public boolean isSelecting() { return phase == Phase.SELECTING; }
    public boolean isParticipant(UUID uuid) { return participants.contains(uuid); }

    /** @return true si le champion a bien été sélectionné */
    public boolean chooseChampion(Player player, String championId) {
        if (!isParticipant(player.getUniqueId())) return false;
        championId = championId.toLowerCase(java.util.Locale.ROOT);
        if (locked.contains(player.getUniqueId())) {
            player.sendActionBar(Component.text("Tu as déjà verrouillé!", NamedTextColor.RED));
            return false;
        }
        if (ranked && banTask != null) {
            player.sendActionBar(Component.text("⏳ Phase de ban en cours.", NamedTextColor.RED));
            return false;
        }
        if (ranked && draftPicking && !player.getUniqueId().equals(currentTurn())) {
            player.sendActionBar(Component.text("⏳ Ce n'est pas ton tour (" + nameOf(currentTurn()) + " choisit).", NamedTextColor.RED));
            return false;
        }
        if (!player.hasPermission("lolmc.champion." + championId)) {
            player.sendActionBar(Component.text("❌ Tu n'as pas accès à ce champion.", NamedTextColor.RED));
            return false;
        }
        if (bannedChampions.contains(championId)) {
            player.sendActionBar(Component.text("⛔ Ce champion est banni.", NamedTextColor.RED));
            return false;
        }
        if (isTaken(player.getUniqueId(), championId)) {
            player.sendActionBar(Component.text(ranked
                    ? "❌ Ce champion est déjà pris."
                    : "❌ Un coéquipier a déjà choisi ce champion.", NamedTextColor.RED));
            return false;
        }
        chosenChampion.put(player.getUniqueId(), championId);
        player.sendActionBar(Component.text("✔ Champion: " + championId, NamedTextColor.GREEN));
        refreshMenus();
        return true;
    }

    // ── Disponibilité des champions ──

    /** Champion déjà pris : par un coéquipier (normal) ou par n'importe qui (classé). */
    public boolean isTaken(UUID requester, String championId) {
        for (var e : chosenChampion.entrySet()) {
            if (e.getKey().equals(requester) || !e.getValue().equalsIgnoreCase(championId)) continue;
            if (ranked || sameTeam(requester, e.getKey())) return true;
        }
        return false;
    }

    public boolean isBanned(String championId) { return bannedChampions.contains(championId.toLowerCase(java.util.Locale.ROOT)); }

    private boolean isAvailable(UUID id, String championId) {
        Player p = Bukkit.getPlayer(id);
        if (p != null && !p.hasPermission("lolmc.champion." + championId)) return false;
        return !isBanned(championId) && !isTaken(id, championId);
    }

    /** Premier champion disponible pour ce joueur (hors {@code exclude}), ou null. */
    private String firstAvailable(UUID id, Set<String> exclude) {
        for (var c : LolPlugin.getInstance().getChampionManager().getAllChampions()) {
            String cid = c.getId();
            if (!exclude.contains(cid) && isAvailable(id, cid)) return cid;
        }
        return null;
    }

    private boolean sameTeam(UUID a, UUID b) {
        var ta = teamOf(a);
        return ta != null && ta == teamOf(b);
    }

    private fr.lolmc.team.TeamManager.Team teamOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? LolPlugin.getInstance().getTeamManager().getTeam(p) : null;
    }

    private List<UUID> teamList(fr.lolmc.team.TeamManager.Team team) {
        List<UUID> list = new ArrayList<>();
        for (UUID id : participants) if (teamOf(id) == team) list.add(id);
        return list;
    }

    private UUID currentTurn() {
        return turnIndex >= 0 && turnIndex < draftOrder.size() ? draftOrder.get(turnIndex) : null;
    }

    private String nameOf(UUID id) {
        Player p = id != null ? Bukkit.getPlayer(id) : null;
        return p != null ? p.getName() : "?";
    }

    private String teamName(UUID id) {
        var t = id != null ? teamOf(id) : null;
        return t == fr.lolmc.team.TeamManager.Team.BLUE ? "Bleu" : t == fr.lolmc.team.TeamManager.Team.RED ? "Rouge" : "?";
    }

    private void broadcast(Component msg) {
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(msg);
        }
    }

    /** Rafraîchit l'écran de sélection des joueurs qui l'ont ouvert (champions pris/bannis). */
    private void refreshMenus() {
        var gui = LolPlugin.getInstance().getChampSelectGUI();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && gui.isChampionMenuOpen(p)) gui.openChampionMenu(p);
        }
    }

    public void chooseRunes(Player player, RunePage page) {
        if (!isParticipant(player.getUniqueId())) return;
        chosenRunes.put(player.getUniqueId(), page);
        player.sendActionBar(Component.text("✔ Runes enregistrées", NamedTextColor.GREEN));
    }

    /** Sorts d'invocateur choisis (FLASH + IGNITE par défaut). */
    public String[] getChosenSpells(UUID id) {
        return chosenSpells.getOrDefault(id, new String[]{"FLASH", "IGNITE"});
    }

    public void chooseSpells(Player player, String spell1, String spell2) {
        if (!isParticipant(player.getUniqueId())) return;
        spell1 = spell1.toUpperCase(java.util.Locale.ROOT);
        spell2 = spell2.toUpperCase(java.util.Locale.ROOT);
        if (spell1.equals(spell2)) {
            player.sendActionBar(Component.text("❌ Choisis deux sorts différents", NamedTextColor.RED));
            return;
        }
        chosenSpells.put(player.getUniqueId(), new String[]{spell1, spell2});
        player.sendActionBar(Component.text("✔ Sorts: " + spell1 + ", " + spell2, NamedTextColor.GREEN));
    }

    public void lock(Player player) {
        if (!isParticipant(player.getUniqueId())) return;
        if (!chosenChampion.containsKey(player.getUniqueId())) {
            player.sendActionBar(Component.text("Choisis d'abord un champion!", NamedTextColor.RED));
            return;
        }
        if (locked.contains(player.getUniqueId())) return;
        if (ranked && draftPicking && !player.getUniqueId().equals(currentTurn())) {
            player.sendActionBar(Component.text("⏳ Ce n'est pas ton tour.", NamedTextColor.RED));
            return;
        }
        locked.add(player.getUniqueId());
        player.sendActionBar(Component.text("🔒 Verrouillé! En attente des autres...", NamedTextColor.GOLD));
        broadcast(Component.text("🔒 " + player.getName() + " a verrouillé " + chosenChampion.get(player.getUniqueId()) + ".",
                NamedTextColor.GOLD));
        if (ranked && draftPicking) nextPickTurn();
    }

    // ══════════════════════════════════════════════════════════════
    // FIN DE SÉLECTION → LANCEMENT
    // ══════════════════════════════════════════════════════════════

    private void finishSelection() {
        phase = Phase.STARTING;

        // Masquer la bossbar
        if (selectBar != null) {
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(selectBar);
            }
        }

        var cm = LolPlugin.getInstance().getChampionManager();
        var rm = LolPlugin.getInstance().getRuneManager();
        var ssm = LolPlugin.getInstance().getSummonerSpellManager();

        // Champions : les verrouillés d'abord (ils gardent leur choix), puis les autres reçoivent
        // leur survol s'il est libre, sinon le premier champion disponible (avant : Garen pour tous).
        Map<UUID, String> finalChamp = new HashMap<>();
        List<UUID> order = new ArrayList<>();
        for (UUID id : participants) if (locked.contains(id)) order.add(id);
        for (UUID id : participants) if (!locked.contains(id)) order.add(id);
        Map<UUID, String> hovered = new HashMap<>(chosenChampion);
        chosenChampion.keySet().retainAll(locked); // les survols non verrouillés ne réservent rien
        for (UUID id : order) {
            String c = chosenChampion.get(id); // verrouillé
            if (c == null) {
                // Survol (s'il est encore libre), sinon premier champion disponible
                String h = hovered.get(id);
                c = (h != null && isAvailable(id, h)) ? h : firstAvailable(id, Set.of());
            }
            if (c == null) c = "garen";
            chosenChampion.put(id, c); // réserve pour les suivants
            finalChamp.put(id, c);
        }

        // Appliquer les choix de chaque joueur
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;

            String champ = finalChamp.getOrDefault(id, "garen");
            cm.assignChampion(p, champ);

            // Runes (page par défaut si rien)
            RunePage page = chosenRunes.getOrDefault(id, RunePage.defaultPage());
            rm.setPage(id, page);
            rm.applyRuneStats(p);

            // Sorts d'invocateur
            String[] spells = chosenSpells.getOrDefault(id, new String[]{"FLASH", "IGNITE"});
            if (ssm != null) ssm.setSpells(p, spells[0], spells[1]);

            // Skin choisi
            var champObj = cm.getChampion(p);
            if (champObj != null) LolPlugin.getInstance().getHotbarManager().renderPage(p, champObj);
            String skinId = chosenSkin.getOrDefault(id, "base");
            LolPlugin.getInstance().getSkinManager().applySkin(p, champ, skinId);

            p.sendMessage(Component.text("⚔ Partie lancée avec " + champ
                    + " | Skin: " + skinId + " | Sorts: " + spells[0] + " + " + spells[1] + "!", NamedTextColor.GOLD));
        }

        // Lancer la partie physique : si les joueurs appartiennent à une GameInstance isolée
        // (mode multi-instances), démarrer CETTE instance plutôt que les managers globaux —
        // sinon (mode partie unique, le défaut) garder le chemin global existant.
        var instMgr = LolPlugin.getInstance().getInstanceManager();
        var instance = instMgr != null && !participants.isEmpty()
                ? instMgr.getInstanceOf(participants.iterator().next()) : null;
        if (instance != null) {
            plugin_startMatch(participants);
            instance.start();
        } else {
            var plugin = LolPlugin.getInstance();
            plugin.getMapManager().resetAllStructures();
            plugin.getPlantManager().spawnAll();
            // Remise à zéro limitée à ces joueurs : ne pas effacer les stats d'autres parties
            plugin.getMatchScoreboard().startMatch(ranked, new HashSet<>(participants));
            plugin.getGameManager().startGame();
            plugin.getMinionManager().startWaves();
            LolPlugin.getInstance().getJungleManager().startJungle();
        }

        phase = Phase.IDLE;
        LolPlugin.getInstance().getChampSelectManager().sessionEnded(this, participants);
    }

    public void cancel() {
        phase = Phase.IDLE;
        LolPlugin.getInstance().getChampSelectManager().sessionEnded(this, participants);
        if (timerTask != null) timerTask.cancel();
        if (banTask != null) { banTask.cancel(); banTask = null; }
        if (selectBar != null) {
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(selectBar);
            }
        }
        participants.clear();
    }

    public Phase getPhase() { return phase; }
    public void onSkinChosen(Player p, String champId, String skinId) { chosenSkin.put(p.getUniqueId(), skinId); }

    /** Remet à zéro les stats de ces joueurs uniquement (les autres parties ne sont pas touchées). */
    private void plugin_startMatch(Collection<UUID> group) {
        LolPlugin.getInstance().getMatchScoreboard().startMatch(ranked, new HashSet<>(group));
    }
}
