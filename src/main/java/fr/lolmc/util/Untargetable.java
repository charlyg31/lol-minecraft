package fr.lolmc.util;

import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * État « intouchable » de LoL (Frappe Alpha de Master Yi, bond de la Marque de Mort de Zed) : le
 * champion ne peut être ni ciblé ni blessé, par les sorts, les attaques, les tourelles et les sbires.
 */
public final class Untargetable {

    private Untargetable() {}

    private static final Map<UUID, Long> UNTIL = new ConcurrentHashMap<>();

    /** Rend l'entité intouchable pendant {@code ticks} ticks. */
    public static void apply(Entity e, int ticks) {
        UNTIL.merge(e.getUniqueId(), System.currentTimeMillis() + ticks * 50L, Math::max);
    }

    public static boolean is(Entity e) {
        if (e == null) return false;
        Long until = UNTIL.get(e.getUniqueId());
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        UNTIL.remove(e.getUniqueId());
        return false;
    }
}
