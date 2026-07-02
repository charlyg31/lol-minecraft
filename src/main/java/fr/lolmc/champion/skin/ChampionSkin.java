package fr.lolmc.champion.skin;

import org.bukkit.Material;

/**
 * Un skin de champion.
 *
 * Permission format : "lolmc.skin.<champId>.<skinId>"
 * Ex: "lolmc.skin.garen.steel_legion"
 *
 * Skin de base (skinNumber=0) : toujours accessible, pas de permission.
 */
public class ChampionSkin {

    public final String   id;
    public final String   championId;
    public final String   displayName;
    public final int      skinNumber;   // 0 = base
    public final Material icon;
    public final String   permission;   // null = base

    public ChampionSkin(String id, String championId, String displayName,
                        int skinNumber, Material icon, String permission) {
        this.id          = id;
        this.championId  = championId;
        this.displayName = displayName;
        this.skinNumber  = skinNumber;
        this.icon        = icon;
        this.permission  = permission;
    }

    public boolean isBase() { return permission == null || skinNumber == 0; }

    public boolean hasAccess(org.bukkit.entity.Player player) {
        return isBase() || player.hasPermission(permission);
    }

    /** Permission automatique si non définie. */
    public String getPermission() {
        return permission != null ? permission : "lolmc.skin." + championId + "." + id;
    }
}
