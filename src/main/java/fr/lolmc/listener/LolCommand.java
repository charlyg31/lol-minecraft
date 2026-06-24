package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
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
 * Commande admin /lol pour configurer la carte.
 *
 *   /lol set turret <top|mid|bot|base> <index>  → puis clic sur la case centrale
 *   /lol set nexus <top|mid|bot> <index>         → puis clic
 *   /lol set basenexus                           → puis clic (nexus principal)
 *   /lol position <blue|red> <1-5>               → puis clic au sol (spawn)
 *   /lol lane <top|mid|bot>                       → clics successifs = waypoints, /lol lane done
 *   /lol schem <pos1|pos2|save>                  → définition et sauvegarde de schématique d'ancre
 *   /lol start                                    → lance la partie (reset structures)
 *   /lol stop                                     → arrête la partie
 */
public class LolCommand implements CommandExecutor, TabCompleter, Listener {

    private final MapManager mapManager;
    private final fr.lolmc.game.RoadManager roadManager;

    // Mode setup en attente d'un clic, par joueur
    private final Map<UUID, PendingSetup> pending = new HashMap<>();
    private final Map<UUID, fr.lolmc.game.GameStructure.TurretTier> pendingTier = new HashMap<>();
    // Waypoints en cours de définition (mode lane)
    private final Map<UUID, List<Location>> laneSetup = new HashMap<>();
    private final Map<UUID, String> laneSetupName = new HashMap<>();

    // AJOUT : Sélections locales des coins de schématiques par joueur
    private final Map<UUID, Location[]> schematicSelections = new HashMap<>();

    private record PendingSetup(String kind, Type type, Team team, String lane, int index, int position) {}

