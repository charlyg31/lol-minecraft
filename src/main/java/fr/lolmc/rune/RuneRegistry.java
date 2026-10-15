package fr.lolmc.rune;

import java.util.*;

/**
 * Registre de toutes les runes de League of Legends (Runes Reforged, patch 26.20).
 *
 * 5 voies : PRECISION, DOMINATION, SORCERY, RESOLVE, INSPIRATION.
 * Chaque voie a : 3-4 keystones (slot 0) + 3 slots mineurs de 3 runes chacun.
 * Noms et descriptions : Data Dragon 16.20.1 (fr_FR).
 *
 * Les runes sont définies ici en données ; leurs effets sont appliqués par RuneManager
 * (et PassiveManager pour certaines) selon le moment (on-hit, takedown, passif, etc.).
 */
public class RuneRegistry {

    public enum Path {
        PRECISION("Précision", "§e"),
        DOMINATION("Domination", "§c"),
        SORCERY("Sorcellerie", "§9"),
        RESOLVE("Volonté", "§a"),
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
        add("press_attack", "Attaque soutenue", Path.PRECISION, 0,
            "Toucher un champion ennemi 3 fois de suite lui inflige des dégâts bonus et augmente vos dégâts contre lui.");
        add("lethal_tempo", "Tempo mortel", Path.PRECISION, 0,
            "Attaquer un champion ennemi vous octroie de la vitesse d'attaque (6 effets cumulés max).");
        add("fleet_footwork", "Jeu de jambes", Path.PRECISION, 0,
            "Attaquer et se déplacer génère de l'énergie. À 100, votre prochaine attaque vous soigne et augmente votre vitesse.");
        add("conqueror", "Conquérant", Path.PRECISION, 0,
            "Attaquer des champions vous fait gagner de la force adaptative cumulable. À 12 effets, vous récupérez des PV selon vos dégâts.");
        add("absorb_life", "Absorption vitale", Path.PRECISION, 1, "Tuer une cible vous rend des PV.");
        add("triumph", "Triomphe", Path.PRECISION, 1,
            "Participer à l'élimination d'un champion vous rend 5% de vos PV manquants, 2,5% de vos PV max et 20 PO.");
        add("presence_mind", "Présence d'esprit", Path.PRECISION, 1,
            "Blesser un champion vous rend du mana. Participer à son élimination vous rend 15% de votre mana max.");
        add("legend_alacrity", "Légende : alacrité", Path.PRECISION, 2,
            "+3% vitesse d'attaque, +1,5% par effet Légende (10 max). Les éliminations donnent des effets.");
        add("legend_haste", "Légende : accélération", Path.PRECISION, 2,
            "+1,5 accélération de compétence par effet Légende (10 max). Les éliminations donnent des effets.");
        add("legend_bloodline", "Légende : sangsue", Path.PRECISION, 2,
            "+0,45% vol de vie par effet Légende (15 max). Au maximum : +85 PV max.");
        add("coup_grace", "Coup de grâce", Path.PRECISION, 3, "+8% de dégâts contre les champions sous 40% de PV.");
        add("cut_down", "Abattage", Path.PRECISION, 3, "+8% de dégâts contre les champions au-dessus de 60% de PV.");
        add("last_stand", "Baroud d'honneur", Path.PRECISION, 3,
            "+5 à 11% de dégâts contre les champions quand vous êtes sous 60% de PV (max à 30%).");

