package fr.lolmc.util;

/**
 * Conversion entre les unités de distance de League of Legends et les blocs Minecraft.
 *
 * Ratio choisi : 100 unités LoL = 1 bloc Minecraft.
 *
 * Repères LoL officiels (portées d'attaque de base) :
 *   - Mêlée : 125-325 unités  → 1.25 à 3.25 blocs
 *   - Distance : 350-650 unités → 3.5 à 6.5 blocs
 *   - Sbire caster : 550 unités → 5.5 blocs
 *   - ADC typique : 525-550 unités → ~5.5 blocs
 *
 * Ce ratio garde les proportions de LoL tout en restant jouable à l'échelle
 * d'une carte Minecraft.
 */
public final class LolUnits {

    private LolUnits() {}

    /** Nombre d'unités LoL par bloc Minecraft. */
    public static final double UNITS_PER_BLOCK = 100.0;

    /** Convertit une distance en unités LoL vers des blocs Minecraft. */
    public static double toBlocks(double lolUnits) {
        return lolUnits / UNITS_PER_BLOCK;
    }

    /** Convertit une distance en blocs Minecraft vers des unités LoL. */
    public static double toUnits(double blocks) {
        return blocks * UNITS_PER_BLOCK;
    }

    // ── Portées d'attaque de référence (en blocs, prêtes à l'emploi) ──

    public static final double MELEE_SHORT   = toBlocks(125); // 1.25 blocs (mêlées courtes type Viego/Zed)
    public static final double MELEE_NORMAL  = toBlocks(175); // 1.75 blocs (mêlée standard)
    public static final double MELEE_LONG    = toBlocks(300); // 3.0 blocs (mêlée longue type Rakan)
    public static final double RANGED_SHORT  = toBlocks(450); // 4.5 blocs (distance courte)
    public static final double RANGED_NORMAL = toBlocks(525); // 5.25 blocs (ADC standard)
    public static final double RANGED_LONG   = toBlocks(650); // 6.5 blocs (Caitlyn, Aphelios)

    /**
     * Convertit une portée de SORT LoL en blocs.
     * Note : dans LoL, la portée de sort est ~125 unités de plus que la portée
     * d'attaque "bord à bord" (différence de hitbox). On applique le ratio direct.
     */
    public static double spellRange(double lolUnits) {
        return toBlocks(lolUnits);
    }
}
