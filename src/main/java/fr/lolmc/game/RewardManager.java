package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.shop.GoldManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Centralise l'attribution d'or et d'XP (last-hits, kills, objectifs).
 * Gère aussi les montées de niveau et notifie le joueur.
 */
public class RewardManager {

    private final ChampionManager championManager;
    private final GoldManager goldManager;

    // Récompenses (proches de LoL)
    public static final int GOLD_MINION_MELEE = 21;
    public static final int GOLD_MINION_CASTER = 14;
    public static final int GOLD_CHAMPION_KILL = 300;
    public static final int GOLD_ASSIST = 150;

    public static final double XP_MINION = 60;
    public static final double XP_CHAMPION_KILL = 200;
    public static final double XP_ASSIST = 100;

    public RewardManager(ChampionManager championManager, GoldManager goldManager) {
        this.championManager = championManager;
        this.goldManager = goldManager;
    }

    // ── Last-hit sur sbire ────────────────────────────────────────

    public void onMinionKill(Player killer, int goldAmount, double xpAmount) {
        if (!championManager.hasChampion(killer)) return;
        goldManager.addGold(killer.getUniqueId(), goldAmount);
        grantXP(killer, xpAmount);
        killer.sendActionBar(Component.text(
                String.format("+%d or  +%.0f XP", goldAmount, xpAmount), NamedTextColor.GOLD));
    }

    // ── Kill de champion ──────────────────────────────────────────

    public void onChampionKill(Player killer, Player victim) {
        if (!championManager.hasChampion(killer)) return;
        goldManager.addGold(killer.getUniqueId(), GOLD_CHAMPION_KILL);
        grantXP(killer, XP_CHAMPION_KILL);
        killer.sendActionBar(Component.text(
                String.format("⚔ KILL! +%d or +%.0f XP", GOLD_CHAMPION_KILL, XP_CHAMPION_KILL),
                NamedTextColor.GOLD));

        // Déclencher les passifs on-kill (Hubris, Axiom Arc...)
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onKill(killer, victim);
    }

    // ── Monstre de jungle ─────────────────────────────────────────

    public void onJungleMonsterKill(Player killer, int goldAmount, double xpAmount) {
        if (!championManager.hasChampion(killer)) return;
        goldManager.addGold(killer.getUniqueId(), goldAmount);
        grantXP(killer, xpAmount);
        killer.sendActionBar(Component.text(
                String.format("🌿 +%d or  +%.0f XP", goldAmount, xpAmount), NamedTextColor.GREEN));
    }

    // ── XP & montée de niveau ─────────────────────────────────────

    private void grantXP(Player player, double xp) {
        BaseChampion champ = championManager.getChampion(player);
        int levelsGained = champ.getLevelSystem().addXP(xp);
        if (levelsGained > 0) {
            int newLevel = champ.getLevelSystem().getLevel();
            player.sendTitle("", "§a⬆ Niveau " + newLevel, 5, 30, 10);
            player.playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            // Faire clignoter les sorts désormais améliorables
            var listener = LolPlugin.getInstance().getAbilityListener();
            if (listener != null) listener.updateAbilityGlow(player, champ);
            // Mettre à jour le HUD (niveau affiché)
            var hud = LolPlugin.getInstance().getHUDManager();
            if (hud != null) hud.updateHUD(player, champ);
        }
    }
}
