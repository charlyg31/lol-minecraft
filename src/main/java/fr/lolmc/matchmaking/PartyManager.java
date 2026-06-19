package fr.lolmc.matchmaking;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Gère les groupes (parties pré-faites) de 1 à 5 joueurs.
 * Un groupe a un chef (celui qui l'a créé) et des membres.
 * Les groupes entrent en file d'attente ensemble.
 */
public class PartyManager {

    public static final int MAX_PARTY_SIZE = 5;

    public static class Party {
        public final UUID leader;
        public final List<UUID> members = new ArrayList<>();
        // Invitations en attente : invité → expiration
        public final Map<UUID, Long> pendingInvites = new HashMap<>();

        public Party(UUID leader) {
            this.leader = leader;
            this.members.add(leader);
        }

        public boolean isFull() { return members.size() >= MAX_PARTY_SIZE; }
        public int size()       { return members.size(); }
    }

    // Chaque joueur → l'ID de son groupe (= UUID du chef)
    private final Map<UUID, UUID> playerToParty = new HashMap<>();
    // ID groupe → Party
    private final Map<UUID, Party> parties = new HashMap<>();

    private static final long INVITE_TIMEOUT_MS = 60_000L;

    // ── Création / dissolution ────────────────────────────────────

    /** Retourne le groupe du joueur, en créant un groupe solo si nécessaire. */
    public Party getOrCreateParty(Player player) {
        UUID partyId = playerToParty.get(player.getUniqueId());
        if (partyId != null && parties.containsKey(partyId)) {
            return parties.get(partyId);
        }
        Party party = new Party(player.getUniqueId());
        parties.put(player.getUniqueId(), party);
        playerToParty.put(player.getUniqueId(), player.getUniqueId());
        return party;
    }

    public Party getParty(Player player) {
        UUID partyId = playerToParty.get(player.getUniqueId());
        return partyId != null ? parties.get(partyId) : null;
    }

    public boolean isLeader(Player player) {
        Party p = getParty(player);
        return p != null && p.leader.equals(player.getUniqueId());
    }

    // ── Invitations ───────────────────────────────────────────────

    public boolean invite(Player leader, Player target) {
        Party party = getOrCreateParty(leader);
        if (!party.leader.equals(leader.getUniqueId())) {
            leader.sendMessage(Component.text("❌ Seul le chef peut inviter.", NamedTextColor.RED));
            return false;
        }
        if (party.isFull()) {
            leader.sendMessage(Component.text("❌ Groupe plein (5/5).", NamedTextColor.RED));
            return false;
        }
        if (getParty(target) != null && getParty(target).size() > 1) {
            leader.sendMessage(Component.text("❌ Ce joueur est déjà dans un groupe.", NamedTextColor.RED));
            return false;
        }
        party.pendingInvites.put(target.getUniqueId(), System.currentTimeMillis() + INVITE_TIMEOUT_MS);
        leader.sendMessage(Component.text("✔ Invitation envoyée à " + target.getName(), NamedTextColor.GREEN));
        target.sendMessage(Component.text(leader.getName() + " t'invite dans son groupe. ", NamedTextColor.YELLOW)
                .append(Component.text("/party accept", NamedTextColor.GREEN))
                .append(Component.text(" pour rejoindre.", NamedTextColor.YELLOW)));
        return true;
    }

    public boolean acceptInvite(Player target) {
        // Chercher un groupe qui a invité ce joueur
        long now = System.currentTimeMillis();
        for (Party party : parties.values()) {
            Long expire = party.pendingInvites.get(target.getUniqueId());
            if (expire != null && now < expire) {
                if (party.isFull()) {
                    target.sendMessage(Component.text("❌ Le groupe est maintenant plein.", NamedTextColor.RED));
                    return false;
                }
                // Quitter l'ancien groupe solo
                leaveParty(target, false);
                party.members.add(target.getUniqueId());
                party.pendingInvites.remove(target.getUniqueId());
                playerToParty.put(target.getUniqueId(), party.leader);
                broadcastToParty(party, Component.text(target.getName() + " a rejoint le groupe! ("
                        + party.size() + "/5)", NamedTextColor.GREEN));
                return true;
            }
        }
        target.sendMessage(Component.text("❌ Aucune invitation en attente.", NamedTextColor.RED));
        return false;
    }

    public void leaveParty(Player player, boolean notify) {
        UUID partyId = playerToParty.remove(player.getUniqueId());
        if (partyId == null) return;
        Party party = parties.get(partyId);
        if (party == null) return;

        party.members.remove(player.getUniqueId());
        if (notify) broadcastToParty(party, Component.text(player.getName() + " a quitté le groupe.", NamedTextColor.GRAY));

        // Si le chef part, dissoudre ou transférer
        if (party.leader.equals(player.getUniqueId())) {
            if (party.members.isEmpty()) {
                parties.remove(partyId);
            } else {
                // Transférer le lead au premier membre restant
                UUID newLeader = party.members.get(0);
                Party newParty = new Party(newLeader);
                newParty.members.clear();
                newParty.members.addAll(party.members);
                parties.remove(partyId);
                parties.put(newLeader, newParty);
                for (UUID m : newParty.members) playerToParty.put(m, newLeader);
                Player nl = Bukkit.getPlayer(newLeader);
                if (nl != null) nl.sendMessage(Component.text("👑 Tu es le nouveau chef du groupe.", NamedTextColor.GOLD));
            }
        }
        // Si le groupe solo restant est vide
        if (party.members.isEmpty()) parties.remove(partyId);
    }

    // ── Helpers ───────────────────────────────────────────────────

    public List<UUID> getMembers(Player player) {
        Party p = getParty(player);
        return p != null ? new ArrayList<>(p.members) : List.of(player.getUniqueId());
    }

    private void broadcastToParty(Party party, Component msg) {
        for (UUID id : party.members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(msg);
        }
    }

    public void cleanup(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) leaveParty(p, true);
        else {
            UUID partyId = playerToParty.remove(uuid);
            if (partyId != null) {
                Party party = parties.get(partyId);
                if (party != null) {
                    party.members.remove(uuid);
                    if (party.members.isEmpty()) parties.remove(partyId);
                }
            }
        }
    }
}
