package fr.lolmc.team;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gère l'appartenance des joueurs aux deux équipes LoL : BLUE et RED.
 */
public class TeamManager {

    public enum Team {
        BLUE(NamedTextColor.BLUE, Color.fromRGB(60, 120, 255)),
        RED(NamedTextColor.RED,  Color.fromRGB(255, 60, 60));

        public final NamedTextColor chatColor;
        public final Color particleColor;

        Team(NamedTextColor chatColor, Color particleColor) {
            this.chatColor = chatColor;
            this.particleColor = particleColor;
        }

        public Team opposite() {
            return this == BLUE ? RED : BLUE;
        }

        /** Bloc coloré correspondant à l'équipe (remplace l'ancien dust() de particules). */
        public org.bukkit.Material blockColor() {
            return this == BLUE ? org.bukkit.Material.BLUE_STAINED_GLASS : org.bukkit.Material.RED_STAINED_GLASS;
        }
    }

    private final Map<UUID, Team> playerTeams = new HashMap<>();

    // ── Attribution ───────────────────────────────────────────────

    public void setTeam(Player player, Team team) {
        playerTeams.put(player.getUniqueId(), team);
    }

    public Team getTeam(Player player) {
        return playerTeams.get(player.getUniqueId());
    }

    public boolean hasTeam(Player player) {
        return playerTeams.containsKey(player.getUniqueId());
    }

    public void removePlayer(UUID uuid) {
        playerTeams.remove(uuid);
    }

    /**
     * Assigne automatiquement le joueur à l'équipe la moins remplie.
     */
    public Team autoAssign(Player player) {
        // Équilibrage au sein du même monde (= de la même partie) uniquement
        long blue = getTeamMembers(Team.BLUE, player.getWorld()).stream().filter(id -> !id.equals(player.getUniqueId())).count();
        long red  = getTeamMembers(Team.RED,  player.getWorld()).stream().filter(id -> !id.equals(player.getUniqueId())).count();
        Team team = (blue <= red) ? Team.BLUE : Team.RED;
        setTeam(player, team);
        return team;
    }

    // ── Relations ─────────────────────────────────────────────────

    /** true si les deux joueurs sont dans la même équipe. */
    public boolean areAllies(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) return false; // parties différentes
        Team ta = getTeam(a), tb = getTeam(b);
        return ta != null && ta == tb;
    }

    /** true si les deux joueurs sont dans des équipes opposées. */
    public boolean areEnemies(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) return false; // parties différentes
        Team ta = getTeam(a), tb = getTeam(b);
        return ta != null && tb != null && ta != tb;
    }

    /**
     * Joueurs en ligne d'une équipe qui se trouvent dans ce monde — permet à deux parties
     * simultanées (deux mondes d'instance) d'avoir chacune une équipe BLEUE et ROUGE distinctes.
     */
    public Set<UUID> getTeamMembers(Team team, org.bukkit.World world) {
        Set<UUID> members = new HashSet<>();
        if (world == null) return getTeamMembers(team);
        for (var e : playerTeams.entrySet()) {
            if (e.getValue() != team) continue;
            Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline() && p.getWorld().equals(world)) members.add(e.getKey());
        }
        return members;
    }

    /** Tous les joueurs en ligne d'une équipe donnée. */
    public Set<UUID> getTeamMembers(Team team) {
        Set<UUID> members = new HashSet<>();
        for (var e : playerTeams.entrySet()) {
            if (e.getValue() == team) members.add(e.getKey());
        }
        return members;
    }
}