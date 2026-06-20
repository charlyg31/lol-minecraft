package fr.lolmc.rune;

import java.util.*;

/**
 * Registre de toutes les runes de League of Legends (système Runes Reforged).
 *
 * 5 voies : PRECISION, DOMINATION, SORCERY, RESOLVE, INSPIRATION.
 * Chaque voie a : 3-4 keystones (slot 0) + 3 slots mineurs de 3 runes chacun.
 *
 * Les runes sont définies ici en données ; leurs effets sont appliqués
 * par RuneEffects selon le moment (on-hit, on-kill, passif, etc.).
 */
public class RuneRegistry {

    public enum Path {
        PRECISION("Précision", "§e"),
        DOMINATION("Domination", "§c"),
        SORCERY("Sorcellerie", "§9"),
        RESOLVE("Détermination", "§a"),
        INSPIRATION("Inspiration", "§b");

        public final String displayName;
        public final String color;
        Path(String n, String c) { this.displayName = n; this.color = c; }
    }

    /**
     * Une rune : son id, son nom, sa voie, son slot (0 = keystone, 1-3 = mineurs),
     * et une description courte de son effet.
     */
    public record Rune(String id, String name, Path path, int slot, String description) {}

    private static final Map<String, Rune> RUNES = new LinkedHashMap<>();

