package fr.lolmc.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;

/**
 * Couche de compatibilité pour les API Bukkit/Paper qui ont changé
 * entre versions (notamment 1.21.x → 26.1.x).
 *
 * On résout les attributs et enchantements via leur clé namespacée,
 * qui reste stable, plutôt que via des constantes d'enum qui changent
 * de nom (GENERIC_MAX_HEALTH → MAX_HEALTH) ou disparaissent.
 */
public final class Compat {

    private Compat() {}

    // ── Attributs ─────────────────────────────────────────────────

    /** Attribut "max health" (anciennement GENERIC_MAX_HEALTH). */
    public static Attribute maxHealth() {
        return attribute("max_health", "generic.max_health");
    }

    /** Attribut "movement speed" (anciennement GENERIC_MOVEMENT_SPEED). */
    public static Attribute movementSpeed() {
        return attribute("movement_speed", "generic.movement_speed");
    }

    /** Attribut "attack damage". */
    public static Attribute attackDamage() {
        return attribute("attack_damage", "generic.attack_damage");
    }

    /** Attribut "scale" (taille de l'entité ET de sa hitbox, depuis 1.20.5). */
    public static Attribute scale() {
        return attribute("scale", "generic.scale");
    }

    /**
     * Résout un attribut par sa clé, en essayant plusieurs noms possibles
     * selon la version. Retourne null si introuvable (le code appelant
     * doit gérer ce cas).
     */
    private static Attribute attribute(String... keys) {
        for (String key : keys) {
            // Essai via Registry (méthode moderne)
            try {
                NamespacedKey nk = NamespacedKey.minecraft(normalizeKey(key));
                Attribute a = Registry.ATTRIBUTE.get(nk);
                if (a != null) return a;
            } catch (Throwable ignored) {}
        }
        // Dernier recours : parcourir le registre des attributs
        for (String key : keys) {
            try {
                Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key.toLowerCase().replace('_', '.')));
                if (a != null) return a;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Normalise une clé "generic.max_health" → "max_health". */
    private static String normalizeKey(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 ? key.substring(dot + 1) : key;
    }

    // ── Enchantements ─────────────────────────────────────────────

    /**
     * Enchantement utilisé pour faire "briller" un item (glow).
     * On utilise un enchantement quelconque (Unbreaking) résolu via Registry.
     */
    public static Enchantment glowEnchant() {
        // Registry moderne (Enchantment n'est plus une enum en 26.1.2)
        try {
            Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
            if (e != null) return e;
        } catch (Throwable ignored) {}
        // Fallback : premier enchantement disponible dans le registre
        try {
            for (Enchantment e : Registry.ENCHANTMENT) {
                if (e != null) return e;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
