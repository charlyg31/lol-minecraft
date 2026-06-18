package fr.lolmc.champion.base;

import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public abstract class BaseChampion {

    protected final String id;
    protected final String displayName;
    protected final ChampionRole role;
    protected final ChampionStats stats;

    // 5 slots : index 0=AA, 1=Q, 2=W, 3=E, 4=R
    protected final BaseAbility[] abilities = new BaseAbility[5];

    public enum ChampionRole { TOP, JUNGLE, MID, SUPPORT, ADC }

    public BaseChampion(String id, String displayName, ChampionRole role, ChampionStats stats) {
        this.id          = id;
        this.displayName = displayName;
        this.role        = role;
        this.stats       = stats;
        registerAbilities();
    }

    /** Chaque champion enregistre ses 5 sorts ici */
    protected abstract void registerAbilities();

    // ══════════════════════════════════════════════
    // INVENTAIRE — barre 1..5 = AA,Q,W,E,R
    // ══════════════════════════════════════════════

    /**
     * Remplit les slots 0..4 de l'inventaire avec les sorts.
     * Slot 0 = attaque de base, slots 1-4 = Q/W/E/R
     */
    public void applyInventory(Player player) {
        for (int i = 0; i < 5; i++) {
            if (abilities[i] != null) {
                player.getInventory().setItem(i, abilities[i].buildItemStack(stats));
            }
        }
    }

    /**
     * Rafraîchit un slot (ex: après un niveau ou un objet)
     */
    public void refreshSlot(Player player, int slot) {
        if (slot < 0 || slot > 4 || abilities[slot] == null) return;
        player.getInventory().setItem(slot, abilities[slot].buildItemStack(stats));
    }

    /**
     * Retourne le sort correspondant au slot tenu en main (0..4)
     */
    public BaseAbility getAbilityForSlot(int heldSlot) {
        if (heldSlot < 0 || heldSlot > 4) return null;
        return abilities[heldSlot];
    }

    /**
     * Tente de lancer le sort du slot actuel.
     * Vérifie cooldown, puis exécute.
     */
    public void tryUseAbility(Player player, int slot, Player target) {
        BaseAbility ability = getAbilityForSlot(slot);
        if (ability == null) return;

        if (ability.isOnCooldown(player)) {
            double remaining = ability.getRemainingCooldown(player);
            player.sendActionBar(Component.text(
                String.format("⏳ %s - %.1fs", ability.getName(), remaining),
                NamedTextColor.RED
            ));
            return;
        }

        ability.cast(this, stats, target);
        ability.triggerCooldown(player);
    }

    // ══════════════════════════════════════════════
    // AFFICHAGE PORTÉE (quand on tient un sort)
    // ══════════════════════════════════════════════

    public void displayRangeIfHoldingAbility(Player player) {
        int slot = player.getInventory().getHeldItemSlot();
        if (slot < 0 || slot > 4) return;
        BaseAbility ability = abilities[slot];
        if (ability != null && slot > 0) { // slot 0 = AA, pas d'affichage
            ability.displayRangeForPlayer(player);
        }
    }

    // ══════════════════════════════════════════════
    // GETTERS
    // ══════════════════════════════════════════════

    public String getId()            { return id; }
    public String getDisplayName()   { return displayName; }
    public ChampionRole getRole()    { return role; }
    public ChampionStats getStats()  { return stats; }
    public BaseAbility[] getAbilities() { return abilities; }

    protected void setAbility(int slot, BaseAbility ability) {
        if (slot >= 0 && slot < 5) abilities[slot] = ability;
    }
}
