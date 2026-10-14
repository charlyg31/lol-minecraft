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

    // registerAbilities() est appelé volontairement depuis le constructeur : les sous-classes
    // n'y utilisent que des valeurs constantes.
    @SuppressWarnings("this-escape")
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
        this.hpSystem.linkStats(this.stats); // PV max = stats LoL (base + croissance + bonus)
        this.resourceSystem = new ResourceSystem(resType, maxResource, resRegen);
    }

    /** Comme initSystems, avec la croissance par niveau de la ressource et de sa régénération (LoL). */
    protected void initSystems(double hpRegen, ResourceSystem.ResourceType resType, double maxResource,
                               double resGrowth, double resRegen, double resRegenGrowth) {
        initSystems(stats.getFinalMaxHP(), hpRegen, resType, maxResource, resRegen);
        this.resourceGrowth = resGrowth;
        this.resourceRegenGrowth = resRegenGrowth;
    }

    // Croissance par niveau de la ressource (mana) et de sa régénération, et part déjà appliquée
    private double resourceGrowth = 0, resourceRegenGrowth = 0;
    private double appliedResGrowth = 0, appliedResRegenGrowth = 0;

    /**
     * À appeler quand le niveau change : met à jour le niveau des stats et la ressource max /
     * régénération (croissance LoL). Avant, le mana max ne grandissait jamais.
     */
    public void onLevelChanged(int level) {
        stats.setChampionLevel(level);
        if (resourceSystem == null) return;
        double g = stats.growthAt(resourceGrowth), rg = stats.growthAt(resourceRegenGrowth);
        resourceSystem.addMaxResource(g - appliedResGrowth);
        resourceSystem.addRegen(rg - appliedResRegenGrowth);
        appliedResGrowth = g; appliedResRegenGrowth = rg;
    }

    /**
     * Portée d'attaque LoL (unités, bord à bord) convertie en blocs centre à centre :
     * (portée + ~130 unités de rayons de collision) / 100 (LolUnits : 100 unités = 1 bloc).
     */
    protected void setLolAttackRange(double units) {
        this.autoAttackRange = (units + 130) / 100.0;
    }

    protected abstract void registerAbilities();

    // ─── Inventaire ──────────────────────────────────────────────
    // (l'initialisation réelle passe par HotbarManager.initPlayer(), qui gère
    //  le tagging PDC, Flash, items et pagination — applyInventory() était un
    //  vestige d'une version antérieure, jamais appelé, retiré)

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
        ability.consumeCancelled(); ability.consumeDeferred(); // état d'un cast précédent
        ability.cast(caster, stats, target);
        // Sort sans cible : ressource rendue, pas de recharge, pas d'interruption du recall
        if (ability.consumeCancelled()) {
            if (resourceSystem != null && ability.getResourceCost() > 0)
                resourceSystem.addCurrent(ability.getResourceCost());
            ability.consumeDeferred();
            return;
        }
        fr.lolmc.instance.InstanceHelper.baseManager(caster).onAction(caster); // un sort interrompt le recall
        boolean deferred = ability.consumeDeferred();

// Cas particulier : Zed W gère son cooldown lui-même.
// Cela permet le second lancer pour échanger avec l'ombre.
        boolean manualCooldown =
                this instanceof fr.lolmc.champion.impl.mid.Zed
                        && slot == 2
                        && (fr.lolmc.champion.impl.mid.Zed.getShadowLocation(caster.getUniqueId()) != null
                        || fr.lolmc.champion.impl.mid.Zed.isWOnCooldown(caster.getUniqueId()));

        if (!manualCooldown && !deferred) {
            ability.triggerCooldown(caster, stats);
        }

// Déclencher passifs post-sort (Spellblade, Shojin, etc.)
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onAbilityCast(caster, slot);
        // Refresh tooltip après le cast
        refreshSlot(caster, slot);
    }

    // ─── Affichage portée (le rendu réel se fait dans AbilityListener) ──

    /** Sort actuellement tenu par ce joueur, ou null si le slot n'a pas de sort. */
    public fr.lolmc.ability.base.BaseAbility getHeldAbility(Player player) {
        int slot = player.getInventory().getHeldItemSlot();
        if (slot < 1 || slot > 4) return null; // slot 0 = AA, pas d'affichage
        return abilities[slot];
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
            onAbilityRanksChanged();
            return true;
        }
        return false;
    }

    /**
     * Appelé quand les rangs des sorts changent (montée, restauration, commande admin) : les
     * champions à effet passif de rang (ex. pénétration du E de Darius) le recalculent ici.
     */
    public void onAbilityRanksChanged() { }

    /**
     * Dégâts bruts d'une attaque de base (avant résistances). Par défaut, un critique multiplie
     * par les dégâts critiques ; Ashe redéfinit ce calcul (ses critiques ne font pas de dégâts bonus).
     */
    public double basicAttackDamage(Player attacker, org.bukkit.entity.LivingEntity target, double ad, boolean crit) {
        return crit ? ad * stats.getFinalCritDamage() : ad;
    }

    /** Le bouclier anti-sort du champion vient de bloquer un sort (Bouclier maléfique de Sivir). */
    public void onSpellShieldBlock(Player self) { }

    /** Le champion subit une attaque de base d'un champion ennemi (ex. Caprice d'Amumu). */
    public void onBasicAttacked(Player self, Player attacker) { }

    public BaseAbility[]     getAbilities()   { return abilities; }
    public BaseAbility       getAbility(int i){ return (i>=0&&i<5)?abilities[i]:null; }

    public HPSystem getHPSystem()           { return hpSystem; }
    public ResourceSystem getResourceSystem() { return resourceSystem; }

    protected void setAbility(int slot, BaseAbility a) {
        if (slot >= 0 && slot < 5) abilities[slot] = a;
    }
}