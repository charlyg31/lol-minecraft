package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.manager.ChampionGUI;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HeadManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class GUIListener implements Listener {

    private final ChampionGUI gui;
    private final ChampionManager manager;
    private final HeadManager headManager;
    private final HUDManager hudManager;

    // NBT tag clé pour identifier une tête de champion (stocké dans le lore)
    public static final String HEAD_TAG = "§0LOL_CHAMPION_HEAD";

    public GUIListener(ChampionGUI gui, ChampionManager manager, HeadManager headManager, HUDManager hudManager) {
        this.gui = gui;
        this.manager = manager;
        this.headManager = headManager;
        this.hudManager = hudManager;
    }

    // ── Clic dans le GUI ──────────────────────────────────────────
    @EventHandler
    public void onGUIClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Vérifier que c'est notre GUI
        Component title = event.getView().title();
        if (!title.equals(Component.text(ChampionGUI.GUI_TITLE))) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        String champId = gui.getChampionIdFromSlot(slot);
        if (champId == null) return;

        // Assigner le champion
        manager.assignChampion(player, champId);
        player.closeInventory();
        // Init HP/Ressource/HUD
        if (manager.hasChampion(player)) {
            hudManager.initPlayer(player, manager.getChampion(player));
            LolPlugin.getInstance().getShopListener().initPlayer(player);
            LolPlugin.getInstance().getHotbarManager().initPlayer(player, manager.getChampion(player));
        }

        // Équiper la tête du champion
        equipChampionHead(player, champId);

        player.sendMessage(Component.text(
            "✓ Champion sélectionné! Slots 0-4 = sorts.",
            NamedTextColor.GREEN));
    }

    // ── Équiper la tête du champion ───────────────────────────────
    public void equipChampionHead(Player player, String champId) {
        ItemStack head = headManager.getChampionHead(champId);
        ItemMeta meta = head.getItemMeta();
        if (meta == null) return;

        // Obtenir le nom du champion
        String displayName = manager.getAllChampions().stream()
                .filter(c -> c.getId().equals(champId))
                .findFirst()
                .map(c -> c.getDisplayName())
                .orElse(champId);

        meta.displayName(Component.text("§e⚔ " + displayName));
        // Tag caché dans le lore pour identifier la tête (non visible car §0)
        meta.lore(List.of(Component.text(HEAD_TAG)));
        head.setItemMeta(meta);

        // Mettre dans le slot casque
        player.getInventory().setHelmet(head);
        player.sendActionBar(Component.text("🪖 " + displayName, NamedTextColor.GOLD));
    }

    // ── Empêcher de retirer le casque de champion ─────────────────
    @EventHandler
    public void onInventoryClickHelmet(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!manager.hasChampion(player)) return;

        // Vérifier si c'est le slot casque (slot 5 dans l'inventaire Minecraft)
        if (event.getSlot() == 5 || event.getRawSlot() == 5) {
            // Vérifier que c'est bien notre tête LoL
            ItemStack current = player.getInventory().getHelmet();
            if (current != null && isChampionHead(current)) {
                event.setCancelled(true);
                player.sendActionBar(Component.text(
                    "⚠ Tu ne peux pas retirer le casque de champion!",
                    NamedTextColor.RED));
            }
        }

        // Empêcher aussi le shift-click sur le casque depuis l'inventaire
        if (event.isShiftClick()) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && isChampionHead(clicked)) {
                event.setCancelled(true);
            }
        }
    }

    // ── Empêcher de dropper la tête ───────────────────────────────
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isChampionHead(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(
                "⚠ Impossible de jeter le casque de champion!",
                NamedTextColor.RED));
        }
    }

    // ── Utilitaire ────────────────────────────────────────────────
    private boolean isChampionHead(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        var lore = meta.lore();
        if (lore == null) return false;
        return lore.stream().anyMatch(l ->
            l.toString().contains("LOL_CHAMPION_HEAD"));
    }
}
