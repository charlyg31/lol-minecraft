package fr.lolmc.bungee.party;

import fr.lolmc.bungee.LolBungeePlugin;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BungeePartyManager {

    private final LolBungeePlugin plugin;
    private final Map<UUID, UUID>       playerToLeader = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> parties        = new ConcurrentHashMap<>();
    private final Map<UUID, UUID>       pendingInvites = new ConcurrentHashMap<>(); // invité → leader

    public BungeePartyManager(LolBungeePlugin plugin) { this.plugin = plugin; }

    public void createParty(ProxiedPlayer leader) {
        UUID lid = leader.getUniqueId();
        if (parties.containsKey(lid)) return;
        List<UUID> members = Collections.synchronizedList(new ArrayList<>());
        members.add(lid);
        parties.put(lid, members);
        playerToLeader.put(lid, lid);
        send(leader, "§a✔ Groupe créé !");
    }

    public void invite(ProxiedPlayer leader, ProxiedPlayer target) {
        if (!isLeader(leader)) createParty(leader);
        UUID lid = playerToLeader.get(leader.getUniqueId());
        List<UUID> members = parties.get(lid);
        if (members != null && members.size() >= plugin.getConfig().getInt("max-party-size", 5)) {
            send(leader, "§cGroupe plein !"); return;
        }
        pendingInvites.put(target.getUniqueId(), lid);
        send(target, "§e" + leader.getName() + " t'invite. Tape §b/lol party accept");
        send(leader, "§7Invitation envoyée à §f" + target.getName());
    }

    public boolean accept(ProxiedPlayer player) {
        UUID lid = pendingInvites.remove(player.getUniqueId());
        if (lid == null) { send(player, "§cPas d'invitation en cours."); return false; }
        List<UUID> members = parties.get(lid);
        if (members == null) { send(player, "§cLe groupe n'existe plus."); return false; }
        members.add(player.getUniqueId());
        playerToLeader.put(player.getUniqueId(), lid);
        ProxiedPlayer leader = ProxyServer.getInstance().getPlayer(lid);
        send(player, "§a✔ Rejoint le groupe de §f" + (leader != null ? leader.getName() : lid.toString().substring(0,8)));
        if (leader != null) send(leader, "§a§f" + player.getName() + " §aa rejoint le groupe !");
        return true;
    }

    public void leave(ProxiedPlayer player) {
        UUID uid = player.getUniqueId();
        UUID lid = playerToLeader.remove(uid);
        if (lid == null) return;
        List<UUID> members = parties.get(lid);
        if (members != null) members.remove(uid);
        if (lid.equals(uid) && members != null && !members.isEmpty()) {
            UUID newLid = members.get(0);
            List<UUID> newMembers = new ArrayList<>(members);
            parties.remove(lid);
            parties.put(newLid, newMembers);
            for (UUID m : newMembers) playerToLeader.put(m, newLid);
            ProxiedPlayer newLeader = ProxyServer.getInstance().getPlayer(newLid);
            if (newLeader != null) send(newLeader, "§eTu es maintenant le chef du groupe.");
        } else if (members != null && members.isEmpty()) {
            parties.remove(lid);
        }
        send(player, "§7Tu as quitté le groupe.");
    }

    public boolean isLeader(ProxiedPlayer p) {
        return p.getUniqueId().equals(playerToLeader.get(p.getUniqueId()));
    }

    public List<UUID> getPartyMembers(UUID uuid) {
        UUID lid = playerToLeader.get(uuid);
        if (lid == null) return List.of(uuid);
        return Collections.unmodifiableList(parties.getOrDefault(lid, List.of(uuid)));
    }

    public void cleanup(UUID uuid) {
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
        if (p != null) leave(p);
        else {
            UUID lid = playerToLeader.remove(uuid);
            if (lid != null) { var m = parties.get(lid); if (m != null) m.remove(uuid); }
            pendingInvites.remove(uuid);
        }
    }

    private void send(ProxiedPlayer p, String msg) {
        p.sendMessage(new TextComponent(msg));
    }
}
