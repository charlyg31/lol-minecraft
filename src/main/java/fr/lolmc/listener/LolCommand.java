package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import org.jetbrains.annotations.NotNull;
import fr.lolmc.game.GameStructure.Type;
import fr.lolmc.game.MapManager;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.*;

/**
 * Commande admin /lola pour configurer la carte.
 *   /lola set turret <top|mid|bot|base> <index>  → puis clic sur la case centrale
 *   /lola set nexus <top|mid|bot> <index>         → puis clic
 *   /lola set basenexus                           → puis clic (nexus principal)
 *   /lola position <blue|red> <1-5>               → puis clic au sol (spawn)
 *   /lola lane <top|mid|bot>                       → clics successifs = waypoints, /lola lane done
 *   /lola schem <pos1|pos2|save>                  → définition et sauvegarde de schématique d'ancre
 *   /lola start                                    → lance la partie (reset structures)
 *   /lola stop                                     → arrête la partie
 */
public class LolCommand implements CommandExecutor, TabCompleter, Listener {

    private final MapManager mapManager;
    private final fr.lolmc.game.RoadManager roadManager;

    // Mode setup en attente d'un clic, par joueur
    private final Map<UUID, PendingSetup> pending = new HashMap<>();
    private final Map<UUID, fr.lolmc.game.GameStructure.TurretTier> pendingTier = new HashMap<>();

    // AJOUT : Sélections locales des coins de schématiques par joueur
    private final Map<UUID, Location[]> schematicSelections = new HashMap<>();

    private record PendingSetup(String kind, Type type, Team team, String lane, int index, int position) {}

