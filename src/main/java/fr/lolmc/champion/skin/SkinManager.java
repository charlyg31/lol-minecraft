package fr.lolmc.champion.skin;

import fr.lolmc.LolPlugin;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les skins actifs des joueurs.
 *
 * En Minecraft sans resource pack client, un "skin" de champion
 * est représenté par :
 *  - un Material d'icône différent dans la hotbar
 *  - un nametag coloré différent au-dessus du joueur
 *  - (avec resource pack) des textures/modèles différents
 *
 * Pour l'instant le système pose les bases et applique les changements
 * visibles sans resource pack (nametag + hotbar icon).
 * Quand un resource pack sera ajouté, SkinManager.applySkin() sera
 * le point d'entrée pour envoyer les données au client.
 */
public class SkinManager {

    // UUID joueur → skinId actif
    private final Map<UUID, String> activeSkins  = new HashMap<>();
    // UUID joueur → champId actif
    private final Map<UUID, String> activeChamps = new HashMap<>();

    /**
     * Applique un skin à un joueur.
     * Stocke le choix et met à jour les éléments visuels disponibles.
     */
    public void applySkin(Player player, String champId, String skinId) {
        activeSkins.put(player.getUniqueId(), skinId);
        activeChamps.put(player.getUniqueId(), champId);

        ChampionSkin skin = SkinRegistry.get(champId, skinId);
        if (skin == null) skin = SkinRegistry.getBase(champId);
        if (skin == null) return;

        // 1. Nametag au-dessus du joueur avec le nom du skin
        String tag = (skin.isBase() ? "" : "§6[" + skin.displayName + "] ") + "§f" + player.getName();
        player.setCustomName(tag);
        player.setCustomNameVisible(true);

        // 2. Mettre à jour l'icône de la hotbar (slot de champion)
        var hm = LolPlugin.getInstance().getHotbarManager();
        var cm = LolPlugin.getInstance().getChampionManager();
        if (hm != null && cm.hasChampion(player)) {
            hm.initPlayer(player, cm.getChampion(player));
        }

        // 3. Log
        LolPlugin.getInstance().getLogger().info(
            "[SkinManager] " + player.getName() + " → " + champId + "/" + skinId);
    }

    public String getActiveSkin(UUID uuid)  { return activeSkins.getOrDefault(uuid, "base"); }
    public String getActiveChamp(UUID uuid) { return activeChamps.get(uuid); }

    public void cleanup(UUID uuid) {
        activeSkins.remove(uuid);
        activeChamps.remove(uuid);
    }
}
