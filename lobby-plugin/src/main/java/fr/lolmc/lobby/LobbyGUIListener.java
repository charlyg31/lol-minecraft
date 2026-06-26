package fr.lolmc.lobby;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Routage de tous les clics dans les GUIs du lobby.
 * Détecte le menu actif via le titre de l'inventaire,
 * puis délègue au manager approprié.
 */
public class LobbyGUIListener implements Listener {

    private final LobbyPlugin plugin;

    public LobbyGUIListener(LobbyPlugin p) { this.plugin = p; }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        String title = PlainTextComponentSerializer.plainText()
            .serialize(e.getView().title());

        // Tous nos menus commencent par "⚔ LoL —"
        if (!title.startsWith("⚔ LoL —")) return;
        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        int slot = e.getRawSlot();
        ClickType click = e.getClick();

        switch (title) {
            case LobbyMainMenu.T_MAIN   -> handleMain(player, slot, click);
            case LobbyMainMenu.T_ROLE   -> handleRole(player, slot);
            case LobbyMainMenu.T_RUNES  -> handleRunes(player, slot, click);
            case LobbyMainMenu.T_SPELLS -> handleSpells(player, slot, click);
            case LobbyMainMenu.T_PARTY  -> handleParty(player, slot, click);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MENU PRINCIPAL
    // ══════════════════════════════════════════════════════════════════════

    private void handleMain(Player player, int slot, ClickType click) {
        switch (slot) {
            // ── Colonne 1 : Rôle ──────────────────────────────────────────
            case 9, 18 -> LobbyMainMenu.openRole(player);

            // Rôles miniatures (27-32)
            case 27, 28, 29, 30, 31, 32 -> {
                int idx = slot - 27;
                if (idx < LobbyMainMenu.ROLES.length) {
                    plugin.getRoleManager().setRole(
                        player.getUniqueId(), LobbyMainMenu.ROLES[idx]);
                    player.sendMessage("§a✔ Rôle mis à jour : §l"
                        + LobbyMainMenu.ROLES[idx]);
                    // Rafraîchir le menu principal
                    org.bukkit.Bukkit.getScheduler().runTask(plugin,
                        () -> LobbyMainMenu.openMain(player));
                }
            }

            // ── Colonne 2 : Runes ─────────────────────────────────────────
            case 11, 20, 22 -> LobbyMainMenu.openRunes(player);
            case 13, 7      -> LobbyMainMenu.openSpells(player);

            // ── Colonne 3 : Groupe ────────────────────────────────────────
            case 15 -> LobbyMainMenu.openParty(player);

            // Bouton JOUER / quitter file
            case 24 -> {
                var qm = plugin.getQueueManager();
                if (qm.isInQueue(player.getUniqueId())) {
                    qm.leave(player);
                } else {
                    qm.join(player);
                }
                org.bukkit.Bukkit.getScheduler().runTask(plugin,
                    () -> LobbyMainMenu.openMain(player));
            }
            case 33 -> {
                plugin.getQueueManager().leave(player);
                org.bukkit.Bukkit.getScheduler().runTask(plugin,
                    () -> LobbyMainMenu.openMain(player));
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOUS-MENU RÔLE
    // ══════════════════════════════════════════════════════════════════════

    private void handleRole(Player player, int slot) {
        // Slots 10-15 → rôles
        if (slot >= 10 && slot <= 15) {
            int idx = slot - 10;
            if (idx < LobbyMainMenu.ROLES.length) {
                plugin.getRoleManager().setRole(player.getUniqueId(), LobbyMainMenu.ROLES[idx]);
                player.sendMessage("§a✔ Rôle : §l" + LobbyMainMenu.ROLES[idx]);
                org.bukkit.Bukkit.getScheduler().runTask(plugin,
                    () -> LobbyMainMenu.openRole(player));
            }
        }
        // Bouton retour
        if (slot == 22) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin,
                () -> LobbyMainMenu.openMain(player));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOUS-MENU RUNES
    // ══════════════════════════════════════════════════════════════════════

    private void handleRunes(Player player, int slot, ClickType click) {
        UUID uid = player.getUniqueId();
        var rm = plugin.getRuneManager();
        var data = rm.getPageJson(uid);

        // Slots 0-13 → keystones
        if (slot >= 0 && slot <= 13) {
            String ks = LobbyMainMenu.KEYSTONES[slot];
            rm.savePage(uid, ks,
                java.util.List.of(),
                data.getOrDefault("spell1", "FLASH"),
                data.getOrDefault("spell2", "IGNITE"));
            player.sendMessage("§d✔ Keystone : §l" + LobbyMainMenu.formatName(ks));
            org.bukkit.Bukkit.getScheduler().runTask(plugin,
                () -> LobbyMainMenu.openRunes(player));
        }
        // Bouton sorts (slot 27)
        if (slot == 27) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin,
                () -> LobbyMainMenu.openSpells(player));
        }
        // Retour (slot 53)
        if (slot == 53) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin,
                () -> LobbyMainMenu.openMain(player));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOUS-MENU SORTS
    // ══════════════════════════════════════════════════════════════════════

    private void handleSpells(Player player, int slot, ClickType click) {
        UUID uid = player.getUniqueId();
        var rm = plugin.getRuneManager();
        var data = rm.getPageJson(uid);
        String spell1 = data.getOrDefault("spell1", "FLASH");
        String spell2 = data.getOrDefault("spell2", "IGNITE");

        // Slots 9-17 → sorts
        if (slot >= 9 && slot <= 17) {
            int idx = slot - 9;
            if (idx < LobbyMainMenu.SPELLS.length) {
                String chosen = LobbyMainMenu.SPELLS[idx];
                if (click.isLeftClick()) {
                    // Éviter les doublons
                    if (chosen.equals(spell2)) spell2 = spell1;
                    spell1 = chosen;
                    player.sendMessage("§e✔ Sort D : §l" + chosen);
                } else {
                    if (chosen.equals(spell1)) spell1 = spell2;
                    spell2 = chosen;
                    player.sendMessage("§b✔ Sort F : §l" + chosen);
                }
                rm.savePage(uid,
                    data.getOrDefault("keystone", "conqueror"),
                    java.util.List.of(), spell1, spell2);
                final String fs1 = spell1, fs2 = spell2;
                org.bukkit.Bukkit.getScheduler().runTask(plugin,
                    () -> LobbyMainMenu.openSpells(player));
            }
        }
        // Retour
        if (slot == 26) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin,
                () -> LobbyMainMenu.openRunes(player));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOUS-MENU GROUPE
    // ══════════════════════════════════════════════════════════════════════

    private void handleParty(Player player, int slot, ClickType click) {
        var pm = plugin.getPartyManager();

        // Slots 9-13 : membres (clic droit chef = exclure)
        if (slot >= 9 && slot <= 13 && click.isRightClick() && pm.isLeader(player)) {
            var members = pm.getPartyMembers(player.getUniqueId());
            int idx = slot - 9;
            if (idx > 0 && idx < members.size()) {
                var target = org.bukkit.Bukkit.getPlayer(members.get(idx));
                if (target != null) pm.leave(target);
                org.bukkit.Bukkit.getScheduler().runTask(plugin,
                    () -> LobbyMainMenu.openParty(player));
            }
        }

        switch (slot) {
            case 18 -> player.sendMessage(
                "§7Utilise §e/party invite <joueur> §7pour inviter.");
            case 19 -> {
                pm.leave(player);
                org.bukkit.Bukkit.getScheduler().runTask(plugin,
                    () -> LobbyMainMenu.openMain(player));
            }
            case 20 -> {
                if (pm.isLeader(player)) {
                    var members = pm.getPartyMembers(player.getUniqueId());
                    for (UUID mid : new java.util.ArrayList<>(members)) {
                        var m = org.bukkit.Bukkit.getPlayer(mid);
                        if (m != null && !m.equals(player)) pm.leave(m);
                    }
                    pm.leave(player);
                    org.bukkit.Bukkit.getScheduler().runTask(plugin,
                        () -> LobbyMainMenu.openMain(player));
                }
            }
            case 53 -> org.bukkit.Bukkit.getScheduler().runTask(plugin,
                () -> LobbyMainMenu.openMain(player));
        }
    }
}
