package fr.lolmc.stats;

/**
 * Système de niveau d'un champion (1 à 18 comme LoL).
 * L'XP vient des last-hits (sbires, monstres) et des kills.
 * Chaque montée de niveau donne 1 point de compétence à dépenser.
 */
public class LevelSystem {

    public static final int MAX_LEVEL = 18;

    private int level = 1;
    private double currentXP = 0;
    private int skillPoints = 1; // niveau 1 = 1 point pour débloquer un premier sort

    // Points déjà investis dans chaque sort (index 1-4 = Q,W,E,R ; 0 = AA non concerné)
    private final int[] abilityRanks = new int[5];

    // XP requise cumulée pour atteindre chaque niveau (formule LoL approximée)
    // Niveau n nécessite : 280 + (n-1)*100 d'XP pour passer au suivant
    private static double xpForLevel(int lvl) {
        return 280 + (lvl - 1) * 100.0;
    }

    // ── XP ────────────────────────────────────────────────────────

    /**
     * Ajoute de l'XP. Retourne le nombre de niveaux gagnés.
     */
    public int addXP(double amount) {
        if (level >= MAX_LEVEL) return 0;
        currentXP += amount;
        int levelsGained = 0;
        while (level < MAX_LEVEL && currentXP >= xpForLevel(level)) {
            currentXP -= xpForLevel(level);
            level++;
            skillPoints++;
            levelsGained++;
        }
        if (level >= MAX_LEVEL) currentXP = 0;
        return levelsGained;
    }

    /**
     * Force le niveau du champion (pour les tests admin).
     * Ajuste les points de compétence disponibles en conséquence.
     */
    public void setLevel(int newLevel) {
        newLevel = Math.max(1, Math.min(MAX_LEVEL, newLevel));
        int diff = newLevel - level;
        level = newLevel;
        currentXP = 0;
        if (diff > 0) skillPoints += diff; // donne les points gagnés
        if (skillPoints < 0) skillPoints = 0;
    }

    /**
     * Débloque/monte automatiquement les sorts en fonction du niveau actuel.
     * Pratique pour les tests : Q/W/E montés autant que possible, R selon niveau (6/11/16).
     * Répartit les rangs disponibles sur Q, W, E puis R.
     */
    public void maxOutAbilities() {
        // Réinitialiser
        for (int i = 0; i < 5; i++) abilityRanks[i] = 0;
        // Rangs max : Q/W/E = 5, R = 3
        // R débloqué aux niveaux 6/11/16
        int[] rMax = {6, 11, 16};
        // Monter Q, W, E au max possible selon le niveau (1 point par niveau)
        int pointsAvailable = level;
        // R d'abord (selon paliers)
        int rRank = 0;
        for (int lvl : rMax) if (level >= lvl) rRank++;
        abilityRanks[4] = rRank;
        pointsAvailable -= rRank;
        // Répartir le reste sur Q(1), W(2), E(3)
        int[] order = {1, 2, 3};
        int idx = 0;
        while (pointsAvailable > 0) {
            int slot = order[idx % 3];
            if (abilityRanks[slot] < 5) {
                abilityRanks[slot]++;
                pointsAvailable--;
            }
            idx++;
            // Sécurité : si Q/W/E sont au max (15 points utilisés), stop
            if (abilityRanks[1] == 5 && abilityRanks[2] == 5 && abilityRanks[3] == 5) break;
        }
        skillPoints = Math.max(0, pointsAvailable);
    }

    // ── Points de compétence ──────────────────────────────────────

    public boolean hasSkillPoint() {
        return skillPoints > 0;
    }

    /**
     * Tente d'améliorer un sort (slot 1-4).
     * Règles LoL : max 5 rangs pour Q/W/E, max 3 pour R (slot 4).
     * R ne peut être pris qu'aux niveaux 6, 11, 16.
     * @return true si l'amélioration a réussi
     */
    public boolean levelUpAbility(int slot) {
        if (slot < 1 || slot > 4) return false;
        if (skillPoints <= 0) return false;

        int maxRank = (slot == 4) ? 3 : 5; // R = 3 rangs max
        if (abilityRanks[slot] >= maxRank) return false;

        // R (ultime) : niveaux requis 6, 11, 16
        if (slot == 4) {
            int currentR = abilityRanks[4];
            int[] requiredLevels = {6, 11, 16};
            if (currentR >= requiredLevels.length) return false;
            if (level < requiredLevels[currentR]) return false;
        }

        abilityRanks[slot]++;
        skillPoints--;
        return true;
    }

    public int getAbilityRank(int slot) {
        if (slot < 0 || slot >= 5) return 0;
        return abilityRanks[slot];
    }

    public boolean isAbilityUnlocked(int slot) {
        if (slot == 0) return true; // AA toujours disponible
        return abilityRanks[slot] > 0;
    }

    /**
     * Indique si un sort PEUT être amélioré maintenant (pour le clignotement).
     */
    public boolean canLevelUp(int slot) {
        if (skillPoints <= 0) return false;
        if (slot < 1 || slot > 4) return false;
        int maxRank = (slot == 4) ? 3 : 5;
        if (abilityRanks[slot] >= maxRank) return false;
        if (slot == 4) {
            int[] req = {6, 11, 16};
            int cur = abilityRanks[4];
            if (cur >= req.length) return false;
            return level >= req[cur];
        }
        return true;
    }

    // ── Reset (nouvelle partie) ───────────────────────────────────

    public void reset() {
        level = 1;
        currentXP = 0;
        skillPoints = 1;
        for (int i = 0; i < 5; i++) abilityRanks[i] = 0;
    }

    // ── Getters ───────────────────────────────────────────────────

    public int getLevel()          { return level; }
    public double getCurrentXP()   { return currentXP; }
    public double getXPToNext()    { return level >= MAX_LEVEL ? 0 : xpForLevel(level); }
    public int getSkillPoints()    { return skillPoints; }
    public double getXPRatio()     { return level >= MAX_LEVEL ? 1.0 : currentXP / xpForLevel(level); }
}
