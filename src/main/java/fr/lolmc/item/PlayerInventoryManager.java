package fr.lolmc.item;

import fr.lolmc.champion.base.BaseChampion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

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

    public static final int[] ITEM_SLOTS = {5, 6, 7, 8, 18, 19};

    // Items équipés par joueur [0..5]
    private final LolItem[] equippedItems = new LolItem[6];

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
        return true;
    }

    /**
     * Vend l'item du slot donné (remboursement 70%).
     */
    public int sellItem(Player player, BaseChampion champ, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 6 || equippedItems[slotIndex] == null) return 0;
        LolItem item = equippedItems[slotIndex];
        item.removeStats(champ.getStats(), champ.getHPSystem(), champ.getResourceSystem());

        var hp = champ.getHPSystem();
        if (hp.getCurrentHP() > hp.getMaxHP()) hp.setCurrentHP(hp.getMaxHP());

        equippedItems[slotIndex] = null;
        return (int)(item.getGoldCost() * 0.70);
    }

    /**
     * Retire un item du slot sans rembourser l'or.
     */
    public void removeItemNoRefund(BaseChampion champ, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 6 || equippedItems[slotIndex] == null) return;
        LolItem item = equippedItems[slotIndex];
        item.removeStats(champ.getStats(), champ.getHPSystem(), champ.getResourceSystem());

        var hp = champ.getHPSystem();
        if (hp.getCurrentHP() > hp.getMaxHP()) hp.setCurrentHP(hp.getMaxHP());

        equippedItems[slotIndex] = null;
    }

    public void refreshAll(Player player) {
        // L'affichage est délégué à HotbarManager
    }

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

    public static int getLolSlotIndex(int mcSlot) {
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (ITEM_SLOTS[i] == mcSlot) return i;
        }
        return -1;
    }

    /**
     * Extrait l'item ID depuis le PersistentDataContainer de l'ItemStack.
     */
    public static String getItemId(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(HotbarManager.KEY_ID, PersistentDataType.STRING);
    }
}