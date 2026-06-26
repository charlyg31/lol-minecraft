package fr.lolmc.bungee.party;

import fr.lolmc.bungee.LolBungeePlugin;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Gestion des groupes cross-serveur depuis le proxy.
 *
 * Logique de rôles pour /lol en groupe :
 *
 *   Chaque membre choisit ses rôles souhaités via /lol <rôle1> <rôle2>...
 *   Le groupe est prêt à jouer quand TOUS les membres ont choisi leurs rôles
 *   ET qu'il n'y a pas de conflit (assez de postes couverts).
 *
 *   Règles de conflit :
 *   - Si le groupe fait 5 : chaque poste doit être couvert par au moins 1 membre
 *   - Si le groupe fait 2-4 : le nombre de postes distincts couverts doit être
 *     >= taille du groupe (sinon certains membres ne peuvent pas jouer)
 *
 *   Exemple (3 membres font /lol mid top) :
 *     Postes distincts = {mid, top} = 2, groupe = 3 → conflit → erreur
 *
 *   Exemple (3 membres font /lol mid top, jungle adc, support) :
 *     Postes distincts = {mid, top, jungle, adc, support} = 5 → OK
 */
public class BungeePartyManager {

    private final LolBungeePlugin plugin;

    // uuid → uuid du chef
    private final Map<UUID, UUID> playerToLeader = new ConcurrentHashMap<>();
    // uuid chef → liste des membres (chef en tête)
    private final Map<UUID, List<UUID>> parties = new ConcurrentHashMap<>();
    // invité → uuid du chef qui a invité
    private final Map<UUID, UUID> pendingInvites = new ConcurrentHashMap<>();
    // uuid → rôles choisis pour la prochaine game (en MAJUSCULES)
    private final Map<UUID, List<String>> memberRoles = new ConcurrentHashMap<>();
    // uuid → true si prêt (a fait /lol <rôles>)
    private final Map<UUID, Boolean> ready = new ConcurrentHashMap<>();

    public BungeePartyManager(LolBungeePlugin plugin) { this.plugin = plugin; }

    // ══════════════════════════════════════════════════════════════════════
    // CRÉATION / INVITATION
    // ══════════════════════════════════════════════════════════════════════

    public void createParty(ProxiedPlayer leader) {
        UUID lid = leader.getUniqueId();
        if (inParty(lid)) return;
        List<UUID> members = Collections.synchronizedList(new ArrayList<>());
        members.add(lid);
        parties.put(lid, members);
        playerToLeader.put(lid, lid);
        send(leader, "§6§l⚔ Groupe créé ! Tu en es le chef.");
        send(leader, "§7Invite des joueurs : §e/lol party invite <joueur>");
    }

    public void invite(ProxiedPlayer leader, ProxiedPlayer target) {
        if (!inParty(leader.getUniqueId())) createParty(leader);
        if (!isLeader(leader)) { send(leader, "§cSeul le chef peut inviter."); return; }

        UUID lid = playerToLeader.get(leader.getUniqueId());
        List<UUID> members = parties.get(lid);
        int maxSize = plugin.getConfig().getInt("max-party-size", 5);
        if (members != null && members.size() >= maxSize) {
            send(leader, "§cGroupe plein ! (" + maxSize + "/" + maxSize + ")"); return;
        }
        if (inParty(target.getUniqueId())) {
            send(leader, "§c" + target.getName() + " est déjà dans un groupe."); return;
        }
        pendingInvites.put(target.getUniqueId(), lid);
        send(target, "§6⚔ §e" + leader.getName() + " §7t'invite dans son groupe LoL.");
        send(target, "§7Tape §a/lol party accept §7pour rejoindre, §c/lol party decline §7pour refuser.");
        send(leader, "§7Invitation envoyée à §f" + target.getName() + "§7.");
        // Expiration de l'invitation après 60s
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            if (pendingInvites.remove(target.getUniqueId(), lid)) {
                send(leader, "§7L'invitation pour §f" + target.getName() + " §7a expiré.");
            }
        }, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    public boolean accept(ProxiedPlayer player) {
        UUID lid = pendingInvites.remove(player.getUniqueId());
        if (lid == null) { send(player, "§cPas d'invitation en cours."); return false; }
        List<UUID> members = parties.get(lid);
        if (members == null) { send(player, "§cLe groupe n'existe plus."); return false; }
        members.add(player.getUniqueId());
        playerToLeader.put(player.getUniqueId(), lid);
        ready.put(player.getUniqueId(), false);

        ProxiedPlayer leader = ProxyServer.getInstance().getPlayer(lid);
        String leaderName = leader != null ? leader.getName() : "le chef";
        send(player, "§a✔ Tu as rejoint le groupe de §f" + leaderName + "§a !");
        broadcastParty(lid, "§f" + player.getName() + " §aa rejoint le groupe ! ("
            + members.size() + "/" + plugin.getConfig().getInt("max-party-size", 5) + ")");
        sendPartyStatus(lid);
        return true;
    }

