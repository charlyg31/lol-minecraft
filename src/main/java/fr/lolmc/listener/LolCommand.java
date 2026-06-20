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
            case "select" -> {
                // Lance une sélection avec tous les joueurs en ligne (test/manuel)
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
                // Si aucun mode défini, démarrer en amical par défaut
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

    // ── /lol set ──────────────────────────────────────────────────

    private void handleSet(Player player, String[] args) {
        // /lol set turret top 1  |  /lol set nexus mid 1  |  /lol set basenexus
        if (args.length < 2) { player.sendMessage("§cUsage: /lol set <turret|nexus|basenexus> ..."); return; }
        String what = args[1].toLowerCase();

        if (what.equals("basenexus")) {
            // Nexus principal — on demande l'équipe
            if (args.length < 3) { player.sendMessage("§cUsage: /lol set basenexus <blue|red>"); return; }
            Team team = parseTeam(args[2]);
            if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
            pending.put(player.getUniqueId(), new PendingSetup("structure", Type.NEXUS_BASE, team, "base", 1, 0));
            player.sendMessage(Component.text("👉 Clique sur la case centrale du Nexus principal ("
                    + team.name() + ").", NamedTextColor.AQUA));
            return;
        }

        // turret / nexus : /lol set turret <blue|red> <top|mid|bot|base> <index>
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

        // Niveau de tourelle optionnel (5e argument) : outer/inner/inhibitor/nexus
        fr.lolmc.game.GameStructure.TurretTier tier = fr.lolmc.game.GameStructure.TurretTier.OUTER;
        if (type == Type.TURRET && args.length >= 6) {
            try {
                tier = fr.lolmc.game.GameStructure.TurretTier.valueOf(args[5].toUpperCase());
            } catch (IllegalArgumentException ex) {
                player.sendMessage("§cNiveau invalide. Utilise: outer, inner, inhibitor, nexus");
                return;
            }
        }
        // Stocker le tier dans le PendingSetup (réutilise le champ position pour l'ordinal)
        pendingTier.put(player.getUniqueId(), tier);
        pending.put(player.getUniqueId(), new PendingSetup("structure", type, team, lane, index, 0));
        player.sendMessage(Component.text(String.format(
                "👉 Clique sur la case centrale de %s %s %s #%d.",
                type.name().toLowerCase(), team.name(), lane, index), NamedTextColor.AQUA));
    }

    // ── /lol position ─────────────────────────────────────────────

    private void handlePosition(Player player, String[] args) {
        // /lol position blue 1
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





    // ══════════════════════════════════════════════════════════════
    // COMMANDES DE TEST ADMIN (mode solo)
    // ══════════════════════════════════════════════════════════════

    /** /lol solo <champion> : met l'admin en jeu, équipe BLEUE, avec un champion, et lance tout. */
    private void handleSolo(Player player, String[] args) {
        String champId = args.length >= 2 ? args[1].toLowerCase() : "garen";
        var plugin = LolPlugin.getInstance();

        // 1. Équipe bleue
        plugin.getTeamManager().setTeam(player, fr.lolmc.team.TeamManager.Team.BLUE);
        // 2. Champion
        plugin.getChampionManager().assignChampion(player, champId);
        // 3. Page de runes par défaut
        plugin.getRuneManager().applyRuneStats(player);
        // 4. Lancer la partie complète
        mapManager.resetAllStructures();
        plugin.getMinionManager().startWaves();
        plugin.getJungleManager().startJungle();
        plugin.getGameManager().startGame();
        plugin.getMatchScoreboard().startMatch(false);
        // 5. Téléporter au spawn bleu si défini
        var spawn = plugin.getMapManager().getSpawn(fr.lolmc.team.TeamManager.Team.BLUE, 1);
        if (spawn != null) player.teleport(spawn);

        player.sendMessage(Component.text("🧪 MODE SOLO lancé!", NamedTextColor.GREEN));
        player.sendMessage(Component.text("Équipe BLEUE • Champion: " + champId, NamedTextColor.AQUA));
        player.sendMessage(Component.text("Commandes utiles: /lol give <champ>, /lol level <n>, /lol gold <n>, /lol team <blue/red>, /lol stop", NamedTextColor.GRAY));
    }

    /** /lol give <champion> : change le champion de l'admin instantanément. */
    private void handleGive(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol give <champion>"); return; }
        String champId = args[1].toLowerCase();
        LolPlugin.getInstance().getChampionManager().assignChampion(player, champId);
        LolPlugin.getInstance().getRuneManager().applyRuneStats(player);
        player.sendMessage(Component.text("✔ Champion: " + champId, NamedTextColor.GREEN));
    }

    /** /lol level <n> : met le champion de l'admin au niveau voulu (1-18). */
    private void handleLevel(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol level <1-18>"); return; }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) { player.sendMessage("§cTu n'as pas de champion (utilise /lol solo)."); return; }
        int lvl;
        try { lvl = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) { player.sendMessage("§cNiveau invalide."); return; }
        lvl = Math.max(1, Math.min(18, lvl));
        var champ = cm.getChampion(player);
        champ.getLevelSystem().setLevel(lvl);
        champ.getStats().setChampionLevel(lvl);
        player.sendMessage(Component.text("✔ Niveau " + lvl, NamedTextColor.GREEN));
    }

    /** /lol gold <n> : donne de l'or à l'admin. */
    private void handleGold(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol gold <montant>"); return; }
        int amount;
        try { amount = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) { player.sendMessage("§cMontant invalide."); return; }
        LolPlugin.getInstance().getGoldManager().addGold(player.getUniqueId(), amount);
        player.sendMessage(Component.text("✔ +" + amount + " or", NamedTextColor.GREEN));
    }

    /** /lol team <blue/red> : change l'admin d'équipe (pour tester les deux côtés). */
    private void handleTeamCmd(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§cUsage: /lol team <blue/red>"); return; }
        var team = parseTeam(args[1]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        LolPlugin.getInstance().getTeamManager().setTeam(player, team);
        var spawn = LolPlugin.getInstance().getMapManager().getSpawn(team, 1);
        if (spawn != null) player.teleport(spawn);
        player.sendMessage(Component.text("✔ Équipe: " + team.name(), NamedTextColor.GREEN));
    }

    /** /lol testgame : lance la map (structures+sbires+jungle) sans toucher au joueur. */
    private void handleTestGame(Player player) {
        var plugin = LolPlugin.getInstance();
        mapManager.resetAllStructures();
        plugin.getMinionManager().startWaves();
        plugin.getJungleManager().startJungle();
        plugin.getGameManager().startGame();
        plugin.getMatchScoreboard().startMatch(false);
        player.sendMessage(Component.text("🧪 Map lancée (structures, sbires, jungle, timer).", NamedTextColor.GREEN));
    }

    // ── /lol shopnpc ──────────────────────────────────────────────

    private void handleShopNpc(Player player, String[] args) {
        // /lol shopnpc <blue|red>
        if (args.length < 2) { player.sendMessage("§cUsage: /lol shopnpc <blue|red>"); return; }
        Team team = parseTeam(args[1]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        LolPlugin.getInstance().getShopNpcManager().spawnShopNpc(player.getLocation(), team);
        player.sendMessage(Component.text("✔ PNJ boutique " + team.name() + " créé à ta position.",
                NamedTextColor.GREEN));
    }

    // ── /lol mode ─────────────────────────────────────────────────

    private void handleMode(Player player, String[] args) {
        // /lol mode <ranked|normal>
        if (args.length < 2) { player.sendMessage("§cUsage: /lol mode <ranked|normal>"); return; }
        boolean ranked = args[1].equalsIgnoreCase("ranked") || args[1].equalsIgnoreCase("classe");
        LolPlugin.getInstance().getMatchScoreboard().startMatch(ranked);
        player.sendMessage(Component.text("✔ Mode de partie : "
                + (ranked ? "CLASSÉ (compte dans l'Elo)" : "AMICAL (hors classement)"),
                NamedTextColor.GREEN));
    }

    // ── /lol jungle ───────────────────────────────────────────────

    private void handleJungle(Player player, String[] args) {
        // /lol jungle <type> [blue|red]
        // Types neutres: gromp, murkwolf, raptor, krug, red_buff, blue_buff
        // Épiques (pas d'équipe): dragon, baron, herald
        if (args.length < 2) {
            player.sendMessage("§cUsage: /lol jungle <type> [blue|red]");
            player.sendMessage("§7Camps: gromp, murkwolf, raptor, krug, red_buff, blue_buff");
            player.sendMessage("§7Neutres: scuttle_crab, herald, baron");
            player.sendMessage("§7Dragons: dragon_infernal, dragon_ocean, dragon_mountain, dragon_cloud, dragon_chemtech, dragon_elder");
            return;
        }
        fr.lolmc.game.JungleManager.MonsterType type;
        try {
            type = fr.lolmc.game.JungleManager.MonsterType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text("❌ Type de monstre inconnu: " + args[1], NamedTextColor.RED));
            return;
        }

        // Les épiques sont neutres, les autres ont besoin d'une équipe
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
        player.sendMessage(Component.text(String.format(
                "👉 Clique au sol pour placer %s%s.",
                type.displayName, team != null ? " (" + team.name() + ")" : " (neutre)"),
                NamedTextColor.AQUA));
    }

    // ── /lol road ─────────────────────────────────────────────────

    private void handleRoad(Player player, String[] args) {
        // /lol road end  → terminer
        if (args.length >= 2 && args[1].equalsIgnoreCase("end")) {
            if (!roadManager.isPainting(player.getUniqueId())) {
                player.sendMessage(Component.text("❌ Aucune route en cours de tracé.", NamedTextColor.RED));
                return;
            }
            int count = roadManager.finishPainting(player.getUniqueId());
            // Retirer l'outil de peinture
            player.getInventory().remove(Material.GOLDEN_HOE);
            player.sendMessage(Component.text(String.format(
                    "✔ Route enregistrée (%d points de passage). Les blocs sont restaurés.", count),
                    NamedTextColor.GREEN));
            return;
        }

        // /lol road <lane> <blue|red>
        if (args.length < 3) {
            player.sendMessage("§cUsage: /lol road <top|mid|bot> <blue|red> | /lol road end");
            return;
        }
        String lane = args[1].toLowerCase();
        String teamHint = args[2].toLowerCase();
        if (!teamHint.equals("blue") && !teamHint.equals("red")
                && !teamHint.equals("bleu") && !teamHint.equals("rouge")) {
            player.sendMessage("§cÉquipe: blue ou red");
            return;
        }

        roadManager.startPainting(player.getUniqueId(), lane, teamHint);
        // Donner l'outil de peinture
        var tool = new org.bukkit.inventory.ItemStack(Material.GOLDEN_HOE);
        var meta = tool.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("🖌 Pinceau de route — " + lane,
                    NamedTextColor.GREEN).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(
                Component.text("Clique sur les blocs pour tracer le chemin.", NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("/lol road end pour terminer.", NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            tool.setItemMeta(meta);
        }
        player.getInventory().addItem(tool);
        player.sendMessage(Component.text(String.format(
                "🖌 Trace la route de la lane %s (sens %s). Clique les blocs, puis /lol road end.",
                lane, teamHint), NamedTextColor.AQUA));
    }

    // ── Clic au sol ───────────────────────────────────────────────

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        // Mode peinture de route (pinceau)
        if (roadManager.isPainting(player.getUniqueId())) {
            // Seulement si le joueur tient le pinceau
            if (player.getInventory().getItemInMainHand().getType() == Material.GOLDEN_HOE) {
                e.setCancelled(true);
                if (roadManager.paintBlock(player.getUniqueId(), e.getClickedBlock())) {
                    player.sendActionBar(Component.text("🖌 Bloc peint", NamedTextColor.GREEN));
                }
                return;
            }
        }

        // Mode lane : ajouter un waypoint
        if (laneSetup.containsKey(player.getUniqueId())) {
            e.setCancelled(true);
            Location wp = e.getClickedBlock().getLocation().add(0.5, 1, 0.5);
            laneSetup.get(player.getUniqueId()).add(wp);
            int count = laneSetup.get(player.getUniqueId()).size();
            player.sendActionBar(Component.text("📍 Waypoint #" + count + " ajouté.", NamedTextColor.YELLOW));
            return;
        }

        // Mode structure/spawn
        PendingSetup setup = pending.get(player.getUniqueId());
        if (setup == null) return;
        e.setCancelled(true);

        Location clicked = e.getClickedBlock().getLocation();

        if (setup.kind().equals("structure")) {
            mapManager.setStructure(setup.type(), setup.team(), setup.lane(), setup.index(), clicked);
            // Appliquer le niveau de tourelle si défini
            var tier = pendingTier.remove(player.getUniqueId());
            if (tier != null) {
                mapManager.setStructureTier(setup.type(), setup.team(), setup.lane(), setup.index(), tier);
            }
            // Marqueur visuel temporaire (bloc de verre coloré)
            showMarker(clicked.clone().add(0, 1, 0), setup.team());
            player.sendMessage(Component.text(String.format(
                    "✔ %s %s %s #%d placé en %d,%d,%d.",
                    setup.type().name().toLowerCase(), setup.team().name(), setup.lane(), setup.index(),
                    clicked.getBlockX(), clicked.getBlockY(), clicked.getBlockZ()), NamedTextColor.GREEN));
        } else if (setup.kind().equals("jungle")) {
            var type = fr.lolmc.game.JungleManager.MonsterType.valueOf(setup.lane());
            Location campLoc = clicked.clone().add(0.5, 1, 0.5);
            LolPlugin.getInstance().getJungleManager().setCamp(type, setup.team(), campLoc);
            player.sendMessage(Component.text(String.format(
                    "✔ %s placé en %d,%d,%d.", type.displayName,
                    clicked.getBlockX(), clicked.getBlockY(), clicked.getBlockZ()), NamedTextColor.GREEN));
        } else if (setup.kind().equals("spawn")) {
            Location spawnLoc = clicked.clone().add(0.5, 1, 0.5);
            spawnLoc.setYaw(player.getLocation().getYaw());
            spawnLoc.setPitch(0);
            mapManager.setSpawn(setup.team(), setup.position(), spawnLoc);
            player.sendMessage(Component.text(String.format(
                    "✔ Spawn %s #%d défini.", setup.team().name(), setup.position()), NamedTextColor.GREEN));
        }

        pending.remove(player.getUniqueId());
    }

    private void showMarker(Location loc, Team team) {
        Material mat = (team == Team.BLUE) ? Material.BLUE_STAINED_GLASS : Material.RED_STAINED_GLASS;
        Material original = loc.getBlock().getType();
        loc.getBlock().setType(mat);
        // Retirer le marqueur après 10s
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (loc.getBlock().getType() == mat) loc.getBlock().setType(original);
            }
        }.runTaskLater(LolPlugin.getInstance(), 200L);
    }

    // ── Helpers ───────────────────────────────────────────────────

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
        p.sendMessage(Component.text("/lol start | /lol stop", NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("set", "position", "lane", "road", "jungle", "shopnpc", "mode", "select", "solo", "give", "level", "gold", "team", "testgame", "start", "stop");
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "set" -> List.of("turret", "inhibitor", "nexus", "basenexus");
                case "shopnpc" -> List.of("blue", "red");
                case "mode" -> List.of("ranked", "normal");
                case "position", "lane" -> List.of("blue", "red");
                case "road" -> List.of("top", "mid", "bot", "end");
                case "jungle" -> List.of("gromp", "murkwolf", "raptor", "krug", "red_buff", "blue_buff",
                        "scuttle_crab", "dragon_infernal", "dragon_ocean", "dragon_mountain",
                        "dragon_cloud", "dragon_chemtech", "dragon_elder", "herald", "baron");
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
