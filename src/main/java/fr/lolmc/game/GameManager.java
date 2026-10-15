package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère le cycle de vie d'une partie :
 *  - Timer de partie affiché sur une BossBar
 *  - Or passif (gold over time) pour tous les joueurs
 *  - Mort et respawn avec timer croissant selon le niveau
 */
public class GameManager {

    // Référence à l'instance (null si mode singleton legacy)
    private fr.lolmc.instance.GameInstance gameInstance = null;

    /** Lie ce GameManager à une GameInstance (mode multi-instance). */
    public void setGameInstance(fr.lolmc.instance.GameInstance inst) {
        this.gameInstance = inst;
    }
    public fr.lolmc.instance.GameInstance getGameInstance() { return gameInstance; }

    private AnnouncementManager siblingAnnouncementManager() {
        return gameInstance != null ? gameInstance.getAnnouncementManager() : LolPlugin.getInstance().getAnnouncementManager();
    }
    private FeatManager siblingFeatManager() {
        return gameInstance != null ? gameInstance.getFeatManager() : LolPlugin.getInstance().getFeatManager();
    }
    private JungleManager siblingJungleManager() {
        return gameInstance != null ? gameInstance.getJungleManager() : LolPlugin.getInstance().getJungleManager();
    }
    private MapManager siblingMapManager() {
        return gameInstance != null ? gameInstance.getMapManager() : LolPlugin.getInstance().getMapManager();
    }
    private MinionManager siblingMinionManager() {
        return gameInstance != null ? gameInstance.getMinionManager() : LolPlugin.getInstance().getMinionManager();
    }

    private boolean gameRunning = false;
    private final java.util.Map<java.util.UUID, fr.lolmc.game.PlayerSnapshot> snapshots
        = new java.util.concurrent.ConcurrentHashMap<>();
    // Tâches BukkitTask stockées pour annulation explicite
    public void saveSnapshot(org.bukkit.entity.Player player) { snapshots.put(player.getUniqueId(), new fr.lolmc.game.PlayerSnapshot(player)); }
    public fr.lolmc.game.PlayerSnapshot getPlayerSnapshot(java.util.UUID uuid) { return snapshots.remove(uuid); }

    private org.bukkit.scheduler.BukkitTask timerTask;
    private org.bukkit.scheduler.BukkitTask passiveGoldTask;
    private org.bukkit.scheduler.BukkitTask respawnTask;
    // Temps de respawn figé au moment de la mort (bug bossbar)
    private final java.util.Map<java.util.UUID, Integer> respawnTotalSecondsMap
        = new java.util.concurrent.ConcurrentHashMap<>();
    // Inhibiteurs détruits avec leur timestamp de respawn (5 min = 300s)
    private final java.util.Map<String, Long> inhibitorRespawnAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long INHIBITOR_RESPAWN_MS = 300_000L; // 5 minutes
    private long gameStartTime = 0;

    // BossBar de timer partagée
    private BossBar timerBar;

    // Joueurs morts en attente de respawn : UUID → timestamp de respawn
    private final Map<UUID, Long> respawnAt = new HashMap<>();
    // Morts déconnectés : décompte en pause (ms restantes), il ne court que si le joueur est en ligne
    private final Map<UUID, Long> pausedRespawnMs = new HashMap<>();
    // BossBar individuelle de respawn
    private final Map<UUID, BossBar> respawnBars = new HashMap<>();
    // Participants de la partie en cours (pour la reconnexion)
    private final java.util.Set<UUID> participants = new java.util.HashSet<>();
    private final Map<UUID, fr.lolmc.team.TeamManager.Team> participantTeam = new HashMap<>();

