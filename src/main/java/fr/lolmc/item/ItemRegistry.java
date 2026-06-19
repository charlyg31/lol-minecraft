package fr.lolmc.item;

import org.bukkit.Material;
import java.util.*;
import static fr.lolmc.item.LolItem.ItemCategory.*;

/**
 * Registre COMPLET de tous les items LoL avec stats officielles (patch 15.x / 2025).
 * Sources: wiki.leagueoflegends.com, mobafire.com
 * ~230 items : composants + items finaux + bottes + consommables
 */
public class ItemRegistry {

    private static final Map<String, LolItem> ITEMS = new LinkedHashMap<>();

    static {

        // ════════════════════════════════════════════════════════════
        // COMPOSANTS DE BASE
        // ════════════════════════════════════════════════════════════

        // ── AD ──
        reg(new LolItem("long_sword",      "Long Sword",       350,  Material.IRON_SWORD,      DAMAGE).ad(10));
        reg(new LolItem("pickaxe",         "Pickaxe",          875,  Material.STONE_SWORD,     DAMAGE).ad(25));
        reg(new LolItem("bf_sword",        "B.F. Sword",       1300, Material.GOLDEN_SWORD,    DAMAGE).ad(40));
        reg(new LolItem("caulfields",      "Caulfield's Warhammer", 1050, Material.IRON_AXE,  DAMAGE).ad(15).ah(15));
        reg(new LolItem("serrated_dirk",   "Serrated Dirk",    1100, Material.STONE_AXE,      DAMAGE).ad(30).lethality(10));
        reg(new LolItem("vampiric_scepter","Vampiric Scepter", 900,  Material.GOLDEN_AXE,     DAMAGE).ad(15).lifeSteal(0.10));

        // ── AP ──
        reg(new LolItem("amplifying_tome", "Amplifying Tome",  435,  Material.BOOK,            MAGE).ap(20));
        reg(new LolItem("blasting_wand",   "Blasting Wand",    850,  Material.BLAZE_ROD,       MAGE).ap(40));
        reg(new LolItem("needlessly_large_rod","Needlessly Large Rod",1250,Material.END_ROD,  MAGE).ap(65));
        reg(new LolItem("aether_wisp",     "Aether Wisp",      900,  Material.PRISMARINE_CRYSTALS,MAGE).ap(30).ms(5));
        reg(new LolItem("bandleglass_mirror","Bandleglass Mirror",900,Material.SPYGLASS,       MAGE).ap(25).ah(10).manaRegen(50));
        reg(new LolItem("blighting_jewel", "Blighting Jewel",  1100, Material.AMETHYST_SHARD,  MAGE).ap(30).magicPen(0.10));
        reg(new LolItem("oblivion_orb",    "Oblivion Orb",     800,  Material.ENDER_EYE,       MAGE).ap(25).passive("Execrate","-15% soins sur la cible."));
        reg(new LolItem("fiendish_codex",  "Fiendish Codex",   900,  Material.ENCHANTED_BOOK,  MAGE).ap(30).ah(15));

        // ── TANK ──
        reg(new LolItem("ruby_crystal",    "Ruby Crystal",     400,  Material.REDSTONE,        TANK).hp(150));
        reg(new LolItem("giants_belt",     "Giant's Belt",     900,  Material.RED_WOOL,        TANK).hp(350));
        reg(new LolItem("cloth_armor",     "Cloth Armor",      300,  Material.LEATHER_CHESTPLATE,TANK).armor(15));
        reg(new LolItem("chain_vest",      "Chain Vest",       800,  Material.CHAINMAIL_CHESTPLATE,TANK).armor(40));
        reg(new LolItem("null_magic_orb",  "Null-Magic Orb",   450,  Material.GRAY_DYE,        TANK).mr(25));
        reg(new LolItem("negatron_cloak",  "Negatron Cloak",   800,  Material.PURPLE_DYE,      TANK).mr(40));
        reg(new LolItem("bramble_vest",    "Bramble Vest",     800,  Material.IRON_BARS,       TANK).armor(30).passive("Thorns","Réfléchit 6+10%%armure dégâts magiques."));
        reg(new LolItem("warden_mail",     "Warden's Mail",    1000, Material.IRON_CHESTPLATE, TANK).armor(40).passive("Cold Steel","-15%% AS aux attaquants."));
        reg(new LolItem("bamis_cinder",    "Bami's Cinder",    900,  Material.MAGMA_BLOCK,     TANK).hp(200).ah(5).passive("Immolate","8-20 dégâts magiques/s proches."));
        reg(new LolItem("aegis_legion",    "Aegis of the Legion",1000,Material.SHIELD,        TANK).armor(20).mr(20));
        reg(new LolItem("haunting_guise",  "Haunting Guise",   1300, Material.GHAST_TEAR,      MAGE).ap(25).hp(150).passive("Eye of Pain","+15 pénétration magique."));
        reg(new LolItem("catalyst",        "Catalyst of Aeons",1300, Material.EXPERIENCE_BOTTLE,UTILITY).hp(225).mana(300));
        reg(new LolItem("spectre_cowl",    "Spectre's Cowl",   1200, Material.LEATHER_HELMET,  TANK).hp(150).mr(30).hpRegen(100));
        reg(new LolItem("negatron_aether", "Negatron Cloak",   800,  Material.PURPLE_WOOL,     TANK).mr(40));

        // ── AS / CRIT ──
        reg(new LolItem("dagger",         "Dagger",            300,  Material.WOODEN_SWORD,   ATTACK_SPEED).as(0.12));
        reg(new LolItem("recurve_bow",    "Recurve Bow",       1000, Material.BOW,            ATTACK_SPEED).as(0.25));
        reg(new LolItem("cloak_agility",  "Cloak of Agility",  600,  Material.FEATHER,        ATTACK_SPEED).crit(0.15));
        reg(new LolItem("kircheis_shard", "Kircheis Shard",    700,  Material.SPECTRAL_ARROW, ATTACK_SPEED).as(0.15));
        reg(new LolItem("zeal",           "Zeal",              1000, Material.ARROW,          ATTACK_SPEED).as(0.18).crit(0.15).ms(5));
        reg(new LolItem("hearthbound_axe","Hearthbound Axe",   1100, Material.GOLDEN_AXE,     ATTACK_SPEED).ad(15).as(0.15).ms(5));

        // ── MANA / REGEN ──
        reg(new LolItem("sapphire_crystal","Sapphire Crystal", 350,  Material.LAPIS_LAZULI,   UTILITY).mana(250));
        reg(new LolItem("faerie_charm",   "Faerie Charm",      125,  Material.PINK_PETALS,    UTILITY).manaRegen(25));
        reg(new LolItem("rejuv_bead",     "Rejuvenation Bead", 150,  Material.LIME_DYE,       UTILITY).hpRegen(50));
        reg(new LolItem("sheen",          "Sheen",             700,  Material.QUARTZ,         UTILITY).mana(200).ah(5).passive("Spellblade","Après sort: prochain AA +100%% AD base."));
        reg(new LolItem("phage",          "Phage",             1100, Material.STONE_SWORD,    UTILITY).ad(18).hp(250).passive("Snowball","+15 vitesse 2s après AA."));
        reg(new LolItem("kindlegem",      "Kindlegem",         800,  Material.GLOWSTONE_DUST, UTILITY).hp(200).ah(10));
        reg(new LolItem("stopwatch",      "Stopwatch",         650,  Material.CLOCK,          UTILITY).active("Stasis","1 fois: invulnérabilité 2.5s."));

        // ── DIVERS COMPOSANTS ──
        reg(new LolItem("crystalline_bracer","Crystalline Bracer",650,Material.PRISMARINE_BRICK,UTILITY).hp(200).hpRegen(50));
        reg(new LolItem("cappa_juice",    "Cappa Juice",       700,  Material.POTION,         UTILITY).passive("Actif","Soigne 40%%HP max sur 120s. 1 charge."));
        reg(new LolItem("winged_moonplate","Winged Moonplate", 800,  Material.PHANTOM_MEMBRANE,UTILITY).hp(150).ms(4));

        // ════════════════════════════════════════════════════════════
        // ITEMS STARTER
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("dorans_blade",   "Doran's Blade",     450,  Material.IRON_SWORD,     DAMAGE).ad(8).hp(80).lifeSteal(0.08));
        reg(new LolItem("dorans_ring",    "Doran's Ring",      450,  Material.BLAZE_POWDER,   MAGE).ap(15).hp(70).mana(50).manaRegen(50));
        reg(new LolItem("dorans_shield",  "Doran's Shield",     450,  Material.SHIELD,         TANK).hp(110).hpRegen(125).dmgReduction(8).passive("Endure","Réduit les dégâts de 8 (early game)."));
        reg(new LolItem("cull",           "Cull",              450,  Material.WOODEN_AXE,     DAMAGE).ad(7).lifeSteal(0.09).passive("Reap","+1 or par minion tué (max 100)."));
        reg(new LolItem("spectral_sickle","Spectral Sickle",   400,  Material.GOLDEN_PICKAXE, SUPPORT).passive("Spoils of War","Partage or avec ADC proche à chaque kill."));

