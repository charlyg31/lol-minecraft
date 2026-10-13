package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RunePage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Point d'entrée de la sélection de champion. Chaque partie (groupe de joueurs) a sa
 * propre {@link ChampSelectSession} : plusieurs sélections peuvent tourner en parallèle
 * sans interférer (équipes, timers, barres, choix, verrous séparés).
 */
public class ChampSelectManager {

    public enum Phase { IDLE, LOBBY, SELECTING, STARTING }

    /** Session de sélection de chaque joueur. */
    private final Map<UUID, ChampSelectSession> sessions = new ConcurrentHashMap<>();

    // Joueurs immobilisés entre « partie trouvée » et le lancement effectif de la partie
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    // ── Démarrage ─────────────────────────────────────────────────

    /** Alias utilisé par PreGameGUI : sélection pour les participants du GameManager global. */
    public void startBanPhase() {
        var gm = LolPlugin.getInstance().getGameManager();
        if (gm != null) startSelection(gm.getParticipants());
    }

    public void startSelection(Collection<UUID> players) {
        startSelection(players, false);
    }

    public void startSelection(Collection<UUID> players, boolean ranked) {
        ChampSelectSession session = new ChampSelectSession();
        for (UUID id : players) sessions.put(id, session);
        session.startSelection(players, ranked);
    }

    /** Appelé par une session terminée ou annulée. */
    void sessionEnded(ChampSelectSession session, Collection<UUID> participants) {
        sessions.values().removeIf(s -> s == session);
        frozen.removeAll(participants);
    }

    // ── Délégation par joueur ─────────────────────────────────────

    private ChampSelectSession of(UUID id) { return sessions.get(id); }

    public boolean isSelecting() {
        return sessions.values().stream().anyMatch(ChampSelectSession::isSelecting);
    }

    public boolean isParticipant(UUID uuid) {
        ChampSelectSession s = of(uuid);
        return s != null && s.isParticipant(uuid);
    }

    public void chooseChampion(Player p, String id)           { var s = of(p.getUniqueId()); if (s != null) s.chooseChampion(p, id); }
    public void chooseRunes(Player p, RunePage page)          { var s = of(p.getUniqueId()); if (s != null) s.chooseRunes(p, page); }
    public void chooseSpells(Player p, String s1, String s2)  { var s = of(p.getUniqueId()); if (s != null) s.chooseSpells(p, s1, s2); }
    public void lock(Player p)                                { var s = of(p.getUniqueId()); if (s != null) s.lock(p); }
    public void onSkinChosen(Player p, String champ, String skin) { var s = of(p.getUniqueId()); if (s != null) s.onSkinChosen(p, champ, skin); }
    public void onBanClick(Player p, String champ)            { var s = of(p.getUniqueId()); if (s != null) s.onBanClick(p, champ); }

    public java.util.Set<String> getBannedChampions() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (ChampSelectSession s : new HashSet<>(sessions.values())) all.addAll(s.getBannedChampions());
        return all;
    }

    /** IDLE si aucune sélection n'est en cours, sinon la phase la plus avancée parmi les sessions. */
    /** Champions bannis dans la session de ce joueur (vide s'il n'est pas en sélection). */
    public Set<String> bansFor(UUID id) {
        ChampSelectSession s = sessions.get(id);
        return s != null ? s.getBannedChampions() : Collections.emptySet();
    }

    public Phase getPhase() {
        Phase best = Phase.IDLE;
        for (ChampSelectSession s : sessions.values()) {
            if (s.getPhase().ordinal() > best.ordinal()) best = s.getPhase();
        }
        return best;
    }

    /** Annule toutes les sélections en cours. */
    public void cancel() {
        for (ChampSelectSession s : new HashSet<>(sessions.values())) s.cancel();
        sessions.clear();
    }

    // ── Immobilisation ────────────────────────────────────────────

    public boolean isFrozen(UUID id) { return frozen.contains(id); }

    /** Immobilise les joueurs (auto-libération après 3 min par sécurité). */
    public void freeze(Collection<UUID> ids) {
        Set<UUID> copy = new HashSet<>(ids);
        frozen.addAll(copy);
        Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> frozen.removeAll(copy), 20L * 180);
    }

    public void unfreezeAll() { frozen.clear(); }

    /** Libère uniquement ces joueurs (n'affecte pas les autres parties). */
    public void unfreeze(Collection<UUID> ids) { frozen.removeAll(ids); }
}
