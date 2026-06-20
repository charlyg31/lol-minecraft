package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.JungleManager;
import fr.lolmc.game.MinionManager;
import fr.lolmc.game.RewardManager;
import fr.lolmc.manager.ChampionManager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Détecte la mort des entités de jeu et attribue or + XP au tueur (last-hit).
 */
public class EntityDeathListener implements Listener {

    private final ChampionManager championManager;
    private final RewardManager rewardManager;

    public EntityDeathListener(ChampionManager championManager, RewardManager rewardManager) {
        this.championManager = championManager;
        this.rewardManager = rewardManager;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();

        // ── Monstre de jungle ──
        if (JungleManager.isJungleMonster(dead)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            LolPlugin.getInstance().getJungleManager().onMonsterDeath(dead.getUniqueId(), killer);
            return;
        }

        // ── Sbire ──
        if (MinionManager.isMinion(dead)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            if (killer != null && championManager.hasChampion(killer)) {
                // Or/XP selon le type de sbire (mêlée par défaut)
                rewardManager.onMinionKill(killer,
                        RewardManager.GOLD_MINION_MELEE, RewardManager.XP_MINION);
            }
            return;
        }
    }

    // ── Mort de champion (joueur) → or/XP au tueur ──
    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)
                && championManager.hasChampion(killer)
                && championManager.hasChampion(victim)) {
            rewardManager.onChampionKill(killer, victim);
            // Annonce (Premier Sang, multi-kills)
            LolPlugin.getInstance().getAnnouncementManager().announceKill(killer, victim);
            LolPlugin.getInstance().getAnnouncementManager().onPlayerDeath(victim);
            // Scoreboard de partie
            LolPlugin.getInstance().getMatchScoreboard().addKill(killer);
            LolPlugin.getInstance().getMatchScoreboard().addDeath(victim);
            // Timer de respawn
            LolPlugin.getInstance().getGameManager().onPlayerDeath(victim);
        }
    }
}
