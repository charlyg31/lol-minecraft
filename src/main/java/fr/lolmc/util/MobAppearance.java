package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ageable;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.Color;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;

/**
 * Système d'apparence custom pour les mobs (façon datapack "summon ... {NBT}").
 *
 * Regroupe les techniques utilisées pour donner une apparence personnalisée
 * à un mob SANS resource pack :
 *  - Tête de joueur texturée (helmet) -> change l'apparence de la tête (humanoïdes)
 *  - Armure de cuir teintée -> colore le corps (humanoïdes)
 *  - Objet en main thématique (humanoïdes)
 *  - Décoration en bloc flottant (BlockDisplay passager) -> fonctionne sur TOUS les mobs
 *  - Invisibilité du mob de base -> on ne voit que la décoration / la tête
 *  - Silencieux, bébé (petite taille)
 *
 * IMPORTANT : casque/armure/main ne s'affichent que sur les mobs humanoïdes
 * (zombie, skelette, husk, etc.). Pour les autres (slime, golem, ravageur...),
 * utiliser addFloatingBlock (décoration) qui marche partout.
 */
public final class MobAppearance {

    private MobAppearance() {}

    private static NamespacedKey decoKey;
    /** Clé PDC marquant une entité de décoration (pour le nettoyage). */
    public static NamespacedKey decoKey() {
        if (decoKey == null) decoKey = new NamespacedKey(LolPlugin.getInstance(), "deco");
        return decoKey;
    }

    // ══════════════════════════════════════════════════════════════
    // TÊTE DE JOUEUR TEXTURÉE (le cœur du "ressemblant")
    // ══════════════════════════════════════════════════════════════

    /**
     * Crée un player head avec une texture base64 (le JSON encodé
     * {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/..."}}}).
     * Les valeurs base64 se récupèrent sur minecraft-heads.com ou dans un datapack existant.
     */
    @SuppressWarnings("deprecation")
    public static ItemStack headFromTexture(String base64) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (base64 == null || base64.isEmpty()) return head;
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;
        try {
            String decoded = new String(Base64.getDecoder().decode(base64));
            String url = extractUrl(decoded);
            if (url != null) {
                PlayerProfile profile = LolPlugin.getInstance().getServer()
                        .createPlayerProfile(UUID.randomUUID());
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create(url).toURL());
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
            }
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("MobAppearance texture invalide: " + e.getMessage());
        }
        head.setItemMeta(meta);
        return head;
    }

    private static String extractUrl(String json) {
        int idx = json.indexOf("\"url\"");
        if (idx < 0) return null;
        int start = json.indexOf("http", idx);
        if (start < 0) return null;
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /** Pose une tête texturée sur le mob (humanoïdes uniquement). */
    public static void setHeadTexture(LivingEntity mob, String base64) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        eq.setHelmet(headFromTexture(base64));
        eq.setHelmetDropChance(0f);
    }

    // ══════════════════════════════════════════════════════════════
    // CASQUE / ARMURE / MAIN (humanoïdes)
    // ══════════════════════════════════════════════════════════════

    /** Met un bloc ou un objet comme casque (rendu sur la tête des humanoïdes). */
    public static void setHelmet(LivingEntity mob, Material material) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        eq.setHelmet(new ItemStack(material));
        eq.setHelmetDropChance(0f);
    }

    /** Met un objet dans la main principale (rendu sur les humanoïdes). */
    public static void setMainHand(LivingEntity mob, Material material) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        eq.setItemInMainHand(new ItemStack(material));
        eq.setItemInMainHandDropChance(0f);
    }

    /** Teinte tout le set d'armure de cuir d'une couleur RGB (colore le corps). */
    public static void dyeArmor(LivingEntity mob, int rgb) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        Color color = Color.fromRGB(rgb & 0xFFFFFF);
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, color));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, color));
        eq.setBoots(dyed(Material.LEATHER_BOOTS, color));
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
    }

    private static ItemStack dyed(Material mat, Color color) {
        ItemStack item = new ItemStack(mat);
        if (item.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(color);
            item.setItemMeta(lm);
        }
        return item;
    }

    // ══════════════════════════════════════════════════════════════
    // ÉTAT DU MOB (invisible, silencieux, bébé)
    // ══════════════════════════════════════════════════════════════

    /** Rend le mob de base invisible (on ne voit plus que la décoration / tête). */
    public static void makeInvisible(LivingEntity mob) {
        mob.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                Integer.MAX_VALUE, 0, false, false));
    }

    public static void setSilent(LivingEntity mob, boolean silent) {
        mob.setSilent(silent);
    }

    /** Réduit la taille (bébé) sur les mobs qui le supportent. */
    public static void setBaby(LivingEntity mob, boolean baby) {
        if (mob instanceof Ageable a) {
            if (baby) a.setBaby(); else a.setAdult();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // DÉCORATION EN BLOC FLOTTANT (universelle, marche sur TOUS les mobs)
    // ══════════════════════════════════════════════════════════════

    /**
     * Fait flotter un bloc au-dessus du mob (BlockDisplay passager qui le suit).
     * C'est la technique universelle pour "habiller" n'importe quel mob,
     * comme le tonneau sur l'ours ou l'arbre sur le mini-arbre du datapack.
     *
     * @param yOffset hauteur du bloc au-dessus du point de montage
     * @param scale   taille du bloc (1.0 = bloc plein)
     * @return le BlockDisplay créé (marqué pour nettoyage), ou null en cas d'échec
     */
    public static BlockDisplay addFloatingBlock(LivingEntity mob, Material block, float yOffset, float scale) {
        try {
            BlockDisplay disp = mob.getWorld().spawn(mob.getLocation(), BlockDisplay.class, d -> {
                d.setBlock(block.createBlockData());
                // Centrer le bloc (rendu depuis le coin) et appliquer l'échelle + hauteur
                d.setTransformation(new Transformation(
                        new Vector3f(-scale / 2f, yOffset, -scale / 2f),
                        new AxisAngle4f(0f, 0f, 0f, 1f),
                        new Vector3f(scale, scale, scale),
                        new AxisAngle4f(0f, 0f, 0f, 1f)));
                d.setPersistent(true);
                d.getPersistentDataContainer().set(decoKey(), PersistentDataType.BYTE, (byte) 1);
            });
            mob.addPassenger(disp);
            return disp;
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("MobAppearance deco echec: " + e.getMessage());
            return null;
        }
    }

    /** Une entité est-elle une décoration créée par ce système ? */
    public static boolean isDecoration(Entity e) {
        return e.getPersistentDataContainer().has(decoKey(), PersistentDataType.BYTE);
    }
}
