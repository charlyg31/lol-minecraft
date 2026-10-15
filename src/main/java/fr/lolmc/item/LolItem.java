package fr.lolmc.item;

import fr.lolmc.stats.ChampionStats;
import fr.lolmc.stats.HPSystem;
import fr.lolmc.stats.ResourceSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente un item LoL achetable en boutique.
 * Chaque item a des stats bonifiées et optionnellement un passif/actif.
 */
public class LolItem {
    /** Largeur (en caractères) du retour à la ligne des descriptions dans le lore. */
    private static final int LORE_WRAP_WIDTH = 38;

    // ── Identité ──
    private final String id;
    private final String displayName;
    private final int goldCost;
    private final Material icon;
    private final ItemCategory category;

    // ── Rang LoL (règles de la boutique : une paire de bottes, un objet de jungle, etc.) ──
    public enum Tier { STARTER, CONSUMABLE, TRINKET, BOOTS, JUNGLE, SUPPORT_QUEST, BASIC, EPIC, LEGENDARY }
    private Tier tier = Tier.BASIC;
    private int sellValue = -1;          // prix de revente officiel (-1 : 70 % du prix)
    private boolean purchasable = true;  // false : obtenu par transformation (Muramana, Fimbulvetr…)

    // ── Recette (arborescence) : IDs des composants qui construisent cet item ──
    private final java.util.List<String> buildsFrom = new java.util.ArrayList<>();

    // ── Catégories ──
    public enum ItemCategory {
        DAMAGE,       // AD / Crit / Lethality
        MAGE,         // AP / Magic pen / AH
        TANK,         // HP / Armor / MR
        ATTACK_SPEED, // AS / On-hit
        SUPPORT,      // Heal power / Aura
        UTILITY,      // MS / Mana / Spécial
        CONSUMABLE    // Potions, wards, élixirs
    }

    // ── Stats bonus ──
    private double bonusAD;
    private double bonusAP;
    private double bonusHP;
    private double bonusArmor;
    private double bonusMR;
    private double bonusAttackSpeed;
    private double bonusCritChance;
    private double bonusCritDamage;
    private double bonusMoveSpeed;
    private double bonusLethality;        // pénétration armure plate
    private double bonusArmorPenPercent;  // pénétration armure %
    private double bonusMagicPen;         // pénétration magique % (compat)
    private double bonusFlatMagicPen;     // pénétration magique plate
    private double bonusFlatDmgReduction; // Doran's Shield
    private double bonusAAReduction;      // Plated Steelcaps
    private double bonusTenacity;         // Mercury's Treads
    private double bonusSlowResist;       // Boots of Swiftness
    private double bonusLifeSteal;
    private double bonusOmnivamp;
    private double bonusAbilityHaste;
    private double bonusMana;
    private double bonusHPRegen;
    private double bonusManaRegen;
    private double hpRegenPct;      // % de la régénération de PV de base (LoL)
    private double manaRegenPct;    // % de la régénération de mana de base (LoL)
    private double moveSpeedPct;    // % de vitesse de déplacement
    private double healShieldPower; // puissance des soins et boucliers
    private double goldPer10;       // or toutes les 10 s (quête de support)

    // ── Passif (description) ──
    private String passiveName;
    private String passiveDescription;
    private boolean hasActive = false; // true si l'item a un actif cliquable
    // Actif séparé du passif : avant, active() écrivait dans passiveName (affiché « Passif — … »)
    // et un .passive() chaîné après l'écrasait.
    private String activeName;
    private String activeDescription;

    public LolItem(String id, String displayName, int goldCost,
                   Material icon, ItemCategory category) {
        this.id = id;
        this.displayName = displayName;
        this.goldCost = goldCost;
        this.icon = icon;
        this.category = category;
    }

    // ── Application des stats au champion ────────────────────────