    public LolCommand(MapManager mapManager, fr.lolmc.game.RoadManager roadManager) {
        this.mapManager = mapManager;
        this.roadManager = roadManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§cJoueur uniquement."); return true; }

        // /lola → commandes joueur (uniquement ce qui n'est pas géré par BungeeCord)
        if (cmd.getName().equalsIgnoreCase("l")) {
            String sub = args.length > 0 ? args[0].toLowerCase() : "help";
            boolean inGame = fr.lolmc.instance.InstanceHelper.gameManager(player).isGameRunning()
                    && LolPlugin.getInstance().getChampionManager().hasChampion(player);
            String playerPerm = playerPermission(sub);
            if (playerPerm != null && !player.hasPermission(playerPerm)) {
                player.sendMessage(Component.text("❌ Permission requise : " + playerPerm, NamedTextColor.RED));
                return true;
            }

            switch (sub) {
                case "runes" ->
                        LolPlugin.getInstance().getRuneGUI().open(player);
                case "ping" -> {
                    if (!inGame) { player.sendMessage(Component.text("❌ Disponible uniquement en partie.", NamedTextColor.RED)); break; }
                    String ptype = args.length > 1 ? args[1].toLowerCase() : "danger";
                    var type = parsePingType(ptype);
                    fr.lolmc.instance.InstanceHelper.announcementManager(player).sendPing(player, type);
                }
                case "ff", "forfait" -> {
                    if (!inGame) { player.sendMessage(Component.text("❌ Disponible uniquement en partie.", NamedTextColor.RED)); break; }
                    LolPlugin.getInstance().getForfeitManager().vote(player);
                }
                case "stats" -> {
                    if (!inGame) { player.sendMessage(Component.text("❌ Disponible uniquement en partie.", NamedTextColor.RED)); break; }
                    var msb = LolPlugin.getInstance().getMatchScoreboard();
                    var st = msb != null ? msb.getStats().get(player.getUniqueId()) : null;
                    if (st == null) {
                        player.sendMessage(Component.text("Aucune stat disponible.", NamedTextColor.GRAY));
                    } else {
                        player.sendMessage(Component.text(String.format(
                                        "§eStats: %d/%d/%d | CS %d | Or %d | DMG %,d",
                                        st.kills, st.deaths, st.assists, st.cs, st.gold, st.damageDealt),
                                NamedTextColor.GOLD));
                    }
                }
                case "shop" -> LolPlugin.getInstance().getShopListener().openShop(player);
                case "recap", "recapitulatif" -> {
                    if (!LolPlugin.getInstance().getEndGameRecap().open(player))
                        player.sendMessage(Component.text("❌ Aucun récapitulatif de fin de partie en cours.", NamedTextColor.RED));
                }
                case "honor", "honorer", "honneur" -> {
                    if (args.length < 2) {
                        player.sendMessage(Component.text("§e/l honor <pseudo> §7— Honorer un allié en fin de partie"));
                        break;
                    }
                    LolPlugin.getInstance().getEndGameRecap().honor(player, args[1]);
                }
                case "grimoire", "spellbook" -> {
                    if (args.length < 3) {
                        player.sendMessage(Component.text("§e/l grimoire <sort possédé> <nouveau sort> §7— Grimoire déchaîné "
                                + "(flash, ignite, heal, barrier, exhaust, teleport, smite, cleanse, ghost)"));
                        break;
                    }
                    LolPlugin.getInstance().getRuneManager().spellbookSwap(player, args[1], args[2]);
                }
                default -> {
                    player.sendMessage(Component.text("§6=== Commandes /l ===", NamedTextColor.GOLD));
                    player.sendMessage(Component.text("§e/l runes §7— Configurer ses runes"));
                    player.sendMessage(Component.text("§e/l ping <danger|omw|miss|assist|enemy> §7— Ping équipe"));
                    player.sendMessage(Component.text("§e/l ff §7— Vote d'abandon"));
                    player.sendMessage(Component.text("§e/l stats §7— Statistiques de la partie"));
                    player.sendMessage(Component.text("§e/l shop §7— Ouvrir la boutique"));
                    player.sendMessage(Component.text("§e/l recap §7— Récapitulatif de fin de partie"));
                    player.sendMessage(Component.text("§e/l honor <pseudo> §7— Honorer un allié en fin de partie"));
                    player.sendMessage(Component.text("§e/l grimoire <sort> <nouveau> §7— Échanger un sort (Grimoire déchaîné)"));
                    player.sendMessage(Component.text("§7File d'attente, groupes et rôles : §e/lol <top|mid|adc|jungle|support|all>", NamedTextColor.GRAY));
                }
            }
            return true;
        }

        // /lola → commandes admin uniquement
        if (!hasAnyAdmin(player)) {
            player.sendMessage(Component.text("❌ Permission requise.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) { handleAdminHelp(player); return true; }

        // Permission par sous-commande : lolmc.admin.<commande> (ex. lolmc.admin.join), donnée aussi
        // par son groupe (lolmc.admin.config / .test / .game) et par lolmc.admin.* (tout)
        String adminSub = canonicalAdminSub(args[0].toLowerCase());
        if (ADMIN_GROUPS.containsKey(adminSub) && !canUseAdmin(player, adminSub)) {
            player.sendMessage(Component.text("❌ Permission requise : lolmc.admin." + adminSub
                    + " (ou lolmc.admin." + ADMIN_GROUPS.get(adminSub) + ", lolmc.admin.*)", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set" -> handleSet(player, args);
            case "position" -> handlePosition(player, args);
            case "center" -> {
                var loc = player.getLocation();
                var cfg = LolPlugin.getInstance().getConfig();
                cfg.set("minimap.center-x", loc.getBlockX());
                cfg.set("minimap.center-z", loc.getBlockZ());
                LolPlugin.getInstance().saveConfig();
                // Réinitialiser la vue partagée pour forcer un re-rendu
                LolPlugin.getInstance().getMinimapManager().resetView();
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        String.format("✔ Centre de la minimap défini à X=%d Z=%d (reçu les changements)",
                                loc.getBlockX(), loc.getBlockZ()),
                        net.kyori.adventure.text.format.NamedTextColor.GREEN));
            }
            case "schem" -> handleSchem(player, args);
            case "road" -> handleRoad(player, args);
            case "jungle" -> handleJungle(player, args);
            case "plant" -> handlePlant(player, args);
            case "baronpit" -> handleBaronPit(player, args);
            case "shopnpc" -> handleShopNpc(player, args);
            case "mode" -> handleMode(player, args);
            case "solo" -> handleSolo(player, args);
            case "give" -> handleGive(player, args);
            case "level" -> handleLevel(player, args);
            case "gold" -> handleGold(player, args);
            case "team" -> handleTeamCmd(player, args);
            case "testgame" -> handleTestGame(player);
            case "rejoindre", "join" -> fr.lolmc.game.JoinInProgress.join(player,
                    args.length > 1 ? args[1] : null, args.length > 2 ? args[2] : null);
            case "debug" -> handleDebug(player, args);
            case "spawn"   -> handleSpawn(player, args);
            case "buff"    -> handleBuff(player, args);
            case "resetcd" -> handleResetCd(player, args);
            case "hp"      -> handleHp(player, args);
            case "wave"    -> handleWave(player);
            case "instance", "instances" -> handleInstance(player, args);
            case "template" -> handleTemplate(player, args);
            case "help"    -> handleAdminHelp(player);
            case "reload" -> {
                LolPlugin.getInstance().reloadConfig();
                fr.lolmc.util.Balance.load();
                // Recharger la portée du fog of war (manager global + toutes les instances actives)
                double visionRange = LolPlugin.getInstance().getConfig().getDouble("fog.vision-range", 30.0);
                var fog = LolPlugin.getInstance().getFogOfWarManager();
                if (fog != null) fog.setVisionRange(visionRange);
                var instMgr = LolPlugin.getInstance().getInstanceManager();
                if (instMgr != null) {
                    for (var inst : instMgr.getAllInstances()) {
                        var instFog = inst.getFogManager();
                        if (instFog != null) instFog.setVisionRange(visionRange);
                    }
                }
                // Recharger les skins
                LolPlugin.getInstance().getSkinManager().reload();
                player.sendMessage(Component.text("✔ Config, équilibrage, fog et skins rechargés.", NamedTextColor.GREEN));
            }
            case "permcheck" -> {
                // Diagnostic : les permissions de skins déclarées dans le code
                // (SkinRegistry) sont-elles bien présentes dans plugin.yml ?
                var declared = fr.lolmc.champion.skin.SkinRegistry.getAllPermissions();
                var pm = LolPlugin.getInstance().getServer().getPluginManager();
                int missing = 0;
                for (String perm : declared) {
                    if (pm.getPermission(perm) == null) {
                        player.sendMessage(Component.text("⚠ Permission absente de plugin.yml : " + perm,
                                NamedTextColor.RED));
                        missing++;
                    }
                }
                player.sendMessage(Component.text(
                        "✔ Vérification terminée : " + declared.size() + " permission(s) de skin dans le code, "
                                + missing + " manquante(s) dans plugin.yml.",
                        missing == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "select" -> {
                var ids = new java.util.ArrayList<java.util.UUID>();
                for (var pl : LolPlugin.getInstance().getServer().getOnlinePlayers()) {
                    ids.add(pl.getUniqueId());
                }
                LolPlugin.getInstance().getChampSelectManager().startSelection(ids);
                player.sendMessage(Component.text("✔ Sélection lancée pour " + ids.size() + " joueurs.", NamedTextColor.GREEN));
            }
            case "start" -> {
                player.sendMessage(Component.text("⚔ Lancement de la partie...", NamedTextColor.GOLD));
                fr.lolmc.instance.InstanceHelper.mapManager(player).resetAllStructures();
                fr.lolmc.instance.InstanceHelper.minionManager(player).startWaves();
                fr.lolmc.instance.InstanceHelper.jungleManager(player).startJungle();
                spawnPlantsFor(player);
                fr.lolmc.instance.InstanceHelper.gameManager(player).startGame();
                LolPlugin.getInstance().getMatchScoreboard().startMatch(
                        LolPlugin.getInstance().getMatchScoreboard().isRanked(), contextIds(player));
                player.sendMessage(Component.text("✔ Partie lancée (structures, sbires, jungle, timer)!", NamedTextColor.GREEN));
            }
            case "stop" -> {
                LolPlugin.getInstance().getMinionManager().stopWaves();
                LolPlugin.getInstance().getJungleManager().stopJungle();
                fr.lolmc.instance.InstanceHelper.minionManager(player).stopWaves();
                fr.lolmc.instance.InstanceHelper.jungleManager(player).stopJungle();
                clearPlantsFor(player);
                fr.lolmc.instance.InstanceHelper.gameManager(player).stopGame();
                // Instance de partie : fermeture immédiate (joueurs renvoyés au spawn, monde supprimé)
                var stopInst = fr.lolmc.instance.InstanceHelper.instanceOf(player);
                if (stopInst != null) {
                    LolPlugin.getInstance().getInstanceManager().closeInstance(stopInst, 20L);
                    player.sendMessage(Component.text("⏹ Partie arrêtée — instance #" + stopInst.getId()
                            + " fermée et supprimée.", NamedTextColor.YELLOW));
                    return true;
                }
                player.sendMessage(Component.text("⏹ Partie arrêtée.", NamedTextColor.YELLOW));
            }
            default -> handleAdminHelp(player);
        }
        return true;
    }

    // ── /lola schem ────────────────────────────────────────────────

    private void handleSchem(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /lola schem <pos1|pos2|save> ...");
            return;
        }

        String subCommand = args[1].toLowerCase();
        UUID uuid = player.getUniqueId();

        switch (subCommand) {
            case "pos1" -> {
                Location loc = player.getLocation().getBlock().getLocation();
                Location[] sel = schematicSelections.computeIfAbsent(uuid, _ -> new Location[2]);
                sel[0] = loc;
                player.sendMessage(Component.text("✔ Position 1 définie à tes pieds : " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(), NamedTextColor.GREEN));
            }
            case "pos2" -> {
                Location loc = player.getLocation().getBlock().getLocation();
                Location[] sel = schematicSelections.computeIfAbsent(uuid, _ -> new Location[2]);
                sel[1] = loc;
                player.sendMessage(Component.text("✔ Position 2 définie à tes pieds : " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(), NamedTextColor.GREEN));
            }
            case "save" -> {
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /lola schem save <nom_de_la_schematic>");
                    return;
                }

                Location[] sel = schematicSelections.get(uuid);
                if (sel == null || sel[0] == null || sel[1] == null) {
                    player.sendMessage(Component.text("❌ Erreur : Tu dois d'abord définir pos1 et pos2 !", NamedTextColor.RED));
                    return;
                }

                if (!sel[0].getWorld().equals(sel[1].getWorld())) {
                    player.sendMessage(Component.text("❌ Erreur : Les deux coins doivent être dans le même monde !", NamedTextColor.RED));
                    return;
                }

                String name = args[2];
                player.sendMessage(Component.text("⏳ Analyse de la zone et recherche du FOUR d'ancrage pour '" + name + "'...", NamedTextColor.YELLOW));

                mapManager.getSchematics().saveSchematicWithAnchor(name, sel[0], sel[1]);

                player.sendMessage(Component.text("✔ Sauvegarde de la schématique exécutée !", NamedTextColor.GREEN));
            }
            default -> player.sendMessage("§cAction inconnue. Utilise /lola schem <pos1|pos2|save>");
        }
    }

    // ── /lola set ──────────────────────────────────────────────────

    private void handleSet(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lola set <turret|nexus|basenexus> ..."); return; }
        String what = args[1].toLowerCase();

        if (what.equals("basenexus")) {
            if (args.length < 3) { player.sendMessage("§cUsage: /lola set basenexus <blue|red>"); return; }
            Team team = parseTeam(args[2]);
            if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
            pending.put(player.getUniqueId(), new PendingSetup("structure", Type.NEXUS_BASE, team, "base", 1, 0));
            player.sendMessage(Component.text("👉 Clique sur la case centrale du Nexus principal ("
                    + team.name() + ").", NamedTextColor.AQUA));
            return;
        }

        if (args.length < 5) {
            player.sendMessage("§cUsage: /lola set " + what + " <blue|red> <top|mid|bot|base> <index>");
            return;
        }
        Type type = switch (what) {
            case "turret" -> Type.TURRET;
            case "inhibitor" -> Type.INHIBITOR;
            case "nexus" -> Type.NEXUS;
            default -> null;
        };
        if (type == null) {
            player.sendMessage("§cType inconnu : " + what + " (attendu: turret, inhibitor ou nexus)");
            return;
        }
        Team team = parseTeam(args[2]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        String lane = args[3].toLowerCase();
        int index;
        try { index = Integer.parseInt(args[4]); }
        catch (NumberFormatException e) { player.sendMessage("§cIndex invalide."); return; }

        // Par défaut : niveau déduit de la lane et du rang (avant : toujours « extérieure »)
        fr.lolmc.game.GameStructure.TurretTier tier = fr.lolmc.game.GameStructure.TurretTier.fromPosition(lane, index);
        if (type == Type.TURRET && args.length >= 6) {
            try {
                tier = fr.lolmc.game.GameStructure.TurretTier.valueOf(args[5].toUpperCase());
            } catch (IllegalArgumentException ex) {
                player.sendMessage("§cNiveau invalide. Utilise: outer, inner, inhibitor, nexus");
                return;
            }
        }
        pendingTier.put(player.getUniqueId(), tier);
        pending.put(player.getUniqueId(), new PendingSetup("structure", type, team, lane, index, 0));
        player.sendMessage(Component.text(String.format(
                "👉 Clique sur la case centrale de %s %s %s #%d.",
                type.name().toLowerCase(), team.name(), lane, index), NamedTextColor.AQUA));
    }

    // ── /lola position ─────────────────────────────────────────────

    private void handlePosition(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage("§cUsage: /lola position <blue|red> <1-5>"); return; }
        Team team = parseTeam(args[1]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        int pos;
        try { pos = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { player.sendMessage("§cPosition invalide (1-5)."); return; }
        if (pos < 1 || pos > 5) { player.sendMessage("§cPosition entre 1 et 5."); return; }

        pending.put(player.getUniqueId(), new PendingSetup("spawn", null, team, null, 0, pos));
        player.sendMessage(Component.text(String.format(
                "👉 Clique au sol pour définir le spawn %s #%d.", team.name(), pos), NamedTextColor.AQUA));
    }

    // ── /lola debug ────────────────────────────────────────────────

    private void handleDebug(Player player, String[] args) {
        var plugin = LolPlugin.getInstance();
        var cm = plugin.getChampionManager();

        if (args.length >= 2) {
            String sub = args[1].toLowerCase();
            switch (sub) {
                case "on" -> {
                    fr.lolmc.util.DebugLogger.setEnabled(true);
                    fr.lolmc.listener.AbilityListener.DEBUG = true;
                    player.sendMessage(Component.text("✔ Débug ACTIVÉ. Log: " + fr.lolmc.util.DebugLogger.getPath(), NamedTextColor.GREEN));
                    return;
                }
                case "off" -> {
                    fr.lolmc.util.DebugLogger.setEnabled(false);
                    fr.lolmc.listener.AbilityListener.DEBUG = false;
                    player.sendMessage(Component.text("✔ Débug DÉSACTIVÉ.", NamedTextColor.YELLOW));
                    return;
                }
                case "clear" -> {
                    fr.lolmc.util.DebugLogger.clear();
                    player.sendMessage(Component.text("✔ Fichier debug.log vidé.", NamedTextColor.GREEN));
                    return;
                }
                case "state" -> {
                    player.sendMessage(Component.text("Débug: " + (fr.lolmc.util.DebugLogger.isEnabled() ? "ACTIF" : "inactif") + " | Fichier: " + fr.lolmc.util.DebugLogger.getPath(), NamedTextColor.AQUA));
                    return;
                }
                default -> {
                    player.sendMessage(Component.text("Usage: /lola debug [on|off|clear|state] — sans argument: rapport d'état du joueur", NamedTextColor.GRAY));
                    return;
                }
            }
        }

        player.sendMessage(Component.text("══════ DEBUG ══════", NamedTextColor.YELLOW));
        boolean hasChamp = cm.hasChampion(player);
        player.sendMessage(Component.text("Champion: " + (hasChamp ? "OUI" : "NON"), hasChamp ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (hasChamp) {
            var champ = cm.getChampion(player);
            player.sendMessage(Component.text("  Nom: " + champ.getDisplayName(), NamedTextColor.GRAY));
            var ls = champ.getLevelSystem();
            player.sendMessage(Component.text("  Niveau: " + ls.getLevel(), NamedTextColor.GRAY));
            String ranks = "  Sorts Q/W/E/R: " + ls.getAbilityRank(1) + "/" + ls.getAbilityRank(2) + "/" + ls.getAbilityRank(3) + "/" + ls.getAbilityRank(4);
            player.sendMessage(Component.text(ranks, NamedTextColor.GRAY));
            for (int s = 1; s <= 4; s++) {
                var ab = champ.getAbility(s);
                String slotName = switch(s) { case 1->"Q"; case 2->"W"; case 3->"E"; default->"R"; };
                boolean unlocked = ls.isAbilityUnlocked(s);
                player.sendMessage(Component.text("    " + slotName + " (" + (ab!=null?ab.getName():"null") + "): " + (unlocked ? "débloqué" : "VERROUILLÉ"), unlocked ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            var st = champ.getStats();
            player.sendMessage(Component.text(String.format("  PV: %.0f/%.0f | AD: %.0f | AP: %.0f", champ.getHPSystem().getCurrentHP(), champ.getHPSystem().getMaxHP(), st.getFinalAD(), st.getFinalAP()), NamedTextColor.GRAY));
        }

        int gold = plugin.getGoldManager().getGold(player.getUniqueId());
        player.sendMessage(Component.text("Or: " + gold, NamedTextColor.GOLD));

        var team = plugin.getTeamManager().getTeam(player);
        player.sendMessage(Component.text("Équipe: " + (team != null ? team.name() : "AUCUNE"), team != null ? NamedTextColor.AQUA : NamedTextColor.RED));

        int page = plugin.getHotbarManager().getPage(player);
        player.sendMessage(Component.text("Page hotbar: " + page, NamedTextColor.GRAY));

        int heldSlot = player.getInventory().getHeldItemSlot();
        var held = player.getInventory().getItem(heldSlot);
        String type = fr.lolmc.item.HotbarManager.getType(held);
        String id = fr.lolmc.item.HotbarManager.getId(held);
        player.sendMessage(Component.text("Slot tenu: " + heldSlot + " | type=" + type + " | id=" + id, NamedTextColor.GRAY));

        boolean running = fr.lolmc.instance.InstanceHelper.gameManager(player).isRunning();
        player.sendMessage(Component.text("Partie active: " + running, NamedTextColor.GRAY));

        if (team != null) {
            var spawn = fr.lolmc.instance.InstanceHelper.mapManager(player).getBaseSpawn(team);
            player.sendMessage(Component.text("Spawn équipe défini: " + (spawn != null), spawn != null ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        player.sendMessage(Component.text("═══════════════════", NamedTextColor.YELLOW));
    }

// ── /lola solo ─────────────────────────────────────────────────
private void handleSolo(Player player, String[] args) {
    String champId = args.length >= 2 ? args[1].toLowerCase() : "garen";
    var plugin = LolPlugin.getInstance();
    // Contexte de l'admin : son instance s'il en est dans une, sinon le monde de test (template)
    var gm  = fr.lolmc.instance.InstanceHelper.gameManager(player);
    var mm  = fr.lolmc.instance.InstanceHelper.minionManager(player);
    var jm  = fr.lolmc.instance.InstanceHelper.jungleManager(player);
    var map = fr.lolmc.instance.InstanceHelper.mapManager(player);
    boolean inInstance = fr.lolmc.instance.InstanceHelper.instanceOf(player) != null;

    // Repart de zéro : arrête l'ancienne partie et purge les sbires orphelins (de CE monde seulement)
    mm.stopWaves();
    jm.stopJungle();
    if (gm.isRunning()) gm.stopGame();
    gm.stopSystems();
    var soloWorld = inInstance ? player.getWorld() : fr.lolmc.util.WorldContext.getGameWorld();
    if (soloWorld != null) fr.lolmc.game.MinionManager.purgeWorld(soloWorld);

    plugin.getTeamManager().setTeam(player, fr.lolmc.team.TeamManager.Team.BLUE);
    plugin.getChampionManager().assignChampion(player, champId);
    var soloChamp = plugin.getChampionManager().getChampion(player);
    if (soloChamp != null) {
        soloChamp.getLevelSystem().setLevel(18);
        soloChamp.onLevelChanged(18);
        soloChamp.getLevelSystem().maxOutAbilities(); soloChamp.onAbilityRanksChanged();
        plugin.getHotbarManager().renderPage(player, soloChamp);
    }
    plugin.getRuneManager().applyRuneStats(player);
    plugin.getGoldManager().addGold(player.getUniqueId(), 20000);
    map.resetAllStructures();
    mm.startWaves();
    jm.startJungle();
    spawnPlantsFor(player);
    gm.startGame();
    plugin.getMatchScoreboard().startMatch(false, java.util.Set.of(player.getUniqueId()));
    var spawn = map.getBaseSpawn(fr.lolmc.team.TeamManager.Team.BLUE);
    if (spawn != null) player.teleport(spawn);

    player.sendMessage(Component.text("🧪 MODE SOLO lancé!" + (inInstance ? " (instance #"
            + fr.lolmc.instance.InstanceHelper.instanceOf(player).getId() + ")" : ""), NamedTextColor.GREEN));
    player.sendMessage(Component.text("Équipe BLEUE • Champion: " + champId, NamedTextColor.AQUA));
}

    private void handleGive(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lola give <champion>"); return; }
        String champId = args[1].toLowerCase();
        LolPlugin.getInstance().getChampionManager().assignChampion(player, champId);
        LolPlugin.getInstance().getRuneManager().applyRuneStats(player);
        player.sendMessage(Component.text("✔ Champion: " + champId, NamedTextColor.GREEN));
    }

    private void handleLevel(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lola level <1-18>"); return; }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) { player.sendMessage("§cTu n'as pas de champion."); return; }
        int lvl;
        try { lvl = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) { player.sendMessage("§cNiveau invalide."); return; }
        lvl = Math.clamp(lvl, 1, 18);
        var champ = cm.getChampion(player);
        champ.getLevelSystem().setLevel(lvl);
        champ.getLevelSystem().maxOutAbilities(); champ.onAbilityRanksChanged();
        champ.onLevelChanged(lvl);
        LolPlugin.getInstance().getHotbarManager().renderPage(player, champ);
        player.sendMessage(Component.text("✔ Niveau " + lvl + " — sorts débloqués", NamedTextColor.GREEN));
    }

    private void handleGold(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lola gold <montant>"); return; }
        int amount;
        try { amount = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) { player.sendMessage("§cMontant invalide."); return; }
        LolPlugin.getInstance().getGoldManager().addGold(player.getUniqueId(), amount);
        player.sendMessage(Component.text("✔ +" + amount + " or", NamedTextColor.GOLD));
    }

    private void handleTeamCmd(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lola team <blue/red>"); return; }
        var team = parseTeam(args[1]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        LolPlugin.getInstance().getTeamManager().setTeam(player, team);
        var spawn = fr.lolmc.instance.InstanceHelper.mapManager(player).getBaseSpawn(team);
        if (spawn != null) player.teleport(spawn);
        player.sendMessage(Component.text("✔ Équipe: " + team.name(), NamedTextColor.GREEN));
    }

    private void handleTestGame(Player player) {
        var plugin = LolPlugin.getInstance();
        fr.lolmc.instance.InstanceHelper.mapManager(player).resetAllStructures();
        fr.lolmc.instance.InstanceHelper.minionManager(player).startWaves();
        fr.lolmc.instance.InstanceHelper.jungleManager(player).startJungle();
        spawnPlantsFor(player);
        fr.lolmc.instance.InstanceHelper.gameManager(player).startGame();
        plugin.getMatchScoreboard().startMatch(false, contextIds(player));
        player.sendMessage(Component.text("🧪 Map lancée (structures, sbires, jungle, timer).", NamedTextColor.GREEN));
    }

    private void handleShopNpc(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lola shopnpc <blue|red>"); return; }
        Team team = parseTeam(args[1]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        LolPlugin.getInstance().getShopNpcManager().spawnShopNpc(player.getLocation(), team);
        player.sendMessage(Component.text("✔ PNJ boutique " + team.name() + " créé à ta position.", NamedTextColor.GREEN));
    }

    private void handleMode(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lola mode <ranked|normal>"); return; }
        boolean ranked = args[1].equalsIgnoreCase("ranked") || args[1].equalsIgnoreCase("classe");
        LolPlugin.getInstance().getMatchScoreboard().startMatch(ranked, contextIds(player));
        player.sendMessage(Component.text("✔ Mode de partie : " + (ranked ? "CLASSÉ" : "AMICAL"), NamedTextColor.GREEN));
    }

    private void handleJungle(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /lola jungle <type> [blue|red]");
            return;
        }
        fr.lolmc.game.JungleManager.MonsterType type;
        try {
            type = fr.lolmc.game.JungleManager.MonsterType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text("❌ Type de monstre inconnu: " + args[1], NamedTextColor.RED));
            return;
        }

        boolean isEpic = type.isEpic() || type == fr.lolmc.game.JungleManager.MonsterType.SCUTTLE_CRAB
                || type == fr.lolmc.game.JungleManager.MonsterType.VOIDGRUB; // fosse neutre
        Team team = null;
        // Deux crabes dans LoL : rivière du haut et du bas (index 1 = top, 2 = bot)
        int river = 0;
        if (type == fr.lolmc.game.JungleManager.MonsterType.SCUTTLE_CRAB) {
            String r = args.length >= 3 ? args[2].toLowerCase() : "";
            river = switch (r) { case "top", "haut" -> 1; case "bot", "bas" -> 2; default -> 0; };
            if (river == 0) {
                player.sendMessage("§cUsage: /lola jungle scuttle_crab <top|bot>");
                return;
            }
        }
        if (!isEpic) {
            if (args.length < 3) {
                player.sendMessage("§cCe camp nécessite une équipe: /lola jungle " + args[1] + " <blue|red>");
                return;
            }
            team = parseTeam(args[2]);
            if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        }

        pending.put(player.getUniqueId(), new PendingSetup("jungle", null, team, args[1].toUpperCase(), river, 0));
        String where = team != null ? " (" + team.name() + ")" : river == 1 ? " (rivière haute)" : river == 2 ? " (rivière basse)" : " (neutre)";
        player.sendMessage(Component.text(String.format("👉 Clique au sol pour placer %s%s.", type.displayName, where), NamedTextColor.AQUA));
    }

    /**
     * /lola baronpit <territorial|omniscient> <start|save|preview|clear>, /lola baronpit reset :
     * enregistre le terrain de la fosse propre à une forme du Baron (sélection de /lola schem).
     */
    /**
     * /lola plant <blastcone|honeyfruit|scryer> [inner|outer|late] : pose un emplacement de plante
     * à tes pieds ; /lola plant list | remove | clear.
     */
    private void handlePlant(Player player, String[] args) {
        var pm = LolPlugin.getInstance().getPlantManager();
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "list" -> {
                var spots = pm.getSpots();
                player.sendMessage(Component.text("🌱 " + spots.size() + " emplacement(s) de plante :", NamedTextColor.GOLD));
                for (var s : spots) {
                    var l = s.location();
                    player.sendMessage(Component.text(String.format("§e%s §7%s (%s) — %.1f %.1f %.1f",
                            s.id(), s.type().displayName, s.variant().name().toLowerCase(), l.getX(), l.getY(), l.getZ())));
                }
                return;
            }
            case "remove" -> {
                var s = pm.removeNearest(player.getLocation(), 5);
                player.sendMessage(s == null
                        ? Component.text("❌ Aucun emplacement de plante à moins de 5 blocs.", NamedTextColor.RED)
                        : Component.text("✔ " + s.id() + " supprimé.", NamedTextColor.GREEN));
                return;
            }
            case "clear" -> {
                int n = pm.clearSpots();
                player.sendMessage(Component.text("✔ " + n + " emplacement(s) de plante supprimé(s).", NamedTextColor.GREEN));
                return;
            }
            default -> { }
        }
        fr.lolmc.game.PlantManager.PlantType type = switch (sub) {
            case "blastcone", "cone" -> fr.lolmc.game.PlantManager.PlantType.BLASTCONE;
            case "honeyfruit", "miel" -> fr.lolmc.game.PlantManager.PlantType.HONEYFRUIT;
            case "scryer", "bulbe" -> fr.lolmc.game.PlantManager.PlantType.SCRYER;
            default -> null;
        };
        if (type == null) {
            player.sendMessage("§cUsage: /lola plant <blastcone [inner|outer] | honeyfruit | scryer [normal|late]> — pose à tes pieds");
            player.sendMessage("§c       /lola plant list | remove (le plus proche) | clear");
            return;
        }
        var variant = fr.lolmc.game.PlantManager.defaultVariant(type);
        if (args.length >= 3) {
            try { variant = fr.lolmc.game.PlantManager.Variant.valueOf(args[2].toUpperCase()); }
            catch (IllegalArgumentException ex) { variant = null; }
            if (variant == null || !fr.lolmc.game.PlantManager.accepts(type, variant)) {
                player.sendMessage("§cVariante invalide : blastcone → inner|outer, scryer → normal|late");
                return;
            }
        }
        var s = pm.addSpot(type, variant, player.getLocation());
        player.sendMessage(Component.text("✔ " + type.displayName + " (" + variant.name().toLowerCase() + ") enregistré : "
                + s.id(), NamedTextColor.GREEN));
    }

    private void handleBaronPit(Player player, String[] args) {
        var jm = LolPlugin.getInstance().getJungleManager();
        var pit = jm.getBaronPit();
        if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
            pit.preview(null, player.getWorld());
            player.sendMessage(Component.text("✔ Terrain normal de la fosse remis en place.", NamedTextColor.GREEN));
            return;
        }
        if (args.length < 3 || !(args[1].equalsIgnoreCase("territorial") || args[1].equalsIgnoreCase("omniscient"))) {
            player.sendMessage("§cUsage: /lola baronpit <territorial|omniscient> <start|save|preview|clear> | /lola baronpit reset");
            return;
        }
        String form = args[1].toLowerCase();
        switch (args[2].toLowerCase()) {
            case "start" -> {
                Location[] sel = schematicSelections.get(player.getUniqueId());
                if (sel == null || sel[0] == null || sel[1] == null || !sel[0].getWorld().equals(sel[1].getWorld())) {
                    player.sendMessage(Component.text("❌ Définis d'abord la zone avec /lola schem pos1 et pos2.", NamedTextColor.RED));
                    return;
                }
                long volume = (long) (Math.abs(sel[0].getBlockX() - sel[1].getBlockX()) + 1)
                        * (Math.abs(sel[0].getBlockY() - sel[1].getBlockY()) + 1)
                        * (Math.abs(sel[0].getBlockZ() - sel[1].getBlockZ()) + 1);
                if (volume > 200_000) {
                    player.sendMessage(Component.text("❌ Zone trop grande (" + volume + " blocs, 200 000 max).", NamedTextColor.RED));
                    return;
                }
                pit.preview(null, sel[0].getWorld()); // partir du terrain normal
                int n = pit.start(player.getUniqueId(), form, sel[0], sel[1]);
                player.sendMessage(Component.text("✔ Terrain normal photographié (" + n + " blocs). Construis la fosse "
                        + form + ", puis /lola baronpit " + form + " save", NamedTextColor.GREEN));
            }
            case "save" -> {
                int n = pit.save(player.getUniqueId(), form, jm.getJungleConfig());
                if (n < 0) {
                    player.sendMessage(Component.text("❌ Lance d'abord /lola baronpit " + form + " start", NamedTextColor.RED));
                    return;
                }
                jm.saveBaronPit();
                player.sendMessage(Component.text("✔ Fosse " + form + " enregistrée (" + n + " blocs modifiés), terrain normal remis.",
                        NamedTextColor.GREEN));
            }
            case "preview" -> {
                if (!pit.has(form)) { player.sendMessage(Component.text("❌ Aucune fosse " + form + " enregistrée.", NamedTextColor.RED)); return; }
                pit.preview(form, player.getWorld());
                player.sendMessage(Component.text("✔ Fosse " + form + " posée. /lola baronpit reset pour l'enlever.", NamedTextColor.GREEN));
            }
            case "clear" -> {
                pit.preview(null, player.getWorld());
                pit.clear(form, jm.getJungleConfig());
                jm.saveBaronPit();
                player.sendMessage(Component.text("✔ Fosse " + form + " supprimée.", NamedTextColor.GREEN));
            }
            default -> player.sendMessage("§cAction : start, save, preview ou clear");
        }
    }

    private void handleRoad(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("end")) {
            if (!roadManager.isPainting(player.getUniqueId())) {
                player.sendMessage(Component.text("❌ Aucune route en cours de tracé.", NamedTextColor.RED));
                return;
            }
            int count = roadManager.finishPainting(player.getUniqueId());
            player.getInventory().remove(Material.GOLDEN_HOE);
            player.sendMessage(Component.text(String.format("✔ Route enregistrée (%d points).", count), NamedTextColor.GREEN));
            return;
        }

        if (args.length < 3) {
            player.sendMessage("§cUsage: /lola road <top|mid|bot> <blue|red> | /lola road end");
            return;
        }
        String lane = args[1].toLowerCase();
        String teamHint = args[2].toLowerCase();
        if (!teamHint.equals("blue") && !teamHint.equals("red") && !teamHint.equals("bleu") && !teamHint.equals("rouge")) {
            player.sendMessage("§cÉquipe: blue ou red");
            return;
        }

        roadManager.startPainting(player.getUniqueId(), lane, teamHint);
        var tool = new org.bukkit.inventory.ItemStack(Material.GOLDEN_HOE);
        var meta = tool.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("🖌 Pinceau de route — " + lane, NamedTextColor.GREEN));
            tool.setItemMeta(meta);
        }
        player.getInventory().addItem(tool);
        player.sendMessage(Component.text(String.format("🖌 Trace la route de la lane %s. Clique les blocs, puis /lola road end.", lane), NamedTextColor.AQUA));
    }

    // ── Événement de Clic ─────────────────────────────────────────

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        if (roadManager.isPainting(player.getUniqueId())) {
            if (player.getInventory().getItemInMainHand().getType() == Material.GOLDEN_HOE) {
                e.setCancelled(true);
                if (roadManager.paintBlock(player.getUniqueId(), e.getClickedBlock())) {
                    player.sendActionBar(Component.text("🖌 Bloc peint", NamedTextColor.GREEN));
                }
                return;
            }
        }

        PendingSetup setup = pending.get(player.getUniqueId());
        if (setup == null) return;
        e.setCancelled(true);

        Location clicked = e.getClickedBlock().getLocation();

        switch (setup.kind()) {
            case "structure" -> {
                int angle = 0;
                String facingMsg = "orientation par défaut (Sud)";
                var bd = e.getClickedBlock().getBlockData();
                // Le four est l'ancre des schématiques de phases (collées juste au-dessus, tournées
                // selon son orientation) : il doit RESTER en place (avant : il était supprimé au
                // clic, et aucune phase ne pouvait plus être collée)
                if (e.getClickedBlock().getType() != Material.FURNACE)
                    player.sendMessage(Component.text("⚠ Ce bloc n'est pas un four : les schématiques de phases ne pourront pas "
                            + "être collées ici. Pose un four (face avant = orientation) et reclique dessus.", NamedTextColor.GOLD));
                if (bd instanceof org.bukkit.block.data.Directional dir) {
                    // SOUTH est l orientation par defaut documentee du batiment ; elle
                    // retombe naturellement sur le default technique du switch (0°).
                    angle = switch (dir.getFacing()) {
                        case WEST  -> 90;
                        case NORTH -> 180;
                        case EAST  -> 270;
                        default    -> 0;
                    };
                    facingMsg = "orientée vers " + dir.getFacing() + " (" + angle + "°)";
                }
                mapManager.setStructure(setup.type(), setup.team(), setup.lane(), setup.index(), clicked, angle);
                var tier = pendingTier.remove(player.getUniqueId());
                if (tier != null) {
                    mapManager.setStructureTier(setup.type(), setup.team(), setup.lane(), setup.index(), tier);
                }
                player.sendMessage(Component.text("   ↳ " + facingMsg, NamedTextColor.GRAY));
                showMarker(clicked.clone().add(0, 1, 0), setup.team());
                player.sendMessage(Component.text(String.format("✔ %s %s %s #%d placé.", setup.type().name().toLowerCase(), setup.team().name(), setup.lane(), setup.index()), NamedTextColor.GREEN));
            }
            case "jungle" -> {
                var type = fr.lolmc.game.JungleManager.MonsterType.valueOf(setup.lane());
                // Configuration de la carte template (reste global, jamais une instance)
                LolPlugin.getInstance().getJungleManager().setCamp(type, setup.team(), setup.index(), clicked.clone().add(0.5, 1, 0.5));
                player.sendMessage(Component.text(String.format("✔ %s placé.", type.displayName), NamedTextColor.GREEN));
            }
            case "spawn" -> {
                Location spawnLoc = clicked.clone().add(0.5, 1, 0.5);
                spawnLoc.setYaw(player.getLocation().getYaw());
                spawnLoc.setPitch(0);
                mapManager.setSpawn(setup.team(), setup.position(), spawnLoc);
                player.sendMessage(Component.text(String.format("✔ Spawn %s #%d défini.", setup.team().name(), setup.position()), NamedTextColor.GREEN));
            }
            default -> { }
        }

        pending.remove(player.getUniqueId());
    }

    private void showMarker(Location loc, Team team) {
        Material mat = (team == Team.BLUE) ? Material.BLUE_STAINED_GLASS : Material.RED_STAINED_GLASS;
        Material original = loc.getBlock().getType();
        loc.getBlock().setType(mat);
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() { if (loc.getBlock().getType() == mat) loc.getBlock().setType(original); }
        }.runTaskLater(LolPlugin.getInstance(), 200L);
    }

    private fr.lolmc.game.AnnouncementManager.PingType parsePingType(String ptype) {
        // "danger"/"warn" retombent sur DANGER, qui est aussi le comportement de secours
        // pour toute entree invalide (default) : pas besoin de les lister explicitement.
        return switch (ptype) {
            case "omw", "onmyway"          -> fr.lolmc.game.AnnouncementManager.PingType.ON_MY_WAY;
            case "miss", "missing", "mia"  -> fr.lolmc.game.AnnouncementManager.PingType.MISSING;
            case "assist"                  -> fr.lolmc.game.AnnouncementManager.PingType.ASSIST;
            case "enemy", "here"           -> fr.lolmc.game.AnnouncementManager.PingType.ENEMY;
            default                        -> fr.lolmc.game.AnnouncementManager.PingType.DANGER;
        };
    }

    private Team parseTeam(String s) {
        return switch (s.toLowerCase()) {
            case "blue", "bleu" -> Team.BLUE;
            case "red", "rouge" -> Team.RED;
            default -> null;
        };
    }

    // ── Permissions ───────────────────────────────────────────────────
    /** Sous-commandes de /lola → groupe (lolmc.admin.<groupe>). Permission : lolmc.admin.<sous-commande>. */
    static final Map<String, String> ADMIN_GROUPS = new java.util.LinkedHashMap<>();
    static {
        for (String s : List.of("set", "position", "center", "schem", "road", "jungle", "plant", "baronpit",
                "shopnpc", "mode", "template")) ADMIN_GROUPS.put(s, "config");
        for (String s : List.of("solo", "testgame", "give", "level", "gold", "hp", "resetcd", "buff", "spawn",
                "wave", "team", "debug")) ADMIN_GROUPS.put(s, "test");
        for (String s : List.of("start", "stop", "select", "join", "instance", "reload", "permcheck"))
            ADMIN_GROUPS.put(s, "game");
    }

    static String canonicalAdminSub(String sub) {
        return switch (sub) {
            case "rejoindre" -> "join";
            case "instances" -> "instance";
            default -> sub;
        };
    }

    static boolean canUseAdmin(Player p, String sub) {
        return p.isOp() || p.hasPermission("lolmc.admin." + canonicalAdminSub(sub));
    }

    /** Au moins une commande /lola autorisée. */
    static boolean hasAnyAdmin(Player p) {
        if (p.isOp()) return true;
        for (String s : ADMIN_GROUPS.keySet()) if (p.hasPermission("lolmc.admin." + s)) return true;
        return false;
    }

    /** Permission d'une sous-commande de /l (null = aucune). */
    private static String playerPermission(String sub) {
        return switch (sub) {
            case "runes" -> "lolmc.player.runes";
            case "ping" -> "lolmc.player.ping";
            case "ff", "forfait" -> "lolmc.player.ff";
            case "stats" -> "lolmc.player.stats";
            case "shop" -> "lolmc.player.shop";
            case "recap", "recapitulatif", "honor", "honorer", "honneur" -> "lolmc.player.honor";
            case "grimoire", "spellbook" -> "lolmc.player.grimoire";
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (cmd.getName().equalsIgnoreCase("l")) {
            if (args.length == 1) {
                List<String> subs = new java.util.ArrayList<>();
                for (String s : List.of("runes", "ping", "ff", "stats", "shop", "recap", "honor", "grimoire"))
                    if (s.startsWith(args[0].toLowerCase()) && sender.hasPermission(playerPermission(s))) subs.add(s);
                return subs;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("ping")) return List.of("danger", "omw", "miss", "assist", "enemy");
            if (args.length == 2 && args[0].equalsIgnoreCase("honor") && sender instanceof Player hp) {
                List<String> allies = new java.util.ArrayList<>();
                var tm = LolPlugin.getInstance().getTeamManager();
                for (Player q : org.bukkit.Bukkit.getOnlinePlayers())
                    if (q != hp && tm.areAllies(q, hp) && q.getName().toLowerCase().startsWith(args[1].toLowerCase()))
                        allies.add(q.getName());
                return allies;
            }
            return List.of();
        }
        if (!(sender instanceof Player tp) || !hasAnyAdmin(tp)) return List.of();
        if (args.length == 1) {
            List<String> subs = new java.util.ArrayList<>();
            for (String s : ADMIN_GROUPS.keySet())
                if (canUseAdmin(tp, s) && s.startsWith(args[0].toLowerCase())) subs.add(s);
            if ("help".startsWith(args[0].toLowerCase())) subs.add("help");
            return subs;
        }
        if (!canUseAdmin(tp, args[0].toLowerCase())) return List.of();
        if (args.length == 2 && (args[0].equalsIgnoreCase("rejoindre") || args[0].equalsIgnoreCase("join"))) {
            List<String> ids = new java.util.ArrayList<>();
            for (var c : LolPlugin.getInstance().getChampionManager().getAllChampions())
                if (c.getId().startsWith(args[1].toLowerCase())) ids.add(c.getId());
            return ids;
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("rejoindre") || args[0].equalsIgnoreCase("join"))) {
            List<String> ids = new java.util.ArrayList<>();
            var im = LolPlugin.getInstance().getInstanceManager();
            if (im != null) for (var gi : im.getAllInstances())
                if (gi.getState() == fr.lolmc.instance.GameInstance.State.RUNNING) ids.add(String.valueOf(gi.getId()));
            return ids;
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "template" -> List.of("save", "info");
                case "instance", "instances" -> {
                    List<String> ids = new java.util.ArrayList<>();
                    for (var gi : LolPlugin.getInstance().getInstanceManager().getAllInstances()) ids.add(String.valueOf(gi.getId()));
                    ids.add("template");
                    yield ids;
                }
                case "set" -> List.of("turret", "inhibitor", "nexus", "basenexus");
                case "schem" -> List.of("pos1", "pos2", "save"); // AJOUT : Auto-complétion de l'action de schématique
                case "baronpit" -> List.of("territorial", "omniscient", "reset");
                case "plant" -> List.of("blastcone", "honeyfruit", "scryer", "list", "remove", "clear");
                case "shopnpc", "position" -> List.of("blue", "red");
                case "mode" -> List.of("ranked", "normal");
                case "road" -> List.of("top", "mid", "bot", "end");
                case "jungle" -> List.of("gromp", "murkwolf", "raptor", "krug", "red_buff", "blue_buff", "scuttle_crab", "dragon_infernal", "dragon_ocean", "dragon_mountain", "dragon_cloud", "dragon_chemtech", "dragon_hextech", "dragon_elder", "voidgrub", "herald", "baron");
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("baronpit") && !args[1].equalsIgnoreCase("reset"))
            return List.of("start", "save", "preview", "clear");
        if (args.length == 3 && args[0].equalsIgnoreCase("jungle")) {
            if (args[1].equalsIgnoreCase("scuttle_crab")) return List.of("top", "bot");
            try {
                var jt = fr.lolmc.game.JungleManager.MonsterType.valueOf(args[1].toUpperCase());
                return jt.isEpic() || jt == fr.lolmc.game.JungleManager.MonsterType.VOIDGRUB ? List.of() : List.of("blue", "red");
            } catch (IllegalArgumentException ex) { return List.of(); }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("plant")) {
            if (args[1].equalsIgnoreCase("blastcone")) return List.of("inner", "outer");
            if (args[1].equalsIgnoreCase("scryer")) return List.of("normal", "late");
            return List.of();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return List.of("blue", "red");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("set")) return List.of("top", "mid", "bot", "base");
        return List.of();
    }

    // ══════════════════════════════════════════════════════════════
    // COMMANDES ADMIN AVANCÉES
    // ══════════════════════════════════════════════════════════════

    // ── /lola spawn <type> [équipe] ─────────────────────────────────────
    // Types: minion_melee, minion_caster, minion_cannon, minion_super,
    //        dragon, baron, herald, wolf, blue, red, gromp, raptor, krug
    private void handleSpawn(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("§c/lola spawn <entité> [team:blue|red]", NamedTextColor.RED));
            player.sendMessage(Component.text("§7Entités: minion_melee minion_caster minion_cannon minion_super", NamedTextColor.GRAY));
            player.sendMessage(Component.text("§7         dragon baron herald wolf blue red gromp raptor krug", NamedTextColor.GRAY));
            return;
        }
        String type = args[1].toLowerCase();
        var loc = player.getLocation();
        var plugin = LolPlugin.getInstance();
        fr.lolmc.team.TeamManager.Team team = fr.lolmc.team.TeamManager.Team.BLUE;
        if (args.length >= 3) {
            try { team = fr.lolmc.team.TeamManager.Team.valueOf(args[2].toUpperCase()); } catch (Exception ignored) {}
        }

        switch (type) {
            case "minion_melee"  -> { fr.lolmc.instance.InstanceHelper.minionManager(player).spawnTestMinion(loc, team, "melee");  player.sendMessage(Component.text("✔ Sbire mêlée spawné", NamedTextColor.GREEN)); }
            case "minion_caster" -> { fr.lolmc.instance.InstanceHelper.minionManager(player).spawnTestMinion(loc, team, "caster"); player.sendMessage(Component.text("✔ Sbire caster spawné", NamedTextColor.GREEN)); }
            case "minion_cannon" -> { fr.lolmc.instance.InstanceHelper.minionManager(player).spawnTestMinion(loc, team, "cannon"); player.sendMessage(Component.text("✔ Sbire canon spawné", NamedTextColor.GREEN)); }
            case "minion_super"  -> { fr.lolmc.instance.InstanceHelper.minionManager(player).spawnTestMinion(loc, team, "super");  player.sendMessage(Component.text("✔ Super-sbire spawné", NamedTextColor.GREEN)); }
            case "dragon"        -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "DRAGON_INFERNAL"); player.sendMessage(Component.text("✔ Dragon spawné", NamedTextColor.RED)); }
            case "baron"         -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "BARON");           player.sendMessage(Component.text("✔ Baron Nashor spawné", NamedTextColor.LIGHT_PURPLE)); }
            case "herald"        -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "HERALD");          player.sendMessage(Component.text("✔ Héraut spawné", NamedTextColor.AQUA)); }
            case "wolf"          -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "WOLF");            player.sendMessage(Component.text("✔ Loup spawné", NamedTextColor.GRAY)); }
            case "blue"          -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "BLUE");            player.sendMessage(Component.text("✔ Blue Sentinel spawné", NamedTextColor.BLUE)); }
            case "red"           -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "RED");             player.sendMessage(Component.text("✔ Red Brambleback spawné", NamedTextColor.RED)); }
            case "gromp"         -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "GROMP");           player.sendMessage(Component.text("✔ Gromp spawné", NamedTextColor.GREEN)); }
            case "raptor"        -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "RAPTOR");          player.sendMessage(Component.text("✔ Raptors spawnés", NamedTextColor.YELLOW)); }
            case "krug"          -> { fr.lolmc.instance.InstanceHelper.jungleManager(player).spawnTestMonster(loc, "KRUG");            player.sendMessage(Component.text("✔ Krugs spawnés", NamedTextColor.GOLD)); }
            default -> player.sendMessage(Component.text("§cEntité inconnue: " + type, NamedTextColor.RED));
        }
    }

    // ── /lola buff <type> [joueur] ─────────────────────────────────────
    // Buffs: red, blue, baron, dragon_infernal, dragon_ocean, dragon_mountain,
    //        dragon_cloud, dragon_chemtech, dragon_elder, dragon_soul
    private void handleBuff(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("§c/lola buff <type> [joueur]", NamedTextColor.RED));
            player.sendMessage(Component.text("§7Types: red blue baron dragon_infernal dragon_ocean dragon_mountain dragon_cloud dragon_chemtech dragon_elder dragon_soul", NamedTextColor.GRAY));
            return;
        }
        String buffType = args[1].toLowerCase();
        Player target = player;
        if (args.length >= 3) {
            target = org.bukkit.Bukkit.getPlayerExact(args[2]);
            if (target == null) { player.sendMessage(Component.text("§cJoueur introuvable", NamedTextColor.RED)); return; }
        }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(target)) { player.sendMessage(Component.text("§cLa cible n'a pas de champion", NamedTextColor.RED)); return; }
        fr.lolmc.instance.InstanceHelper.jungleManager(target).applyBuffPublic(target, buffType);
        player.sendMessage(Component.text("✔ Buff '" + buffType + "' appliqué à " + target.getName(), NamedTextColor.GREEN));
        if (!target.equals(player)) target.sendMessage(Component.text("✔ Buff '" + buffType + "' reçu de " + player.getName(), NamedTextColor.GOLD));
    }

    // ── /lola resetcd [joueur] ─────────────────────────────────────────────
    // Remet tous les cooldowns de sorts à 0 pour le joueur
    private void handleResetCd(Player player, String[] args) {
        Player target = player;
        if (args.length >= 2) {
            target = org.bukkit.Bukkit.getPlayerExact(args[1]);
            if (target == null) { player.sendMessage(Component.text("§cJoueur introuvable", NamedTextColor.RED)); return; }
        }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(target)) { player.sendMessage(Component.text("§cPas de champion", NamedTextColor.RED)); return; }
        var champ = cm.getChampion(target);
        // Réinitialiser tous les cooldowns en triggerant avec dynamicCooldown=0
        for (var ab : champ.getAbilities()) {
            if (ab != null) {
                ab.setDynamicCooldown(0.001);
                ab.triggerCooldown(target);
                // Remettre à -1 (utilise baseCooldown) au tick suivant
                final var finalAb = ab;
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override public void run() { finalAb.setDynamicCooldown(-1); }
                }.runTaskLater(LolPlugin.getInstance(), 1L);
            }
        }
        // Reset Flash
        LolPlugin.getInstance().getFlashManager().resetCooldown(target);
        player.sendMessage(Component.text("✔ Cooldowns réinitialisés pour " + target.getName(), NamedTextColor.GREEN));
        if (!target.equals(player)) target.sendMessage(Component.text("✔ Tes cooldowns ont été réinitialisés par " + player.getName(), NamedTextColor.GOLD));
    }

    // ── /lola hp <montant|full> [joueur] ───────────────────────────────────
    private void handleHp(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(Component.text("§c/lola hp <montant|full> [joueur]", NamedTextColor.RED)); return; }
        Player target = player;
        if (args.length >= 3) {
            target = org.bukkit.Bukkit.getPlayerExact(args[2]);
            if (target == null) { player.sendMessage(Component.text("§cJoueur introuvable", NamedTextColor.RED)); return; }
        }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(target)) { player.sendMessage(Component.text("§cPas de champion", NamedTextColor.RED)); return; }
        var hp = cm.getChampion(target).getHPSystem();
        if (args[1].equalsIgnoreCase("full")) {
            hp.setCurrentHP(hp.getMaxHP());
            player.sendMessage(Component.text("✔ HP plein pour " + target.getName(), NamedTextColor.GREEN));
        } else {
            try {
                double amount = Double.parseDouble(args[1]);
                hp.setCurrentHP(amount);
                player.sendMessage(Component.text("✔ HP de " + target.getName() + " → " + (int)amount, NamedTextColor.GREEN));
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("§cMontant invalide", NamedTextColor.RED));
            }
        }
    }

    // ── /lola wave ────────────────────────────────────────────────────────
    // Force le spawn immédiat d'une vague de sbires
    // ── /lola template [save|info] ─────────────────────────────────────────
    // save : enregistre le monde où se trouve l'admin dans plugins/LolMC/template et y rattache map.yml/roads.yml/jungle.yml.
    private void handleTemplate(Player player, String[] args) {
        var im = LolPlugin.getInstance().getInstanceManager();
        String sub = args.length >= 2 ? args[1].toLowerCase() : "info";
        switch (sub) {
            case "save" -> {
                var w = player.getWorld();
                if (w.getName().startsWith(fr.lolmc.instance.InstanceManager.INSTANCE_PREFIX)
                        || fr.lolmc.instance.InstanceHelper.instanceOf(player) != null) {
                    player.sendMessage(Component.text("❌ Impossible depuis un monde d'instance : place-toi dans la map à enregistrer.",
                            NamedTextColor.RED));
                    return;
                }
                if (im.getAllInstances().stream().anyMatch(i -> i.getState() == fr.lolmc.instance.GameInstance.State.RUNNING)) {
                    player.sendMessage(Component.text("❌ Une partie est en cours : attends la fin avant de changer le template.",
                            NamedTextColor.RED));
                    return;
                }
                player.sendMessage(Component.text("⏳ Enregistrement du monde « " + w.getName() + " » comme template...",
                        NamedTextColor.YELLOW));
                final String name = w.getName();
                im.snapshotTemplate(w, msg -> {
                    boolean ok = msg.startsWith("✔");
                    player.sendMessage(Component.text(msg, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                    if (!ok) return;
                    // Le template devient ce monde : chemins, tours, nexus, spawns et jungle suivent
                    im.setTemplateWorldName(name);
                    var lp = LolPlugin.getInstance();
                    lp.getMapManager().rebindWorld(name);
                    lp.getJungleManager().rebindWorld(name);
                    lp.getRoadManager().rebindWorld(name);
                    lp.getPlantManager().rebindWorld(name);
                    player.sendMessage(Component.text("✔ Tours, nexus, spawns, chemins, jungle et plantes de « " + name
                            + " » seront utilisés par toutes les instances.", NamedTextColor.GREEN));
                });
            }
            default -> {
                player.sendMessage(Component.text("Source du template : " + im.describeTemplateSource(), NamedTextColor.YELLOW));
                player.sendMessage(Component.text("§7/lola template save : enregistre le monde où tu es comme template (plugins/LolMC/template) et y rattache map/chemins/jungle",
                        NamedTextColor.GRAY));
            }
        }
    }

    /** UUID des joueurs de la partie de l'admin (son instance, sinon tous les joueurs en jeu). */
    private java.util.Set<java.util.UUID> contextIds(Player admin) {
        var inst = fr.lolmc.instance.InstanceHelper.instanceOf(admin);
        java.util.Collection<? extends Player> grp = inst != null
                ? inst.getOnlinePlayers() : fr.lolmc.util.WorldContext.getGamePlayers();
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        for (Player gp : grp) ids.add(gp.getUniqueId());
        ids.add(admin.getUniqueId());
        return ids;
    }

    /** Fait pousser les plantes dans le monde de la partie de l'admin (instance ou template). */
    private void spawnPlantsFor(Player admin) {
        var pm = LolPlugin.getInstance().getPlantManager();
        if (fr.lolmc.instance.InstanceHelper.instanceOf(admin) != null) pm.spawnAll(admin.getWorld());
        else pm.spawnAll();
    }

    private void clearPlantsFor(Player admin) {
        var pm = LolPlugin.getInstance().getPlantManager();
        if (fr.lolmc.instance.InstanceHelper.instanceOf(admin) != null) pm.clearAll(admin.getWorld());
        else pm.clearAll();
    }

    private void handleWave(Player player) {
        fr.lolmc.instance.InstanceHelper.minionManager(player).forceWave();
        player.sendMessage(Component.text("✔ Vague forcée!", NamedTextColor.GREEN));
    }

    // ── /lola instance [numéro|template] ──────────────────────────────────
    // Sans argument : liste les instances. Avec un numéro : téléporte l'admin
    // dans cette instance (spawn bleu, sinon spawn du monde). "template" = monde template.
    private void handleInstance(Player player, String[] args) {
        var im = LolPlugin.getInstance().getInstanceManager();
        var all = new java.util.ArrayList<>(im.getAllInstances());
        all.sort(java.util.Comparator.comparingInt(fr.lolmc.instance.GameInstance::getId));
        if (args.length < 2) {
            if (all.isEmpty()) {
                player.sendMessage(Component.text("Aucune instance active. (/lola instance template pour le monde template)", NamedTextColor.GRAY));
                return;
            }
            player.sendMessage(Component.text("═══ Instances actives ═══", NamedTextColor.GOLD));
            for (var gi : all) {
                player.sendMessage(Component.text(String.format("  #%d  %s  — %d joueur(s), état %s",
                        gi.getId(), gi.getWorldName(), gi.getWorld().getPlayers().size(), gi.getState()),
                        NamedTextColor.YELLOW));
            }
            player.sendMessage(Component.text("§7/lola instance <numéro> pour s'y téléporter", NamedTextColor.GRAY));
            return;
        }
        org.bukkit.Location dest;
        String label;
        if (args[1].equalsIgnoreCase("template")) {
            var w = fr.lolmc.util.WorldContext.getGameWorld();
            if (w == null) { player.sendMessage(Component.text("❌ Monde template introuvable.", NamedTextColor.RED)); return; }
            dest = w.getSpawnLocation();
            label = "monde template (" + w.getName() + ")";
        } else {
            int id;
            try { id = Integer.parseInt(args[1].replace("#", "")); }
            catch (NumberFormatException ex) {
                player.sendMessage(Component.text("❌ Numéro invalide : " + args[1], NamedTextColor.RED));
                return;
            }
            fr.lolmc.instance.GameInstance target = null;
            for (var gi : all) if (gi.getId() == id) { target = gi; break; }
            if (target == null) {
                player.sendMessage(Component.text("❌ Instance #" + id + " introuvable. /lola instance pour la liste.", NamedTextColor.RED));
                return;
            }
            dest = target.getMapManager().getBaseSpawn(fr.lolmc.team.TeamManager.Team.BLUE);
            if (dest == null) dest = target.getWorld().getSpawnLocation();
            label = "instance #" + id;
        }
        player.teleport(dest);
        player.sendMessage(Component.text("✔ Téléporté dans " + label + ".", NamedTextColor.GREEN));
    }

    // ── /lola help ────────────────────────────────────────────────────────
    private void handleAdminHelp(Player player) {
        player.sendMessage(Component.text("═══ /lola — Commandes admin ═══", NamedTextColor.GOLD));
        String[][] cmds = {
                {"/lola instance [n|template]", "Lister / rejoindre une instance de partie"},
                {"/lola template [save|info]",   "Enregistrer / voir le template (plugins/LolMC/template)"},
                {"/lola solo [champion]",        "Mode test solo (niveau 18, 20k or)"},
                {"/lola give <champion>",         "Assigner un champion"},
                {"/lola level <1-18>",            "Changer de niveau"},
                {"/lola gold <montant>",          "Ajouter de l'or"},
                {"/lola hp <montant|full> [j]",   "Modifier les HP"},
                {"/lola resetcd [joueur]",        "Reset tous les cooldowns"},
                {"/lola buff <type> [joueur]",    "Appliquer un buff"},
                {"/lola spawn <entité> [team]",   "Spawner une entité"},
                {"/lola wave",                    "Forcer une vague de sbires"},
                {"/lola start",                   "Lancer la partie"},
                {"/lola stop",                    "Arrêter la partie"},
                {"/lola testgame",                "Lancer une partie test"},
                {"/lola join <champion> [instance]", "Rejoindre une partie en cours (alias rejoindre)"},
                {"/lola debug on|off",            "Activer/désactiver le debug"},
                {"/lola reload",                  "Recharger champions.yml"},
                {"/lola set <turret|nexus|basenexus> ...", "Placer une structure"},
                {"/lola position <blue|red> <1-5>", "Définir un point de spawn"},
                {"/lola center",                  "Définir le centre de la minimap ici"},
                {"/lola schem <pos1|pos2|save> ...", "Gérer une schématique"},
                {"/lola baronpit <forme> <start|save|preview|clear>", "Terrain de la fosse du Baron par forme"},
                {"/lola road <top|mid|bot|end> ...", "Tracer une route"},
                {"/lola jungle <type> [blue|red]", "Placer un camp de jungle (clic au sol)"},
                {"/lola plant <type> [variante]", "Placer une plante à tes pieds (list, remove, clear)"},
                {"/lola shopnpc <blue|red>",      "Créer un PNJ boutique ici"},
                {"/lola mode <ranked|normal>",    "Définir le mode de partie"},
                {"/lola team <blue|red>",         "Se placer dans une équipe"},
                {"/lola select",                  "Lancer la sélection de champion pour tous"},
                {"/lola permcheck",                "Vérifier les permissions de skin vs plugin.yml"},
        };
        for (String[] cmd : cmds) {
            String sub = canonicalAdminSub(cmd[0].split(" ")[1]);
            if (!"help".equals(sub) && !canUseAdmin(player, sub)) continue;
            player.sendMessage(Component.text("§e" + cmd[0] + " §7— " + cmd[1]));
        }
    }}
