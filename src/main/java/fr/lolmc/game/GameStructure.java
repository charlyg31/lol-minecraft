package fr.lolmc.game;

import fr.lolmc.team.TeamManager.Team;
import org.bukkit.Location;

import java.util.List;

/**
 * Représente une structure de jeu : tourelle, nexus de lane, ou nexus de base.
 *
 * Système de phases par nom de schématique (plugins/LolMC/schematics, voir MapManager) :
 *   "Turret_Blue"     = 100 % (schématique de base)
 *   "Turret_Blue_75"  = affichée quand PV <= 75 %
 *   "Turret_Blue_50"  = affichée quand PV <= 50 %
 *   "Turret_Blue_0"   = ruines, affichées à la destruction
 * Le plugin détecte automatiquement les seuils depuis les noms de fichiers.
 */
public class GameStructure {

    public enum Type {
        TURRET,       // tourelle
        INHIBITOR,    // inhibiteur (sa destruction fait spawn des super-sbires)
        NEXUS,        // nexus de lane
        NEXUS_BASE    // nexus principal
    }

    /** Niveau d'une tourelle : PV du wiki LoL (dégâts : TurretManager, selon la minute). */
    public enum TurretTier {
        OUTER(9000),     // tourelle extérieure
        INNER(5000),     // tourelle intérieure
        INHIBITOR(4750), // tourelle d'inhibiteur
        NEXUS(3500);     // tourelles de nexus

        public final double hp;
        TurretTier(double hp) { this.hp = hp; }

        /** Niveau d'une tourelle d'après sa lane et son rang (1 = extérieure). */
        public static TurretTier fromPosition(String lane, int index) {
            if ("base".equals(lane)) return NEXUS;
            return index <= 1 ? OUTER : index == 2 ? INNER : INHIBITOR;
        }
    }

    private final Type type;
    private final Team team;
    private final String lane;        // "top", "mid", "bot" (pour les tourelles/nexus)
    private final int index;          // 1, 2, 3...
    private Location center;    // case centrale où coller la schématique
    private TurretTier tier = TurretTier.OUTER; // niveau (tourelles uniquement)
    private int angle = 0; // rotation de la schématique en degrés (0/90/180/270)

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
        // Mettre à jour la HealthBar au-dessus de la structure
        updateHealthBarNametag();
        if (currentHP <= 0) {
            destroyed = true;
            if (!phases.isEmpty()) currentPhaseIndex = phases.size() - 1; // phase la plus basse (ruines)
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
    public void setCenter(Location c) { this.center = c; }
    public int getAngle()           { return angle; }
    public void setAngle(int a)     { this.angle = a; }
    public double getMaxHP()        { return maxHP; }
    public double getCurrentHP()    { return currentHP; }
    public void respawn() { this.currentHP = this.maxHP; this.destroyed = false; this.currentPhaseIndex = 0; }

    /** Réapparition à un % des PV max (tourelles de nexus : 40 %). */
    public void reviveAt(double ratio) {
        destroyed = false;
        currentHP = maxHP * ratio;
        checkPhaseChange();
        updateHealthBarNametag();
    }

    /** Soigne sans dépasser {@code capRatio} des PV max. @return true si la phase change. */
    public boolean heal(double amount, double capRatio) {
        if (destroyed || currentHP >= maxHP * capRatio) return false;
        currentHP = Math.min(maxHP * capRatio, currentHP + amount);
        updateHealthBarNametag();
        return checkPhaseChange();
    }
    public boolean isDestroyed()    { return destroyed; }

    /**
     * Met à jour la barre de vie de la structure (nametag ArmorStand).
     *
     * Fog of war : le nametag n'est visible que par les joueurs qui ont
     * vision de la structure — l'équipe propriétaire toujours, l'équipe
     * ennemie seulement si un de ses membres est à portée de vision.
     * Un ennemi hors vision voit la structure (blocs) mais pas ses HP à jour,
     * exactement comme dans LoL.
     */
    public void updateHealthBarNametag() {
        if (center == null || center.getWorld() == null) return;
        // Trouver l'ArmorStand taggé "structure" près du centre
        for (var entity : center.getWorld().getNearbyEntities(center, 5, 6, 5)) {
            if (!(entity instanceof org.bukkit.entity.ArmorStand as)) continue;
            var pdc = as.getPersistentDataContainer();
            var key = new org.bukkit.NamespacedKey(fr.lolmc.LolPlugin.getInstance(), "structure");
            if (!pdc.has(key, org.bukkit.persistence.PersistentDataType.STRING)) continue;

            String label = switch (type) {
                case TURRET     -> "🗼";
                case INHIBITOR  -> "💎";
                case NEXUS, NEXUS_BASE -> "🏰";
            } + " " + (team == Team.BLUE ? "§9" : "§c");
            fr.lolmc.util.HealthBar.apply(as, currentHP, maxHP, label);

            // Fog of war : cacher le nametag aux ennemis hors vision
            applyNametagVisibility(as);
            return;
        }
    }

    /** Cache/montre l'ArmorStand de la barre de vie selon la vision des joueurs. */
    private void applyNametagVisibility(org.bukkit.entity.ArmorStand as) {
        var plugin = fr.lolmc.LolPlugin.getInstance();
        var tm = plugin.getTeamManager();
        var fog = plugin.getFogOfWarManager();
        double range = fog != null ? fog.getVisionRange() : FogOfWarManager.CHAMPION_SIGHT;

        for (var viewer : center.getWorld().getPlayers()) {
            var viewerTeam = tm.getTeam(viewer);
            // Équipe propriétaire (ou spectateur) : toujours visible
            if (viewerTeam == null || viewerTeam == team) {
                viewer.showEntity(plugin, as);
                continue;
            }
            // Ennemi : visible si lui ou un allié est à portée de vision
            boolean visible = false;
            for (var ally : center.getWorld().getPlayers()) {
                if (tm.getTeam(ally) != viewerTeam) continue;
                if (ally.getLocation().distanceSquared(center) <= range * range) {
                    visible = true; break;
                }
            }
            // Ward ennemie proche = vision aussi
            if (!visible) {
                var wardMgr = plugin.getWardManager();
                if (wardMgr != null && wardMgr.hasVisionAt(viewerTeam, center))
                    visible = true;
            }
            if (visible) viewer.showEntity(plugin, as);
            else         viewer.hideEntity(plugin, as);
        }
    }
    public double getHealthPercent(){ return (currentHP / maxHP) * 100.0; }
    public List<Phase> getPhases()  { return phases; }

    public void setMaxHP(double hp) { this.maxHP = hp; this.currentHP = hp; }

    public TurretTier getTier()          { return tier; }
    public void setTier(TurretTier t)    {
        this.tier = t;
        if (type == Type.TURRET) setMaxHP(t.hp);
    }

    /** Identifiant unique de la structure (ex: "turret_blue_top_1"). */
    public String getId() {
        return type.name().toLowerCase() + "_" + team.name().toLowerCase()
                + "_" + lane + "_" + index;
    }
}
