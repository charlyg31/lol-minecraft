package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/**
 * Filtre centralisé par monde.
 *
 * Le nom du monde LoL est défini dans config.yml :
 *   world:
 *     name: "lolmc"        # nom du monde où le jeu se déroule
 *     lobby: "lobby"       # nom du monde lobby (optionnel)
 *
 * Toutes les boucles sur getOnlinePlayers() dans le plugin
 * passent par WorldContext.getGamePlayers() pour éviter
 * d'affecter les joueurs dans d'autres mondes du serveur.
 */
public final class WorldContext {

    private WorldContext() {}

    // ── Accesseurs du monde ───────────────────────────────────────

    /** Nom du monde de jeu (depuis config.yml → world.name). */
    public static String getGameWorldName() {
        return LolPlugin.getInstance().getConfig().getString("world.name", "world");
    }

    /** Nom du monde lobby (depuis config.yml → world.lobby). */
    public static String getLobbyWorldName() {
        return LolPlugin.getInstance().getConfig().getString("world.lobby", "");
    }

    /** Retourne le monde de jeu, ou null si non chargé. */
    public static World getGameWorld() {
        return Bukkit.getWorld(getGameWorldName());
    }

    /** Retourne le monde lobby, ou null si non configuré/chargé. */
    public static World getLobbyWorld() {
        String name = getLobbyWorldName();
        return name.isEmpty() ? null : Bukkit.getWorld(name);
    }

    // ── Filtres sur joueurs ───────────────────────────────────────

    /**
     * Joueurs actuellement dans le monde de jeu LoL.
     * Si le système d'instances est actif, retourne les joueurs
     * du monde de l'instance du joueur appelant.
     * Sinon, retourne les joueurs du monde configuré.
     */
    public static Collection<Player> getGamePlayers() {
        // Monde de jeu configuré + mondes d'instances (copies du template)
        List<Player> result = new java.util.ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isInGameWorld(p)) result.add(p);
        }
        return result;
    }

    /**
     * Joueurs d'une instance donnée.
     * Utiliser dans les managers d'instance pour éviter les fuites.
     */
    public static Collection<Player> getInstancePlayers(
            fr.lolmc.instance.GameInstance instance) {
        if (instance == null || instance.getWorld() == null) return List.of();
        return instance.getWorld().getPlayers();
    }

    /**
     * Vrai si le joueur est dans le monde template (admin) OU dans une instance.
     */
    public static boolean isInGameWorld(Player player) {
        // Vérifier le monde template (configuration admin)
        World configured = getGameWorld();
        if (configured != null && configured.equals(player.getWorld())) return true;
        // Vérifier si dans une instance
        return fr.lolmc.instance.InstanceWorldContext.isInAnyInstance(player);
    }

    /**
     * Annonce limitée à la partie qui se joue dans {@code world} : dans un monde d'instance,
     * seuls les joueurs de ce monde la reçoivent ; sinon (partie unique), tous les joueurs
     * qui ne sont pas dans une instance (les autres parties ne sont pas spammées).
     */
    public static void broadcast(World world, net.kyori.adventure.text.Component message) {
        var im = LolPlugin.getInstance().getInstanceManager();
        if (world != null && im != null && im.getInstanceByWorldName(world.getName()) != null) {
            for (Player p : world.getPlayers()) p.sendMessage(message);
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (im != null && im.getInstanceOf(p) != null) continue;
            p.sendMessage(message);
        }
    }

    /**
     * Vrai si le joueur est dans le monde lobby.
     */
    public static boolean isInLobbyWorld(Player player) {
        World w = getLobbyWorld();
        if (w == null) return false;
        return w.equals(player.getWorld());
    }
}
