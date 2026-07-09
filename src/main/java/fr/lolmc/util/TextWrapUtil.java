package fr.lolmc.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Découpage de texte en lignes pour l'affichage dans un lore d'item
 * (retour à la ligne par mot entier, sans coupure au milieu).
 * Factorisé depuis BaseAbility et LolItem qui avaient chacun leur
 * propre copie identique de cette logique.
 */
public final class TextWrapUtil {

    private TextWrapUtil() {}

    /**
     * Découpe {@code text} en lignes d'au plus {@code maxWidth} caractères,
     * en coupant uniquement entre les mots.
     */
    public static List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            if (cur.length() + word.length() + 1 > maxWidth) {
                lines.add(cur.toString().trim());
                cur = new StringBuilder();
            }
            cur.append(word).append(" ");
        }
        if (!cur.isEmpty()) lines.add(cur.toString().trim());
        return lines;
    }
}