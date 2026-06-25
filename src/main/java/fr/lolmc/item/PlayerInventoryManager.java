package fr.lolmc.item;

import fr.lolmc.champion.base.BaseChampion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Gère les 6 slots d'items LoL pour un joueur.
 *
 * Disposition dans l'inventaire Minecraft (slots 0-35 + armure):
 *   Slots 0-4  = sorts (AA, Q, W, E, R) — PROTÉGÉS
 *   Slots 5    = boots (slot 5 de la hotbar)
 *   Slots 6-11 = 5 items + 1 boot (hotbar étendue = slots 9-11 + row2)
 *
 * On utilise les slots suivants pour les items LoL :
 *   Slot 5  = Item 1 (boots souvent)
 *   Slot 6  = Item 2
 *   Slot 7  = Item 3
 *   Slot 8  = Item 4 (dernier slot hotbar)
 *   Slot 18 = Item 5 (2ème rangée)
 *   Slot 19 = Item 6 (2ème rangée)
 */
public class PlayerInventoryManager {

    // Slots Minecraft pour les 6 items LoL
    public static final int[] ITEM_SLOTS = {5, 6, 7, 8, 18, 19};
    // Tag identifiant un item LoL dans le lore (sans séquence de couleur legacy)
    public static final String LOL_ITEM_TAG = "LOL_ITEM:";

    // Items équipés par joueur [0..5]
    private final LolItem[] equippedItems = new LolItem[6];

    // ── Équiper un item ───────────────────────────────────────────

    /**
     * Équipe un item dans le premier slot libre.
     * @return true si équipé, false si inventaire plein
     */
    public boolean equipItem(Player player, BaseChampion champ, LolItem item) {
        int freeSlot = findFreeSlot();
        if (freeSlot == -1) {
            player.sendMessage(Component.text("❌ Inventaire plein (6/6 items)!", NamedTextColor.RED));
            return false;
        }
        equippedItems[freeSlot] = item;
        item.applyStats(champ.getStats(), champ.getHPSystem(), champ.getResourceSystem());
        // L'affichage hotbar est géré par HotbarManager (pas ici)
        return true;
    }

    /**
     * Vend l'item du slot donné (remboursement 70%).
     * Après removeStats, on clamp les HP au nouveau max pour éviter currentHP > maxHP.
     */
    public int sellItem(Player player, BaseChampion champ, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 6 || equippedItems[slotIndex] == null) return 0;
        LolItem item = equippedItems[slotIndex];
        item.removeStats(champ.getStats(), champ.getHPSystem(), champ.getResourceSystem());
        // Clamp HP au nouveau max (evite currentHP > maxHP apres retrait d'un item de vie)
        var hp = champ.getHPSystem();
        if (hp.getCurrentHP() > hp.getMaxHP()) hp.setCurrentHP(hp.getMaxHP());
        equippedItems[slotIndex] = null;
        int refund = (int)(item.getGoldCost() * 0.70);
        return refund;
    }

    /**
     * Retire un item du slot sans rembourser l'or (utilisé pour les upgrades :
     * le joueur paie seulement la différence prix_final - prix_composant).
     */
    public void removeItemNoRefund(BaseChampion champ, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 6 || equippedItems[slotIndex] == null) return;
        LolItem item = equippedItems[slotIndex];
        item.removeStats(champ.getStats(), champ.getHPSystem(), champ.getResourceSystem());
        var hp = champ.getHPSystem();
        if (hp.getCurrentHP() > hp.getMaxHP()) hp.setCurrentHP(hp.getMaxHP());
        equippedItems[slotIndex] = null;
    }

    /**
     * Recharge tous les items dans l'inventaire (ex: après respawn).
     */
    public void refreshAll(Player player) {
        // Affichage géré par HotbarManager désormais
    }

    /**
     * Met à jour un slot spécifique dans l'inventaire Minecraft.
     */
    private void updateSlot(Player player, int slotIndex) {
        int mcSlot = ITEM_SLOTS[slotIndex];
        LolItem item = equippedItems[slotIndex];
        if (item == null) {
            player.getInventory().setItem(mcSlot, emptySlot(slotIndex + 1));
        } else {
            // Ajouter le tag LoL dans le lore pour identifier l'item
            ItemStack stack = item.buildItemStack();
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                // Tag caché pour retrouver l'item ID
                lore.add(Component.text(LOL_ITEM_TAG + item.getId()));
                meta.lore(lore);
                stack.setItemMeta(meta);
            }
            player.getInventory().setItem(mcSlot, stack);
        }
    }

    private void clearSlot(Player player, int slotIndex) {
        player.getInventory().setItem(ITEM_SLOTS[slotIndex], emptySlot(slotIndex + 1));
    }

    /**
     * Crée un item "slot vide" affiché dans l'inventaire.
     */
    private ItemStack emptySlot(int number) {
        ItemStack empty = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = empty.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Slot item " + number, NamedTextColor.DARK_GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            empty.setItemMeta(meta);
        }
        return empty;
    }

    // ── Getters ───────────────────────────────────────────────────

    public LolItem getItem(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 6) return null;
        return equippedItems[slotIndex];
    }

    public LolItem[] getEquippedItems() { return equippedItems; }

    public int findFreeSlot() {
        for (int i = 0; i < 6; i++) if (equippedItems[i] == null) return i;
        return -1;
    }

    public boolean isFull() { return findFreeSlot() == -1; }

    public int countEquipped() {
        int count = 0;
        for (LolItem item : equippedItems) if (item != null) count++;
        return count;
    }

    /**
     * Retrouve l'index de slot LoL depuis un slot Minecraft.
     * @return -1 si pas un slot item
     */
    public static int getLolSlotIndex(int mcSlot) {
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (ITEM_SLOTS[i] == mcSlot) return i;
        }
        return -1;
    }

    /**
     * Extrait l'item ID depuis le lore d'un ItemStack.
     */
    public static String getItemId(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasLore()) return null;
        var lore = meta.lore();
        if (lore == null) return null;
        for (Component line : lore) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);
            if (plain.startsWith(LOL_ITEM_TAG)) {
                return plain.substring(LOL_ITEM_TAG.length());
            }
        }
        return null;
    }
}
