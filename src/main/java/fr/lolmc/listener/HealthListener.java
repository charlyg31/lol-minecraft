package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.item.PassiveManager;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.stats.HPSystem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class HealthListener implements Listener {

    private final ChampionManager manager;
    private final HUDManager hud;

    public HealthListener(ChampionManager manager, HUDManager hud) {
        this.manager = manager;
        this.hud = hud;
    }

    // ── Bloquer la regen HP vanilla (gérée par nous) ─────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onHealthRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!manager.hasChampion(p)) return;

        // Bloquer toute regen Minecraft sauf la nôtre (MAGIC = potions OK)
        var reason = e.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED
                || reason == EntityRegainHealthEvent.RegainReason.REGEN
                || reason == EntityRegainHealthEvent.RegainReason.EATING) {
            e.setCancelled(true);
        }
    }

    // ── Bloquer la faim ───────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onFoodChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!manager.hasChampion(p)) return;
        e.setCancelled(true);
        p.setFoodLevel(20);
    }

    // ── Intercepter les dégâts → les passer au HPSystem ──────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!manager.hasChampion(p)) return;
        // Ne pas interférer avec le setHealth(0.0001) de triggerDeath (le joueur est déjà passé
        // en spectateur juste avant) : sinon cet événement est annulé et la mort Minecraft
        // n'a jamais lieu, malgré HPSystem qui le considère déjà mort.
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;

        // Annuler le dégât Minecraft natif (on gère nous-mêmes)
        e.setCancelled(true);

        // Les dégâts des sorts sont déjà gérés dans AbilityListener
        // Ici on gère seulement les dégâts environnementaux (chute, feu, etc.)
        double dmg = e.getDamage();
        if (dmg <= 0) return;

        var cause = e.getCause();
        // Garder les dégâts de chute, feu, explosion, noyade pour le gameplay
        boolean isEnvironmental = switch (cause) {
            case FALL, FIRE, FIRE_TICK, LAVA, DROWNING,
                 POISON, WITHER, VOID, SUFFOCATION -> true;
            default -> false;
        };

        if (isEnvironmental) {
            BaseChampion champ = manager.getChampion(p);
            HPSystem hp = champ.getHPSystem();
            double finalDmg = dmg * 5;
            hp.takeDamage(finalDmg);
            // Notifier les passifs de dégâts reçus
            if ("malphite".equals(champ.getId()))
                fr.lolmc.champion.impl.top.Malphite.onDamageTaken(p.getUniqueId());
            // Vérifier passifs défensifs (Sterak's, Guardian Angel)
            PassiveManager pm = LolPlugin.getInstance().getPassiveManager();
            if (pm != null) pm.onDamageTaken(p);
            hud.updateHUD(p, champ);
        }
    }

    /** Fenêtre « en combat » pour la déconnexion : dégâts reçus il y a moins de 10 s. */
    private static final long COMBAT_LOG_WINDOW_MS = 10_000L;

    // ── Déconnexion : sauvegarde de l'état, et anti-déconnexion en combat ─────
    // LOWEST : la mort doit être traitée avant les nettoyages des autres listeners
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        Player p = e.getPlayer();
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(p);
        if (gm != null && gm.isGameRunning()
                && LolPlugin.getInstance().getChampionManager().hasChampion(p)) {
            punishCombatLog(p);
            gm.pauseRespawn(p.getUniqueId()); // le décompte ne court que si le joueur est en ligne
            gm.saveSnapshot(p);
        }
        var bridge = LolPlugin.getInstance().getBridgeManager();
        if (bridge != null) bridge.cleanupPlayer(p.getUniqueId());
    }

    /**
     * Se déconnecter en combat pour échapper à la mort compte comme une mort : le dernier
     * champion ennemi à l'avoir touché (10 s) reçoit le kill, l'or et les assistances ; le
     * décompte de réapparition est en pause tant qu'il est hors ligne.
     * Hors combat, la déconnexion ne coûte rien (le joueur se reconnecte au même endroit).
     */
    private void punishCombatLog(Player p) {
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return; // déjà mort
        var champ = LolPlugin.getInstance().getChampionManager().getChampion(p);
        if (champ == null) return;
        var rm = fr.lolmc.instance.InstanceHelper.rewardManager(p);
        boolean hitByChampion = rm != null && rm.getLastAttacker(p.getUniqueId()) != null;
        boolean recentlyHit = champ.getHPSystem().msSinceLastDamage() < COMBAT_LOG_WINDOW_MS;
        if (!hitByChampion && !recentlyHit) return;

        fr.lolmc.util.WorldContext.broadcast(p.getWorld(), net.kyori.adventure.text.Component.text(
                "⚠ " + p.getName() + " s'est déconnecté en combat : compté comme mort.",
                net.kyori.adventure.text.format.NamedTextColor.RED));
        hud.triggerDeath(p, null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExhaustion(0f);

        // Reconnexion un tick plus tard pour s'assurer que le joueur est pleinement chargé
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) return;
                var plugin = LolPlugin.getInstance();
                var rejoinInst = plugin.getInstanceManager() != null ? plugin.getInstanceManager().getInstanceOf(p) : null;
                var gm = rejoinInst != null ? rejoinInst.getGameManager() : plugin.getGameManager();
                var cm = plugin.getChampionManager();
                var tm = plugin.getTeamManager();
                java.util.UUID id = p.getUniqueId();

                boolean canRejoin = gm.isRunning() && gm.isParticipant(id);
                if (canRejoin) {
                    var team = gm.getParticipantTeam(id);
                    if (team != null && !tm.hasTeam(p)) tm.setTeam(p, team);
                    
                    // Récupération et restauration complète du Snapshot
                    var snap = gm.getPlayerSnapshot(id);
                    // Mort pendant la déconnexion : décompte repris ou réapparition (après la
                    // restauration ci-dessous, qui recrée le champion)
                    final var gmRejoin = gm;
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline()) gmRejoin.onRejoin(p);
                    }, 2L);
                    if (snap != null) {
                        snap.restore(p);
                        p.sendMessage(net.kyori.adventure.text.Component.text(
                                "✔ Reconnexion — Ton état de partie a été restauré !", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                    } else if (cm.hasChampion(p)) {
                        var champ = cm.getChampion(p);
                        try { plugin.getHotbarManager().renderPage(p, champ); } catch (Exception ex) { fr.lolmc.LolPlugin.getInstance().getLogger().log(java.util.logging.Level.WARNING, "[HealthListener] erreur ignorée (ligne 140)", ex); }
                        plugin.getMinimapManager().giveMinimap(p);
                        var rejoinMap = rejoinInst != null ? rejoinInst.getMapManager() : plugin.getMapManager();
                        var spawn = (team != null) ? rejoinMap.getBaseSpawn(team) : null;
                        if (spawn != null) p.teleport(spawn);
                        p.sendMessage(net.kyori.adventure.text.Component.text(
                                "🔄 Reconnecté à ta partie en cours.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                    }
                } else {
                    if (cm.hasChampion(p)) cm.removeChampion(p);
                    tm.removePlayer(id);
                    var world = plugin.getServer().getWorlds().getFirst();
                    p.teleport(world.getSpawnLocation());
                    if (gm.isRunning()) {
                        p.sendMessage(net.kyori.adventure.text.Component.text(
                                "Une partie est en cours mais tu n'en fais pas partie.", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    }
                }
            }
        }.runTaskLater(LolPlugin.getInstance(), 1L);
    }


    // ── Respawn → réinitialiser HP et ressource ───────────────────
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasChampion(p)) return;

        BaseChampion champ = manager.getChampion(p);
        // Reset HP à max après respawn
        champ.getHPSystem().setCurrentHP(champ.getHPSystem().getMaxHP());
        champ.getResourceSystem().fill();
        champ.getStats().clearShields();
        // Téléporter au spawn de l'équipe
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(p);
        if (team != null) {
            // Position = index du joueur dans l'équipe (simplifié: position 1-5)
            var spawn = fr.lolmc.instance.InstanceHelper.mapManager(p).getBaseSpawn(team);
            if (spawn != null) {
                LolPlugin.getInstance().getServer().getScheduler().runTaskLater(
                    LolPlugin.getInstance(), () -> p.teleport(spawn), 2L);
            }
        }

        // Re-init le HUD + minimap en offhand
        LolPlugin.getInstance().getServer().getScheduler()
            .runTaskLater(LolPlugin.getInstance(), () -> {
                hud.initPlayer(p, champ);
                LolPlugin.getInstance().getMinimapManager().giveMinimap(p);
            }, 1L);
    }
}
