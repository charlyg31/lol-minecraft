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
            // Vérifier passifs défensifs (Sterak's, Guardian Angel)
            PassiveManager pm = LolPlugin.getInstance().getPassiveManager();
            if (pm != null) LolPlugin.getInstance().getBushManager().revealOnDamage(p);
            pm.onDamageTaken(p, finalDmg);
            hud.updateHUD(p, champ);
        }
    }

    // ── À la connexion → désactiver regen vanilla ─────────────────
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Désactiver la regen naturelle de Minecraft
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExhaustion(0f);

        // Reconnexion : un tick plus tard (joueur pleinement chargé pour le téléport)
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                var plugin = LolPlugin.getInstance();
                var gm = plugin.getGameManager();
                var cm = plugin.getChampionManager();
                var tm = plugin.getTeamManager();
                java.util.UUID id = p.getUniqueId();

                boolean canRejoin = gm.isRunning() && gm.isParticipant(id) && cm.hasChampion(p);
                if (canRejoin) {
                    // Restaurer le joueur dans SA partie (son champion/équipe/objets ont été gardés)
                    var team = gm.getParticipantTeam(id);
                    if (team != null && !tm.hasTeam(p)) tm.setTeam(p, team);
                    var champ = cm.getChampion(p);
                    try { plugin.getHotbarManager().renderPage(p, champ); } catch (Exception ignored) {}
                    var spawn = (team != null) ? plugin.getMapManager().getSpawn(team, 1) : null;
                    if (spawn != null) p.teleport(spawn);
                    p.sendMessage(net.kyori.adventure.text.Component.text(
                            "🔄 Reconnecté à ta partie en cours.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                } else {
                    // Pas de partie en cours pour ce joueur → ne pas le laisser sur la map
                    if (cm.hasChampion(p)) cm.removeChampion(p);
                    tm.removePlayer(id);
                    var world = plugin.getServer().getWorlds().get(0);
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
            var spawn = LolPlugin.getInstance().getMapManager().getSpawn(team, 1);
            if (spawn != null) {
                LolPlugin.getInstance().getServer().getScheduler().runTaskLater(
                    LolPlugin.getInstance(), () -> p.teleport(spawn), 2L);
            }
        }

        // Re-init le HUD
        LolPlugin.getInstance().getServer().getScheduler()
            .runTaskLater(LolPlugin.getInstance(),
                () -> hud.initPlayer(p, champ), 1L);
    }
}
