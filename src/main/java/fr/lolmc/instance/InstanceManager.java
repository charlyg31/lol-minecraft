package fr.lolmc.instance;

import fr.lolmc.LolPlugin;
import fr.lolmc.team.TeamManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

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
 * <p>
 * Responsabilités :
 *  1. Copier le monde template (lolmc_template) → lolmc_game_N via Files.walkFileTree
 *  2. Charger le monde copié avec WorldCreator
 *  3. Créer et démarrer une GameInstance sur ce monde
 *  4. Mapper chaque joueur → son GameInstance
 *  5. Nettoyer et supprimer le monde à la fin de la partie
 * <p>
 * Convention de nommage :
 *   Template : world.template dans config.yml (défaut : lolmc_template)
 *   Instance : lolmc_game_1, lolmc_game_2… (compteur atomique, jamais réutilisé)
 * <p>
 * Isolation :
 *   - WorldContext.getInstanceOf(player) retourne le World de la GameInstance du joueur
 *   - Chaque GameInstance a ses propres managers → zéro fuite entre parties simultanées
 * <p>
 * Multiverse :
 *   Si Multiverse-Core est installé, on utilise son API pour charger/décharger.
 *   Sinon, on utilise WorldCreator natif Paper (fonctionne sans Multiverse).
 */
public final class InstanceManager {

    // Préfixe des mondes d'instances
    public static final String INSTANCE_PREFIX = "lolmc_game_";
    private volatile String mainWorldName = null;

    private final LolPlugin            plugin;
    private final Logger               log;
    private final AtomicInteger        idCounter   = new AtomicInteger(0);
    private final Map<Integer, GameInstance> instances = new ConcurrentHashMap<>();
    // UUID joueur → GameInstance
    private final Map<UUID, GameInstance>   playerMap = new ConcurrentHashMap<>();

    private String templateWorldName;

    // Mondes en cours de création (copie) ou de fermeture : le nettoyage ne doit pas y toucher.
    private final Set<String> busyWorlds = ConcurrentHashMap.newKeySet();
    // Instance id -> tick (temps serveur en ms) depuis lequel son monde est vide.
    private final Map<Integer, Long> emptySince = new ConcurrentHashMap<>();
    private static final long EMPTY_GRACE_MS = 60_000L;
    private static final long CLEANUP_PERIOD_TICKS = 20L * 30;

    public InstanceManager(LolPlugin plugin) {
        this.plugin            = plugin;
        this.log               = plugin.getLogger();
        this.templateWorldName = plugin.getConfig().getString("world.template", "lolmc_template");
        // Nom du monde principal lu ici (thread principal) : les suppressions asynchrones en ont besoin
        if (!Bukkit.getWorlds().isEmpty()) this.mainWorldName = Bukkit.getWorlds().getFirst().getName();
        startCleanupTask();
    }

