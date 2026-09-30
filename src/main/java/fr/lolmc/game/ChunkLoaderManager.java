package fr.lolmc.game;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.HashSet;
import java.util.Set;

/**
 * Gère le chargement forcé des chunks (Force-Loading) autour des sbires et monstres
 * pour éviter qu'ils ne se figent lorsque aucun joueur n'est à proximité.
 */
public class ChunkLoaderManager extends BukkitRunnable {

    // Contient les clés des chunks actuellement forcés par le plugin ("nom_monde:x:z")
    private final Set<String> currentlyForceLoaded = new HashSet<>();

    @Override
    public void run() {
        Set<String> chunksToLoad = new HashSet<>();

        // 1. Analyse de toutes les entités des mondes du serveur
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {

                // On cible uniquement tes sbires ou tes monstres de la jungle
                if (isMinionOrMonster(entity)) {
                    Chunk centerChunk = entity.getLocation().getChunk();
                    int cx = centerChunk.getX();
                    int cz = centerChunk.getZ();

                    // On englobe le chunk de l'entité + 1 chunk aux alentours (Carré de 3x3 chunks)
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            String chunkKey = world.getName() + ":" + (cx + dx) + ":" + (cz + dz);
                            chunksToLoad.add(chunkKey);
                        }
                    }
                }
            }
        }

        // 2. On applique le Force-Load sur les chunks qui viennent d'entrer dans la zone d'action
        for (String chunkKey : chunksToLoad) {
            if (!currentlyForceLoaded.contains(chunkKey)) {
                setChunkForceLoaded(chunkKey, true);
                currentlyForceLoaded.add(chunkKey);
            }
        }

        // 3. SÉCURITÉ ANTI-LAG : On retire le Force-Load des chunks qui n'ont plus aucun sbire
        currentlyForceLoaded.removeIf(chunkKey -> {
            if (!chunksToLoad.contains(chunkKey)) {
                setChunkForceLoaded(chunkKey, false);
                return true; // Retiré de notre set de suivi
            }
            return false;
        });
    }

    /**
     * Détecte les vrais sbires (MinionManager) et monstres de jungle (JungleManager),
     * marqués via leur PersistentDataContainer respectif — jamais via des Scoreboard Tags,
     * qui ne sont posés nulle part dans ce projet (le check précédent ne matchait donc jamais
     * rien, laissant les entités éloignées des joueurs se figer une fois leur chunk déchargé).
     */
    private boolean isMinionOrMonster(Entity entity) {
        if (!(entity instanceof LivingEntity le)) return false;
        return fr.lolmc.game.MinionManager.isMinion(le) || fr.lolmc.game.JungleManager.isJungleMonster(entity);
    }

    /**
     * Active ou désactive le force-load natif de Minecraft sur un chunk précis.
     */
    private void setChunkForceLoaded(String chunkKey, boolean force) {
        String[] parts = chunkKey.split(":");
        World world = Bukkit.getWorld(parts[0]);
        if (world != null) {
            int x = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            // API native de Spigot/Paper (1.13+) extrêmement stable
            world.setChunkForceLoaded(x, z, force);
        }
    }

    /**
     * À exécuter lors de l'arrêt du plugin ou d'un reset de partie pour tout nettoyer en RAM.
     */
    public void clearAllForcedChunks() {
        for (String chunkKey : currentlyForceLoaded) {
            setChunkForceLoaded(chunkKey, false);
        }
        currentlyForceLoaded.clear();
    }
}