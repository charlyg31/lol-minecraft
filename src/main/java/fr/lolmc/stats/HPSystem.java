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
    private double maxHP;           // HP max LoL (inclut bonus objets)
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

    public void takeDamage(double amount) {
        currentHP = Math.max(0, currentHP - amount);
        lastDamageTime = System.currentTimeMillis();
    }

    public void heal(double amount) {
        double mult = (stats != null) ? stats.getHealMultiplier() : 1.0;
        currentHP = Math.min(maxHP, currentHP + amount * mult);
    }
    /** Lie HPSystem aux ChampionStats pour l'antiheal. */
    public void linkStats(fr.lolmc.stats.ChampionStats s) { this.stats = s; }

    public boolean isDead() { return currentHP <= 0; }

    // ── Regen ────────────────────────────────────────────────────

    /**
     * Appelé toutes les 5 secondes (100 ticks).
     * Applique la regen LoL si hors combat.
     */
    public void tickRegen() {
        if (!isInCombat()) {
            currentHP = Math.min(maxHP, currentHP + hpRegenPer5s);
        }
    }

    /**
     * Regen forcée même en combat (Warwick passif, Nasus R).
     */
    public void tickRegenForced(double amount) {
        currentHP = Math.min(maxHP, currentHP + amount);
    }

    public boolean isInCombat() {
        return (System.currentTimeMillis() - lastDamageTime) < COMBAT_COOLDOWN_MS;
    }

    // ── Mapping Minecraft ─────────────────────────────────────────

    /**
     * Convertit les HP LoL en HP Minecraft (0..40).
     */
    public double toMinecraftHealth() {
        if (maxHP <= 0) return 0;
        return Math.max(0.1, (currentHP / maxHP) * 40.0);
    }

    /**
     * Synchro depuis Minecraft vers LoL (ex: dégât vanilla).
     */
    public void syncFromMinecraft(double mcHealth, double mcMaxHealth) {
        if (mcMaxHealth <= 0) return;
        currentHP = (mcHealth / mcMaxHealth) * maxHP;
    }

    // ── Stats ─────────────────────────────────────────────────────

    public double getCurrentHP()       { return currentHP; }
    public double getMaxHP()           { return maxHP; }
    public double getHpRegenPer5s()    { return hpRegenPer5s; }
    public void setMaxHP(double v)     { maxHP = v; currentHP = Math.min(currentHP, v); }
    public void setHpRegen(double v)   { hpRegenPer5s = v; }
    public void addBonusHP(double v)   { maxHP += v; }
    public void setCurrentHP(double v) { currentHP = Math.max(0, Math.min(maxHP, v)); }

    public float getHPRatio() {
        return (float) Math.max(0, Math.min(1, currentHP / maxHP));
    }
}
