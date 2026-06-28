package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;

/**
 * Feats of Strength (Saison 2025) — les 3 objectifs early game.
 *
 * Chaque feat est attribué à la première équipe qui l'accomplit :
 *   FIRST_BLOOD    — premier kill de champion
 *   FIRST_TOWER    — première tourelle détruite
 *   FIRST_EPIC     — premier objectif épique (Dragon ou Héraut)
 *
 * Quand une équipe complète les 3 feats, elle obtient un avantage global :
 *   • +150 or à chaque membre
 *   • +300 PV max temporaires (2 minutes) → Atakhan buff (simplifié)
 *   • Annonce globale
 *
 * Si les deux équipes ont chacune ≥1 feat, aucune ne peut déclencher
 * le bonus complet — elles s'annulent (comportement LoL 2025).
 */
public class FeatManager {

    public enum Feat { FIRST_BLOOD, FIRST_TOWER, FIRST_EPIC }

    private final Map<Feat, Team> claimed = new EnumMap<>(Feat.class);
    private boolean bonusGranted = false;

    // ── Déclenche un feat ─────────────────────────────────────────

    /**
     * Appeler quand un feat est accompli.
     * Retourne true si c'est la première fois et le feat est attribué.
     */
    public boolean claim(Feat feat, Team team, Player actor) {
        if (claimed.containsKey(feat)) return false; // déjà réclamé
        claimed.put(feat, team);

        String featName = switch (feat) {
            case FIRST_BLOOD -> "Premier Sang";
            case FIRST_TOWER -> "Première Tourelle";
            case FIRST_EPIC  -> "Premier Objectif Épique";
        };

        // Annonce à tous
        Bukkit.broadcast(Component.text(
            String.format("⚡ FEAT OF STRENGTH — %s par l'équipe %s!",
                featName, team == Team.BLUE ? "Bleue" : "Rouge"),
            team.chatColor));

        // Son pour l'équipe qui gagne le feat
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!LolPlugin.getInstance().getTeamManager().hasTeam(p)) continue;
            Sound snd = LolPlugin.getInstance().getTeamManager().getTeam(p) == team
                    ? Sound.ENTITY_PLAYER_LEVELUP
                    : Sound.ENTITY_WITHER_AMBIENT;
            p.playSound(p.getLocation(), snd, 1f, 1.2f);
        }

        checkAllFeats(team);
        return true;
    }

    /** Vérifie si une équipe a remporté les 3 feats → bonus Atakhan. */
    private void checkAllFeats(Team team) {
        if (bonusGranted) return;
        long teamFeats = claimed.values().stream().filter(t -> t == team).count();
        if (teamFeats < 3) return;

        bonusGranted = true;
        Bukkit.broadcast(Component.text(
            "🔥 " + (team == Team.BLUE ? "BLEUE" : "ROUGE")
            + " remporte les 3 FEATS OF STRENGTH — Avantage global!",
            NamedTextColor.GOLD));

        var cm = LolPlugin.getInstance().getChampionManager();
        var gm = LolPlugin.getInstance().getGoldManager();
        var tm = LolPlugin.getInstance().getTeamManager();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!tm.hasTeam(p) || tm.getTeam(p) != team) continue;
            // +150 or
            if (gm != null) gm.addGold(p.getUniqueId(), 150);
            // +300 PV max temporaires (2 min = 2400 ticks)
            if (cm.hasChampion(p)) {
                var stats = cm.getChampion(p).getStats();
                stats.addBonusHP(300);
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override public void run() { stats.addBonusHP(-300); }
                }.runTaskLater(LolPlugin.getInstance(), 2400L);
            }
            p.sendMessage(Component.text(
                "⚡ FEATS OF STRENGTH: +150 or, +300 PV max (2min)!",
                NamedTextColor.GOLD));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    // ── Accesseurs ────────────────────────────────────────────────

    public boolean isClaimed(Feat feat) { return claimed.containsKey(feat); }
    public Team getOwner(Feat feat)     { return claimed.get(feat); }
    public boolean isBonusGranted()     { return bonusGranted; }

    /** Résumé des feats pour affichage en fin de partie. */
    public String getSummary() {
        if (claimed.isEmpty()) return "Aucun feat accompli.";
        var sb = new StringBuilder();
        for (var e : claimed.entrySet()) {
            String name = switch (e.getKey()) {
                case FIRST_BLOOD -> "Premier Sang";
                case FIRST_TOWER -> "1ère Tourelle";
                case FIRST_EPIC  -> "1er Objectif";
            };
            sb.append(name).append(": ").append(e.getValue() == Team.BLUE ? "Bleue" : "Rouge").append("  ");
        }
        return sb.toString().trim();
    }

    public void reset() { claimed.clear(); bonusGranted = false; }
}
