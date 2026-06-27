package fr.lolmc.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.LivingEntity;

/**
 * Barre de vie affichée au-dessus des entités via customName.
 *
 * Format :  [████████░░] 1250/1800
 *
 * Couleur :
 *   > 50% HP  → vert
 *   25-50%    → jaune
 *   < 25%     → rouge
 *
 * Appelé depuis :
 *   - MinionManager  (sbires)
 *   - JungleManager  (monstres)
 *   - TurretManager  (tourelles)
 *   - HUDManager     (joueurs — au-dessus de la tête des autres)
 */
public final class HealthBar {

    private static final int BAR_LENGTH = 10;
    private static final char FILLED  = '█';
    private static final char EMPTY   = '░';

    private HealthBar() {}

    /**
     * Construit la barre de vie pour une entité.
     *
     * @param current  HP actuels
     * @param max      HP maximum
     * @param label    Nom affiché avant la barre (ex: "Sbire", "Gromp", "Tourelle T1")
     * @return Component à utiliser avec entity.customName(...)
     */
    public static Component build(double current, double max, String label) {
        if (max <= 0) max = 1;
        double ratio = Math.max(0, Math.min(1, current / max));
        int filled = (int) Math.round(ratio * BAR_LENGTH);

        NamedTextColor barColor = ratio > 0.50 ? NamedTextColor.GREEN
                                : ratio > 0.25 ? NamedTextColor.YELLOW
                                : NamedTextColor.RED;

        String bar = String.valueOf(FILLED).repeat(filled)
                   + String.valueOf(EMPTY).repeat(BAR_LENGTH - filled);

        int cur = (int) Math.max(0, current);
        int mx  = (int) max;

        return Component.join(
            net.kyori.adventure.text.JoinConfiguration.noSeparators(),
            Component.text(label + " ", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("[", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text(bar, barColor)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("] ", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text(cur + "/" + mx, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        );
    }

    /**
     * Applique la barre de vie sur une entité.
     * Active automatiquement customNameVisible.
     */
    public static void apply(LivingEntity entity, double current, double max, String label) {
        entity.customName(build(current, max, label));
        entity.setCustomNameVisible(true);
    }

    /**
     * Met à jour uniquement les HP (label inchangé — extrait du nom actuel).
     * Si le nom actuel n'est pas une barre de vie connue, reconstruit avec label vide.
     */
    public static void update(LivingEntity entity, double current, double max) {
        Component existing = entity.customName();
        // Extraire le label existant (tout avant "[")
        String label = "";
        if (existing != null) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(existing);
            int bracket = plain.indexOf('[');
            if (bracket > 0) label = plain.substring(0, bracket).trim();
        }
        apply(entity, current, max, label);
    }
}