    public LolCommand(MapManager mapManager, fr.lolmc.game.RoadManager roadManager) {
        this.mapManager = mapManager;
        this.roadManager = roadManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§cJoueur uniquement."); return true; }
        if (!player.hasPermission("lolmc.admin") && !player.isOp()) {
            player.sendMessage(Component.text("❌ Permission requise.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) { sendHelp(player); return true; }

        switch (args[0].toLowerCase()) {
            case "set" -> handleSet(player, args);
            case "position" -> handlePosition(player, args);
            case "lane" -> handleLane(player, args);
            case "schem" -> handleSchem(player, args); // AJOUT : Liaison vers le gestionnaire de schématiques
            case "road" -> handleRoad(player, args);
            case "jungle" -> handleJungle(player, args);
            case "shopnpc" -> handleShopNpc(player, args);
            case "mode" -> handleMode(player, args);
            case "solo" -> handleSolo(player, args);
            case "give" -> handleGive(player, args);
            case "level" -> handleLevel(player, args);
            case "gold" -> handleGold(player, args);
            case "team" -> handleTeamCmd(player, args);
            case "testgame" -> handleTestGame(player);
            case "debug" -> handleDebug(player, args);
            case "reload" -> {
                LolPlugin.getInstance().reloadConfig();
                fr.lolmc.util.Balance.load();
                player.sendMessage(Component.text("✔ Config et équilibrage (champions.yml) rechargés.", NamedTextColor.GREEN));
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
                mapManager.resetAllStructures();
                LolPlugin.getInstance().getMinionManager().startWaves();
                LolPlugin.getInstance().getJungleManager().startJungle();
                LolPlugin.getInstance().getGameManager().startGame();
                LolPlugin.getInstance().getMatchScoreboard().startMatch(
                        LolPlugin.getInstance().getMatchScoreboard().isRanked());
                player.sendMessage(Component.text("✔ Partie lancée (structures, sbires, jungle, timer)!", NamedTextColor.GREEN));
            }
            case "stop" -> {
                LolPlugin.getInstance().getMinionManager().stopWaves();
                LolPlugin.getInstance().getJungleManager().stopJungle();
                LolPlugin.getInstance().getGameManager().stopGame();
                player.sendMessage(Component.text("⏹ Partie arrêtée.", NamedTextColor.YELLOW));
            }
            default -> sendHelp(player);
        }
        return true;
    }

    // ── /lol schem ────────────────────────────────────────────────

    private void handleSchem(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /lol schem <pos1|pos2|save> ...");
            return;
        }

        String subCommand = args[1].toLowerCase();
        UUID uuid = player.getUniqueId();

        if (subCommand.equals("pos1")) {
            Location loc = player.getLocation().getBlock().getLocation();
            Location[] sel = schematicSelections.computeIfAbsent(uuid, k -> new Location[2]);
            sel[0] = loc;
            player.sendMessage(Component.text("✔ Position 1 définie à tes pieds : " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(), NamedTextColor.GREEN));
            return;
        }

        if (subCommand.equals("pos2")) {
            Location loc = player.getLocation().getBlock().getLocation();
            Location[] sel = schematicSelections.computeIfAbsent(uuid, k -> new Location[2]);
            sel[1] = loc;
            player.sendMessage(Component.text("✔ Position 2 définie à tes pieds : " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(), NamedTextColor.GREEN));
            return;
        }

        if (subCommand.equals("save")) {
            if (args.length < 3) {
                player.sendMessage("§cUsage: /lol schem save <nom_de_la_schematic>");
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

            // Appel direct à ton gestionnaire de schématiques
            mapManager.getSchematics().saveSchematicWithAnchor(name, sel[0], sel[1]);

            player.sendMessage(Component.text("✔ Sauvegarde de la schématique exécutée !", NamedTextColor.GREEN));
            return;
        }

        player.sendMessage("§cAction inconnue. Utilise /lol schem <pos1|pos2|save>");
    }

    // ── /lol set ──────────────────────────────────────────────────

    private void handleSet(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol set <turret|nexus|basenexus> ..."); return; }
        String what = args[1].toLowerCase();

        if (what.equals("basenexus")) {
            if (args.length < 3) { player.sendMessage("§cUsage: /lol set basenexus <blue|red>"); return; }
            Team team = parseTeam(args[2]);
            if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
            pending.put(player.getUniqueId(), new PendingSetup("structure", Type.NEXUS_BASE, team, "base", 1, 0));
            player.sendMessage(Component.text("👉 Clique sur la case centrale du Nexus principal ("
                    + team.name() + ").", NamedTextColor.AQUA));
            return;
        }

        if (args.length < 5) {
            player.sendMessage("§cUsage: /lol set " + what + " <blue|red> <top|mid|bot|base> <index>");
            return;
        }
        Type type = switch (what) {
            case "turret" -> Type.TURRET;
            case "inhibitor" -> Type.INHIBITOR;
            default -> Type.NEXUS;
        };
        Team team = parseTeam(args[2]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        String lane = args[3].toLowerCase();
        int index;
        try { index = Integer.parseInt(args[4]); }
        catch (NumberFormatException e) { player.sendMessage("§cIndex invalide."); return; }

        fr.lolmc.game.GameStructure.TurretTier tier = fr.lolmc.game.GameStructure.TurretTier.OUTER;
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

    // ── /lol position ─────────────────────────────────────────────

    private void handlePosition(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage("§cUsage: /lol position <blue|red> <1-5>"); return; }
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

    // ── /lol lane ─────────────────────────────────────────────────

    private void handleLane(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol lane <top|mid|bot> | /lol lane done"); return; }
        if (args[1].equalsIgnoreCase("done")) {
            List<Location> wps = laneSetup.remove(player.getUniqueId());
            String lane = laneSetupName.remove(player.getUniqueId());
            if (wps == null || lane == null || wps.isEmpty()) {
                player.sendMessage("§cAucune lane en cours de définition.");
                return;
            }
            LolPlugin.getInstance().getMinionManager().setLaneWaypoints(lane, wps);
            player.sendMessage(Component.text(String.format(
                    "✔ Lane %s enregistrée (%d points).", lane, wps.size()), NamedTextColor.GREEN));
            return;
        }
        String lane = args[1].toLowerCase();
        laneSetup.put(player.getUniqueId(), new ArrayList<>());
        laneSetupName.put(player.getUniqueId(), lane);
        player.sendMessage(Component.text(String.format(
                "👉 Clique successivement sur le chemin de la lane %s (du spawn BLEU vers ROUGE). "
                        + "Tape /lol lane done pour finir.", lane), NamedTextColor.AQUA));
    }

    // ── /lol debug ────────────────────────────────────────────────

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
                    player.sendMessage(Component.text("Usage: /lol debug [on|off|clear|state] — sans argument: rapport d'état du joueur", NamedTextColor.GRAY));
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

        boolean running = plugin.getGameManager().isRunning();
        player.sendMessage(Component.text("Partie active: " + running, NamedTextColor.GRAY));

        if (team != null) {
            var spawn = plugin.getMapManager().getSpawn(team, 1);
            player.sendMessage(Component.text("Spawn équipe défini: " + (spawn != null), spawn != null ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        player.sendMessage(Component.text("═══════════════════", NamedTextColor.YELLOW));
    }

    // ── /lol solo ─────────────────────────────────────────────────

    private void handleSolo(Player player, String[] args) {
        String champId = args.length >= 2 ? args[1].toLowerCase() : "garen";
        var plugin = LolPlugin.getInstance();

        plugin.getTeamManager().setTeam(player, fr.lolmc.team.TeamManager.Team.BLUE);
        plugin.getChampionManager().assignChampion(player, champId);
        var soloChamp = plugin.getChampionManager().getChampion(player);
        if (soloChamp != null) {
            soloChamp.getLevelSystem().setLevel(18);
            soloChamp.getStats().setChampionLevel(18);
            soloChamp.getLevelSystem().maxOutAbilities();
            plugin.getHotbarManager().renderPage(player, soloChamp);
        }
        plugin.getRuneManager().applyRuneStats(player);
        plugin.getGoldManager().addGold(player.getUniqueId(), 20000);
        mapManager.resetAllStructures();
        plugin.getMinionManager().startWaves();
        plugin.getJungleManager().startJungle();
        plugin.getGameManager().startGame();
        plugin.getMatchScoreboard().startMatch(false);
        var spawn = plugin.getMapManager().getSpawn(fr.lolmc.team.TeamManager.Team.BLUE, 1);
        if (spawn != null) player.teleport(spawn);

        player.sendMessage(Component.text("🧪 MODE SOLO lancé!", NamedTextColor.GREEN));
        player.sendMessage(Component.text("Équipe BLEUE • Champion: " + champId, NamedTextColor.AQUA));
    }

    private void handleGive(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol give <champion>"); return; }
        String champId = args[1].toLowerCase();
        LolPlugin.getInstance().getChampionManager().assignChampion(player, champId);
        LolPlugin.getInstance().getRuneManager().applyRuneStats(player);
        player.sendMessage(Component.text("✔ Champion: " + champId, NamedTextColor.GREEN));
    }

    private void handleLevel(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol level <1-18>"); return; }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) { player.sendMessage("§cTu n'as pas de champion."); return; }
        int lvl;
        try { lvl = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) { player.sendMessage("§cNiveau invalide."); return; }
        lvl = Math.max(1, Math.min(18, lvl));
        var champ = cm.getChampion(player);
        champ.getLevelSystem().setLevel(lvl);
        champ.getLevelSystem().maxOutAbilities();
        champ.getStats().setChampionLevel(lvl);
        LolPlugin.getInstance().getHotbarManager().renderPage(player, champ);
        player.sendMessage(Component.text("✔ Niveau " + lvl + " — sorts débloqués", NamedTextColor.GREEN));
    }

    private void handleGold(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol gold <montant>"); return; }
        int amount;
        try { amount = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) { player.sendMessage("§cMontant invalide."); return; }
        LolPlugin.getInstance().getGoldManager().addGold(player.getUniqueId(), amount);
        player.sendMessage(Component.text("✔ +" + amount + " or", NamedTextColor.GOLD));
    }

    private void handleTeamCmd(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol team <blue/red>"); return; }
        var team = parseTeam(args[1]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        LolPlugin.getInstance().getTeamManager().setTeam(player, team);
        var spawn = LolPlugin.getInstance().getMapManager().getSpawn(team, 1);
        if (spawn != null) player.teleport(spawn);
        player.sendMessage(Component.text("✔ Équipe: " + team.name(), NamedTextColor.GREEN));
    }

    private void handleTestGame(Player player) {
        var plugin = LolPlugin.getInstance();
        mapManager.resetAllStructures();
        plugin.getMinionManager().startWaves();
        plugin.getJungleManager().startJungle();
        plugin.getGameManager().startGame();
        plugin.getMatchScoreboard().startMatch(false);
        player.sendMessage(Component.text("🧪 Map lancée (structures, sbires, jungle, timer).", NamedTextColor.GREEN));
    }

    private void handleShopNpc(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol shopnpc <blue|red>"); return; }
        Team team = parseTeam(args[1]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        LolPlugin.getInstance().getShopNpcManager().spawnShopNpc(player.getLocation(), team);
        player.sendMessage(Component.text("✔ PNJ boutique " + team.name() + " créé à ta position.", NamedTextColor.GREEN));
    }

    private void handleMode(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol mode <ranked|normal>"); return; }
        boolean ranked = args[1].equalsIgnoreCase("ranked") || args[1].equalsIgnoreCase("classe");
        LolPlugin.getInstance().getMatchScoreboard().startMatch(ranked);
        player.sendMessage(Component.text("✔ Mode de partie : " + (ranked ? "CLASSÉ" : "AMICAL"), NamedTextColor.GREEN));
    }

    private void handleJungle(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /lol jungle <type> [blue|red]");
            return;
        }
        fr.lolmc.game.JungleManager.MonsterType type;
        try {
            type = fr.lolmc.game.JungleManager.MonsterType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text("❌ Type de monstre inconnu: " + args[1], NamedTextColor.RED));
            return;
        }

        boolean isEpic = type.isEpic() || type == fr.lolmc.game.JungleManager.MonsterType.SCUTTLE_CRAB;
        Team team = null;
        if (!isEpic) {
            if (args.length < 3) {
                player.sendMessage("§cCe camp nécessite une équipe: /lol jungle " + args[1] + " <blue|red>");
                return;
            }
            team = parseTeam(args[2]);
            if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        }

        pending.put(player.getUniqueId(), new PendingSetup("jungle", null, team, args[1].toUpperCase(), 0, 0));
        player.sendMessage(Component.text(String.format("👉 Clique au sol pour placer %s%s.", type.displayName, team != null ? " (" + team.name() + ")" : " (neutre)"), NamedTextColor.AQUA));
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
            player.sendMessage("§cUsage: /lol road <top|mid|bot> <blue|red> | /lol road end");
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
        player.sendMessage(Component.text(String.format("🖌 Trace la route de la lane %s. Clique les blocs, puis /lol road end.", lane), NamedTextColor.AQUA));
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

        if (laneSetup.containsKey(player.getUniqueId())) {
            e.setCancelled(true);
            Location wp = e.getClickedBlock().getLocation().add(0.5, 1, 0.5);
            laneSetup.get(player.getUniqueId()).add(wp);
            player.sendActionBar(Component.text("📍 Waypoint #" + laneSetup.get(player.getUniqueId()).size() + " ajouté.", NamedTextColor.YELLOW));
            return;
        }

        PendingSetup setup = pending.get(player.getUniqueId());
        if (setup == null) return;
        e.setCancelled(true);

        Location clicked = e.getClickedBlock().getLocation();

        if (setup.kind().equals("structure")) {
            int angle = 0;
            String facingMsg = "orientation par défaut (Sud)";
            var bd = e.getClickedBlock().getBlockData();
            if (bd instanceof org.bukkit.block.data.Directional dir) {
                angle = switch (dir.getFacing()) {
                    case SOUTH -> 0;
                    case WEST  -> 90;
                    case NORTH -> 180;
                    case EAST  -> 270;
                    default    -> 0;
                };
                facingMsg = "orientée vers " + dir.getFacing() + " (" + angle + "°)";
                e.getClickedBlock().setType(org.bukkit.Material.AIR);
            }
            mapManager.setStructure(setup.type(), setup.team(), setup.lane(), setup.index(), clicked, angle);
            var tier = pendingTier.remove(player.getUniqueId());
            if (tier != null) {
                mapManager.setStructureTier(setup.type(), setup.team(), setup.lane(), setup.index(), tier);
            }
            player.sendMessage(Component.text("   ↳ " + facingMsg, NamedTextColor.GRAY));
            showMarker(clicked.clone().add(0, 1, 0), setup.team());
            player.sendMessage(Component.text(String.format("✔ %s %s %s #%d placé.", setup.type().name().toLowerCase(), setup.team().name(), setup.lane(), setup.index()), NamedTextColor.GREEN));
        } else if (setup.kind().equals("jungle")) {
            var type = fr.lolmc.game.JungleManager.MonsterType.valueOf(setup.lane());
            LolPlugin.getInstance().getJungleManager().setCamp(type, setup.team(), clicked.clone().add(0.5, 1, 0.5));
            player.sendMessage(Component.text(String.format("✔ %s placé.", type.displayName), NamedTextColor.GREEN));
        } else if (setup.kind().equals("spawn")) {
            Location spawnLoc = clicked.clone().add(0.5, 1, 0.5);
            spawnLoc.setYaw(player.getLocation().getYaw());
            spawnLoc.setPitch(0);
            mapManager.setSpawn(setup.team(), setup.position(), spawnLoc);
            player.sendMessage(Component.text(String.format("✔ Spawn %s #%d défini.", setup.team().name(), setup.position()), NamedTextColor.GREEN));
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

    private Team parseTeam(String s) {
        return switch (s.toLowerCase()) {
            case "blue", "bleu" -> Team.BLUE;
            case "red", "rouge" -> Team.RED;
            default -> null;
        };
    }

    private void sendHelp(Player p) {
        p.sendMessage(Component.text("═══ Configuration LoL ═══", NamedTextColor.GOLD));
        p.sendMessage(Component.text("/lol set turret <blue|red> <top|mid|bot|base> <index>", NamedTextColor.AQUA));
        p.sendMessage(Component.text("/lol set nexus <blue|red> <top|mid|bot> <index>", NamedTextColor.AQUA));
        p.sendMessage(Component.text("/lol set basenexus <blue|red>", NamedTextColor.AQUA));
        p.sendMessage(Component.text("/lol position <blue|red> <1-5>", NamedTextColor.AQUA));
        p.sendMessage(Component.text("/lol lane <top|mid|bot> ... /lol lane done", NamedTextColor.AQUA));
        p.sendMessage(Component.text("/lol schem <pos1|pos2|save>", NamedTextColor.YELLOW)); // AJOUT : Aide textuelle
        p.sendMessage(Component.text("/lol start | /lol stop", NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("set", "position", "lane", "schem", "road", "jungle", "shopnpc", "mode", "select", "solo", "give", "level", "gold", "team", "testgame", "debug", "reload", "start", "stop"); // MODIFICATION : Injecté "schem"
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "set" -> List.of("turret", "inhibitor", "nexus", "basenexus");
                case "schem" -> List.of("pos1", "pos2", "save"); // AJOUT : Auto-complétion de l'action de schématique
                case "shopnpc" -> List.of("blue", "red");
                case "mode" -> List.of("ranked", "normal");
                case "position", "lane" -> List.of("blue", "red");
                case "road" -> List.of("top", "mid", "bot", "end");
                case "jungle" -> List.of("gromp", "murkwolf", "raptor", "krug", "red_buff", "blue_buff", "scuttle_crab", "dragon_infernal", "dragon_ocean", "dragon_mountain", "dragon_cloud", "dragon_chemtech", "dragon_elder", "herald", "baron");
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            if (args[1].equalsIgnoreCase("basenexus")) return List.of("blue", "red");
            return List.of("blue", "red");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("set")) return List.of("top", "mid", "bot", "base");
        return List.of();
    }
}