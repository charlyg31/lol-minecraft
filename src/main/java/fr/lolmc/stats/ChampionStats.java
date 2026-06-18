package fr.lolmc.stats;

/**
 * Stats d'un champion — toutes les valeurs passent par ici.
 * Les objets ajoutent leurs bonus via addBonus().
 * Les sorts utilisent getFinal*() pour leurs calculs.
 */
public class ChampionStats {

    // ── Stats de base (définies par le champion, niveau 1) ──
    private double baseMaxHP;
    private double baseAttackDamage;   // AD
    private double baseAbilityPower;   // AP
    private double baseArmor;
    private double baseMagicResist;
    private double baseAttackSpeed;    // attaques par seconde
    private double baseCritChance;     // 0.0 à 1.0
    private double baseCritDamage;     // multiplicateur (défaut 1.75)
    private double baseMovementSpeed;
    private double baseRange;          // portée attaque de base en blocs
    private double baseHPRegen;        // HP régénérés par seconde
    private double baseLethality;      // pénétration armure fixe
    private double baseMagicPen;       // pénétration magie fixe (0.0..1.0)
    private double baseLifeSteal;      // 0.0 à 1.0
    private double baseOmnivamp;       // 0.0 à 1.0
    private double baseAbilityHaste;   // réduit les cooldowns

    // ── Bonus ajoutés par les objets/runes ──
    private double bonusMaxHP;
    private double bonusAttackDamage;
    private double bonusAbilityPower;
    private double bonusArmor;
    private double bonusMagicResist;
    private double bonusAttackSpeed;
    private double bonusCritChance;
    private double bonusCritDamage;
    private double bonusMovementSpeed;
    private double bonusRange;
    private double bonusHPRegen;
    private double bonusLethality;
    private double bonusMagicPen;
    private double bonusLifeSteal;
    private double bonusOmnivamp;
    private double bonusAbilityHaste;

    // ── Multiplicateurs (ex: +15% AD) ──
    private double multAttackDamage = 1.0;
    private double multAbilityPower = 1.0;
    private double multMovementSpeed = 1.0;
    private double multAttackSpeed   = 1.0;
    private double multMaxHP         = 1.0;

    // ── HP actuel ──
    private double currentHP;

    public ChampionStats(double maxHP, double ad, double ap,
                         double armor, double mr, double attackSpeed,
                         double crit, double moveSpeed, double range,
                         double hpRegen) {
        this.baseMaxHP        = maxHP;
        this.baseAttackDamage = ad;
        this.baseAbilityPower = ap;
        this.baseArmor        = armor;
        this.baseMagicResist  = mr;
        this.baseAttackSpeed  = attackSpeed;
        this.baseCritChance   = crit;
        this.baseCritDamage   = 1.75;
        this.baseMovementSpeed = moveSpeed;
        this.baseRange        = range;
        this.baseHPRegen      = hpRegen;
        this.baseLethality    = 0;
        this.baseMagicPen     = 0;
        this.baseLifeSteal    = 0;
        this.baseOmnivamp     = 0;
        this.baseAbilityHaste = 0;
        this.currentHP        = getFinalMaxHP();
    }

    // ══════════════════════════════════════════════
    // GETTERS FINAUX (base + bonus + multiplicateurs)
    // ══════════════════════════════════════════════

    public double getFinalMaxHP() {
        return (baseMaxHP + bonusMaxHP) * multMaxHP;
    }

    public double getFinalAD() {
        return (baseAttackDamage + bonusAttackDamage) * multAttackDamage;
    }

    public double getFinalAP() {
        return (baseAbilityPower + bonusAbilityPower) * multAbilityPower;
    }

    public double getFinalArmor() {
        return baseArmor + bonusArmor;
    }

    public double getFinalMagicResist() {
        return baseMagicResist + bonusMagicResist;
    }

    public double getFinalAttackSpeed() {
        return Math.min((baseAttackSpeed + bonusAttackSpeed) * multAttackSpeed, 2.5);
    }

    public double getFinalCritChance() {
        return Math.min(baseCritChance + bonusCritChance, 1.0);
    }

    public double getFinalCritDamage() {
        return baseCritDamage + bonusCritDamage;
    }

    public double getFinalMovementSpeed() {
        return (baseMovementSpeed + bonusMovementSpeed) * multMovementSpeed;
    }

    public double getFinalRange() {
        return baseRange + bonusRange;
    }

    public double getFinalHPRegen() {
        return baseHPRegen + bonusHPRegen;
    }

    public double getFinalLethality() {
        return baseLethality + bonusLethality;
    }

    public double getFinalMagicPen() {
        return Math.min(baseMagicPen + bonusMagicPen, 0.45);
    }

    public double getFinalLifeSteal() {
        return Math.min(baseLifeSteal + bonusLifeSteal, 1.0);
    }

    public double getFinalOmnivamp() {
        return Math.min(baseOmnivamp + bonusOmnivamp, 1.0);
    }

    public double getFinalAbilityHaste() {
        return baseAbilityHaste + bonusAbilityHaste;
    }

    // ══════════════════════════════════════════════
    // CALCULS DE DÉGÂTS (utilisés par sorts + AA)
    // ══════════════════════════════════════════════