        // ════════════════════════════════════════════════════════════
        // ITEMS AD FINAUX (MANQUANTS)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("sundered_sky",   "Sundered Sky",      3100, Material.DIAMOND_SWORD,  DAMAGE).ad(40).hp(350).ah(20).crit(0.20).passive("Lightshield Strike","AA critique healing 10%%HP manquants."));
        reg(new LolItem("voltaic_cyclosword","Voltaic Cyclosword",2900,Material.STICK,        DAMAGE).ad(55).lethality(18).ah(20).passive("Firmament","AA: stacks. 100 stacks: AA éclair +100 dégâts physiques."));
        reg(new LolItem("axiom_arc",      "Axiom Arc",         2750, Material.PRISMARINE,     DAMAGE).ad(55).lethality(10).ah(25).passive("Flux","Kill/assist: réduit CD ultime de 50%%."));
        reg(new LolItem("bastionbreaker", "Bastionbreaker",    3200, Material.NETHERITE_PICKAXE,DAMAGE).ad(45).lethality(25).ah(25).passive("Breaker","Dégâts sur boucliers: +15%% dommages."));
        reg(new LolItem("chempunk_chainsword","Chempunk Chainsword",3000,Material.IRON_HOE,  DAMAGE).ad(45).hp(350).ah(20).passive("Hackshorn","-60%% soins sur cible frappée."));
        reg(new LolItem("spear_of_shojin","Spear of Shojin",   3100, Material.STICK,          DAMAGE).ad(55).hp(300).ah(20).passive("Dragonforce","Après sort: 3 AA réduisent CD de 1s."));
        reg(new LolItem("bloodletter_curse","Bloodletter's Curse",2900,Material.NETHERITE_HOE,DAMAGE).ad(25).hp(300).ap(40).ah(20).passive("Vile Decay","-5%% MR/stack (max 6) sur dégâts magiques."));
        reg(new LolItem("atmas_reckoning","Atma's Reckoning",  3000, Material.DIAMOND_PICKAXE,DAMAGE).ad(30).crit(0.20).passive("Valiance","+3%% AD bonus = %% HP max."));
        reg(new LolItem("mortal_reminder","Mortal Reminder",   2500, Material.BONE,           DAMAGE).ad(35).crit(0.25).armorPenPct(0.30).passive("Executioner","-40%% soins sur la cible."));
        reg(new LolItem("hubris",         "Hubris",            3000, Material.DIAMOND,        DAMAGE).ad(60).lethality(15).passive("Eminence","Kill champ: +40 AD temporaire."));
        reg(new LolItem("edge_of_night",  "Edge of Night",     2800, Material.BLACK_STAINED_GLASS,DAMAGE).ad(55).hp(275).lethality(15).passive("Night Edge","Bouclier sorts 1 fois."));
        reg(new LolItem("profane_hydra",  "Profane Hydra",     3300, Material.IRON_AXE,       DAMAGE).ad(60).lethality(15).lifeSteal(0.12).active("Voracité","AA/sorts infligent 60%% dégâts AoE."));
        reg(new LolItem("titanic_hydra",  "Titanic Hydra",     3300, Material.NETHERITE_AXE,  TANK).hp(600).ad(40).hpRegen(150).passive("Colossal","AA: +5+1%%HP max dégâts."));
        reg(new LolItem("frozen_heart",   "Frozen Heart",      2500, Material.ICE,            TANK).armor(70).mana(400).ah(20).passive("Winter's Caress","-20%% AS aux ennemis proches."));
        reg(new LolItem("sunfire_cape",   "Sunfire Cape",      3100, Material.ORANGE_WOOL,    TANK).hp(450).armor(30).mr(30).ah(15).passive("Immolate","20-40 dégâts/s AoE."));
        reg(new LolItem("iceborn_gauntlet","Iceborn Gauntlet", 3100, Material.PACKED_ICE,     TANK).armor(60).mana(400).ah(20).passive("Spellblade","Après sort: zone ralentissante."));

        // ════════════════════════════════════════════════════════════
        // ITEMS MAGE FINAUX (MANQUANTS)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("actualizer",     "Actualizer",        2800, Material.ENCHANTED_BOOK, MAGE).ap(70).ah(20).mana(300));
        reg(new LolItem("blackfire_torch","Blackfire Torch",   2800, Material.TORCH,          MAGE).ap(80).ah(20).mana(400).passive("Baleful Blaze","Dégâts sorts: +2%% max HP dégâts DoT 4s."));
        reg(new LolItem("liandry_anguish","Liandry's Anguish", 3000, Material.CAMPFIRE,       MAGE).ap(90).mana(300).ah(25).passive("Torment","Dégâts sorts brûlent 1%%HP max/s (4s)."));
        reg(new LolItem("demonic_embrace","Demonic Embrace",   3000, Material.SOUL_SAND,      MAGE).ap(70).hp(350).passive("Azakana","Dégâts magiques: +1%%HP max DoT 4s."));
        reg(new LolItem("stormsurge_nh","Stormsurge",        3000, Material.LIGHTNING_ROD,  MAGE).ap(95).ms(5).lethality(10).passive("Stormraider","Kill champ: foudre voisins 300+25%%AP."));
        reg(new LolItem("horizon_focus",  "Horizon Focus",     3000, Material.SPYGLASS,       MAGE).ap(100).hp(150).ah(15).passive("Hypershot","Sorts à >700 portée: +10%% dégâts."));
        reg(new LolItem("imperial_mandate","Imperial Mandate", 2200, Material.GOLD_INGOT,     SUPPORT).ap(40).hp(200).ah(20).manaRegen(100).passive("Coordinated Fire","CC: marque. Allié attaquant: +90+30%%AP dégâts."));
        reg(new LolItem("malignance",     "Malignance",        2700, Material.PURPLE_STAINED_GLASS,MAGE).ap(80).mana(600).ah(20).passive("Haunt","Dégâts d'ultime: -20%% MR 3s."));
        reg(new LolItem("seraphs_embrace","Seraph's Embrace",  3200, Material.CYAN_DYE,       MAGE).ap(80).ah(25).mana(860).passive("Lifeline","HP crit bas: bouclier.").passive("Awe","+1%% AP / 100 mana."));
        reg(new LolItem("night_cap",      "Rabadon's Deathcap",3600, Material.POINTED_DRIPSTONE,MAGE).ap(120).passive("Amplification","+35%% AP total."));
        reg(new LolItem("twin_shadows",   "Ixtali Seedjar",    2300, Material.AZALEA,         MAGE).ap(50).ah(20).ms(5).passive("Ancestral Gifts","Actif: envoie fantômes ralentissants."));

