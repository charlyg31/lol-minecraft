package fr.lolmc.instance;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Gestionnaire d'instances de partie.
 *
 * Responsabilités :
 *  1. Copier le monde template (lolmc_template) → lolmc_game_N via Files.walkFileTree
 *  2. Charger le monde copié avec WorldCreator
 *  3. Créer et démarrer une GameInstance sur ce monde
 *  4. Mapper chaque joueur → son GameInstance
 *  5. Nettoyer et supprimer le monde à la fin de la partie
 *
 * Convention de nommage :
 *   Template : world.template dans config.yml (défaut : lolmc_template)
 *   Instance : lolmc_game_1, lolmc_game_2… (compteur atomique, jamais réutilisé)
 *
 * Isolation :
 *   - WorldContext.getInstanceOf(player) retourne le World de la GameInstance du joueur
 *   - Chaque GameInstance a ses propres managers → zéro fuite entre parties simultanées
 *
 * Multiverse :
 *   Si Multiverse-Core est installé, on utilise son API pour charger/décharger.
 *   Sinon, on utilise WorldCreator natif Paper (fonctionne sans Multiverse).
 */
public class InstanceManager {

    // Préfixe des mondes d'instances
    public static final String INSTANCE_PREFIX = "lolmc_game_";

    private final LolPlugin            plugin;
    private final Logger               log;
    private final AtomicInteger        idCounter   = new AtomicInteger(0);
    private final Map<Integer, GameInstance> instances = new ConcurrentHashMap<>();
    // UUID joueur → GameInstance
    private final Map<UUID, GameInstance>   playerMap = new ConcurrentHashMap<>();

    private String templateWorldName;

    public InstanceManager(LolPlugin plugin) {
        this.plugin            = plugin;
        this.log               = plugin.getLogger();
        this.templateWorldName = plugin.getConfig().getString("world.template", "lolmc_template");
    }

    // ══════════════════════════════════════════════════════════════════════
    // CRÉER UNE INSTANCE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Crée une nouvelle instance de partie de façon asynchrone.
     * La copie du monde se fait en async (I/O), le chargement en sync.
     *
     * @param blue      Liste des UUIDs équipe bleue
     * @param red       Liste des UUIDs équipe rouge
     * @param callback  Appelé sur le thread principal quand l'instance est prête
     */
    public void createInstance(List<UUID> blue, List<UUID> red,
                               java.util.function.Consumer<GameInstance> callback) {
        int instanceId = idCounter.incrementAndGet();
        String worldName = INSTANCE_PREFIX + instanceId;

        log.info("[InstanceManager] Création instance #" + instanceId + " (" + worldName + ")");

        // 1. Copie asynchrone du monde template
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyWorldFolder(templateWorldName, worldName);
                log.info("[InstanceManager] Copie terminée pour " + worldName);
            } catch (Exception e) {
                log.severe("[InstanceManager] Erreur copie: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () ->
                    callback.accept(null)); // signaler l'échec
                return;
            }

