package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.shop.GoldManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Centralise l'attribution d'or et d'XP (last-hits, kills, objectifs).
 * Gère aussi les montées de niveau et notifie le joueur.
 */
public class RewardManager {
    private fr.lolmc.instance.GameInstance gameInstance = null;
    public void setGameInstance(fr.lolmc.instance.GameInstance inst) { this.gameInstance = inst; }
    public fr.lolmc.instance.GameInstance getGameInstance() { return gameInstance; }
    private AnnouncementManager siblingAnnouncementManager() {
        return gameInstance != null ? gameInstance.getAnnouncementManager() : LolPlugin.getInstance().getAnnouncementManager();
    }
    private GameManager siblingGameManager() {
        return gameInstance != null ? gameInstance.getGameManager() : LolPlugin.getInstance().getGameManager();
    }

    private final ChampionManager championManager;
    private final GoldManager goldManager;

    // Récompenses (proches de LoL)
    // Valeurs par défaut (les sbires portent leur or/XP réels, fixés à leur apparition)
    public static final int GOLD_MINION_MELEE = 20;   // sbire mêlée (wiki LoL)
    public static final int GOLD_MINION_CASTER = 14;  // sbire caster (wiki LoL)
    public static final int GOLD_MINION_CANNON = 50;  // canon/super : 50 puis +1 par amélioration
    // Tourelles (Faille de l'invocateur, wiki LoL) : or GLOBAL à toute l'équipe (morts compris),
    // pas d'or local ni d'XP ; première tourelle de la partie : +300 partagés entre les proches
    public static final int GOLD_TURRET_OUTER_GLOBAL     = 50;
    public static final int GOLD_TURRET_INNER_GLOBAL     = 25;
    public static final int GOLD_TURRET_INHIBITOR_GLOBAL = 25;
    public static final int GOLD_TURRET_NEXUS_GLOBAL     = 50;
    public static final int GOLD_FIRST_TURRET            = 300;
    // Plaque (toutes les tourelles sauf nexus, permanentes depuis 26.1) : 120 or partagés entre
    // les champions proches (extérieures : -10 or par minute dès 11:00, max -40)
    public static final int GOLD_TURRET_PLATE     = 120;
    // Inhibiteur : 50 or au seul last-hit
    public static final int GOLD_INHIBITOR_KILLER = 50;
    // Rayon « local » : 1200 unités LoL (100 unités = 1 bloc, LolUnits)
    public static final double LOCAL_RADIUS = 12.0;

    public static final double XP_MINION = 62;         // mêlée (wiki LoL)
    public static final double XP_MINION_CASTER = 31;  // caster
    public static final double XP_MINION_CANNON = 75;  // canon
    public static final double XP_MINION_SUPER  = 75;  // super-sbire
    /** XP d'un kill solo selon le niveau de la victime (1 à 18), valeurs LoL (patch 10.8). */
    private static final double[] KILL_XP = {42, 114, 144, 174, 204, 234, 308, 392, 486, 590,
                                             640, 690, 740, 790, 840, 890, 940, 990};

    // ── Primes (bounty) : système LoL actuel (sans paliers de séries depuis 14.21) ──────
    // Écart de prime par rapport à la prime de base : monte avec l'or gagné (kills, farm),
    // baisse à chaque mort (et peut passer sous la base, jusqu'à 50 or minimum).
    private final java.util.Map<java.util.UUID, Double> bountyOffset = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SHUTDOWN_BUFFER = 100; // au-delà de base + 100 : or de « Shut Down »
    private static final int MIN_BOUNTY = 50;
    private boolean firstTurretDone = false;

    // ── Système d'assists ─────────────────────────────────────────────────
    // Map<victim_uuid, Map<attacker_uuid, lastDamageTimestamp>>
    // Fenêtre : dégâts infligés dans les 15 s avant la mort = assistance (AssistDurationOverride, données du jeu)
    private final java.util.Map<java.util.UUID,
        java.util.Map<java.util.UUID, Long>> damageContrib = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ASSIST_WINDOW_MS = 15_000L;
    /** Crédit d'un kill à un champion quand la victime meurt sans tueur (exécution) : 10 s. */
    private static final long KILL_CREDIT_MS = 10_000L;

