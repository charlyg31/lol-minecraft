package fr.lolmc.item;

import org.bukkit.Material;
import java.util.*;
import static fr.lolmc.item.LolItem.ItemCategory.*;

/**
 * Registre des objets de la Faille de l'invocateur, GÉNÉRÉ depuis les données officielles de Riot
 * (Data Dragon 16.20.1 = patch 26.20, fr_FR) : noms français, prix, prix de revente, statistiques,
 * recettes et textes des passifs/actifs. Les identifiants internes (ex. "trinity_force") sont
 * conservés : PassiveManager s'en sert pour les effets.
 * Avant : ~230 entrées saisies à la main, dont des doublons, des objets retirés du jeu
 * (Goredrinker, Divine Sunderer…) et des statistiques d'anciens patchs.
 */
public class ItemRegistry {

    private static final Map<String, LolItem> ITEMS = new LinkedHashMap<>();

    static {
        // ── DÉPART ──
        reg(new LolItem("dark_seal", "Sceau noir", 350, Material.BLACKSTONE, MAGE).tier(LolItem.Tier.STARTER).sell(140).ap(15).hp(50)
                .passive("Gloire / effets Gloire", "Gloire Participer à une élimination vous fait gagner des effets Gloire (10 effets cumulés max). Vous perdez 5 effets Gloire en cas de mort. Vous gagnez +4 puissance par effet Gloire."));
        reg(new LolItem("doran_s_bow", "Arc de Doran", 400, Material.BOW, ATTACK_SPEED).tier(LolItem.Tier.STARTER).sell(160).ad(8).as(0.15).omnivamp(0.015));
        reg(new LolItem("doran_s_ring", "Anneau de Doran", 400, Material.GOLDEN_APPLE, SUPPORT).tier(LolItem.Tier.STARTER).sell(160).ap(18).hp(90)
                .passive("Drain / Coup de main", "Drain Restaure 1 mana toutes les secondes, ce bonus passe à 2 mana par seconde pendant 5 sec après avoir infligé des dégâts à un champion ennemi. Si vous ne pouvez pas récupérer de mana, vous récupérez 45% de cette valeur en PV à la place. Coup de main Vos attaques infligent 5 pts de dégâts physiques bonus aux sbires."));
        reg(new LolItem("tear_of_goddess", "Larme de la déesse", 400, Material.WATER_BUCKET, UTILITY).tier(LolItem.Tier.STARTER).sell(280).mana(240)
                .passive("Flux de mana / Coup de main", "Flux de mana (8 sec, max 4 charges) Quand vous touchez une cible avec une compétence, vous gagnez +3 mana max (doublé contre les champions, max. +360). Coup de main Vos attaques infligent 5 pts de dégâts physiques bonus aux sbires."));
        reg(new LolItem("cull", "Abatteur", 450, Material.WOODEN_AXE, DAMAGE).tier(LolItem.Tier.STARTER).sell(180).ad(7)
                .passive("Fauchaison", "Fauchaison Vos attaques vous rendent 3 PV à l'impact. Tuer des sbires vous rapporte 1 PO (jusqu'à 100 PO max). Atteindre la limite vous rapporte 350 PO bonus."));
        reg(new LolItem("doran_s_blade", "Lame de Doran", 450, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.STARTER).sell(180).ad(10).hp(80).omnivamp(0.025));
        reg(new LolItem("doran_s_helm", "Casque de Doran", 450, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.STARTER).sell(180).hp(150).armor(8).mr(8)
                .passive("Coup de main", "Coup de main Vos attaques infligent 5 pts de dégâts physiques bonus aux sbires."));
        reg(new LolItem("doran_s_shield", "Bouclier de Doran", 450, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.STARTER).sell(180).hp(110)
                .passive("Focalisation cicatrisante / Coup de main", "Focalisation cicatrisante Vous récupérez 4 PV toutes les 5 sec. Après avoir subi des dégâts d'un champion ennemi, vous récupérez des PV en 8 sec. Coup de main Vos attaques infligent 5 pts de dégâts physiques bonus aux sbires."));

        // ── CONSOMMABLES ──
        reg(new LolItem("health_potion", "Potion de soin", 50, Material.POTION, CONSUMABLE).tier(LolItem.Tier.CONSUMABLE).sell(20));
        reg(new LolItem("biscuit_will", "Biscuit de longévité", 50, Material.COOKIE, CONSUMABLE).tier(LolItem.Tier.CONSUMABLE).sell(5).unpurchasable());
        reg(new LolItem("control_ward", "Balise de contrôle", 75, Material.POTION, CONSUMABLE).tier(LolItem.Tier.CONSUMABLE).sell(30));
        reg(new LolItem("refillable_potion", "Potion rechargeable", 150, Material.GLASS_BOTTLE, CONSUMABLE).tier(LolItem.Tier.CONSUMABLE).sell(60));
        reg(new LolItem("cappa_juice", "Jus chapi-chapo", 300, Material.POTION, CONSUMABLE).tier(LolItem.Tier.CONSUMABLE).sell(300));
        reg(new LolItem("elixir_iron", "Élixir de fer", 500, Material.IRON_NUGGET, CONSUMABLE).tier(LolItem.Tier.CONSUMABLE).sell(200));
        reg(new LolItem("elixir_sorcery", "Élixir de sorcellerie", 500, Material.MAGENTA_DYE, CONSUMABLE).tier(LolItem.Tier.CONSUMABLE).sell(200));
        reg(new LolItem("elixir_wrath", "Élixir de rage", 500, Material.DRAGON_BREATH, CONSUMABLE).tier(LolItem.Tier.CONSUMABLE).sell(200));

        // ── TOTEMS ──
        reg(new LolItem("farsight", "Altération divinatoire", 0, Material.COMPASS, CONSUMABLE).tier(LolItem.Tier.TRINKET).sell(0));
        reg(new LolItem("oracle_lens", "Brouilleur oraculaire", 0, Material.SPYGLASS, CONSUMABLE).tier(LolItem.Tier.TRINKET).sell(0));
        reg(new LolItem("stealth_ward", "Balise camouflée", 0, Material.TORCH, CONSUMABLE).tier(LolItem.Tier.TRINKET).sell(0));

        // ── BOTTES ──
        reg(new LolItem("boots_speed", "Bottes", 300, Material.LEATHER_BOOTS, UTILITY).tier(LolItem.Tier.BOOTS).sell(210).ms(25));
        reg(new LolItem("crimson_lucidity", "Lucidité pourpre", 900, Material.DIAMOND_BOOTS, MAGE).tier(LolItem.Tier.BOOTS).sell(630).ah(20).ms(45)
                .passive("Savoir ionien / Hâte noxienne", "Savoir ionien Vous gagnez +20 accélération de sort d'invocateur. Hâte noxienne Renforcer ou protéger vos alliés avec des compétences, infliger des dégâts aux champions ennemis avec des compétences ou lancer un sort d'invocateur vous octroie +0 vitesse de déplacement pendant 4 sec. Hâte noxienne ne peut se déclencher qu'une fois par compétence lancée.")
                .recipe("ionian_boots"));
        reg(new LolItem("ionian_boots", "Bottes de lucidité", 900, Material.DIAMOND_BOOTS, UTILITY).tier(LolItem.Tier.BOOTS).sell(630).ah(10).ms(45)
                .passive("Savoir ionien", "Savoir ionien Vous gagnez +10 accélération de sort d'invocateur.")
                .recipe("boots_speed", "glowing_mote"));
        reg(new LolItem("boots_swiftness", "Bottes de célérité", 1000, Material.LEATHER_BOOTS, UTILITY).tier(LolItem.Tier.BOOTS).sell(700).ms(55)
                .passive("Rapidité", "Rapidité Réduit de 25% l'efficacité des ralentissements.")
                .recipe("boots_speed"));
        reg(new LolItem("gluttonous_greaves", "Jambières gloutonnes", 1000, Material.LEATHER_BOOTS, UTILITY).tier(LolItem.Tier.BOOTS).sell(700).ms(45).omnivamp(0.04)
                .passive("Carnage", "Carnage Vous gagnez 0.6% d'omnivampirisme à chaque élimination de champion, cumulable jusqu'à 10 fois.")
                .recipe("boots_speed"));
        reg(new LolItem("immortal_path", "Voie immortelle", 1000, Material.LEATHER_BOOTS, UTILITY).tier(LolItem.Tier.BOOTS).sell(700).ms(45).omnivamp(0.04)
                .passive("Carnage / Maintenant et à jamais", "Carnage Vous gagnez 0.6% d'omnivampirisme à chaque élimination de champion, cumulable jusqu'à 10 fois. Maintenant et à jamais Lorsque vous avez plus de la moitié de vos PV, vous infligez 4% de dégâts bonus. Lorsque vous avez moins de la moitié de vos PV, vous bénéficiez de 12% de soins, de boucliers et de régénération bonus.")
                .recipe("gluttonous_greaves"));
        reg(new LolItem("swiftmarch", "Célérité martiale", 1000, Material.LEATHER_BOOTS, UTILITY).tier(LolItem.Tier.BOOTS).sell(700).ms(65)
                .passive("Rapidité / Ferveur noxienne", "Rapidité Réduit de 25% l'efficacité des ralentissements. Ferveur noxienne Vous gagnez de la force adaptative équivalente à 5% de votre vitesse de déplacement.")
                .recipe("boots_swiftness"));
        reg(new LolItem("berserkers_greaves", "Jambières du berzerker", 1100, Material.GOLDEN_BOOTS, ATTACK_SPEED).tier(LolItem.Tier.BOOTS).sell(770).as(0.3).ms(45)
                .recipe("boots_speed", "dagger", "dagger"));
        reg(new LolItem("gunmetal_greaves", "Jambières de métal", 1100, Material.NETHERITE_BOOTS, ATTACK_SPEED).tier(LolItem.Tier.BOOTS).sell(770).as(0.45).ms(45).lifeSteal(0.05)
                .recipe("berserkers_greaves"));
        reg(new LolItem("sorcerers_shoes", "Chaussures de sorcier", 1100, Material.LEATHER_BOOTS, MAGE).tier(LolItem.Tier.BOOTS).sell(770).flatMagicPen(12).ms(45)
                .recipe("boots_speed"));
        reg(new LolItem("spellslingers_shoes", "Chaussures de lanceur de sorts", 1100, Material.GOLDEN_BOOTS, MAGE).tier(LolItem.Tier.BOOTS).sell(770).flatMagicPen(20).magicPen(0.08).ms(45)
                .recipe("sorcerers_shoes"));
        reg(new LolItem("armored_advance", "Avancée blindée", 1200, Material.IRON_BOOTS, TANK).tier(LolItem.Tier.BOOTS).sell(840).armor(35).ms(45)
                .passive("Placage / Endurance noxienne", "Placage Les dégâts que vous infligent les attaques sont réduits de 10%. Endurance noxienne (0 sec) Quand vous subissez des dégâts physiques d'un champion, vous gagnez un bouclier anti-dégâts physiques de 0 PV pendant 5 sec.")
                .recipe("plated_steelcaps"));
        reg(new LolItem("plated_steelcaps", "Coques en acier", 1200, Material.IRON_BOOTS, TANK).tier(LolItem.Tier.BOOTS).sell(840).armor(25).ms(45)
                .passive("Placage", "Placage Les dégâts que vous infligent les attaques sont réduits de 10%.")
                .recipe("boots_speed", "cloth_armor"));
        reg(new LolItem("chainlaced_crushers", "Broyeuses à chaînes", 1250, Material.CHAINMAIL_BOOTS, TANK).tier(LolItem.Tier.BOOTS).sell(875).mr(25).ms(45).tenacity(0.3)
                .passive("Persévérance noxienne", "Persévérance noxienne (0 sec) Quand vous subissez des dégâts magiques d'un champion, vous gagnez un bouclier anti-dégâts magiques de 0 PV pendant 5 sec.")
                .recipe("mercurys_treads"));
        reg(new LolItem("mercurys_treads", "Sandales de Mercure", 1250, Material.CHAINMAIL_BOOTS, TANK).tier(LolItem.Tier.BOOTS).sell(875).mr(20).ms(45).tenacity(0.3)
                .recipe("boots_speed", "null_magic_mantle"));

        // ── JUNGLE ──
        reg(new LolItem("smite_stalker", "Bébé Saute-nuages", 450, Material.GRAY_WOOL, UTILITY).tier(LolItem.Tier.JUNGLE).sell(0)
                .passive("Compagnon de la jungle", "Compagnon de la jungle Vous invoquez un Saute-nuages qui vous assistera contre les monstres. Votre compagnon grandit à mesure que vous chassez des monstres, ce qui renforce votre Châtiment. Quand votre compagnon a fini de grandir, il achève votre quête de jungle et vous gagnez de la vitesse de déplacement quand vous entrez dans des hautes herbes. Ce bonus augmente quand vous tuez des grands monstres."));
        reg(new LolItem("pickaxe_jungle", "Bébé Ixamandre", 450, Material.GREEN_WOOL, UTILITY).tier(LolItem.Tier.JUNGLE).sell(0)
                .passive("Compagnon de la jungle", "Compagnon de la jungle Vous invoquez un Ixamandre qui vous assistera contre les monstres. Votre compagnon grandit à mesure que vous chassez des monstres, ce qui renforce votre Châtiment. Quand votre compagnon a fini de grandir, il achève votre quête de jungle et vous gagnez un bouclier quand vous n'êtes pas en combat et quand vous tuez des grands monstres."));
        reg(new LolItem("blue_smite", "Bébé Chardent", 450, Material.RED_WOOL, UTILITY).tier(LolItem.Tier.JUNGLE).sell(0)
                .passive("Compagnon de la jungle", "Compagnon de la jungle Vous invoquez un Chardent qui vous assistera contre les monstres. Votre compagnon grandit à mesure que vous chassez des monstres, ce qui renforce votre Châtiment. Quand votre compagnon a fini de grandir, il achève votre quête de jungle et renforce périodiquement votre prochaine attaque ou compétence pour qu'elle brûle les ennemis et les ralentisse. Tuer un grand monstre prépare immédiatement cet effet."));

        // ── QUÊTE DE SUPPORT ──
        reg(new LolItem("bloodsong", "Mélodie du sang", 400, Material.GOLDEN_APPLE, SUPPORT).tier(LolItem.Tier.SUPPORT_QUEST).sell(160).hp(200).hpRegenPct(0.75).manaRegenPct(0.75).goldPer10(9)
                .passive("Lame enchantée", "Lame enchantée Après avoir utilisé une de vos compétences, votre prochaine attaque inflige des dégâts physiques bonus à l'impact. Si la cible est un champion, il subit des dégâts bonus pendant 4 sec. Propriété active (4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .active("Propriété active", "(4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .recipe("bounty_worlds"));
        reg(new LolItem("bounty_worlds", "Trésor des mondes", 400, Material.FILLED_MAP, SUPPORT).tier(LolItem.Tier.SUPPORT_QUEST).sell(160).unpurchasable().goldPer10(5).hpRegenPct(0.75).manaRegenPct(0.75).hp(200)
                .passive("Amélioration", "Amélioration Cet objet peut être amélioré gratuitement en Mélodie du sang, Opposition céleste, Rêve éveillé, Pique dimensionnelle de Zaz'Zak ou Traîneau du solstice.Propriété active (4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .active("Propriété active", "(4 charges) Pose une Balise camouflée invisible qui octroie de la vision."));
        reg(new LolItem("celestial_opposition", "Opposition céleste", 400, Material.YELLOW_DYE, SUPPORT).tier(LolItem.Tier.SUPPORT_QUEST).sell(160).hp(200).hpRegenPct(0.75).manaRegenPct(0.75).goldPer10(9)
                .passive("Bénédiction de la montagne", "Bénédiction de la montagne Réduit les dégâts que vous infligent les champions pendant 2 sec après avoir subi des dégâts d'un champion. Quand l'effet prend fin, vous ralentissez les ennemis proches de 50% pendant 1.5 sec. Propriété active (4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .active("Propriété active", "(4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .recipe("bounty_worlds"));
        reg(new LolItem("dream_maker", "Rêve éveillé", 400, Material.GOLDEN_APPLE, SUPPORT).tier(LolItem.Tier.SUPPORT_QUEST).sell(160).hp(200).hpRegenPct(0.75).manaRegenPct(0.75).goldPer10(9)
                .passive("Rêve éveillé", "Rêve éveillé Soigner un allié ou lui octroyer un bouclier lui envoie les bulles pendant 3 sec. Sa prochaine attaque inflige des dégâts magiques bonus à l'impact, et les prochains dégâts qu'il subit sont réduits. Propriété active (4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .active("Propriété active", "(4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .recipe("bounty_worlds"));
        reg(new LolItem("runic_compass", "Boussole runique", 400, Material.GOLDEN_APPLE, SUPPORT).tier(LolItem.Tier.SUPPORT_QUEST).sell(160).unpurchasable().hp(60).hpRegenPct(0.75).manaRegenPct(0.5).goldPer10(5)
                .passive("Quête de support / Richesses partagées", "Quête de support Gagnez 0 PO grâce à cet objet pour le transformer en Trésor des mondes. Richesses partagées (0 sec, max 0 charges) Quand vous êtes à proximité d'un champion allié, infligez des dégâts à un champion ennemi ou tuez des sbires pour gagner des PO.Propriété active (3 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .active("Propriété active", "(3 charges) Pose une Balise camouflée invisible qui octroie de la vision."));
        reg(new LolItem("solstice_sleigh", "Traîneau du solstice", 400, Material.JUKEBOX, SUPPORT).tier(LolItem.Tier.SUPPORT_QUEST).sell(160).hp(200).hpRegenPct(0.75).manaRegenPct(0.75).goldPer10(9)
                .passive("Virée en traîneau", "Virée en traîneau Ralentir ou immobiliser un champion ennemi près d'alliés vous octroie, ainsi qu'à un allié proche, des PV et +20% de vitesse de déplacement (ce bonus diminue sur la durée) pendant 2.5 sec. Propriété active (4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .active("Propriété active", "(4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .recipe("bounty_worlds"));
        reg(new LolItem("world_atlas", "Atlas", 400, Material.MAP, SUPPORT).tier(LolItem.Tier.SUPPORT_QUEST).sell(160).goldPer10(3)
                .passive("Quête de support / Richesses partagées", "Quête de support : gagnez 400 PO avec cet objet pour le transformer en Boussole runique. Richesses partagées : une charge toutes les 20 s (max 3) ; près d'un champion allié, tuer un sbire donne son or à l'allié le plus proche et 18 PO à vous, toucher un champion ennemi rapporte 18 PO."));
        reg(new LolItem("zaz_zak_s_realmspike", "Pique dimensionnelle de Zaz'Zak", 400, Material.GOLDEN_APPLE, SUPPORT).tier(LolItem.Tier.SUPPORT_QUEST).sell(160).hp(200).hpRegenPct(0.75).manaRegenPct(0.75).goldPer10(9)
                .passive("Explosion du Néant", "Explosion du Néant Infliger des dégâts de compétence à un champion provoque une explosion qui inflige des dégâts magiques. Propriété active (4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .active("Propriété active", "(4 charges) Pose une Balise camouflée invisible qui octroie de la vision.")
                .recipe("bounty_worlds"));

        // ── BASIQUES ──
        reg(new LolItem("faerie_charm", "Charme féérique", 200, Material.PINK_PETALS, UTILITY).tier(LolItem.Tier.BASIC).sell(140).manaRegenPct(0.5));
        reg(new LolItem("dagger", "Dague", 250, Material.WOODEN_SWORD, ATTACK_SPEED).tier(LolItem.Tier.BASIC).sell(175).as(0.1));
        reg(new LolItem("glowing_mote", "Poussière luisante", 250, Material.LEATHER_BOOTS, UTILITY).tier(LolItem.Tier.BASIC).sell(175).ah(5));
        reg(new LolItem("cloth_armor", "Armure d'étoffe", 300, Material.LEATHER_CHESTPLATE, TANK).tier(LolItem.Tier.BASIC).sell(210).armor(15));
        reg(new LolItem("rejuv_bead", "Collier rafraîchissant", 300, Material.LIME_DYE, UTILITY).tier(LolItem.Tier.BASIC).sell(120).hpRegenPct(1));
        reg(new LolItem("sapphire_crystal", "Cristal de saphir", 300, Material.LAPIS_LAZULI, UTILITY).tier(LolItem.Tier.BASIC).sell(210).mana(300));
        reg(new LolItem("long_sword", "Épée longue", 350, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.BASIC).sell(245).ad(10));
        reg(new LolItem("amplifying_tome", "Tome d'amplification", 400, Material.BOOK, MAGE).tier(LolItem.Tier.BASIC).sell(280).ap(20));
        reg(new LolItem("null_magic_mantle", "Cape de néant", 400, Material.LIGHT_GRAY_DYE, TANK).tier(LolItem.Tier.BASIC).sell(280).mr(20));
        reg(new LolItem("ruby_crystal", "Cristal de rubis", 400, Material.REDSTONE, TANK).tier(LolItem.Tier.BASIC).sell(280).hp(150));
        reg(new LolItem("cloak_agility", "Cape d'agilité", 600, Material.FEATHER, ATTACK_SPEED).tier(LolItem.Tier.BASIC).sell(420).crit(0.15));
        reg(new LolItem("blasting_wand", "Baguette explosive", 850, Material.BLAZE_ROD, MAGE).tier(LolItem.Tier.BASIC).sell(595).ap(45));
        reg(new LolItem("pickaxe", "Pioche", 875, Material.STONE_SWORD, DAMAGE).tier(LolItem.Tier.BASIC).sell(613).ad(25));
        reg(new LolItem("needlessly_large_rod", "Baguette trop grosse", 1200, Material.END_ROD, MAGE).tier(LolItem.Tier.BASIC).sell(840).ap(65));
        reg(new LolItem("bf_sword", "BF Glaive", 1300, Material.GOLDEN_SWORD, DAMAGE).tier(LolItem.Tier.BASIC).sell(910).ad(40));

        // ── ÉPIQUES ──
        reg(new LolItem("forbidden_idol", "Idole interdite", 600, Material.TOTEM_OF_UNDYING, SUPPORT).tier(LolItem.Tier.EPIC).sell(420).manaRegenPct(0.5).healShieldPower(0.08)
                .recipe("faerie_charm"));
        reg(new LolItem("scout_s_slingshot", "Fronde de l'éclaireur", 600, Material.BOW, ATTACK_SPEED).tier(LolItem.Tier.EPIC).sell(420).as(0.2)
                .passive("Dans le mille", "Dans le mille Blesser un champion lui inflige des dégâts magiques bonus. Les attaques réduisent ce délai de récupération de 1 sec.")
                .recipe("dagger", "dagger"));
        reg(new LolItem("recurve_bow", "Arc courbe", 700, Material.BOW, ATTACK_SPEED).tier(LolItem.Tier.EPIC).sell(490).as(0.15)
                .passive("Dard", "Dard Les attaques infligent 15 pts de dégâts physiques bonus à l'impact.")
                .recipe("dagger"));
        reg(new LolItem("rectrix", "Rectrix", 775, Material.GOLD_INGOT, ATTACK_SPEED).tier(LolItem.Tier.EPIC).sell(543).ad(15).msPct(0.04)
                .recipe("long_sword"));
        reg(new LolItem("bramble_vest", "Armure roncière", 800, Material.IRON_BARS, TANK).tier(LolItem.Tier.EPIC).sell(560).armor(30)
                .passive("Épines", "Épines Quand vous êtes touché par une attaque, vous infligez 0 pts de dégâts magiques à l'attaquant, ainsi que 40% d'Hémorragie pendant 3 sec si c'est un champion.")
                .recipe("cloth_armor", "cloth_armor"));
        reg(new LolItem("chain_vest", "Cotte de mailles", 800, Material.CHAINMAIL_CHESTPLATE, TANK).tier(LolItem.Tier.EPIC).sell(560).armor(40)
                .recipe("cloth_armor"));
        reg(new LolItem("crystalline_bracer", "Bracelet cristallin", 800, Material.PRISMARINE_BRICKS, UTILITY).tier(LolItem.Tier.EPIC).sell(560).hp(200).hpRegenPct(1)
                .recipe("ruby_crystal", "rejuv_bead"));
        reg(new LolItem("executioner_s_calling", "Marque du bourreau", 800, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.EPIC).sell(560).ad(15)
                .passive("Hémorragie", "Hémorragie Infliger des dégâts physiques aux champions leur inflige 40% d'Hémorragie pendant 3 sec.")
                .recipe("long_sword"));
        reg(new LolItem("kindlegem", "Gemme exaltante", 800, Material.GLOWSTONE_DUST, UTILITY).tier(LolItem.Tier.EPIC).sell(560).hp(200).ah(10)
                .recipe("ruby_crystal", "glowing_mote"));
        reg(new LolItem("oblivion_orb", "Orbe de l'oubli", 800, Material.ENDER_EYE, MAGE).tier(LolItem.Tier.EPIC).sell(560).ap(25)
                .passive("Hémorragie", "Hémorragie Infliger des dégâts magiques aux champions leur inflige 40% d'Hémorragie pendant 3 sec.")
                .recipe("amplifying_tome"));
        reg(new LolItem("winged_moonplate", "Plaque de lune ailée", 800, Material.PHANTOM_MEMBRANE, UTILITY).tier(LolItem.Tier.EPIC).sell(560).hp(200).msPct(0.04)
                .recipe("ruby_crystal"));
        reg(new LolItem("fiendish_codex", "Codex méphitique", 850, Material.ENCHANTED_BOOK, MAGE).tier(LolItem.Tier.EPIC).sell(595).ap(25).ah(10)
                .recipe("amplifying_tome", "glowing_mote"));
        reg(new LolItem("negatron_cloak", "Manteau de Négatron", 850, Material.PURPLE_DYE, TANK).tier(LolItem.Tier.EPIC).sell(595).mr(45)
                .recipe("null_magic_mantle"));
        reg(new LolItem("aether_wisp", "Feu follet éthéré", 900, Material.PRISMARINE_CRYSTALS, MAGE).tier(LolItem.Tier.EPIC).sell(630).ap(30).msPct(0.04)
                .recipe("amplifying_tome"));
        reg(new LolItem("bamis_cinder", "Cendres de Bami", 900, Material.MAGMA_BLOCK, TANK).tier(LolItem.Tier.EPIC).sell(630).hp(150).ah(5)
                .passive("Immolation", "Immolation Subir ou infliger des dégâts vous permet d'infliger des dégâts magiques aux ennemis proches pendant 3 sec.")
                .recipe("ruby_crystal", "glowing_mote"));
        reg(new LolItem("bandleglass_mirror", "Miroir en verre de Bandle", 900, Material.SPYGLASS, MAGE).tier(LolItem.Tier.EPIC).sell(630).ap(20).manaRegenPct(1).ah(10)
                .recipe("faerie_charm", "amplifying_tome", "glowing_mote"));
        reg(new LolItem("fated_ashes", "Cendres fatidiques", 900, Material.BLAZE_POWDER, MAGE).tier(LolItem.Tier.EPIC).sell(630).ap(30)
                .passive("Ignition", "Ignition Les compétences blessantes infligent 15 pts de dégâts magiques bonus en 3 sec. Inflige 45 pts de dégâts magiques bonus aux monstres.")
                .recipe("amplifying_tome"));
        reg(new LolItem("giant_s_belt", "Ceinture du géant", 900, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.EPIC).sell(630).hp(350)
                .recipe("ruby_crystal"));
        reg(new LolItem("glacial_buckler", "Bouclier glacial", 900, Material.PACKED_ICE, TANK).tier(LolItem.Tier.EPIC).sell(630).armor(25).mana(300).ah(10)
                .recipe("cloth_armor", "sapphire_crystal", "glowing_mote"));
        reg(new LolItem("sheen", "Brillance", 900, Material.QUARTZ, UTILITY).tier(LolItem.Tier.EPIC).sell(630).ah(10)
                .passive("Lame enchantée", "Lame enchantée Après avoir utilisé une de vos compétences, votre prochaine attaque inflige des dégâts physiques bonus à l'impact.")
                .recipe("glowing_mote"));
        reg(new LolItem("vampiric_scepter", "Sceptre vampirique", 900, Material.GOLDEN_AXE, DAMAGE).tier(LolItem.Tier.EPIC).sell(630).ad(15).lifeSteal(0.07)
                .recipe("long_sword"));
        reg(new LolItem("serrated_dirk", "Dague dentelée", 1000, Material.STONE_AXE, DAMAGE).tier(LolItem.Tier.EPIC).sell(700).ad(20).lethality(10)
                .recipe("long_sword", "long_sword"));
        reg(new LolItem("warden_mail", "Mailles du gardien", 1000, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.EPIC).sell(700).armor(40)
                .passive("Dur comme fer", "Dur comme fer Les dégâts que vous infligent les attaques sont réduits.")
                .recipe("cloth_armor", "cloth_armor"));
        reg(new LolItem("caulfields", "Marteau de Caulfield", 1050, Material.IRON_AXE, DAMAGE).tier(LolItem.Tier.EPIC).sell(735).ad(20).ah(10)
                .recipe("long_sword", "glowing_mote", "long_sword"));
        reg(new LolItem("blighting_jewel", "Joyau putréfiant", 1100, Material.AMETHYST_SHARD, MAGE).tier(LolItem.Tier.EPIC).sell(770).ap(25).magicPen(0.13)
                .recipe("amplifying_tome"));
        reg(new LolItem("hextech_alternator", "Alternateur Hextech", 1100, Material.COPPER_INGOT, MAGE).tier(LolItem.Tier.EPIC).sell(770).ap(45)
                .passive("Vibration magique", "Vibration magique Blesser un champion lui inflige des dégâts magiques bonus.")
                .recipe("amplifying_tome", "amplifying_tome"));
        reg(new LolItem("phage", "Phage", 1100, Material.STONE_SWORD, UTILITY).tier(LolItem.Tier.EPIC).sell(770).ad(15).hp(200)
                .passive("Rage", "Rage Vos attaques vous octroient de la vitesse de déplacement pendant 2 sec.")
                .recipe("ruby_crystal", "long_sword"));
        reg(new LolItem("steel_sigil", "Sceau d'acier", 1100, Material.IRON_NUGGET, DAMAGE).tier(LolItem.Tier.EPIC).sell(770).ad(15).armor(30)
                .recipe("cloth_armor", "long_sword", "cloth_armor"));
        reg(new LolItem("tunneler", "Foreuse", 1150, Material.STONE_PICKAXE, TANK).tier(LolItem.Tier.EPIC).sell(805).ad(15).hp(250)
                .recipe("long_sword", "ruby_crystal"));
        reg(new LolItem("hearthbound_axe", "Hache d'Âtrinsèque", 1200, Material.GOLDEN_AXE, ATTACK_SPEED).tier(LolItem.Tier.EPIC).sell(840).ad(20).as(0.2)
                .recipe("long_sword", "dagger", "long_sword"));
        reg(new LolItem("lost_chapter", "Chapitre perdu", 1200, Material.WRITTEN_BOOK, MAGE).tier(LolItem.Tier.EPIC).sell(840).ap(40).mana(300).ah(10)
                .passive("Illumination", "Illumination Quand vous gagnez un niveau, vous récupérez 20% de votre mana max en 3 sec.")
                .recipe("amplifying_tome", "sapphire_crystal", "glowing_mote"));
        reg(new LolItem("tiamat", "Tiamat", 1200, Material.IRON_AXE, DAMAGE).tier(LolItem.Tier.EPIC).sell(840).ad(25)
                .passive("Fendoir", "Fendoir Vos attaques infligent des dégâts physiques aux ennemis proches. Croissant Vous infligez des dégâts physiques aux ennemis autour de vous.")
                .active("Croissant", "Vous infligez des dégâts physiques aux ennemis autour de vous.")
                .recipe("long_sword", "long_sword"));
        reg(new LolItem("zeal", "Zèle", 1200, Material.ARROW, ATTACK_SPEED).tier(LolItem.Tier.EPIC).sell(840).as(0.15).crit(0.15).msPct(0.04)
                .recipe("cloak_agility", "dagger"));
        reg(new LolItem("spectre_cowl", "Manteau de spectre", 1250, Material.LEATHER_HELMET, TANK).tier(LolItem.Tier.EPIC).sell(875).hp(200).mr(35).hpRegenPct(1)
                .recipe("ruby_crystal", "null_magic_mantle", "rejuv_bead"));
        reg(new LolItem("catalyst", "Catalyseur de l'éternité", 1300, Material.EXPERIENCE_BOTTLE, UTILITY).tier(LolItem.Tier.EPIC).sell(910).hp(300).mana(375)
                .passive("Éternité", "Éternité Vous récupérez du mana équivalent à 10% des dégâts que vous infligent les champions. Lancer une compétence vous rend des PV équivalents à 25% du mana dépensé.")
                .recipe("ruby_crystal", "ruby_crystal", "sapphire_crystal"));
        reg(new LolItem("haunting_guise", "Déguisement hanté", 1300, Material.GHAST_TEAR, MAGE).tier(LolItem.Tier.EPIC).sell(910).ap(30).hp(200)
                .passive("Folie", "Folie Pour chaque seconde passée en combat avec des champions ennemis, vous infligez 2% de dégâts bonus (jusqu'à 6%).")
                .recipe("amplifying_tome", "ruby_crystal"));
        reg(new LolItem("hexdrinker", "Hexterminateur", 1300, Material.STONE_AXE, DAMAGE).tier(LolItem.Tier.EPIC).sell(910).ad(25).mr(25)
                .passive("Lien vital", "Lien vital Si des dégâts magiques sont censés vous laisser moins de 30% de vos PV, vous gagnez au préalable un bouclier anti-dégâts magiques pendant 2.5 sec.")
                .recipe("long_sword", "null_magic_mantle", "long_sword"));
        reg(new LolItem("noonquiver", "Carquois zénithien", 1300, Material.ARROW, DAMAGE).tier(LolItem.Tier.EPIC).sell(910).ad(15).crit(0.2)
                .recipe("long_sword", "cloak_agility"));
        reg(new LolItem("quicksilver_sash", "Ceinture de mercure", 1300, Material.CYAN_STAINED_GLASS, UTILITY).tier(LolItem.Tier.EPIC).sell(910).mr(30)
                .active("Mercure", "Vous dissipez tous les contrôles de foule qui vous affectent (sauf les projections dans les airs).")
                .recipe("null_magic_mantle"));
        reg(new LolItem("the_brutalizer", "Brutaliseur", 1337, Material.IRON_AXE, DAMAGE).tier(LolItem.Tier.EPIC).sell(936).ad(25).ah(10).lethality(5)
                .recipe("glowing_mote", "pickaxe"));
        reg(new LolItem("last_whisper", "Dernier souffle", 1450, Material.FEATHER, DAMAGE).tier(LolItem.Tier.EPIC).sell(1015).ad(20).armorPenPct(0.18)
                .recipe("long_sword", "long_sword"));
        reg(new LolItem("mejai_s_soulstealer", "Voleur d'âmes de Mejai", 1500, Material.BLAZE_ROD, MAGE).tier(LolItem.Tier.EPIC).sell(1050).ap(20).hp(100)
                .passive("Gloire / effets Gloire", "Gloire Participer à une élimination vous fait gagner des effets Gloire (25 effets cumulés max). Vous perdez 10 effets Gloire en cas de mort. Vous gagnez +5 puissance par effet Gloire et +10% en vitesse de déplacement si vous avez 10 effets Gloire ou plus.")
                .recipe("dark_seal"));
        reg(new LolItem("seeker_s_armguard", "Protège-bras du savant", 1600, Material.BLAZE_ROD, MAGE).tier(LolItem.Tier.EPIC).sell(640).ap(40).armor(25)
                .active("Pause temporelle", "(usage unique) Vous entrez en stase pendant 2.5 sec.")
                .recipe("amplifying_tome", "cloth_armor", "amplifying_tome"));
        reg(new LolItem("shattered_armguard", "Protège-bras pulvérisé", 1600, Material.LEATHER_BOOTS, UTILITY).tier(LolItem.Tier.EPIC).sell(640).ap(40).armor(25)
                .passive("Fracture du temps", "Fracture du temps Le Protège-bras est brisé, mais peut être amélioré. Après que vous avez brisé un Protège-bras, le marchand ne vous vend plus que des Protège-bras pulvérisés.")
                .recipe("amplifying_tome", "cloth_armor", "amplifying_tome"));
        reg(new LolItem("verdant_barrier", "Barrière verdoyante", 1600, Material.GREEN_DYE, MAGE).tier(LolItem.Tier.EPIC).sell(1120).ap(40).mr(25)
                .passive("Invalidation", "Invalidation Vous gagnez un bouclier antisorts qui bloque la prochaine compétence ennemie.")
                .recipe("amplifying_tome", "null_magic_mantle", "amplifying_tome"));

        // ── LÉGENDAIRES ──
        reg(new LolItem("ardent_censer", "Encensoir ardent", 2200, Material.GOLDEN_SWORD, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1540).ap(45).healShieldPower(0.1).manaRegenPct(1.25).msPct(0.04)
                .passive("Sanctification", "Sanctification Lancer un soin ou un bouclier sur un allié vous renforce tous les deux pendant 6 sec, vous octroyant +25% de vitesse d'attaque et +20 pts de dégâts magiques à l'impact.")
                .recipe("aether_wisp", "forbidden_idol"));
        reg(new LolItem("echoes_of_helia", "Échos d'Helia", 2200, Material.GOLDEN_APPLE, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1540).ap(35).hp(200).ah(20).manaRegenPct(1.25)
                .passive("Siphon d'âme / charges spirituelles", "Siphon d'âme Vous gagnez 30% des dégâts infligés aux champions avant mitigation sous forme de charges spirituelles. Lancer un soin ou un bouclier sur un allié consomme toutes les charges spirituelles pour lui rendre des PV.")
                .recipe("kindlegem", "bandleglass_mirror"));
        reg(new LolItem("locket", "Médaillon de l'Iron Solari", 2200, Material.GOLDEN_CHESTPLATE, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1540).hp(200).armor(30).mr(30).ah(10)
                .active("Dévotion", "Vous octroyez aux alliés proches un bouclier de 290 - 360 PV qui se désagrège en 2.5 sec.")
                .recipe("kindlegem", "cloth_armor", "null_magic_mantle"));
        reg(new LolItem("moonstone_renewer", "Régénérateur de pierre de lune", 2200, Material.WHITE_CONCRETE, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1540).ap(25).hp(200).ah(20).manaRegenPct(1.25)
                .passive("Grâce stellaire", "Grâce stellaire Lancer un soin ou un bouclier sur un allié propage l'effet à un autre allié (à l'exclusion de vous-même), rendant des PV équivalents à 30% du montant original ou octroyant un bouclier équivalent à 35% du montant original.")
                .recipe("kindlegem", "bandleglass_mirror"));
        reg(new LolItem("shurelyas", "Chant de guerre de Shurelya", 2200, Material.GOLDEN_APPLE, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1540).ap(50).ah(15).msPct(0.04).manaRegenPct(1.25)
                .active("Discours exaltant", "Vous octroyez +30% de vitesse de déplacement pendant 4 sec aux alliés proches.")
                .recipe("aether_wisp", "bandleglass_mirror"));
        reg(new LolItem("zekess_convergence", "Convergence de Zeke", 2200, Material.CYAN_WOOL, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1540).hp(300).armor(25).mr(25).ah(10)
                .passive("Tempête cryopyrique", "Tempête cryopyrique Lancer votre ultime invoque une tempête autour de vous pendant 5 sec. La tempête inflige 30 pts de dégâts magiques par seconde aux champions ennemis et les ralentit de 30%.")
                .recipe("kindlegem", "cloth_armor", "null_magic_mantle"));
        reg(new LolItem("staff_of_flowing_water", "Bâton des flots", 2250, Material.GOLDEN_APPLE, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1575).ap(35).healShieldPower(0.1).manaRegenPct(1.25).ah(10)
                .passive("Rapides", "Rapides Chaque fois que vous lancez un soin ou un bouclier sur un autre allié, cet allié et vous gagnez 40 puissance et 15 accélération de compétence pendant 6 sec.")
                .recipe("fiendish_codex", "forbidden_idol"));
        reg(new LolItem("whispering_circlet", "Diadème des murmures", 2250, Material.WHITE_DYE, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1575).hp(200).healShieldPower(0.08).manaRegenPct(0.75).mana(300)
                .passive("Harmonie / Flux de mana", "Harmonie Vous gagnez 0% d'efficacité des soins et boucliers. Flux de mana (8 sec, max. 5 charges) Quand vous touchez une cible avec une compétence, vous gagnez 4 pts de mana max (doublé contre les champions). Cet objet se transforme en Diadème musical à 360 pts de mana max.")
                .recipe("forbidden_idol", "ruby_crystal", "tear_of_goddess"));
        reg(new LolItem("bandlepipes", "Flûtes de Bandle", 2300, Material.NOTE_BLOCK, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1610).hp(200).ah(15).armor(20).mr(20)
                .passive("Fanfare", "Fanfare Ralentir ou immobiliser un champion ennemi vous octroie Fanfare pendant 0 sec. Fanfare vous octroie 20 de vitesse de déplacement. Lorsque vous avez Fanfare, les alliés à proximité, et vous-même, gagnez 0 de vitesse d'attaque.")
                .recipe("kindlegem", "cloth_armor", "null_magic_mantle"));
        reg(new LolItem("knight_s_vow", "Vœu du chevalier", 2300, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.LEGENDARY).sell(1610).hp(200).armor(40).ah(10).hpRegenPct(1)
                .passive("Sacrifice", "Sacrifice Quand l'allié digne est proche, vous redirigez vers vous 14% des dégâts qu'il subit et vous récupérez des PV équivalents à 12% des dégâts qu'il inflige aux champions.Serment (0 sec) Vous désignez un allié qui est digne de votre aide.")
                .active("Serment", "(0 sec) Vous désignez un allié qui est digne de votre aide.")
                .recipe("kindlegem", "chain_vest", "rejuv_bead"));
        reg(new LolItem("mikaels", "Bénédiction de Mikael", 2300, Material.AMETHYST_CLUSTER, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1610).hp(250).manaRegenPct(1).healShieldPower(0.12).ah(15)
                .active("Purification", "Vous dissipez tous les contrôles de foule qui affectent un champion allié (sauf les projections dans les airs et les neutralisations) et vous lui rendez 100 - 250 PV.")
                .recipe("kindlegem", "forbidden_idol"));
        reg(new LolItem("redemption", "Rédemption", 2300, Material.BEACON, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1610).ap(30).ah(15).manaRegenPct(1).healShieldPower(0.1)
                .active("Intervention", "Au bout de 2.5 sec, vous rendez 150 - 350 PV aux unités alliées et infligez 10% des PV max en dégâts bruts aux champions ennemis.")
                .recipe("fiendish_codex", "forbidden_idol"));
        reg(new LolItem("fimbulwinter", "Fimbulvetr", 2400, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.LEGENDARY).sell(1680).unpurchasable().hp(550).mana(1000).ah(15)
                .passive("Effroi / Persistance", "Effroi Vous gagnez +0 PV. Persistance (0 sec) Immobiliser ou ralentir (en mêlée uniquement) un champion ennemi vous octroie un bouclier pendant 3 sec. Le bouclier augmente de 80% s'il y a plusieurs ennemis à proximité."));
        reg(new LolItem("imperial_mandate", "Mandat impérial", 2400, Material.GOLD_INGOT, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1680).ap(60).ah(15).manaRegenPct(1.5)
                .passive("Contrôle / Commande", "Contrôle Vous gagnez 20 d'accélération de compétence pour vos compétences avec des effets d'immobilisation. Commande Lorsque vous immobilisez un champion ennemi, vous le marquez avec 7% de vulnérabilité pendant 4 sec.")
                .recipe("amplifying_tome", "bandleglass_mirror", "amplifying_tome"));
        reg(new LolItem("winter_s_approach", "Approche de l'hiver", 2400, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.LEGENDARY).sell(1680).hp(550).mana(500).ah(15)
                .passive("Effroi / Flux de mana", "Effroi Vous gagnez 0 PV. Flux de mana (8 sec, max 4 charges) Quand vous touchez une cible avec une attaque ou une compétence, vous gagnez +3 mana max (doublé contre les champions). Cet objet se transforme en Fimbulvetr à +360 mana max.")
                .recipe("tear_of_goddess", "giant_s_belt", "kindlegem"));
        reg(new LolItem("thornmail", "Cotte épineuse", 2450, Material.IRON_BARS, TANK).tier(LolItem.Tier.LEGENDARY).sell(1715).hp(150).armor(75)
                .passive("Épines", "Épines Quand vous êtes touché par une attaque, vous infligez des dégâts magiques à l'attaquant, ainsi que 40% d'Hémorragie pendant 3 sec si c'est un champion.")
                .recipe("bramble_vest", "chain_vest", "ruby_crystal"));
        reg(new LolItem("dawncore", "Cœur de l'aube", 2500, Material.DAYLIGHT_DETECTOR, SUPPORT).tier(LolItem.Tier.LEGENDARY).sell(1750).ap(45).healShieldPower(0.16).manaRegenPct(1)
                .passive("Première lumière", "Première lumière Vous gagnez +2% d'efficacité des soins et boucliers et +10 puissance tous les 100% de régénération de base du mana.")
                .recipe("blasting_wand", "forbidden_idol", "forbidden_idol"));
        reg(new LolItem("frozen_heart", "Cœur gelé", 2500, Material.ICE, TANK).tier(LolItem.Tier.LEGENDARY).sell(1750).armor(75).mana(400).ah(20)
                .passive("Caresse de l'hiver", "Caresse de l'hiver Vous réduisez de 20% la vitesse d'attaque des champions ennemis proches.")
                .recipe("warden_mail", "glacial_buckler"));
        reg(new LolItem("serpent_s_fang", "Crochet de serpent", 2500, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(1750).ad(55).lethality(15)
                .passive("Briseur de boucliers", "Briseur de boucliers Infliger des dégâts à un champion ennemi réduit les boucliers qu'il reçoit de 0% pendant 3 sec. Si l'ennemi n'était pas déjà affecté par Briseur de boucliers, vous réduisez ses boucliers actuels de 0%.")
                .recipe("serrated_dirk", "pickaxe"));
        reg(new LolItem("protoplasm_harness", "Harnais protoplasmique", 2600, Material.SLIME_BLOCK, TANK).tier(LolItem.Tier.LEGENDARY).sell(1820).hp(600).ah(20)
                .passive("Lien vital", "Lien vital (0 sec) Si des dégâts sont censés vous laisser moins de 30% de vos PV, vous gagnez 0 PV max pendant 5 sec, puis vous récupérez 0 PV sur la durée. Lorsque vous récupérez des PV, vous grandissez de 15%, vous gagnez 10% de vitesse de déplacement et 25% de ténacité.")
                .recipe("kindlegem", "giant_s_belt"));
        reg(new LolItem("rod_of_ages", "Bâton séculaire", 2600, Material.BAMBOO, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1820).ap(45).hp(350).mana(500)
                .passive("Intemporel / Éternité", "Intemporel Cet objet gagne +10 PV, +30 pts de mana et +3 puissance toutes les 60 sec, jusqu'à 10 fois. Au maximum d'effets cumulés, vous gagnez un niveau. Éternité Subir des dégâts d'un champion vous rend du mana équivalent à 10% des dégâts subis. Lancer une compétence vous rend des PV équivalents à 25% du mana dépensé.")
                .recipe("blasting_wand", "catalyst"));
        reg(new LolItem("rylai_s_crystal_scepter", "Sceptre de Rylai", 2600, Material.BLAZE_ROD, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1820).ap(65).hp(400)
                .passive("Couche de givre", "Couche de givre Vos compétences blessantes ralentissent les ennemis de 30% pendant 1 sec.")
                .recipe("blasting_wand", "giant_s_belt", "amplifying_tome"));
        reg(new LolItem("abyssal_mask", "Masque abyssal", 2650, Material.PURPLE_WOOL, TANK).tier(LolItem.Tier.LEGENDARY).sell(1855).hp(350).mr(45).ah(15)
                .passive("Anéantissement", "Anéantissement Les champions ennemis proches subissent 12% de dégâts magiques bonus.")
                .recipe("kindlegem", "negatron_cloak"));
        reg(new LolItem("fiendhunter_bolts", "Chasseur de monstres", 2650, Material.ARROW, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(1855).as(0.45).crit(0.25).msPct(0.04)
                .passive("Veille de nuit / Barrage d'ouverture", "Veille de nuit Vous gagnez 30 d'accélération d'ultime. Barrage d'ouverture Après avoir lancé votre ultime, vos prochaines 3 attaques de base gagnent 50% de vitesse d'attaque et infligent un coup critique de qui inflige 80% de vos dégâts de coups critiques normaux pendant 8 sec. Si une attaque devait déjà infliger un coup critique, elle inflige 15% de dégâts bruts bonus.")
                .recipe("zeal", "scout_s_slingshot"));
        reg(new LolItem("rocketbelt", "Ceinture-roquette Hextech", 2650, Material.FIREWORK_ROCKET, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1855).ap(60).hp(350).ah(10)
                .active("Supersonique", "Vous vous ruez dans la direction ciblée en lançant des projectiles qui infligent des dégâts magiques.")
                .recipe("hextech_alternator", "kindlegem", "ruby_crystal"));
        reg(new LolItem("navori_flickerblade", "Tremblelame navori", 2650, Material.FEATHER, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(1855).as(0.4).crit(0.25).msPct(0.04)
                .passive("Transcendance", "Transcendance Vos attaques réduisent le délai de récupération de vos compétences de base de 15% de leur délai de récupération restant.")
                .recipe("dagger", "zeal", "dagger"));
        reg(new LolItem("phantom_dancer", "Danseur fantôme", 2650, Material.ELYTRA, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(1855).as(0.65).crit(0.25).msPct(0.1)
                .passive("Valse spectrale", "Valse spectrale Vous devenez fantomatique.")
                .recipe("dagger", "zeal", "dagger"));
        reg(new LolItem("rapid_firecannon", "Canon ultrarapide", 2650, Material.CROSSBOW, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(1855).as(0.35).crit(0.25).msPct(0.04)
                .passive("Sniper", "Sniper Vos attaques énergisées infligent 40 pts de dégâts magiques bonus et gagnent +35% de portée d'attaque bonus.")
                .recipe("zeal", "scout_s_slingshot"));
        reg(new LolItem("runaan_s_hurricane", "Ouragan de Runaan", 2650, Material.BOW, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(1855).as(0.4).crit(0.25).msPct(0.05)
                .passive("Fureur du vent", "Fureur du vent Vos attaques tirent des projectiles sur 2 ennemis bonus près de la cible. Chaque projectile inflige des dégâts physiques et applique vos effets à l'impact.")
                .recipe("zeal", "scout_s_slingshot"));
        reg(new LolItem("horizon_focus", "Concentration lointaine", 2700, Material.SPYGLASS, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1890).ap(75).ah(25)
                .passive("Tir hyperprécis / Focalisation", "Tir hyperprécis Infliger des dégâts de compétence à un champion à au moins 600 unités de distance le révèle pendant 6 sec. Focalisation Quand Tir hyperprécis se déclenche, vous révélez tous les autres champions ennemis dans un rayon de 1400 unités de distance de votre cible pendant 3 sec.")
                .recipe("fiendish_codex", "fiendish_codex", "amplifying_tome"));
        reg(new LolItem("malignance", "Malfaisance", 2700, Material.PURPLE_STAINED_GLASS, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1890).ap(90).mana(600).ah(15)
                .passive("Mépris / Brouillard de haine", "Mépris Vous gagnez +20 accélération d'ultime. Brouillard de haine Lorsque vous blessez un champion avec votre ultime, vous brûlez le sol sous ses pieds pendant 3 sec, ce qui lui inflige des dégâts magiques par seconde et réduit sa résistance magique.")
                .recipe("lost_chapter", "blasting_wand"));
        reg(new LolItem("randuin_s_omen", "Présage de Randuin", 2700, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.LEGENDARY).sell(1890).hp(350).armor(75)
                .passive("Vigueur", "Vigueur Les coups critiques vous infligent 30% de dégâts en moins. Humilité Vous ralentissez les ennemis proches de 70% pendant 2 sec.")
                .active("Humilité", "Vous ralentissez les ennemis proches de 70% pendant 2 sec.")
                .recipe("warden_mail", "giant_s_belt"));
        reg(new LolItem("spirit_visage", "Visage spirituel", 2700, Material.NETHER_BRICK, TANK).tier(LolItem.Tier.LEGENDARY).sell(1890).hp(400).mr(50).ah(10).hpRegenPct(1)
                .passive("Vitalité intarissable", "Vitalité intarissable Les soins et les boucliers que vous recevez sont augmentés de 25%.")
                .recipe("spectre_cowl", "kindlegem"));
        reg(new LolItem("axiom_arc", "Arc axiomatique", 2750, Material.PRISMARINE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(1925).ad(55).lethality(18).ah(20)
                .passive("Flux", "Flux Quand un champion meurt dans les 3 sec après que vous l'avez blessé, le délai de récupération de votre compétence ultime est réduit d'un pourcentage de son total.")
                .recipe("the_brutalizer", "caulfields"));
        reg(new LolItem("ludens_echo", "Écho de Luden", 2750, Material.AMETHYST_SHARD, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1925).ap(100).mana(600).ah(10)
                .passive("Écho", "Écho Les compétences blessantes tirent 6 échos qui infligent des dégâts magiques bonus à la cible et aux ennemis proches. Les échos restants sont redirigés vers la cible principale et lui infligent 20% des dégâts.")
                .recipe("lost_chapter", "hextech_alternator"));
        reg(new LolItem("actualizer", "Actualisateur", 2800, Material.ENCHANTED_BOOK, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1960).ap(90).mana(300).ah(10)
                .active("Renforts de mana", "(0 sec) Pendant 8 sec, votre mana est renforcé. Pendant que votre mana est renforcé, vos compétences coûtent 100% de mana en plus, vos dégâts, vos boucliers, et vos soins sont augmentés de 0, et le délai de récupération de vos compétences de base est réduit de 30%.")
                .recipe("lost_chapter", "blasting_wand"));
        reg(new LolItem("blackfire_torch", "Torche noire", 2800, Material.TORCH, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1960).ap(80).mana(600).ah(20)
                .passive("Flamme funèbre / Feu noir / Flamme funèbre.", "Flamme funèbre Les compétences blessantes infligent des dégâts magiques bonus pendant 3 sec. Feu noir Vous gagnez +4% de puissance par champion ennemi, monstre épique ou grand monstre affecté par votre Flamme funèbre.")
                .recipe("lost_chapter", "fated_ashes"));
        reg(new LolItem("force_of_nature", "Force de la nature", 2800, Material.GRASS_BLOCK, TANK).tier(LolItem.Tier.LEGENDARY).sell(1960).hp(400).mr(55).msPct(0.04)
                .passive("Immuable", "Immuable Vous gagnez +70 résistance magique et +6% de vitesse de déplacement bonus après avoir subi des dégâts magiques de la part de champions 8 fois.")
                .recipe("negatron_cloak", "ruby_crystal", "winged_moonplate"));
        reg(new LolItem("hexoptics_c44", "Lunettes Hextech C44", 2800, Material.SPYGLASS, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(1960).ad(55).crit(0.25)
                .passive("Grossissement / Visée arcanique", "Grossissement Vous infligez jusqu'à 10% de dégâts bonus avec vos attaques, en fonction de la distance entre vous et l'ennemi (vous infligez le maximum de dégâts lorsque vous êtes à une portée de 500 unités). Visée arcanique Quand un champion auquel vous avez infligé des dégâts meurt dans les 3 sec, vous gagnez 100 de portée d'attaque bonus pendant 8 sec.")
                .recipe("pickaxe", "noonquiver", "long_sword"));
        reg(new LolItem("hollow_radiance", "Rayonnement du vide", 2800, Material.PURPLE_STAINED_GLASS, TANK).tier(LolItem.Tier.LEGENDARY).sell(1960).hp(400).mr(40).ah(10).hpRegenPct(1)
                .passive("Immolation / Désolation", "Immolation Subir ou infliger des dégâts vous permet d'infliger chaque seconde des dégâts magiques aux ennemis proches pendant 3 sec. Désolation Éliminer un ennemi inflige des dégâts magiques autour de lui.")
                .recipe("bamis_cinder", "spectre_cowl"));
        reg(new LolItem("hubris", "Orgueil", 2800, Material.DIAMOND, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(1960).ad(55).lethality(18).ah(10)
                .passive("Éminence", "Éminence Quand un champion meurt dans les 3 sec après que vous l'avez blessé, vous gagnez +12 dégâts d'attaque (+3 par champion tué) pendant 90 sec.")
                .recipe("serrated_dirk", "caulfields"));
        reg(new LolItem("stormsurge", "Onde orageuse", 2800, Material.LIGHTNING_ROD, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1960).ap(90).flatMagicPen(15).msPct(0.06)
                .passive("Dresseur d'orage / Rafale", "Dresseur d'orage Infliger des dégâts équivalents à 25% des PV max du champion ciblé en moins de 2.5 sec applique Rafale sur lui. Rafale Après 2 sec, vous infligez des dégâts magiques. Si la cible meurt avant que Rafale ne se déclenche, ses dégâts sont infligés aux ennemis proches.")
                .recipe("hextech_alternator", "aether_wisp"));
        reg(new LolItem("sunfire_aegis", "Égide solaire", 2800, Material.MAGMA_BLOCK, TANK).tier(LolItem.Tier.LEGENDARY).sell(1960).hp(350).armor(50).ah(10)
                .passive("Immolation", "Immolation Subir ou infliger des dégâts vous permet d'infliger chaque seconde des dégâts magiques aux ennemis proches pendant 3 sec.")
                .recipe("bamis_cinder", "chain_vest", "ruby_crystal"));
        reg(new LolItem("umbral_glaive", "Glaive d'ombre", 2800, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(1960).ad(60).lethality(18).ah(15)
                .passive("Traqueur nocturne / Black-out", "Traqueur nocturne Après être resté invisible pour les ennemis quelques secondes, votre prochaine attaque contre un champion inflige des dégâts bruts bonus. Black-out Quand vous êtes proche d'une balise ou d'un piège camouflé ennemi, vous les révélez pendant quelques secondes. Lorsque vous révélez des balises, vos attaques leur infligent des dégâts bonus.")
                .recipe("serrated_dirk", "caulfields"));
        reg(new LolItem("unending_despair", "Désespoir infini", 2800, Material.CRYING_OBSIDIAN, TANK).tier(LolItem.Tier.LEGENDARY).sell(1960).hp(400).armor(50).ah(15)
                .passive("Affliction", "Affliction Toutes les 4 sec de combat contre un champion, vous infligez des dégâts magiques aux champions ennemis proches et vous récupérez des PV équivalents à 250% des dégâts infligés.")
                .recipe("chain_vest", "kindlegem", "ruby_crystal"));
        reg(new LolItem("wits_end", "Au bout du rouleau", 2800, Material.DIAMOND_AXE, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(1960).as(0.5).mr(45).tenacity(0.2)
                .passive("Conflit", "Conflit Vos attaques infligent des dégâts magiques bonus à l'impact.")
                .recipe("recurve_bow", "negatron_cloak", "recurve_bow"));
        reg(new LolItem("youmuus_ghostblade", "Lame spectre de Youmuu", 2800, Material.GOLDEN_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(1960).ad(55).lethality(18).msPct(0.04)
                .passive("Hantise", "Hantise Vous gagnez +0 vitesse de déplacement en dehors des combats. Marche spectrale Vous gagnez de la vitesse de déplacement et devenez fantomatique pendant 0 sec.")
                .active("Marche spectrale", "Vous gagnez de la vitesse de déplacement et devenez fantomatique pendant 0 sec.")
                .recipe("serrated_dirk", "rectrix", "long_sword"));
        reg(new LolItem("morellonomicon", "Morellonomicon", 2850, Material.NETHERRACK, MAGE).tier(LolItem.Tier.LEGENDARY).sell(1995).ap(75).hp(350).ah(15)
                .passive("Hémorragie", "Hémorragie Infliger des dégâts magiques aux champions leur inflige 40% d'Hémorragie pendant 3 sec.")
                .recipe("oblivion_orb", "blasting_wand", "kindlegem"));
        reg(new LolItem("profane_hydra", "Hydre profane", 2850, Material.IRON_AXE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(1995).ad(55).lethality(18).ah(10)
                .passive("Fendoir", "Fendoir Vos attaques infligent des dégâts physiques aux ennemis proches. Balayage hérétique Vous infligez des dégâts physiques autour de vous.")
                .active("Balayage hérétique", "Vous infligez des dégâts physiques autour de vous.")
                .recipe("tiamat", "the_brutalizer"));
        reg(new LolItem("archangel_s_staff", "Bâton de l'archange", 2900, Material.BLAZE_ROD, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2030).ap(70).mana(600).ah(25)
                .passive("Effroi / Flux de mana", "Effroi Vous gagnez de la puissance équivalente à 1% de votre mana bonus. Flux de mana (8 sec, max 5 charges) Quand vous touchez une cible avec une compétence, vous gagnez +5 mana max (doublé contre les champions). Cet objet se transforme en Étreinte du séraphin à +360 mana max.")
                .recipe("tear_of_goddess", "lost_chapter", "fiendish_codex"));
        reg(new LolItem("bloodletter_s_curse", "Malédiction du sanguinaire", 2900, Material.BLAZE_ROD, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2030).ap(65).hp(400).ah(15)
                .passive("Vile décomposition", "Vile décomposition Infliger des dégâts magiques avec des compétences ou des compétences passives à des champions réduit leur résistance magique de 7.5% pendant 6 sec. (Cumulable 4 fois).")
                .recipe("haunting_guise", "fiendish_codex"));
        reg(new LolItem("dead_man_s_plate", "Plaque du mort", 2900, Material.IRON_CHESTPLATE, TANK).tier(LolItem.Tier.LEGENDARY).sell(2030).hp(350).armor(55).msPct(0.04)
                .passive("Naufrageur / Insubmersible", "Naufrageur Vous déplacer vous octroie jusqu'à +20 vitesse de déplacement bonus. Votre prochaine attaque consomme cette vitesse de déplacement bonus pour infliger des dégâts physiques bonus. Insubmersible Réduit de 15% l'efficacité des ralentissements.")
                .recipe("winged_moonplate", "ruby_crystal", "chain_vest"));
        reg(new LolItem("eclipse", "Éclipse", 2900, Material.ORANGE_DYE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2030).ad(60).ah(15)
                .passive("Lune ascendante", "Lune ascendante Quand vous touchez un champion avec 2 attaques ou compétences distinctes en moins de 2 sec, vous gagnez un bouclier pendant 2 sec.")
                .recipe("caulfields", "pickaxe", "long_sword"));
        reg(new LolItem("iceborn_gauntlet", "Gantelet givrant", 2900, Material.PACKED_ICE, TANK).tier(LolItem.Tier.LEGENDARY).sell(2030).hp(300).armor(50).ah(15)
                .passive("Lame enchantée", "Lame enchantée Après avoir utilisé une de vos compétences, votre prochaine attaque inflige des dégâts physiques bonus à l'impact et crée un champ de givre pendant 2 sec qui ralentit les ennemis.")
                .recipe("sheen", "ruby_crystal", "chain_vest"));
        reg(new LolItem("kaenic_rookern", "Rookern kaénique", 2900, Material.WHITE_WOOL, TANK).tier(LolItem.Tier.LEGENDARY).sell(2030).hp(400).mr(80).hpRegenPct(1)
                .passive("Fléau des mages", "Fléau des mages Après n'avoir subi aucun dégât magique pendant 15 sec, vous gagnez un bouclier anti-dégâts magiques.")
                .recipe("spectre_cowl", "negatron_cloak"));
        reg(new LolItem("lich_bane", "Fléau de liche", 2900, Material.PRISMARINE_CRYSTALS, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2030).ap(100).msPct(0.06).ah(10)
                .passive("Lame enchantée", "Lame enchantée Après avoir utilisé une de vos compétences, votre prochaine attaque inflige des dégâts magiques bonus à l'impact.")
                .recipe("sheen", "aether_wisp", "blasting_wand"));
        reg(new LolItem("manamune", "Manamune", 2900, Material.BUCKET, UTILITY).tier(LolItem.Tier.LEGENDARY).sell(2030).ad(35).mana(500).ah(15)
                .passive("Effroi / Flux de mana", "Effroi Vous gagnez +0 dégâts d'attaque bonus. Flux de mana (8 sec, max 4 charges) Quand vous touchez une cible avec une attaque ou une compétence, vous gagnez +3 mana max (doublé contre les champions). Cet objet se transforme en Muramana à +360 mana max.")
                .recipe("tear_of_goddess", "caulfields", "long_sword"));
        reg(new LolItem("muramana", "Muramana", 2900, Material.WATER_BUCKET, UTILITY).tier(LolItem.Tier.LEGENDARY).sell(2030).unpurchasable().ad(35).mana(1000).ah(15)
                .passive("Effroi / Choc", "Effroi Vous gagnez des dégâts d'attaque bonus équivalents à 2% de votre mana max. Choc Contre les champions, vos attaques infligent des dégâts physiques bonus équivalents à 1.2% de votre mana max à l'impact. Contre les champions, vos compétences blessantes infligent des dégâts physiques bonus équivalents à 3% - 4% de votre mana max."));
        reg(new LolItem("nashors_tooth", "Dent de Nashor", 2900, Material.BONE, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(2030).ap(80).as(0.5).ah(15)
                .passive("Morsure d'Icathia", "Morsure d'Icathia Vos attaques infligent des dégâts magiques bonus à l'impact.")
                .recipe("recurve_bow", "blasting_wand", "fiendish_codex"));
        reg(new LolItem("seraphs_embrace", "Étreinte du séraphin", 2900, Material.CYAN_DYE, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2030).unpurchasable().ap(70).mana(1000).ah(25)
                .passive("Effroi / Lien vital", "Effroi Vous gagnez +0 puissance. Lien vital (0 sec) Si des dégâts sont censés vous laisser moins de 30% de vos PV, vous gagnez au préalable un bouclier de 0 PV pendant 3 sec."));
        reg(new LolItem("banshee_s_veil", "Voile de la banshee", 3000, Material.BLAZE_ROD, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ap(105).mr(40)
                .passive("Invalidation", "Invalidation Vous gagnez un bouclier antisorts qui bloque la prochaine compétence ennemie.")
                .recipe("needlessly_large_rod", "verdant_barrier"));
        reg(new LolItem("bastionbreaker", "Briseur de bastion", 3000, Material.NETHERITE_PICKAXE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(55).lethality(22).ah(15)
                .passive("Double dégâts / Sabotage", "Double dégâts (0 sec) Infliger des dégâts de compétence à un champion ou un monstre épique vous permet d'infliger 0 pts de dégâts bruts bonus. Sabotage Participer à l'élimination d'un champion dans les 3 sec après lui avoir infligé des dégâts vous confère Sabotage pendant 90 sec. Lorsque vous disposez de Sabotage, votre prochaine attaque contre un monstre épique ou une tourelle inflige 0 pts de dégâts bruts bonus en 3 sec.")
                .recipe("the_brutalizer", "serrated_dirk"));
        reg(new LolItem("black_cleaver", "Couperet noir", 3000, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(45).hp(400).ah(20)
                .passive("Découpage / Ferveur", "Découpage Infliger des dégâts physiques aux champions ennemis avec des compétences, des compétences passives et des attaques réduit leur armure de 6% pendant 6 sec. (se cumule 5 fois). Ferveur Infliger des dégâts physiques vous octroie 0 vitesse de déplacement pendant 2 sec.")
                .recipe("phage", "kindlegem", "pickaxe"));
        reg(new LolItem("chempunk_chainsword", "Épée dentelée chimico-punk", 3000, Material.IRON_HOE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(45).hp(450).ah(15)
                .passive("Cisailles", "Cisailles Infliger des dégâts physiques aux champions ennemis leur inflige 40% d'Hémorragie pendant 3 sec.")
                .recipe("executioner_s_calling", "giant_s_belt", "caulfields"));
        reg(new LolItem("cosmic_drive", "Volonté cosmique", 3000, Material.NETHER_BRICK, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ap(70).hp(350).ah(25).msPct(0.04)
                .passive("Danse des sorts", "Danse des sorts Infliger des dégâts magiques ou bruts à des champions vous octroie de la vitesse de déplacement pendant 4 sec.")
                .recipe("kindlegem", "aether_wisp", "fiendish_codex"));
        reg(new LolItem("cryptbloom", "Fleur de crypte", 3000, Material.FERN, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ap(75).magicPen(0.3).ah(20)
                .passive("La vie par la mort", "La vie par la mort Quand un champion meurt dans les 3 sec après que vous l'avez blessé, une nova détone à l'emplacement de son corps, rendant des PV.")
                .recipe("blighting_jewel", "fiendish_codex", "fiendish_codex"));
        reg(new LolItem("edge_of_night", "Manteau de la nuit", 3000, Material.BLACK_STAINED_GLASS, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(50).lethality(15).hp(250)
                .passive("Invalidation", "Invalidation Vous gagnez un bouclier antisorts qui bloque la prochaine compétence ennemie.")
                .recipe("serrated_dirk", "tunneler"));
        reg(new LolItem("experimental_hexplate", "Hexplaque expérimentale", 3000, Material.COPPER_INGOT, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(40).as(0.2).hp(450)
                .passive("Charge Hextech / Surcharge", "Charge Hextech Vous gagnez 30 accélération d'ultime. Surcharge Après avoir lancé votre compétence ultime, vous gagnez 0% de vitesse d'attaque et 0% de vitesse de déplacement pendant 8 sec.")
                .recipe("tunneler", "dagger", "phage"));
        reg(new LolItem("rageblade", "Lame enragée de Guinsoo", 3000, Material.BLAZE_ROD, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(30).ap(30).as(0.25)
                .passive("Courroux / Frappe furieuse", "Courroux Vos attaques infligent 30 pts de dégâts magiques bonus à l'impact. Frappe furieuse Vos attaques vous octroient +8% de vitesse d'attaque pendant 4 sec (effet cumulable 4 fois). Une fois au max d'effets cumulés, toutes les trois attaques, vous appliquez deux fois vos effets à l'impact.")
                .recipe("amplifying_tome", "recurve_bow", "pickaxe"));
        reg(new LolItem("heartsteel", "Cœuracier", 3000, Material.RED_CONCRETE, TANK).tier(LolItem.Tier.LEGENDARY).sell(2100).hp(900).hpRegenPct(1)
                .passive("Consommation colossale / Goliath", "Consommation colossale (0 sec) par cible Si un champion ennemi reste proche pendant quelques sec, votre prochaine attaque contre lui inflige des dégâts physiques bonus équivalents à 70 + 6% de vos PV max et vous octroie des PV max équivalents à 10% des dégâts. Goliath Tous les 1000 PV max, votre taille augmente de 3% (jusqu'à 30%).")
                .recipe("giant_s_belt", "crystalline_bracer", "giant_s_belt"));
        reg(new LolItem("hextech_gunblade", "Pistolame Hextech", 3000, Material.BLAZE_ROD, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ap(80).ad(40).omnivamp(0.1)
                .active("Orbe de foudre", "Envoie une décharge au champion ennemi ciblé, ce qui lui inflige 0 pts de dégâts magiques et le ralentit de 25% pendant 1.5 sec.")
                .recipe("vampiric_scepter", "hextech_alternator", "amplifying_tome"));
        reg(new LolItem("hullbreaker", "Brise-coques", 3000, Material.OAK_LOG, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(40).hp(500).msPct(0.04)
                .passive("Pilote / Section d'abordage", "Pilote Toutes les cinq attaques contre des champions ou des monstres épiques, vous infligez des dégâts physiques bonus. Ces dégâts sont augmentés contre les bâtiments. Section d'abordage Les super-sbires et sbires de siège alliés proches gagnent de l'armure et de la résistance magique.")
                .recipe("tunneler", "winged_moonplate", "pickaxe"));
        reg(new LolItem("immortal_shieldbow", "Arc-bouclier immortel", 3000, Material.CROSSBOW, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(55).crit(0.25)
                .passive("Lien vital", "Lien vital Si des dégâts sont censés vous laisser moins de 30% de vos PV, vous gagnez au préalable un bouclier pendant 3 sec.")
                .recipe("pickaxe", "noonquiver"));
        reg(new LolItem("kraken_slayer", "Tueur de krakens", 3000, Material.TRIDENT, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(45).as(0.4).msPct(0.04)
                .passive("Trempe", "Trempe Toutes les trois attaques, vous infligez des dégâts physiques bonus à l'impact (ces dégâts sont augmentés en fonction des PV manquants de la cible).")
                .recipe("rectrix", "hearthbound_axe", "recurve_bow"));
        reg(new LolItem("liandry_anguish", "Tourment de Liandry", 3000, Material.CAMPFIRE, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ap(60).hp(300)
                .passive("Tourment / Souffrance", "Tourment Les compétences blessantes brûlent les ennemis, leur infligeant 2% de leurs PV max en dégâts magiques chaque seconde pendant 3 sec. Souffrance Pour chaque seconde passée en combat avec des champions ennemis, vous infligez 2% de dégâts bonus (jusqu'à 6%).")
                .recipe("haunting_guise", "fated_ashes"));
        reg(new LolItem("mortal_reminder", "Rappel mortel", 3000, Material.BONE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(35).armorPenPct(0.3).crit(0.25)
                .passive("Hémorragie", "Hémorragie Infliger des dégâts physiques aux champions ennemis leur inflige 40% d'Hémorragie pendant 3 sec.")
                .recipe("executioner_s_calling", "last_whisper", "cloak_agility"));
        reg(new LolItem("serylda_s_grudge", "Rancune de Serylda", 3000, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(45).armorPenPct(0.35).ah(15)
                .passive("Froid mordant", "Froid mordant Vos compétences blessantes ralentissent de 30% pendant 1 sec les ennemis qui ont moins de 50% de leurs PV.")
                .recipe("caulfields", "last_whisper"));
        reg(new LolItem("statikk_shiv", "Poignard de Statikk", 3000, Material.LIGHTNING_ROD, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(45).ap(45).as(0.3).msPct(0.04)
                .passive("Étincelle électrique / Électrochoc", "Étincelle électrique Vos attaques énergisées lancent une chaîne d'éclairs, appliquant effets à l'impact et infligeant des dégâts magiques aux cibles proches. Électrochoc Les attaques de base octroient des effets énergisants supplémentaires.")
                .recipe("scout_s_slingshot", "aether_wisp", "pickaxe"));
        reg(new LolItem("terminus", "Terminus", 3000, Material.IRON_NUGGET, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(30).as(0.35)
                .passive("Ombre / Juxtaposition", "Ombre Vos attaques infligent 0 pts de dégâts magiques bonus à l'impact. Juxtaposition Vous alternez entre des attaques lumineuses et ténébreuses contre les champions : • Les attaques lumineuses vous octroient de l'armure et de la résistance magique pendant 5 sec. • Les attaques ténébreuses vous octroient +10% de pénétration d'armure et de pénétration magique pendant 5 sec.")
                .recipe("hearthbound_axe", "recurve_bow"));
        reg(new LolItem("the_collector", "Percepteur", 3000, Material.DIAMOND_PICKAXE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(50).lethality(10).crit(0.25)
                .passive("La vie / La bourse", "La vie Vos dégâts exécutent les champions qui ont moins de 5% de leurs PV. La bourse Éliminer un champion vous octroie 25 PO bonus.")
                .recipe("pickaxe", "serrated_dirk", "cloak_agility"));
        reg(new LolItem("void_staff", "Bâton du vide", 3000, Material.END_ROD, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ap(95).magicPen(0.4)
                .recipe("blighting_jewel", "blasting_wand"));
        reg(new LolItem("voltaic_cyclosword", "Cyclosabre voltaïque", 3000, Material.STICK, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(55).lethality(10).ah(10)
                .passive("Galvanisation / Firmament", "Galvanisation Infliger des dégâts à un champion ennemi avec une compétence déclenche Énergisé si l'effet est prêt. Firmament Votre attaque énergisée inflige des dégâts physiques bonus équivalents à un % des PV actuels de la cible et vous confère de la létalité bonus pendant 4 sec.")
                .recipe("the_brutalizer", "long_sword", "long_sword"));
        reg(new LolItem("yun_tal_wildarrows", "Flèches des Yun Tal", 3000, Material.BOW, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(2100).ad(50).as(0.45).crit(0)
                .passive("Entraînement létal / Déluge", "Entraînement létal À l'attaque, vous gagnez définitivement des chances de coup critique (max +25%). Déluge En attaquant un champion ennemi, vous gagnez +30% de vitesse d'attaque pendant 6 sec (30 sec de récupération). Vos attaques réduisent ce délai de récupération de 1 sec. Vos coups critiques réduisent ce délai de récupération de 2 sec à la place.")
                .recipe("bf_sword", "scout_s_slingshot", "long_sword"));
        reg(new LolItem("essence_reaver", "Faux spectrale", 3050, Material.DIAMOND_HOE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2135).ad(50).ah(20).crit(0.25)
                .passive("Lame enchantée", "Lame enchantée Après avoir utilisé une compétence, votre prochaine attaque inflige pts de dégâts physiques bonus, et vous octroie du mana à l'impact.")
                .recipe("sheen", "caulfields", "cloak_agility"));
        reg(new LolItem("dusk_and_dawn", "Aube et crépuscule", 3100, Material.PURPLE_DYE, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2170).hp(300).ap(60).ah(20).as(0.2)
                .passive("Lame enchantée", "Lame enchantée (0 sec) Après l'utilisation d'une compétence, votre prochaine attaque inflige 0 pts de dégâts magiques bonus, vous soigne de 0 PV à l'impact et applique les effets à l'impact une fois de plus.")
                .recipe("sheen", "blasting_wand", "kindlegem", "dagger"));
        reg(new LolItem("unending_fury", "Faim insatiable", 3100, Material.RAW_IRON, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2170).ad(65).omnivamp(0.05).tenacity(0.2)
                .passive("Famine / Festin", "Famine Vous gagnez de l'accélération de compétence selon vos dégâts d'attaque bonus. Festin Quand un champion auquel vous avez infligé des dégâts récemment meurt, vous gagnez de l'omnivampirisme pendant quelques secondes.")
                .recipe("caulfields", "pickaxe", "long_sword"));
        reg(new LolItem("maw", "Gueule de Malmortius", 3100, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2170).ad(60).ah(15).mr(40)
                .passive("Lien vital", "Lien vital Si des dégâts magiques sont censés vous laisser moins de 30% de vos PV, vous gagnez au préalable un bouclier anti-dégâts magiques pendant 3 sec et +10% d'omnivampirisme jusqu'à la fin du combat.")
                .recipe("hexdrinker", "caulfields"));
        reg(new LolItem("riftmaker", "Créateur de failles", 3100, Material.END_CRYSTAL, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2170).ap(70).hp(350).ah(15)
                .passive("Corruption du Néant / Infusion du Néant", "Corruption du Néant Pour chaque seconde passée en combat avec des champions ennemis, vous infligez 2% de dégâts bonus (jusqu'à 8%). Une fois le pourcentage max atteint, vous gagnez de l'omnivampirisme. Infusion du Néant Vous gagnez de la puissance équivalente à 2% de vos PV bonus.")
                .recipe("haunting_guise", "fiendish_codex"));
        reg(new LolItem("spear_of_shojin", "Lance de Shojin", 3100, Material.STICK, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2170).ad(45).hp(450)
                .passive("Force du dragon / Détermination inflexible", "Force du dragon Vous gagnez +25 accélération de compétence de base. Détermination inflexible Infliger des dégâts avec vos compétences augmente vos dégâts de compétence de champion et de compétence passive de 3% pendant 6 sec (effet cumulable 4 fois).")
                .recipe("pickaxe", "tunneler", "ruby_crystal"));
        reg(new LolItem("sundered_sky", "Ciel éventré", 3100, Material.DIAMOND_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2170).ad(40).hp(400).ah(10)
                .passive("Frappe des Lightshield", "Frappe des Lightshield Votre première attaque contre un champion lui porte un coup critique et vous rend des PV.")
                .recipe("tunneler", "caulfields"));
        reg(new LolItem("warmogs_armor", "Armure de Warmog", 3100, Material.RED_WOOL, TANK).tier(LolItem.Tier.LEGENDARY).sell(2170).hp(1000).hpRegenPct(1)
                .passive("Cœur de Warmog / Vitalité de Warmog", "Cœur de Warmog Si vous avez 2000 PV bonus et n'avez pas subi de dégâts pendant 8 sec, vous récupérez 0 PV par seconde. Vitalité de Warmog Vous gagnez des PV bonus équivalents à 12% de vos PV d'objets (0).")
                .recipe("giant_s_belt", "giant_s_belt", "crystalline_bracer"));
        reg(new LolItem("botrk", "Lame du roi déchu", 3200, Material.GOLDEN_AXE, ATTACK_SPEED).tier(LolItem.Tier.LEGENDARY).sell(2240).ad(40).as(0.25).lifeSteal(0.1)
                .passive("Fil de brume / Griffe des ombres", "Fil de brume Vos attaques infligent des dégâts physiques bonus à l'impact équivalents à un pourcentage des PV actuels de l'ennemi. Griffe des ombres Attaquer un champion 3 fois le ralentit de 30% pendant 1 sec.")
                .recipe("vampiric_scepter", "recurve_bow", "pickaxe"));
        reg(new LolItem("guardian_angel", "Ange gardien", 3200, Material.TOTEM_OF_UNDYING, UTILITY).tier(LolItem.Tier.LEGENDARY).sell(1280).ad(55).armor(45)
                .passive("Renaissance", "Renaissance En cas de coup fatal, vous revenez à la vie avec 50% de vos PV de base et 100% de votre mana max après 4 sec de stase.")
                .recipe("steel_sigil", "bf_sword"));
        reg(new LolItem("jaksho", "Jak'Sho, le Protéiforme", 3200, Material.ENDER_EYE, TANK).tier(LolItem.Tier.LEGENDARY).sell(2240).hp(350).armor(45).mr(45)
                .passive("Vigueur de Néantin", "Vigueur de Néantin Après 5 sec de combat contre des champions, vous augmentez votre armure et votre résistance magique bonus de 30% jusqu'à la fin du combat.")
                .recipe("giant_s_belt", "chain_vest", "negatron_cloak"));
        reg(new LolItem("mercurial_scimitar", "Cimeterre mercuriel", 3200, Material.GOLDEN_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2240).ad(50).mr(35).lifeSteal(0.1)
                .active("Mercure", "Vous dissipez tous les contrôles de foule qui vous affectent (sauf les projections dans les airs) et gagnez de la vitesse de déplacement.")
                .recipe("quicksilver_sash", "pickaxe", "vampiric_scepter"));
        reg(new LolItem("shadowflame", "Flamme-ombre", 3200, Material.BLAZE_POWDER, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2240).ap(110).flatMagicPen(15)
                .passive("Floraicendre", "Floraicendre Vos dégâts magiques et vos dégâts bruts infligent des coups critiques aux ennemis qui ont moins de 40% de leurs PV, leur infligeant 20% de dégâts bonus.")
                .recipe("hextech_alternator", "needlessly_large_rod"));
        reg(new LolItem("steraks_gage", "Gage de Sterak", 3200, Material.IRON_CHESTPLATE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2240).hp(400).tenacity(0.2)
                .passive("Griffes qui happent / Lien vital", "Griffes qui happent Vous gagnez des dégâts d'attaque bonus. Lien vital Si des dégâts sont censés vous laisser moins de 30% de vos PV, vous gagnez au préalable un bouclier qui se désagrège progressivement pendant 4.5 sec.")
                .recipe("pickaxe", "tunneler", "ruby_crystal"));
        reg(new LolItem("stormrazor", "Lame tempête", 3200, Material.SPECTRAL_ARROW, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2240).ad(50).as(0.25).crit(0.25)
                .passive("Énergisant / Éclair", "Énergisant Vos déplacements et vos attaques permettent d'énergiser une attaque. Éclair Vos attaques énergisées appliquent 0 pts de dégâts magiques bonus et vous octroient +45% de vitesse de déplacement pendant 1.5 sec.")
                .recipe("bf_sword", "cloak_agility", "scout_s_slingshot"));
        reg(new LolItem("zhonyas_hourglass", "Sablier de Zhonya", 3250, Material.CLOCK, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2275).ap(105).armor(50)
                .active("Pause temporelle", "Vous entrez en stase pendant 2.5 sec.")
                .recipe("needlessly_large_rod", "seeker_s_armguard"));
        reg(new LolItem("death_s_dance", "Danse de la mort", 3300, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2310).ad(60).armor(50).ah(15)
                .passive("Insensible à la douleur / Massacre", "Insensible à la douleur Un pourcentage des dégâts subis vous sont infligés en différé en 3 sec. Massacre Quand un champion meurt dans les 3 sec après que vous l'avez blessé, vous purgez les dégâts restants d'Insensible à la douleur et vous récupérez des PV en 2 sec.")
                .recipe("steel_sigil", "pickaxe", "caulfields"));
        reg(new LolItem("lord_dominiks", "Salutations de Dominik", 3300, Material.DIAMOND_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2310).ad(35).armorPenPct(0.35).crit(0.25)
                .passive("Tueur de géants", "Tueur de géants Vous infligez jusqu'à 15% de dégâts bonus aux champions en fonction de leurs PV bonus. Bonus de dégâts maximum atteint à partir de 1500 PV bonus.")
                .recipe("last_whisper", "noonquiver"));
        reg(new LolItem("overlord_s_bloodmail", "Armure sanguine", 3300, Material.IRON_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2310).ad(30).hp(550)
                .passive("Tyrannie / Représailles", "Tyrannie Vous gagnez des dégâts d'attaque équivalents à 2.5% de vos PV bonus. Représailles Vous gagnez jusqu'à +12% de dégâts d'attaque selon un pourcentage de vos PV manquants.")
                .recipe("tunneler", "tunneler"));
        reg(new LolItem("ravenous_hydra", "Hydre vorace", 3300, Material.IRON_AXE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2310).ad(65).ah(15).lifeSteal(0.12)
                .passive("Fendoir", "Fendoir Vos attaques infligent des dégâts physiques aux ennemis proches. Croissant vorace Vous infligez des dégâts physiques aux ennemis autour de vous. Le vol de vie s'applique à ces dégâts.")
                .active("Croissant vorace", "Vous infligez des dégâts physiques aux ennemis autour de vous. Le vol de vie s'applique à ces dégâts.")
                .recipe("tiamat", "vampiric_scepter", "caulfields"));
        reg(new LolItem("stridebreaker", "Estropieur", 3300, Material.DIAMOND_AXE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2310).ad(40).as(0.25).hp(450)
                .passive("Fendoir", "Fendoir Vos attaques infligent des dégâts physiques aux ennemis proches. Onde de choc entravante Vous infligez des dégâts physiques aux ennemis proches et les ralentissez de 35%. Vous gagnez +35% de vitesse de déplacement par champion touché, et ce bonus diminue en 3 sec.")
                .active("Onde de choc entravante", "Vous infligez des dégâts physiques aux ennemis proches et les ralentissez de 35%. Vous gagnez +35% de vitesse de déplacement par champion touché, et ce bonus diminue en 3 sec.")
                .recipe("tiamat", "phage", "dagger"));
        reg(new LolItem("titanic_hydra", "Hydre titanesque", 3300, Material.NETHERITE_AXE, TANK).tier(LolItem.Tier.LEGENDARY).sell(2310).ad(40).hp(600)
                .passive("Fendoir", "Fendoir Vos attaques infligent des dégâts physiques à l'impact et aux ennemis derrière la cible. Croissant titanesque Vous renforcez votre prochain Fendoir et infligez des dégâts physiques bonus à l'impact et des dégâts physiques bonus aux ennemis derrière la cible.")
                .active("Croissant titanesque", "Vous renforcez votre prochain")
                .recipe("tiamat", "tunneler", "giant_s_belt"));
        reg(new LolItem("trinity_force", "Force de la trinité", 3333, Material.NETHER_STAR, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2333).ad(36).as(0.3).hp(333).ah(15)
                .passive("Lame enchantée / Accélération", "Lame enchantée Après avoir utilisé une de vos compétences, votre prochaine attaque inflige des dégâts physiques bonus à l'impact. Accélération Vos attaques vous octroient +20 vitesse de déplacement pendant 2 sec.")
                .recipe("sheen", "phage", "hearthbound_axe"));
        reg(new LolItem("bloodthirster", "Soif-de-sang", 3400, Material.RED_CONCRETE, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2380).ad(80).lifeSteal(0.15)
                .passive("Bouclier d'ichor", "Bouclier d'ichor Convertit les soins excédentaires reçus grâce à votre vol de vie en un bouclier.")
                .recipe("bf_sword", "pickaxe", "vampiric_scepter"));
        reg(new LolItem("infinity_edge", "Lame d'infini", 3500, Material.NETHERITE_SWORD, DAMAGE).tier(LolItem.Tier.LEGENDARY).sell(2450).ad(75).crit(0.25).critDmg(0.3)
                .recipe("bf_sword", "pickaxe", "cloak_agility"));
        reg(new LolItem("rabadons_deathcap", "Coiffe de Rabadon", 3500, Material.POINTED_DRIPSTONE, MAGE).tier(LolItem.Tier.LEGENDARY).sell(2450).ap(130)
                .passive("Opus magique", "Opus magique Vous augmentez votre puissance totale de 30%.")
                .recipe("needlessly_large_rod", "needlessly_large_rod"));

        java.util.logging.Logger.getLogger("LolMC").info("[ItemRegistry] " + ITEMS.size() + " objets (patch 26.20) chargés.");
    }

    private static void reg(LolItem item) { ITEMS.put(item.getId(), item); }
    /** Force le chargement de cette classe (déclenche le bloc static d'enregistrement des objets). */
    public static void ensureLoaded() { /* no-op : l'appel suffit à déclencher le bloc static */ }
    public static LolItem get(String id)  { return ITEMS.get(id); }
    public static Collection<LolItem> all()             { return ITEMS.values(); }
    public static Map<String, LolItem> getAll()         { return ITEMS; }
    public static boolean exists(String id)             { return ITEMS.containsKey(id); }
    /** Objets achetables d'une catégorie (les transformations comme Muramana n'apparaissent pas). */
    public static List<LolItem> byCategory(LolItem.ItemCategory cat) {
        return ITEMS.values().stream().filter(i -> i.getCategory() == cat && i.isPurchasable()).toList();
    }

    /** Items dont la recette contient ce composant (= ce que ce composant construit). */
    public static List<LolItem> buildsInto(String componentId) {
        List<LolItem> out = new ArrayList<>();
        for (LolItem it : ITEMS.values()) {
            if (it.getBuildsFrom().contains(componentId)) out.add(it);
        }
        return out;
    }
}
