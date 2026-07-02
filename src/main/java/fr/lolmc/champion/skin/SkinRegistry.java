package fr.lolmc.champion.skin;

import org.bukkit.Material;

import java.util.*;

/**
 * Registre de tous les skins disponibles.
 *
 * Ajouter un skin :
 *   register(new ChampionSkin("steel_legion", "garen", "Légion d'Acier",
 *       1, Material.IRON_CHESTPLATE, "lolmc.skin.garen.steel_legion"));
 *
 * Le skin de base (skinNumber=0, permission=null) est toujours enregistré
 * automatiquement pour chaque champion.
 */
public class SkinRegistry {

    // championId → liste ordonnée de skins (base en premier)
    private static final Map<String, List<ChampionSkin>> registry = new LinkedHashMap<>();

    static {
        // ── TOP ──────────────────────────────────────────────────
        base("garen",       "Garen",        Material.IRON_SWORD);
        register("commando",     "garen", "Commando",         1, Material.IRON_SWORD,       null);
        register("steel_legion", "garen", "Légion d'Acier",   2, Material.IRON_CHESTPLATE,  "lolmc.skin.garen.steel_legion");
        register("demacia_vice", "garen", "Démacie Vice",     3, Material.PINK_DYE,          "lolmc.skin.garen.demacia_vice");

        base("darius",      "Darius",       Material.IRON_AXE);
        register("god_king",     "darius","God-King",          1, Material.GOLDEN_AXE,        "lolmc.skin.darius.god_king");
        register("academy",      "darius","Académie",          2, Material.LEATHER_HELMET,    null);

        base("malphite",    "Malphite",     Material.STONE);
        register("obsidian",     "malphite","Obsidian",         1, Material.OBSIDIAN,          "lolmc.skin.malphite.obsidian");
        register("mecha",        "malphite","Mecha",            2, Material.IRON_BLOCK,        "lolmc.skin.malphite.mecha");

        base("nasus",       "Nasus",        Material.GOLD_INGOT);
        register("infernal",     "nasus", "Infernal",          1, Material.MAGMA_CREAM,        "lolmc.skin.nasus.infernal");
        register("dreadknight",  "nasus", "Chevalier Sinistre",2, Material.NETHERITE_HELMET,   "lolmc.skin.nasus.dreadknight");

        // ── JUNGLE ───────────────────────────────────────────────
        base("warwick",     "Warwick",      Material.BONE);
        register("firefang",     "warwick","Crocfeu",           1, Material.BLAZE_ROD,          "lolmc.skin.warwick.firefang");
        register("blood_moon",   "warwick","Lune de Sang",      2, Material.SPIDER_EYE,         "lolmc.skin.warwick.blood_moon");

        base("amumu",       "Amumu",        Material.STRING);
        register("sad_robot",    "amumu", "Robot Triste",      1, Material.COMPARATOR,          null);
        register("infernal",     "amumu", "Infernal",          2, Material.MAGMA_BLOCK,         "lolmc.skin.amumu.infernal");

        base("masteryi",    "Master Yi",    Material.GOLDEN_SWORD);
        register("project",      "masteryi","PROJECT",          1, Material.CYAN_DYE,            "lolmc.skin.masteryi.project");
        register("blood_moon",   "masteryi","Lune de Sang",     2, Material.RED_DYE,             "lolmc.skin.masteryi.blood_moon");

        base("leesin",      "Lee Sin",      Material.LEATHER_BOOTS);
        register("god_fist",     "leesin", "Poing de Dieu",    1, Material.GOLDEN_BOOTS,        "lolmc.skin.leesin.god_fist");
        register("muay_thai",    "leesin", "Muay Thai",        2, Material.LEATHER_BOOTS,        null);

        // ── MID ──────────────────────────────────────────────────
        base("annie",       "Annie",        Material.BLAZE_POWDER);
        register("panda",        "annie",  "Panda",            1, Material.BAMBOO,               null);
        register("hextech",      "annie",  "Hextech",          2, Material.CYAN_STAINED_GLASS,   "lolmc.skin.annie.hextech");
        register("frostfire",    "annie",  "Givrefeu",         3, Material.PACKED_ICE,           "lolmc.skin.annie.frostfire");

        base("veigar",      "Veigar",       Material.DRAGON_BREATH);
        register("bad_santa",    "veigar", "Mauvais Père Noël",1, Material.RED_DYE,              null);
        register("omega_squad",  "veigar", "Escouade Oméga",  2, Material.NETHERITE_BLOCK,       "lolmc.skin.veigar.omega_squad");

        base("zed",         "Zed",          Material.NETHERITE_SWORD);
        register("project",      "zed",    "PROJECT",          1, Material.GRAY_DYE,             "lolmc.skin.zed.project");
        register("death_sworn",  "zed",    "Serment de Mort",  2, Material.WITHER_ROSE,          "lolmc.skin.zed.death_sworn");

        base("yasuo",       "Yasuo",        Material.FEATHER);
        register("true_damage",  "yasuo",  "True Damage",      1, Material.MUSIC_DISC_CAT,       "lolmc.skin.yasuo.true_damage");
        register("blood_moon",   "yasuo",  "Lune de Sang",     2, Material.RED_STAINED_GLASS,    "lolmc.skin.yasuo.blood_moon");

        // ── SUPPORT ──────────────────────────────────────────────
        base("morgana",     "Morgana",      Material.WITHER_ROSE);
        register("bewitching",   "morgana","Ensorcelante",      1, Material.ORANGE_DYE,           null);
        register("lunar_goddess","morgana","Déesse Lunaire",    2, Material.LIGHT_BLUE_DYE,       "lolmc.skin.morgana.lunar_goddess");

        base("leona",       "Leona",        Material.SHIELD);
        register("solar_eclipse","leona",  "Éclipse Solaire",  1, Material.GLOWSTONE_DUST,       "lolmc.skin.leona.solar_eclipse");
        register("pool_party",   "leona",  "Pool Party",       2, Material.SPONGE,               null);

        base("blitzcrank",  "Blitzcrank",   Material.PISTON);
        register("goalkeeper",   "blitzcrank","Gardien de But", 1, Material.YELLOW_DYE,           null);
        register("witch_craft",  "blitzcrank","Sorcellerie",    2, Material.CAULDRON,             "lolmc.skin.blitzcrank.witch_craft");

        base("janna",       "Janna",        Material.PHANTOM_MEMBRANE);
        register("hextech",      "janna",  "Hextech",          1, Material.CYAN_DYE,             "lolmc.skin.janna.hextech");
        register("medieval",     "janna",  "Médiévale",        2, Material.WHITE_DYE,            null);

        // ── ADC ──────────────────────────────────────────────────
        base("ashe",        "Ashe",         Material.BOW);
        register("queen",        "ashe",   "Reine",            1, Material.GOLD_NUGGET,          null);
        register("project",      "ashe",   "PROJECT",          2, Material.CYAN_STAINED_GLASS,   "lolmc.skin.ashe.project");
        register("cosmic",       "ashe",   "Cosmique",         3, Material.NETHER_STAR,          "lolmc.skin.ashe.cosmic");

        base("sivir",       "Sivir",        Material.GOLDEN_HOE);
        register("huntress",     "sivir",  "Chasseresse",      1, Material.GOLDEN_HOE,           null);
        register("snow_day",     "sivir",  "Jour de Neige",    2, Material.SNOWBALL,             "lolmc.skin.sivir.snow_day");

        base("jinx",        "Jinx",         Material.CROSSBOW);
        register("star_guardian","jinx",   "Gardienne des Étoiles", 1, Material.PINK_DYE,        "lolmc.skin.jinx.star_guardian");
        register("arcane",       "jinx",   "Arcane",           2, Material.NETHER_BRICK,         "lolmc.skin.jinx.arcane");
        register("crime_city",   "jinx",   "Cité du Crime",    3, Material.PURPLE_DYE,           "lolmc.skin.jinx.crime_city");

        base("missfortune", "Miss Fortune", Material.TIPPED_ARROW);
        register("cowgirl",      "missfortune","Cow-girl",      1, Material.SADDLE,               null);
        register("pool_party",   "missfortune","Pool Party",    2, Material.TROPICAL_FISH,        "lolmc.skin.missfortune.pool_party");
        register("bullet_angel", "missfortune","Ange Armée",   3, Material.FEATHER,              "lolmc.skin.missfortune.bullet_angel");
    }

