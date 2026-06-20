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

    // ── Identité ──
    private final String id;
    private final String displayName;
    private final int goldCost;
    private final Material icon;
    private final ItemCategory category;

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
    private double bonusLifeSteal;
    private double bonusOmnivamp;
    private double bonusAbilityHaste;
    private double bonusMana;
    private double bonusHPRegen;
    private double bonusManaRegen;

    // ── Passif (description) ──
    private String passiveName;
    private String passiveDescription;
    private boolean hasActive = false; // true si l'item a un actif cliquable

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

        // Passifs spéciaux
        applyPassive(stats);
    }

    /**
     * Applique les effets passifs qui modifient les stats de base.
     * Ex: Rabadon's +35% AP, Manamune +2% AD/mana, etc.
     */
    private void applyPassive(ChampionStats stats) {
        if (passiveName == null) return;
        switch (passiveName) {
            case "Amplification" -> stats.multiplyAP(1.35);  // Rabadon's +35% AP
            case "Awe" -> {
                // Archangel's/Manamune: +1% AP ou AD pour chaque 100 mana bonus
                // Appliqué dynamiquement dans HUDManager
            }
        }
    }

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
        stats.addBonusLifeSteal(-bonusLifeSteal);
        stats.addBonusOmnivamp(-bonusOmnivamp);
        stats.addBonusAbilityHaste(-bonusAbilityHaste);

        if (bonusHP > 0) hp.addBonusHP(-bonusHP);
        if (bonusHPRegen > 0) hp.setHpRegen(Math.max(0, hp.getHpRegenPer5s() - bonusHPRegen));
        if (bonusMana > 0 && res.getType() == ResourceSystem.ResourceType.MANA)
            res.addMaxResource(-bonusMana);
        if (bonusManaRegen > 0 && res.getType() == ResourceSystem.ResourceType.MANA)
            res.addRegen(-bonusManaRegen);

        // Retirer les passifs multiplicatifs
        removePassive(stats);
    }

    private void removePassive(ChampionStats stats) {
        if (passiveName == null) return;
        switch (passiveName) {
            case "Amplification" -> stats.multiplyAP(1.0 / 1.35); // Annuler Rabadon's
        }
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
        if (bonusFlatDmgReduction > 0) lore.add(stat("🛡 Réduction dégâts", "-" + (int)bonusFlatDmgReduction, NamedTextColor.GREEN));
        if (bonusLifeSteal > 0)    lore.add(stat("🩸 Vol de vie",          "+" + pct(bonusLifeSteal), NamedTextColor.RED));
        if (bonusOmnivamp > 0)     lore.add(stat("💚 Omnivamp",            "+" + pct(bonusOmnivamp), NamedTextColor.GREEN));
        if (bonusAbilityHaste > 0) lore.add(stat("⏱ Hâte de compétence",  "+" + (int)bonusAbilityHaste, NamedTextColor.AQUA));
        if (bonusMoveSpeed > 0)    lore.add(stat("👟 Vitesse déplacement", "+" + (int)bonusMoveSpeed, NamedTextColor.GREEN));
        if (bonusMana > 0)         lore.add(stat("💧 Mana",                "+" + (int)bonusMana, NamedTextColor.BLUE));
        if (bonusHPRegen > 0)      lore.add(stat("💚 Regen HP/5s",         "+" + (int)bonusHPRegen, NamedTextColor.GREEN));

        // Passif
        if (passiveName != null) {
            lore.add(sep());
            lore.add(Component.text("Passif — " + passiveName + ":", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            for (String line : wrap(passiveDescription, 38)) {
                lore.add(Component.text("  " + line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        lore.add(sep());
        lore.add(Component.text("💰 Coût: " + goldCost + " or", NamedTextColor.GOLD)
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

    private String pct(double v) { return (int)(v * 100) + "%"; }

    private List<String> wrap(String text, int w) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            if (cur.length() + word.length() + 1 > w) { lines.add(cur.toString().trim()); cur = new StringBuilder(); }
            cur.append(word).append(" ");
        }
        if (!cur.isEmpty()) lines.add(cur.toString().trim());
        return lines;
    }

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
    public LolItem tenacity(double v)     { bonusTenacity = v;      return this; }
    public LolItem lifeSteal(double v)    { bonusLifeSteal = v;    return this; }
    public LolItem omnivamp(double v)     { bonusOmnivamp = v;     return this; }
    public LolItem ah(double v)           { bonusAbilityHaste = v; return this; }
    public LolItem mana(double v)         { bonusMana = v;         return this; }
    public LolItem hpRegen(double v)      { bonusHPRegen = v;      return this; }
    public LolItem manaRegen(double v)    { bonusManaRegen = v;    return this; }
    public LolItem passive(String name, String desc) {
        this.passiveName = name; this.passiveDescription = desc; return this;
    }
    /** Marque l'item comme ayant un actif cliquable (apparaît dans la hotbar). */
    public LolItem active(String name, String desc) {
        this.passiveName = name; this.passiveDescription = desc; this.hasActive = true; return this;
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
}
