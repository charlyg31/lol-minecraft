package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RunePage;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère la phase de sélection avant une partie (Champion Select de LoL) :
 *  - Un lobby attend les joueurs
 *  - Une phase de sélection avec timer où chacun choisit champion, runes, sorts
 *  - Au bout du timer (ou quand tout le monde a verrouillé), la partie démarre
 *
 * Les choix sont stockés ici puis appliqués au lancement.
 */
public class ChampSelectManager {

    public enum Phase { IDLE, LOBBY, SELECTING, STARTING }

    private Phase phase = Phase.IDLE;

    // Joueurs dans la sélection
    private final Set<UUID> participants = new HashSet<>();
    // Choix de chaque joueur
    private final Map<UUID, String> chosenChampion = new HashMap<>();
    private final Map<UUID, RunePage> chosenRunes = new HashMap<>();
    private final Map<UUID, String[]> chosenSpells = new HashMap<>(); // [spell1, spell2]
    private final Set<UUID> locked = new HashSet<>(); // joueurs ayant verrouillé

    private int timeLeft = 0;
    private BossBar selectBar;
    private BukkitRunnable timerTask;

    private static final int SELECT_DURATION = 60; // 60s de sélection

    // ══════════════════════════════════════════════════════════════
    // DÉMARRAGE DE LA SÉLECTION
    // ══════════════════════════════════════════════════════════════

    /**
     * Lance la phase de sélection avec les joueurs donnés (ex: les 10 de la file).
     */
    /** Alias utilisé par MatchmakingManager et PreGameGUI — lance la sélection. */
    public void startBanPhase() {
        var gm = LolPlugin.getInstance().getGameManager();
        if (gm != null) startSelection(gm.getParticipants());
    }

    /** Appelé depuis ChampSelectGUI quand un joueur clique sur un champion en phase de ban. */
    public void onBanClick(org.bukkit.entity.Player player, String championId) {
        fr.lolmc.game.ChampSelectGUI.banChampion(championId);
    }

    public void startSelection(Collection<UUID> players) {
        if (phase != Phase.IDLE && phase != Phase.LOBBY) return;
        phase = Phase.SELECTING;
        participants.clear();
        participants.addAll(players);
        chosenChampion.clear();
        chosenRunes.clear();
        chosenSpells.clear();
        locked.clear();
        timeLeft = SELECT_DURATION;

        selectBar = BossBar.bossBar(Component.text("Sélection — choisis ton champion!"),
                1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);

        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.showBossBar(selectBar);
            p.sendMessage(Component.text("═══ SÉLECTION DE CHAMPION ═══", NamedTextColor.LIGHT_PURPLE));
            p.sendMessage(Component.text("Choisis : /pick <champion>, /runes, /spell <sort1> <sort2>",
                    NamedTextColor.AQUA));
            p.sendMessage(Component.text("Puis /lock pour verrouiller. Tu as " + SELECT_DURATION + "s.",
                    NamedTextColor.GRAY));
            // Ouvrir le menu de sélection de champion
            LolPlugin.getInstance().getChampSelectGUI().openChampionMenu(p);
        }

        startTimer();
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

    public void chooseChampion(Player player, String championId) {
        if (!isParticipant(player.getUniqueId())) return;
        if (locked.contains(player.getUniqueId())) {
            player.sendActionBar(Component.text("Tu as déjà verrouillé!", NamedTextColor.RED));
            return;
        }
        chosenChampion.put(player.getUniqueId(), championId);
        player.sendActionBar(Component.text("✔ Champion: " + championId, NamedTextColor.GREEN));
    }

    public void chooseRunes(Player player, RunePage page) {
        if (!isParticipant(player.getUniqueId())) return;
        chosenRunes.put(player.getUniqueId(), page);
        player.sendActionBar(Component.text("✔ Runes enregistrées", NamedTextColor.GREEN));
    }

    public void chooseSpells(Player player, String spell1, String spell2) {
        if (!isParticipant(player.getUniqueId())) return;
        chosenSpells.put(player.getUniqueId(), new String[]{spell1, spell2});
        player.sendActionBar(Component.text("✔ Sorts: " + spell1 + ", " + spell2, NamedTextColor.GREEN));
    }

    public void lock(Player player) {
        if (!isParticipant(player.getUniqueId())) return;
        if (!chosenChampion.containsKey(player.getUniqueId())) {
            player.sendActionBar(Component.text("Choisis d'abord un champion!", NamedTextColor.RED));
            return;
        }
        locked.add(player.getUniqueId());
        player.sendActionBar(Component.text("🔒 Verrouillé! En attente des autres...", NamedTextColor.GOLD));
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

        // Appliquer les choix de chaque joueur
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;

            // Champion (par défaut Garen si rien choisi)
            String champ = chosenChampion.getOrDefault(id, "garen");
            cm.assignChampion(p, champ);

            // Runes (page par défaut si rien)
            RunePage page = chosenRunes.getOrDefault(id, RunePage.defaultPage());
            rm.setPage(id, page);
            rm.applyRuneStats(p);

            p.sendMessage(Component.text("⚔ Partie lancée avec " + champ + "!", NamedTextColor.GOLD));
        }

        // Lancer la partie physique
        LolPlugin.getInstance().getGameManager().startGame();
        LolPlugin.getInstance().getMinionManager().startWaves();
        LolPlugin.getInstance().getJungleManager().startJungle();

        phase = Phase.IDLE;
    }

    public void cancel() {
        phase = Phase.IDLE;
        if (timerTask != null) timerTask.cancel();
        if (selectBar != null) {
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(selectBar);
            }
        }
        participants.clear();
    }

    public Phase getPhase() { return phase; }
}