    // Or passif LoL : 20.4 or / 10s, démarre à 1:50, distribué toutes les 0.5s
    // 20.4 / 20 ticks-de-0.5s = 1.02 or par demi-seconde
    private static final double PASSIVE_GOLD_PER_HALFSEC = 20.4 / 20.0; // = 1.02
    private static final long PASSIVE_GOLD_PERIOD = 10L; // toutes les 0.5s (10 ticks)
    private static final long PASSIVE_GOLD_START_SECONDS = 110; // 1:50
    // Accumulateur d'or fractionnaire par joueur (pour gérer le 1.02)
    private final Map<UUID, Double> goldAccumulator = new HashMap<>();

    public GameManager() {
        // NE PAS démarrer les tâches ici.
        // Appelé par startGame() → startSystems().
    }

    // ══════════════════════════════════════════════════════════════
    // CYCLE DE PARTIE
    // ══════════════════════════════════════════════════════════════

    /** Démarre les systèmes de partie (tâches BukkitTask). */
    public void startSystems() {
        startTimerTask();
        startPassiveGoldTask();
        startRespawnTask();
    }

    /** Arrête les tâches de partie (annulation propre). */
    public void stopSystems() {
        stopTimerTask();
        stopPassiveGoldTask();
        stopRespawnTask();
    }

    private void stopRespawnTask() { if (respawnTask != null) { respawnTask.cancel(); respawnTask = null; } }

    // ── Isolation entre parties ─────────────────────────────────────────

    /** Joueurs de CETTE partie (monde de l'instance), sinon ceux du monde de jeu configuré. */
    private TabScoreboardManager tab;
    private TabScoreboardManager tab() {
        if (gameInstance == null) return LolPlugin.getInstance().getTabScoreboard();
        if (tab == null) tab = new TabScoreboardManager(this::players);
        return tab;
    }

    private java.util.Collection<Player> players() {
        return gameInstance != null ? gameInstance.getOnlinePlayers() : WorldContext.getGamePlayers();
    }

    /** Vrai si une AUTRE partie (globale ou instance) est en cours : les états partagés ne doivent pas être touchés. */
    private boolean otherGameRunning() {
        var lp = LolPlugin.getInstance();
        var g = lp.getGameManager();
        if (g != null && g != this && g.isRunning()) return true;
        var im = lp.getInstanceManager();
        if (im != null) {
            for (var inst : im.getAllInstances()) {
                var gm = inst.getGameManager();
                if (gm != null && gm != this && gm.isRunning()) return true;
            }
        }
        return false;
    }

