package codecrafter47.bungeetablistplus.api.bukkit;

import org.bukkit.entity.Player;

/**
 * STUB de compilation de l'API BungeeTabListPlus (même signature que la vraie classe).
 * Exclu du jar final (voir maven-shade-plugin) : au runtime, la vraie classe est fournie
 * par le plugin BungeeTabListPlus (bridge Bukkit).
 */
public abstract class Variable {
    private final String name;

    public Variable(String name) { this.name = name; }

    /** Appelé par BTLP depuis des threads autres que le thread principal, éventuellement en parallèle. */
    public abstract String getReplacement(Player player);

    public final String getName() { return name; }
}
