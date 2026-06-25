package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;

/**
 * Gère le PNJ boutique (un villageois) posé à chaque base.
 * Cliquer dessus ouvre la boutique — mais seulement si le joueur
 * est de la bonne équipe et proche de sa base.
 */
public class ShopNpcManager implements Listener {

    public static NamespacedKey KEY_SHOP_NPC;
    private final File npcFile;
    private org.bukkit.configuration.file.FileConfiguration config;

    public ShopNpcManager() {
        KEY_SHOP_NPC = new NamespacedKey(LolPlugin.getInstance(), "shop_npc");
        this.npcFile = new File(LolPlugin.getInstance().getDataFolder(), "npcs.yml");
        if (!npcFile.exists()) {
            try { npcFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(npcFile);
    }

    /**
     * Crée un PNJ boutique à une position pour une équipe.
     * Appelé via commande admin.
     */
    public void spawnShopNpc(Location loc, Team team) {
        Villager npc = loc.getWorld().spawn(loc, Villager.class, v -> {
            v.customName(Component.text("🛒 Boutique " + (team == Team.BLUE ? "Bleue" : "Rouge"),
                    team == Team.BLUE ? NamedTextColor.BLUE : NamedTextColor.RED));
            v.setCustomNameVisible(true);
            v.setAI(false);            // immobile
            v.setInvulnerable(true);   // indestructible
            v.setProfession(Villager.Profession.WEAPONSMITH);
            v.setSilent(true);
            v.getPersistentDataContainer().set(KEY_SHOP_NPC, PersistentDataType.STRING, team.name());
        });

        // Persister la position
        String key = team.name().toLowerCase();
        config.set("shop." + key + ".world", loc.getWorld().getName());
        config.set("shop." + key + ".x", loc.getX());
        config.set("shop." + key + ".y", loc.getY());
        config.set("shop." + key + ".z", loc.getZ());
        save();
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Villager npc)) return;
        String npcTeam = npc.getPersistentDataContainer().get(KEY_SHOP_NPC, PersistentDataType.STRING);
        if (npcTeam == null) return;

        e.setCancelled(true);
        Player player = e.getPlayer();

        // Vérifier que le joueur est de la bonne équipe
        Team playerTeam = LolPlugin.getInstance().getTeamManager().getTeam(player);
        if (playerTeam == null || !playerTeam.name().equals(npcTeam)) {
            player.sendActionBar(Component.text("❌ Ce n'est pas ta boutique!", NamedTextColor.RED));
            return;
        }

        // Ouvrir la boutique
        LolPlugin.getInstance().getShopGUI().open(player);
    }

    private void save() {
        try { config.save(npcFile); }
        catch (Exception e) { LolPlugin.getInstance().getLogger().warning("Erreur npcs.yml: " + e.getMessage()); }
    }
}
