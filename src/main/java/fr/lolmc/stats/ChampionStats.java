package fr.lolmc.stats;

/**
 * Stats d'un champion avec le modèle de dégâts LoL officiel.
 *
 * Ordre de calcul de la pénétration (comme dans LoL) :
 *   1. Réduction d'armure plate (Black Cleaver) — déjà appliquée via addBonusArmor négatif
 *   2. Réduction d'armure en % (effets de réduction)
 *   3. Pénétration d'armure en % (Lord Dominik's, Last Whisper)
 *   4. Pénétration plate / Létalité (Youmuu's, etc.)
 * Puis formule de réduction :
 *   - armure >= 0 : dmg * 100 / (100 + armure_effective)
 *   - armure < 0  : dmg * (2 - 100 / (100 - armure_effective))
 */
public class ChampionStats {

    // ── Stats de base (niveau 1) ──
    private double baseMaxHP, baseAttackDamage, baseAbilityPower;
    private double baseArmor, baseMagicResist, baseAttackSpeed;
    private double baseCritChance, baseCritDamage, baseMovementSpeed, baseRange, baseHPRegen;

    // ── Pénétration ──
    private double baseLethality;        // pénétration armure plate
    private double bonusLethality;
    private double bonusArmorPenPercent; // pénétration armure en % (0.0..1.0)
    private double baseFlatMagicPen;     // pénétration magique plate (Sorcerer's Shoes = 18)
    private double bonusFlatMagicPen;
    private double bonusMagicPenPercent; // pénétration magique en % (0.0..1.0)

    // ── Réductions défensives (reçues) ──
    private double flatDamageReduction;     // Doran's Shield -8
    private double percentDamageReduction;  // réduction % générale
    private double aaPercentReduction;      // Plated Steelcaps -12% des AA
    private double tenacity;                // 0.0..1.0 réduction durée CC

    // ── Bonus objets ──
    private double bonusMaxHP, bonusAttackDamage, bonusAbilityPower;
    private double bonusArmor, bonusMagicResist, bonusAttackSpeed;
    private double bonusCritChance, bonusCritDamage, bonusMovementSpeed, bonusRange, bonusHPRegen;
    private double bonusLifeSteal, bonusOmnivamp, bonusAbilityHaste;

    // ── Multiplicateurs ──
    private double multAttackDamage = 1.0, multAbilityPower = 1.0;
    private double multMovementSpeed = 1.0, multAttackSpeed = 1.0, multMaxHP = 1.0;

    // ── Stats de croissance (growth) par niveau (façon LoL) ──
    private double growthHP = 0, growthAD = 0, growthArmor = 0, growthMR = 0;
    private double growthAttackSpeed = 0, growthHPRegen = 0;
    private int championLevel = 1; // niveau actuel (mis à jour par le LevelSystem)

    // ── HP & bouclier ──
    private double currentHP;
    private double shieldAmount = 0;        // bouclier général (absorbe tout)
    private double magicShieldAmount = 0;   // bouclier anti-magie (Maw, Banshee's)

    public ChampionStats(double maxHP, double ad, double ap, double armor, double mr,
                         double attackSpeed, double crit, double moveSpeed, double range, double hpRegen) {
        this.baseMaxHP = maxHP; this.baseAttackDamage = ad; this.baseAbilityPower = ap;
        this.baseArmor = armor; this.baseMagicResist = mr; this.baseAttackSpeed = attackSpeed;
        this.baseCritChance = crit; this.baseCritDamage = 1.75;
        this.baseMovementSpeed = moveSpeed; this.baseRange = range; this.baseHPRegen = hpRegen;
        this.currentHP = getFinalMaxHP();
    }

    /**
     * Définit les stats de croissance par niveau (façon LoL).
     * Ces valeurs sont les coefficients 'g' de la formule officielle.
     */
    public void setGrowthStats(double hp, double ad, double armor, double mr,
                               double attackSpeed, double hpRegen) {
        this.growthHP = hp; this.growthAD = ad; this.growthArmor = armor;
        this.growthMR = mr; this.growthAttackSpeed = attackSpeed; this.growthHPRegen = hpRegen;
    }

    /** Met à jour le niveau du champion (recalcule les stats de croissance). */
    public void setChampionLevel(int level) {
        this.championLevel = Math.max(1, Math.min(18, level));
    }

    public int getChampionLevel() { return championLevel; }

    /**
     * Formule de croissance officielle LoL :
     *   bonus de croissance = g × (n−1) × (0.7025 + 0.0175 × (n−1))
     * où g = stat de croissance, n = niveau.
     */
    private double growthBonus(double g) {
        if (championLevel <= 1 || g == 0) return 0;
        double n = championLevel;
        return g * (n - 1) * (0.7025 + 0.0175 * (n - 1));
    }

    // ══════════════════════════════════════════════
    // GETTERS FINAUX
    // ══════════════════════════════════════════════