    /** Enregistre un dégât infligé par [attacker] sur [victim] (appelé depuis DamageUtil). */
    public void recordDamage(java.util.UUID attacker, java.util.UUID victim) {
        damageContrib
            .computeIfAbsent(victim, _ -> new java.util.concurrent.ConcurrentHashMap<>())
            .put(attacker, System.currentTimeMillis());
    }

    /** Dernier joueur ayant blessé {@code victim} dans les 10 s (null si aucun) — sans vider le suivi. */
    public java.util.UUID getLastAttacker(java.util.UUID victim) {
        var contribs = damageContrib.get(victim);
        if (contribs == null) return null;
        long threshold = System.currentTimeMillis() - KILL_CREDIT_MS;
        java.util.UUID best = null;
        long bestTime = Long.MIN_VALUE;
        for (var entry : contribs.entrySet()) {
            if (entry.getValue() >= threshold && entry.getValue() > bestTime) {
                best = entry.getKey();
                bestTime = entry.getValue();
            }
        }
        return best;
    }

    /** Retourne les joueurs éligibles à l'assist (ont infligé des dégâts dans les 15 s). */
    public java.util.Set<java.util.UUID> getAssistants(java.util.UUID victim, java.util.UUID killer) {
        var contribs = damageContrib.remove(victim);
        if (contribs == null) return java.util.Collections.emptySet();
        long threshold = System.currentTimeMillis() - ASSIST_WINDOW_MS;
        var result = new java.util.HashSet<java.util.UUID>();
        for (var entry : contribs.entrySet()) {
            if (!entry.getKey().equals(killer) && entry.getValue() >= threshold)
                result.add(entry.getKey());
        }
        return result;
    }

    private long gameStartMs = 0;
    public void setGameStart() { gameStartMs = System.currentTimeMillis(); }

    public RewardManager(ChampionManager championManager, GoldManager goldManager) {
        this.championManager = championManager;
        this.goldManager = goldManager;
    }

    // ── Last-hit sur sbire ────────────────────────────────────────

    public void onMinionKill(Player killer, int goldAmount, double xpAmount) {
        if (!championManager.hasChampion(killer)) return;
        // (l'or du canon/super augmente avec les améliorations, fixé à l'apparition du sbire ;
        // mêlée et caster ne changent pas — avant : +1 or toutes les 90 s pour tous)
        int total = goldAmount;
        goldManager.addGold(killer.getUniqueId(), total);
        accrueFarmBounty(killer.getUniqueId(), total);
        grantXP(killer, xpAmount);
        // CS tracker
        var msb = LolPlugin.getInstance().getMatchScoreboard();
        if (msb != null) msb.addCS(killer);
        killer.sendActionBar(net.kyori.adventure.text.Component.text(
                String.format("+%d or  +%.0f XP", total, xpAmount), NamedTextColor.GOLD));
    }

    // ── Bounty d'objectifs (comeback LoL) ────────────────────────

    // ── Kill de champion ──────────────────────────────────────────

    /** Prime de base d'un kill : 300 jusqu'au niveau 6, puis +10 par niveau (420 au niveau 18). */
    public static int baseKillGold(int level) {
        return 300 + Math.max(0, level - 6) * 10;
    }

    private int levelOf(java.util.UUID id) {
        Player p = org.bukkit.Bukkit.getPlayer(id);
        return p != null && championManager.hasChampion(p)
                ? championManager.getChampion(p).getLevelSystem().getLevel() : 1;
    }

    /** Plafond d'un seul kill au-dessus de la base : +700 (prime totale 1000 au niveau 1). */
    private static int maxAboveBase(int level, int base) {
        return level <= 1 ? 1000 - base : 700;
    }

