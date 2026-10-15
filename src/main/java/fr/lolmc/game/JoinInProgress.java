package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.instance.GameInstance;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * Rejoindre une partie déjà lancée ({@code /lola join [champion] [instance]}, admin).
 *
 * LoL ne permet pas de rejoindre une partie en cours : les règles de rattrapage sont donc propres
 * au plugin. Le joueur entre dans l'équipe la moins remplie (5 max), au niveau moyen de la partie,
 * avec 500 PO plus l'or passif gagné depuis 1:50 (comme un joueur resté à la base), sa page de
 * runes, Saut éclair + Embrasement (Châtiment pour un jungler) et la Balise camouflée.
 */
public final class JoinInProgress {

    private JoinInProgress() {}

    private static final int MAX_PER_TEAM = 5;

    public static void join(Player player, String championArg, String instanceArg) {
        var plugin = LolPlugin.getInstance();
        var cm = plugin.getChampionManager();
        UUID id = player.getUniqueId();

        // ── Partie visée ──
        GameInstance instance = null;
        GameManager gm;
        var im = plugin.getInstanceManager();
        if (im != null && !im.getAllInstances().isEmpty()) {
            for (GameInstance gi : im.getAllInstances()) {
                if (gi.getState() != GameInstance.State.RUNNING) continue;
                if (instanceArg != null && !instanceArg.equals(String.valueOf(gi.getId()))) continue;
                if (instance == null || gi.getParticipants().size() < instance.getParticipants().size()) instance = gi;
            }
            if (instance == null) { fail(player, "Aucune partie en cours" + (instanceArg != null ? " (instance " + instanceArg + ")" : "") + "."); return; }
            gm = instance.getGameManager();
        } else {
            gm = plugin.getGameManager();
            if (gm == null || !gm.isRunning()) { fail(player, "Aucune partie en cours."); return; }
        }
        if (gm.isParticipant(id)) { fail(player, "Tu fais déjà partie de cette partie."); return; }
        if (plugin.getChampSelectManager() != null && plugin.getChampSelectManager().isParticipant(id)) {
            fail(player, "Tu es en sélection des champions."); return;
        }

        // ── Champion ──
        if (championArg == null) {
            StringBuilder sb = new StringBuilder();
            for (var c : cm.getAllChampions()) {
                if (!canPlay(player, c.getId())) continue;
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(c.getId());
            }
            player.sendMessage(Component.text("Usage : /lola join <champion> [instance]", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Champions : " + sb, NamedTextColor.GRAY));
            return;
        }
        String champId = championArg.toLowerCase();
        if (cm.getPrototype(champId) == null) { fail(player, "Champion inconnu : " + championArg); return; }
        if (!canPlay(player, champId)) { fail(player, "Tu n'as pas accès à ce champion."); return; }

        // ── Équipe la moins remplie ──
        Collection<UUID> members = gm.getParticipants();
        int blue = 0, red = 0;
        for (UUID m : members) {
            Team t = gm.getParticipantTeam(m);
            if (t == Team.BLUE) blue++; else if (t == Team.RED) red++;
        }
        if (blue >= MAX_PER_TEAM && red >= MAX_PER_TEAM) { fail(player, "La partie est complète (5 contre 5)."); return; }
        Team team = blue <= red ? Team.BLUE : Team.RED;

        // ── Sortie des files d'attente ──
        var rq = plugin.getRoleQueueManager();
        if (rq != null && rq.isInQueue(id)) rq.leaveQueue(player);
        var mm = plugin.getMatchmakingManager();
        if (mm != null && mm.isInQueue(player)) mm.leaveQueue(player);

        // ── Équipe et téléportation dans le monde de la partie ──
        MapManager map;
        if (instance != null) {
            im.registerLateJoiner(instance, player, team);
            map = instance.getMapManager();
        } else {
            plugin.getTeamManager().setTeam(player, team);
            map = plugin.getMapManager();
        }
        Location spawn = map.getBaseSpawn(team);
        if (spawn == null) { fail(player, "Base de l'équipe introuvable (carte non configurée)."); return; }
        player.teleport(spawn);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);

        // ── Champion, objets, runes, sorts (comme à la fin de la sélection) ──
        cm.assignChampion(player, champId);
        var champ = cm.getChampion(player);
        if (champ == null) { fail(player, "Impossible d'assigner le champion."); return; }
        plugin.getShopListener().getOrCreate(player).clear();
        plugin.getShopListener().initPlayer(player); // 500 PO
        plugin.getHotbarManager().cleanup(id);
        plugin.getHotbarManager().addConsumable(player, "stealth_ward");
        if (plugin.getConsumableManager() != null) plugin.getConsumableManager().giveTrinket(player, "stealth_ward");

        // Niveau moyen de la partie (arrondi à l'inférieur) : points de compétence à répartir
        double sum = 0; int n = 0;
        for (UUID m : members) {
            Player o = org.bukkit.Bukkit.getPlayer(m);
            if (o == null || !cm.hasChampion(o)) continue;
            sum += cm.getChampion(o).getLevelSystem().getLevel();
            n++;
        }
        int level = n > 0 ? Math.max(1, (int) Math.floor(sum / n)) : 1;
        if (level > 1) {
            champ.getLevelSystem().setLevel(level);
            champ.onLevelChanged(level);
            champ.getHPSystem().setCurrentHP(champ.getHPSystem().getMaxHP());
            if (champ.getResourceSystem() != null) champ.getResourceSystem().setCurrent(champ.getResourceSystem().getMax());
        }
        // Or passif depuis 1:50 (20.4 PO / 10 s), comme un joueur resté à la base
        long elapsed = gm.getElapsedSeconds();
        int passive = (int) Math.floor(Math.max(0, elapsed - 110) * 2.04);
        if (passive > 0) plugin.getGoldManager().addGold(id, passive);

        var rm = plugin.getRuneManager();
        rm.setPage(id, rm.getPage(id));
        rm.applyRuneStats(player);
        var ssm = plugin.getSummonerSpellManager();
        boolean jungler = champ.getRole() == fr.lolmc.champion.base.BaseChampion.ChampionRole.JUNGLE;
        if (ssm != null) ssm.setSpells(player, "FLASH", jungler ? "SMITE" : "IGNITE");
        plugin.getSkinManager().applySkin(player, champId, "base");
        plugin.getHotbarManager().renderPage(player, champ);
        var hud = plugin.getHUDManager();
        if (hud != null) hud.initPlayer(player, champ);

        // ── Participant de la partie (reconnexion, timer, minimap, tab) ──
        gm.addLateJoiner(player, team);

        player.sendMessage(Component.text(String.format("⚔ Tu rejoins la partie : équipe %s, %s niveau %d, %d PO.",
                team == Team.BLUE ? "bleue" : "rouge", champ.getDisplayName(), level,
                plugin.getGoldManager().getGold(id)), NamedTextColor.GOLD));
        for (UUID m : members) {
            Player o = org.bukkit.Bukkit.getPlayer(m);
            if (o != null && !o.equals(player))
                o.sendMessage(Component.text("➕ " + player.getName() + " rejoint l'équipe "
                        + (team == Team.BLUE ? "bleue" : "rouge") + " avec " + champ.getDisplayName() + ".",
                        NamedTextColor.YELLOW));
        }
    }

    private static boolean canPlay(Player p, String champId) {
        return fr.lolmc.champion.ChampionAccess.canPlay(p, champId);
    }

    private static void fail(Player p, String msg) {
        p.sendMessage(Component.text("❌ " + msg, NamedTextColor.RED));
    }
}
