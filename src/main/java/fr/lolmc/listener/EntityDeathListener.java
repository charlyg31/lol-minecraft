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

    /** XP d'un sbire selon son type (la même pour le last-hit et la proximité). */
    private static double minionXP(String typeTag) {
        return switch (typeTag != null ? typeTag : "melee") {
            case "caster" -> RewardManager.XP_MINION_CASTER;
            case "cannon" -> RewardManager.XP_MINION_CANNON;
            case "super"  -> RewardManager.XP_MINION_SUPER;
            default       -> RewardManager.XP_MINION;
        };
    }

    /** Champions ennemis du sbire, vivants, à moins de 14 blocs : ils se partagent son XP. */
    private java.util.List<Player> minionXpEarners(LivingEntity le, fr.lolmc.team.TeamManager.Team minionTeam) {
        var list = new java.util.ArrayList<Player>();
        for (Player p : le.getWorld().getPlayers()) {
            if (!championManager.hasChampion(p)) continue;
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue; // mort
            fr.lolmc.team.TeamManager.Team pTeam = LolPlugin.getInstance().getTeamManager().getTeam(p);
            if (pTeam == null || pTeam == minionTeam) continue; // allié du sbire
            if (p.getLocation().distance(le.getLocation()) > 14.0) continue;
            list.add(p);
        }
        return list;
    }

    /** Nombre de champions qui se partagent l'XP (le tueur compte toujours). */
    private static int xpShareCount(java.util.List<Player> earners, Player killer) {
        int n = earners.size();
        if (killer != null && !earners.contains(killer)) n++;
        return Math.max(1, n);
    }

    /** XP de proximité : quand un sbire meurt, les champions ennemis dans 14 blocs se partagent son XP. */
    @EventHandler
    public void onMinionDeath(EntityDeathEvent e) {
        org.bukkit.entity.LivingEntity le = e.getEntity();
        if (!fr.lolmc.game.MinionManager.isMinion(le)) return;
        fr.lolmc.team.TeamManager.Team minionTeam = fr.lolmc.game.MinionManager.getMinionTeam(le);
        if (minionTeam == null) return;

        // XP partagée entre les champions ennemis proches (rayon 14 blocs comme LoL) ; le
        // killer reçoit sa part via onMinionKill. Avant : XP entière pour chacun (et pour les morts).
        Player killer = le.getKiller();
        if (killer != null && !championManager.hasChampion(killer)) killer = null;
        var earners = minionXpEarners(le, minionTeam);
        double xp = RewardManager.sharedXP(fr.lolmc.util.MobCombat.getXP(le,
                minionXP(fr.lolmc.game.MinionManager.getMinionTypeTag(le))), xpShareCount(earners, killer));
        for (Player p : earners) {
            if (p.equals(killer)) continue; // le killer a déjà son XP
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
                int gold = fr.lolmc.util.MobCombat.getGold(dead,
                           "caster".equals(minionType) ? RewardManager.GOLD_MINION_CASTER
                         : "melee".equals(minionType) ? RewardManager.GOLD_MINION_MELEE
                         : RewardManager.GOLD_MINION_CANNON);
                var minionTeam = MinionManager.getMinionTeam(dead);
                double xp = RewardManager.sharedXP(fr.lolmc.util.MobCombat.getXP(dead, minionXP(minionType)),
                        minionTeam != null ? xpShareCount(minionXpEarners(dead, minionTeam), killer) : 1);
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
        var validAssists = new java.util.ArrayList<Player>();
        for (java.util.UUID aid : assistants) {
            var assistant = plugin.getServer().getPlayer(aid);
            if (assistant != null && assistant.isOnline()
                    && championManager.hasChampion(assistant)
                    && tm.getTeam(assistant) != tm.getTeam(victim)) validAssists.add(assistant);
        }
        // Exécution (aucun champion ennemi récent) : ni prime ni assistance (LoL)
        if (killer == null) validAssists.clear();
        // Avant le kill : la prime de la victime n'a pas encore baissé
        int assistGold = rewards.getAssistGoldEach(victim.getUniqueId(), validAssists.size());
        // XP du kill : tueur, assistants et alliés vivants à moins de 15 blocs (1600 unités, LoL)
        var nearbyAllies = new java.util.ArrayList<Player>();
        if (killer != null) {
            var killerTeam = tm.getTeam(killer);
            for (Player p : victim.getWorld().getPlayers()) {
                if (p.equals(killer) || validAssists.contains(p) || !championManager.hasChampion(p)) continue;
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || tm.getTeam(p) != killerTeam) continue;
                if (p.getLocation().distance(victim.getLocation()) <= 15.0) nearbyAllies.add(p);
            }
        }
        int participants = 1 + validAssists.size() + nearbyAllies.size();
        for (Player ally : nearbyAllies)
            rewards.grantKillXP(ally, rewards.getKillXP(victim.getUniqueId(), ally, participants));
        for (Player assistant : validAssists) {
            plugin.getGoldManager().addGold(assistant.getUniqueId(), assistGold);
            rewards.accrueTakedownBounty(assistant.getUniqueId(), assistGold);
            double assistXp = rewards.getKillXP(victim.getUniqueId(), assistant, participants);
            rewards.grantKillXP(assistant, assistXp);
            assistant.sendActionBar(net.kyori.adventure.text.Component.text(
                String.format("🤝 ASSIST! +%d or +%.0f XP", assistGold, assistXp),
                net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            plugin.getMatchScoreboard().addAssist(assistant);
            plugin.getRuneManager().onTakedown(assistant, victim);
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

        // Exécution : la prime de la victime ne bouge pas (LoL)
        if (killer == null) return;

        // ── Kill de champion ──
        rewards.onChampionKill(killer, victim, validAssists.size(), assistGold, participants);
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
        plugin.getRuneManager().onTakedown(killer, victim);
    }
}
