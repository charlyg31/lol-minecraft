package fr.lolmc.rune;

import fr.lolmc.rune.RuneRegistry.Path;

import java.util.HashSet;
import java.util.Set;

/**
 * La page de runes d'un joueur : voie primaire (keystone + 3 mineures),
 * voie secondaire (2 mineures), et 3 fragments de stats.
 */
public class RunePage {

    // Voie primaire
    public Path primaryPath;
    public String keystone;            // id de la keystone
    public final Set<String> primaryRunes = new HashSet<>(); // 3 runes mineures

    // Voie secondaire
    public Path secondaryPath;
    public final Set<String> secondaryRunes = new HashSet<>(); // 2 runes mineures

    // Fragments de stats (adaptive, attack speed/ability haste, armor/mr/hp)
    public String statShard1 = "adaptive"; // force adaptative par défaut
    public String statShard2 = "adaptive";
    public String statShard3 = "health";

    /** Vérifie qu'un id de rune est actif dans cette page. */
    public boolean has(String runeId) {
        return runeId.equals(keystone)
                || primaryRunes.contains(runeId)
                || secondaryRunes.contains(runeId);
    }

    /** Toutes les runes actives (pour itération). */
    public Set<String> allActiveRunes() {
        Set<String> all = new HashSet<>();
        if (keystone != null) all.add(keystone);
        all.addAll(primaryRunes);
        all.addAll(secondaryRunes);
        return all;
    }

    /** Page valide : keystone + 3 primaires + 2 secondaires + voies différentes. */
    public boolean isValid() {
        return keystone != null
                && primaryPath != null
                && secondaryPath != null
                && primaryPath != secondaryPath
                && primaryRunes.size() == 3
                && secondaryRunes.size() == 2;
    }

    /** Crée une page par défaut (Précision/Domination) pour les joueurs sans config. */
    public static RunePage defaultPage() {
        RunePage p = new RunePage();
        p.primaryPath = Path.PRECISION;
        p.keystone = "conqueror";
        p.primaryRunes.add("triumph");
        p.primaryRunes.add("legend_alacrity");
        p.primaryRunes.add("coup_grace");
        p.secondaryPath = Path.DOMINATION;
        p.secondaryRunes.add("cheap_shot");
        p.secondaryRunes.add("relentless_hunter");
        return p;
    }
}
