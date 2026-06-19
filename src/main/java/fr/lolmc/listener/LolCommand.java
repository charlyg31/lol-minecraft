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

    // Mode setup en attente d'un clic, par joueur
    private final Map<UUID, PendingSetup> pending = new HashMap<>();
    // Waypoints en cours de définition (mode lane)
    private final Map<UUID, List<Location>> laneSetup = new HashMap<>();
    private final Map<UUID, String> laneSetupName = new HashMap<>();

    private record PendingSetup(String kind, Type type, Team team, String lane, int index, int position) {}

    public LolCommand(MapManager mapManager) {
        this.mapManager = mapManager;
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
            case "start" -> {
                player.sendMessage(Component.text("⚔ Lancement de la partie...", NamedTextColor.GOLD));
                mapManager.resetAllStructures();
                LolPlugin.getInstance().getMinionManager().startWaves();
                player.sendMessage(Component.text("✔ Structures réinitialisées, vagues de sbires lancées!", NamedTextColor.GREEN));
            }
            case "stop" -> {
                LolPlugin.getInstance().getMinionManager().stopWaves();
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
        Type type = what.equals("turret") ? Type.TURRET : Type.NEXUS;
        Team team = parseTeam(args[2]);
        if (team == null) { player.sendMessage("§cÉquipe: blue ou red"); return; }
        String lane = args[3].toLowerCase();
        int index;
        try { index = Integer.parseInt(args[4]); }
        catch (NumberFormatException e) { player.sendMessage("§cIndex invalide."); return; }

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

    // ── Clic au sol ───────────────────────────────────────────────

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

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
            // Marqueur visuel temporaire (bloc de verre coloré)
            showMarker(clicked.clone().add(0, 1, 0), setup.team());
            player.sendMessage(Component.text(String.format(
                    "✔ %s %s %s #%d placé en %d,%d,%d.",
                    setup.type().name().toLowerCase(), setup.team().name(), setup.lane(), setup.index(),
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
        if (args.length == 1) return List.of("set", "position", "lane", "start", "stop");
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "set" -> List.of("turret", "nexus", "basenexus");
                case "position", "lane" -> List.of("blue", "red");
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