        // ═══════════════ DOMINATION ═══════════════
        add("electrocute", "Électrocution", Path.DOMINATION, 0,
            "Toucher un champion avec 3 attaques ou compétences différentes en moins de 3 s inflige des dégâts adaptatifs.");
        add("dark_harvest", "Moisson noire", Path.DOMINATION, 0,
            "Blesser un champion à qui il reste peu de PV lui inflige des dégâts adaptatifs et siphonne son âme.");
        add("hail_blades", "Déluge de lames", Path.DOMINATION, 0,
            "Vos 3 premières attaques contre un champion gagnent beaucoup de vitesse d'attaque et des dégâts bruts.");
        add("cheap_shot", "Coup bas", Path.DOMINATION, 1, "Dégâts bruts bonus contre les champions sous contrôle.");
        add("taste_blood", "Goût du sang", Path.DOMINATION, 1, "Vous récupérez des PV quand vous blessez un champion ennemi.");
        add("sudden_impact", "Ruée offensive", Path.DOMINATION, 1,
            "Après une ruée, un bond ou une téléportation, vos dégâts aux champions sont augmentés de dégâts bruts.");
        add("sixth_sense", "Sixième sens", Path.DOMINATION, 2,
            "Repère automatiquement une balise ennemie proche (250 s). Niveau 11 : la révèle pendant 10 s.");
        add("grisly_mementos", "Souvenirs épouvantables", Path.DOMINATION, 2,
            "1 souvenir par élimination de champion (18 max). Chaque souvenir : +3 accélération de sort d'invocateur.");
        add("deep_ward", "Balise de profondeur", Path.DOMINATION, 2,
            "Vos balises dans la jungle ennemie durent plus longtemps. Niveau 9 : aussi dans la rivière.");
        add("treasure_hunter", "Chasseur de trésors", Path.DOMINATION, 3,
            "Participer à l'élimination d'un champion unique vous octroie 50 PO (+20 par effet, 130 max).");
        add("relentless_hunter", "Chasseur acharné", Path.DOMINATION, 3,
            "Chaque champion unique éliminé augmente votre vitesse hors combat.");
        add("ultimate_hunter", "Chasseur ultime", Path.DOMINATION, 3,
            "Chaque champion unique éliminé réduit le délai de récupération de votre ultime.");

        // ═══════════════ SORCELLERIE ═══════════════
        add("summon_aery", "Invocation d'Aery", Path.SORCERY, 0,
            "Vos attaques et compétences envoient Aery vers une cible, blessant les ennemis.");
        add("arcane_comet", "Comète arcanique", Path.SORCERY, 0,
            "Blesser un champion avec une compétence fait tomber une comète sur sa position.");
        add("phase_rush", "Assaut du maraudeur", Path.SORCERY, 0,
            "Infliger 25% des PV max d'un champion en 3 s : +48% de vitesse (36% à distance) et 50% de résistance aux ralentissements 4 s.");
        add("deathfire_touch", "Toucher de feu mortel", Path.SORCERY, 0,
            "Blesser un champion avec une compétence le brûle (dégâts magiques par seconde, +75% après 3 s).");
        add("axiom_arcanist", "Arcaniste axiomatique", Path.SORCERY, 1,
            "+12% de dégâts de votre ultime. Une élimination réduit son délai de récupération actuel de 7%.");
        add("manaflow_band", "Ruban de mana", Path.SORCERY, 1,
            "Toucher un champion avec une compétence : +25 mana max (250 max). Ensuite, 1% du mana manquant / 5 s.");
        add("nimbus_cloak", "Manteau nuageux", Path.SORCERY, 1,
            "Après un sort d'invocateur, vous gagnez un bref bonus de vitesse de déplacement.");
        add("transcendence", "Transcendance", Path.SORCERY, 2,
            "Niveau 5 et 8 : +5 accélération de compétence. Niveau 11 : une élimination réduit vos délais de 20%.");
        add("celerity", "Célérité", Path.SORCERY, 2, "Bonus de vitesse de déplacement 7% plus efficaces, et +1% de vitesse de déplacement.");
        add("absolute_focus", "Concentration absolue", Path.SORCERY, 2,
            "Au-dessus de 70% de PV : jusqu'à +18 AD ou +30 AP (adaptatif, selon le niveau).");
        add("scorch", "Brûlure", Path.SORCERY, 3, "Toutes les 10 s, votre prochaine compétence brûle le champion touché.");
        add("waterwalking", "Marche sur l'eau", Path.SORCERY, 3, "Dans la rivière : +10 vitesse de déplacement et +13 à 30 force adaptative.");
        add("gathering_storm", "Tempête menaçante", Path.SORCERY, 3,
            "Toutes les 10 min, vous gagnez des dégâts d'attaque ou de la puissance (adaptatif).");

