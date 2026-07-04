package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.item.LolItem;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Snapshot de l'état d'un joueur en cours de partie.
 * Sauvegardé à la déconnexion, restauré à la reconnexion.
 */
public class PlayerSnapshot {

    public final UUID uuid;
    public final String championId;
    public final double currentHP;
    public final double currentResource;
    public final int level;
    public final int[] abilityRanks;   // Q W E R + passif
    public final double currentXP;
    public final int gold;
    public final List<String> itemIds;
    public final String spell1;
    public final String spell2;

    public PlayerSnapshot(Player player) {
        this.uuid = player.getUniqueId();
        var cm = LolPlugin.getInstance().getChampionManager();
        var champ = cm.hasChampion(player) ? cm.getChampion(player) : null;

        this.championId    = champ != null ? champ.getId() : null;
        this.currentHP     = champ != null ? champ.getHPSystem().getCurrentHP()   : 0;
        this.currentResource = champ != null && champ.getResourceSystem() != null
                             ? champ.getResourceSystem().getCurrent() : 0;
        this.level         = champ != null ? champ.getLevelSystem().getLevel() : 1;
        this.abilityRanks  = champ != null ? champ.getLevelSystem().getAbilityRanks().clone() : new int[5];
        this.currentXP     = champ != null ? champ.getLevelSystem().getCurrentXP() : 0;
        this.gold          = LolPlugin.getInstance().getGoldManager().getGold(uuid);

        // Items équipés
        var inv = LolPlugin.getInstance().getShopListener().getOrCreate(player);
        this.itemIds = new ArrayList<>();
        for (LolItem item : inv.getEquippedItems()) {
            if (item != null) itemIds.add(item.getId());
        }

        // Sorts
        var ssm = LolPlugin.getInstance().getSummonerSpellManager();
        var spells = ssm.getSpells(player);
        this.spell1 = spells != null && spells.length > 0 ? spells[0].name() : "FLASH";
        this.spell2 = spells != null && spells.length > 1 ? spells[1].name() : "IGNITE";
    }

    /** Restaure l'état du joueur après reconnexion. */
    public void restore(Player player) {
        if (championId == null) return;
        var cm = LolPlugin.getInstance().getChampionManager();

        // Remettre le champion
        cm.assignChampion(player, championId);
        var champ = cm.getChampion(player);
        if (champ == null) return;

        // Niveaux + XP
        champ.getLevelSystem().setLevel(level);
        champ.getLevelSystem().setAbilityRanks(abilityRanks);
        champ.getLevelSystem().setCurrentXP(currentXP);
        champ.getStats().setChampionLevel(level);

        // HP
        champ.getHPSystem().setCurrentHP(currentHP);
        if (champ.getResourceSystem() != null)
            champ.getResourceSystem().setCurrent(currentResource);

        // Or
        LolPlugin.getInstance().getGoldManager().setGold(uuid, gold);

        // Items
        var shopListener = LolPlugin.getInstance().getShopListener();
        var inv = shopListener.getOrCreate(player);
        inv.clear();
        for (String itemId : itemIds) {
            var item = fr.lolmc.item.ItemRegistry.get(itemId);
            if (item != null) inv.equipItem(player, champ, item);
        }

        // Sorts
        LolPlugin.getInstance().getSummonerSpellManager().setSpells(player, spell1, spell2);

        // Hotbar
        LolPlugin.getInstance().getHotbarManager().initPlayer(player, champ);

        player.sendMessage(net.kyori.adventure.text.Component.text(
            "✔ Champion " + championId + " niveau " + level + " restauré.",
            net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }
}