    /**
     * Applique les stats de l'item à un champion.
     * Appelé à l'achat ou au rechargement des items.
     */
    public void applyStats(ChampionStats stats, HPSystem hp, ResourceSystem res) {
        stats.addBonusAD(bonusAD);
        stats.addBonusAP(bonusAP);
        stats.addBonusArmor(bonusArmor);
        stats.addBonusMR(bonusMR);
        stats.addBonusAttackSpeed(bonusAttackSpeed);
        stats.addBonusCritChance(bonusCritChance);
        stats.addBonusCritDamage(bonusCritDamage);
        stats.addBonusMoveSpeed(bonusMoveSpeed);
        stats.addBonusLethality(bonusLethality);
        stats.addBonusArmorPenPercent(bonusArmorPenPercent);
        stats.addBonusMagicPen(bonusMagicPen);
        stats.addBonusFlatMagicPen(bonusFlatMagicPen);
        stats.addFlatDamageReduction(bonusFlatDmgReduction);
        stats.addAAPercentReduction(bonusAAReduction);
        stats.addTenacity(bonusTenacity);
        stats.addSlowResist(bonusSlowResist);
        stats.addBonusLifeSteal(bonusLifeSteal);
        stats.addBonusOmnivamp(bonusOmnivamp);
        stats.addBonusAbilityHaste(bonusAbilityHaste);

        // HP
        if (bonusHP > 0) hp.addBonusHP(bonusHP);

        // Regen HP
        if (bonusHPRegen > 0) hp.setHpRegen(hp.getHpRegenPer5s() + bonusHPRegen);

        // Mana: augmenter le MAX et le current
        if (bonusMana > 0 && res.getType() == ResourceSystem.ResourceType.MANA) {
            res.addMaxResource(bonusMana);
        }
        // Regen mana
        if (bonusManaRegen > 0 && res.getType() == ResourceSystem.ResourceType.MANA) {
            res.addRegen(bonusManaRegen);
        }
        // Régénérations en % de la base, vitesse en %, puissance des soins (stats des objets LoL)
        if (hpRegenPct > 0) hp.setHpRegen(hp.getHpRegenPer5s() + hpRegenPct * hp.getBaseHpRegenPer5s());
        if (manaRegenPct > 0 && res.getType() == ResourceSystem.ResourceType.MANA)
            res.addRegen(manaRegenPct * res.getBaseRegenPer5s());
        if (moveSpeedPct > 0) stats.multiplyMS(1 + moveSpeedPct);
        if (healShieldPower > 0) stats.addHealShieldPower(healShieldPower);

        // Coiffe de Rabadon : +30 % de puissance totale (Opus magique)
        if ("rabadons_deathcap".equals(id)) stats.multiplyAP(RABADON);
    }

    private static final double RABADON = 1.30;

    /**
     * Retire les stats (vente de l'item).
     */
    public void removeStats(ChampionStats stats, HPSystem hp, ResourceSystem res) {
        stats.addBonusAD(-bonusAD);
        stats.addBonusAP(-bonusAP);
        stats.addBonusArmor(-bonusArmor);
        stats.addBonusMR(-bonusMR);
        stats.addBonusAttackSpeed(-bonusAttackSpeed);
        stats.addBonusCritChance(-bonusCritChance);
        stats.addBonusCritDamage(-bonusCritDamage);
        stats.addBonusMoveSpeed(-bonusMoveSpeed);
        stats.addBonusLethality(-bonusLethality);
        stats.addBonusArmorPenPercent(-bonusArmorPenPercent);
        stats.addBonusMagicPen(-bonusMagicPen);
        stats.addBonusFlatMagicPen(-bonusFlatMagicPen);
        stats.addFlatDamageReduction(-bonusFlatDmgReduction);
        stats.addAAPercentReduction(-bonusAAReduction);
        stats.addTenacity(-bonusTenacity);
        stats.addSlowResist(-bonusSlowResist);
        stats.addBonusLifeSteal(-bonusLifeSteal);
        stats.addBonusOmnivamp(-bonusOmnivamp);
        stats.addBonusAbilityHaste(-bonusAbilityHaste);

        if (bonusHP > 0) hp.addBonusHP(-bonusHP);
        if (bonusHPRegen > 0) hp.setHpRegen(Math.max(0, hp.getHpRegenPer5s() - bonusHPRegen));
        if (bonusMana > 0 && res.getType() == ResourceSystem.ResourceType.MANA)
            res.addMaxResource(-bonusMana);
        if (bonusManaRegen > 0 && res.getType() == ResourceSystem.ResourceType.MANA)
            res.addRegen(-bonusManaRegen);
        if (hpRegenPct > 0) hp.setHpRegen(Math.max(0, hp.getHpRegenPer5s() - hpRegenPct * hp.getBaseHpRegenPer5s()));
        if (manaRegenPct > 0 && res.getType() == ResourceSystem.ResourceType.MANA)
            res.addRegen(-manaRegenPct * res.getBaseRegenPer5s());
        if (moveSpeedPct > 0) stats.multiplyMS(1.0 / (1 + moveSpeedPct));
        if (healShieldPower > 0) stats.addHealShieldPower(-healShieldPower);
        // Avant : seule la Coiffe était annulée ; la Lame d'infini, le Bâton du vide ou le Dernier
        // souffle revendus laissaient leur bonus « de passif » pour toute la partie
        if ("rabadons_deathcap".equals(id)) stats.multiplyAP(1.0 / RABADON);
    }

