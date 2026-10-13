package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Système de vote d'abandon (FF) façon LoL.
 *
 * Règles :
 *  - Disponible à partir de 15 minutes (configurable)
 *  - 3/5 votes FOR dans les 60s → l'équipe abandonne
 *  - Une seule vote en cours par équipe à la fois
 *  - Timeout 60s si pas de majorité
 */
public class ForfeitManager {

    private static final long MIN_FF_TIME_MS = 15 * 60 * 1000L; // 15 minutes
    private static final int VOTE_TIMEOUT_S  = 60;
    private static final double VOTE_RATIO   = 3.0 / 5.0;       // 3/5

    // Clé = "<id de partie>:<équipe>" : deux parties simultanées ont des votes distincts.
    private final Map<String, Set<UUID>>   votes      = new HashMap<>();
    private final Map<String, org.bukkit.scheduler.BukkitTask> timers = new HashMap<>();

    private static int gameKey(Player p) {
        var inst = fr.lolmc.instance.InstanceHelper.instanceOf(p);
        return inst != null ? inst.getId() : 0;
    }

    private static String key(int game, Team team) { return game + ":" + team.name(); }

    /** Joueurs de la même partie (instance, ou tous les joueurs en jeu hors instances). */
    private static Collection<Player> groupOf(Player p) {
        var inst = fr.lolmc.instance.InstanceHelper.instanceOf(p);
        return inst != null ? inst.getOnlinePlayers() : fr.lolmc.util.WorldContext.getGamePlayers();
    }

    private static List<Player> teamMembers(Player ref, Team team) {
        var tm = LolPlugin.getInstance().getTeamManager();
        List<Player> out = new ArrayList<>();
        for (Player q : groupOf(ref)) if (tm.getTeam(q) == team) out.add(q);
        return out;
    }

    /** Réinitialise tous les votes. */
    public void reset() {
        timers.values().forEach(t -> { if (t != null) t.cancel(); });
        votes.clear(); timers.clear();
    }

    /** Réinitialise les votes d'une seule partie (0 = hors instance). */
    public void reset(int game) {
        String prefix = game + ":";
        timers.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(prefix)) return false;
            if (e.getValue() != null) e.getValue().cancel();
            return true;
        });
        votes.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Un joueur vote pour l'abandon.
     * Retourne false si le vote est impossible (trop tôt, déjà voté, pas en partie).
     */
    public boolean vote(Player player) {
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(player);
        if (!gm.isGameRunning()) {
            player.sendMessage(Component.text("❌ Aucune partie en cours.", NamedTextColor.RED));
            return false;
        }

        // Vérifier le temps minimum
        long elapsed = gm.getElapsedSeconds() * 1000L;
        if (elapsed < MIN_FF_TIME_MS) {
            long remaining = (MIN_FF_TIME_MS - elapsed) / 60000L + 1;
            player.sendMessage(Component.text(
                String.format("❌ L'abandon n'est disponible qu'à partir de 15min (encore %dmin).", remaining),
                NamedTextColor.RED));
            return false;
        }

        var tm = LolPlugin.getInstance().getTeamManager();
        Team team = tm.getTeam(player);
        if (team == null) { player.sendMessage(Component.text("❌ Equipe inconnue.", NamedTextColor.RED)); return false; }

        final int game = gameKey(player);
        final String k = key(game, team);
        final Player ref = player;

        // Démarrer un nouveau vote si aucun en cours
        if (!votes.containsKey(k)) {
            votes.put(k, new HashSet<>());
            broadcastTeam(ref, team, Component.text(
                "🏳 Vote d'abandon lancé par " + player.getName() + " ! /l ff pour voter (60s)",
                NamedTextColor.YELLOW));
            // Timeout
            var task = new BukkitRunnable() {
                @Override public void run() {
                    if (votes.containsKey(k)) {
                        votes.remove(k); timers.remove(k);
                        broadcastTeam(ref, team, Component.text(
                            "🏳 Vote d'abandon expiré.", NamedTextColor.GRAY));
                    }
                }
            };
            timers.put(k, task.runTaskLater(LolPlugin.getInstance(), VOTE_TIMEOUT_S * 20L));
        }

        Set<UUID> teamVotes = votes.get(k);
        if (teamVotes.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("Tu as déjà voté.", NamedTextColor.GRAY));
            return false;
        }

        teamVotes.add(player.getUniqueId());

        // Compter les membres de l'équipe en ligne
        int total = teamMembers(ref, team).size();
        int needed = (int) Math.ceil(total * VOTE_RATIO);

        broadcastTeam(ref, team, Component.text(
            String.format("🏳 %s a voté l'abandon (%d/%d)", player.getName(), teamVotes.size(), needed),
            NamedTextColor.YELLOW));

        if (teamVotes.size() >= needed) {
            // Abandon !
            var tk = timers.get(k);
            if (tk != null) tk.cancel();
            votes.remove(k); timers.remove(k);
            Component ffMsg = Component.text(
                "🏳 L'équipe " + (team == Team.BLUE ? "Bleue" : "Rouge") + " abandonne !",
                NamedTextColor.RED);
            for (Player gp : groupOf(ref)) gp.sendMessage(ffMsg);
            Team winner = team == Team.BLUE ? Team.RED : Team.BLUE;
            var sdl = LolPlugin.getInstance().getStructureDamageListener();
            if (sdl != null) {
                sdl.endMatch(winner, player);
            } else {
                LolPlugin.getInstance().getMatchScoreboard().showEndScreen(winner, groupOf(ref), gm);
                fr.lolmc.instance.InstanceHelper.gameManager(player).stopGame();
            }
        }
        return true;
    }

    private void broadcastTeam(Player ref, Team team, Component msg) {
        for (Player p : teamMembers(ref, team)) p.sendMessage(msg);
    }
}
