package fr.lolmc.stats;

/**
 * Gère les ressources d'un champion : Mana, Énergie, Flow, Furie, ou aucune.
 *
 * Affiché via :
 *   - Barre EXP Minecraft → remplissage = ressource actuelle / max
 *   - Niveau EXP          → valeur entière de la ressource
 *   - ActionBar           → HP réels LoL + ressource en texte coloré
 */
public class ResourceSystem {

    public enum ResourceType {
        MANA,    // Bleu  — champions classiques
        ENERGY,  // Jaune — Zed, Lee Sin (max 200, regen rapide, pas regen HP)
        FLOW,    // Blanc — Yasuo (se remplit en bougeant)
        FURY,    // Rouge — se remplit en attaquant
        NONE     // Garen, Warwick, Master Yi, Darius, Jinx, MissFortune
    }

    private final ResourceType type;
    private double maxResource;
    private double currentResource;
    private double regenPer5s; // regen toutes les 5 secondes

    // Énergie Lee Sin / Zed : regen 50/5s en LoL réel
    // Mana : variable selon champion (6–15/5s)
    // Flow Yasuo : se remplit en marchant (0.1/tick)
    // Fury : se remplit en attaquant (+8/AA), décroît hors combat

    public ResourceSystem(ResourceType type, double maxResource, double regenPer5s) {
        this.type = type;
        this.maxResource = maxResource;
        this.regenPer5s = regenPer5s;
        // Démarrer plein sauf Furie
        this.currentResource = (type == ResourceType.FURY) ? 0 : maxResource;
    }

    // ── Consommer de la ressource ─────────────────────────────────

    /**
     * Tente de consommer `cost` ressource.
     * @return true si succès, false si ressource insuffisante
     */
    public boolean consume(double cost) {
        if (type == ResourceType.NONE) return true; // pas de coût
        if (currentResource < cost) return false;
        currentResource = Math.max(0, currentResource - cost);
        return true;
    }

    /**
     * Régénère la ressource (appelé toutes les 5 secondes).
     * Énergie : regen rapide (50/5s dans LoL = ~10/s)
     */
    public void tickRegen() {
        if (type == ResourceType.NONE) return;
        currentResource = Math.min(maxResource, currentResource + regenPer5s);
    }

    /**
     * Furie : +8 par attaque de base
     * Decay : -4/tick hors combat → géré externalement
     */
    public void addFury(double amount) {
        if (type == ResourceType.FURY)
            currentResource = Math.min(maxResource, currentResource + amount);
    }

    /**
     * Flow Yasuo : +0.5 par mouvement (appelé quand le joueur bouge)
     */
    public void addFlow(double amount) {
        if (type == ResourceType.FLOW)
            currentResource = Math.min(maxResource, currentResource + amount);
    }

    // ── Getters ───────────────────────────────────────────────────

    public ResourceType getType()       { return type; }
    public double getCurrent()          { return currentResource; }
    public double getMax()              { return maxResource; }
    public double getRegenPer5s()       { return regenPer5s; }
    public boolean hasResource()        { return type != ResourceType.NONE; }
    public void setCurrent(double v)    { currentResource = Math.max(0, Math.min(maxResource, v)); }
    public void addCurrent(double v)    { setCurrent(currentResource + v); }
    public void addCurrent(int v)       { setCurrent(currentResource + v); }
    public void fill()                  { currentResource = maxResource; }
    /** Augmente/réduit le max (achat/vente d'item avec +mana) */
    public void addMaxResource(double v) {
        double ratio = maxResource > 0 ? currentResource / maxResource : 1.0;
        maxResource = Math.max(0, maxResource + v);
        // Garder le ratio actuel (si 80% plein, rester à 80%)
        currentResource = ratio * maxResource;
    }
    /** Augmente/réduit la regen par 5s */
    public void addRegen(double v) { regenPer5s = Math.max(0, regenPer5s + v); }

    /**
     * Ratio 0.0..1.0 pour la barre EXP.
     */
    public float getRatio() {
        if (maxResource <= 0) return 0f;
        return (float) Math.max(0, Math.min(1, currentResource / maxResource));
    }

    /**
     * Couleur d'affichage selon le type.
     */
    public net.kyori.adventure.text.format.NamedTextColor getColor() {
        return switch (type) {
            case MANA   -> net.kyori.adventure.text.format.NamedTextColor.BLUE;
            case ENERGY -> net.kyori.adventure.text.format.NamedTextColor.YELLOW;
            case FLOW   -> net.kyori.adventure.text.format.NamedTextColor.WHITE;
            case FURY   -> net.kyori.adventure.text.format.NamedTextColor.RED;
            case NONE   -> net.kyori.adventure.text.format.NamedTextColor.GRAY;
        };
    }

    /**
     * Symbole d'affichage.
     */
    public String getSymbol() {
        return switch (type) {
            case MANA   -> "💧";
            case ENERGY -> "⚡";
            case FLOW   -> "💨";
            case FURY   -> "🔥";
            case NONE   -> "";
        };
    }
}