        // ════════════════════════════════════════════════════════════
        // ITEMS TANK FINAUX (MANQUANTS)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("unending_despair","Unending Despair",  2900, Material.CRYING_OBSIDIAN,TANK).hp(400).armor(50).mr(10).passive("Anguish","Soigne 2%%HP max/s en combat."));
        reg(new LolItem("turbo_chemtank", "Turbo Chemtank",    3200, Material.CAULDRON,       TANK).hp(350).armor(25).mr(50).ah(15).active("Immolate","20-40 dégâts/s AoE.").passive("Supercharged","Actif: +75%% MS vers ennemis, puis AoE ralentissant."));
        reg(new LolItem("spirit_visage",  "Spirit Visage",     2900, Material.NETHER_BRICK,   TANK).hp(450).mr(40).hpRegen(150).ah(10).passive("Boundless Vitality","+30%% soins reçus."));
        reg(new LolItem("knights_vow",    "Knight's Vow",      2300, Material.GOLDEN_CHESTPLATE,SUPPORT).hp(400).armor(30).ah(20).passive("Sacrifice","Redirige 15%% dégâts d'un allié vers toi."));
        reg(new LolItem("hullbreaker",    "Hullbreaker",       3000, Material.OAK_LOG,        DAMAGE).ad(50).hp(400).passive("Boarding Party","Seul: +20 armure/MR. Boost minions proches."));
        reg(new LolItem("anathema_chains","Anathema's Chains", 2500, Material.CHAIN,          TANK).hp(650).ah(20).passive("Vendetta","Désigne 1 ennemi: -20%% dégâts de lui."));
        reg(new LolItem("frozen_mallet",  "Frozen Mallet",     3100, Material.PACKED_ICE,     TANK).ad(30).hp(700).passive("Icy","AA ralentissent 40%% (20%% ranged) 1.5s."));
        reg(new LolItem("ravenous_hydra2","Heartsteel",        3000, Material.RED_CONCRETE,   TANK).hp(800).hpRegen(50).passive("Colossal Consumption","Toutes 3s: +5 HP stack."));
        reg(new LolItem("warmogs",        "Warmog's Armor",    3100, Material.RED_WOOL,       TANK).hp(800).hpRegen(200).passive("Warmog's Heart",">1000 HP bonus: +5%% HP max/s hors combat."));
        reg(new LolItem("bandlepipes",    "Bandlepipes",       2300, Material.NOTE_BLOCK,     SUPPORT).hp(250).armor(25).mr(25).ah(15).passive("Rally","CC: alliés proches +15%% AS 5s."));
        reg(new LolItem("mantle_12th",    "Mantle of the Twelfth Hour",3000,Material.TOTEM_OF_UNDYING,TANK).hp(400).armor(30).mr(30).hpRegen(150).passive("Lifeline","HP bas: soin sur 4s + tenacité."));
        reg(new LolItem("echoes_helia",   "Echoes of Helia",   2200, Material.HEART_OF_THE_SEA,SUPPORT).hp(150).mana(200).ah(20).manaRegen(75).passive("Soul Siphon","Soins → stacks → soins AoE et dégâts."));
        reg(new LolItem("unending_desp",  "Unending Despair",  2900, Material.OBSIDIAN,       TANK).hp(400).armor(50).mr(10).passive("Anguish","Soin 2%%HP/s proche ennemi."));

        // ════════════════════════════════════════════════════════════
        // ITEMS SUPPORT FINAUX (MANQUANTS)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("dawncore",       "Dawncore",          2400, Material.DAYLIGHT_DETECTOR,SUPPORT).ap(50).mana(300).ah(20).hpRegen(100).passive("Dawnsurge","Soins bonus scalent avec AP."));
        reg(new LolItem("immortal_shieldbow","Immortal Shieldbow",3400,Material.CROSSBOW,     DAMAGE).ad(50).as(0.15).crit(0.20).lifeSteal(0.12).passive("Lifeline","HP bas: bouclier 250-700."));
        reg(new LolItem("vigilant_wardstone","Vigilant Wardstone",1100,Material.LANTERN,      SUPPORT).ah(12).passive("Warding","Porte 3 wards. Alliés proches: +12 AH et +12%% AS."));
        reg(new LolItem("zekess_convergence","Zeke's Convergence",2400,Material.CYAN_WOOL,    SUPPORT).hp(300).armor(30).mr(30).ah(15).passive("Conduit","CC allié: +30%% dégâts 6s."));
        reg(new LolItem("whispering_circlet","Whispering Circlet",2250,Material.WHITE_DYE,   SUPPORT).hp(200).mana(300).manaRegen(75).passive("Whi","Soins + bouclier améliorés."));
        reg(new LolItem("diadem_songs",   "Diadem of Songs",   2250, Material.MUSIC_DISC_13,  SUPPORT).ap(40).mana(200).manaRegen(75).passive("Song","Soins AP-scalés."));

        // ════════════════════════════════════════════════════════════
        // ITEMS CRIT / AS FINAUX (MANQUANTS)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("statikk_shiv",   "Statikk Shiv",      2900, Material.LIGHTNING_ROD, ATTACK_SPEED).ad(45).as(0.45).crit(0.20).passive("Electroshock","Toutes les 3 AA: 60-190 dégâts magiques AoE."));
        reg(new LolItem("rapid_firecannon","Rapid Firecannon",  2600, Material.CROSSBOW,       ATTACK_SPEED).ad(35).as(0.35).crit(0.20).ms(5).passive("Sharpshooter","Charge: +50%% portée AA + électrique."));
        reg(new LolItem("runaans_hurricane","Runaan's Hurricane",2600,Material.BOW,            ATTACK_SPEED).ad(25).as(0.45).crit(0.20).ms(5).passive("Wind's Fury","AA envoient 2 projectiles latéraux."));
        reg(new LolItem("fiendhunter_bolts","Fiendhunter Bolts",2650,Material.ARROW,          ATTACK_SPEED).as(0.45).crit(0.20).passive("Hunt","AA garantis crit après ultime. +15%% vrais dégâts."));
        reg(new LolItem("emblem_allin",   "Emblem of All-Inning",2650,Material.GOLDEN_HELMET, ATTACK_SPEED).as(0.30).crit(0.20).ah(30).passive("Ultimatum","Ultime: prochain AA garanti crit."));
        reg(new LolItem("hexoptics_c44",  "Hexoptics C44",      2800, Material.SPYGLASS,       ATTACK_SPEED).ad(50).as(0.25).passive("Long Shot","Dégâts +0-10%% selon distance. +100 portée après kill."));

        // ════════════════════════════════════════════════════════════
        // BOOTS TIER 3 (SAISON 2025 - FEATS OF STRENGTH)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("armored_advance","Armored Advance",    1200, Material.IRON_BOOTS,     TANK).ms(45).armor(25).passive("Plating","-12%% dégâts AA. Bouclier après dégâts physiques."));
        reg(new LolItem("chainlaced_crushers","Chainlaced Crushers",1250,Material.CHAINMAIL_BOOTS,TANK).ms(45).mr(30).passive("Tenacity","-30%% durée CC. Bouclier après dégâts magiques."));
        reg(new LolItem("crimson_lucidity","Crimson Lucidity",  1200, Material.DIAMOND_BOOTS,  MAGE).ms(45).ah(20).passive("Flow","Après sort: +8%% MS 2s."));
        reg(new LolItem("gunmetal_greaves","Gunmetal Greaves",  1200, Material.NETHERITE_BOOTS,ATTACK_SPEED).ms(45).as(0.50).passive("Iron Plating","-12%% dégâts AA."));
        reg(new LolItem("spellslingers_shoes","Spellslinger's Shoes",1200,Material.GOLDEN_BOOTS,MAGE).ms(45).flatMagicPen(22));
        reg(new LolItem("swiftmarch",     "Swiftmarch",         1100, Material.LEATHER_BOOTS,  UTILITY).ms(60).passive("March","Hors combat: +5%% MS supplémentaire."));
        reg(new LolItem("boots_swiftness","Boots of Swiftness", 1000, Material.LEATHER_BOOTS,  UTILITY).ms(55).passive("Slow Resist","-25%% durée ralentissements."));

