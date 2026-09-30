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
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
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

    /**
     * Applique les dégâts d'une auto-attaque sur une structure.
     * Appelé depuis AbilityListener quand un joueur attaque depuis sa portée AA LoL.
     */
    public void applyAutoAttackDamage(Player player, GameStructure structure) {
        if (!championManager.hasChampion(player)) return;
        Team playerTeam = teamManager.getTeam(player);
        if (playerTeam == null || structure.getTeam() == playerTeam) return;
        if (structure.isDestroyed()) return;

        // Protection Nexus
        if (structure.getType() == Type.NEXUS_BASE && !mapManager.canAttackBaseNexus(structure.getTeam())) {
            player.sendActionBar(Component.text(
                "🛡 Le Nexus est protégé !", NamedTextColor.RED));
            return;
        }

        BaseChampion champ = championManager.getChampion(player);
        double damage = champ.getStats().getFinalAD();

        // Bonus voidgrubs (3 grubs: +6%, 6 grubs: +14%)
        var jm = fr.lolmc.instance.InstanceHelper.jungleManager(player);
        if (jm != null && structure.getType() == Type.TURRET) {
            damage *= jm.getVoidgrubDamageBonus(playerTeam);
        }

        // Fortification LoL : les tours externes (T1) prennent -50% de
        // degats pendant les 5 premieres minutes
        if (structure.getType() == Type.TURRET
                && structure.getIndex() == 1
                && fr.lolmc.instance.InstanceHelper.gameManager(player).getElapsedSeconds() < 300) {
            damage *= 0.5;
        }

        // Anti-backdoor : structure fortifiée (-66% dégâts) si aucun sbire
        // allié de l'attaquant n'est à moins de 12 blocs (règle LoL)
        boolean minionNearby = false;
        for (var ent : structure.getCenter().getWorld()
                .getNearbyEntities(structure.getCenter(), 12, 8, 12)) {
            if (ent instanceof org.bukkit.entity.LivingEntity le
                    && fr.lolmc.game.MinionManager.isMinion(le)
                    && fr.lolmc.game.MinionManager.getMinionTeam(le) == playerTeam) {
                minionNearby = true; break;
            }
        }
        if (!minionNearby) {
            damage *= 0.34; // fortification -66%
            player.sendActionBar(Component.text(
                "🛡 Structure fortifiée (-66% sans sbires)", NamedTextColor.GRAY));
        }

        // Plaques
        var tm = fr.lolmc.instance.InstanceHelper.turretManager(player);
        String structKey = structure.getType().name() + "_" + structure.getTeam() + "_" + structure.getLane();
        if (structure.getType() == Type.TURRET && tm.hasPlating(structKey)) {
            damage *= 0.60;
            tm.tickPlating(structKey);
            fr.lolmc.instance.InstanceHelper.rewardManager(player).onTurretHit(player, structure);
        }

        boolean phaseChanged = structure.takeDamage(damage);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_HIT, 0.5f, 1.2f);

        // Rune Demolish : 3 AA sur une tourelle consomment les stacks pour un dégât bonus (45s CD)
        if (structure.getType() == Type.TURRET && !structure.isDestroyed()) {
            var rm = LolPlugin.getInstance().getRuneManager();
            var pm = LolPlugin.getInstance().getPassiveManager();
            if (rm != null && pm != null && rm.getPage(player.getUniqueId()).has("demolish")) {
                var state = pm.getState(player);
                String structId = structure.getId();
                Long cd = state.demolishCooldown.get(structId);
                if (cd == null || System.currentTimeMillis() > cd) {
                    int stacks = state.demolishStacks.merge(structId, 1, Integer::sum);
                    if (stacks >= 3) {
                        state.demolishStacks.remove(structId);
                        state.demolishCooldown.put(structId, System.currentTimeMillis() + 45000L);
                        double demolishDmg = 100.0 + champ.getStats().getFinalMaxHP() * 0.20;
                        structure.takeDamage(demolishDmg);
                        player.sendActionBar(Component.text(
                                "💥 Démolition ! +" + (int) demolishDmg + " dégâts", NamedTextColor.GOLD));
                    }
                }
            }
        }

        if (structure.isDestroyed()) {
            onStructureDestroyed(structure, player);
        } else {
            if (phaseChanged) mapManager.updateStructurePhase(structure);
            player.sendActionBar(Component.text(String.format(
                "%s : %.0f/%.0f HP (%.0f%%)",
                structureName(structure), structure.getCurrentHP(),
                structure.getMaxHP(), structure.getHealthPercent()), NamedTextColor.YELLOW));
        }
    }

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
        // Turret Plating : -40% degats si plaques actives + or de plaque au frappeur
        var tm = fr.lolmc.instance.InstanceHelper.turretManager(player);
        String structKey = structure.getType().name() + "_" + structure.getTeam() + "_" + structure.getLane();
        if (structure.getType() == Type.TURRET && tm.hasPlating(structKey)) {
            damage *= 0.60;
            tm.tickPlating(structKey, player);
            // Or de plaque (avant 14min, max 5 par tour)
            fr.lolmc.instance.InstanceHelper.rewardManager(player).onTurretHit(player, structure);
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
        Team playerTeam = LolPlugin.getInstance().getTeamManager().getTeam(destroyer);

        // Inhibiteur détruit → super-sbires sur cette lane pour l'équipe adverse
        if (structure.getType() == Type.TURRET) {
            int turretIndex = structure.getIndex(); // 1=T1, 2=T2, 3=T3
            fr.lolmc.instance.InstanceHelper.rewardManager(destroyer).onTurretDestroyed(destroyer, playerTeam, turretIndex);
            fr.lolmc.instance.InstanceHelper.featManager(destroyer).claim(
                fr.lolmc.game.FeatManager.Feat.FIRST_TOWER, playerTeam, destroyer);
        } else if (structure.getType() == Type.INHIBITOR) {
            fr.lolmc.instance.InstanceHelper.minionManager(destroyer)
                    .enableSuperMinions(enemyTeam, structure.getLane());
            String inhKey = structure.getType().name() + "_" + enemyTeam.name() + "_" + structure.getLane();
            fr.lolmc.instance.InstanceHelper.gameManager(destroyer).onInhibitorDestroyed(inhKey);
            fr.lolmc.instance.InstanceHelper.announcementManager(destroyer).announceInhibitorDestroyed(
                    structure.getLane(), enemyTeam.name());
            fr.lolmc.instance.InstanceHelper.rewardManager(destroyer).onInhibitorDestroyed(destroyer, playerTeam);
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
        fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                c, Material.GRAY_STAINED_GLASS, 0.45f, 1.0, 14, 10L);
        c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);

        // ── Victoire si Nexus principal détruit ──
        if (structure.getType() == Type.NEXUS_BASE) {
            Team winner = enemyTeam.opposite();
            announceVictory(winner, destroyer);
        }
    }

    private void announceVictory(Team winner, Player destroyer) {
        Component msg = Component.text("🏆 VICTOIRE DE L'ÉQUIPE " + winner.name() + " ! 🏆",
                winner.chatColor);
        var inst = fr.lolmc.instance.InstanceHelper.instanceOf(destroyer);
        // En mode instances, n'annoncer qu'aux joueurs de CETTE partie (pas tout le serveur,
        // qui pourrait héberger d'autres parties en cours) ; sinon (mode partie unique), tout
        // le monde partagé.
        Iterable<Player> audience = inst != null
                ? inst.getOnlinePlayers() : LolPlugin.getInstance().getServer().getOnlinePlayers();
        for (Player p : audience) {
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
        fr.lolmc.instance.InstanceHelper.minionManager(destroyer).stopWaves();
        fr.lolmc.instance.InstanceHelper.jungleManager(destroyer).stopJungle();
        fr.lolmc.instance.InstanceHelper.gameManager(destroyer).stopGame();
        // Retour au lobby après 30s
        var mm  = LolPlugin.getInstance().getMatchmakingManager();
        var im  = LolPlugin.getInstance().getInstanceManager();
        // Trouver l'instance du joueur qui a détruit le Nexus
        var winnerInstance = (destroyer != null) ? im.getInstanceOf(destroyer) : null;
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
                        p.sendActionBar(Component.text(
                            "🏠 Retour dans " + countdown + "s",
                            NamedTextColor.YELLOW));
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