    /** Or que rapporte le kill de ce champion (prime de base ± écart, plafonnée, 50 minimum). */
    public int getBounty(java.util.UUID player) {
        int level = levelOf(player);
        int base = baseKillGold(level);
        double off = Math.min(bountyOffset.getOrDefault(player, 0.0), maxAboveBase(level, base));
        return (int) Math.round(Math.max(MIN_BOUNTY, base + off));
    }

    /** Prime gagnée par un kill ou une assistance : 1 or de prime pour 3 or gagnés. */
    public void accrueTakedownBounty(java.util.UUID player, int goldEarned) {
        bountyOffset.merge(player, goldEarned / 3.0, Double::sum);
    }

    /** Prime gagnée en farmant : 1 pour 20 or (1 pour 7 tant que la prime est sous la base). */
    private void accrueFarmBounty(java.util.UUID player, int goldEarned) {
        double off = bountyOffset.getOrDefault(player, 0.0);
        bountyOffset.put(player, off + goldEarned / (off < 0 ? 7.0 : 20.0));
    }

    /**
     * Mort de la victime : l'or de Shut Down versé (au-delà de base + 100) est retiré, puis la
     * prime baisse de 1 pour 3,5 or distribués au tueur et aux assistants (minimum 50 or).
     * Ce qui dépasse le plafond d'un kill reste pour le kill suivant.
     */
    private void depreciateBounty(java.util.UUID victim, int paidAboveBase, int distributed) {
        int base = baseKillGold(levelOf(victim));
        double off = bountyOffset.getOrDefault(victim, 0.0);
        off -= Math.max(0, paidAboveBase - SHUTDOWN_BUFFER);
        off -= distributed / 3.5;
        bountyOffset.put(victim, Math.max(off, MIN_BOUNTY - base));
    }

    /**
     * Or d'assistance par joueur : la moitié de la prime versée, plafonnée à la moitié de la
     * prime de base (150 à 210), partagée à parts égales entre les assistants (LoL).
     */
    public int getAssistGoldEach(java.util.UUID victim, int assistCount) {
        if (assistCount <= 0) return 0;
        double pool = Math.min(getBounty(victim) * 0.5, baseKillGold(levelOf(victim)) * 0.5);
        // Début de partie : 50 % jusqu'à 0:55, puis +0,416 % par seconde jusqu'à 100 % à 2:55 (wiki)
        long secs = gameStartMs == 0 ? Long.MAX_VALUE : (System.currentTimeMillis() - gameStartMs) / 1000L;
        pool *= Math.min(1.0, 0.5 + 0.00416 * Math.max(0, secs - 55));
        return (int) Math.round(pool / assistCount);
    }

    /**
     * XP d'un kill pour un participant (tueur ou assistant) : l'XP du niveau de la victime,
     * partagée à parts égales entre les participants, modifiée par l'écart de niveau
     * (±20 % par niveau d'écart au-delà du premier, au moins 40 %).
     */
    public double getKillXP(java.util.UUID victim, Player receiver, int participants) {
        int victimLevel = levelOf(victim);
        double xp = KILL_XP[Math.max(1, Math.min(18, victimLevel)) - 1];
        // Kill partagé : le total vaut 66,6 % (niv. 1-6), 82 % (7-8) ou 90 % (9-18) de l'XP solo (wiki)
        if (participants > 1) {
            xp *= victimLevel <= 6 ? 0.666 : victimLevel <= 8 ? 0.82 : 0.90;
            xp /= participants;
        }
        int diff = levelOf(receiver.getUniqueId()) - victimLevel;
        if (diff >= 2) xp *= Math.max(0.4, 1 - 0.2 * (diff - 1));
        else if (diff <= -2) xp *= 1 + 0.2 * (-diff - 1);
        return xp;
    }

    /** XP de kill/assistance (appelé pour les assistants depuis EntityDeathListener). */
    public void grantKillXP(Player receiver, double xp) {
        grantXP(receiver, xp);
    }

    /**
     * XP d'un sbire partagée entre {@code n} champions proches : 100 % seul, puis l'XP est
     * divisée avec un bonus de partage (65 % chacun à 2, 43 % à 3, 33 % à 4, 26 % à 5, comme LoL).
     */
    public static double sharedXP(double xp, int n) {
        return n <= 1 ? xp : xp * 1.3 / n;
    }