    public void startGame() {
        gameRunning = true;
        gameStartTime = System.currentTimeMillis();
        // Horloges de partie : plaques de tourelles (14 min), or croissant des sbires, séries de kills
        var lp = LolPlugin.getInstance();
        var rewards = gameInstance != null ? gameInstance.getRewardManager() : lp.getRewardManager();
        if (rewards != null) { rewards.resetKillStreaks(); rewards.setGameStart(); }
        var turrets = gameInstance != null ? gameInstance.getTurretManager() : lp.getTurretManager();
        if (turrets != null) turrets.setGameStart();
        startSystems();
        // Démarrer les sous-systèmes
        var __pm = LolPlugin.getInstance().getPassiveManager();
        if (__pm != null) __pm.startTasks();
        captureParticipants();
        timerBar = BossBar.bossBar(Component.text("Partie — 00:00"),
                1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        for (Player p : players()) {
            p.showBossBar(timerBar);
        }

        // Donner la minimap en main secondaire à chaque participant
        var mm = LolPlugin.getInstance().getMinimapManager();
        for (Player p : players()) {
            mm.giveMinimap(p);
        }
        // Tab scoreboard
        tab().start();
        // Marqueurs de cible (rouge visee / vert verrouillee)
        LolPlugin.getInstance().getAutoAttackManager().startVisuals();
        // FF + death recap reset
        LolPlugin.getInstance().getForfeitManager().reset(gameInstance != null ? gameInstance.getId() : 0);
    }

    public void stopGame() {
        gameRunning = false;
        stopSystems();
        // États statiques champions
        final boolean last = !otherGameRunning();
        if (last) fr.lolmc.util.ChampionStateReset.resetAll();
        // Annonces (kill spree, first blood, multi-kills)
        siblingAnnouncementManager().reset();
        // Feats of Strength
        siblingFeatManager().reset();
        // Tab scoreboard
        if (gameInstance != null || last) tab().stop();
        if (last) LolPlugin.getInstance().getAutoAttackManager().stopVisuals();
        // Réinitialiser les skins
        if (last) LolPlugin.getInstance().getSkinManager().resetAll();
        // FF reset
        LolPlugin.getInstance().getForfeitManager().reset(gameInstance != null ? gameInstance.getId() : 0);
        // Passifs d'objets : arrêt des tâches et remise à zéro de l'état par joueur
        var passives = LolPlugin.getInstance().getPassiveManager();
        if (passives != null && last) { passives.stopTasks(); passives.cleanupAll(); }
        // Consommables (charges de fiole, potions en cours)
        var consumables = LolPlugin.getInstance().getConsumableManager();
        if (consumables != null && last) consumables.reset();
        // Inhibiteurs en attente de respawn
        inhibitorRespawnAt.clear();
        respawnTotalSecondsMap.clear();
        if (timerBar != null) {
            for (Player p : players()) p.hideBossBar(timerBar);
        }
        respawnAt.clear();
        pausedRespawnMs.clear();
        for (BossBar bar : respawnBars.values()) {
            for (Player p : players()) p.hideBossBar(bar);
        }
        respawnBars.clear();
        participants.clear();
        participantTeam.clear();
        goldAccumulator.clear();
        snapshots.clear();
        // Remettre les joueurs dans un état normal (SURVIVAL, etc.)
        for (org.bukkit.entity.Player p : players()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR)
                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }

        // Les joueurs encore en file peuvent maintenant former un nouveau match
        // (le retour des joueurs vers le lobby / leur serveur d'origine est géré par
        // StructureDamageListener.endMatch, après l'écran de fin de partie)
        var matchmaking = LolPlugin.getInstance().getMatchmakingManager();
        if (matchmaking != null) matchmaking.retryQueue();
    }

    /** Capture les joueurs présents (avec champion) comme participants de la partie. */
    private void captureParticipants() {
        participants.clear();
        participantTeam.clear();
        var cm = LolPlugin.getInstance().getChampionManager();
        var tm = LolPlugin.getInstance().getTeamManager();
        for (Player p : players()) {
            if (cm.hasChampion(p)) {
                participants.add(p.getUniqueId());
                if (tm.hasTeam(p)) participantTeam.put(p.getUniqueId(), tm.getTeam(p));
            }
        }
    }

    /** Ce joueur fait-il partie de la partie en cours ? (pour la reconnexion) */
    public boolean isParticipant(UUID id) { return participants.contains(id); }
    public fr.lolmc.team.TeamManager.Team getParticipantTeam(UUID id) { return participantTeam.get(id); }

    public boolean isRunning() { return gameRunning; }
    public boolean isGameRunning() { return isRunning(); }
    public java.util.Set<java.util.UUID> getParticipants() { return participants; }

    public long getElapsedSeconds() {
        if (!gameRunning) return 0;
        return (System.currentTimeMillis() - gameStartTime) / 1000;
    }

    // ── Timer sur BossBar ─────────────────────────────────────────

