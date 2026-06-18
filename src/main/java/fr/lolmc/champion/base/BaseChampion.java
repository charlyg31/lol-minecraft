package fr.lolmc.champion.base;

import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

public abstract class BaseChampion {

    protected final String id;
    protected final String displayName;
    protected final ChampionRole role;
    protected final ChampionStats stats;
    protected final BaseAbility[] abilities = new BaseAbility[5];

    public enum ChampionRole { TOP, JUNGLE, MID, SUPPORT, ADC }

    public BaseChampion(String id, String displayName, ChampionRole role, ChampionStats stats) {
        this.id = id; this.displayName = displayName;
        this.role = role; this.stats = stats;
        registerAbilities();
    }

    protected abstract void registerAbilities();

    // ─── Inventaire ──────────────────────────────────────────────

    public void applyInventory(Player player) {
        for (int i = 0; i < 5; i++)
            if (abilities[i] != null)
                player.getInventory().setItem(i, abilities[i].buildItemStack(stats));
    }

    public void refreshSlot(Player player, int slot) {
        if (slot < 0 || slot > 4 || abilities[slot] == null) return;
        player.getInventory().setItem(slot, abilities[slot].buildItemStack(stats));
    }

    // ─── Lancement de sort ───────────────────────────────────────

    /**
     * Tente de lancer le sort du slot donné.
     * @param caster le joueur qui lance
     * @param slot   0=AA, 1=Q, 2=W, 3=E, 4=R
     * @param target la cible (null si self-cast ou directionnel)
     */
    public void tryUseAbility(Player caster, int slot, Player target) {
        if (slot < 0 || slot > 4) return;
        BaseAbility ability = abilities[slot];
        if (ability == null) return;

        if (ability.isOnCooldown(caster)) {
            caster.sendActionBar(Component.text(
                String.format("⏳ %s — %.1fs", ability.getName(), ability.getRemainingCooldown(caster)),
                NamedTextColor.RED));
            return;
        }

        ability.cast(caster, stats, target);
        ability.triggerCooldown(caster);
        // Refresh tooltip après le cast
        refreshSlot(caster, slot);
    }

    // ─── Affichage portée ─────────────────────────────────────────

    public void displayRangeIfHoldingAbility(Player player) {
        int slot = player.getInventory().getHeldItemSlot();
        if (slot < 1 || slot > 4) return; // slot 0 = AA, pas d'affichage
        if (abilities[slot] != null) abilities[slot].displayRangeForPlayer(player);
    }

    // ─── Getters ─────────────────────────────────────────────────

    public String            getId()          { return id; }
    public String            getDisplayName() { return displayName; }
    public ChampionRole      getRole()        { return role; }
    public ChampionStats     getStats()       { return stats; }
    public BaseAbility[]     getAbilities()   { return abilities; }
    public BaseAbility       getAbility(int i){ return (i>=0&&i<5)?abilities[i]:null; }

    protected void setAbility(int slot, BaseAbility a) {
        if (slot >= 0 && slot < 5) abilities[slot] = a;
    }
}
