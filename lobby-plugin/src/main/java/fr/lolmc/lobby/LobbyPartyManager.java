package fr.lolmc.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/** Gestion des groupes dans le lobby (identique à PartyManager côté jeu). */
public class LobbyPartyManager {

    private final LobbyPlugin plugin;
    private final Map<UUID, UUID> playerToParty = new HashMap<>();  // uuid → leaderUUID
    private final Map<UUID, List<UUID>> parties = new HashMap<>();  // leaderUUID → membres
    private final Map<UUID, Long> pendingInvites = new HashMap<>(); // invitéUUID → expireMs

    public LobbyPartyManager(LobbyPlugin plugin) { this.plugin = plugin; }

    public void createParty(Player leader) {
        UUID lid = leader.getUniqueId();
        if (parties.containsKey(lid)) return;
        List<UUID> members = new ArrayList<>();
        members.add(lid);
        parties.put(lid, members);
        playerToParty.put(lid, lid);
        leader.sendMessage(Component.text("✔ Groupe créé!", NamedTextColor.GREEN));
    }

    public void invite(Player leader, Player target) {
        if (!isLeader(leader)) createParty(leader);
        UUID lid = playerToParty.get(leader.getUniqueId());
        List<UUID> members = parties.get(lid);
        if (members != null && members.size() >= plugin.getConfig().getInt("max-party-size", 5)) {
            leader.sendMessage(Component.text("Groupe plein!", NamedTextColor.RED)); return;
        }
        pendingInvites.put(target.getUniqueId(), System.currentTimeMillis() + 60_000L);
        target.sendMessage(Component.text(
            leader.getName() + " t'invite dans son groupe. /party accept", NamedTextColor.YELLOW));
    }

    public boolean accept(Player player) {
        Long exp = pendingInvites.remove(player.getUniqueId());
        if (exp == null || System.currentTimeMillis() > exp) {
            player.sendMessage(Component.text("Pas d'invitation active.", NamedTextColor.RED));
            return false;
        }
        // Trouver le leader qui a invité
        for (var entry : parties.entrySet()) {
            Player leader = Bukkit.getPlayer(entry.getKey());
            if (leader != null) {
                entry.getValue().add(player.getUniqueId());
                playerToParty.put(player.getUniqueId(), entry.getKey());
                player.sendMessage(Component.text("✔ Tu as rejoint le groupe de " + leader.getName(), NamedTextColor.GREEN));
                leader.sendMessage(Component.text("✔ " + player.getName() + " a rejoint le groupe!", NamedTextColor.GREEN));
                return true;
            }
        }
        return false;
    }

    public void leave(Player player) {
        UUID lid = playerToParty.remove(player.getUniqueId());
        if (lid == null) return;
        List<UUID> members = parties.get(lid);
        if (members != null) members.remove(player.getUniqueId());
        if (lid.equals(player.getUniqueId()) && members != null && !members.isEmpty()) {
            // Transférer le chef
            UUID newLeader = members.get(0);
            parties.put(newLeader, members);
            parties.remove(lid);
            for (UUID m : members) playerToParty.put(m, newLeader);
            Player newLeaderPlayer = Bukkit.getPlayer(newLeader);
            if (newLeaderPlayer != null)
                newLeaderPlayer.sendMessage(Component.text("Tu es maintenant le chef du groupe.", NamedTextColor.YELLOW));
        } else if (members != null && members.isEmpty()) {
            parties.remove(lid);
        }
        player.sendMessage(Component.text("Tu as quitté le groupe.", NamedTextColor.GRAY));
    }

    public boolean isLeader(Player player) {
        return playerToParty.getOrDefault(player.getUniqueId(), null) != null
            && playerToParty.get(player.getUniqueId()).equals(player.getUniqueId());
    }

    public List<UUID> getPartyMembers(UUID uuid) {
        UUID lid = playerToParty.get(uuid);
        if (lid == null) return List.of(uuid);
        return parties.getOrDefault(lid, List.of(uuid));
    }

    public void cleanup(UUID uuid) {
        UUID lid = playerToParty.get(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) leave(p);
        else { playerToParty.remove(uuid); if (lid != null) { var m = parties.get(lid); if (m != null) m.remove(uuid); } }
    }
}