            // 2. Chargement du monde sur le thread principal
            Bukkit.getScheduler().runTask(plugin, () -> {
                World world = loadWorld(worldName);
                if (world == null) {
                    log.severe("[InstanceManager] Impossible de charger " + worldName);
                    callback.accept(null);
                    return;
                }

                // 3. Créer la GameInstance
                GameInstance instance = new GameInstance(instanceId, world);
                instances.put(instanceId, instance);

                // 4. Enregistrer les joueurs
                for (UUID uid : blue) {
                    playerMap.put(uid, instance);
                    instance.addPlayer(Bukkit.getPlayer(uid), TeamManager.Team.BLUE);
                }
                for (UUID uid : red) {
                    playerMap.put(uid, instance);
                    instance.addPlayer(Bukkit.getPlayer(uid), TeamManager.Team.RED);
                }

                instance.setState(GameInstance.State.READY);
                log.info("[InstanceManager] Instance #" + instanceId + " prête (" + worldName + ")");
                callback.accept(instance);
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // FERMER UNE INSTANCE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Arrête et supprime une instance après la fin de partie.
     * Les joueurs sont déjà téléportés avant cet appel.
     *
     * @param instance L'instance à supprimer
     * @param delayTicks Délai avant suppression (laisse le temps aux joueurs de partir)
     */
    public void closeInstance(GameInstance instance, long delayTicks) {
        new BukkitRunnable() {
            @Override public void run() {
                int id = instance.getId();
                String worldName = instance.getWorldName();

                // Arrêter les systèmes de jeu
                instance.stop();

                // Expulser les joueurs restants vers le spawn principal
                World defaultWorld = Bukkit.getWorlds().get(0);
                for (Player p : instance.getWorld().getPlayers()) {
                    p.teleport(defaultWorld.getSpawnLocation());
                    p.sendMessage("§cPartie terminée — retour au monde principal.");
                }

                // Supprimer les mappings joueurs
                for (UUID uid : instance.getParticipants()) playerMap.remove(uid);
                instances.remove(id);

                // Décharger et supprimer le monde (asynchrone)
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    boolean unloaded = unloadWorld(worldName);
                    if (unloaded) {
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            deleteWorldFolder(worldName);
                            log.info("[InstanceManager] Instance #" + id + " supprimée.");
                        });
                    }
                }, 60L); // 3s pour que tous les joueurs soient partis
            }
        }.runTaskLater(plugin, delayTicks);
    }

    // ══════════════════════════════════════════════════════════════════════
    // RECHERCHE
    // ══════════════════════════════════════════════════════════════════════

    /** Retourne l'instance d'un joueur (null si hors partie). */
    public GameInstance getInstanceOf(Player player) {
        return playerMap.get(player.getUniqueId());
    }

    public GameInstance getInstanceOf(UUID uuid) {
        return playerMap.get(uuid);
    }

    /** Retourne toutes les instances actives. */
    public Collection<GameInstance> getAllInstances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    public int getActiveCount() { return instances.size(); }

    // ══════════════════════════════════════════════════════════════════════
    // OPÉRATIONS SUR LES MONDES
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Charge un monde par son nom.
     * Utilise Multiverse si disponible, WorldCreator sinon.
     */
    private World loadWorld(String name) {
        // Essayer Multiverse d'abord
        var mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mvPlugin != null && mvPlugin.isEnabled()) {
            try {
                var mv = (com.onarandombox.MultiverseCore.MultiverseCore) mvPlugin;
                if (mv.getMVWorldManager().loadWorld(name)) {
                    World w = Bukkit.getWorld(name);
                    if (w != null) {
                        log.info("[InstanceManager] Monde chargé via Multiverse: " + name);
                        return w;
                    }
                }
            } catch (Exception e) {
                log.warning("[InstanceManager] Multiverse erreur, fallback Paper: " + e.getMessage());
            }
        }

        // Fallback : WorldCreator natif
        WorldCreator creator = new WorldCreator(name);
        // Copier les paramètres du template si disponible
        World template = Bukkit.getWorld(templateWorldName);
        if (template != null) {
            creator.environment(template.getEnvironment());
            creator.type(template.getWorldType() != null
                ? template.getWorldType() : org.bukkit.WorldType.FLAT);
        }
        World world = creator.createWorld();
        if (world != null) {
            world.setAutoSave(false); // pas de sauvegarde auto pour les instances
            log.info("[InstanceManager] Monde chargé via WorldCreator: " + name);
        }
        return world;
    }

    /**
     * Décharge un monde proprement.
     */
    private boolean unloadWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) return true;

        // Multiverse
        var mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mvPlugin != null && mvPlugin.isEnabled()) {
            try {
                var mv = (com.onarandombox.MultiverseCore.MultiverseCore) mvPlugin;
                if (mv.getMVWorldManager().unloadWorld(name)) return true;
            } catch (Exception ignored) {}
        }

        // Fallback
        return Bukkit.unloadWorld(world, false); // false = ne pas sauvegarder
    }

    // ══════════════════════════════════════════════════════════════════════
    // COPIE / SUPPRESSION DU DOSSIER MONDE (I/O)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Copie le dossier du monde template vers un nouveau nom.
     * Exclut session.lock, uid.dat et uid.dat.old.
     * Doit être appelé depuis un thread asynchrone.
     */
    private void copyWorldFolder(String source, String dest) throws IOException {
        File serverRoot = Bukkit.getWorldContainer();
        Path srcPath    = new File(serverRoot, source).toPath();
        Path destPath   = new File(serverRoot, dest).toPath();

        if (!Files.exists(srcPath)) {
            throw new IOException("Monde template introuvable : " + source
                + " (chemin : " + srcPath.toAbsolutePath() + ")");
        }

        // Copier récursivement
        Files.walkFileTree(srcPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = srcPath.relativize(dir);
                Path target   = destPath.resolve(relative);
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                // Exclure les fichiers de verrou et identifiants uniques
                if (name.equals("session.lock") || name.equals("uid.dat")
                        || name.equals("uid.dat.old"))
                    return FileVisitResult.CONTINUE;
                Path relative = srcPath.relativize(file);
                Files.copy(file, destPath.resolve(relative),
                    StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Supprime le dossier d'une instance.
     * Doit être appelé depuis un thread asynchrone.
     */
    private void deleteWorldFolder(String worldName) {
        File serverRoot = Bukkit.getWorldContainer();
        Path worldPath  = new File(serverRoot, worldName).toPath();

        if (!Files.exists(worldPath)) return;

        try {
            Files.walkFileTree(worldPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("[InstanceManager] Dossier supprimé : " + worldName);
        } catch (IOException e) {
            log.warning("[InstanceManager] Erreur suppression " + worldName + ": " + e.getMessage());
        }
    }

    // ── Nettoyage global (onDisable) ──────────────────────────────────────

    /** Arrête toutes les instances actives (onDisable). */
    public void shutdownAll() {
        for (GameInstance instance : new ArrayList<>(instances.values())) {
            try { instance.stop(); } catch (Exception ignored) {}
        }
        instances.clear();
        playerMap.clear();
        log.info("[InstanceManager] Toutes les instances arrêtées.");
    }
}
