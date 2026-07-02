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
    public static final int GOLD_MINION_MELEE = 21;   // sbire mêlée (valeur LoL exacte)
    public static final int GOLD_MINION_CASTER = 17;  // sbire caster (valeur LoL exacte)
    public static final int GOLD_MINION_CANNON = 60;  // sbire canon/siège (60-90 selon temps)
    public static final int GOLD_CHAMPION_KILL = 300; // kill de base (valeur LoL)
    // Or global (distribué à toute l'équipe) pour destruction de structures
    // Tourelles — or global par type (LoL)
    public static final int GOLD_TURRET_T1_GLOBAL = 600; // T1 extérieure
    public static final int GOLD_TURRET_T2_GLOBAL = 750; // T2 intérieure
    public static final int GOLD_TURRET_T3_GLOBAL = 875; // T3 inhibiteur
    public static final int GOLD_TURRET_KILLER    = 150; // bonus last-hit
    public static final int GOLD_TURRET_PLATE     = 160; // plaque (avant 14min)
    public static final int TURRET_PLATE_MAX      = 5;
    public static final long TURRET_PLATE_DURATION = 14 * 60 * 1000L;
    // Inhibiteur
    public static final int GOLD_INHIBITOR_GLOBAL = 400;
    public static final int GOLD_INHIBITOR_KILLER = 25;
    // Nexus
    public static final int GOLD_NEXUS_GLOBAL     = 100;
    // XP structures
    public static final double XP_TURRET    = 100;
    public static final double XP_INHIBITOR = 150;
    // Plaques par tourelle
    private final java.util.Map<String, Integer> turretPlates = new java.util.concurrent.ConcurrentHashMap<>();
    public static final int GOLD_ASSIST = 150;

    public static final double XP_MINION = 60;
    public static final double XP_CHAMPION_KILL = 200;
    public static final double XP_ASSIST = 100;

    // ── Bounty système (serie de kills = plus d'or à la mort) ────────────
    // killStreak[uuid] = nombre de kills consécutifs sans mourir
    private final java.util.Map<java.util.UUID, Integer> killStreak = new java.util.concurrent.ConcurrentHashMap<>();
    // Table bounty LoL : 0 kills = 0, 1=100, 2=200, 3=300, 4=400, 5=500, 6+=500 (plafond)
    private static final int[] BOUNTY = {0, 100, 200, 300, 400, 500, 500};

    // ── Système d'assists ─────────────────────────────────────────────────
    // Map<victim_uuid, Map<attacker_uuid, lastDamageTimestamp>>
    // Fenêtre : dégâts infligés dans les 10s avant la mort = assist
    private final java.util.Map<java.util.UUID,
        java.util.Map<java.util.UUID, Long>> damageContrib = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ASSIST_WINDOW_MS = 10_000L;

    /** Enregistre un dégât infligé par [attacker] sur [victim] (appelé depuis DamageUtil). */
    public void recordDamage(java.util.UUID attacker, java.util.UUID victim) {
        damageContrib
            .computeIfAbsent(victim, k -> new java.util.concurrent.ConcurrentHashMap<>())
            .put(attacker, System.currentTimeMillis());
    }

    /** Retourne les joueurs éligibles à l'assist (ont infligé des dégâts dans les 10s). */
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

    // ── Or des sbires croissant par tranche de temps ──────────────────────
    // En LoL : +0.5 or/sbire toutes les 90s à partir de 1:15, plafonné vers 15min
    private long gameStartMs = 0;
    public void setGameStart() { gameStartMs = System.currentTimeMillis(); }

    private int getMinionGoldBonus() {
        if (gameStartMs == 0) return 0;
        long elapsed = (System.currentTimeMillis() - gameStartMs) / 1000L;
        // +1 or toutes les 90s (arrondi à l'entier, max +10)
        return (int) Math.min(10, elapsed / 90);
    }

    public RewardManager(ChampionManager championManager, GoldManager goldManager) {
        this.championManager = championManager;
        this.goldManager = goldManager;
    }

    // ── Last-hit sur sbire ────────────────────────────────────────

    public void onMinionKill(Player killer, int goldAmount, double xpAmount) {
        if (!championManager.hasChampion(killer)) return;
        int bonus = getMinionGoldBonus();
        int total = goldAmount + bonus;
        goldManager.addGold(killer.getUniqueId(), total);
        grantXP(killer, xpAmount);
        // CS tracker
        var msb = LolPlugin.getInstance().getMatchScoreboard();
        if (msb != null) msb.addCS(killer);
        killer.sendActionBar(net.kyori.adventure.text.Component.text(
                String.format("+%d or  +%.0f XP", total, xpAmount), NamedTextColor.GOLD));
    }

    // ── Kill de champion ──────────────────────────────────────────

    public void onChampionKill(Player killer, Player victim) {
        if (!championManager.hasChampion(killer)) return;
        // Bounty de la victime
        int victimStreak = killStreak.getOrDefault(victim.getUniqueId(), 0);
        int bounty = BOUNTY[Math.min(victimStreak, BOUNTY.length - 1)];
        int totalGold = GOLD_CHAMPION_KILL + bounty;
        goldManager.addGold(killer.getUniqueId(), totalGold);
        grantXP(killer, XP_CHAMPION_KILL);
        // Reset streak de la victime, incrémenter celle du tueur
        killStreak.put(victim.getUniqueId(), 0);
        killStreak.merge(killer.getUniqueId(), 1, Integer::sum);
        // Killing spree announcement
        int streak = killStreak.get(killer.getUniqueId());
        String spreeMsg = streak == 3 ? "⚔ KILLING SPREE!" : streak == 4 ? "⚔⚔ RAMPAGE!" : streak == 5 ? "⚔⚔⚔ UNSTOPPABLE!" : streak >= 6 ? "⚔⚔⚔⚔ GODLIKE!" : null;
        String goldMsg = bounty > 0 ? String.format("⚔ KILL! +%d or (+%d bounty) +%.0f XP", totalGold, bounty, XP_CHAMPION_KILL) : String.format("⚔ KILL! +%d or +%.0f XP", totalGold, XP_CHAMPION_KILL);
        killer.sendActionBar(net.kyori.adventure.text.Component.text(goldMsg, NamedTextColor.GOLD));
        if (spreeMsg != null) killer.sendMessage(net.kyori.adventure.text.Component.text(spreeMsg, NamedTextColor.RED));

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

    /** XP de proximité (sbire mort sans last-hit) — public pour EntityDeathListener. */
    public void grantProximityXP(Player player, double xp) {
        grantXP(player, xp);
    }

    private void grantXP(Player player, double xp) {
        BaseChampion champ = championManager.getChampion(player);
        int levelsGained = champ.getLevelSystem().addXP(xp);
        if (levelsGained > 0) {
            int newLevel = champ.getLevelSystem().getLevel();
            // Synchroniser le niveau avec les stats (déclenche la croissance LoL)
            champ.getStats().setChampionLevel(newLevel);
            player.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.text.Component.text("⬆ Niveau " + newLevel,
                        net.kyori.adventure.text.format.NamedTextColor.GREEN),
                    net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(250),
                        java.time.Duration.ofMillis(1500),
                        java.time.Duration.ofMillis(500))));
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
    public void resetKillStreaks() { killStreak.clear(); gameStartMs = 0; }

    /** Plaque de tourelle : +160 or avant 14min, max 5 par tour. */
    public void onTurretHit(Player attacker, GameStructure turret) {
        if (gameStartMs == 0) return;
        if (System.currentTimeMillis() - gameStartMs > TURRET_PLATE_DURATION) return;
        String tid = turret.getId();
        int plates = turretPlates.getOrDefault(tid, TURRET_PLATE_MAX);
        if (plates <= 0) return;
        turretPlates.put(tid, plates - 1);
        goldManager.addGold(attacker.getUniqueId(), GOLD_TURRET_PLATE);
        attacker.sendActionBar(net.kyori.adventure.text.Component.text(
            "🏛 Plaque! +" + GOLD_TURRET_PLATE + " or (" + (plates-1) + " restantes)",
            net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    /** Distribue l'or et l'XP quand une tourelle est détruite. */
    public void onTurretDestroyed(Player lastHit, fr.lolmc.team.TeamManager.Team attackingTeam, int turretIndex) {
        int globalGold = switch (turretIndex) {
            case 1  -> GOLD_TURRET_T1_GLOBAL;
            case 2  -> GOLD_TURRET_T2_GLOBAL;
            default -> GOLD_TURRET_T3_GLOBAL;
        };
        String teamName = attackingTeam == fr.lolmc.team.TeamManager.Team.BLUE ? "Bleue" : "Rouge";
        for (java.util.UUID id : LolPlugin.getInstance().getGameManager().getParticipants()) {
            var p = org.bukkit.Bukkit.getPlayer(id);
            if (p == null) continue;
            if (LolPlugin.getInstance().getTeamManager().getTeam(p) == attackingTeam) {
                goldManager.addGold(id, globalGold);
                grantXP(p, XP_TURRET);
            }
        }
        if (lastHit != null) {
            goldManager.addGold(lastHit.getUniqueId(), GOLD_TURRET_KILLER);
            lastHit.sendActionBar(net.kyori.adventure.text.Component.text(
                "🏛 Tourelle T" + turretIndex + "! +" + (globalGold + GOLD_TURRET_KILLER) + " or",
                net.kyori.adventure.text.format.NamedTextColor.GOLD));
        }
        org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text(
            "🏛 Tourelle T" + turretIndex + " détruite! +" + globalGold + " or — Équipe " + teamName,
            net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }

    public void onTurretDestroyed(Player lastHit, fr.lolmc.team.TeamManager.Team attackingTeam) {
        onTurretDestroyed(lastHit, attackingTeam, 1);
    }

    /** Distribue l'or et l'XP quand un inhibiteur est détruit. */
    public void onInhibitorDestroyed(Player lastHit, fr.lolmc.team.TeamManager.Team attackingTeam) {
        String teamName = attackingTeam == fr.lolmc.team.TeamManager.Team.BLUE ? "Bleue" : "Rouge";
        for (java.util.UUID id : LolPlugin.getInstance().getGameManager().getParticipants()) {
            var p = org.bukkit.Bukkit.getPlayer(id);
            if (p == null) continue;
            if (LolPlugin.getInstance().getTeamManager().getTeam(p) == attackingTeam) {
                goldManager.addGold(id, GOLD_INHIBITOR_GLOBAL);
                grantXP(p, XP_INHIBITOR);
            }
        }
        if (lastHit != null) {
            goldManager.addGold(lastHit.getUniqueId(), GOLD_INHIBITOR_KILLER);
            lastHit.sendActionBar(net.kyori.adventure.text.Component.text(
                "💎 Inhibiteur! +" + (GOLD_INHIBITOR_GLOBAL + GOLD_INHIBITOR_KILLER) + " or",
                net.kyori.adventure.text.format.NamedTextColor.AQUA));
        }
        org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text(
            "💎 Inhibiteur détruit! +" + GOLD_INHIBITOR_GLOBAL + " or — Équipe " + teamName,
            net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    public void onInhibitorDestroyed(fr.lolmc.team.TeamManager.Team attackingTeam) {
        onInhibitorDestroyed(null, attackingTeam);
    }
}
