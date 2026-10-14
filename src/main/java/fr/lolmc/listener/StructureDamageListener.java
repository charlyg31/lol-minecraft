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

    public StructureDamageListener(MapManager mapManager, ChampionManager championManager, TeamManager teamManager) {
        this.mapManager = mapManager;
        this.championManager = championManager;
        this.teamManager = teamManager;
    }

    /** Carte de la partie du joueur : celle de son instance, sinon la carte globale. */
    private MapManager map(Player player) {
        var inst = fr.lolmc.instance.InstanceHelper.instanceOf(player);
        return inst != null ? inst.getMapManager() : mapManager;
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

        // Protection anti-backdoor (tourelles suivantes, inhibiteur, base, Nexus)
        if (map(player).isInvulnerable(structure)) {
            player.sendActionBar(Component.text(
                "🛡 Structure protégée : détruis d'abord celle qui la précède !", NamedTextColor.RED));
            return;
        }

        BaseChampion champ = championManager.getChampion(player);
        double damage = champ.getStats().getFinalAD();

        voidgrubEffects(player, structure, playerTeam, champ.getAutoAttackRange() <= 3.0);

        var tm = fr.lolmc.instance.InstanceHelper.turretManager(player);
        if (structure.getType() != Type.TURRET) {
            // Inhibiteur / Nexus (wiki) : 100 % AD + 60 % AP, physique si l'AD bonus dépasse 60 %
            // de l'AP, sinon magique ; armure 20, RM 0, la pénétration n'a pas d'effet
            var st = champ.getStats();
            double ap = st.getFinalAP() * 0.60;
            damage += ap;
            boolean physical = st.getBonusAD() > ap;
            if (physical) damage *= fr.lolmc.util.MobCombat.resistMultiplier(20);
        }
        if (structure.getType() == Type.TURRET) {
            // Les tourelles prennent 20 % de dégâts en plus des champions de mêlée (LoL)
            if (champ.getAutoAttackRange() <= 3.0) damage *= 1.20;
            // Armure 60 (baisse 11:00-15:00 pour les extérieures, Rempart), pénétration comprise
            damage = champ.getStats().calcPhysicalDamageVs(damage, tm.getArmor(structure));
        }

        // Armure renforcée (LoL, tourelles) : -80 % de dégâts sans sbire de l'attaquant à proximité.
        // (L'ancienne « fortification » des 5 premières minutes n'existe plus sur la Faille.)
        boolean minionNearby = structure.getType() != Type.TURRET;
        for (var ent : structure.getCenter().getWorld()
                .getNearbyEntities(structure.getCenter(), 12, 8, 12)) {
            if (ent instanceof org.bukkit.entity.LivingEntity le
                    && fr.lolmc.game.MinionManager.isMinion(le)
                    && fr.lolmc.game.MinionManager.getMinionTeam(le) == playerTeam) {
                minionNearby = true; break;
            }
        }
        if (!minionNearby) {
            damage *= 0.20;
            player.sendActionBar(Component.text(
                "🛡 Armure renforcée (-80% sans sbires)", NamedTextColor.GRAY));
        } else if (structure.getType() == Type.TURRET) {
            // Croissance cristalline : vrais dégâts bonus (pas consommée sous Armure renforcée)
            double crystal = tm.consumeOvergrowth(structure, playerTeam);
            if (crystal > 0) {
                damage += crystal;
                player.sendActionBar(Component.text(
                    "💎 Croissance cristalline ! +" + (int) crystal + " vrais dégâts", NamedTextColor.LIGHT_PURPLE));
                fr.lolmc.util.VisualEffectUtil.impactBurst(structure.getCenter().getWorld(),
                    structure.getCenter().clone().add(0.5, 1.5, 0.5), Material.AMETHYST_BLOCK, 0.3f, 0.6, 10, 8L);
            }
        }

        // Plaques (patch 26.1 : elles ne réduisent plus les dégâts des sbires et des champions à
        // distance ; remplacé par +20 % de dégâts des champions de mêlée, appliqué plus haut)
        String structKey = fr.lolmc.game.TurretManager.platingKey(structure);
        boolean plated = fr.lolmc.game.TurretManager.hasPlatesTier(structure) && tm.hasPlating(structKey);

        double hpBefore = structure.getCurrentHP();
        boolean phaseChanged = structure.takeDamage(damage);
        if (structure.getType() == Type.TURRET) tm.onTurretDamaged(structure, hpBefore, playerTeam);
        if (plated) breakPlates(player, structure, structKey, tm);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_HIT, 0.5f, 1.2f);

        // Rune Démolition : la 3e attaque sur une tourelle inflige des dégâts bonus (30 s de délai ;
        // mêlée 85 + 28 % des PV max, distance 50 + 20 %, patch 26.1)
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
                        state.demolishCooldown.put(structId, System.currentTimeMillis() + 30000L);
                        boolean melee = champ.getAutoAttackRange() <= 3.0;
                        double demolishDmg = melee ? 85.0 + champ.getStats().getFinalMaxHP() * 0.28
                                                   : 50.0 + champ.getStats().getFinalMaxHP() * 0.20;
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
            if (phaseChanged) map(player).updateStructurePhase(structure);
            player.sendActionBar(Component.text(String.format(
                "%s : %.0f/%.0f HP (%.0f%%)",
                structureName(structure), structure.getCurrentHP(),
                structure.getMaxHP(), structure.getHealthPercent()), NamedTextColor.YELLOW));
        }
    }

    /** Brûlures du Toucher du Néant en cours, par structure et par joueur. */
    private final java.util.Map<String, Long> voidBurnUntil = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> voidmiteReady = new java.util.HashMap<>();

    /**
     * Larves du Néant (wiki, patch 26.11) : Toucher du Néant, chaque attaque brûle la structure
     * 4 s (8 ticks) pour 4/12/16 bruts par tick en mêlée, 2/6/8 à distance (1/2/3 larves) ; Faim du
     * Néant (3 larves) : 1 Voidmite allié toutes les 15 s en combat contre une structure.
     * Avant : +6 % / +14 % de dégâts aux tourelles.
     */
    private void voidgrubEffects(Player player, GameStructure structure, Team team, boolean melee) {
        var jm = fr.lolmc.instance.InstanceHelper.jungleManager(player);
        int grubs = jm != null ? Math.min(3, jm.getVoidgrubKills(team)) : 0;
        if (grubs <= 0) return;
        double perTick = melee ? new double[]{4, 12, 16}[grubs - 1] : new double[]{2, 6, 8}[grubs - 1];
        String key = player.getUniqueId() + ":" + structure.getId();
        boolean running = voidBurnUntil.getOrDefault(key, 0L) > System.currentTimeMillis();
        voidBurnUntil.put(key, System.currentTimeMillis() + 4000L); // rafraîchie à chaque attaque
        if (!running) new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (structure.isDestroyed() || voidBurnUntil.getOrDefault(key, 0L) <= System.currentTimeMillis()) {
                    voidBurnUntil.remove(key); cancel(); return;
                }
                boolean phase = structure.takeDamage(perTick);
                if (structure.isDestroyed()) {
                    if (player.isOnline()) onStructureDestroyed(structure, player);
                    else onStructureDestroyedByMinions(structure, structure.getCenter().getWorld());
                } else if (phase && player.isOnline()) map(player).updateStructurePhase(structure);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 10L, 10L);
        if (grubs >= 3 && voidmiteReady.getOrDefault(player.getUniqueId(), 0L) <= System.currentTimeMillis()) {
            voidmiteReady.put(player.getUniqueId(), System.currentTimeMillis() + 15000L);
            var mm = fr.lolmc.instance.InstanceHelper.minionManager(player);
            if (mm != null) mm.spawnVoidmite(player.getLocation(), team);
        }
    }

    /**
     * Structure abattue par des sbires (aucun champion n'a porté le coup final) : or d'équipe,
     * super-sbires après un inhibiteur, et fin de partie si c'est le Nexus principal.
     * Avant, rien ne se passait — un Nexus détruit par les sbires ne terminait jamais la partie.
     */
    public void onStructureDestroyedByMinions(GameStructure structure, org.bukkit.World world) {
        Team enemyTeam = structure.getTeam();
        Team attacking = enemyTeam.opposite();
        // Joueur de référence (équipe attaquante de cette partie) pour retrouver instance et managers
        Player ref = null;
        for (Player p : world.getPlayers()) {
            if (!championManager.hasChampion(p)) continue;
            if (teamManager.getTeam(p) == attacking) { ref = p; break; }
            if (ref == null) ref = p;
        }
        if (ref == null) return;
        if (structure.getType() == Type.TURRET) {
            fr.lolmc.instance.InstanceHelper.rewardManager(ref).onTurretDestroyed(null, attacking, structure);
            fr.lolmc.instance.InstanceHelper.featManager(ref).claim(
                fr.lolmc.game.FeatManager.Feat.FIRST_TOWER, attacking, ref);
        } else if (structure.getType() == Type.INHIBITOR) {
            fr.lolmc.instance.InstanceHelper.minionManager(ref).enableSuperMinions(enemyTeam, structure.getLane());
            String inhKey = structure.getType().name() + "_" + enemyTeam.name() + "_" + structure.getLane();
            fr.lolmc.instance.InstanceHelper.gameManager(ref).onInhibitorDestroyed(inhKey);
            fr.lolmc.instance.InstanceHelper.announcementManager(ref).announceInhibitorDestroyed(
                    structure.getLane(), enemyTeam.name());
            fr.lolmc.instance.InstanceHelper.rewardManager(ref).onInhibitorDestroyed(null, attacking);
        }
        fr.lolmc.util.WorldContext.broadcast(world, Component.text(
                "💥 " + structureName(structure) + " (" + enemyTeam.name() + ") détruit par les sbires !",
                NamedTextColor.GOLD));
        if (structure.getType() == Type.NEXUS_BASE) endMatch(attacking, ref);
    }

    /**
     * Plaques : elles tombent à 90 / 75 / 55 / 30 / 0 % des PV (patch 26.1) et rapportent leur or
     * aux champions proches. Avant, chaque coup retirait une plaque : 5 auto-attaques = 625 or.
     */
    private void breakPlates(Player player, GameStructure structure, String structKey,
                             fr.lolmc.game.TurretManager tm) {
        int shouldRemain = fr.lolmc.game.TurretManager.MAX_PLATING - fr.lolmc.game.TurretManager.platesFallen(structure);
        var rewards = fr.lolmc.instance.InstanceHelper.rewardManager(player);
        while (tm.hasPlating(structKey) && tm.getPlatingLeft(structKey) > shouldRemain) {
            tm.tickPlating(structKey);
            rewards.onPlateDestroyed(player, structure, tm.getPlatingLeft(structKey), tm.plateGold(structure));
        }
    }

    private void onStructureDestroyed(GameStructure structure, Player destroyer) {
        String name = structureName(structure);
        Team enemyTeam = structure.getTeam();
        Team playerTeam = LolPlugin.getInstance().getTeamManager().getTeam(destroyer);

        // Inhibiteur détruit → super-sbires sur cette lane pour l'équipe adverse
        if (structure.getType() == Type.TURRET) {
            fr.lolmc.instance.InstanceHelper.rewardManager(destroyer).onTurretDestroyed(destroyer, playerTeam, structure);
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
        fr.lolmc.util.WorldContext.broadcast(destroyer.getWorld(), Component.text(
                "💥 " + name + " (" + enemyTeam.name() + ") détruit par " + destroyer.getName() + "!",
                NamedTextColor.GOLD));

        // (l'or du last-hit est versé par RewardManager.onTurretDestroyed : avant, +250 en double)

        // Effet visuel de destruction
        Location c = structure.getCenter().clone().add(0.5, 1, 0.5);
        fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                c, Material.GRAY_STAINED_GLASS, 0.45f, 1.0, 14, 10L);
        c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);

        // ── Victoire si Nexus principal détruit (50 or au last-hit, wiki) ──
        if (structure.getType() == Type.NEXUS_BASE) {
            LolPlugin.getInstance().getGoldManager().addGold(destroyer.getUniqueId(), 50);
            Team winner = enemyTeam.opposite();
            endMatch(winner, destroyer);
        }
    }

    /**
     * Fin de partie (Nexus détruit ou abandon) : annonce, tableau de score, arrêt des systèmes,
     * puis retour des joueurs (serveur d'origine via BungeeCord, sinon position d'avant-partie)
     * et fermeture de l'instance après 30 s.
     *
     * @param ref un joueur de la partie (sert à retrouver l'instance et ses managers)
     */
    public void endMatch(Team winner, Player ref) {
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(ref);
        if (!gm.isGameRunning()) return; // fin déjà traitée (double déclenchement)

        Component msg = Component.text("🏆 VICTOIRE DE L'ÉQUIPE " + winner.name() + " ! 🏆",
                winner.chatColor);
        var inst = fr.lolmc.instance.InstanceHelper.instanceOf(ref);
        // En mode instances, n'annoncer qu'aux joueurs de CETTE partie (pas tout le serveur,
        // qui pourrait héberger d'autres parties en cours) ; sinon, aux joueurs du monde de jeu.
        java.util.List<Player> group = new java.util.ArrayList<>(inst != null
                ? inst.getOnlinePlayers() : fr.lolmc.util.WorldContext.getGamePlayers());
        for (Player p : group) {
            p.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("VICTOIRE " + winner.name(), winner.chatColor),
                    Component.empty(),
                    net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(500),
                        java.time.Duration.ofMillis(4000),
                        java.time.Duration.ofMillis(1000))));
            p.sendMessage(msg);
        }
        final java.util.List<java.util.UUID> groupIds = new java.util.ArrayList<>();
        for (Player gp : group) groupIds.add(gp.getUniqueId());
        // Tableau de score de fin de partie (et persistance des résultats)
        LolPlugin.getInstance().getMatchScoreboard().showEndScreen(winner, group, gm);
        // Arrêter la partie
        fr.lolmc.instance.InstanceHelper.minionManager(ref).stopWaves();
        fr.lolmc.instance.InstanceHelper.jungleManager(ref).stopJungle();
        gm.stopGame();
        // Retour au lobby après 30s
        var mm  = LolPlugin.getInstance().getMatchmakingManager();
        var im  = LolPlugin.getInstance().getInstanceManager();
        new org.bukkit.scheduler.BukkitRunnable() {
            int countdown = 30;
            @Override public void run() {
                if (countdown <= 0) {
                    cancel();
                    returnPlayers(groupIds, mm);
                    // Mode partie unique : nettoyer toutes les positions mémorisées
                    if (mm != null && inst == null) mm.returnAllToPreGameLocations();
                    // Fermer l'instance (laisse le temps aux transferts BungeeCord)
                    if (inst != null) im.closeInstance(inst, 100L);
                    return;
                }
                if (countdown <= 10 || countdown % 10 == 0) {
                    for (java.util.UUID gid : groupIds) {
                        Player p = org.bukkit.Bukkit.getPlayer(gid);
                        if (p == null) continue;
                        p.sendActionBar(Component.text(
                            "🏠 Retour dans " + countdown + "s",
                            NamedTextColor.YELLOW));
                    }
                }
                countdown--;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
    }

    /**
     * Renvoie les joueurs de la partie : via le proxy (LolMC-Bungee) vers leur serveur d'origine,
     * puis, pour ceux encore là 3 s plus tard, vers l'origine connue ou le lobby. Sans BungeeCord :
     * retour à la position d'avant-partie.
     */
    private void returnPlayers(java.util.List<java.util.UUID> groupIds, fr.lolmc.matchmaking.MatchmakingManager mm) {
        var bridge = LolPlugin.getInstance().getBridgeManager();
        if (bridge != null && bridge.isEnabled()) {
            bridge.requestReturnToOrigin(groupIds);
            org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
                for (java.util.UUID gid : groupIds) {
                    Player p = org.bukkit.Bukkit.getPlayer(gid);
                    if (p == null || !p.isOnline()) continue;
                    if (bridge.getOriginServer(gid) != null) bridge.sendPlayerToOrigin(p);
                    else bridge.sendPlayerToLobby(p);
                }
            }, 60L);
            return;
        }
        for (java.util.UUID gid : groupIds) {
            Player p = org.bukkit.Bukkit.getPlayer(gid);
            if (p != null && p.isOnline() && mm != null) mm.returnPlayerToPreGameLocation(p);
        }
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
