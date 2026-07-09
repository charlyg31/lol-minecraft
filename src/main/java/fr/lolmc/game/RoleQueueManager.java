package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * File d'attente par rôles (façon LoL).
 *
 * Chaque joueur choisit au moins 2 rôles avant d'entrer en file. Le matchmaking
 * compose deux équipes de 5 en respectant les rôles : un joueur par rôle
 * (top, jungle, mid, adc, support) dans chaque équipe.
 *
 * Quand 2 équipes complètes sont formées, la phase de sélection démarre.
 */
public class RoleQueueManager {

    public enum Role {
        TOP("Top"), JUNGLE("Jungle"), MID("Mid"), ADC("ADC"), SUPPORT("Support");
        public final String displayName;
        Role(String n) { this.displayName = n; }
    }

    // Rôles préférés de chaque joueur (au moins 2)
    private final Map<UUID, Set<Role>> preferredRoles = new HashMap<>();
    // Joueurs actuellement en file
    private final Set<UUID> inQueue = new LinkedHashSet<>();
    // Rôle assigné à chaque joueur une fois la partie trouvée
    private final Map<UUID, Role> assignedRole = new HashMap<>();

    private static final int PLAYERS_PER_GAME = 10; // 2×5

    // ══════════════════════════════════════════════════════════════
    // CHOIX DES RÔLES (avant la file)
    // ══════════════════════════════════════════════════════════════

    public void setPreferredRoles(UUID uuid, Set<Role> roles) {
        preferredRoles.put(uuid, new HashSet<>(roles));
    }

    public Set<Role> getPreferredRoles(UUID uuid) {
        return preferredRoles.getOrDefault(uuid, new HashSet<>());
    }

    public void toggleRole(Player player, Role role) {
        Set<Role> roles = preferredRoles.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        if (roles.contains(role)) {
            roles.remove(role);
            player.sendActionBar(Component.text("➖ " + role.displayName + " retiré", NamedTextColor.YELLOW));
        } else {
            roles.add(role);
            player.sendActionBar(Component.text("➕ " + role.displayName + " ajouté", NamedTextColor.GREEN));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // FILE D'ATTENTE
    // ══════════════════════════════════════════════════════════════

    public boolean joinQueue(Player player) {
        Set<Role> roles = getPreferredRoles(player.getUniqueId());
        if (roles.size() < 2) {
            player.sendMessage(Component.text(
                    "❌ Choisis au moins 2 rôles avant d'entrer en file (/roles).", NamedTextColor.RED));
            return false;
        }
        inQueue.add(player.getUniqueId());
        player.sendMessage(Component.text(
                "✔ En file d'attente (" + inQueue.size() + "/" + PLAYERS_PER_GAME + ")", NamedTextColor.GREEN));
        notifyQueueStatus(player.getUniqueId(), "JOINED");
        tryFormGame();
        return true;
    }

    public void leaveQueue(Player player) {
        inQueue.remove(player.getUniqueId());
        player.sendMessage(Component.text("Sorti de la file.", NamedTextColor.YELLOW));
        notifyQueueStatus(player.getUniqueId(), "LEFT");
    }

    /** Notifie le lobby (BungeeCord) du statut de queue d'un joueur, si activé. */
    private void notifyQueueStatus(UUID uuid, String status) {
        var bridge = LolPlugin.getInstance().getBridgeManager();
        if (bridge != null && bridge.isEnabled()) {
            bridge.notifyQueueStatus(uuid, status, inQueue.size());
        }
    }

    public boolean isInQueue(UUID uuid) { return inQueue.contains(uuid); }
    public int getQueueSize() { return inQueue.size(); }

    // ══════════════════════════════════════════════════════════════
    // COMPOSITION DES ÉQUIPES PAR RÔLES
    // ══════════════════════════════════════════════════════════════

    private void tryFormGame() {
        if (inQueue.size() < PLAYERS_PER_GAME) return;

        // Prendre les 10 premiers en file
        List<UUID> pool = new ArrayList<>(inQueue);
        pool = pool.subList(0, PLAYERS_PER_GAME);

        // Tenter d'assigner les rôles : 2 équipes × 5 rôles
        Map<UUID, Role> assignment = assignRoles(pool);
        if (assignment == null) {
            // Composition impossible avec les rôles actuels — on attend
            return;
        }

        // Retirer ces 10 joueurs de la file
        for (UUID id : pool) inQueue.remove(id);
        assignedRole.putAll(assignment);

        // Notifier et lancer la sélection
        for (UUID id : pool) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Role r = assignment.get(id);
                p.sendMessage(Component.text("⚔ Partie trouvée! Ton rôle : " + r.displayName,
                        NamedTextColor.GOLD));
            }
        }

        // Démarrer la phase de sélection avec ces joueurs
        LolPlugin.getInstance().getChampSelectManager().startSelection(pool);
    }

    /**
     * Assigne un rôle à chaque joueur en respectant ses préférences.
     * Besoin : 2 joueurs par rôle (un par équipe). Backtracking.
     * @return map joueur→rôle, ou null si impossible
     */
    private Map<UUID, Role> assignRoles(List<UUID> players) {
        // Chaque rôle doit être pris exactement 2 fois (une fois par équipe)
        Map<Role, Integer> needed = new EnumMap<>(Role.class);
        for (Role r : Role.values()) needed.put(r, 2);

        Map<UUID, Role> result = new HashMap<>();
        if (backtrack(players, 0, needed, result)) {
            return result;
        }
        return null;
    }

    private boolean backtrack(List<UUID> players, int idx,
                              Map<Role, Integer> needed, Map<UUID, Role> result) {
        if (idx >= players.size()) {
            // Tous les rôles doivent être comblés
            return needed.values().stream().allMatch(v -> v == 0);
        }
        UUID player = players.get(idx);
        Set<Role> prefs = getPreferredRoles(player);

        for (Role role : prefs) {
            if (needed.get(role) > 0) {
                needed.put(role, needed.get(role) - 1);
                result.put(player, role);
                if (backtrack(players, idx + 1, needed, result)) return true;
                // Annuler
                needed.put(role, needed.get(role) + 1);
                result.remove(player);
            }
        }
        return false;
    }

    public Role getAssignedRole(UUID uuid) {
        return assignedRole.get(uuid);
    }

    public void cleanup(UUID uuid) {
        inQueue.remove(uuid);
        assignedRole.remove(uuid);
    }
}