    // ── ItemStack Minecraft ───────────────────────────────────────

    public ItemStack buildItemStack() {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        NamedTextColor catColor = switch (category) {
            case DAMAGE       -> NamedTextColor.RED;
            case MAGE         -> NamedTextColor.BLUE;
            case TANK         -> NamedTextColor.GREEN;
            case ATTACK_SPEED -> NamedTextColor.YELLOW;
            case SUPPORT      -> NamedTextColor.AQUA;
            case UTILITY      -> NamedTextColor.LIGHT_PURPLE;
            case CONSUMABLE   -> NamedTextColor.WHITE;
        };

        meta.displayName(Component.text(displayName, catColor)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));

        List<Component> lore = new ArrayList<>();
        lore.add(sep());

        // Stats
        if (bonusAD > 0)           lore.add(stat("⚔ Dégâts d'attaque", "+" + (int)bonusAD, NamedTextColor.RED));
        if (bonusAP > 0)           lore.add(stat("✨ Puissance magique",  "+" + (int)bonusAP, NamedTextColor.BLUE));
        if (bonusHP > 0)           lore.add(stat("❤ Points de vie",       "+" + (int)bonusHP, NamedTextColor.GREEN));
        if (bonusArmor > 0)        lore.add(stat("🛡 Armure",              "+" + (int)bonusArmor, NamedTextColor.YELLOW));
        if (bonusMR > 0)           lore.add(stat("🔮 Résistance magique",  "+" + (int)bonusMR, NamedTextColor.LIGHT_PURPLE));
        if (bonusAttackSpeed > 0)  lore.add(stat("⚡ Vitesse d'attaque",   "+" + pct(bonusAttackSpeed), NamedTextColor.YELLOW));
        if (bonusCritChance > 0)   lore.add(stat("🎯 Chance critique",     "+" + pct(bonusCritChance), NamedTextColor.RED));
        if (bonusCritDamage > 0)   lore.add(stat("💥 Dégâts critiques",    "+" + pct(bonusCritDamage), NamedTextColor.RED));
        if (bonusLethality > 0)    lore.add(stat("🗡 Létalité",            "+" + (int)bonusLethality, NamedTextColor.RED));
        if (bonusMagicPen > 0)     lore.add(stat("🌀 Pénétration magique %", "+" + pct(bonusMagicPen), NamedTextColor.BLUE));
        if (bonusArmorPenPercent > 0) lore.add(stat("🗡 Pénétration armure %", "+" + pct(bonusArmorPenPercent), NamedTextColor.RED));
        if (bonusFlatMagicPen > 0) lore.add(stat("🌀 Pénétration magique", "+" + (int)bonusFlatMagicPen, NamedTextColor.BLUE));
        if (bonusTenacity > 0)     lore.add(stat("🏃 Ténacité", "+" + pct(bonusTenacity), NamedTextColor.GREEN));
        if (bonusSlowResist > 0)   lore.add(stat("🏃 Résist. ralentissement", "+" + pct(bonusSlowResist), NamedTextColor.GREEN));
        if (bonusFlatDmgReduction > 0) lore.add(stat("🛡 Réduction dégâts", "-" + (int)bonusFlatDmgReduction, NamedTextColor.GREEN));
        if (bonusLifeSteal > 0)    lore.add(stat("🩸 Vol de vie",          "+" + pct(bonusLifeSteal), NamedTextColor.RED));
        if (bonusOmnivamp > 0)     lore.add(stat("💚 Omnivamp",            "+" + pct(bonusOmnivamp), NamedTextColor.GREEN));
        if (bonusAbilityHaste > 0) lore.add(stat("⏱ Hâte de compétence",  "+" + (int)bonusAbilityHaste, NamedTextColor.AQUA));
        if (bonusMoveSpeed > 0)    lore.add(stat("👟 Vitesse déplacement", "+" + (int)bonusMoveSpeed, NamedTextColor.GREEN));
        if (moveSpeedPct > 0)      lore.add(stat("👟 Vitesse déplacement", "+" + pct(moveSpeedPct), NamedTextColor.GREEN));
        if (bonusMana > 0)         lore.add(stat("💧 Mana",                "+" + (int)bonusMana, NamedTextColor.BLUE));
        if (bonusHPRegen > 0)      lore.add(stat("💚 Regen HP/5s",         "+" + (int)bonusHPRegen, NamedTextColor.GREEN));
        if (hpRegenPct > 0)        lore.add(stat("💚 Régén. PV de base",   "+" + pct(hpRegenPct), NamedTextColor.GREEN));
        if (manaRegenPct > 0)      lore.add(stat("💧 Régén. mana de base", "+" + pct(manaRegenPct), NamedTextColor.BLUE));
        if (healShieldPower > 0)   lore.add(stat("✚ Soins et boucliers",  "+" + pct(healShieldPower), NamedTextColor.AQUA));
        if (goldPer10 > 0)         lore.add(stat("💰 Or / 10 s",           "+" + (int)goldPer10, NamedTextColor.GOLD));