    private void startTimerTask() {
        if (timerTask != null) return;
        timerTask = new BukkitRunnable() {
            @Override public void run() {
                if (!gameRunning || timerBar == null) return;
                long secs = getElapsedSeconds();
                String time = String.format("%02d:%02d", secs / 60, secs % 60);
                // Chrono respawn épiques dans la BossBar
                var jm2 = siblingJungleManager();
                StringBuilder epicTimers = new StringBuilder();
                if (jm2 != null) {
                    long now2 = System.currentTimeMillis();
                    for (var re : jm2.getEpicRespawnAt().entrySet()) {
                        long rem = (re.getValue() - now2) / 1000L;
                        if (rem > 0) epicTimers.append(" | ").append(re.getKey()).append(" ").append(String.format("%02d:%02d", rem/60, rem%60));
                    }
                }
                timerBar.name(Component.text("⏱ " + time + epicTimers, NamedTextColor.WHITE));
                // BossBar (temps + chrono épiques) pour chaque joueur ; le détail or/CS/PV/mana est
                // affiché en continu par HUDManager sur la barre d'action, pour éviter que les deux
                // systèmes ne se disputent le même emplacement d'affichage (clignotement).
                for (Player p : players()) {
                    p.showBossBar(timerBar);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }

    private void stopTimerTask() { if (timerTask != null) { timerTask.cancel(); timerTask = null; } }

    // ══════════════════════════════════════════════════════════════
    // OR PASSIF
    // ══════════════════════════════════════════════════════════════

    private void startPassiveGoldTask() {
        if (passiveGoldTask != null) return;
        passiveGoldTask = new BukkitRunnable() {
            @Override public void run() {
                if (!gameRunning) return;
                // L'or passif ne démarre qu'à 1:50 (comme LoL)
                if (getElapsedSeconds() < PASSIVE_GOLD_START_SECONDS) return;
                var goldManager = LolPlugin.getInstance().getGoldManager();
                var cm = LolPlugin.getInstance().getChampionManager();
                for (Player p : players()) {
                    if (cm.hasChampion(p)) { // l'or passif continue pendant la mort (LoL)
                        // Accumuler l'or fractionnaire (1.02/demi-sec) et verser les entiers
                        double acc = goldAccumulator.getOrDefault(p.getUniqueId(), 0.0)
                                + PASSIVE_GOLD_PER_HALFSEC;
                        int whole = (int) acc;
                        if (whole > 0) {
                            goldManager.addGold(p.getUniqueId(), whole);
                            acc -= whole;
                        }
                        goldAccumulator.put(p.getUniqueId(), acc);
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), PASSIVE_GOLD_PERIOD, PASSIVE_GOLD_PERIOD);
    }

    private void stopPassiveGoldTask() { if (passiveGoldTask != null) { passiveGoldTask.cancel(); passiveGoldTask = null; } }
    
    

    // ══════════════════════════════════════════════════════════════
    // MORT & RESPAWN AVEC TIMER
    // ══════════════════════════════════════════════════════════════

    /**
     * Calcule le temps de respawn selon le niveau (formule LoL simplifiée).
     * Base ~10s au niveau 1, augmente avec le niveau.
     */
    // Table officielle LoL des temps de respawn de base (BRW) par niveau 1-18
    private static final double[] BRW_TABLE = {
        10, 10, 12, 12, 14, 16, 20, 25, 28, 32.5, 35, 37.5, 40, 42.5, 45, 47.5, 50, 52.5
    };

    public int computeRespawnSeconds(int level) {
        // BRW de base selon le niveau (table officielle LoL)
        int idx = Math.clamp(level, 1, 18) - 1;
        double brw = BRW_TABLE[idx];

        // Time Increase Factor (TIFx) : augmente le timer selon le temps de jeu
        double minutes = getElapsedSeconds() / 60.0;
        double tifx = 0;
        // Données du jeu (DeathTimes) : par tranche de 30 s, +0.425 % de 15 à 30 min, +0.30 % de 30
        // à 45 min, +1.45 % au-delà (avant : la hausse 15-30 continuait après 30 min et se cumulait)
        if (minutes >= 15) tifx += Math.ceil((Math.min(minutes, 30) - 15) * 2) * 0.425 / 100.0;
        if (minutes >= 30) tifx += Math.ceil((Math.min(minutes, 45) - 30) * 2) * 0.30 / 100.0;
        if (minutes >= 45) tifx += Math.ceil((minutes - 45) * 2) * 1.45 / 100.0;
        // Plafond du TIFx à 50%
        tifx = Math.min(tifx, 0.50);

        double total = brw + brw * tifx;
        return (int) Math.round(total);
    }

    /**
     * Déclenche la mort d'un joueur : timer de respawn + masquage.
     */
    public void onPlayerDeath(Player player) {
        if (!gameRunning) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;

        int level = cm.getChampion(player).getLevelSystem().getLevel();
        int respawnSecs = computeRespawnSeconds(level);
        respawnAt.put(player.getUniqueId(), System.currentTimeMillis() + respawnSecs * 1000L);
        respawnTotalSecondsMap.put(player.getUniqueId(), respawnSecs); // durée figée pour la BossBar

        // BossBar de respawn
        BossBar bar = BossBar.bossBar(
                Component.text("☠ Réapparition dans " + respawnSecs + "s", NamedTextColor.RED),
                1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        respawnBars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);

        // Nettoyer ce qui ne doit pas survivre à la mort : potions (ralentissements, buffs de
        // sorts…), contrôles, Téléportation en cours
        for (var effect : new java.util.ArrayList<>(player.getActivePotionEffects()))
            player.removePotionEffect(effect.getType());
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null) cc.clear(player.getUniqueId());
        JungleManager.clearElderBuff(player.getUniqueId()); // Aspect du Dragon perdu à la mort
        JungleBuffs.onDeath(player); // Main du Baron et crêtes perdues à la mort
        var ssm = LolPlugin.getInstance().getSummonerSpellManager();
        if (ssm != null && ssm.isChanneling(player)) ssm.interruptTeleport(player);

        // Mettre le joueur en mode spectateur pendant la mort
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);

        // Téléporter à la base de son équipe
        Team team = LolPlugin.getInstance().getTeamManager().getTeam(player);
        if (team != null) {
            Location spawn = siblingMapManager().getBaseSpawn(team);
            if (spawn != null) player.teleport(spawn);
        }
    }

    /**
     * Reconnexion d'un participant : s'il était mort, reprendre le décompte (BossBar) ou le
     * faire réapparaître si le délai est écoulé ; s'il est resté spectateur sans décompte,
     * le faire réapparaître.
     */
    public void onRejoin(Player player) {
        if (!gameRunning) return;
        UUID id = player.getUniqueId();
        // Reprise du décompte mis en pause à la déconnexion
        Long paused = pausedRespawnMs.remove(id);
        if (paused != null) respawnAt.put(id, System.currentTimeMillis() + paused);
        Long until = respawnAt.get(id);
        if (until != null && System.currentTimeMillis() < until) {
            BossBar bar = respawnBars.computeIfAbsent(id, _ -> BossBar.bossBar(
                    Component.text("☠ Réapparition…", NamedTextColor.RED), 1.0f,
                    BossBar.Color.RED, BossBar.Overlay.PROGRESS));
            player.showBossBar(bar);
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        } else if (until != null || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            respawnAt.remove(id);
            respawn(player);
        }
        if (timerBar != null) player.showBossBar(timerBar);
    }

    /**
     * Déconnexion d'un mort : le décompte de réapparition est mis en pause (sinon il suffirait
     * de se déconnecter pour attendre son respawn hors ligne, puis revenir).
     */
    public void pauseRespawn(UUID id) {
        Long until = respawnAt.remove(id);
        if (until != null) pausedRespawnMs.put(id, Math.max(0L, until - System.currentTimeMillis()));
    }

    public boolean isDead(Player player) {
        Long until = respawnAt.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    private void startRespawnTask() {
        if (respawnTask != null) return;
        respawnTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Long>> it = respawnAt.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    Player p = Bukkit.getPlayer(entry.getKey());
                    // Hors ligne : garder l'entrée (avant : supprimée, et le joueur restait
                    // spectateur pour toujours en se reconnectant)
                    if (p == null) continue;

                    long remaining = (entry.getValue() - now) / 1000;
                    if (remaining <= 0) {
                        // Réapparition
                        it.remove();
                        respawn(p);
                    } else {
                        // Mettre à jour la BossBar de respawn
                        BossBar bar = respawnBars.get(entry.getKey());
                        if (bar != null) {
                            bar.name(Component.text("☠ Réapparition dans " + remaining + "s", NamedTextColor.RED));
                            int level = LolPlugin.getInstance().getChampionManager().hasChampion(p)
                                    ? LolPlugin.getInstance().getChampionManager().getChampion(p).getLevelSystem().getLevel() : 1;
                            int total = respawnTotalSecondsMap.getOrDefault(entry.getKey(), computeRespawnSeconds(level));
                            bar.progress(Math.clamp((float) remaining / total, 0f, 1f));
                        }
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 10L, 10L);
    }

    private void respawn(Player player) {
        // Retirer la BossBar de respawn
        respawnTotalSecondsMap.remove(player.getUniqueId());
        BossBar bar = respawnBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);

        // Remettre en mode survie
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);

        var cm = LolPlugin.getInstance().getChampionManager();
        if (cm.hasChampion(player)) {
            BaseChampion champ = cm.getChampion(player);
            // Restaurer HP/ressource pleins
            champ.getHPSystem().setCurrentHP(champ.getHPSystem().getMaxHP());
            champ.getResourceSystem().fill();
            champ.getStats().clearShields();
        }

        // Téléporter au spawn de l'équipe
        Team team = LolPlugin.getInstance().getTeamManager().getTeam(player);
        if (team != null) {
            Location spawn = siblingMapManager().getBaseSpawn(team);
            if (spawn != null) player.teleport(spawn);
        }

        player.sendActionBar(Component.text("✨ Réapparition!", NamedTextColor.GREEN));
    }

    public void cleanup(UUID uuid) {
        respawnAt.remove(uuid);
        pausedRespawnMs.remove(uuid);
        BossBar bar = respawnBars.remove(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            if (bar != null) p.hideBossBar(bar);
            if (timerBar != null) p.hideBossBar(timerBar);
        }
    }

    /** Annonce aux seuls joueurs de cette partie. */
    private void announce(Component message) {
        if (gameInstance != null) {
            fr.lolmc.util.WorldContext.broadcast(gameInstance.getWorld(), message);
        } else {
            fr.lolmc.util.WorldContext.broadcast(WorldContext.getGameWorld(), message);
        }
    }

    /** Enregistre la destruction d'un inhibiteur (clé = team_lane). */
    public void onInhibitorDestroyed(String key) {
        inhibitorRespawnAt.put(key, System.currentTimeMillis() + INHIBITOR_RESPAWN_MS);
        // Annonce
        announce(net.kyori.adventure.text.Component.text(
            "🏛 Inhibiteur " + key + " détruit! Respawn dans 5 minutes.", net.kyori.adventure.text.format.NamedTextColor.RED));
        startInhibitorRespawnCheck(key);
    }

    private void startInhibitorRespawnCheck(String key) {
        new org.bukkit.scheduler.BukkitRunnable() {
            boolean warned = false;
            @Override public void run() {
                Long at = inhibitorRespawnAt.get(key);
                if (at == null || !gameRunning) { cancel(); return; }
                // Annonce 15 s avant la repousse (LoL)
                if (!warned && at - System.currentTimeMillis() <= 15_000L) {
                    warned = true;
                    announce(net.kyori.adventure.text.Component.text(
                        "🏛 Inhibiteur " + key + " : repousse dans 15 secondes!", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                }
                if (System.currentTimeMillis() >= at) {
                    inhibitorRespawnAt.remove(key);
                    // Notifier MapManager de reconstruire l'inhibiteur
                    siblingMapManager().respawnInhibitor(key);
                    // Arrêter les super-sbires sur cette lane
                    siblingMinionManager().onInhibitorRespawned(key);
                    announce(net.kyori.adventure.text.Component.text(
                        "🏛 Inhibiteur " + key + " a repoussé!", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                    cancel();
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }
}