package fr.lolmc.game;

import fr.lolmc.team.TeamManager.Team;
import org.bukkit.Location;

import java.util.List;

/**
 * Représente une structure de jeu : tourelle, nexus de lane, ou nexus de base.
 *
 * Système de phases par nom de schématique :
 *   "TurretBlue"    = 100% (schématique de base)
 *   "TurretBlue75"  = affiché quand HP <= 75%
 *   "TurretBlue50"  = affiché quand HP <= 50%
 *   "TurretBlue25"  = affiché quand HP <= 25%
 * Le plugin détecte automatiquement les seuils depuis les noms de fichiers.
 */
public class GameStructure {

    public enum Type {
        TURRET,      // tourelle
        NEXUS,       // nexus de lane (petit nexus / inhibiteur)
        NEXUS_BASE   // nexus principal
    }

    private final Type type;
    private final Team team;
    private final String lane;        // "top", "mid", "bot" (pour les tourelles/nexus)
    private final int index;          // 1, 2, 3...
    private final Location center;    // case centrale où coller la schématique

    private double maxHP;
    private double currentHP;
    private boolean destroyed = false;

    // Phases triées par seuil décroissant (ex: [100, 75, 50, 25])
    private final List<Phase> phases;
    private int currentPhaseIndex = 0;

    public record Phase(int threshold, String schematicName) {}

    public GameStructure(Type type, Team team, String lane, int index,
                         Location center, double maxHP, List<Phase> phases) {
        this.type = type;
        this.team = team;
        this.lane = lane;
        this.index = index;
        this.center = center;
        this.maxHP = maxHP;
        this.currentHP = maxHP;
        this.phases = phases;
    }

    // ── Dégâts & phases ───────────────────────────────────────────

    /**
     * Inflige des dégâts. Retourne true si la structure change de phase.
     */
    public boolean takeDamage(double amount) {
        if (destroyed) return false;
        currentHP = Math.max(0, currentHP - amount);
        if (currentHP <= 0) {
            destroyed = true;
            return true;
        }
        return checkPhaseChange();
    }

    private boolean checkPhaseChange() {
        double pct = (currentHP / maxHP) * 100.0;
        // Trouver la phase appropriée (le plus grand seuil <= pct, en partant du haut)
        int newPhase = 0;
        for (int i = 0; i < phases.size(); i++) {
            if (pct <= phases.get(i).threshold()) {
                newPhase = i;
            }
        }
        if (newPhase != currentPhaseIndex) {
            currentPhaseIndex = newPhase;
            return true;
        }
        return false;
    }

    public void reset() {
        currentHP = maxHP;
        destroyed = false;
        currentPhaseIndex = 0;
    }

    // ── Getters ───────────────────────────────────────────────────

    public String getCurrentSchematic() {
        if (phases.isEmpty()) return null;
        return phases.get(currentPhaseIndex).schematicName();
    }

    public Type getType()           { return type; }
    public Team getTeam()           { return team; }
    public String getLane()         { return lane; }
    public int getIndex()           { return index; }
    public Location getCenter()     { return center; }
    public double getMaxHP()        { return maxHP; }
    public double getCurrentHP()    { return currentHP; }
    public boolean isDestroyed()    { return destroyed; }
    public double getHealthPercent(){ return (currentHP / maxHP) * 100.0; }
    public List<Phase> getPhases()  { return phases; }

    public void setMaxHP(double hp) { this.maxHP = hp; this.currentHP = hp; }

    /** Identifiant unique de la structure (ex: "turret_blue_top_1"). */
    public String getId() {
        return type.name().toLowerCase() + "_" + team.name().toLowerCase()
                + "_" + lane + "_" + index;
    }
}