        // Passif
        if (passiveName != null) {
            lore.add(sep());
            lore.add(Component.text("Passif — " + passiveName + ":", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            for (String line : fr.lolmc.util.TextWrapUtil.wrap(passiveDescription, LORE_WRAP_WIDTH)) {
                lore.add(Component.text("  " + line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        // Actif (clic droit sur l'objet dans la barre d'action pendant la partie)
        if (hasActive && activeName != null) {
            lore.add(sep());
            lore.add(Component.text("⚡ Actif — " + activeName + ":", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            for (String line : fr.lolmc.util.TextWrapUtil.wrap(activeDescription, LORE_WRAP_WIDTH)) {
                lore.add(Component.text("  " + line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.text("  ➤ Clic droit dans la barre d'action pour l'utiliser", NamedTextColor.DARK_AQUA)
                    .decoration(TextDecoration.ITALIC, false));
        }

        lore.add(sep());
        lore.add(Component.text(purchasable ? "💰 Coût: " + goldCost + " or" : "✦ Obtenu par transformation", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Revente : " + getSellValue() + " or", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ── Helpers ───────────────────────────────────────────────────

    private Component sep() {
        return Component.text("─────────────────────", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component stat(String key, String val, NamedTextColor color) {
        return Component.text(key + ": ", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(val, color).decoration(TextDecoration.ITALIC, false));
    }

    private String pct(double v) { return Math.round(v * 1000) / 10.0 % 1 == 0 ? (int)Math.round(v * 100) + "%" : Math.round(v * 1000) / 10.0 + "%"; }
    // ── Builder fluent ────────────────────────────────────────────

    public LolItem ad(double v)           { bonusAD = v;           return this; }
    public LolItem ap(double v)           { bonusAP = v;           return this; }
    public LolItem hp(double v)           { bonusHP = v;           return this; }
    public LolItem armor(double v)        { bonusArmor = v;        return this; }
    public LolItem mr(double v)           { bonusMR = v;           return this; }
    public LolItem as(double v)           { bonusAttackSpeed = v;  return this; }
    public LolItem crit(double v)         { bonusCritChance = v;   return this; }
    public LolItem critDmg(double v)      { bonusCritDamage = v;   return this; }
    public LolItem ms(double v)           { bonusMoveSpeed = v;    return this; }
    public LolItem lethality(double v)    { bonusLethality = v;    return this; }
    public LolItem magicPen(double v)     { bonusMagicPen = v;     return this; }
    public LolItem armorPenPct(double v)  { bonusArmorPenPercent = v; return this; }
    public LolItem flatMagicPen(double v) { bonusFlatMagicPen = v;  return this; }
    public LolItem dmgReduction(double v) { bonusFlatDmgReduction = v; return this; }
    public LolItem aaReduction(double v)  { bonusAAReduction = v;   return this; }
    public LolItem slowResist(double v)   { bonusSlowResist = v;    return this; }
    public LolItem tenacity(double v)     { bonusTenacity = v;      return this; }
    public LolItem lifeSteal(double v)    { bonusLifeSteal = v;    return this; }
    public LolItem omnivamp(double v)     { bonusOmnivamp = v;     return this; }
    public LolItem ah(double v)           { bonusAbilityHaste = v; return this; }
    public LolItem mana(double v)         { bonusMana = v;         return this; }
    public LolItem hpRegen(double v)      { bonusHPRegen = v;      return this; }
    public LolItem manaRegen(double v)    { bonusManaRegen = v;    return this; }
    public LolItem hpRegenPct(double v)   { hpRegenPct = v;        return this; }
    public LolItem manaRegenPct(double v) { manaRegenPct = v;      return this; }
    public LolItem msPct(double v)        { moveSpeedPct = v;      return this; }
    public LolItem healShieldPower(double v) { healShieldPower = v; return this; }
    public LolItem goldPer10(double v)    { goldPer10 = v;         return this; }
    public LolItem tier(Tier t)           { tier = t;              return this; }
    public LolItem sell(int v)            { sellValue = v;         return this; }
    public LolItem unpurchasable()        { purchasable = false;   return this; }
    public LolItem passive(String name, String desc) {
        this.passiveName = name; this.passiveDescription = desc; return this;
    }
    /** Marque l'item comme ayant un actif cliquable (apparaît dans la hotbar). */
    public LolItem active(String name, String desc) {
        this.activeName = name; this.activeDescription = desc; this.hasActive = true; return this;
    }

    /** Définit la recette : les IDs des composants qui construisent cet item. */
    public LolItem recipe(String... componentIds) {
        java.util.Collections.addAll(buildsFrom, componentIds);
        return this;
    }
    public java.util.List<String> getBuildsFrom() { return buildsFrom; }

    /** Ajoute à {@code into} les stats différentes apportées par l'objet (rune Polyvalence). */
    public void collectStatKinds(java.util.Set<String> into) {
        if (bonusAD > 0) into.add("ad");
        if (bonusAP > 0) into.add("ap");
        if (bonusHP > 0) into.add("hp");
        if (bonusArmor > 0) into.add("armor");
        if (bonusMR > 0) into.add("mr");
        if (bonusAttackSpeed > 0) into.add("as");
        if (bonusCritChance > 0) into.add("crit");
        if (bonusMoveSpeed > 0 || moveSpeedPct > 0) into.add("ms");
        if (bonusLethality > 0) into.add("lethality");
        if (bonusArmorPenPercent > 0) into.add("armor_pen");
        if (bonusMagicPen > 0 || bonusFlatMagicPen > 0) into.add("magic_pen");
        if (bonusTenacity > 0) into.add("tenacity");
        if (bonusLifeSteal > 0) into.add("life_steal");
        if (bonusOmnivamp > 0) into.add("omnivamp");
        if (bonusAbilityHaste > 0) into.add("ah");
        if (bonusMana > 0) into.add("mana");
        if (bonusHPRegen > 0 || hpRegenPct > 0) into.add("hp_regen");
        if (bonusManaRegen > 0 || manaRegenPct > 0) into.add("mana_regen");
        if (healShieldPower > 0) into.add("heal_shield");
    }
    public boolean hasActive() { return hasActive; }

    // ── Getters ───────────────────────────────────────────────────

    public String getId()            { return id; }
    public String getDisplayName()   { return displayName; }
    public int getGoldCost()         { return goldCost; }
    public Material getIcon()        { return icon; }
    public ItemCategory getCategory(){ return category; }
    public double getBonusAD()       { return bonusAD; }
    public double getBonusAP()       { return bonusAP; }
    public double getBonusHP()       { return bonusHP; }
    public double getBonusMana()     { return bonusMana; }
    public double getManaRegenPct()  { return manaRegenPct; }
    public Tier getTier()            { return tier; }
    public boolean isPurchasable()   { return purchasable; }
    public double getGoldPer10()     { return goldPer10; }
    /** Prix de revente officiel (LoL : 70 % en général, 40 % pour les objets de départ). */
    public int getSellValue()        { return sellValue >= 0 ? sellValue : (int) (goldCost * 0.70); }
}
