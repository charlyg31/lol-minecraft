package fr.lolmc.rune;

import fr.lolmc.rune.RuneRegistry.Path;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * La page de runes d'un joueur : voie primaire (keystone + 3 mineures),
 * voie secondaire (2 mineures de rangées différentes), et 3 fragments de stats.
 */
public class RunePage {

    /** Fragments disponibles par rangée (LoL : offensif, flexible, défensif). */
    public static final List<String> SHARDS_1 = List.of("adaptive", "attack_speed", "ability_haste");
    public static final List<String> SHARDS_2 = List.of("adaptive", "move_speed", "health_scaling");
    public static final List<String> SHARDS_3 = List.of("health", "tenacity", "health_scaling");

    // Voie primaire
    public Path primaryPath;
    public String keystone;            // id de la keystone
    public final Set<String> primaryRunes = new LinkedHashSet<>(); // 3 runes mineures

    // Voie secondaire (ordre de sélection conservé : la plus ancienne est remplacée en premier)
    public Path secondaryPath;
    public final Set<String> secondaryRunes = new LinkedHashSet<>(); // 2 runes mineures

    // Fragments de stats
    public String statShard1 = "adaptive";
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
        Set<String> all = new LinkedHashSet<>();
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

    /**
     * Retire tout ce qui ne respecte plus les règles : runes supprimées du jeu (pages sauvegardées
     * avant le patch), rune hors de sa voie ou de sa rangée, deux runes de la même rangée,
     * fragments inconnus. La page peut ensuite être incomplète.
     */
    public void sanitize() {
        RuneRegistry.Rune ks = keystone != null ? RuneRegistry.get(keystone) : null;
        // Voies absentes (page incomplète) : les déduire de la keystone / de la 1re rune secondaire
        if (primaryPath == null && ks != null) primaryPath = ks.path();
        if (secondaryPath == null) {
            for (String id : secondaryRunes) {
                RuneRegistry.Rune r = RuneRegistry.get(id);
                if (r != null && r.path() != primaryPath) { secondaryPath = r.path(); break; }
            }
        }
        if (primaryPath != null && primaryPath == secondaryPath) secondaryPath = null;
        if (ks == null || ks.path() != primaryPath || ks.slot() != 0) keystone = null;
        keepOnePerRow(primaryRunes, primaryPath, 3);
        keepOnePerRow(secondaryRunes, secondaryPath, 2);
        if (!SHARDS_1.contains(statShard1)) statShard1 = SHARDS_1.getFirst();
        if (!SHARDS_2.contains(statShard2)) statShard2 = SHARDS_2.getFirst();
        if (!SHARDS_3.contains(statShard3)) statShard3 = SHARDS_3.getFirst();
    }

    private static void keepOnePerRow(Set<String> runes, Path path, int max) {
        Set<Integer> rows = new java.util.HashSet<>();
        runes.removeIf(id -> {
            RuneRegistry.Rune r = RuneRegistry.get(id);
            return r == null || r.path() != path || r.slot() == 0 || !rows.add(r.slot()) || rows.size() > max;
        });
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
