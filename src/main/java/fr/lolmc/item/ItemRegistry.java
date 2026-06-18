package fr.lolmc.item;

import org.bukkit.Material;

import java.util.*;

import static fr.lolmc.item.LolItem.ItemCategory.*;

/**
 * Registre de tous les items LoL avec leurs vraies stats officielles (2025).
 * Sources: wiki.leagueoflegends.com, patch 15.x
 */
public class ItemRegistry {

    private static final Map<String, LolItem> ITEMS = new LinkedHashMap<>();

    static {
        // ══════════════════════════════════════════════════════════════
        // ITEMS D'ATTAQUE (AD / Crit / Létalité)
        // ══════════════════════════════════════════════════════════════

        // ── Infinity Edge ─────────────────────────────────────────────
        register(new LolItem("infinity_edge", "Infinity Edge", 3500, Material.NETHERITE_SWORD, DAMAGE)
            .ad(75).crit(0.20).critDmg(0.30)
            .passive("Perfection", "+30% dégâts critiques. Tes critiques infligent 215% AD au lieu de 175%."));

        // ── Kraken Slayer ──────────────────────────────────────────────
        register(new LolItem("kraken_slayer", "Kraken Slayer", 3000, Material.TRIDENT, DAMAGE)
            .ad(40).as(0.30).crit(0.20)
            .passive("Bring It Down", "Toutes les 3 attaques: +150 dégâts vrais on-hit."));

        // ── Ravenous Hydra ────────────────────────────────────────────
        register(new LolItem("ravenous_hydra", "Ravenous Hydra", 3300, Material.IRON_AXE, DAMAGE)
            .ad(70).hpRegen(150).lifeSteal(0.15).ah(20)
            .passive("Voracité", "AA et sorts infligent 40-60% dégâts aux ennemis proches."));

        // ── Black Cleaver ─────────────────────────────────────────────
        register(new LolItem("black_cleaver", "Black Cleaver", 3100, Material.IRON_SWORD, DAMAGE)
            .ad(40).hp(400).ah(30)
            .passive("Carve", "Dégâts physiques: -6% armure/stack (max 6 stacks = -36% armure)."));

        // ── Youmuu's Ghostblade ────────────────────────────────────────
        register(new LolItem("youmuus_ghostblade", "Youmuu's Ghostblade", 2700, Material.GOLDEN_SWORD, DAMAGE)
            .ad(60).lethality(18).ms(20)
            .passive("Haunt", "+20 vitesse mouvement hors combat.")
            );

        // ── Serylda's Grudge ──────────────────────────────────────────
        register(new LolItem("seryldas_grudge", "Serylda's Grudge", 3200, Material.STONE_SWORD, DAMAGE)
            .ad(45).lethality(30).ah(20)
            .passive("Bitter Cold", "Dégâts de sorts et d'attaques: ralentit 30% pendant 1s."));

        // ── Lord Dominik's Regards ────────────────────────────────────
        register(new LolItem("lord_dominiks", "Lord Dominik's Regards", 3000, Material.DIAMOND_SWORD, DAMAGE)
            .ad(35).crit(0.20).lethality(10)
            .passive("Giant Slayer", "+35% pénétration d'armure. +0-15% dégâts si cible a plus de HP."));

        // ── Galeforce ─────────────────────────────────────────────────
        register(new LolItem("galeforce", "Galeforce", 3100, Material.FEATHER, DAMAGE)
            .ad(55).as(0.15).crit(0.20).ms(5)
            .passive("Cloudburst", "Actif: dash + 3 projectiles (200+60% AD dégâts physiques, 90s CD)."));

        // ── Trinity Force ─────────────────────────────────────────────
        register(new LolItem("trinity_force", "Trinity Force", 3333, Material.NETHER_STAR, DAMAGE)
            .ad(36).hp(333).as(0.30).ah(15).ms(4)
            .passive("Spellblade", "Après sort: prochain AA inflige +200% AD base en dégâts bonus.")
            );

        // ── Sterak's Gage ─────────────────────────────────────────────
        register(new LolItem("steraks_gage", "Sterak's Gage", 3100, Material.IRON_CHESTPLATE, DAMAGE)
            .ad(50).hp(400)
            .passive("Lifeline", "À <30% HP: bouclier +75% AD bonus pendant 4s (45s CD)."));

        // ── Blade of the Ruined King ──────────────────────────────────
        register(new LolItem("botrk", "Blade of the Ruined King", 3200, Material.GOLDEN_AXE, ATTACK_SPEED)
            .ad(40).as(0.25).lifeSteal(0.12)
            .passive("Mist's Edge", "AA infligent 6% HP actuels en dégâts physiques bonus (min 15)."));

        // ── Guinsoo's Rageblade ────────────────────────────────────────
        register(new LolItem("rageblade", "Guinsoo's Rageblade", 2700, Material.BLAZE_ROD, ATTACK_SPEED)
            .ap(30).as(0.40)
            .passive("Wrath", "Les crits infligent 0% dégâts mais appliquent 2x effets on-hit."));

        // ── Phantom Dancer ────────────────────────────────────────────
        register(new LolItem("phantom_dancer", "Phantom Dancer", 2900, Material.ELYTRA, ATTACK_SPEED)
            .as(0.30).crit(0.20).ms(0.07)
            .passive("Spectral Waltz", "AA: ghosting 2s et +7% vitesse. 5 AA: +40% vitesse attaque 2s."));

        // ── Wit's End ─────────────────────────────────────────────────
        register(new LolItem("wits_end", "Wit's End", 3100, Material.DIAMOND_AXE, ATTACK_SPEED)
            .as(0.40).mr(40).ms(5)
            .passive("Mana Shred", "AA volent 42 dégâts magiques et 30 mana."));

        // ── Nashor's Tooth ────────────────────────────────────────────
        register(new LolItem("nashors_tooth", "Nashor's Tooth", 3000, Material.BONE, ATTACK_SPEED)
            .ap(100).as(0.50).ah(15)
            .passive("Bite", "AA infligent 15+20% AP dégâts magiques bonus."));

        // ══════════════════════════════════════════════════════════════
        // ITEMS MAGIQUES (AP / Pénétration / AH)
        // ══════════════════════════════════════════════════════════════

        // ── Rabadon's Deathcap ────────────────────────────────────────
        register(new LolItem("rabadons_deathcap", "Rabadon's Deathcap", 3600, Material.POINTED_DRIPSTONE, MAGE)
            .ap(120)
            .passive("Amplification", "+35% AP total (multiplicatif)."));

        // ── Shadowflame ───────────────────────────────────────────────
        register(new LolItem("shadowflame", "Shadowflame", 3000, Material.BLAZE_POWDER, MAGE)
            .ap(100).hp(250).lethality(10)
            .passive("Cinderbloom", "Sorts infligent jusqu'à +20% dégâts magiques si cible a bouclier ou <35% HP."));

        // ── Luden's Companion ─────────────────────────────────────────
        register(new LolItem("ludens_companion", "Luden's Companion", 3000, Material.AMETHYST_SHARD, MAGE)
            .ap(90).mana(600).ah(20)
            .passive("Surge", "Toutes les 12s: prochain sort envoie un éclair (100+15% AP dégâts magiques)."));

        // ── Zhonya's Hourglass ────────────────────────────────────────
        register(new LolItem("zhonyas_hourglass", "Zhonya's Hourglass", 2900, Material.CLOCK, MAGE)
            .ap(65).armor(45).ah(10)
            .passive("Stasis", "Actif: invulnérabilité 2.5s (120s CD)."));

        // ── Void Staff ────────────────────────────────────────────────
        register(new LolItem("void_staff", "Void Staff", 3000, Material.END_ROD, MAGE)
            .ap(100).magicPen(0.40)
            .passive("Dissolve", "+40% pénétration magique."));

        // ── Stormsurge ────────────────────────────────────────────────
        register(new LolItem("stormsurge", "Stormsurge", 3000, Material.LIGHTNING_ROD, MAGE)
            .ap(95).ms(5).lethality(10)
            .passive("Stormraider", "Tuer un champion: foudre sur les ennemis proches (300+25% AP)."));

        // ── Lich Bane ─────────────────────────────────────────────────
        register(new LolItem("lich_bane", "Lich Bane", 3100, Material.PRISMARINE_CRYSTALS, MAGE)
            .ap(80).mana(250).ms(5).ah(10)
            .passive("Spellblade", "Après sort: prochain AA inflige 150% AD base + 40% AP dégâts magiques."));

        // ── Cosmic Drive ──────────────────────────────────────────────
        register(new LolItem("cosmic_drive", "Cosmic Drive", 3000, Material.NETHER_BRICK, MAGE)
            .ap(90).hp(300).ms(5).ah(30)
            .passive("Spelldance", "Après sort: +20% vitesse déplacement 2s."));

        // ── Archangel's Staff ─────────────────────────────────────────
        register(new LolItem("archangels_staff", "Archangel's Staff", 3400, Material.STICK, MAGE)
            .ap(100).mana(600).ah(25)
            .passive("Awe", "+1% AP pour chaque 100 mana. Stacks mana jusqu'à +500."));

        // ── Hextech Rocketbelt ────────────────────────────────────────
        register(new LolItem("rocketbelt", "Hextech Rocketbelt", 3200, Material.FIREWORK_ROCKET, MAGE)
            .ap(90).hp(250).ms(5)
            .passive("Supersonic", "Actif: dash + 3 missiles (75+15% AP chacun, 90s CD)."));

        // ══════════════════════════════════════════════════════════════
        // ITEMS TANK (HP / Armure / MR)
        // ══════════════════════════════════════════════════════════════

        // ── Warmog's Armor ────────────────────────────────────────────
        register(new LolItem("warmogs_armor", "Warmog's Armor", 3100, Material.RED_WOOL, TANK)
            .hp(800).hpRegen(200)
            .passive("Warmog's Heart", "Si >1000 HP bonus: régénère 5% HP max/s hors combat."));

        // ── Sunfire Aegis ─────────────────────────────────────────────
        register(new LolItem("sunfire_aegis", "Sunfire Aegis", 3100, Material.MAGMA_BLOCK, TANK)
            .hp(400).armor(50).mr(25)
            .passive("Immolate", "Aura: 12-30 dégâts magiques/s aux ennemis proches. +10% par stack (max 6)."));

        // ── Thornmail ─────────────────────────────────────────────────
        register(new LolItem("thornmail", "Thornmail", 2700, Material.IRON_BARS, TANK)
            .hp(350).armor(60)
            .passive("Thornmail", "Réfléchit 25 + 10% armure en dégâts magiques aux attaquants."));

        // ── Gargoyle Stoneplate ───────────────────────────────────────
        register(new LolItem("gargoyle_stoneplate", "Gargoyle Stoneplate", 3300, Material.STONE, TANK)
            .hp(400).armor(60).mr(60)
            .passive("Stone Skin", "Actif: +100% HP bonus temporaire, -60% dégâts infligés (90s CD)."));

        // ── Heartsteel ────────────────────────────────────────────────
        register(new LolItem("heartsteel", "Heartsteel", 3000, Material.NETHER_HEART, TANK)
            .hp(800).hpRegen(50)
            .passive("Colossal Consumption", "Toutes les 3s en combat: stack +5 HP max. Dégâts = 100% HP stacked."));

        // ── Jak'Sho, The Protean ──────────────────────────────────────
        register(new LolItem("jaksho", "Jak'Sho, The Protean", 3400, Material.ENDER_EYE, TANK)
            .hp(400).armor(40).mr(40)
            .passive("Voidborn Resilience", "+5 armure/MR par stack en combat (max 8). Stacks → +permanents à mort ennemie."));

        // ── Force of Nature ───────────────────────────────────────────
        register(new LolItem("force_of_nature", "Force of Nature", 2900, Material.GRASS_BLOCK, TANK)
            .hp(350).mr(60).ms(5)
            .passive("Absorb", "Prendre dégâts magiques: +6 MR 5s (max 10 stacks)."));

        // ── Randuin's Omen ────────────────────────────────────────────
        register(new LolItem("randuins_omen", "Randuin's Omen", 2700, Material.IRON_CHESTPLATE, TANK)
            .hp(400).armor(80)
            .passive("Cold Steel", "Être attaqué réduit AS attaquant de 15%.")
            );

        // ── Dead Man's Plate ──────────────────────────────────────────
        register(new LolItem("dead_mans_plate", "Dead Man's Plate", 2900, Material.CHAINMAIL_CHESTPLATE, TANK)
            .hp(300).armor(45).ms(5)
            .passive("Shipwrecker", "+20 vitesse hors combat. AA avec >50 stacks: ralentit 50% 1s."));

        // ── Abyssal Mask ──────────────────────────────────────────────
        register(new LolItem("abyssal_mask", "Abyssal Mask", 2500, Material.PURPLE_WOOL, TANK)
            .hp(500).mr(50).mana(300)
            .passive("Unmake", "Ennemis proches: -15% MR. Alliés: +20% dégâts magiques sur ces ennemis."));

        // ══════════════════════════════════════════════════════════════
        // ITEMS SUPPORT / UTILITÉ
        // ══════════════════════════════════════════════════════════════

        // ── Redemption ────────────────────────────────────────────────
        register(new LolItem("redemption", "Redemption", 2300, Material.BEACON, SUPPORT)
            .hp(300).mana(300).hpRegen(100).ah(20)
            .passive("Actif", "Soigne alliés pour 250+4%HP max dans une zone (120s CD)."));

        // ── Locket of the Iron Solari ─────────────────────────────────
        register(new LolItem("locket", "Locket of the Iron Solari", 2200, Material.GOLDEN_CHESTPLATE, SUPPORT)
            .hp(200).armor(30).mr(30).ah(15)
            .passive("Devotion", "Actif: bouclier 150+10%HP max aux alliés proches (90s CD)."));

        // ── Shurelya's Battlesong ─────────────────────────────────────
        register(new LolItem("shurelyas", "Shurelya's Battlesong", 2500, Material.MUSIC_DISC_CAT, SUPPORT)
            .hp(200).mana(300).ah(20).ms(5)
            .passive("Inspire", "Actif: +60% vitesse alliés proches 4s (120s CD)."));

        // ── Mikael's Blessing ─────────────────────────────────────────
        register(new LolItem("mikaels", "Mikael's Blessing", 2300, Material.AMETHYST_CLUSTER, SUPPORT)
            .hp(250).mana(300).mr(40)
            .passive("Purify", "Actif: soigne 100+15% mana, cleanse CC sur allié (90s CD)."));

        // ── Staff of Flowing Water ────────────────────────────────────
        register(new LolItem("staff_flowing_water", "Staff of Flowing Water", 2300, Material.BAMBOO, SUPPORT)
            .ap(60).mana(300).ah(10).ms(5)
            .passive("Rapids", "Soigner allié: +20-40% AS + 20% AP à toi et cet allié 4s."));

        // ── Moonstone Renewer ─────────────────────────────────────────
        register(new LolItem("moonstone_renewer", "Moonstone Renewer", 2400, Material.WHITE_CONCRETE, SUPPORT)
            .hp(250).mana(400).hpRegen(150).ah(20)
            .passive("Starlit Grace", "Soigner en combat: +15% puissance soin (max +45%)."));

        // ── Ardent Censer ─────────────────────────────────────────────
        register(new LolItem("ardent_censer", "Ardent Censer", 2300, Material.GOLDEN_SWORD, SUPPORT)
            .ap(60).mana(300).ah(10)
            .passive("Cense", "Soigner allié: +15% AS + 10-20% dégâts magiques on-hit 6s."));

        // ── Zeke's Convergence ────────────────────────────────────────
        register(new LolItem("zekes_convergence", "Zeke's Convergence", 2400, Material.CYAN_WOOL, SUPPORT)
            .hp(300).armor(30).mr(30).ah(15)
            .passive("Conduit", "CC sur ennemi: allié lié inflige +30% dégâts 6s."));

        // ══════════════════════════════════════════════════════════════
        // BOOTS (Bottes)
        // ══════════════════════════════════════════════════════════════

        register(new LolItem("boots_speed", "Bottes de Vitesse", 300, Material.LEATHER_BOOTS, UTILITY)
            .ms(45));

        register(new LolItem("sorcerers_shoes", "Sorcerer's Shoes", 1100, Material.LEATHER_BOOTS, MAGE)
            .ms(45).magicPen(0.18));

        register(new LolItem("plated_steelcaps", "Plated Steelcaps", 1100, Material.IRON_BOOTS, TANK)
            .ms(45).armor(20)
            .passive("Plating", "Réduit dégâts des AA de 12%."));

        register(new LolItem("mercurys_treads", "Mercury's Treads", 1100, Material.CHAINMAIL_BOOTS, TANK)
            .ms(45).mr(25)
            .passive("Tenacity", "-30% durée des CC."));

        register(new LolItem("berserkers_greaves", "Berserker's Greaves", 1100, Material.GOLDEN_BOOTS, ATTACK_SPEED)
            .ms(45).as(0.35));

        register(new LolItem("ionian_boots", "Ionian Boots of Lucidity", 950, Material.DIAMOND_BOOTS, UTILITY)
            .ms(45).ah(15));

        // ══════════════════════════════════════════════════════════════
        // ITEMS UTILITAIRES / SPÉCIAUX
        // ══════════════════════════════════════════════════════════════

        // ── Guardian Angel ────────────────────────────────────────────
        register(new LolItem("guardian_angel", "Guardian Angel", 3200, Material.TOTEM_OF_UNDYING, UTILITY)
            .ad(40).armor(40)
            .passive("Rebirth", "À la mort: résurrection avec 50% HP et 30% mana (300s CD)."));

        // ── Banshee's Veil ────────────────────────────────────────────
        register(new LolItem("banshees_veil", "Banshee's Veil", 3100, Material.BLUE_STAINED_GLASS, MAGE)
            .ap(90).mr(50)
            .passive("Annul", "Bouclier qui bloque 1 sort ennemi (40s CD hors combat)."));

        // ── Maw of Malmortius ─────────────────────────────────────────
        register(new LolItem("maw", "Maw of Malmortius", 2900, Material.DARK_OAK_SWORD, DAMAGE)
            .ad(55).mr(50).ah(15)
            .passive("Lifeline", "À <30% HP contre dégâts magiques: bouclier 150+20% AD bonus 5s."));

        // ── Hexdrinker (composant Maw) ────────────────────────────────
        register(new LolItem("hexdrinker", "Hexdrinker", 1300, Material.STONE_AXE, DAMAGE)
            .ad(25).mr(35)
            .passive("Lifeline", "Bouclier contre dégâts magiques letaux (30s CD)."));

        // ── Spear of Shojin ───────────────────────────────────────────
        register(new LolItem("spear_of_shojin", "Spear of Shojin", 3100, Material.STICK, DAMAGE)
            .ad(55).hp(300).ah(20)
            .passive("Dragonforce", "Après sort: les 3 prochains AA réduisent CD sorts de 1s."));

        // ── Bloodthirster ─────────────────────────────────────────────
        register(new LolItem("bloodthirster", "Bloodthirster", 3400, Material.RED_CONCRETE, DAMAGE)
            .ad(80).crit(0.20).lifeSteal(0.18)
            .passive("Ichorshield", "Vol de vie excédentaire = bouclier (max 50-400)."));

        // ── Mortal Reminder ───────────────────────────────────────────
        register(new LolItem("mortal_reminder", "Mortal Reminder", 2500, Material.BONE, DAMAGE)
            .ad(30).crit(0.20).lethality(7)
            .passive("Executioner", "Dégâts physiques: -40% soins sur la cible 3s."));

        // ── Warmog's complète la liste tank
        // ── Moonstone complète la liste support

        System.out.println("[ItemRegistry] " + ITEMS.size() + " items chargés.");
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static void register(LolItem item) {
        ITEMS.put(item.getId(), item);
    }

    public static LolItem get(String id) {
        return ITEMS.get(id);
    }

    public static Collection<LolItem> all() {
        return ITEMS.values();
    }

    public static List<LolItem> byCategory(LolItem.ItemCategory cat) {
        return ITEMS.values().stream()
                .filter(i -> i.getCategory() == cat)
                .toList();
    }

    public static Map<String, LolItem> getAll() {
        return ITEMS;
    }

    public static boolean exists(String id) {
        return ITEMS.containsKey(id);
    }
}
