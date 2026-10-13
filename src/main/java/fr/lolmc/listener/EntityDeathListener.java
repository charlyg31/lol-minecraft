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

    public EntityDeathListener(ChampionManager championManager) {
        this.championManager = championManager;
    }

    /** XP de proximité : quand un sbire meurt, tous les champions alliés dans 14 blocs reçoivent de l'XP. */
    @EventHandler
    public void onMinionDeath(EntityDeathEvent e) {
        org.bukkit.entity.LivingEntity le = e.getEntity();
        if (!fr.lolmc.game.MinionManager.isMinion(le)) return;
        fr.lolmc.team.TeamManager.Team minionTeam = fr.lolmc.game.MinionManager.getMinionTeam(le);
        if (minionTeam == null) return;

        // XP pour les champions ennemis proches (rayon 14 blocs comme LoL)
        // On exclut le killer (il reçoit son XP via onMinionKill)
        Player killer = le.getKiller();
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
            fr.lolmc.instance.InstanceHelper.rewardManager(p).grantProximityXP(p, xp);
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
            fr.lolmc.instance.InstanceHelper.jungleManager(dead).onMonsterDeath(dead.getUniqueId(), killer);
            return;
        }

        // ── Sbire ──
        if (MinionManager.isMinion(dead)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            fr.lolmc.instance.InstanceHelper.minionManager(dead).onMinionDeath(dead.getUniqueId());
            // XP de proximité des autres champions : gérée par onMinionDeath
            if (killer != null && championManager.hasChampion(killer)) {
                // Or/XP (et CS) selon le type de sbire
                String minionType = fr.lolmc.game.MinionManager.getMinionTypeTag(dead);
                int gold = "caster".equals(minionType) ? RewardManager.GOLD_MINION_CASTER
                         : "cannon".equals(minionType) ? RewardManager.GOLD_MINION_CANNON
                         : RewardManager.GOLD_MINION_MELEE;
                double xp = switch (minionType != null ? minionType : "melee") {
                    case "caster" -> RewardManager.XP_MINION_CASTER;
                    case "cannon" -> RewardManager.XP_MINION_CANNON;
                    case "super"  -> RewardManager.XP_MINION_SUPER;
                    default       -> RewardManager.XP_MINION;
                };
                fr.lolmc.instance.InstanceHelper.rewardManager(killer).onMinionKill(killer, gold, xp);
            }
        }
    }

    // ── Mort de champion (joueur) → or/XP au tueur ──
    /** Mort vanilla d'un champion (ex. /kill) : les morts LoL passent par HUDManager.triggerDeath. */
    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player victim = e.getEntity();
        if (!championManager.hasChampion(victim)) return;
        if (victim.getGameMode() == org.bukkit.GameMode.SPECTATOR) return; // déjà mort côté LoL
        handleChampionDeath(victim, victim.getKiller());
    }

    /**
     * Récompenses et suivi d'une mort de champion : assists, kill (or, bounty, XP, annonces,
     * passifs), scoreboard, Premier Sang, death recap. Le respawn est géré par l'appelant.
     */
    public void handleChampionDeath(Player victim, Player killer) {
        if (!championManager.hasChampion(victim)) return;
        var plugin = LolPlugin.getInstance();
        var rewards = fr.lolmc.instance.InstanceHelper.rewardManager(victim);
        if (killer != null && (killer.equals(victim) || !championManager.hasChampion(killer))) killer = null;

        // ── Assists : tous ceux qui ont infligé des dégâts dans les 10s ──
        java.util.UUID killerUUID = (killer != null) ? killer.getUniqueId() : null;
        var assistants = rewards.getAssistants(victim.getUniqueId(), killerUUID);
        var tm = plugin.getTeamManager();
        for (java.util.UUID aid : assistants) {
            var assistant = plugin.getServer().getPlayer(aid);
            if (assistant != null && assistant.isOnline()
                    && championManager.hasChampion(assistant)
                    && tm.getTeam(assistant) != tm.getTeam(victim)) {
                plugin.getGoldManager().addGold(aid, RewardManager.GOLD_ASSIST);
                rewards.grantProximityXP(assistant, RewardManager.XP_ASSIST);
                assistant.sendActionBar(net.kyori.adventure.text.Component.text(
                    String.format("🤝 ASSIST! +%d or +%.0f XP", RewardManager.GOLD_ASSIST, RewardManager.XP_ASSIST),
                    net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                plugin.getMatchScoreboard().addAssist(assistant);
                plugin.getRuneManager().onTakedown(assistant);
            }
        }

        // ── Suivi commun à toutes les morts ──
        plugin.getMatchScoreboard().addDeath(victim);
        fr.lolmc.instance.InstanceHelper.announcementManager(victim).onPlayerDeath(victim);
        var drm = plugin.getDeathRecapManager();
        if (drm != null) drm.showRecap(victim);
        // Consommables : annule les potions de soin en cours à la mort
        var cm = plugin.getConsumableManager();
        if (cm != null) cm.onPlayerDeath(victim);
        // Annie meurt → Tibbers s'enrage
        if ("annie".equals(championManager.getChampion(victim).getId()))
            fr.lolmc.champion.impl.mid.Annie.onAnnieDeath(victim);

        if (killer == null) return;

        // ── Kill de champion ──
        rewards.onChampionKill(killer, victim);
        // Annonce (Premier Sang, multi-kills)
        fr.lolmc.instance.InstanceHelper.announcementManager(killer).announceKill(killer);
        var killerChamp = championManager.getChampion(killer);
        // MasterYi Highlander reset sur kill
        if ("masteryi".equals(killerChamp.getId()))
            fr.lolmc.champion.impl.jungle.MasterYi.onKillDuringHighlander(killer);
        // Veigar passif Malfaisance
        if ("veigar".equals(killerChamp.getId()))
            fr.lolmc.champion.impl.mid.Veigar.onTakedown(killer.getUniqueId());
        // Jinx passif Get Excited
        if ("jinx".equals(killerChamp.getId()))
            fr.lolmc.champion.impl.adc.Jinx.getExcited(killer);
        // Scoreboard de partie (stats indexées par joueur : les parties simultanées ne se mélangent pas)
        plugin.getMatchScoreboard().addKill(killer);
        // Feat of Strength : Premier Sang
        var killerTeam = tm.getTeam(killer);
        if (killerTeam != null)
            fr.lolmc.instance.InstanceHelper.featManager(killer).claim(
                fr.lolmc.game.FeatManager.Feat.FIRST_BLOOD, killerTeam, killer);
        // Runes : Triomphe, Absorption (soin/or sur takedown)
        plugin.getRuneManager().onTakedown(killer);
    }
}
