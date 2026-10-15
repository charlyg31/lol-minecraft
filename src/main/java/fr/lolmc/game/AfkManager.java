package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Détection des joueurs inactifs (AFK) en partie, façon LoL.
 *
 * Toute action (déplacement, rotation de la caméra, clic, changement de slot, boutique…) remet
 * le compteur à zéro ; un champion mort n'est jamais inactif. Après {@code afk.warn-seconds}
 * sans action, le joueur est averti ; après {@code afk.flag-seconds}, il est signalé absent à son
 * équipe. Tant qu'un allié est absent (ou déconnecté), l'abandon est possible dès
 * {@code afk.early-ff-minutes} et l'absent ne compte pas dans les votes. En classé, un joueur
 * signalé absent pendant la partie ne gagne pas d'Elo et perd {@code afk.elo-penalty} points.
 *
 * Config config.yml :
 *   afk:
 *     enabled: true
 *     warn-seconds: 120
 *     flag-seconds: 180
 *     early-ff-minutes: 5
 *     elo-penalty: 15
 */
public class AfkManager implements Listener {

    private final Map<UUID, Long> lastActivity = new HashMap<>();
    /** Avertis (le message n'est envoyé qu'une fois par période d'inactivité). */
    private final Set<UUID> warned = new HashSet<>();
    /** Actuellement absents. */
    private final Set<UUID> afk = new HashSet<>();
    /** Signalés absents au moins une fois pendant la partie en cours (pénalité de fin de partie). */
    private final Set<UUID> leavers = new HashSet<>();

    private BukkitTask task;

    public AfkManager() {
        task = Bukkit.getScheduler().runTaskTimer(LolPlugin.getInstance(), this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private static org.bukkit.configuration.file.FileConfiguration cfg() {
        return LolPlugin.getInstance().getConfig();
    }

    private static boolean enabled() { return cfg().getBoolean("afk.enabled", true); }

    // ── Cycle de partie ───────────────────────────────────────────

    /** Début de partie : compteurs remis à zéro pour ces joueurs. */
    public void startGame(Collection<UUID> players) {
        long now = System.currentTimeMillis();
        for (UUID id : players) {
            lastActivity.put(id, now);
            warned.remove(id);
            afk.remove(id);
            leavers.remove(id);
        }
    }

    /** Fin de partie : oublie ces joueurs. */
    public void endGame(Collection<UUID> players) {
        for (UUID id : players) {
            lastActivity.remove(id);
            warned.remove(id);
            afk.remove(id);
            leavers.remove(id);
        }
    }

    /** Actuellement absent. */
    public boolean isAfk(UUID id) { return afk.contains(id); }

    /** Signalé absent au moins une fois pendant la partie en cours. */
    public boolean wasAfk(UUID id) { return leavers.contains(id); }

    /** Les joueurs signalés absents parmi {@code players}. */
    public Set<UUID> leaversAmong(Collection<UUID> players) {
        Set<UUID> out = new HashSet<>();
        for (UUID id : players) if (leavers.contains(id)) out.add(id);
        return out;
    }

    /**
     * Un allié de {@code ref} (même partie, même équipe) est-il absent ou déconnecté ?
     * Sert à avancer l'abandon.
     */
    public boolean teamHasAbsent(Player ref, Team team) {
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(ref);
        for (UUID id : gm.getParticipants()) {
            if (gm.getParticipantTeam(id) != team) continue;
            if (afk.contains(id)) return true;
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) return true;
        }
        return false;
    }

    public static int earlyForfeitMinutes() { return Math.max(0, cfg().getInt("afk.early-ff-minutes", 5)); }

    public static int eloPenalty() { return Math.max(0, cfg().getInt("afk.elo-penalty", 15)); }

    // ── Activité ──────────────────────────────────────────────────

    private void touch(Player p) {
        UUID id = p.getUniqueId();
        if (!lastActivity.containsKey(id)) return; // pas en partie
        lastActivity.put(id, System.currentTimeMillis());
        warned.remove(id);
        if (afk.remove(id)) {
            Team team = LolPlugin.getInstance().getTeamManager().getTeam(p);
            if (team != null) broadcastTeam(p, team, Component.text(
                    "✔ " + p.getName() + " est de retour.", NamedTextColor.GREEN));
        }
    }

    // ignoreCancelled = false : un mouvement annulé (joueur figé) reste une action du joueur
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        var from = e.getFrom();
        var to = e.getTo();
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()
                || from.getX() != to.getX() || from.getZ() != to.getZ()) touch(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) { touch(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent e) { touch(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent e) { touch(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwap(PlayerSwapHandItemsEvent e) { touch(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) { touch(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) { touch(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) touch(p);
    }

    // ── Vérification périodique ───────────────────────────────────

    private void tick() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        long warnMs = cfg().getInt("afk.warn-seconds", 120) * 1000L;
        long flagMs = Math.max(warnMs, cfg().getInt("afk.flag-seconds", 180) * 1000L);
        var cm = LolPlugin.getInstance().getChampionManager();

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            var gm = fr.lolmc.instance.InstanceHelper.gameManager(p);
            if (!gm.isGameRunning() || !gm.isParticipant(id) || !cm.hasChampion(p)) continue;
            // Un mort ne peut rien faire : son compteur repart à sa réapparition
            if (gm.isDead(p) || !lastActivity.containsKey(id)) { lastActivity.put(id, now); continue; }

            long idle = now - lastActivity.get(id);
            if (idle >= flagMs && afk.add(id)) {
                leavers.add(id);
                p.showTitle(net.kyori.adventure.title.Title.title(
                        Component.text("ABSENT", NamedTextColor.RED),
                        Component.text("Ton équipe a été prévenue.", NamedTextColor.GRAY)));
                Team team = LolPlugin.getInstance().getTeamManager().getTeam(p);
                if (team != null) broadcastTeam(p, team, Component.text(
                        "⚠ " + p.getName() + " est inactif (AFK) : l'abandon est possible dès "
                        + earlyForfeitMinutes() + " min (/l ff).", NamedTextColor.GOLD));
            } else if (idle >= warnMs && idle < flagMs && warned.add(id)) {
                long left = (flagMs - idle) / 1000;
                p.showTitle(net.kyori.adventure.title.Title.title(
                        Component.text("Inactif ?", NamedTextColor.YELLOW),
                        Component.text("Bouge dans " + left + " s ou tu seras signalé absent.",
                                NamedTextColor.GRAY)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f);
            }
        }
    }

    private static void broadcastTeam(Player ref, Team team, Component msg) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var inst = fr.lolmc.instance.InstanceHelper.instanceOf(ref);
        Collection<Player> group = inst != null ? inst.getOnlinePlayers() : fr.lolmc.util.WorldContext.getGamePlayers();
        for (Player q : group) if (tm.getTeam(q) == team) q.sendMessage(msg);
    }
}