        // ═══════════════ VOLONTÉ ═══════════════
        add("grasp_undying", "Poigne de l'immortel", Path.RESOLVE, 0,
            "Toutes les 4 s en combat, votre attaque inflige des dégâts selon vos PV max, vous soigne et vous donne des PV.");
        add("aftershock", "Après-coup", Path.RESOLVE, 0,
            "Après avoir immobilisé un champion, vous augmentez vos défenses, puis infligez des dégâts autour de vous.");
        add("guardian", "Gardien", Path.RESOLVE, 0,
            "Protège les alliés proches : si l'un d'eux ou vous subissez de gros dégâts, vous recevez tous les deux un bouclier.");
        add("demolish", "Démolition", Path.RESOLVE, 1, "Votre troisième attaque contre une tourelle inflige des dégâts bonus.");
        add("font_life", "Fontaine de vie", Path.RESOLVE, 1, "Restreindre un champion ennemi vous soigne, ainsi que l'allié proche le plus blessé (20 s).");
        add("shield_bash", "Coup de bouclier", Path.RESOLVE, 1,
            "Après un nouveau bouclier, votre prochaine attaque contre un champion inflige des dégâts adaptatifs.");
        add("conditioning", "Conditionnement", Path.RESOLVE, 2,
            "Après 12 min : +8 armure et +8 résistance magique, puis +3% d'armure et de résistance magique.");
        add("second_wind", "Second souffle", Path.RESOLVE, 2,
            "Après avoir subi des dégâts d'un champion, vous récupérez une partie de vos PV manquants.");
        add("bone_plating", "Plaque d'os", Path.RESOLVE, 2,
            "Après des dégâts d'un champion, ses 3 prochaines attaques vous infligent 30-60 dégâts de moins (55 s).");
        add("overgrowth", "Surcroissance", Path.RESOLVE, 3,
            "+3 PV max tous les 8 sbires ou monstres morts près de vous. À 120 : +3,5% PV max.");
        add("revitalize", "Revitalisation", Path.RESOLVE, 3, "+5% d'efficacité des soins et boucliers ; +10% sous 40% des PV.");
        add("unflinching", "Inébranlable", Path.RESOLVE, 3, "+10 armure et résistance magique sous contrôle de foule, puis pendant 2 s.");

        // ═══════════════ INSPIRATION ═══════════════
        add("glacial_augment", "Optimisation glaciale", Path.INSPIRATION, 0,
            "Immobiliser un champion crée 3 rayons glacés qui ralentissent et affaiblissent les ennemis (25 s).");
        add("unsealed_spellbook", "Grimoire déchaîné", Path.INSPIRATION, 0,
            "Échangez vos sorts d'invocateur hors combat dès 6:00 : /l grimoire <sort> <nouveau>.");
        add("first_strike", "Premier coup", Path.INSPIRATION, 0,
            "En engageant un champion : +7% de dégâts pendant 3 s et des PO selon les dégâts infligés.");
        add("hextech_flashtraption", "Canaliportation Hextech", Path.INSPIRATION, 1,
            "Quand Saut éclair est en récupération, il est remplacé par un saut canalisé.");
        add("magical_footwear", "Chaussures magiques", Path.INSPIRATION, 1,
            "Bottes (+10 vitesse) après 12 min, 45 s plus tôt par élimination ; pas de bottes avant.");
        add("cash_back", "Remise immédiate", Path.INSPIRATION, 1, "Vous récupérez 7,5% des PO dépensées en objets légendaires.");
        add("triple_tonic", "Triple tonique", Path.INSPIRATION, 2,
            "Niveau 3 : élixir d'avarice. Niveau 6 : élixir de force. Niveau 9 : élixir de compétence.");
        add("time_warp_tonic", "Philtre de chronodistorsion", Path.INSPIRATION, 2,
            "Les potions vous rendent immédiatement 40% des PV prévus.");
        add("biscuit_delivery", "Livraison de biscuits", Path.INSPIRATION, 2,
            "Un biscuit gratuit toutes les 2 min, jusqu'à 6 min de jeu.");
        add("cosmic_insight", "Savoir cosmique", Path.INSPIRATION, 3,
            "+18 accélération de sort d'invocateur, +10 accélération d'objet.");
        add("approach_velocity", "Vitesse d'approche", Path.INSPIRATION, 3,
            "Votre vitesse augmente vers les champions ennemis proches dont les déplacements sont restreints.");
        add("jack_of_all_trades", "Polyvalence", Path.INSPIRATION, 3,
            "+1 accélération de compétence par stat différente de vos objets. +8 / +20 adaptatif à 5 / 10 stats.");
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
