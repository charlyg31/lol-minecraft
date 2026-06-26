package fr.lolmc.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.*;

/** File d'attente cross-serveur. Attend N joueurs puis envoie tout le monde sur le serveur de jeu. */
public class LobbyQueueManager {

    private final LobbyPlugin plugin;
    private final LobbyPartyManager partyManager;
    private LobbyBridge bridge;
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final int playersNeeded;

    public LobbyQueueManager(LobbyPlugin plugin, LobbyPartyManager pm, LobbyBridge bridge) {
        this.plugin = plugin;
        this.partyManager = pm;
        this.bridge = bridge;
        this.playersNeeded = plugin.getConfig().getInt("players-per-game", 10);
    }

    public void setBridge(LobbyBridge b) { this.bridge = b; }

    public void join(Player player) {
        if (queue.contains(player.getUniqueId())) return;
        List<UUID> group = partyManager.getPartyMembers(player.getUniqueId());
        if (queue.size() + group.size() > playersNeeded) return;
        queue.addAll(group);
        // Un seul message dans le tchat pour chaque joueur ajouté
        for (UUID id : group) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null)
                p.sendMessage(Component.text(
                    "Vous êtes dans la file d'attente.",
                    NamedTextColor.GREEN));
        }
        checkAndStartGame();
    }

    public void leave(Player player) {
        queue.remove(player.getUniqueId());
    }

    private void checkAndStartGame() {
        if (queue.size() < playersNeeded) return;
        // Prendre les N premiers joueurs
        List<UUID> gamePlayers = new ArrayList<>();
        Iterator<UUID> it = queue.iterator();
        for (int i = 0; i < playersNeeded && it.hasNext(); i++) {
            gamePlayers.add(it.next()); it.remove();
        }
        // Envoyer les données puis connecter
        for (UUID uuid : gamePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && bridge != null) {
                bridge.sendPlayerToGame(p, true);
                // Délai pour que les données arrivent avant la connexion
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (bridge != null) bridge.sendToServer(p, plugin.getConfig().getString("game-server","lolmc-01"));
                }, 40L);
            }
        }
    }

    public int getQueueSize()     { return queue.size(); }
    public int getPlayersNeeded() { return playersNeeded; }
    public boolean isInQueue(UUID uuid) { return queue.contains(uuid); }
}
