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

    private boolean gameRunning = false;
    // Tâches BukkitTask stockées pour annulation explicite
    private org.bukkit.scheduler.BukkitTask timerTask;
    private org.bukkit.scheduler.BukkitTask passiveGoldTask;
    private org.bukkit.scheduler.BukkitTask respawnTask;
    private org.bukkit.scheduler.BukkitTask respawnTotalSeconds_task;
    // Temps de respawn figé au moment de la mort (bug bossbar)
    private final java.util.Map<java.util.UUID, Integer> respawnTotalSecondsMap
        = new java.util.concurrent.ConcurrentHashMap<>();
    // ── Snapshots joueurs pour reconnexion ───────────────────────────────
    // Sauvegarde l'état complet (HP, level, or, items) à la déconnexion
    private final java.util.Map<java.util.UUID, PlayerSnapshot> playerSnapshots
        = new java.util.concurrent.ConcurrentHashMap<>();

    public record PlayerSnapshot(
        double hp, double maxHp, double resource, int level, int gold,
        java.util.UUID teamId, long respawnAt
    ) {}

    /** Appelé depuis AbilityListener.onQuit quand un joueur quitte en pleine partie. */
    public void onPlayerLeave(org.bukkit.entity.Player player) {
        if (!gameRunning || !participants.contains(player.getUniqueId())) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;
        var champ = cm.getChampion(player);
        int gold = LolPlugin.getInstance().getGoldManager().getGold(player.getUniqueId());
        long respawn = respawnAt.getOrDefault(player.getUniqueId(), 0L);
        playerSnapshots.put(player.getUniqueId(), new PlayerSnapshot(
            champ.getHPSystem().getCurrentHP(),
            champ.getHPSystem().getMaxHP(),
            champ.getResourceSystem().getCurrent(),
            champ.getLevelSystem().getLevel(),
            gold, player.getUniqueId(), respawn
        ));
        LolPlugin.getInstance().getLogger().info("[GameManager] Snapshot sauvegardé pour "
            + player.getName() + " (level=" + champ.getLevelSystem().getLevel()
            + " hp=" + (int)champ.getHPSystem().getCurrentHP() + ")");
    }

    /** Appelé depuis AbilityListener.onJoin quand un joueur revient en partie. */
    public void onPlayerRejoin(org.bukkit.entity.Player player) {
        if (!gameRunning) return;
        PlayerSnapshot snap = playerSnapshots.remove(player.getUniqueId());
        if (snap == null) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;
        var champ = cm.getChampion(player);
        // Restaurer HP, level, or
        champ.getHPSystem().setCurrentHP(snap.hp());
        champ.getLevelSystem().setLevel(snap.level());
        LolPlugin.getInstance().getGoldManager().setGold(player.getUniqueId(), snap.gold());
        champ.getResourceSystem().setCurrent(snap.resource());
        // Restaurer le respawn si applicable
        if (snap.respawnAt() > System.currentTimeMillis())
            respawnAt.put(player.getUniqueId(), snap.respawnAt());
        player.sendMessage(net.kyori.adventure.text.Component.text(
            "✔ Reconnecté! Ton état a été restauré (level " + snap.level() + ", "
            + (int)snap.hp() + " HP, " + snap.gold() + " or).",
            net.kyori.adventure.text.format.NamedTextColor.GREEN));
        LolPlugin.getInstance().getLogger().info("[GameManager] Snapshot restauré pour " + player.getName());
    }

    // Inhibiteurs détruits avec leur timestamp de respawn (5 min = 300s)
    private final java.util.Map<String, Long> inhibitorRespawnAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long INHIBITOR_RESPAWN_MS = 300_000L; // 5 minutes
    private long gameStartTime = 0;

    // BossBar de timer partagée
    private BossBar timerBar;

    // Joueurs morts en attente de respawn : UUID → timestamp de respawn
    private final Map<UUID, Long> respawnAt = new HashMap<>();
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
    public void startGame() {
        gameRunning = true;
        gameStartTime = System.currentTimeMillis();
        startSystems();
        // Démarrer les sous-systèmes
        var __pm = LolPlugin.getInstance().getPassiveManager();
        if (__pm != null) __pm.startTasks();
        captureParticipants();
        timerBar = BossBar.bossBar(Component.text("Partie — 00:00"),
                1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        for (Player p : WorldContext.getGamePlayers()) {
            p.showBossBar(timerBar);
        }

        // Donner la minimap en main secondaire à chaque participant
        var mm = LolPlugin.getInstance().getMinimapManager();
        for (Player p : WorldContext.getGamePlayers()) {
            mm.giveMinimap(p);
        }
    }

    public void stopGame() {
        gameRunning = false;
        stopSystems();
        // États statiques champions
        fr.lolmc.util.ChampionStateReset.resetAll();
        // Annonces (kill spree, first blood, multi-kills)
        LolPlugin.getInstance().getAnnouncementManager().reset();
        // Feats of Strength
        LolPlugin.getInstance().getFeatManager().reset();
        // Inhibiteurs en attente de respawn
        inhibitorRespawnAt.clear();
        respawnTotalSecondsMap.clear();
        if (timerBar != null) {
            for (Player p : WorldContext.getGamePlayers()) p.hideBossBar(timerBar);
        }
        respawnAt.clear();
        for (BossBar bar : respawnBars.values()) {
            for (Player p : WorldContext.getGamePlayers()) p.hideBossBar(bar);
        }
        respawnBars.clear();
        participants.clear();
        participantTeam.clear();
        goldAccumulator.clear();
        playerSnapshots.clear();
        // Remettre les joueurs dans un état normal (SURVIVAL, etc.)
        for (org.bukkit.entity.Player p : fr.lolmc.util.WorldContext.getGamePlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR)
                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
    }

    /** Capture les joueurs présents (avec champion) comme participants de la partie. */
    private void captureParticipants() {
        participants.clear();
        participantTeam.clear();
        var cm = LolPlugin.getInstance().getChampionManager();
        var tm = LolPlugin.getInstance().getTeamManager();
        for (Player p : WorldContext.getGamePlayers()) {
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
                timerBar.name(Component.text("⏱ Partie — " + time, NamedTextColor.WHITE));
                // S'assurer que les nouveaux joueurs voient la barre
                for (Player p : WorldContext.getGamePlayers()) {
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
                for (Player p : WorldContext.getGamePlayers()) {
                    if (cm.hasChampion(p) && !isDead(p)) {
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
        int idx = Math.max(1, Math.min(18, level)) - 1;
        double brw = BRW_TABLE[idx];

        // Time Increase Factor (TIFx) : augmente le timer selon le temps de jeu
        double minutes = getElapsedSeconds() / 60.0;
        double tifx = 0;
        if (minutes >= 15) {
            // Formule officielle LoL (par paliers de temps)
            // Entre 15 et 30 min : +0.425% par demi-minute écoulée depuis 15min
            tifx += (Math.ceil((minutes - 15) * 2) * 0.425) / 100.0;
        }
        if (minutes >= 30) {
            tifx += (Math.ceil((minutes - 30) * 2) * 0.30) / 100.0;
        }
        if (minutes >= 45) {
            tifx += (Math.ceil((minutes - 45) * 2) * 1.45) / 100.0;
        }
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

        // BossBar de respawn
        BossBar bar = BossBar.bossBar(
                Component.text("☠ Réapparition dans " + respawnSecs + "s", NamedTextColor.RED),
                1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        respawnBars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);

        // Mettre le joueur en mode spectateur pendant la mort
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);

        // Téléporter à la base de son équipe
        Team team = LolPlugin.getInstance().getTeamManager().getTeam(player);
        if (team != null) {
            Location spawn = LolPlugin.getInstance().getMapManager().getSpawn(team, 1);
            if (spawn != null) player.teleport(spawn);
        }
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
                    if (p == null) { it.remove(); continue; }

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
                            bar.progress(Math.max(0f, Math.min(1f, (float) remaining / total)));
                        }
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 10L, 10L);
    }

    private void respawn(Player player) {
        // Retirer la BossBar de respawn
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
            Location spawn = LolPlugin.getInstance().getMapManager().getSpawn(team, 1);
            if (spawn != null) player.teleport(spawn);
        }

        player.sendActionBar(Component.text("✨ Réapparition!", NamedTextColor.GREEN));
    }

    public void cleanup(UUID uuid) {
        respawnAt.remove(uuid);
        BossBar bar = respawnBars.remove(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            if (bar != null) p.hideBossBar(bar);
            if (timerBar != null) p.hideBossBar(timerBar);
        }
    }

    /** Enregistre la destruction d'un inhibiteur (clé = team_lane). */
    public void onInhibitorDestroyed(String key) {
        inhibitorRespawnAt.put(key, System.currentTimeMillis() + INHIBITOR_RESPAWN_MS);
        // Annonce
        org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text(
            "🏛 Inhibiteur " + key + " détruit! Respawn dans 5 minutes.", net.kyori.adventure.text.format.NamedTextColor.RED));
        startInhibitorRespawnCheck(key);
    }

    private void startInhibitorRespawnCheck(String key) {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                Long at = inhibitorRespawnAt.get(key);
                if (at == null || !gameRunning) { cancel(); return; }
                if (System.currentTimeMillis() >= at) {
                    inhibitorRespawnAt.remove(key);
                    // Notifier MapManager de reconstruire l'inhibiteur
                    LolPlugin.getInstance().getMapManager().respawnInhibitor(key);
                    // Arrêter les super-sbires sur cette lane
                    LolPlugin.getInstance().getMinionManager().onInhibitorRespawned(key);
                    org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text(
                        "🏛 Inhibiteur " + key + " a repoussé!", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                    cancel();
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }
}