    /**
     * Calcule les dégâts physiques après réduction d'armure.
     * formule LoL : dmg * 100 / (100 + armor_effective)
     */
    public double calcPhysicalDamage(double rawDamage, ChampionStats target) {
        double effectiveArmor = Math.max(0, target.getFinalArmor() - this.getFinalLethality());
        double reduction = 100.0 / (100.0 + effectiveArmor);
        return rawDamage * reduction;
    }

    /**
     * Calcule les dégâts magiques après réduction de MR.
     */
    public double calcMagicalDamage(double rawDamage, ChampionStats target) {
        double effectiveMR = Math.max(0, target.getFinalMagicResist() * (1.0 - this.getFinalMagicPen()));
        double reduction = 100.0 / (100.0 + effectiveMR);
        return rawDamage * reduction;
    }

    /**
     * Calcule les dégâts vrais (ignorent armure/MR).
     */
    public double calcTrueDamage(double rawDamage) {
        return rawDamage;
    }

    /**
     * Calcule les dégâts d'attaque de base avec crit éventuel.
     */
    public double calcAutoAttackDamage(ChampionStats target) {
        double ad = getFinalAD();
        boolean isCrit = Math.random() < getFinalCritChance();
        if (isCrit) ad *= getFinalCritDamage();
        return calcPhysicalDamage(ad, target);
    }

    /**
     * Applique le lifesteal/omnivamp après un dégât.
     */
    public void applyVamp(double damageDealt, boolean isAbility) {
        double heal = 0;
        if (!isAbility) {
            heal = damageDealt * getFinalLifeSteal();
        }
        heal += damageDealt * getFinalOmnivamp();
        currentHP = Math.min(currentHP + heal, getFinalMaxHP());
    }

    /**
     * Convertit l'ability haste en cooldown multiplier.
     * formule LoL : CDmult = 100 / (100 + abilityHaste)
     */
    public double getCooldownMultiplier() {
        return 100.0 / (100.0 + getFinalAbilityHaste());
    }

    // ══════════════════════════════════════════════
    // AJOUT BONUS (appelé par les objets)
    // ══════════════════════════════════════════════

    public void addBonusHP(double v)           { bonusMaxHP += v; }
    public void addBonusAD(double v)           { bonusAttackDamage += v; }
    public void addBonusAP(double v)           { bonusAbilityPower += v; }
    public void addBonusArmor(double v)        { bonusArmor += v; }
    public void addBonusMR(double v)           { bonusMagicResist += v; }
    public void addBonusAttackSpeed(double v)  { bonusAttackSpeed += v; }
    public void addBonusCritChance(double v)   { bonusCritChance += v; }
    public void addBonusCritDamage(double v)   { bonusCritDamage += v; }
    public void addBonusMoveSpeed(double v)    { bonusMovementSpeed += v; }
    public void addBonusRange(double v)        { bonusRange += v; }
    public void addBonusHPRegen(double v)      { bonusHPRegen += v; }
    public void addBonusLethality(double v)    { bonusLethality += v; }
    public void addBonusMagicPen(double v)     { bonusMagicPen += v; }
    public void addBonusLifeSteal(double v)    { bonusLifeSteal += v; }
    public void addBonusOmnivamp(double v)     { bonusOmnivamp += v; }
    public void addBonusAbilityHaste(double v) { bonusAbilityHaste += v; }

    public void multiplyAD(double mult)    { multAttackDamage *= mult; }
    public void multiplyAP(double mult)    { multAbilityPower *= mult; }
    public void multiplyMS(double mult)    { multMovementSpeed *= mult; }
    public void multiplyAS(double mult)    { multAttackSpeed *= mult; }
    public void multiplyHP(double mult)    { multMaxHP *= mult; }

    // ══════════════════════════════════════════════
    // HP
    // ══════════════════════════════════════════════

    public double getCurrentHP()  { return currentHP; }
    public void setCurrentHP(double hp) { currentHP = Math.max(0, Math.min(hp, getFinalMaxHP())); }
    public void takeDamage(double dmg)  { currentHP = Math.max(0, currentHP - dmg); }
    public boolean isDead()             { return currentHP <= 0; }

    // ══════════════════════════════════════════════
    // RESET BONUS (appelé quand un objet est retiré)
    // ══════════════════════════════════════════════

    public void resetBonuses() {
        bonusMaxHP=bonusAttackDamage=bonusAbilityPower=bonusArmor=0;
        bonusMagicResist=bonusAttackSpeed=bonusCritChance=bonusCritDamage=0;
        bonusMovementSpeed=bonusRange=bonusHPRegen=bonusLethality=0;
        bonusMagicPen=bonusLifeSteal=bonusOmnivamp=bonusAbilityHaste=0;
        multAttackDamage=multAbilityPower=multMovementSpeed=multAttackSpeed=multMaxHP=1.0;
    }

    // Getters de base (pour affichage)
    public double getBaseAD()  { return baseAttackDamage; }
    public double getBaseAP()  { return baseAbilityPower; }
    public double getBonusAD() { return bonusAttackDamage; }
    public double getBonusAP() { return bonusAbilityPower; }
}
