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
    // Or total gagné dans la partie (hors or de départ et remboursements), comme le « team gold » LoL
    private final Map<UUID, Integer> earned = new HashMap<>();

    private static final int START_GOLD = 500;

    public void initPlayer(UUID uuid) {
        gold.put(uuid, START_GOLD);
        earned.put(uuid, 0);
    }

    public int getGold(UUID uuid) {
        return gold.getOrDefault(uuid, 0);
    }

    public void addGold(UUID uuid, int amount) {
        gold.merge(uuid, amount, Integer::sum);
        if (amount > 0) earned.merge(uuid, amount, Integer::sum);
    }

    /** Rend de l'or (vente, achat annulé) : ne compte pas comme de l'or gagné. */
    public void refundGold(UUID uuid, int amount) {
        gold.merge(uuid, amount, Integer::sum);
    }

    /** Or total gagné depuis le début de la partie (dépensé ou non). */
    public int getEarned(UUID uuid) {
        return earned.getOrDefault(uuid, 0);
    }

    public boolean spendGold(UUID uuid, int amount) {
        int current = getGold(uuid);
        if (current < amount) return false;
        gold.put(uuid, current - amount);
        return true;
    }

    public void removePlayer(UUID uuid) {
        gold.remove(uuid);
        earned.remove(uuid);
    }


    /** Définit l'or d'un joueur à une valeur exacte (utilisé pour la reconnexion). */
    public void setGold(java.util.UUID uuid, int amount) {
        gold.put(uuid, Math.max(0, amount));
    }
}