    /**
     * Supprime tout monde d'instance existant sans joueur dedans :
     * au démarrage (restes d'un crash), puis toutes les 30 s.
     * Un monde d'une instance en cours n'est supprimé qu'après 60 s sans joueur.
     */
    private void startCleanupTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupEmptyWorlds, 20L, CLEANUP_PERIOD_TICKS);
    }

    private void cleanupEmptyWorlds() {
        long now = System.currentTimeMillis();

        // 1. Instances enregistrées dont le monde reste vide trop longtemps.
        for (GameInstance inst : new ArrayList<>(instances.values())) {
            if (inst.getState() == GameInstance.State.CREATING) continue;
            if (inst.getWorld() != null && inst.getWorld().getPlayers().isEmpty()) {
                long since = emptySince.computeIfAbsent(inst.getId(), _ -> now);
                if (now - since >= EMPTY_GRACE_MS) {
                    emptySince.remove(inst.getId());
                    log.info("[InstanceManager] Instance #" + inst.getId() + " vide : suppression.");
                    closeInstance(inst, 0L);
                }
            } else {
                emptySince.remove(inst.getId());
            }
        }

        // 2. Mondes chargés avec le préfixe, sans instance ni joueur.
        Set<String> owned = new HashSet<>();
        for (GameInstance inst : instances.values()) owned.add(inst.getWorldName());
        for (World w : new ArrayList<>(Bukkit.getWorlds())) {
            String name = w.getName();
            if (!name.startsWith(INSTANCE_PREFIX) || owned.contains(name) || busyWorlds.contains(name)) continue;
            if (!w.getPlayers().isEmpty()) continue;
            busyWorlds.add(name);
            if (unloadWorld(name)) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    deleteWorldFolder(name);
                    busyWorlds.remove(name);
                });
            } else {
                busyWorlds.remove(name);
            }
        }

        // 3a. Restes migrés par Paper 26+ (<monde>/dimensions/minecraft/lolmc_game_N)
        File[] migrated = migratedDir("x").getParent().toFile().listFiles(
                f -> f.isDirectory() && f.getName().startsWith(INSTANCE_PREFIX));
        if (migrated != null) {
            for (File dir : migrated) {
                String name = dir.getName();
                if (owned.contains(name) || busyWorlds.contains(name) || Bukkit.getWorld(name) != null) continue;
                busyWorlds.add(name);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    deleteWorldFolder(name);
                    busyWorlds.remove(name);
                });
            }
        }

        // 3. Dossiers restants sur le disque (non chargés, sans instance).
        File[] dirs = Bukkit.getWorldContainer().listFiles(
                f -> f.isDirectory() && f.getName().startsWith(INSTANCE_PREFIX));
        if (dirs == null) return;
        for (File dir : dirs) {
            String name = dir.getName();
            if (owned.contains(name) || busyWorlds.contains(name) || Bukkit.getWorld(name) != null) continue;
            busyWorlds.add(name);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                deleteWorldFolder(name);
                busyWorlds.remove(name);
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CRÉER UNE INSTANCE
    // ══════════════════════════════════════════════════════════════════════

    /** Créations d'instances en cours (comptées dans la limite world.max-instances). */
    private final AtomicInteger creating = new AtomicInteger(0);

    /**
     * Crée une nouvelle instance de partie de façon asynchrone.
     * La copie du monde se fait en async (I/O), le chargement en sync.
     *
     * @param blue        Liste des UUIDs équipe bleue
     * @param red         Liste des UUIDs équipe rouge
     * @param callbackIn  Appelé sur le thread principal quand l'instance est prête (null si échec)
     */
    public void createInstance(List<UUID> blue, List<UUID> red,
                               java.util.function.Consumer<GameInstance> callbackIn) {
        int max = plugin.getConfig().getInt("world.max-instances", 0);
        if (max > 0 && instances.size() + creating.get() >= max) {
            log.warning("[InstanceManager] Limite atteinte (world.max-instances=" + max + ") — création refusée.");
            callbackIn.accept(null);
            return;
        }
        creating.incrementAndGet();
        final java.util.function.Consumer<GameInstance> callback = inst -> {
            creating.decrementAndGet();
            callbackIn.accept(inst);
        };
        int instanceId = idCounter.incrementAndGet();
        String worldName = INSTANCE_PREFIX + instanceId;

        log.info("[InstanceManager] Création instance #" + instanceId + " (" + worldName + ")");
        busyWorlds.add(worldName);

        // 1. Copie asynchrone du monde template
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteWorldFolder(worldName); // restes d'une ancienne partie du même nom
                copyWorldFolder(worldName);
                log.info("[InstanceManager] Copie terminée pour " + worldName);
            } catch (Exception e) {
                log.severe("[InstanceManager] Erreur copie: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busyWorlds.remove(worldName);
                    callback.accept(null); // signaler l'échec
                });
                return;
            }

            // 2. Chargement du monde sur le thread principal
            Bukkit.getScheduler().runTask(plugin, () -> {
                World world = loadWorld(worldName);
                if (world == null) {
                    log.severe("[InstanceManager] Impossible de charger " + worldName);
                    busyWorlds.remove(worldName);
                    callback.accept(null);
                    return;
                }

                // 3. Créer la GameInstance
                GameInstance instance = new GameInstance(instanceId, world);
                instances.put(instanceId, instance);
                busyWorlds.remove(worldName); // désormais protégé par instances

                // 4. Enregistrer les joueurs
                for (UUID uid : blue) {
                    var p = Bukkit.getPlayer(uid);
                    if (p == null) continue; // déconnecté entre l'inscription et le lancement
                    playerMap.put(uid, instance);
                    instance.addPlayer(p, TeamManager.Team.BLUE);
                }
                for (UUID uid : red) {
                    var p = Bukkit.getPlayer(uid);
                    if (p == null) continue;
                    playerMap.put(uid, instance);
                    instance.addPlayer(p, TeamManager.Team.RED);
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

                // Copier la liste des participants AVANT stop() (qui vide la liste)
                Set<UUID> participantsCopy = new HashSet<>(instance.getParticipants());

                // Arrêter les systèmes de jeu
                instance.stop();

                // Expulser les joueurs restants vers le spawn principal
                World defaultWorld = Bukkit.getWorlds().getFirst();
                for (Player p : instance.getWorld().getPlayers()) {
                    p.teleport(defaultWorld.getSpawnLocation());
                }

                // Supprimer les mappings joueurs
                for (UUID uid : participantsCopy) playerMap.remove(uid);
                instances.remove(id);
                emptySince.remove(id);
                busyWorlds.add(worldName);

                // Décharger et supprimer le monde (asynchrone)
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    boolean unloaded = unloadWorld(worldName);
                    if (unloaded) {
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            deleteWorldFolder(worldName);
                            busyWorlds.remove(worldName);
                            log.info("[InstanceManager] Instance #" + id + " supprimée.");
                        });
                    } else {
                        busyWorlds.remove(worldName); // le nettoyage périodique réessaiera
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

    /** Retrouve l'instance propriétaire d'un monde donné (par nom), ou null si aucune (mode partie unique). */
    public GameInstance getInstanceByWorldName(String worldName) {
        for (GameInstance inst : instances.values()) {
            if (inst.getWorldName().equals(worldName)) return inst;
        }
        return null;
    }

    /** Retourne toutes les instances actives. */
    public Collection<GameInstance> getAllInstances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    // ══════════════════════════════════════════════════════════════════════
    // OPÉRATIONS SUR LES MONDES
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Charge un monde par son nom.
     * Utilise Multiverse si disponible, WorldCreator sinon.
     */
    @SuppressWarnings("deprecation") // CORRECTION : Supprime proprement le warning lié à getWorldType() pour la cross-compatibilité
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
            } catch (Exception ex) { log.log(java.util.logging.Level.WARNING, "[InstanceManager] Multiverse : échec du déchargement de " + name, ex); }
        }

        // Fallback
        return Bukkit.unloadWorld(world, false); // false = ne pas sauvegarder
    }

    // ══════════════════════════════════════════════════════════════════════
    // COPIE / SUPPRESSION DU DOSSIER MONDE (I/O)
    // ══════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════
    // MONDE TEMPLATE (source des copies)
    // ══════════════════════════════════════════════════════════════════════

    /** Nouveau nom de monde template (après /lola template save), persisté dans config.yml. */
    public void setTemplateWorldName(String name) {
        this.templateWorldName = name;
        plugin.getConfig().set("world.template", name);
        plugin.saveConfig();
    }

    /** Dossier du template dans le plugin : plugins/LolMC/template */
    public Path pluginTemplateDir() {
        return plugin.getDataFolder().toPath().resolve("template");
    }

    /** Un dossier est un monde exploitable s'il contient level.dat ou des régions. */
    private static boolean looksLikeWorld(Path dir) {
        return Files.isDirectory(dir) && (Files.exists(dir.resolve("level.dat"))
                || Files.isDirectory(dir.resolve("region"))
                || Files.isDirectory(dir.resolve("dimensions")));
    }

    /**
     * Source des copies : 1) plugins/LolMC/template, 2) dossier du monde template à la racine du
     * serveur (ancien comportement), 3) emplacement migré par Paper 26. Null si introuvable.
     */
    public Path resolveTemplateSource() {
        Path inPlugin = pluginTemplateDir();
        if (looksLikeWorld(inPlugin)) return inPlugin;
        Path legacy = new File(Bukkit.getWorldContainer(), templateWorldName).toPath();
        if (looksLikeWorld(legacy)) return legacy;
        Path migrated = migratedDir(templateWorldName);
        if (looksLikeWorld(migrated)) return migrated;
        return null;
    }

    /** Texte décrivant la source utilisée (pour /lola template info). */
    public String describeTemplateSource() {
        Path src = resolveTemplateSource();
        if (src == null) return "introuvable";
        String where = src.equals(pluginTemplateDir()) ? "dossier du plugin" : "dossier du serveur (ancien emplacement)";
        return where + " → " + src.toAbsolutePath();
    }

    /**
     * Enregistre le monde template actuel (en jeu) dans plugins/LolMC/template.
     * Sauvegarde le monde puis copie ses fichiers en asynchrone ; {@code done} est rappelé sur le thread principal.
     */
    public void snapshotTemplate(World world, java.util.function.Consumer<String> done) {
        if (world == null) { done.accept("❌ Monde template introuvable (non chargé)."); return; }
        world.save();
        final Path src = world.getWorldFolder().toPath();
        final Path dst = pluginTemplateDir();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String msg;
            try {
                deleteTree(dst, "template (remplacement)");
                copyTree(src, dst);
                msg = "✔ Template enregistré dans " + dst.toAbsolutePath()
                        + " (source : " + src.toAbsolutePath() + ").";
            } catch (Exception e) {
                log.severe("[InstanceManager] Snapshot template: " + e);
                msg = "❌ Échec de l'enregistrement du template : " + e.getMessage();
            }
            final String out = msg;
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(out));
        });
    }

    /**
     * Copie le dossier du monde template vers un nouveau nom.
     * Exclut session.lock, uid.dat et uid.dat.old.
     * Doit être appelé depuis un thread asynchrone.
     */
    private void copyWorldFolder(String dest) throws IOException {
        Path srcPath = resolveTemplateSource();
        if (srcPath == null) {
            throw new IOException("Monde template introuvable : ni " + pluginTemplateDir().toAbsolutePath()
                    + " ni le dossier « " + templateWorldName + " » à la racine du serveur"
                    + " (utilise /lola template save pour enregistrer le template)");
        }
        copyTree(srcPath, new File(Bukkit.getWorldContainer(), dest).toPath());
    }

    /** Copie récursive d'un monde (sans session.lock ni uid.dat). */
    private void copyTree(Path srcPath, Path destPath) throws IOException {
        Files.walkFileTree(srcPath, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(destPath.resolve(srcPath.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.equals("session.lock") || name.equals("uid.dat") || name.equals("uid.dat.old"))
                    return FileVisitResult.CONTINUE;
                Files.copy(file, destPath.resolve(srcPath.relativize(file)),
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
        deleteTree(new File(serverRoot, worldName).toPath(), worldName);
        // Paper 26+ range les mondes API dans <monde principal>/dimensions/minecraft/<nom>
        deleteTree(migratedDir(worldName), worldName + " (dimensions)");
    }

    /** Emplacement où Paper 26+ migre un monde créé par l'API. */
    private Path migratedDir(String worldName) {
        String main = mainWorldName;
        if (main == null) {
            // Lu sur le thread principal ; hors thread principal on ne fige pas une valeur par défaut
            if (Bukkit.isPrimaryThread() && !Bukkit.getWorlds().isEmpty()) {
                main = Bukkit.getWorlds().getFirst().getName();
                mainWorldName = main;
            } else {
                main = "world";
            }
        }
        return new File(Bukkit.getWorldContainer(), main).toPath()
                .resolve("dimensions").resolve("minecraft").resolve(worldName);
    }

    private void deleteTree(Path worldPath, String label) {
        if (!Files.exists(worldPath)) return;
        try {
            Files.walkFileTree(worldPath, new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("[InstanceManager] Dossier supprimé : " + label);
        } catch (IOException e) {
            log.warning("[InstanceManager] Erreur suppression " + label + ": " + e.getMessage());
        }
    }

    // ── Nettoyage global (onDisable) ──────────────────────────────────────

    /** Arrête toutes les instances actives (onDisable). */
    public void shutdownAll() {
        for (GameInstance instance : new ArrayList<>(instances.values())) {
            try { instance.stop(); } catch (Exception ex) { log.log(java.util.logging.Level.WARNING, "[InstanceManager] Arrêt de l'instance #" + instance.getId(), ex); }
        }
        instances.clear();
        playerMap.clear();
        log.info("[InstanceManager] Toutes les instances arrêtées.");
    }
}