    /**
     * Kill d'un champion : prime de la victime (+100 au Premier Sang) et XP au tueur, puis la
     * prime de la victime baisse. {@code assistCount} et {@code assistGoldEach} : assistances déjà
     * versées (or distribué) ; {@code xpSharers} : champions qui se partagent l'XP.
     */
    /**
     * Part du Shut Down supprimée après 6:00 quand l'équipe du tueur ne gagne pas nettement (LoL :
     * 30 / 60 / 90 / 100 % près de l'équilibre). Riot ne publie pas les seuils : APPROXIMATION
     * sur l'avance en or gagné de l'équipe du tueur, d'après l'ordre de grandeur de l'ancien
     * système (3 % à 24 % d'écart) : < 2 % → 100 %, < 4 % → 90 %, < 6 % → 60 %, < 8 % → 30 %.
     */
    private double shutdownSuppression(fr.lolmc.team.TeamManager.Team killerTeam) {
        if (gameStartMs == 0 || killerTeam == null) return 0;
        if (System.currentTimeMillis() - gameStartMs < 6 * 60 * 1000L) return 0;
        var tm = LolPlugin.getInstance().getTeamManager();
        long mine = 0, theirs = 0;
        var participants = gameInstance != null ? gameInstance.getParticipants() : siblingGameManager().getParticipants();
        for (java.util.UUID id : participants) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p == null) continue;
            var t = tm.getTeam(p);
            if (t == killerTeam) mine += goldManager.getEarned(id);
            else if (t != null) theirs += goldManager.getEarned(id);
        }
        if (mine + theirs <= 0) return 1.0;
        double lead = (double) (mine - theirs) / Math.max(1, theirs); // avance relative
        if (lead < 0.02) return 1.0;
        if (lead < 0.04) return 0.9;
        if (lead < 0.06) return 0.6;
        if (lead < 0.08) return 0.3;
        return 0;
    }

    public void onChampionKill(Player killer, Player victim, int assistCount, int assistGoldEach, int xpSharers) {
        if (!championManager.hasChampion(killer)) return;
        java.util.UUID vid = victim.getUniqueId();
        int base = baseKillGold(levelOf(vid));
        int bounty = getBounty(vid);
        // Shut Down (au-delà de base + 100) réduit si l'équipe du tueur ne gagne pas nettement ;
        // la part supprimée reste sur la victime (elle n'est retirée que si elle est versée)
        int shutdown = Math.max(0, bounty - base - SHUTDOWN_BUFFER);
        int suppressed = (int) Math.round(shutdown
                * shutdownSuppression(LolPlugin.getInstance().getTeamManager().getTeam(killer)));
        bounty -= suppressed;
        int totalGold = bounty;
        var ann = siblingAnnouncementManager();
        if (ann != null && !ann.isFirstBloodDone()) totalGold += 100; // Premier Sang
        double xp = getKillXP(vid, killer, xpSharers);

        goldManager.addGold(killer.getUniqueId(), totalGold);
        grantXP(killer, xp);
        accrueTakedownBounty(killer.getUniqueId(), totalGold);
        depreciateBounty(vid, bounty - base, bounty + assistGoldEach * assistCount);

        String goldMsg = bounty > base
                ? String.format("⚔ KILL! +%d or (Shut Down +%d) +%.0f XP", totalGold, bounty - base, xp)
                : String.format("⚔ KILL! +%d or +%.0f XP", totalGold, xp);
        killer.sendActionBar(net.kyori.adventure.text.Component.text(goldMsg, NamedTextColor.GOLD));

        // Déclencher les passifs on-kill (Hubris, Axiom Arc...)
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onKill(killer);
    }

    // ── Monstre de jungle ─────────────────────────────────────────

    public void onJungleMonsterKill(Player killer, int goldAmount, double xpAmount) {
        if (!championManager.hasChampion(killer)) return;
        goldManager.addGold(killer.getUniqueId(), goldAmount);
        accrueFarmBounty(killer.getUniqueId(), goldAmount);
        grantXP(killer, xpAmount);
        killer.sendActionBar(Component.text(
                String.format("🌿 +%d or  +%.0f XP", goldAmount, xpAmount), NamedTextColor.GREEN));
    }

    /**
     * Récompenses d'un monstre épique : or au tueur, or et XP « globaux » à chaque membre de
     * l'équipe (morts compris), XP « locale » partagée entre les alliés vivants proches.
     */
    public void onEpicKill(Player killer, org.bukkit.Location loc, int killerGold, int globalGold,
                           double localXp, double globalXp) {
        if (!championManager.hasChampion(killer)) return;
        var team = LolPlugin.getInstance().getTeamManager().getTeam(killer);
        if (killerGold > 0) goldManager.addGold(killer.getUniqueId(), killerGold);
        if ((globalGold > 0 || globalXp > 0) && team != null) {
            var participants = gameInstance != null ? gameInstance.getParticipants() : siblingGameManager().getParticipants();
            for (java.util.UUID id : participants) {
                Player p = org.bukkit.Bukkit.getPlayer(id);
                if (p == null || !championManager.hasChampion(p)
                        || LolPlugin.getInstance().getTeamManager().getTeam(p) != team) continue;
                if (globalGold > 0) goldManager.addGold(id, globalGold);
                if (globalXp > 0) grantXP(p, globalXp);
            }
        }
        if (localXp > 0 && team != null && loc != null) {
            var near = localChampions(loc, team, killer);
            for (Player p : near) grantXP(p, localXp / near.size());
        }
        killer.sendActionBar(Component.text(String.format("🐉 +%d or", killerGold + globalGold), NamedTextColor.GOLD));
    }

    // ── XP & montée de niveau ─────────────────────────────────────

    /** XP de proximité (sbire mort sans last-hit) — public pour EntityDeathListener. */
    public void grantProximityXP(Player player, double xp) {
        grantXP(player, xp);
    }

    /**
     * Soigne un allié en tenant compte du Healing Power (items/runes support).
     * À appeler depuis les sorts/items qui soignent (Redemption, Moonstone, Heal, etc.)
     * @param healer  le joueur qui soigne (source du buff)
     * @param target  le joueur soigné
     * @param baseHeal montant brut de soin
     */
    public static double applyHealPower(Player healer, Player target, double baseHeal) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(healer) || !cm.hasChampion(target)) return baseHeal;
        var healerStats = cm.getChampion(healer).getStats();
        double healPower = 1.0 + healerStats.getHealShieldPower(); // ex: 1.15 si +15%
        double finalHeal = baseHeal * healPower;
        // (l'Hémorragie de la cible est appliquée par heal() via son multiplicateur de soins)
        cm.getChampion(target).getHPSystem().heal(finalHeal);
        return finalHeal;
    }

    private void grantXP(Player player, double xp) {
        BaseChampion champ = championManager.getChampion(player);
        // ── XP catch-up (LoL) : un joueur en retard sur le niveau moyen ──
        // ── de la partie gagne un bonus d'XP progressif (jusqu'à +40%) ──
        double avgLevel = 0; int counted = 0;
        for (var op : (gameInstance != null ? gameInstance.getOnlinePlayers() : fr.lolmc.util.WorldContext.getGamePlayers())) {
            if (!championManager.hasChampion(op)) continue;
            avgLevel += championManager.getChampion(op).getLevelSystem().getLevel();
            counted++;
        }
        if (counted > 0) {
            avgLevel /= counted;
            int myLevel = champ.getLevelSystem().getLevel();
            double behind = avgLevel - myLevel;
            if (behind >= 1.0) {
                // +20% par niveau de retard, plafonné à +40%
                double bonus = Math.min(0.40, behind * 0.20);
                xp *= (1.0 + bonus);
            }
        }
        int levelsGained = champ.getLevelSystem().addXP(xp);
        if (levelsGained > 0) {
            int newLevel = champ.getLevelSystem().getLevel();
            champ.onLevelChanged(newLevel);
            // Titre + son + particules level-up
            player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text("⬆ NIVEAU " + newLevel,
                    net.kyori.adventure.text.format.NamedTextColor.GOLD,
                    net.kyori.adventure.text.format.TextDecoration.BOLD),
                net.kyori.adventure.text.Component.text(
                    champ.getLevelSystem().getSkillPoints() + " point(s) — clique droit sur Q/W/E/R",
                    net.kyori.adventure.text.format.NamedTextColor.YELLOW),
                net.kyori.adventure.title.Title.Times.times(
                    java.time.Duration.ofMillis(200),
                    java.time.Duration.ofMillis(2500),
                    java.time.Duration.ofMillis(500))));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f);
            fr.lolmc.util.VisualEffectUtil.impactBurst(player.getWorld(),
                    player.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.3f, 0.6, 12, 8L);
            // Faire clignoter les sorts désormais améliorables
            var listener = LolPlugin.getInstance().getAbilityListener();
            if (listener != null) listener.updateAbilityGlow(player, champ);
            // Mettre à jour le HUD (niveau affiché)
            var hud = LolPlugin.getInstance().getHUDManager();
            if (hud != null) hud.updateHUD(player, champ);
        }
    }
    public void resetKillStreaks() { bountyOffset.clear(); firstTurretDone = false; gameStartMs = 0; }

    /** Champions vivants de {@code team} à moins de LOCAL_RADIUS de {@code loc} (+ {@code always}). */
    private java.util.List<Player> localChampions(org.bukkit.Location loc,
                                                  fr.lolmc.team.TeamManager.Team team, Player always) {
        var list = new java.util.ArrayList<Player>();
        var tm = LolPlugin.getInstance().getTeamManager();
        var participants = gameInstance != null ? gameInstance.getParticipants() : siblingGameManager().getParticipants();
        for (java.util.UUID id : participants) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p == null || !championManager.hasChampion(p) || tm.getTeam(p) != team) continue;
            boolean near = p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && p.getWorld().equals(loc.getWorld())
                    && p.getLocation().distance(loc) <= LOCAL_RADIUS;
            if (near || p.equals(always)) list.add(p);
        }
        return list;
    }

    /** Partage {@code gold} à parts égales (or « local » LoL). */
    private void splitGold(java.util.List<Player> players, int gold, String label) {
        if (players.isEmpty()) return;
        int each = (int) Math.round((double) gold / players.size());
        for (Player p : players) {
            goldManager.addGold(p.getUniqueId(), each);
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    label + " +" + each + " or", NamedTextColor.GOLD));
        }
    }

    /**
     * Objective bounty (comeback LoL) : si l'équipe de l'attaquant a
     * >3000 or de retard sur l'équipe adverse, les objectifs rapportent plus.
     * @return bonus d'or (0 si pas de retard)
     */
    public int getObjectiveBounty(fr.lolmc.team.TeamManager.Team attackingTeam) {
        final int baseBonus = 125; // → bounty max 250 (2× base)
        var tm = LolPlugin.getInstance().getTeamManager();
        var gm = goldManager;
        if (gm == null) return 0;
        long teamGold = 0, enemyGold = 0;
        var participants = gameInstance != null ? gameInstance.getParticipants() : siblingGameManager().getParticipants();
        for (var id : participants) {
            var p = org.bukkit.Bukkit.getPlayer(id);
            if (p == null) continue;
            var t = tm.getTeam(p);
            // Or GAGNÉ (et non l'or en poche) : une équipe qui a acheté ses objets n'est pas « en retard »
            if (t == attackingTeam) teamGold += gm.getEarned(id);
            else if (t != null)     enemyGold += gm.getEarned(id);
        }
        long deficit = enemyGold - teamGold;
        if (deficit < 3000) return 0;
        // Bounty proportionnel au retard, plafonné à 2× le bonus de base
        return (int) Math.min(baseBonus * 2L, baseBonus * deficit / 6000L);
    }

    /**
     * Plaque de tourelle détruite : son or est partagé entre les champions vivants proches et
     * celui qui la fait tomber. Le décompte des plaques est tenu par TurretManager.
     */
    public void onPlateDestroyed(Player attacker, GameStructure turret, int platesLeft, int gold) {
        if (gameStartMs == 0) return;
        var team = LolPlugin.getInstance().getTeamManager().getTeam(attacker);
        splitGold(localChampions(turret.getCenter(), team, attacker), gold,
                "🏛 Plaque (" + platesLeft + " restantes)");
    }

    /**
     * Tourelle détruite : or global à toute l'équipe selon le niveau de la tourelle (50/25/25/50,
     * morts compris), +300 partagés entre les proches pour la première tourelle de la partie,
     * et bounty d'objectif (comeback). Pas d'XP (LoL).
     */
    public void onTurretDestroyed(Player lastHit, fr.lolmc.team.TeamManager.Team attackingTeam, GameStructure turret) {
        int objBounty = getObjectiveBounty(attackingTeam);
        if (objBounty > 0 && lastHit != null) {
            goldManager.addGold(lastHit.getUniqueId(), objBounty);
            lastHit.sendActionBar(net.kyori.adventure.text.Component.text(
                "💰 Bounty d'objectif +" + objBounty + " or (comeback)!",
                NamedTextColor.GOLD));
        }
        int globalGold = switch (turret.getTier()) {
            case OUTER     -> GOLD_TURRET_OUTER_GLOBAL;
            case INNER     -> GOLD_TURRET_INNER_GLOBAL;
            case INHIBITOR -> GOLD_TURRET_INHIBITOR_GLOBAL;
            case NEXUS     -> GOLD_TURRET_NEXUS_GLOBAL;
        };
        String teamName = attackingTeam == fr.lolmc.team.TeamManager.Team.BLUE ? "Bleue" : "Rouge";
        var participants2 = gameInstance != null ? gameInstance.getParticipants() : siblingGameManager().getParticipants();
        for (java.util.UUID id : participants2) {
            var p = org.bukkit.Bukkit.getPlayer(id);
            if (p == null) continue;
            if (LolPlugin.getInstance().getTeamManager().getTeam(p) == attackingTeam)
                goldManager.addGold(id, globalGold);
        }
        if (!firstTurretDone) {
            firstTurretDone = true;
            splitGold(localChampions(turret.getCenter(), attackingTeam, lastHit), GOLD_FIRST_TURRET,
                    "🏛 Première tourelle !");
        }
        announce(net.kyori.adventure.text.Component.text(
            "🏛 Tourelle détruite! +" + globalGold + " or — Équipe " + teamName,
            net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }

    /** Annonce aux seuls joueurs de cette partie. */
    private void announce(net.kyori.adventure.text.Component message) {
        fr.lolmc.util.WorldContext.broadcast(gameInstance != null ? gameInstance.getWorld()
                : fr.lolmc.util.WorldContext.getGameWorld(), message);
    }


    /** Inhibiteur détruit : 50 or au seul last-hit (LoL), pas d'or global ni d'XP. */
    public void onInhibitorDestroyed(Player lastHit, fr.lolmc.team.TeamManager.Team attackingTeam) {
        String teamName = attackingTeam == fr.lolmc.team.TeamManager.Team.BLUE ? "Bleue" : "Rouge";
        if (lastHit != null) {
            goldManager.addGold(lastHit.getUniqueId(), GOLD_INHIBITOR_KILLER);
            lastHit.sendActionBar(net.kyori.adventure.text.Component.text(
                "💎 Inhibiteur! +" + GOLD_INHIBITOR_KILLER + " or",
                net.kyori.adventure.text.format.NamedTextColor.AQUA));
        }
        announce(net.kyori.adventure.text.Component.text(
            "💎 Inhibiteur détruit — Équipe " + teamName,
            net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    public void onInhibitorDestroyed(fr.lolmc.team.TeamManager.Team attackingTeam) {
        onInhibitorDestroyed(null, attackingTeam);
    }
}