    public double getFinalMaxHP()        { return (baseMaxHP + growthBonus(growthHP) + bonusMaxHP) * multMaxHP; }
    public double getFinalAD()           { return (baseAttackDamage + growthBonus(growthAD) + bonusAttackDamage) * multAttackDamage; }
    public double getFinalAP()           { return (baseAbilityPower + bonusAbilityPower) * multAbilityPower; }
    public double getFinalArmor()        { return baseArmor + growthBonus(growthArmor) + bonusArmor; }
    public double getFinalMagicResist()  { return baseMagicResist + growthBonus(growthMR) + bonusMagicResist; }
    public double getFinalAttackSpeed()  { return Math.min((baseAttackSpeed + growthBonus(growthAttackSpeed) + bonusAttackSpeed) * multAttackSpeed, 2.5); }
    public double getFinalCritChance()   { return Math.min(baseCritChance + bonusCritChance, 1.0); }
    public double getFinalCritDamage()   { return baseCritDamage + bonusCritDamage; }
    public double getFinalMovementSpeed(){ return (baseMovementSpeed + bonusMovementSpeed) * multMovementSpeed; }
    public double getFinalRange()        { return baseRange + bonusRange; }
    public double getFinalHPRegen()      { return baseHPRegen + growthBonus(growthHPRegen) + bonusHPRegen; }
    public double getFinalLethality()    { return baseLethality + bonusLethality; }
    public double getFinalLifeSteal()    { return Math.min(bonusLifeSteal, 1.0); }
    public double getFinalOmnivamp()     { return Math.min(bonusOmnivamp, 1.0); }
    public double getFinalAbilityHaste() { return bonusAbilityHaste; }
    public double getFinalTenacity()     { return Math.min(tenacity, 0.95); }

    // Pénétration
    public double getArmorPenPercent()   { return Math.min(bonusArmorPenPercent, 1.0); }
    public double getFlatMagicPen()      { return baseFlatMagicPen + bonusFlatMagicPen; }
    public double getMagicPenPercent()   { return Math.min(bonusMagicPenPercent, 1.0); }
    // Compat : ancienne API getFinalMagicPen() = pénétration magique en %
    public double getFinalMagicPen()     { return getMagicPenPercent(); }

    // ══════════════════════════════════════════════
    // CALCUL DES DÉGÂTS — modèle LoL officiel
    // ══════════════════════════════════════════════

    /**
     * Armure effective après pénétration, dans l'ordre LoL :
     * (armure) → ×(1 - pén%) → - pén_plate, borné à un minimum de 0 côté pénétration
     * (la pénétration ne peut pas rendre l'armure négative ; seules les réductions le peuvent)
     */
    private double effectiveArmor(double targetArmor) {
        double armor = targetArmor;
        if (armor > 0) {
            armor *= (1.0 - getArmorPenPercent());     // pén %
            armor -= getFinalLethality();              // pén plate
            if (armor < 0) armor = 0;                  // la pén seule ne passe pas sous 0
        }
        return armor; // peut être <0 si l'armure de base était négative (réductions)
    }

    private double effectiveMR(double targetMR) {
        double mr = targetMR;
        if (mr > 0) {
            mr *= (1.0 - getMagicPenPercent());
            mr -= getFlatMagicPen();
            if (mr < 0) mr = 0;
        }
        return mr;
    }

    /** Multiplicateur de réduction selon la résistance (gère le négatif). */
    private double resistMultiplier(double resist) {
        if (resist >= 0) return 100.0 / (100.0 + resist);
        return 2.0 - 100.0 / (100.0 - resist); // formule LoL pour résistance négative
    }

    public double calcPhysicalDamage(double rawDamage, ChampionStats target) {
        if (target == null) return rawDamage;
        double armor = effectiveArmor(target.getFinalArmor());
        return rawDamage * resistMultiplier(armor);
    }

    public double calcMagicalDamage(double rawDamage, ChampionStats target) {
        if (target == null) return rawDamage;
        double mr = effectiveMR(target.getFinalMagicResist());
        return rawDamage * resistMultiplier(mr);
    }

    public double calcTrueDamage(double rawDamage) {
        return rawDamage;
    }

    public double calcAutoAttackDamage(ChampionStats target) {
        double ad = getFinalAD();
        if (Math.random() < getFinalCritChance()) ad *= getFinalCritDamage();
        return calcPhysicalDamage(ad, target);
    }

    /**
     * Applique les réductions défensives reçues (plate puis %), côté CIBLE.
     * Appelé par HPSystem/DamageUtil après le calcul de résistance.
     * @param isAutoAttack true pour appliquer aussi aaPercentReduction (Plated Steelcaps)
     */
    public double applyDamageReductions(double incomingDamage, boolean isAutoAttack) {
        double dmg = incomingDamage;
        if (isAutoAttack) dmg *= (1.0 - Math.min(aaPercentReduction, 1.0));
        dmg *= (1.0 - Math.min(percentDamageReduction, 1.0));
        dmg -= flatDamageReduction;
        return Math.max(0, dmg);
    }

