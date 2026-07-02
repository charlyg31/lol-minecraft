package fr.lolmc.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Death Recap — affiche les sources de dégâts à la mort d'un champion.
 *
 * Chaque dégât reçu dans les 5 dernières secondes est enregistré.
 * À la mort, un résumé est envoyé au joueur : source, type, montant.
 */
public class DeathRecapManager {

    private static final long RECAP_WINDOW_MS = 5_000L;

    /** Un coup individuel reçu. */
    public record DamageEntry(String source, String type, double amount, long timestamp) {}

    // UUID victim → liste de dégâts récents
    private final Map<UUID, List<DamageEntry>> recentDamage = new java.util.concurrent.ConcurrentHashMap<>();

    /** Enregistre un dégât sur une victime. */
    public void record(UUID victim, String source, String type, double amount) {
        List<DamageEntry> entries = recentDamage.computeIfAbsent(victim, k -> new ArrayList<>());
        entries.add(new DamageEntry(source, type, amount, System.currentTimeMillis()));
        // Garder seulement les 5 dernières secondes
        long cutoff = System.currentTimeMillis() - RECAP_WINDOW_MS;
        entries.removeIf(e -> e.timestamp() < cutoff);
    }

    /** Affiche le death recap au joueur mort. */
    public void showRecap(Player victim) {
        List<DamageEntry> entries = recentDamage.remove(victim.getUniqueId());
        if (entries == null || entries.isEmpty()) return;

        // Regrouper par source
        Map<String, Double> bySource = new LinkedHashMap<>();
        Map<String, String> sourceType = new LinkedHashMap<>();
        for (DamageEntry e : entries) {
            bySource.merge(e.source(), e.amount(), Double::sum);
            sourceType.put(e.source(), e.type());
        }

        // Trier par montant décroissant
        var sorted = bySource.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .toList();

        double total = sorted.stream().mapToDouble(Map.Entry::getValue).sum();

        victim.sendMessage(Component.text("─── ☠ Récapitulatif de mort ───", NamedTextColor.RED));
        victim.sendMessage(Component.text(
            String.format("Total : %.0f dégâts en 5s", total), NamedTextColor.GRAY));

        for (var entry : sorted) {
            String src = entry.getKey();
            double dmg = entry.getValue();
            String type = sourceType.getOrDefault(src, "");
            String typeIcon = switch (type) {
                case "magic"    -> "✨";
                case "physical" -> "⚔";
                case "true"     -> "💀";
                case "turret"   -> "🗼";
                default         -> "•";
            };
            double pct = total > 0 ? dmg / total * 100 : 0;
            String bar = "█".repeat(Math.max(1, (int)(pct / 10)));
            victim.sendMessage(Component.text(
                String.format("  %s %-16s %5.0f  (%4.0f%%)  %s",
                    typeIcon, src, dmg, pct, bar),
                NamedTextColor.WHITE));
        }
        victim.sendMessage(Component.text("─────────────────────────────", NamedTextColor.RED));
    }

    public void cleanup(UUID uuid) { recentDamage.remove(uuid); }
}
