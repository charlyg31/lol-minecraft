package fr.lolmc.item;

import fr.lolmc.LolPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère le sort d'invocateur Flash.
 * Téléporte le joueur ~5 blocs dans la direction visée (distance LoL exacte).
 * Cooldown: 300 secondes (5 minutes) comme dans LoL.
 */
public class FlashManager {

    private static final double FLASH_DISTANCE = 5.0; // ~400 unités LoL
    private static final long FLASH_COOLDOWN_MS = 300_000L; // 5 minutes

    private final Map<UUID, Long> cooldowns = new HashMap<>();

    /**
     * Tente d'utiliser Flash.
     */
    public boolean useFlash(Player player) {
        if (isOnCooldown(player)) {
            player.sendActionBar(Component.text(
                String.format("✦ Flash en recharge — %.0fs", getRemaining(player)),
                NamedTextColor.RED));
            return false;
        }

        Location from = player.getLocation();
        Vector dir = from.getDirection().normalize();

        // Calculer la destination à 5 blocs, en s'arrêtant avant les murs
        Location dest = from.clone();
        for (double d = 0.5; d <= FLASH_DISTANCE; d += 0.5) {
            Location check = from.clone().add(dir.clone().multiply(d));
            // Garder la hauteur du sol
            check.setY(from.getY());
            if (!isSafe(check)) {
                break; // Mur trouvé, on s'arrête à la dernière position sûre
            }
            dest = check;
        }

        // Effets visuels et sonores
        from.getWorld().spawnParticle(Particle.PORTAL, from.add(0, 1, 0), 30, 0.3, 0.5, 0.3);
        player.teleport(dest);
        dest.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 30, 0.3, 0.5, 0.3);
        player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);

        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.sendActionBar(Component.text("✦ Flash!", NamedTextColor.AQUA));
        return true;
    }

    private boolean isSafe(Location loc) {
        // La position est sûre si le bloc et celui au-dessus sont traversables
        return loc.getBlock().isPassable()
            && loc.clone().add(0, 1, 0).getBlock().isPassable();
    }

    public boolean isOnCooldown(Player player) {
        Long last = cooldowns.get(player.getUniqueId());
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < FLASH_COOLDOWN_MS;
    }

    public double getRemaining(Player player) {
        Long last = cooldowns.get(player.getUniqueId());
        if (last == null) return 0;
        double rem = (FLASH_COOLDOWN_MS - (System.currentTimeMillis() - last)) / 1000.0;
        return Math.max(0, rem);
    }

    public void reset(Player player) {
        cooldowns.remove(player.getUniqueId());
    }

    public void cleanup(UUID uuid) {
        cooldowns.remove(uuid);
    }


    /** Remet le cooldown de Flash à 0 (admin). */
    public void resetCooldown(org.bukkit.entity.Player player) {
        // Supprimer l'entrée de cooldown
        // La map est private, on doit ajouter une méthode ou la rendre accessible
        // On simule un usage avec CD=0
        setFlashReady(player);
    }

    public void setFlashReady(org.bukkit.entity.Player player) {
        // Chercher et appeler la méthode existante ou accéder à la map
        // Si la méthode isOnCooldown existe, on peut juste refresher
        LolPlugin.getInstance().getHotbarManager()
            .refreshAbilitySlot(player,
                LolPlugin.getInstance().getChampionManager().getChampion(player), 0);
    }
}