    public void decline(ProxiedPlayer player) {
        UUID lid = pendingInvites.remove(player.getUniqueId());
        send(player, "§7Invitation refusée.");
        if (lid != null) {
            ProxiedPlayer leader = ProxyServer.getInstance().getPlayer(lid);
            if (leader != null) send(leader, "§7§f" + player.getName() + " §7a refusé l'invitation.");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // EXCLUSION (chef seulement)
    // ══════════════════════════════════════════════════════════════════════

    public void kick(ProxiedPlayer leader, String targetName) {
        if (!isLeader(leader)) { send(leader, "§cSeul le chef peut exclure."); return; }
        UUID lid = playerToLeader.get(leader.getUniqueId());
        List<UUID> members = parties.get(lid);
        if (members == null) return;

        // Chercher le joueur par nom
        UUID targetUUID = null;
        String foundName = null;
        for (UUID uid : members) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            String name = p != null ? p.getName() : uid.toString().substring(0, 8);
            if (name.equalsIgnoreCase(targetName)) { targetUUID = uid; foundName = name; break; }
        }
        if (targetUUID == null) { send(leader, "§c" + targetName + " n'est pas dans le groupe."); return; }
        if (targetUUID.equals(leader.getUniqueId())) { send(leader, "§cTu ne peux pas t'exclure toi-même. Utilise §e/lol party leave§c."); return; }

        members.remove(targetUUID);
        playerToLeader.remove(targetUUID);
        memberRoles.remove(targetUUID);
        ready.remove(targetUUID);

        ProxiedPlayer target = ProxyServer.getInstance().getPlayer(targetUUID);
        if (target != null) send(target, "§cTu as été exclu du groupe par §f" + leader.getName() + "§c.");
        broadcastParty(lid, "§f" + foundName + " §ca été exclu du groupe.");
        sendPartyStatus(lid);
    }

    // ══════════════════════════════════════════════════════════════════════
    // QUITTER / DISSOUDRE
    // ══════════════════════════════════════════════════════════════════════

    public void leave(ProxiedPlayer player) {
        UUID uid  = player.getUniqueId();
        UUID lid  = playerToLeader.remove(uid);
        if (lid == null) { send(player, "§7Tu n'es pas dans un groupe."); return; }

        List<UUID> members = parties.get(lid);
        if (members != null) members.remove(uid);
        memberRoles.remove(uid);
        ready.remove(uid);
        send(player, "§7Tu as quitté le groupe.");

        if (lid.equals(uid)) {
            // Chef qui part → transférer ou dissoudre
            if (members != null && !members.isEmpty()) {
                UUID newLid = members.get(0);
                List<UUID> newMembers = new ArrayList<>(members);
                parties.remove(lid);
                parties.put(newLid, newMembers);
                for (UUID m : newMembers) playerToLeader.put(m, newLid);
                ProxiedPlayer newLeader = ProxyServer.getInstance().getPlayer(newLid);
                if (newLeader != null) send(newLeader, "§6Tu es maintenant le chef du groupe !");
                broadcastParty(newLid, "§7Le chef a quitté. §f" +
                    (newLeader != null ? newLeader.getName() : "?") + " §7est le nouveau chef.");
            } else {
                if (members != null) parties.remove(lid);
            }
        } else {
            broadcastParty(lid, "§f" + player.getName() + " §7a quitté le groupe.");
            if (members != null && members.isEmpty()) parties.remove(lid);
        }
    }

    public void disband(ProxiedPlayer leader) {
        if (!isLeader(leader)) { send(leader, "§cSeul le chef peut dissoudre le groupe."); return; }
        UUID lid = leader.getUniqueId();
        List<UUID> members = parties.remove(lid);
        if (members == null) return;
        for (UUID uid : members) {
            playerToLeader.remove(uid);
            memberRoles.remove(uid);
            ready.remove(uid);
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            if (p != null) send(p, "§cLe groupe a été dissous par le chef.");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRANSFERT DU CHEF
    // ══════════════════════════════════════════════════════════════════════

    public void transferLeader(ProxiedPlayer leader, String targetName) {
        if (!isLeader(leader)) { send(leader, "§cSeul le chef peut transférer le pouvoir."); return; }
        UUID lid = leader.getUniqueId();
        List<UUID> members = parties.get(lid);
        if (members == null) return;

        UUID targetUUID = null;
        for (UUID uid : members) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            if (p != null && p.getName().equalsIgnoreCase(targetName)) { targetUUID = uid; break; }
        }
        if (targetUUID == null || targetUUID.equals(lid)) {
            send(leader, "§c" + targetName + " n'est pas dans le groupe."); return;
        }

        // Reconstruire le groupe avec le nouveau chef
        List<UUID> newMembers = new ArrayList<>(members);
        newMembers.remove(targetUUID);
        newMembers.add(0, targetUUID);
        parties.remove(lid);
        parties.put(targetUUID, newMembers);
        for (UUID uid : newMembers) playerToLeader.put(uid, targetUUID);

        ProxiedPlayer newLeader = ProxyServer.getInstance().getPlayer(targetUUID);
        if (newLeader != null) send(newLeader, "§6§l⚔ Tu es maintenant le chef du groupe !");
        broadcastParty(targetUUID, "§6" + (newLeader != null ? newLeader.getName() : targetName)
            + " §7est le nouveau chef du groupe.");
    }

    // ══════════════════════════════════════════════════════════════════════
    // RÔLES ET FILE D'ATTENTE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Un membre choisit ses rôles pour la prochaine partie.
     * Vérifie les conflits et lance la file si tout le groupe est prêt.
     *
     * @return true si la file a été lancée
     */
    public boolean setRolesAndCheckReady(ProxiedPlayer player, List<String> roles) {
        UUID uid = player.getUniqueId();
        UUID lid = playerToLeader.get(uid);

        // Joueur hors groupe → géré directement par BungeeQueueManager
        if (lid == null) return false;

        memberRoles.put(uid, roles);
        ready.put(uid, true);

        List<UUID> members = parties.get(lid);
        if (members == null) return false;

        // Informer le groupe
        String rolesStr = String.join("/", roles);
        broadcastParty(lid, "§f" + player.getName() + " §7est prêt : §a" + rolesStr);

        // Vérifier si tout le groupe est prêt
        for (UUID m : members) {
            if (!Boolean.TRUE.equals(ready.get(m))) {
                // Afficher qui manque encore
                StringBuilder waiting = new StringBuilder();
                for (UUID mu : members) {
                    if (!Boolean.TRUE.equals(ready.get(mu))) {
                        ProxiedPlayer mp = ProxyServer.getInstance().getPlayer(mu);
                        if (waiting.length() > 0) waiting.append(", ");
                        waiting.append(mp != null ? mp.getName() : "?");
                    }
                }
                send(player, "§7En attente de : §f" + waiting);
                return false;
            }
        }

        // Tout le monde est prêt → vérifier les conflits de rôles
        String conflict = checkRoleConflict(lid, members);
        if (conflict != null) {
            broadcastParty(lid, "§c⚠ Conflit de rôles : " + conflict);
            broadcastParty(lid, "§7Refaites §e/lol <rôles> §7pour corriger.");
            // Remettre tout le monde non-prêt
            for (UUID m : members) ready.put(m, false);
            memberRoles.clear();
            return false;
        }

        // Pas de conflit → lancer la file
        broadcastParty(lid, "§a§l✔ Tout le groupe est prêt ! Recherche d'une partie...");
        return true;
    }

    /**
     * Vérifie les conflits de rôles dans le groupe.
     *
     * Règle : le nombre de postes distincts couverts doit être >= taille du groupe.
     * Si groupe = 5 : les 5 postes doivent être couverts.
     * Si groupe = 3 : au moins 3 postes distincts couverts.
     *
     * @return message d'erreur, ou null si pas de conflit
     */
    private String checkRoleConflict(UUID lid, List<UUID> members) {
        Set<String> allRoles = new LinkedHashSet<>();
        Map<String, List<String>> byMember = new LinkedHashMap<>();

        for (UUID uid : members) {
            List<String> roles = memberRoles.getOrDefault(uid, List.of());
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            String name = p != null ? p.getName() : uid.toString().substring(0, 8);
            byMember.put(name, roles);
            allRoles.addAll(roles);
        }

        int groupSize = members.size();
        int distinctRoles = allRoles.size();

        if (distinctRoles < groupSize) {
            // Construire le message d'erreur
            String rolesStr = allRoles.stream().collect(Collectors.joining(", "));
            return "§fSeulement §e" + distinctRoles + " poste(s) distinct(s) §fpour §e"
                + groupSize + " joueurs §f(" + rolesStr + "). "
                + "§7Vous n'occupez pas assez de postes.";
        }
        return null;
    }

    /** Retourne les rôles choisis par un membre (pour la file). */
    public List<String> getMemberRoles(UUID uuid) {
        return memberRoles.getOrDefault(uuid, List.of("TOP","JUNGLE","MID","ADC","SUPPORT"));
    }

    /** Remet tout le monde non-prêt après la fin de la partie. */
    public void resetReady(UUID leaderUUID) {
        List<UUID> members = parties.get(leaderUUID);
        if (members == null) return;
        for (UUID uid : members) { ready.put(uid, false); memberRoles.remove(uid); }
    }

    // ══════════════════════════════════════════════════════════════════════
    // INFOS / STATUT
    // ══════════════════════════════════════════════════════════════════════

    public void sendPartyStatus(UUID leaderUUID) {
        List<UUID> members = parties.get(leaderUUID);
        if (members == null) return;
        String header = "§6§l⚔ Groupe (" + members.size() + "/"
            + plugin.getConfig().getInt("max-party-size", 5) + ")";
        for (UUID uid : members) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            if (p == null) continue;
            StringBuilder sb = new StringBuilder(header + "\n");
            for (UUID m : members) {
                ProxiedPlayer mp = ProxyServer.getInstance().getPlayer(m);
                String name  = mp != null ? mp.getName() : "?";
                boolean isChef = m.equals(leaderUUID);
                boolean isRdy  = Boolean.TRUE.equals(ready.get(m));
                List<String> roles = memberRoles.getOrDefault(m, List.of());
                sb.append(isChef ? "§6★ " : "§7• ")
                  .append("§f").append(name)
                  .append(isRdy ? " §a✔ " + String.join("/", roles) : " §7(pas prêt)")
                  .append("\n");
            }
            sb.append("§7Tape §e/lol <rôles> §7pour indiquer tes postes souhaités.");
            send(p, sb.toString());
        }
    }

    public void sendPartyInfo(ProxiedPlayer player) {
        UUID lid = playerToLeader.get(player.getUniqueId());
        if (lid == null) { send(player, "§7Tu n'es pas dans un groupe."); return; }
        sendPartyStatus(lid);
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILITAIRES
    // ══════════════════════════════════════════════════════════════════════

    public boolean inParty(UUID uuid)              { return playerToLeader.containsKey(uuid); }
    public boolean isLeader(ProxiedPlayer p)       { return p.getUniqueId().equals(playerToLeader.get(p.getUniqueId())); }
    public UUID    getLeader(UUID uuid)            { return playerToLeader.get(uuid); }
    public List<UUID> getPartyMembers(UUID uuid) {
        UUID lid = playerToLeader.get(uuid);
        if (lid == null) return List.of(uuid);
        return Collections.unmodifiableList(parties.getOrDefault(lid, List.of(uuid)));
    }

    private void broadcastParty(UUID leaderUUID, String msg) {
        List<UUID> members = parties.get(leaderUUID);
        if (members == null) return;
        for (UUID uid : members) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uid);
            if (p != null) send(p, msg);
        }
    }

    private void send(ProxiedPlayer p, String msg) {
        for (String line : msg.split("\n"))
            p.sendMessage(new TextComponent(line));
    }

    public void cleanup(UUID uuid) {
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
        if (p != null) leave(p);
        else {
            UUID lid = playerToLeader.remove(uuid);
            if (lid != null) { var m = parties.get(lid); if (m != null) m.remove(uuid); }
            pendingInvites.remove(uuid);
            memberRoles.remove(uuid);
            ready.remove(uuid);
        }
    }
}
