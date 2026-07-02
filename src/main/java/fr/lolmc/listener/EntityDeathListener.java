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

    /** XP de proximité : quand un sbire meurt, tous les champions alliés dans 14 blocs reçoivent de l'XP. */
    @EventHandler
    public void onMinionDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.LivingEntity le)) return;
        if (!fr.lolmc.game.MinionManager.isMinion(le)) return;
        fr.lolmc.team.TeamManager.Team minionTeam = fr.lolmc.game.MinionManager.getMinionTeam(le);
        if (minionTeam == null) return;

        // XP pour les champions ennemis proches (rayon 14 blocs comme LoL)
        // On exclut le killer (il reçoit son XP via onMinionKill)
        Player killer = e.getEntity().getKiller();
        for (Player p : le.getWorld().getPlayers()) {
            if (p.equals(killer)) continue; // le killer a déjà son XP
            if (!championManager.hasChampion(p)) continue;
            fr.lolmc.team.TeamManager.Team pTeam = LolPlugin.getInstance().getTeamManager().getTeam(p);
            if (pTeam == null || pTeam == minionTeam) continue; // allié du sbire
            if (p.getLocation().distance(le.getLocation()) > 14.0) continue;
            // XP de proximité : 80% de l'XP normale (divisé par les joueurs proches)
            // Simplifié : XP fixe de proximité (LoL ~50-60 XP selon le type)
            String typeTag = fr.lolmc.game.MinionManager.getMinionTypeTag(le);
            double xp = switch (typeTag != null ? typeTag : "melee") {
                case "cannon" -> 92.0;
                case "super"  -> 97.0;
                case "caster" -> 55.0;
                default       -> 60.0; // melee
            };
            LolPlugin.getInstance().getRewardManager().grantProximityXP(p, xp);
        }
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
            LolPlugin.getInstance().getMinionManager().onMinionDeath(dead.getUniqueId());
            // XP partagée : alliés dans un rayon de 14 blocs du sbire
            double sharedXP = RewardManager.XP_MINION * 0.50; // 50% XP si pas le last-hit
            for (org.bukkit.entity.Player nearby : dead.getWorld().getNearbyPlayers(dead.getLocation(), 14)) {
                if (!championManager.hasChampion(nearby)) continue;
                if (nearby.equals(killer)) continue; // le killer reçoit déjà sa XP complète
                // N'attribuer qu'aux alliés du killer (même équipe)
                var tm = LolPlugin.getInstance().getTeamManager();
                if (killer != null && tm.getTeam(killer) != null
                        && tm.getTeam(killer) == tm.getTeam(nearby)) {
                    rewardManager.onMinionKill(nearby, 0, sharedXP); // XP seule, pas d'or
                }
            }
            if (killer != null && championManager.hasChampion(killer)) {
                // CS (Creep Score)
                LolPlugin.getInstance().getMatchScoreboard().addCS(killer);
                // Or/XP selon le type de sbire
                String minionType = fr.lolmc.game.MinionManager.getMinionTypeTag(dead);
                int gold = "caster".equals(minionType) ? RewardManager.GOLD_MINION_CASTER
                         : "cannon".equals(minionType) ? RewardManager.GOLD_MINION_CANNON
                         : RewardManager.GOLD_MINION_MELEE;
                rewardManager.onMinionKill(killer, gold, RewardManager.XP_MINION);
            }
            return;
        }
    }

    // ── Mort de champion (joueur) → or/XP au tueur ──
    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (championManager.hasChampion(victim)) {
            // ── Assists : tous ceux qui ont infligé des dégâts dans les 10s ──
            java.util.UUID killerUUID = (killer != null) ? killer.getUniqueId() : null;
            var assistants = rewardManager.getAssistants(victim.getUniqueId(), killerUUID);
            for (java.util.UUID aid : assistants) {
                var assistant = LolPlugin.getInstance().getServer().getPlayer(aid);
                if (assistant != null && assistant.isOnline()
                        && championManager.hasChampion(assistant)) {
                    LolPlugin.getInstance().getGoldManager().addGold(aid, fr.lolmc.game.RewardManager.GOLD_ASSIST);
                    assistant.sendActionBar(net.kyori.adventure.text.Component.text(
                        String.format("🤝 ASSIST! +%d or", fr.lolmc.game.RewardManager.GOLD_ASSIST),
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    LolPlugin.getInstance().getMatchScoreboard().addAssist(assistant);
                    LolPlugin.getInstance().getRuneManager().onTakedown(assistant);
                }
            }
        }
        if (killer != null && !killer.equals(victim)
                && championManager.hasChampion(killer)
                && championManager.hasChampion(victim)) {
            rewardManager.onChampionKill(killer, victim);
            // Annonce (Premier Sang, multi-kills)
            LolPlugin.getInstance().getAnnouncementManager().announceKill(killer, victim);
            LolPlugin.getInstance().getAnnouncementManager().onPlayerDeath(victim);
            // MasterYi Highlander reset sur kill
            var killerChamp = championManager.getChampion(killer);
            if ("masteryi".equals(killerChamp.getId()))
                fr.lolmc.champion.impl.jungle.MasterYi.onKillDuringHighlander(killer);
            // Veigar passif Malfaisance
            if ("veigar".equals(killerChamp.getId()))
                fr.lolmc.champion.impl.mid.Veigar.onTakedown(killer.getUniqueId());
            // Jinx passif Get Excited
            if ("jinx".equals(killerChamp.getId()))
                fr.lolmc.champion.impl.adc.Jinx.getExcited(killer);
            // Annie meurt → Tibbers s'enrage
            var victimChamp = championManager.getChampion(victim);
            if ("annie".equals(victimChamp.getId()))
                fr.lolmc.champion.impl.mid.Annie.onAnnieDeath(victim);
            // Scoreboard de partie
            LolPlugin.getInstance().getMatchScoreboard().addKill(killer);
            LolPlugin.getInstance().getMatchScoreboard().addDeath(victim);
            // Feat of Strength : Premier Sang
            var killerTeam = LolPlugin.getInstance().getTeamManager().getTeam(killer);
            if (killerTeam != null)
                LolPlugin.getInstance().getFeatManager().claim(
                    fr.lolmc.game.FeatManager.Feat.FIRST_BLOOD, killerTeam, killer);
            // Timer de respawn
            LolPlugin.getInstance().getGameManager().onPlayerDeath(victim);
            // Runes : Triomphe, Absorption (soin/or sur takedown)
            LolPlugin.getInstance().getRuneManager().onTakedown(killer);
        }
    }
}
