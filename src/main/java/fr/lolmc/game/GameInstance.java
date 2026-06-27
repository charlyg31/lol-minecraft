package fr.lolmc.game;

import org.bukkit.World; // Import essentiel pour corriger l'erreur "cannot find symbol"
import org.bukkit.entity.Player;
import org.bukkit.Location;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

public class GameInstance {

    private final World world; // Le monde spécifique à cette partie[cite: 10]
    private boolean gameRunning = false; //[cite: 10]

    // Les maps deviennent non-statiques pour être uniques à chaque instance de partie[cite: 10]
    private final Map<UUID, Location> respawnAt = new HashMap<>();
    private final Map<UUID, Double> snapshots = new HashMap<>(); // Exemple pour stocker des données (vie, etc.)

    public GameInstance(World world) { //[cite: 10]
        this.world = world; //[cite: 10]
    }

    /**
     * Démarre la partie pour cette instance.
     */
    public void startGame() { //[cite: 10]
        this.gameRunning = true;

        // IMPORTANT : Remplacement de WorldContext.getGamePlayers() par world.getPlayers()[cite: 10]
        for (Player player : world.getPlayers()) {
            player.sendMessage("§aLa partie commence ! Bonne chance.");
        }
    }

    /**
     * Gère la mort d'un joueur dans cette instance.
     */
    public void onPlayerDeath(Player player) { //[cite: 10]
        if (!gameRunning) return;

        Location respawnLocation = respawnAt.get(player.getUniqueId());
        if (respawnLocation != null) {
            player.teleport(respawnLocation);
            player.sendMessage("§eVous avez réapparu !");
        }
    }

    /**
     * Arrête la partie et nettoie les données locales.
     */
    public void stopGame() {
        this.gameRunning = false;

        for (Player player : world.getPlayers()) { //[cite: 10]
            player.sendMessage("§cLa partie est maintenant terminée.");
        }

        // Nettoyage des maps de l'instance
        this.respawnAt.clear();
        this.snapshots.clear();
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public World getWorld() {
        return world;
    }

    public boolean isGameRunning() {
        return gameRunning;
    }

    public Map<UUID, Location> getRespawnAt() {
        return respawnAt;
    }

    public Map<UUID, Double> getSnapshots() {
        return snapshots;
    }
}