        // ════════════════════════════════════════════════════════════
        // ITEMS AD JUNGLE
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("smite_stalker", "Gustwalker Smite",   350,  Material.GRAY_WOOL,      UTILITY).ms(5).passive("Smite","Inflige 900 dégâts vrais aux monstres."));
        reg(new LolItem("pickaxe_jungle","Mosstomper Smite",   350,  Material.GREEN_WOOL,     UTILITY).hp(75).passive("Smite","Inflige 900 dégâts vrais aux monstres."));
        reg(new LolItem("blue_smite",    "Scorchclaw Smite",   350,  Material.RED_WOOL,       UTILITY).passive("Smite","Inflige 900 dégâts vrais. AA ralentissent."));
        reg(new LolItem("noonquiver",    "Noonquiver",          1100, Material.ARROW,          DAMAGE).ad(25).as(0.15).passive("Coup de foudre","AA: stocke. Tir 50 dégâts physiques bonus."));
        reg(new LolItem("long_bow",      "Long Bow",            1000, Material.BOW,            DAMAGE).ad(20).as(0.15));

        // ════════════════════════════════════════════════════════════
        // ITEMS UTILITAIRES / SPÉCIAUX (MANQUANTS)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("cosmic_drive2", "Cosmic Drive",        3000, Material.NETHER_BRICK,   MAGE).ap(90).hp(300).ms(5).ah(30).passive("Spelldance","Après sort: +20%% MS 2s."));
        reg(new LolItem("unending_fury", "Endless Hunger",      3000, Material.RAW_IRON,       DAMAGE).ad(55).omnivamp(0.15).ah(20).passive("Voracious","Kill/assist: +15%% omnivamp 3s."));
        reg(new LolItem("overlords_bloodmail","Overlord's Bloodmail",3000,Material.NETHER_STAR,TANK).hp(500).ad(25).passive("Ichor","Chaque stack 1%% HP max bonus = +0.5 AD bonus."));
        reg(new LolItem("riftmaker",     "Riftmaker",           3000, Material.END_CRYSTAL,    MAGE).ap(80).hp(300).omnivamp(0.08).ah(15).passive("Void Infusion","Dégâts magiques: +8%% + 3%% par stack combat (max 3)."));
        reg(new LolItem("jak_sho",       "Jak'Sho, The Protean",3400,Material.ENDER_EYE,      TANK).hp(400).armor(40).mr(40).passive("Voidborn Resilience","+5 armure/MR/stack combat (max 8)."));
        reg(new LolItem("trinity_force2","Trinity Force",       3333, Material.NETHER_STAR,    DAMAGE).ad(36).hp(333).as(0.30).ah(15).ms(4).passive("Spellblade","Après sort: AA +200%% AD base.").passive("Quicken","AA: +20 MS 2s."));
        reg(new LolItem("black_cleaver2","Black Cleaver",       3100, Material.IRON_SWORD,     DAMAGE).ad(40).hp(400).ah(30).passive("Carve","-6%% armure/stack (max 6)."));
        reg(new LolItem("manamune",      "Manamune",            2200, Material.BUCKET,         UTILITY).ad(35).mana(500).manaRegen(50).passive("Awe","+2%% AD = mana bonus. Stacks AA/sorts."));
        reg(new LolItem("muramana",      "Muramana",            2200, Material.WATER_BUCKET,   UTILITY).ad(35).mana(860).passive("Awe","+2%% AD = mana. AA/sorts consomment 3%% mana = dégâts."));
        reg(new LolItem("divine_sunderer","Divine Sunderer",    3300, Material.GOLD_BLOCK,     DAMAGE).ad(35).hp(400).ah(20).passive("Spellblade","Après sort: AA +125%%AD base + 6%%HP max cible."));
        reg(new LolItem("essence_reaver","Essence Reaver",      2800, Material.DIAMOND_HOE,    DAMAGE).ad(60).crit(0.20).ah(15).passive("Spellblade","Après sort: AA +100%%AD + 3%% mana resto."));
        reg(new LolItem("sterak_gage2",  "Sterak's Gage",       3100, Material.IRON_CHESTPLATE,DAMAGE).ad(50).hp(400).passive("Lifeline","HP <30%%: bouclier 75%%AD bonus 4s."));

        // ════════════════════════════════════════════════════════════
        // CONSOMMABLES
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("health_potion", "Health Potion",       50,   Material.POTION,         UTILITY).passive("Actif","Soigne 150 HP sur 15s."));
        reg(new LolItem("refillable_potion","Refillable Potion",150,  Material.GLASS_BOTTLE,   UTILITY).passive("Actif","2 charges: soigne 125 HP sur 12s."));
        reg(new LolItem("elixir_wrath",  "Elixir of Wrath",     500,  Material.DRAGON_BREATH,  DAMAGE).passive("Actif","3min: +30 AD + vol de vie."));
        reg(new LolItem("elixir_iron",   "Elixir of Iron",      500,  Material.IRON_NUGGET,    TANK).passive("Actif","3min: +300 HP + Tenacité + MS."));
        reg(new LolItem("elixir_sorcery","Elixir of Sorcery",   500,  Material.MAGENTA_DYE,    MAGE).passive("Actif","3min: +50 AP + 15 mana/s."));
        reg(new LolItem("oracle_lens",   "Oracle Lens",         0,    Material.SPYGLASS,        UTILITY).passive("Actif","Révèle et détruit wards proches 10s."));
        reg(new LolItem("stealth_ward",  "Stealth Ward (trinket)",0,  Material.TORCH,           UTILITY).passive("Actif","Place une ward invisible 150s (3 charges max)."));
        reg(new LolItem("farsight",      "Farsight Alteration", 0,    Material.COMPASS,         UTILITY).passive("Actif","Place ward révélatrice à longue portée."));

        // ════════════════════════════════════════════════════════════
        // ITEMS FINAUX DIVERS (MANQUANTS)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("serpents_fang",  "Serpent's Fang",     2200, Material.POISONOUS_POTATO,DAMAGE).ad(55).lethality(15).passive("Shield Reaver","Dégâts physiques réduisent boucliers de 35%%."));
        reg(new LolItem("opportunity",    "Opportunity",        2700, Material.ENDER_PEARL,    DAMAGE).ad(55).lethality(15).passive("Preparation","Hors combat: prochain AA ou sort +15%% dégâts."));
        reg(new LolItem("last_whisper",   "Last Whisper",       1450, Material.FEATHER,        DAMAGE).ad(20).armorPenPct(0.20).passive("Last Whisper","+20%% pénétration armure."));
        reg(new LolItem("the_collector",  "The Collector",      3000, Material.DIAMOND_PICKAXE,DAMAGE).ad(55).crit(0.20).lethality(10).passive("Death","Exécute <5%% HP."));
        reg(new LolItem("navori_quickblades","Navori Quickblades",3100,Material.STONE_SWORD,  DAMAGE).ad(65).crit(0.20).ah(15).passive("Transcendence","Crits réduisent CD de 15%%."));
        reg(new LolItem("phantom_dancer2","Phantom Dancer",     2900, Material.ELYTRA,         ATTACK_SPEED).as(0.30).crit(0.20).ms(7).passive("Spectral Waltz","AA: ghosting +7%% MS 2s."));
        reg(new LolItem("kraken_slayer2", "Kraken Slayer",      3000, Material.TRIDENT,        DAMAGE).ad(40).as(0.30).crit(0.20).passive("Bring It Down","3ème AA: +150 vrais dégâts."));
        reg(new LolItem("wit_s_end2",     "Wit's End",          3100, Material.DIAMOND_AXE,    ATTACK_SPEED).as(0.40).mr(40).ms(5).passive("Fray","+42 dégâts magiques on-hit."));
        reg(new LolItem("nashors_tooth2", "Nashor's Tooth",     3000, Material.BONE,           ATTACK_SPEED).ap(100).as(0.50).ah(15).passive("Bite","+15+20%%AP dégâts on-hit."));
        reg(new LolItem("hextec_rocketbelt2","Hextech Rocketbelt",3200,Material.FIREWORK_ROCKET,MAGE).ap(90).hp(250).ms(5).active("Supersonic","Actif: dash + 3 missiles 90s CD."));
        reg(new LolItem("evenshroud",     "Evenshroud",         2500, Material.DARK_OAK_PLANKS,SUPPORT).hp(300).armor(20).mr(20).ah(10).passive("Coruscation","CC: -7%% résistances cible 5s."));
        reg(new LolItem("world_atlas",    "World Atlas",         400,  Material.MAP,            SUPPORT).passive("Income","Or partagé par kill/minion proche."));
        reg(new LolItem("bounty_worlds",  "Bounty of Worlds",   400,  Material.FILLED_MAP,     SUPPORT).hp(50).hpRegen(25).manaRegen(25).passive("Income","Or partagé."));
        reg(new LolItem("celestial_opposition","Celestial Opposition",400,Material.YELLOW_DYE, SUPPORT).passive("Income","Or partagé. +CC bonus."));
        reg(new LolItem("solstice_sleigh","Solstice Sleigh",     400,  Material.JUKEBOX,        SUPPORT).passive("Income","Or partagé. +MS bonus."));
        reg(new LolItem("watchful_wardstone","Watchful Wardstone",1100,Material.LANTERN,       SUPPORT).hp(150).ah(10).passive("Warding","Porte 2 wards. Upgrade possible."));


        // ════════════════════════════════════════════════════════════
        // ITEMS ANCIENS REGISTRE (complément)
        // ════════════════════════════════════════════════════════════

        // AD
        reg(new LolItem("infinity_edge",   "Infinity Edge",     3500, Material.NETHERITE_SWORD, DAMAGE).ad(75).crit(0.20).critDmg(0.30).passive("Perfection","+30%% dégâts critiques."));
        reg(new LolItem("ravenous_hydra",  "Ravenous Hydra",    3300, Material.IRON_AXE,        DAMAGE).ad(70).hpRegen(150).lifeSteal(0.15).ah(20).active("Voracité","AA infligent 40%% dégâts AoE."));
        reg(new LolItem("black_cleaver",   "Black Cleaver",     3100, Material.IRON_SWORD,      DAMAGE).ad(40).hp(400).ah(30).passive("Carve","-6%% armure/stack."));
        reg(new LolItem("youmuus_ghostblade","Youmuu's Ghostblade",2700,Material.GOLDEN_SWORD,  DAMAGE).ad(60).lethality(18).ms(20));
        reg(new LolItem("seryldas_grudge", "Serylda's Grudge",  3200, Material.STONE_SWORD,     DAMAGE).ad(45).lethality(15).armorPenPct(0.30).ah(20).passive("Bitter Cold","Dégâts: ralentit 30%% 1s."));
        reg(new LolItem("lord_dominiks",   "Lord Dominik's Regards",3000,Material.DIAMOND_SWORD,DAMAGE).ad(35).crit(0.20).armorPenPct(0.35).passive("Giant Slayer","+0-15%% dégâts si cible plus de HP."));
        reg(new LolItem("galeforce",       "Galeforce",         3100, Material.FEATHER,         DAMAGE).ad(55).as(0.15).crit(0.20).ms(5).active("Cloudburst","Actif: dash + projectiles."));
        reg(new LolItem("trinity_force",   "Trinity Force",     3333, Material.NETHER_STAR,     DAMAGE).ad(36).hp(333).as(0.30).ah(15).ms(4).passive("Spellblade","Après sort: AA +200%% AD base."));
        reg(new LolItem("steraks_gage",    "Sterak's Gage",     3100, Material.IRON_CHESTPLATE, DAMAGE).ad(50).hp(400).passive("Lifeline","HP <30%%: bouclier 75%%AD bonus."));
        reg(new LolItem("botrk",           "Blade of the Ruined King",3200,Material.GOLDEN_AXE,ATTACK_SPEED).ad(40).as(0.25).lifeSteal(0.12).active("Mist's Edge","AA: 6%% HP actuels."));
        reg(new LolItem("rageblade",       "Guinsoo's Rageblade",2700,Material.BLAZE_ROD,       ATTACK_SPEED).ap(30).as(0.40).passive("Wrath","Crits = 2x on-hit."));
        reg(new LolItem("phantom_dancer",  "Phantom Dancer",    2900, Material.ELYTRA,          ATTACK_SPEED).as(0.30).crit(0.20).ms(7));
        reg(new LolItem("wits_end",        "Wit's End",         3100, Material.DIAMOND_AXE,     ATTACK_SPEED).as(0.40).mr(40).ms(5).passive("Fray","+42 dégâts magiques on-hit."));
        reg(new LolItem("nashors_tooth",   "Nashor's Tooth",    3000, Material.BONE,            ATTACK_SPEED).ap(100).as(0.50).ah(15).passive("Bite","+15+20%%AP on-hit."));
        reg(new LolItem("kraken_slayer",   "Kraken Slayer",     3000, Material.TRIDENT,         DAMAGE).ad(40).as(0.30).crit(0.20).passive("Bring It Down","3ème AA: +150 vrais dégâts."));
        reg(new LolItem("bloodthirster",   "Bloodthirster",     3400, Material.RED_CONCRETE,    DAMAGE).ad(80).crit(0.20).lifeSteal(0.18).passive("Ichorshield","Vol de vie excédentaire = bouclier."));

        // AP
        reg(new LolItem("rabadons_deathcap","Rabadon's Deathcap",3600,Material.POINTED_DRIPSTONE,MAGE).ap(120).passive("Amplification","+35%% AP total."));
        reg(new LolItem("shadowflame",     "Shadowflame",       3000, Material.BLAZE_POWDER,    MAGE).ap(100).hp(250).passive("Cinderbloom","Sorts: jusqu'à +20%% dégâts sur boucliers/bas HP."));
        reg(new LolItem("ludens_companion","Luden's Companion", 3000, Material.AMETHYST_SHARD,  MAGE).ap(90).mana(600).ah(20).passive("Surge","Éclair toutes 12s: 100+15%%AP."));
        reg(new LolItem("zhonyas_hourglass","Zhonya's Hourglass",2900,Material.CLOCK,           MAGE).ap(65).armor(45).ah(10).active("Stasis","Actif: invulnérabilité 2.5s."));
        reg(new LolItem("void_staff",      "Void Staff",        3000, Material.END_ROD,         MAGE).ap(100).magicPen(0.40).passive("Dissolve","+40%% pénétration magique."));
        reg(new LolItem("stormsurge",      "Stormsurge",        3000, Material.LIGHTNING_ROD,   MAGE).ap(95).ms(5).lethality(10).passive("Stormraider","Kill: foudre voisins."));
        reg(new LolItem("lich_bane",       "Lich Bane",         3100, Material.PRISMARINE_CRYSTALS,MAGE).ap(80).mana(250).ms(5).ah(10).passive("Spellblade","Après sort: AA +150%%AD+40%%AP."));
        reg(new LolItem("cosmic_drive",    "Cosmic Drive",      3000, Material.NETHER_BRICK,    MAGE).ap(90).hp(300).ms(5).ah(30).passive("Spelldance","Après sort: +20%% MS 2s."));
        reg(new LolItem("archangels_staff","Archangel's Staff", 3400, Material.STICK,           MAGE).ap(100).mana(600).ah(25).passive("Awe","+1%% AP/100 mana."));
        reg(new LolItem("rocketbelt",      "Hextech Rocketbelt",3200, Material.FIREWORK_ROCKET, MAGE).ap(90).hp(250).ms(5).active("Supersonic","Actif: dash + missiles."));
        reg(new LolItem("banshees_veil",   "Banshee's Veil",    3100, Material.BLUE_STAINED_GLASS,MAGE).ap(90).mr(50).passive("Annul","Bouclier: bloque 1 sort."));

        // TANK
        reg(new LolItem("warmogs_armor",   "Warmog's Armor",    3100, Material.RED_WOOL,        TANK).hp(800).hpRegen(200).passive("Warmog's Heart",">1000 HP bonus: 5%% HP/s."));
        reg(new LolItem("sunfire_aegis",   "Sunfire Aegis",     3100, Material.MAGMA_BLOCK,     TANK).hp(400).armor(50).mr(25).passive("Immolate","12-30 dégâts/s AoE."));
        reg(new LolItem("thornmail",       "Thornmail",         2700, Material.IRON_BARS,       TANK).hp(350).armor(60).passive("Thornmail","Réfléchit dégâts aux attaquants."));
        reg(new LolItem("gargoyle_stoneplate","Gargoyle Stoneplate",3300,Material.STONE,        TANK).hp(400).armor(60).mr(60).active("Stone Skin","Actif: +100%% HP bonus temp."));
        reg(new LolItem("heartsteel",      "Heartsteel",        3000, Material.RED_CONCRETE,    TANK).hp(800).hpRegen(50).passive("Colossal Consumption","Stack HP."));
        reg(new LolItem("jaksho",          "Jak'Sho, The Protean",3400,Material.ENDER_EYE,     TANK).hp(400).armor(40).mr(40).passive("Voidborn Resilience","+5 armure/MR par stack."));
        reg(new LolItem("force_of_nature","Force of Nature",    2900, Material.GRASS_BLOCK,     TANK).hp(350).mr(60).ms(5).passive("Absorb","Dégâts magiques: +6 MR/stack."));
        reg(new LolItem("randuins_omen",   "Randuin's Omen",    2700, Material.IRON_CHESTPLATE, TANK).hp(400).armor(80).passive("Cold Steel","-15%% AS attaquants."));
        reg(new LolItem("dead_mans_plate", "Dead Man's Plate",  2900, Material.CHAINMAIL_CHESTPLATE,TANK).hp(300).armor(45).ms(5).passive("Shipwrecker","AA avec stacks: ralentit."));
        reg(new LolItem("abyssal_mask",    "Abyssal Mask",      2650, Material.PURPLE_WOOL,     TANK).hp(500).mr(50).mana(300).ah(10).passive("Unmake","Ennemis proches: -15%% MR."));

        // SUPPORT
        reg(new LolItem("redemption",      "Redemption",        2300, Material.BEACON,          SUPPORT).hp(300).mana(300).hpRegen(100).ah(20).active("Actif","Soigne 250+4%%HP zone."));
        reg(new LolItem("locket",          "Locket of the Iron Solari",2200,Material.GOLDEN_CHESTPLATE,SUPPORT).hp(200).armor(30).mr(30).ah(15).active("Devotion","Actif: bouclier alliés."));
        reg(new LolItem("shurelyas",       "Shurelya's Battlesong",2500,Material.MUSIC_DISC_13,SUPPORT).hp(200).mana(300).ah(20).ms(5).active("Inspire","+60%% vitesse alliés 4s."));
        reg(new LolItem("mikaels",         "Mikael's Blessing", 2300, Material.AMETHYST_CLUSTER,SUPPORT).hp(250).mana(300).mr(40).active("Purify","Cleanse CC allié."));
        reg(new LolItem("staff_flowing_water","Staff of Flowing Water",2300,Material.BAMBOO,   SUPPORT).ap(60).mana(300).ah(10).ms(5).passive("Rapids","Soin allié: +AS +AP."));
        reg(new LolItem("moonstone_renewer","Moonstone Renewer",2400, Material.WHITE_CONCRETE,  SUPPORT).hp(250).mana(400).hpRegen(150).ah(20).passive("Starlit Grace","+15%% puissance soin."));
        reg(new LolItem("ardent_censer",   "Ardent Censer",     2300, Material.GOLDEN_SWORD,   SUPPORT).ap(60).mana(300).ah(10).passive("Cense","Soin: +AS +dégâts on-hit."));

        // BOOTS
        reg(new LolItem("boots_speed",     "Boots",             300,  Material.LEATHER_BOOTS,   UTILITY).ms(25));
        reg(new LolItem("sorcerers_shoes", "Sorcerer's Shoes",  1100, Material.LEATHER_BOOTS,   MAGE).ms(45).flatMagicPen(18));
        reg(new LolItem("plated_steelcaps","Plated Steelcaps",  1100, Material.IRON_BOOTS,      TANK).ms(45).armor(20).aaReduction(0.12).passive("Plating","-12%% dégâts AA."));
        reg(new LolItem("mercurys_treads", "Mercury's Treads",  1100, Material.CHAINMAIL_BOOTS, TANK).ms(45).mr(25).tenacity(0.30).passive("Tenacity","-30%% durée des CC."));
        reg(new LolItem("berserkers_greaves","Berserker's Greaves",1100,Material.GOLDEN_BOOTS,  ATTACK_SPEED).ms(45).as(0.35));
        reg(new LolItem("ionian_boots",    "Ionian Boots of Lucidity",950,Material.DIAMOND_BOOTS,UTILITY).ms(45).ah(15));

        // SPÉCIAUX
        reg(new LolItem("guardian_angel",  "Guardian Angel",    3200, Material.TOTEM_OF_UNDYING,UTILITY).ad(40).armor(40).passive("Rebirth","Résurrection 50%% HP."));
        reg(new LolItem("maw",             "Maw of Malmortius", 2900, Material.DARK_OAK_SWORD,  DAMAGE).ad(55).mr(50).ah(15).passive("Lifeline","HP <30%%: bouclier anti-magie."));
        reg(new LolItem("hexdrinker",      "Hexdrinker",        1300, Material.STONE_AXE,       DAMAGE).ad(25).mr(35).passive("Lifeline","Bouclier sorts."));
        reg(new LolItem("spear_of_shojin2","Spear of Shojin",  3100, Material.STICK,           DAMAGE).ad(55).hp(300).ah(20).passive("Dragonforce","3 AA réduisent CD."));


        // ════════════════════════════════════════════════════════════
        // CONSOMMABLES & WARDS (prix officiels LoL)
        // ════════════════════════════════════════════════════════════

        reg(new LolItem("health_potion2",      "Potion de vie",            50,  Material.POTION,            CONSUMABLE).passive("Actif","Soigne 150 HP sur 15s. Max 5 en inventaire."));
        reg(new LolItem("refillable_potion2",  "Fiole rechargeable",       150, Material.GLASS_BOTTLE,      CONSUMABLE).passive("Actif","2 charges — Soigne 125 HP en 12s."));
        reg(new LolItem("biscuit_will",        "Biscuit de la Volonté",    0,   Material.COOKIE,            CONSUMABLE).passive("Actif","Soigne 150 HP + 100 mana. +50 mana max si mana plein."));
        reg(new LolItem("elixir_wrath2",       "Élixir de Fureur",         500, Material.DRAGON_BREATH,     CONSUMABLE).passive("Actif","3min: +30 AD + vol de vie physique."));
        reg(new LolItem("elixir_iron2",        "Élixir de Fer",            500, Material.FERMENTED_SPIDER_EYE,CONSUMABLE).passive("Actif","3min: +300 HP + Ténacité."));
        reg(new LolItem("elixir_sorcery2",     "Élixir de Sorcellerie",    500, Material.MAGENTA_DYE,       CONSUMABLE).passive("Actif","3min: +50 AP + mana/s."));
        reg(new LolItem("control_ward2",       "Sentinelle de contrôle",   75,  Material.PINK_CANDLE,       CONSUMABLE).passive("Actif","Révèle et désactive wards ennemies. Max 2."));
        reg(new LolItem("stealth_ward2",       "Totem furtif",             0,   Material.SOUL_TORCH,        CONSUMABLE).passive("Actif","Place ward invisible 60-150s. 3 charges."));
        reg(new LolItem("oracle_lens2",        "Lentille Oracle",          0,   Material.SPYGLASS,          CONSUMABLE).passive("Actif","Révèle wards proches 10s. Dispo niv 3."));
        reg(new LolItem("farsight2",           "Vision Lointaine",         0,   Material.COMPASS,           CONSUMABLE).passive("Actif","Ward révélatrice longue portée (1 HP)."));
        reg(new LolItem("cappa_juice2",        "Cappa Juice",              700, Material.HONEY_BOTTLE,      CONSUMABLE).passive("Actif","Soigne 40%% HP max sur 120s."));


        // ════════════════════════════════════════════════════════════
        // ITEMS OFFICIELS MANQUANTS (57 items — patch 16.12)
        // ════════════════════════════════════════════════════════════

        // ── LEGENDARIES manquants ──
        reg(new LolItem("deaths_dance",       "Death's Dance",            3300, Material.NETHERITE_SWORD,    DAMAGE).ad(65).armor(40).ah(15).passive("Cauterize","Soins retardés: 30%% dégâts → soin sur 3s.").passive("Dance","Kill/assist soigne 15%% HP max."));
        reg(new LolItem("stridebreaker",      "Stridebreaker",            3300, Material.DIAMOND_AXE,     DAMAGE).ad(55).hp(300).as(0.20).ah(20).active("Halting Slash","Actif: AoE qui ralentit 60%% 1s."));
        reg(new LolItem("goredrinker",        "Goredrinker",              3200, Material.NETHERITE_SWORD, DAMAGE).ad(55).hp(350).ah(20).omnivamp(0.08).active("Thirsting Slash","Actif: fente AoE, soigne 15+10%%HP man."));
        reg(new LolItem("mercurial_scimitar", "Mercurial Scimitar",       3200, Material.GOLDEN_SWORD,    DAMAGE).ad(50).mr(30).lifeSteal(0.10).active("Quicksilver","Actif: cleanse tous CC (90s CD)."));
        reg(new LolItem("stormrazor",         "Stormrazor",               3200, Material.SPECTRAL_ARROW,  DAMAGE).ad(60).crit(0.20).ms(5).passive("Paralyze","1ère AA: ralentit 99%% 0.5s (18s CD)."));
        reg(new LolItem("dusk_and_dawn",      "Dusk and Dawn",            3100, Material.PURPLE_DYE,      MAGE).ap(70).ah(20).passive("Spellblade","Après sort: AA double effet on-hit."));
        reg(new LolItem("yun_tal_wildarrows", "Yun Tal Wildarrows",       3100, Material.BOW,             ATTACK_SPEED).ad(60).crit(0.20).as(0.15).passive("Serration","Crits: DoT 60%% AD physiques sur 3s."));
        reg(new LolItem("cryptbloom",         "Cryptbloom",               3000, Material.FERN,    MAGE).ap(80).mr(40).magicPen(0.30).passive("Noxious Bloom","Kill/assist: zone qui soigne alliés."));
        reg(new LolItem("experimental_hexplate","Experimental Hexplate",  3000, Material.COPPER_INGOT,    DAMAGE).ad(50).hp(300).ah(25).passive("Supersonic","Après ultime: +30%% AS + 15%% MS 7s."));
        reg(new LolItem("hextech_gunblade",   "Hextech Gunblade",         3000, Material.BLAZE_ROD,       MAGE).ad(45).ap(65).lifeSteal(0.10).omnivamp(0.10).passive("Spellblade AP","Sorts soignent aussi via AD."));
        reg(new LolItem("liandry_torment",    "Liandry's Torment",        3000, Material.CAMPFIRE,        MAGE).ap(90).mana(300).ah(25).passive("Torment","Dégâts sorts: brûlure 1%%HP max/s 4s."));
        reg(new LolItem("rite_of_ruin",       "Rite of Ruin",             3000, Material.OBSIDIAN,        TANK).hp(400).armor(40).mr(40).passive("Rite","Dégâts sorts: -3%% résistances/stack (max 10)."));
        reg(new LolItem("silvermere_dawn",    "Silvermere Dawn",          3000, Material.LIGHT_BLUE_DYE,  UTILITY).ad(50).mr(40).tenacity(0.30).active("Quicksilver","Actif: cleanse CC + +60%% tenacité 3s."));
        reg(new LolItem("terminus",           "Terminus",                 3000, Material.CHAIN,           ATTACK_SPEED).ad(30).as(0.40).passive("Juxtaposition","AA alternate Light/Dark: armure/MR ou pén."));
        reg(new LolItem("duskblade",          "Duskblade of Draktharr",   2950, Material.OBSIDIAN,        DAMAGE).ad(65).lethality(20).ah(20).passive("Nightstalker","Après invis: prochain AA +0-200 dégâts."));
        reg(new LolItem("eclipse",            "Eclipse",                  2900, Material.ORANGE_DYE,      DAMAGE).ad(55).armor(15).lifeSteal(0.08).passive("Ever Rising Moon","2 AA/1.5s: bouclier + 15%% MS."));
        reg(new LolItem("kaenic_rookern",     "Kaenic Rookern",           2900, Material.WHITE_WOOL,      TANK).hp(400).mr(80).passive("Nirvana","Hors combat: bouclier 200+150%%MR."));
        reg(new LolItem("crown_shattered_queen","Crown of the Shattered Queen",2865,Material.EMERALD,    MAGE).ap(80).hp(250).mana(600).ah(20).passive("Queendom","Au combat: bouclier 250+15%%HP max 5s."));
        reg(new LolItem("everfrost",          "Everfrost",                2865, Material.ICE,             MAGE).ap(75).mana(600).ah(20).hp(250).active("Glaciate","Actif: rayon gelant 550 dégâts + root."));
        reg(new LolItem("morellonomicon",     "Morellonomicon",           2850, Material.NETHERRACK,      MAGE).ap(90).hp(250).passive("Affliction","Dégâts magiques: -50%% soins 5s si ennemi <50%% HP."));
        reg(new LolItem("prowlers_claw",      "Prowler's Claw",           2850, Material.DARK_OAK_SWORD,  DAMAGE).ad(60).lethality(18).ah(20).active("Sandswipe","Actif: dash + 15%% dégâts bonus 3s."));
        reg(new LolItem("hollow_radiance",    "Hollow Radiance",          2800, Material.PURPLE_STAINED_GLASS,TANK).hp(350).armor(25).mr(50).ah(15).passive("Immolate","12-40 dégâts/s AoE. Ralentit 30%%."));
        reg(new LolItem("umbral_glaive",      "Umbral Glaive",            2800, Material.DARK_OAK_SWORD,  DAMAGE).ad(50).lethality(15).ah(20).passive("Blackout","AA: détecte et détruit wards proches."));
        reg(new LolItem("night_harvester",    "Night Harvester",          2765, Material.WITHER_ROSE,     MAGE).ap(80).ms(5).ah(15).passive("Soulrend","1ère fois/12s sur champ: 75+12%%AP dégâts AoE."));
        reg(new LolItem("ludens_echo",        "Luden's Echo",             2750, Material.AMETHYST_SHARD,  MAGE).ap(90).mana(600).ah(20).passive("Surge","Toutes 12s: sort → éclair 100+15%%AP."));
        reg(new LolItem("shield_rakkor",      "Shield of the Rakkor",     2675, Material.SHIELD,     TANK).hp(400).armor(60).passive("Reverence","AA sur toi: réfléchit 15+5%%armure dégâts."));
        reg(new LolItem("navori_flickerblade","Navori Flickerblade",      2650, Material.FEATHER,         ATTACK_SPEED).ad(55).crit(0.20).as(0.25).passive("Transcendence","Crits: réduit CD sorts de 15%%."));
        reg(new LolItem("radiant_virtue",     "Radiant Virtue",           2600, Material.GLOWSTONE,       SUPPORT).hp(400).hpRegen(200).ah(20).passive("Noblesse","Ultime: soigne alliés 6%%HP max + MS 8s."));
        reg(new LolItem("rod_of_ages",        "Rod of Ages",              2600, Material.BAMBOO,           MAGE).ap(40).hp(300).mana(400).passive("Eternity","Stacks mensuels +20HP+20mana. Max 10."));
        reg(new LolItem("rylais_crystal_scepter","Rylai's Crystal Scepter",2600,Material.PINK_WOOL,MAGE).ap(75).hp(350).passive("Crystal Scepter","Sorts: ralentissent 25%% 1s."));
        reg(new LolItem("protoplasm_harness","Protoplasm Harness",        2500, Material.SLIME_BLOCK,      TANK).hp(450).armor(30).mr(30).passive("Protoplasm","AA: stacks. 6 stacks → AoE."));
        reg(new LolItem("trailblazer",        "Trailblazer",              2400, Material.SUGAR_CANE,       UTILITY).hp(300).ms(8).ah(15).passive("Trailblaze","Ennemis traversant: ralentissent. Alliés: +MS."));
        reg(new LolItem("winters_approach",   "Winter's Approach",        2400, Material.BLUE_ICE,         UTILITY).hp(400).mana(500).passive("Awe","+1%%HP par 100 mana bonus. Stacks AA/sorts."));
        reg(new LolItem("chemtech_putrifier","Chemtech Putrifier",        1900, Material.POISONOUS_POTATO, SUPPORT).ap(40).mana(200).ah(20).passive("Puffcap Toxin","Sorts: -60%% soins ennemis 3s."));

        // ── EPICS manquants ──
        reg(new LolItem("seekers_armguard",   "Seeker's Armguard",        1600, Material.IRON_CHESTPLATE, MAGE).ap(20).armor(30));
        reg(new LolItem("verdant_barrier",    "Verdant Barrier",          1600, Material.GREEN_DYE,       MAGE).ap(20).mr(30));
        reg(new LolItem("mejais_soulstealer", "Mejai's Soulstealer",      1500, Material.BOOK,            MAGE).ap(20).ms(5).passive("Dread","Stacks AP: +5 par kill, +2 assist. Max 25 stacks = +100 AP."));
        reg(new LolItem("the_brutalizer",     "The Brutalizer",           1337, Material.IRON_AXE,        DAMAGE).ad(25).lethality(10).ah(10));
        reg(new LolItem("quicksilver_sash",   "Quicksilver Sash",         1300, Material.CYAN_STAINED_GLASS,UTILITY).mr(30).active("Quicksilver","Actif: cleanse CC instantané (90s CD)."));
        reg(new LolItem("lost_chapter",       "Lost Chapter",             1200, Material.WRITTEN_BOOK,    MAGE).ap(25).mana(300).ah(10));
        reg(new LolItem("tiamat",             "Tiamat",                   1200, Material.IRON_AXE,        DAMAGE).ad(25).hpRegen(75).active("Crescent","AA: AoE 60%% dégâts latéraux."));
        reg(new LolItem("tunneler",           "Tunneler",                 1150, Material.STONE_PICKAXE,   TANK).hp(250).armor(20).passive("Charge","+MS vers tour ennemie."));
        reg(new LolItem("rageknife",          "Rageknife",                1100, Material.STICK,           ATTACK_SPEED).as(0.35).passive("Phantom Hit","Toutes les 2 AA: applique on-hit ×2."));
        reg(new LolItem("hextech_alternator", "Hextech Alternator",       1100, Material.COPPER_INGOT,    MAGE).ap(40).passive("Disrupt","Après 10s hors dégâts: +60 dégâts magiques sort/AA."));
        reg(new LolItem("ironspike_whip",     "Ironspike Whip",           1100, Material.GOLDEN_PICKAXE,  DAMAGE).ad(25).hpRegen(50).passive("Crescent","AA: AoE côtés."));
        reg(new LolItem("steel_sigil",        "Steel Sigil",              1100, Material.CHAIN,           DAMAGE).ad(20).crit(0.15));
        reg(new LolItem("lifewell_pendant",   "Lifewell Pendant",         1050, Material.GHAST_TEAR,      TANK).hp(200).hpRegen(75));
        reg(new LolItem("chalice_blessing",   "Chalice of Blessing",      900,  Material.GLASS_BOTTLE,    SUPPORT).mana(300).manaRegen(50).passive("Font of Life","Soin allié marqué: +50+3%%mana HP."));
        reg(new LolItem("fated_ashes",        "Fated Ashes",              900,  Material.BLAZE_POWDER,    MAGE).ap(25).passive("Immolate","12 dégâts/s AoE faibles."));
        reg(new LolItem("glacial_buckler",    "Glacial Buckler",          900,  Material.PACKED_ICE,      TANK).armor(30).mana(250).ah(10));
        reg(new LolItem("executioners_calling","Executioner's Calling",   800,  Material.IRON_SWORD,      DAMAGE).ad(20).crit(0.10).passive("Executioner","Dégâts physiques: -40%% soins cible 3s."));
        reg(new LolItem("rectrix",            "Rectrix",                  775,  Material.GOLD_INGOT,ATTACK_SPEED).as(0.20).ms(5));
        reg(new LolItem("forbidden_idol",     "Forbidden Idol",           600,  Material.TOTEM_OF_UNDYING,SUPPORT).manaRegen(50).passive("Forbidden","+8%% puissance soin/bouclier."));
        reg(new LolItem("scouts_slingshot",   "Scout's Slingshot",        600,  Material.BOW,             UTILITY).passive("Actif","Envoie une ward révélatrice à longue portée."));

        // ── BASICS manquants ──
        reg(new LolItem("null_magic_mantle",  "Null-Magic Mantle",        400,  Material.LIGHT_GRAY_DYE,  TANK).mr(25));
        reg(new LolItem("tear_of_goddess",    "Tear of the Goddess",      400,  Material.WATER_BUCKET,    UTILITY).mana(240).manaRegen(25).passive("Awe","Stacks mana sur AA/sorts."));
        reg(new LolItem("dark_seal",          "Dark Seal",                350,  Material.BLACKSTONE,      MAGE).ap(15).hp(60).passive("Dread","Stacks AP par kill. Perd 4 stacks à la mort."));

        System.out.println("[ItemRegistry] " + ITEMS.size() + " items chargés.");
    }

    private static void reg(LolItem item) { ITEMS.put(item.getId(), item); }
    public static LolItem get(String id)  { return ITEMS.get(id); }
    public static Collection<LolItem> all()             { return ITEMS.values(); }
    public static Map<String, LolItem> getAll()         { return ITEMS; }
    public static boolean exists(String id)             { return ITEMS.containsKey(id); }
    public static List<LolItem> byCategory(LolItem.ItemCategory cat) {
        return ITEMS.values().stream().filter(i -> i.getCategory() == cat).toList();
    }
}
