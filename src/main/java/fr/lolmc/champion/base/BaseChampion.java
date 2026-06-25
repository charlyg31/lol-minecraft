package fr.lolmc.champion.base;

import fr.lolmc.LolPlugin;
import fr.lolmc.stats.HPSystem;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.stats.LevelSystem;

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
    protected HPSystem hpSystem;
    protected ResourceSystem resourceSystem;
    protected final LevelSystem levelSystem = new LevelSystem();
    protected double autoAttackRange = 2.5; // portée AA par défaut (mêlée), surchargée par champion

    public enum ChampionRole { TOP, JUNGLE, MID, SUPPORT, ADC }

    public BaseChampion(String id, String displayName, ChampionRole role, ChampionStats stats) {
        this.id = id; this.displayName = displayName;
        this.role = role; this.stats = stats;
        registerAbilities();
        // HPSystem et ResourceSystem initialisés par les sous-classes via initSystems()
    }

    protected void initSystems(double maxHP, double hpRegen,
                                ResourceSystem.ResourceType resType,
                                double maxResource, double resRegen) {
        this.hpSystem = new HPSystem(maxHP, hpRegen);
        this.hpSystem.linkStats(this.stats); // lier pour GW
        this.resourceSystem = new ResourceSystem(resType, maxResource, resRegen);
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
        // Passer par HotbarManager pour CONSERVER le marquage PDC de l'item
        // (sinon getType() renvoie null après le 1er cast et les clics sont ignorés)
        LolPlugin.getInstance().getHotbarManager().refreshAbilitySlot(player, this, slot);
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
        if (ability == null) {
            fr.lolmc.util.DebugLogger.log("TryUseAbility", "ABILITY NULL slot=" + slot);
            return;
        }
        fr.lolmc.util.DebugLogger.log("TryUseAbility", "sort=" + ability.getName()
            + " slot=" + slot + " rang=" + levelSystem.getAbilityRank(slot)
            + " unlocked=" + (slot==0 || levelSystem.isAbilityUnlocked(slot))
            + " onCooldown=" + ability.isOnCooldown(caster));

        // Blocage par contrôle de foule : stun bloque tout, silence bloque les sorts (pas l'AA)
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null) {
            boolean blocked = (slot == 0) ? cc.isStunned(caster.getUniqueId())
                                          : !cc.canCastAbility(caster.getUniqueId());
            if (blocked) {
                caster.sendActionBar(Component.text(
                    cc.isStunned(caster.getUniqueId()) ? "💫 Étourdi — action impossible!" : "🔇 Réduit au silence!",
                    NamedTextColor.YELLOW));
                return;
            }
        }

        // Le sort doit être débloqué (AA slot 0 toujours OK)
        if (slot >= 1 && !levelSystem.isAbilityUnlocked(slot)) {
            caster.sendActionBar(Component.text(
                "🔒 " + ability.getName() + " pas encore débloqué (clique pour l'apprendre)",
                NamedTextColor.GRAY));
            return;
        }

        if (ability.isOnCooldown(caster)) {
            caster.sendActionBar(Component.text(
                String.format("⏳ %s — %.1fs", ability.getName(), ability.getRemainingCooldown(caster)),
                NamedTextColor.RED));
            return;
        }

        // Vérifier et consommer la ressource (mana/énergie)
        if (resourceSystem != null && ability.getResourceCost() > 0) {
            if (!resourceSystem.consume(ability.getResourceCost())) {
                String resName = switch (resourceSystem.getType()) {
                    case MANA   -> "mana";
                    case ENERGY -> "énergie";
                    case FLOW   -> "flow";
                    case FURY   -> "furie";
                    default     -> "ressource";
                };
                caster.sendActionBar(Component.text(
                    String.format("❌ Pas assez de %s (%s: %.0f/%.0f)",
                        resName, ability.getName(),
                        resourceSystem.getCurrent(), resourceSystem.getMax()),
                    NamedTextColor.RED));
                return;
            }
        }

        ability.setLevel(levelSystem.getAbilityRank(slot));
        ability.cast(caster, stats, target);
        ability.triggerCooldown(caster);
        // Déclencher passifs post-sort (Spellblade, Shojin, etc.)
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onAbilityCast(caster, slot);
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
    public LevelSystem       getLevelSystem() { return levelSystem; }

    public double getAutoAttackRange()       { return autoAttackRange; }
    public void setAutoAttackRange(double r) { this.autoAttackRange = r; }

    /**
     * Améliore un sort si un point est disponible. Synchronise le level interne.
     * @return true si réussi
     */
    public boolean levelUpAbility(Player player, int slot) {
        if (levelSystem.levelUpAbility(slot)) {
            BaseAbility a = abilities[slot];
            if (a != null) {
                a.setLevel(levelSystem.getAbilityRank(slot));
                refreshSlot(player, slot);
            }
            return true;
        }
        return false;
    }

    public BaseAbility[]     getAbilities()   { return abilities; }
    public BaseAbility       getAbility(int i){ return (i>=0&&i<5)?abilities[i]:null; }

    public HPSystem getHPSystem()           { return hpSystem; }
    public ResourceSystem getResourceSystem() { return resourceSystem; }

    protected void setAbility(int slot, BaseAbility a) {
        if (slot >= 0 && slot < 5) abilities[slot] = a;
    }
}