    // ── API ───────────────────────────────────────────────────────

    private static void base(String champId, String champName, Material icon) {
        register("base", champId, champName + " (Base)", 0, icon, null);
    }

    private static void register(String id, String champId, String name,
                                  int num, Material icon, String perm) {
        registry.computeIfAbsent(champId, k -> new ArrayList<>())
            .add(new ChampionSkin(id, champId, name, num, icon, perm));
    }

    /** Tous les skins d'un champion accessibles à ce joueur. */
    public static List<ChampionSkin> getAccessible(String champId,
                                                    org.bukkit.entity.Player player) {
        return registry.getOrDefault(champId, List.of()).stream()
            .filter(s -> s.hasAccess(player))
            .toList();
    }

    /** Tous les skins d'un champion (pour affichage admin). */
    public static List<ChampionSkin> getAll(String champId) {
        return registry.getOrDefault(champId, List.of());
    }

    /** Skin de base d'un champion. */
    public static ChampionSkin getBase(String champId) {
        return registry.getOrDefault(champId, List.of()).stream()
            .filter(ChampionSkin::isBase).findFirst().orElse(null);
    }

    /** Récupère un skin par son ID. */
    public static ChampionSkin get(String champId, String skinId) {
        return registry.getOrDefault(champId, List.of()).stream()
            .filter(s -> s.id.equals(skinId)).findFirst().orElse(null);
    }

    /** True si ce champion a des skins au-delà du skin de base. */
    public static boolean hasSkins(String champId) {
        return registry.getOrDefault(champId, List.of()).size() > 1;
    }

    /** Toutes les permissions de skins (pour plugin.yml). */
    public static List<String> getAllPermissions() {
        return registry.values().stream()
            .flatMap(Collection::stream)
            .filter(s -> !s.isBase())
            .map(ChampionSkin::getPermission)
            .distinct().toList();
    }
}