    public double getCooldownMultiplier() {
        return 100.0 / (100.0 + getFinalAbilityHaste());
    }

    public void applyVamp(double damageDealt, boolean isAbility) {
        double heal = 0;
        if (!isAbility) heal = damageDealt * getFinalLifeSteal();
        heal += damageDealt * getFinalOmnivamp();
        currentHP = Math.min(currentHP + heal, getFinalMaxHP());
    }

    // ══════════════════════════════════════════════
    // BOUCLIERS (vrai système, se consument)
    // ══════════════════════════════════════════════

    public void addShield(double amount)       { shieldAmount += amount; }
    public void addMagicShield(double amount)   { magicShieldAmount += amount; }
    public double getShield()                   { return shieldAmount + magicShieldAmount; }

    /**
     * Absorbe des dégâts avec les boucliers. Retourne les dégâts restants à infliger aux HP.
     * @param isMagic true si dégâts magiques (le bouclier anti-magie s'applique en priorité)
     */
    public double absorbWithShield(double damage, boolean isMagic) {
        double remaining = damage;
        if (isMagic && magicShieldAmount > 0) {
            double absorbed = Math.min(magicShieldAmount, remaining);
            magicShieldAmount -= absorbed;
            remaining -= absorbed;
        }
        if (remaining > 0 && shieldAmount > 0) {
            double absorbed = Math.min(shieldAmount, remaining);
            shieldAmount -= absorbed;
            remaining -= absorbed;
        }
        return remaining;
    }

    public void clearShields() { shieldAmount = 0; magicShieldAmount = 0; }

    // ══════════════════════════════════════════════
    // AJOUT BONUS (objets)
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
    public void addBonusLifeSteal(double v)    { bonusLifeSteal += v; }
    public void addBonusOmnivamp(double v)     { bonusOmnivamp += v; }
    public void addBonusAbilityHaste(double v) { bonusAbilityHaste += v; }

    // Pénétration — nouvelles méthodes
    public void addBonusArmorPenPercent(double v) { bonusArmorPenPercent += v; }
    public void addBonusFlatMagicPen(double v)    { bonusFlatMagicPen += v; }
    public void addBonusMagicPenPercent(double v) { bonusMagicPenPercent += v; }
    // Compat : ancienne API addBonusMagicPen(v) = pénétration magique en %
    public void addBonusMagicPen(double v)        { bonusMagicPenPercent += v; }

    // Réductions défensives
    public void addFlatDamageReduction(double v)   { flatDamageReduction += v; }
    public void addPercentDamageReduction(double v){ percentDamageReduction += v; }
    public void addAAPercentReduction(double v)    { aaPercentReduction += v; }
    public void addTenacity(double v)              { tenacity += v; }

    public void multiplyAD(double m) { multAttackDamage *= m; }
    public void multiplyAP(double m) { multAbilityPower *= m; }
    public void multiplyMS(double m) { multMovementSpeed *= m; }
    public void multiplyAS(double m) { multAttackSpeed *= m; }
    public void multiplyHP(double m) { multMaxHP *= m; }

    // ══════════════════════════════════════════════
    // HP
    // ══════════════════════════════════════════════

    public double getCurrentHP()        { return currentHP; }
    public void setCurrentHP(double hp)  { currentHP = Math.max(0, Math.min(hp, getFinalMaxHP())); }
    public void takeDamage(double dmg)   { currentHP = Math.max(0, currentHP - dmg); }
    public boolean isDead()              { return currentHP <= 0; }

    public void resetBonuses() {
        bonusMaxHP=bonusAttackDamage=bonusAbilityPower=bonusArmor=0;
        bonusMagicResist=bonusAttackSpeed=bonusCritChance=bonusCritDamage=0;
        bonusMovementSpeed=bonusRange=bonusHPRegen=bonusLethality=0;
        bonusLifeSteal=bonusOmnivamp=bonusAbilityHaste=0;
        bonusArmorPenPercent=bonusFlatMagicPen=bonusMagicPenPercent=0;
        flatDamageReduction=percentDamageReduction=aaPercentReduction=tenacity=0;
        multAttackDamage=multAbilityPower=multMovementSpeed=multAttackSpeed=multMaxHP=1.0;
    }

    // Getters de base (affichage + passifs)
    public double getBaseAD()  { return baseAttackDamage; }
    public double getBaseAP()  { return baseAbilityPower; }
    public double getBonusHP() { return bonusMaxHP; }
    public double getBonusAD() { return bonusAttackDamage; }
    public double getBonusAP() { return bonusAbilityPower; }
}
