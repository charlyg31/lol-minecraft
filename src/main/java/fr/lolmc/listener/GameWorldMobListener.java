package fr.lolmc.listener;

import fr.lolmc.LolPlugin;
import fr.lolmc.game.MinionManager;
import fr.lolmc.util.WorldContext;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Créatures vanilla sur la carte LoL :
 * <ul>
 *   <li>pas d'apparition naturelle (poulets, chauves-souris, monstres de nuit, renforts de zombies…) ;
 *       les entités du plugin apparaissent avec la raison CUSTOM et ne sont pas touchées ;</li>
 *   <li>les créatures naturelles déjà présentes dans les chunks chargés sont supprimées ;</li>
 *   <li>l'IA vanilla des sbires ne peut pas viser un allié (ni un joueur caché ou hors partie).</li>
 * </ul>
 */
public class GameWorldMobListener implements Listener {

    private static final Set<CreatureSpawnEvent.SpawnReason> NATURAL = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.CHUNK_GEN,
            CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.MOUNT,
            CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.RAID,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
            CreatureSpawnEvent.SpawnReason.TRAP,
            CreatureSpawnEvent.SpawnReason.LIGHTNING,
            CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.EGG,
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK,
            CreatureSpawnEvent.SpawnReason.INFECTION);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNaturalSpawn(CreatureSpawnEvent e) {
        if (NATURAL.contains(e.getSpawnReason()) && WorldContext.isGameWorld(e.getLocation().getWorld()))
            e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!WorldContext.isGameWorld(e.getWorld())) return;
        for (Entity en : e.getEntities()) if (isStrayVanillaMob(en)) en.remove();
    }

    /** Supprime les créatures naturelles des chunks déjà chargés (début de partie). */
    public static void purgeStrayMobs(org.bukkit.World world) {
        if (world == null) return;
        for (LivingEntity en : world.getLivingEntities()) if (isStrayVanillaMob(en)) en.remove();
    }

    /** Créature vivante non créée par le plugin : ni tag LolMC, ni nom personnalisé. */
    private static boolean isStrayVanillaMob(Entity en) {
        if (!(en instanceof LivingEntity) || en instanceof Player || en.customName() != null) return false;
        String ns = LolPlugin.getInstance().getName().toLowerCase(java.util.Locale.ROOT);
        for (var key : en.getPersistentDataContainer().getKeys())
            if (key.getNamespace().equals(ns)) return false;
        return !(en instanceof org.bukkit.entity.ArmorStand);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMinionTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity le) || !MinionManager.isMinion(le)) return;
        if (!MinionManager.canTarget(le, p)) e.setCancelled(true);
    }
}
