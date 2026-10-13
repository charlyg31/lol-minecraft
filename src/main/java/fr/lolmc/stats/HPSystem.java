package fr.lolmc.stats;

/**
 * Gère les HP d'un champion selon les règles LoL.
 *
 * Mapping HP LoL → Minecraft :
 *   - La santé Minecraft reste toujours entre 0 et 40 (20 coeurs)
 *   - mc_health = (lol_hp / lol_max_hp) * 40
 *   - L'ActionBar affiche les vraies valeurs LoL
 *
 * Regen LoL :
 *   - hpRegen = HP régénérés par 5 secondes
 *   - Hors combat : regen pleine
 *   - En combat (dégât reçu dans les 5 dernières secondes) : regen à 0 pour la plupart
 *   - Certains champions ont regen en combat (Nasus R, Warwick passif)
 */
public class HPSystem {

    private double currentHP;
    // PV max hors stats : valeur de départ, puis bonus d'objets (addBonusHP). Une fois lié aux
    // ChampionStats, le max réel = stats.getFinalMaxHP() (base LoL + croissance par niveau + bonus
    // de sorts) + bonus d'objets. Avant, la croissance par niveau et les bonus de PV des sorts
    // (Nasus R, âmes, Feats…) n'atteignaient jamais les vrais PV.
    private double maxHP;
    private double itemBonusHP = 0;
    private double lastMax = -1;    // pour suivre les hausses de PV max (les PV actuels montent aussi)
    private double hpRegenPer5s;    // regen LoL par 5s
    private long lastDamageTime;    // timestamp dernier dégât reçu

    // Délai avant reprise de la regen (5s dans LoL)
    private static final long COMBAT_COOLDOWN_MS = 5000L;

    private fr.lolmc.stats.ChampionStats stats; // référence pour GW

    public HPSystem(double maxHP, double hpRegenPer5s) {
        this.maxHP = maxHP;
        this.currentHP = maxHP; // démarrer full HP
        this.hpRegenPer5s = hpRegenPer5s;
        this.lastDamageTime = 0;
    }

    // ── Dégâts & soins ───────────────────────────────────────────

    private double totalHealed = 0;

    public void takeDamage(double amount) {
        currentHP = Math.max(0, currentHP - amount);
        lastDamageTime = System.currentTimeMillis();
    }

    public void heal(double amount) {
        double mult = (stats != null) ? stats.getHealMultiplier() : 1.0;
        double before = currentHP;
        currentHP = Math.min(getMaxHP(), currentHP + amount * mult);
        totalHealed += currentHP - before; // soins réellement appliqués (hors regen naturelle)
    }

    /** Total des soins réellement appliqués via heal() depuis le début de la partie (statistique "soins"). */
    public double getTotalHealed() { return totalHealed; }
    /** Lie HPSystem aux ChampionStats pour l'antiheal. */
    public void linkStats(fr.lolmc.stats.ChampionStats s) {
        this.stats = s;
        this.lastMax = -1;
        this.currentHP = getMaxHP(); // départ PV pleins (valeurs LoL des stats)
    }

    public boolean isDead() { return currentHP <= 0; }

    // ── Regen ────────────────────────────────────────────────────

    /**
     * Appelé toutes les 5 secondes (100 ticks).
     * Applique la regen LoL si hors combat.
     */
    public void tickRegen() {
        if (!isInCombat()) {
            // Regen de base (+ objets) + croissance par niveau (avant : jamais appliquée)
            double regen = hpRegenPer5s + (stats != null ? stats.getHPRegenGrowth() : 0);
            currentHP = Math.min(getMaxHP(), currentHP + regen);
        }
    }

    /**
     * Regen forcée même en combat (Warwick passif, Nasus R).
     */
    public void tickRegenForced(double amount) {
        currentHP = Math.min(getMaxHP(), currentHP + amount);
    }

    public boolean isInCombat() {
        return (System.currentTimeMillis() - lastDamageTime) < COMBAT_COOLDOWN_MS;
    }

    public long msSinceLastDamage() {
        return lastDamageTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastDamageTime;
    }

    // ── Mapping Minecraft ─────────────────────────────────────────

    /**
     * Convertit les HP LoL en HP Minecraft (0..40).
     */
    public double toMinecraftHealth() {
        double max = getMaxHP();
        if (max <= 0) return 0;
        return Math.max(0.1, (currentHP / max) * 40.0);
    }

    /**
     * Synchro depuis Minecraft vers LoL (ex: dégât vanilla).
     */
    public void syncFromMinecraft(double mcHealth, double mcMaxHealth) {
        if (mcMaxHealth <= 0) return;
        currentHP = (mcHealth / mcMaxHealth) * getMaxHP();
    }

    // ── Stats ─────────────────────────────────────────────────────

    public double getCurrentHP()       { return currentHP; }
    /**
     * PV max réels. Quand ils augmentent (niveau, objet, sort), les PV actuels augmentent d'autant
     * (comme dans LoL) ; quand ils baissent, les PV actuels sont plafonnés.
     */
    public double getMaxHP() {
        double max = (stats != null ? stats.getFinalMaxHP() : maxHP) + itemBonusHP;
        if (lastMax >= 0 && max != lastMax) {
            if (max > lastMax && currentHP > 0) currentHP += max - lastMax;
            currentHP = Math.min(currentHP, max);
        }
        lastMax = max;
        return max;
    }
    public double getHpRegenPer5s()    { return hpRegenPer5s; }
    public void setMaxHP(double v)     {
        itemBonusHP = v - (stats != null ? stats.getFinalMaxHP() : maxHP);
        currentHP = Math.min(currentHP, getMaxHP());
    }
    public void setHpRegen(double v)   { hpRegenPer5s = v; }
    public void addBonusHP(double v)   { itemBonusHP += v; getMaxHP(); }
    public void setCurrentHP(double v) { currentHP = Math.max(0, Math.min(getMaxHP(), v)); }

    public float getHPRatio() {
        return (float) Math.max(0, Math.min(1, currentHP / getMaxHP()));
    }
}