    static {
        // ═══════════════ PRÉCISION ═══════════════
        // Keystones (slot 0)
        add("press_attack", "Frappe Foudroyante", Path.PRECISION, 0,
            "3 attaques consécutives infligent des dégâts bonus et exposent la cible (+dégâts subis).");
        add("lethal_tempo", "Tempo Mortel", Path.PRECISION, 0,
            "Attaquer un champion donne de la vitesse d'attaque, jusqu'à 6 stacks.");
        add("fleet_footwork", "Jeu de Jambes", Path.PRECISION, 0,
            "Attaquer et bouger accumule de l'énergie ; à 100, l'attaque soigne et donne de la vitesse.");
        add("conqueror", "Conquérant", Path.PRECISION, 0,
            "Attaquer des champions donne de la force adaptative ; à 12 stacks, soigne une partie des dégâts.");
        // Slot 1
        add("absorb_life", "Absorption", Path.PRECISION, 1, "Tuer une cible soigne.");
        add("triumph", "Triomphe", Path.PRECISION, 1, "Les takedowns soignent 12% PV manquants + or bonus.");
        add("presence_mind", "Présence d'Esprit", Path.PRECISION, 1, "Les takedowns restaurent la ressource.");
        // Slot 2
        add("legend_alacrity", "Légende: Vivacité", Path.PRECISION, 2, "Gagne de la vitesse d'attaque par stacks.");
        add("legend_haste", "Légende: Hâte", Path.PRECISION, 2, "Gagne de la hâte de compétence par stacks.");
        add("legend_bloodline", "Légende: Lignée", Path.PRECISION, 2, "Gagne du vol de vie par stacks.");
        // Slot 3
        add("coup_grace", "Coup de Grâce", Path.PRECISION, 3, "Plus de dégâts aux champions à bas PV (<40%).");
        add("cut_down", "Abattage", Path.PRECISION, 3, "Plus de dégâts aux champions avec plus de PV max que toi.");
        add("last_stand", "Dernier Rempart", Path.PRECISION, 3, "Plus de dégâts quand tu es à bas PV.");

        // ═══════════════ DOMINATION ═══════════════
        add("electrocute", "Électrocution", Path.DOMINATION, 0,
            "3 attaques/sorts distincts sur un champion infligent des dégâts adaptatifs.");
        add("dark_harvest", "Moisson Noire", Path.DOMINATION, 0,
            "Frapper un champion <50% PV inflige des dégâts adaptatifs et accumule des âmes.");
        add("hail_blades", "Grêle de Lames", Path.DOMINATION, 0,
            "Après avoir attaqué un champion, vitesse d'attaque fortement accrue 3s.");
        // Slot 1
        add("cheap_shot", "Coup Bas", Path.DOMINATION, 1, "Dégâts vrais bonus aux ennemis sous contrôle.");
        add("taste_blood", "Goût du Sang", Path.DOMINATION, 1, "Soigne en blessant un champion ennemi.");
        add("sudden_impact", "Impact Soudain", Path.DOMINATION, 1, "Après un dash/Flash, gagne pénétration.");
        // Slot 2
        add("eyeball_collection", "Collection d'Yeux", Path.DOMINATION, 2, "Les takedowns donnent de la force adaptative.");
        add("ghost_poro", "Poro Fantôme", Path.DOMINATION, 2, "Place des poros dans les bushes pour la vision et l'AdF.");
        add("zombie_ward", "Ward Zombie", Path.DOMINATION, 2, "Détruire une ward ennemie en crée une amie.");
        // Slot 3
        add("ravenous_hunter", "Chasseur Vorace", Path.DOMINATION, 3, "Omnivampirisme par takedowns uniques.");
        add("relentless_hunter", "Chasseur Implacable", Path.DOMINATION, 3, "Vitesse hors combat par takedowns uniques.");
        add("ultimate_hunter", "Chasseur Ultime", Path.DOMINATION, 3, "Hâte de l'ultime par takedowns uniques.");

        // ═══════════════ SORCELLERIE ═══════════════
        add("summon_aery", "Invocation d'Aery", Path.SORCERY, 0,
            "Tes sorts/attaques envoient Aery qui blesse les ennemis ou protège les alliés.");
        add("arcane_comet", "Comète Arcanique", Path.SORCERY, 0,
            "Toucher un champion avec un sort lance une comète infligeant des dégâts.");
        add("phase_rush", "Ruée de Phase", Path.SORCERY, 0,
            "3 attaques/sorts sur un champion donnent une forte vitesse de déplacement.");
        // Slot 1
        add("nullifying_orb", "Orbe Annihilatrice", Path.SORCERY, 1, "Bouclier anti-magie à bas PV.");
        add("manaflow_band", "Bande de Flux", Path.SORCERY, 1, "Toucher un champion augmente le mana max.");
        add("nimbus_cloak", "Cape de Nimbus", Path.SORCERY, 1, "Après un sort d'invocateur, gagne de la vitesse.");
        // Slot 2
        add("transcendence", "Transcendance", Path.SORCERY, 2, "Hâte de compétence bonus par niveau.");
        add("celerity", "Célérité", Path.SORCERY, 2, "Les bonus de vitesse sont plus efficaces.");
        add("absolute_focus", "Concentration Absolue", Path.SORCERY, 2, "Force adaptative bonus quand PV élevés.");
        // Slot 3
        add("scorch", "Brûlure", Path.SORCERY, 3, "Premier sort touchant inflige des dégâts bonus (10s CD).");
        add("waterwalking", "Marche Aquatique", Path.SORCERY, 3, "Vitesse + AdF dans la rivière.");
        add("gathering_storm", "Tempête Montante", Path.SORCERY, 3, "Force adaptative croissante avec le temps.");

        // ═══════════════ DÉTERMINATION ═══════════════
        add("grasp_undying", "Poigne de l'Immortel", Path.RESOLVE, 0,
            "En combat, ton attaque inflige des dégâts %PV max, soigne et donne des PV permanents.");
        add("aftershock", "Réplique", Path.RESOLVE, 0,
            "Après avoir immobilisé un ennemi, gagne des résistances puis explose en dégâts.");
        add("guardian", "Gardien", Path.RESOLVE, 0,
            "Protège un allié proche avec un bouclier si lui ou toi subit des dégâts.");
        // Slot 1
        add("demolish", "Démolition", Path.RESOLVE, 1, "Charge une attaque puissante contre les tourelles.");
        add("font_life", "Source de Vie", Path.RESOLVE, 1, "Marque les ennemis blessés, les alliés les soignent.");
        add("shield_bash", "Coup de Bouclier", Path.RESOLVE, 1, "Quand tu as un bouclier, ton attaque inflige + dégâts.");
        // Slot 2
        add("conditioning", "Conditionnement", Path.RESOLVE, 2, "Après 12min, +armure/RM et +% résistances.");
        add("second_wind", "Second Souffle", Path.RESOLVE, 2, "Soin régénératif après avoir subi des dégâts.");
        add("bone_plating", "Plaques Osseuses", Path.RESOLVE, 2, "Réduit les dégâts des prochaines attaques après en avoir subi.");
        // Slot 3
        add("overgrowth", "Prolifération", Path.RESOLVE, 3, "PV max bonus selon les monstres/sbires morts près de toi.");
        add("revitalize", "Revitalisation", Path.RESOLVE, 3, "Soins et boucliers renforcés.");
        add("unflinching", "Inébranlable", Path.RESOLVE, 3, "Tenacité et résistance aux ralentissements.");

        // ═══════════════ INSPIRATION ═══════════════
        add("glacial_augment", "Augmentation Glaciale", Path.INSPIRATION, 0,
            "Tes attaques ralentissent ; depuis un objet actif, crée un champ de givre.");
        add("first_strike", "Première Frappe", Path.INSPIRATION, 0,
            "Initier le combat inflige des dégâts bonus et génère de l'or.");
        add("unsealed_spellbook", "Grimoire Descellé", Path.INSPIRATION, 0,
            "Change tes sorts d'invocateur en cours de partie.");
        // Slot 1
        add("hextech_flashtraption", "Flashtraption", Path.INSPIRATION, 1, "Hexéclair: Flash canalisé hors combat.");
        add("magical_footwear", "Chaussures Magiques", Path.INSPIRATION, 1, "Bottes gratuites après un délai.");
        add("triple_tonic", "Triple Tonique", Path.INSPIRATION, 1, "Potions multiples et élixir.");
        // Slot 2
        add("future_market", "Marché à Terme", Path.INSPIRATION, 2, "Permet d'acheter en dette d'or.");
        add("minion_dematerializer", "Démolécularisateur", Path.INSPIRATION, 2, "Détruit des sbires pour bonus de dégâts.");
        add("biscuit_delivery", "Livraison de Biscuits", Path.INSPIRATION, 2, "Biscuits restaurant PV/mana.");
        // Slot 3
        add("cosmic_insight", "Perspicacité Cosmique", Path.INSPIRATION, 3, "+Hâte d'invocateur et d'objets.");
        add("approach_velocity", "Vélocité d'Approche", Path.INSPIRATION, 3, "Vitesse vers les alliés/ennemis affectés.");
        add("time_warp_tonic", "Tonique Temporel", Path.INSPIRATION, 3, "Effets de potion instantanés + vitesse.");
    }

    private static void add(String id, String name, Path path, int slot, String desc) {
        RUNES.put(id, new Rune(id, name, path, slot, desc));
    }

    // ── Accès ─────────────────────────────────────────────────────

    public static Rune get(String id) { return RUNES.get(id); }

    public static Collection<Rune> all() { return RUNES.values(); }

    /** Runes d'une voie pour un slot donné (0 = keystones). */
    public static List<Rune> getByPathAndSlot(Path path, int slot) {
        List<Rune> result = new ArrayList<>();
        for (Rune r : RUNES.values()) {
            if (r.path() == path && r.slot() == slot) result.add(r);
        }
        return result;
    }

    /** Toutes les keystones d'une voie. */
    public static List<Rune> getKeystones(Path path) {
        return getByPathAndSlot(path, 0);
    }
}
