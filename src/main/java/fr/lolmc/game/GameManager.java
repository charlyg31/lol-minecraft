package fr.lolmc.game;

import fr.lolmc.LolPlugin;
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

    private boolean gameRunning = false;
    private long gameStartTime = 0;

    // BossBar de timer partagée
    private BossBar timerBar;

    // Joueurs morts en attente de respawn : UUID → timestamp de respawn
    private final Map<UUID, Long> respawnAt = new HashMap<>();
    // BossBar individuelle de respawn
    private final Map<UUID, BossBar> respawnBars = new HashMap<>();

    // Or passif : 1 or toutes les secondes (comme LoL ~2.4/s après 110s, simplifié)
    private static final int PASSIVE_GOLD_PER_TICK = 2;
    private static final long PASSIVE_GOLD_PERIOD = 20L; // chaque seconde

    public GameManager() {
        startTimerTask();
        startPassiveGoldTask();
        startRespawnTask();
    }

    // ══════════════════════════════════════════════════════════════
    // CYCLE DE PARTIE
    // ══════════════════════════════════════════════════════════════

    public void startGame() {
        gameRunning = true;
        gameStartTime = System.currentTimeMillis();
        timerBar = BossBar.bossBar(Component.text("Partie — 00:00"),
                1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(timerBar);
        }
    }

    public void stopGame() {
        gameRunning = false;
        if (timerBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(timerBar);
        }
        respawnAt.clear();
        for (BossBar bar : respawnBars.values()) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        }
        respawnBars.clear();
    }

    public boolean isRunning() { return gameRunning; }

    public long getElapsedSeconds() {
        if (!gameRunning) return 0;
        return (System.currentTimeMillis() - gameStartTime) / 1000;
    }

    // ── Timer sur BossBar ─────────────────────────────────────────

    private void startTimerTask() {
        new BukkitRunnable() {
            @Override public void run() {
                if (!gameRunning || timerBar == null) return;
                long secs = getElapsedSeconds();
                String time = String.format("%02d:%02d", secs / 60, secs % 60);
                timerBar.name(Component.text("⏱ Partie — " + time, NamedTextColor.WHITE));
                // S'assurer que les nouveaux joueurs voient la barre
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showBossBar(timerBar);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }

    // ══════════════════════════════════════════════════════════════
    // OR PASSIF
    // ══════════════════════════════════════════════════════════════

    private void startPassiveGoldTask() {
        new BukkitRunnable() {
            @Override public void run() {
                if (!gameRunning) return;
                var goldManager = LolPlugin.getInstance().getGoldManager();
                var cm = LolPlugin.getInstance().getChampionManager();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (cm.hasChampion(p) && !isDead(p)) {
                        goldManager.addGold(p.getUniqueId(), PASSIVE_GOLD_PER_TICK);
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), PASSIVE_GOLD_PERIOD, PASSIVE_GOLD_PERIOD);
    }

    // ══════════════════════════════════════════════════════════════
    // MORT & RESPAWN AVEC TIMER
    // ══════════════════════════════════════════════════════════════

    /**
     * Calcule le temps de respawn selon le niveau (formule LoL simplifiée).
     * Base ~10s au niveau 1, augmente avec le niveau.
     */
    public int computeRespawnSeconds(int level) {
        // LoL : BRW (Base Respawn Wait) augmente par paliers
        // Approximation : 6 + niveau * 2.5, plafonné
        double seconds = 6 + level * 2.5;
        // Augmentation selon le temps de jeu (plus la partie avance, plus c'est long)
        double minutes = getElapsedSeconds() / 60.0;
        if (minutes > 15) seconds *= 1.3;
        if (minutes > 30) seconds *= 1.5;
        return (int) Math.min(seconds, 60); // plafond 60s
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
        new BukkitRunnable() {
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
                            int total = computeRespawnSeconds(level);
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
}
