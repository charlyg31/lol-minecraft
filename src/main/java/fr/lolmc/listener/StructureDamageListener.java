package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.game.GameStructure;
import fr.lolmc.game.GameStructure.Type;
import fr.lolmc.game.MapManager;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.team.TeamManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Gère les dégâts infligés aux structures (tourelles/nexus).
 *
 * Un joueur attaque une structure en cliquant (auto-attaque) dans sa zone.
 * Applique la règle de protection : le Nexus principal ne peut être attaqué
 * que si au moins 1 nexus de lane ET les 2 tourelles de base sont détruits.
 */
public class StructureDamageListener implements Listener {

    private final MapManager mapManager;
    private final ChampionManager championManager;
    private final TeamManager teamManager;

    // Rayon dans lequel un clic compte comme une attaque sur la structure
    private static final double ATTACK_RADIUS = 3.0;

    public StructureDamageListener(MapManager mapManager, ChampionManager championManager, TeamManager teamManager) {
        this.mapManager = mapManager;
        this.championManager = championManager;
        this.teamManager = teamManager;
    }

    @EventHandler
    public void onAttack(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_AIR) return;
        Player player = e.getPlayer();
        if (!championManager.hasChampion(player)) return;
        Team playerTeam = teamManager.getTeam(player);
        if (playerTeam == null) return;

        // Chercher une structure ennemie dans le rayon d'attaque devant le joueur
        Location eye = player.getEyeLocation();
        GameStructure structure = null;
        // On vise jusqu'à 6 blocs devant
        for (double d = 0; d <= 6.0; d += 0.5) {
            Location point = eye.clone().add(eye.getDirection().multiply(d));
            GameStructure s = mapManager.getStructureAt(point, ATTACK_RADIUS);
            if (s != null) { structure = s; break; }
        }
        if (structure == null) return;

        // On n'attaque que les structures ENNEMIES
        if (structure.getTeam() == playerTeam) return;

        // ── Règle de protection du Nexus principal ──
        if (structure.getType() == Type.NEXUS_BASE) {
            if (!mapManager.canAttackBaseNexus(structure.getTeam())) {
                player.sendActionBar(Component.text(
                        "🛡 Le Nexus est protégé ! Détruis d'abord un nexus de lane et les 2 tourelles de base.",
                        NamedTextColor.RED));
                return;
            }
        }

        // Infliger les degats (bases sur l'AD du champion)
        BaseChampion champ = championManager.getChampion(player);
        double damage = champ.getStats().getFinalAD();
        // Turret Plating : -40% degats si plaques actives
        var tm = LolPlugin.getInstance().getTurretManager();
        String structKey = structure.getType().name() + "_" + structure.getTeam() + "_" + structure.getLane();
        if (structure.getType() == fr.lolmc.game.GameStructure.Type.TURRET && tm.hasPlating(structKey)) {
            damage *= 0.60;
            tm.tickPlating(structKey, player);
        }

