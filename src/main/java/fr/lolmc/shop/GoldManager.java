package fr.lolmc.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère l'or LoL de chaque joueur.
 * Or de départ : 500 (comme en partie LoL)
 */
public class GoldManager {

    private final Map<UUID, Integer> gold = new HashMap<>();

    private static final int START_GOLD = 500;

    public void initPlayer(UUID uuid) {
        gold.put(uuid, START_GOLD);
    }

    public int getGold(UUID uuid) {
        return gold.getOrDefault(uuid, 0);
    }

    public void addGold(UUID uuid, int amount) {
        gold.merge(uuid, amount, Integer::sum);
    }

    public boolean spendGold(UUID uuid, int amount) {
        int current = getGold(uuid);
        if (current < amount) return false;
        gold.put(uuid, current - amount);
        return true;
    }

    public void removePlayer(UUID uuid) {
        gold.remove(uuid);
    }


    /** Définit l'or d'un joueur à une valeur exacte (utilisé pour la reconnexion). */
    public void setGold(java.util.UUID uuid, int amount) {
        gold.put(uuid, Math.max(0, amount));
    }
}