        boolean phaseChanged = structure.takeDamage(damage);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_HIT, 0.5f, 1.2f);

        if (structure.isDestroyed()) {
            onStructureDestroyed(structure, player);
        } else {
            // Mettre à jour la schématique si changement de phase
            if (phaseChanged) {
                mapManager.updateStructurePhase(structure);
            }
            player.sendActionBar(Component.text(String.format(
                    "%s : %.0f/%.0f HP (%.0f%%)",
                    structureName(structure), structure.getCurrentHP(),
                    structure.getMaxHP(), structure.getHealthPercent()), NamedTextColor.YELLOW));
        }
    }

    private void onStructureDestroyed(GameStructure structure, Player destroyer) {
        String name = structureName(structure);
        Team enemyTeam = structure.getTeam();

        // Inhibiteur détruit → super-sbires sur cette lane pour l'équipe adverse
        if (structure.getType() == Type.TURRET) {
            LolPlugin.getInstance().getRewardManager().onTurretDestroyed(player, playerTeam);
        } else if (structure.getType() == Type.INHIBITOR) {
            LolPlugin.getInstance().getMinionManager()
                    .enableSuperMinions(enemyTeam, structure.getLane());
            String inhKey = structure.getType().name() + "_" + enemyTeam.name() + "_" + structure.getLane();
            LolPlugin.getInstance().getGameManager().onInhibitorDestroyed(inhKey);
            LolPlugin.getInstance().getAnnouncementManager().announceInhibitorDestroyed(
                    structure.getLane(), enemyTeam.name());
            LolPlugin.getInstance().getRewardManager().onInhibitorDestroyed(playerTeam);
        }

        // Annonce
        LolPlugin.getInstance().getServer().broadcast(Component.text(
                "💥 " + name + " (" + enemyTeam.name() + ") détruit par " + destroyer.getName() + "!",
                NamedTextColor.GOLD));

        // Or de récompense (comme LoL)
        var goldManager = LolPlugin.getInstance().getGoldManager();
        if (structure.getType() == Type.TURRET) {
            goldManager.addGold(destroyer.getUniqueId(), 250);
        }

        // Effet visuel de destruction
        Location c = structure.getCenter().clone().add(0.5, 1, 0.5);
        c.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER, c, 3, 1, 1, 1);
        c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);

        // ── Victoire si Nexus principal détruit ──
        if (structure.getType() == Type.NEXUS_BASE) {
            Team winner = enemyTeam.opposite();
            announceVictory(winner);
        }
    }

    private void announceVictory(Team winner) {
        Component msg = Component.text("🏆 VICTOIRE DE L'ÉQUIPE " + winner.name() + " ! 🏆",
                winner.chatColor);
        for (Player p : LolPlugin.getInstance().getServer().getOnlinePlayers()) {
            p.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("VICTOIRE " + winner.name(), winner.chatColor),
                    Component.empty(),
                    net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(500),
                        java.time.Duration.ofMillis(4000),
                        java.time.Duration.ofMillis(1000))));
            p.sendMessage(msg);
        }
        // Afficher le tableau de score de fin de partie
        LolPlugin.getInstance().getMatchScoreboard().showEndScreen(winner);
        // Arrêter la partie
        LolPlugin.getInstance().getMinionManager().stopWaves();
        LolPlugin.getInstance().getJungleManager().stopJungle();
        LolPlugin.getInstance().getGameManager().stopGame();
        // Retour au lobby après 30s
        var mm  = LolPlugin.getInstance().getMatchmakingManager();
        var im  = LolPlugin.getInstance().getInstanceManager();
        // Trouver l'instance du joueur qui a détruit le Nexus
        var winnerInstance = (player != null) ? im.getInstanceOf(player) : null;
        new org.bukkit.scheduler.BukkitRunnable() {
            int countdown = 30;
            @Override public void run() {
                if (countdown <= 0) {
                    var bridge = LolPlugin.getInstance().getBridgeManager();
                    // Pour chaque joueur : retour sur son serveur d'origine
                    // (ou téléportation locale si pas de BungeeCord)
                    for (Player p : fr.lolmc.util.WorldContext.getGamePlayers()) {
                        if (bridge != null && bridge.isEnabled()) {
                            // BungeeCord : envoyer vers le serveur d'origine
                            bridge.sendPlayerToOrigin(p);
                        } else {
                            // Pas de BungeeCord : téléporter à la position sauvegardée
                            if (mm != null) mm.returnPlayerToPreGameLocation(p);
                        }
                    }
                    // Aussi nettoyer les joueurs des instances
                    if (mm != null) mm.returnAllToPreGameLocations();
                    // Fermer l'instance
                    if (winnerInstance != null)
                        im.closeInstance(winnerInstance, 60L);
                    cancel();
                    return;
                }
                if (countdown <= 10 || countdown % 10 == 0) {
                    for (Player p : fr.lolmc.util.WorldContext.getGamePlayers()) {
                        p.sendActionBar(net.kyori.adventure.text.Component.text(
                            "🏠 Retour dans " + countdown + "s",
                            net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    }
                }
                countdown--;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }

    private String structureName(GameStructure s) {
        return switch (s.getType()) {
            case TURRET -> "Tourelle " + s.getLane() + " #" + s.getIndex();
            case INHIBITOR -> "Inhibiteur " + s.getLane();
            case NEXUS -> "Nexus " + s.getLane();
            case NEXUS_BASE -> "Nexus Principal";
        };
    }
}
