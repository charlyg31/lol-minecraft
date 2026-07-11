package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.ability.base.BasicAttackAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les auto-attaques façon LoL avec 3 types d'animation :
 *
 *  MELEE  (portée ≤ 3 blocs) : slash SWEEP_ATTACK + impact CRIT au contact.
 *  MAGE   (portée > 3, dégâts MAGICAL) : orbe magique animée (WITCH_MAGIC_PARTICLE)
 *         qui voyage vers la cible avec une traînée.
 *  ADC    (portée > 3, dégâts PHYSICAL) : projectile rapide (CRIT + traînée blanche)
 *         simulant une flèche/balle, avec son de tir.
 *
 * Le type est déduit de la BasicAttackAbility du champion (slot 0).
 */
public class AutoAttackManager {

    /** Les 3 types d'animation d'auto-attaque. */
    public enum AAType { MELEE, MAGE, ADC }

    // Dernière auto-attaque par joueur (pour respecter la cadence)
    private final Map<UUID, Long> lastAttack = new HashMap<>();

    // ══════════════════════════════════════════════════════════
    // LOCK-ON façon LoL : un clic verrouille la cible, puis les
    // AA s'enchaînent automatiquement à la cadence d'attack speed
    // tant que la cible reste à portée. Cliquer ailleurs change
    // de cible ; cliquer dans le vide annule (comme le S de LoL).
    // ══════════════════════════════════════════════════════════
    private final Map<UUID, UUID> lockedTargets = new HashMap<>();
    private final Map<UUID, Long> lockSetAt = new HashMap<>();
    private static final long TOGGLE_GRACE_MS = 500; // anti-spam du re-clic
    private org.bukkit.scheduler.BukkitTask autoFireTask;

    /** Verrouille la cible : les AA continueront automatiquement. */
    public void lockTarget(Player attacker, LivingEntity target) {
        if (!LolPlugin.getInstance().getConfig().getBoolean("combat.aa-lock-on", true)) return;
        lockedTargets.put(attacker.getUniqueId(), target.getUniqueId());
        lockSetAt.put(attacker.getUniqueId(), System.currentTimeMillis());
        String name = target instanceof Player p ? p.getName()
            : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serializeOr(target.customName(), target.getType().name());
        attacker.sendActionBar(Component.text("🔒 " + name, NamedTextColor.YELLOW));
        startAutoFire();
    }

    /** True si ce joueur est déjà verrouillé sur cette cible. */
    public boolean isLockedOn(Player attacker, LivingEntity target) {
        return target.getUniqueId().equals(lockedTargets.get(attacker.getUniqueId()));
    }

    /**
     * Toggle façon interrupteur : re-cliquer la MÊME cible coupe l'auto-fire
     * (le clic porte quand même son attaque manuelle) ; une autre cible bascule.
     */
    public void toggleLock(Player attacker, LivingEntity target) {
        if (isLockedOn(attacker, target)) {
            // Spam-clic (reflexe Minecraft) : dans la fenetre de grace,
            // le re-clic n'attaque qu'en manuel, le lock est CONSERVE
            Long since = lockSetAt.get(attacker.getUniqueId());
            long now = System.currentTimeMillis();
            if (since != null && now - since < TOGGLE_GRACE_MS) {
                lockSetAt.put(attacker.getUniqueId(), now); // fenetre glissante
                return;
            }
            clearLock(attacker);
        } else lockTarget(attacker, target);
    }

    /** Annule le verrouillage (re-clic, clic dans le vide, structure). */
    public void clearLock(Player attacker) {
        lockSetAt.remove(attacker.getUniqueId());
        if (lockedTargets.remove(attacker.getUniqueId()) != null)
            attacker.sendActionBar(Component.text("🔓 Verrouillage annulé", NamedTextColor.GRAY));
    }

    // ══════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════
    //  Anneau visuel de ciblage/lock : BlockDisplay au lieu de
    //  particules (invisibles si le joueur les désactive en options).
    //  Chaque anneau n'est visible QUE pour son viewer (showEntity),
    //  ce qui permet naturellement 2 couleurs différentes pour 2
    //  joueurs regardant la même cible (l'un locke, l'autre non).
    //  Vert  : cible verrouillée (lock-on actif)
    //  Rouge : cible actuellement sous le viseur
    // ══════════════════════════════════════════════════════════
    private org.bukkit.scheduler.BukkitTask visualsTask;

    /** Anneaux actifs par (viewer, cible) : 4 BlockDisplay formant les points cardinaux. */
    private final Map<UUID, Map<UUID, java.util.List<org.bukkit.entity.BlockDisplay>>> ringDisplays
            = new HashMap<>();

    public void startVisuals() {
        if (visualsTask != null && !visualsTask.isCancelled()) return;
        visualsTask = new BukkitRunnable() {
            int phase = 0;
            @Override public void run() {
                phase++;
                var cm = LolPlugin.getInstance().getChampionManager();
                for (Player viewer : fr.lolmc.util.WorldContext.getGamePlayers()) {
                    if (!cm.hasChampion(viewer)) continue;
                    if (viewer.getGameMode() == GameMode.SPECTATOR) {
                        clearRingsFor(viewer);
                        continue;
                    }

                    UUID stillValid1 = null, stillValid2 = null;

                    // Cible verrouillée → anneau VERT
                    LivingEntity locked = null;
                    UUID lockedId = lockedTargets.get(viewer.getUniqueId());
                    if (lockedId != null && Bukkit.getEntity(lockedId)
                            instanceof LivingEntity le && !le.isDead()
                            && le.getWorld().equals(viewer.getWorld())) {
                        locked = le;
                        drawTargetRing(viewer, le, Color.fromRGB(60, 220, 90), phase);
                        stillValid1 = le.getUniqueId();
                    }

                    // Cible sous le viseur → anneau ROUGE (si différente du lock)
                    double range = cm.getChampion(viewer).getAutoAttackRange();
                    LivingEntity aimed = fr.lolmc.util.TargetingUtil.getTargetedEnemy(viewer, range);
                    if (aimed != null && !aimed.equals(locked)) {
                        drawTargetRing(viewer, aimed, Color.fromRGB(230, 60, 60), phase);
                        stillValid2 = aimed.getUniqueId();
                    }

                    // Nettoyer les anneaux de ce viewer qui ne correspondent plus
                    // à la cible lockée ni à la cible visée actuelles.
                    pruneRingsFor(viewer, stillValid1, stillValid2);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 4L, 4L);
    }

    public void stopVisuals() {
        if (visualsTask != null) { visualsTask.cancel(); visualsTask = null; }
        lockedTargets.clear();
        for (UUID viewerId : new java.util.ArrayList<>(ringDisplays.keySet())) {
            var v = Bukkit.getPlayer(viewerId);
            if (v != null) clearRingsFor(v); else removeAllRingsRaw(viewerId);
        }
        ringDisplays.clear();
    }

    /** Retire tous les anneaux d'un viewer (déconnexion, mode spectateur). */
    private void clearRingsFor(Player viewer) {
        var byTarget = ringDisplays.remove(viewer.getUniqueId());
        if (byTarget == null) return;
        for (var displays : byTarget.values()) {
            for (var d : displays) if (d != null && !d.isDead()) d.remove();
        }
    }

    /** Comme clearRingsFor, mais sans avoir besoin du Player en ligne (fallback cleanup). */
    private void removeAllRingsRaw(UUID viewerId) {
        var byTarget = ringDisplays.remove(viewerId);
        if (byTarget == null) return;
        for (var displays : byTarget.values()) {
            for (var d : displays) if (d != null && !d.isDead()) d.remove();
        }
    }

    /** Retire les anneaux du viewer dont la cible n'est plus lockée/visée. */
    private void pruneRingsFor(Player viewer, UUID keepA, UUID keepB) {
        var byTarget = ringDisplays.get(viewer.getUniqueId());
        if (byTarget == null) return;
        var it = byTarget.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID targetId = entry.getKey();
            if (!targetId.equals(keepA) && !targetId.equals(keepB)) {
                for (var d : entry.getValue()) if (d != null && !d.isDead()) d.remove();
                it.remove();
            }
        }
    }

    /**
     * Anneau rotatif aux pieds de la cible, visible par le seul viewer.
     * Réutilise 4 BlockDisplay persistants (créés au premier appel pour
     * cette paire viewer/cible, puis simplement repositionnés).
     */
    private void drawTargetRing(Player viewer, LivingEntity target, Color color, int phase) {
        var byTarget = ringDisplays.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
        var displays = byTarget.get(target.getUniqueId());

        if (displays == null) {
            displays = new java.util.ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                var d = target.getWorld().spawn(target.getLocation(), org.bukkit.entity.BlockDisplay.class, disp -> {
                    disp.setBlock(Material.LIME_STAINED_GLASS.createBlockData()); // couleur ajustée juste après
                    disp.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                    disp.setPersistent(false);
                    disp.setInterpolationDuration(4);
                    disp.setInterpolationDelay(0);
                    disp.setVisibleByDefault(false);
                });
                viewer.showEntity(LolPlugin.getInstance(), d);
                displays.add(d);
            }
            byTarget.put(target.getUniqueId(), displays);
        }

        // Couleur selon vert/rouge (approximation via un bloc de verre teinté proche)
        Material blockColor = (color.getGreen() > color.getRed())
                ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS;

        Location base = target.getLocation().add(0, 0.15, 0);
        double r = 0.65;
        double spin = phase * 0.35; // rotation lente
        float size = 0.18f;
        for (int i = 0; i < 4; i++) {
            double a = spin + Math.PI / 2 * i;
            Location pos = base.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            var d = displays.get(i);
            if (d.getBlock().getMaterial() != blockColor) d.setBlock(blockColor.createBlockData());
            d.teleport(pos);
            d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(-size / 2f, 0f, -size / 2f),
                    new org.joml.Quaternionf(),
                    new org.joml.Vector3f(size, size, size),
                    new org.joml.Quaternionf()));
        }
    }

    private void startAutoFire() {
        if (autoFireTask != null && !autoFireTask.isCancelled()) return;
        autoFireTask = new BukkitRunnable() {
            @Override public void run() {
                if (lockedTargets.isEmpty()) return;
                var cm = LolPlugin.getInstance().getChampionManager();
                var it = lockedTargets.entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    Player attacker = Bukkit.getPlayer(e.getKey());
                    if (attacker == null || !attacker.isOnline()
                            || !cm.hasChampion(attacker)) { it.remove(); continue; }
                    if (attacker.getGameMode() == GameMode.SPECTATOR) continue; // mort : lock en pause
                    var ent = Bukkit.getEntity(e.getValue());
                    if (!(ent instanceof LivingEntity target) || target.isDead()
                            || !target.getWorld().equals(attacker.getWorld())) { it.remove(); continue; }
                    if (target instanceof Player tp && (tp.getGameMode() == GameMode.SPECTATOR
                            || !cm.hasChampion(tp))) { it.remove(); continue; }

                    // Cible cachee par le fog/bush : pause (anti-wallhack),
                    // l'auto-fire reprend si elle redevient visible
                    if (!attacker.canSee(target)) continue;

                    double range = cm.getChampion(attacker).getAutoAttackRange();
                    double dist = attacker.getLocation().distance(target.getLocation());
                    if (dist > range * 1.15) { it.remove(); continue; } // partie trop loin : lock perdu
                    if (dist > range) continue;      // zone tampon (hystérésis LoL) : on attend
                    if (!canAutoAttack(attacker)) continue; // cadence AS pas prête

                    if (target instanceof Player tp) tryAutoAttack(attacker, tp);
                    else tryAutoAttackEntity(attacker, target);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 2L, 2L);
    }

    // ════════════════════════════════════════════════════════
    // LOGIQUE PRINCIPALE
    // ════════════════════════════════════════════════════════

    /**
     * Tente une auto-attaque sur un joueur.
     * @return true si l'attaque a eu lieu
     */
    public boolean tryAutoAttack(Player attacker, Player target) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker) || !cm.hasChampion(target)) return false;

        BaseChampion champ = cm.getChampion(attacker);

        // Vérifier portée
        double range = champ.getAutoAttackRange();
        double dist = attacker.getLocation().distance(target.getLocation());
        if (dist > range) {
            attacker.sendActionBar(Component.text("⚔ Hors de portée", NamedTextColor.GRAY));
            return false;
        }
        if (!checkRangeAndCooldown(attacker, champ)) return false;

        double rawDamage = champ.getStats().getFinalAD();
        boolean crit = Math.random() < champ.getStats().getFinalCritChance();
        if (crit) rawDamage *= champ.getStats().getFinalCritDamage();

        // Animation selon le type d'AA du champion
        AAType type = getAAType(champ);
        playAnimation(attacker, target, type, crit);

        // Dégâts
        DamageUtil.damage(attacker, target, rawDamage, false, DamageUtil.Type.PHYSICAL);

        // Passifs on-hit
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onAutoAttack(attacker, target);

        return true;
    }

    /**
     * Auto-attaque sur une entité non-joueur (sbire, monstre de jungle).
     * @return true si l'attaque a été portée
     */
    public boolean tryAutoAttackEntity(Player attacker, LivingEntity target) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return false;
        BaseChampion champ = cm.getChampion(attacker);

        // Vérifier portée
        double range = champ.getAutoAttackRange();
        double dist = attacker.getLocation().distance(target.getLocation());
        if (dist > range) {
            attacker.sendActionBar(Component.text("⚔ Hors de portée", NamedTextColor.GRAY));
            return false;
        }
        if (!checkRangeAndCooldown(attacker, champ)) return false;

        double rawDamage = champ.getStats().getFinalAD();
        boolean crit = Math.random() < champ.getStats().getFinalCritChance();
        if (crit) rawDamage *= champ.getStats().getFinalCritDamage();

        AAType type = getAAType(champ);
        playAnimation(attacker, target, type, crit);

        target.playHurtAnimation(attacker.getLocation().getYaw());
        if (fr.lolmc.util.VirtualHP.has(target)) {
            // Entité à HP virtuels (Baron, Elder, canon...) : dégâts virtuels
            fr.lolmc.util.VirtualHP.damage(target, rawDamage, attacker);
        } else {
            double newHealth = Math.max(0, target.getHealth() - rawDamage);
            target.setHealth(newHealth);
            fr.lolmc.util.HealthBar.update(target, newHealth, target.getAttribute(
                fr.lolmc.util.Compat.maxHealth()) != null
                ? target.getAttribute(fr.lolmc.util.Compat.maxHealth()).getValue()
                : target.getMaxHealth());
        }

        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.onAutoAttackEntity(attacker, target);

        return true;
    }

    // ════════════════════════════════════════════════════════
    // DÉTERMINATION DU TYPE D'AA
    // ════════════════════════════════════════════════════════

    /**
     * Déduit le type d'animation depuis la BasicAttackAbility du champion.
     *  - Portée > 3 + DamageType MAGICAL → MAGE
     *  - Portée > 3 + DamageType PHYSICAL → ADC
     *  - Portée ≤ 3 → MELEE (quelle que soit le type de dégâts)
     */
    public static AAType getAAType(BaseChampion champ) {
        double range = champ.getAutoAttackRange();
        if (range <= 3.0) return AAType.MELEE;

        BaseAbility aa = champ.getAbility(0);
        if (aa instanceof BasicAttackAbility baa) {
            if (baa.getDamageType() == BaseAbility.DamageType.MAGICAL) return AAType.MAGE;
        }
        return AAType.ADC;
    }

    // ════════════════════════════════════════════════════════
    // ANIMATIONS
    // ════════════════════════════════════════════════════════

    /** Joue l'animation vers une position (tir dans le vide). */
    public void playAnimationToLocation(Player attacker, Location target, AAType type, boolean crit) {
        switch (type) {
            case ADC  -> playADCAnimation(attacker, target);
            case MAGE -> playMageAnimation(attacker, target);
            default   -> {} // mêlée : pas d'animation dans le vide
        }
    }

    /** Joue l'animation d'AA appropriée entre attaquant et cible. */
    private void playAnimation(Player attacker, LivingEntity target, AAType type, boolean crit) {
        switch (type) {
            case MELEE -> playMeleeAnimation(attacker, target, crit);
            case MAGE  -> playMageAnimationLE(attacker, target, crit);
            case ADC   -> playADCAnimationLE(attacker, target, crit);
        }
    }

    /**
     * MÊLÉE : slash + impact au contact.
     * Ligne de BlockDisplay courts du poignet vers la cible pour simuler le swing.
     * Visible par tous (contrairement au lock, ceci est un effet public de combat).
     */
    private void playMeleeAnimation(Player attacker, LivingEntity target, boolean crit) {
        Location impactLoc = target.getLocation().add(0, 1, 0);
        World world = attacker.getWorld();

        // Ligne de swing (poignet → cible) : petits blocs éphémères qui
        // apparaissent puis disparaissent en quelques ticks.
        Vector dir = impactLoc.toVector()
                .subtract(attacker.getEyeLocation().toVector()).normalize();
        Location start = attacker.getEyeLocation().add(dir.clone().multiply(0.5));
        double dist = start.distance(impactLoc);
        for (double d = 0; d < Math.min(dist, 2.5); d += 0.5) {
            spawnBrief(world, start.clone().add(dir.clone().multiply(d)),
                    Material.WHITE_STAINED_GLASS, 0.15f, 3L);
        }

        // Impact
        spawnBrief(world, impactLoc, crit ? Material.ORANGE_STAINED_GLASS : Material.WHITE_STAINED_GLASS,
                crit ? 0.35f : 0.22f, crit ? 5L : 3L);
        if (crit) {
            world.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.1f);
        } else {
            world.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.0f);
        }
    }

    /**
     * MAGE : orbe magique animée qui voyage de l'attaquant vers la cible.
     * Traînée WITCH_MAGIC_PARTICLE + explosion ENCHANT à l'impact.
     * L'orbe est simulée en ticks (BukkitRunnable) pour un effet fluide.
     */
    // Surcharges Location (pour tir dans le vide ou vers une structure)
    private void playMageAnimation(Player attacker, Location end) {
        playMageAnimation(attacker, end, false);
    }
    private void playADCAnimation(Player attacker, Location end) {
        playADCAnimation(attacker, end, false);
    }

    private void playMageAnimation(Player attacker, Location end, boolean crit) {
        Location start = attacker.getEyeLocation();
        World world = attacker.getWorld();

        double totalDist = start.distance(end);
        int totalSteps = Math.max(4, (int)(totalDist / 0.35));
        Vector step = end.toVector().subtract(start.toVector())
                .normalize().multiply(totalDist / totalSteps);

        // Orbe magique : petite sphère (bloc violet) qui voyage réellement
        // vers la cible via ItemDisplay, au lieu d'une traînée de particules.
        var orb = world.spawn(start, org.bukkit.entity.ItemDisplay.class, disp -> {
            disp.setItemStack(new org.bukkit.inventory.ItemStack(Material.PURPLE_STAINED_GLASS));
            disp.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(2);
            disp.setInterpolationDelay(0);
            float s = 0.35f;
            disp.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(-s / 2f, -s / 2f, -s / 2f),
                    new org.joml.Quaternionf(),
                    new org.joml.Vector3f(s, s, s),
                    new org.joml.Quaternionf()));
        });

        new BukkitRunnable() {
            int step_ = 0;
            Location current = start.clone();
            @Override public void run() {
                if (step_ >= totalSteps) {
                    orb.remove();
                    // Impact
                    spawnBrief(world, end, Material.PURPLE_STAINED_GLASS, 0.6f, 4L);
                    spawnBrief(world, end, Material.MAGENTA_STAINED_GLASS, 0.35f, 3L);
                    if (crit) {
                        world.playSound(end, Sound.ENTITY_GENERIC_EXPLODE, 0.3f, 1.8f);
                    } else {
                        world.playSound(end, Sound.ENTITY_BLAZE_HURT, 0.5f, 1.4f);
                    }
                    cancel();
                    return;
                }
                current.add(step);
                orb.teleport(current);
                step_++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);

        world.playSound(attacker.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.4f, 1.6f);
    }

    /**
     * ADC : projectile physique rapide (flèche / balle) en CRIT + traînée blanche.
     * Plus rapide que l'orbe mage : avance de 0.6 blocs/tick.
     */
    private void playMageAnimationLE(Player attacker, LivingEntity target, boolean crit) {
        playMageAnimation(attacker, target.getLocation().add(0,1,0), crit);
    }

    private void playADCAnimation(Player attacker, Location end, boolean crit) {
        Location start = attacker.getEyeLocation();
        World world = attacker.getWorld();

        double totalDist = start.distance(end);
        // Projectile rapide : 0.6 blocs/tick (≈ 12 blocs/s)
        int totalSteps = Math.max(3, (int)(totalDist / 0.6));
        Vector step = end.toVector().subtract(start.toVector())
                .normalize().multiply(totalDist / totalSteps);

        // Tête du projectile : petit bloc clair qui voyage réellement
        var head = world.spawn(start, org.bukkit.entity.ItemDisplay.class, disp -> {
            disp.setItemStack(new org.bukkit.inventory.ItemStack(Material.WHITE_STAINED_GLASS));
            disp.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(1);
            disp.setInterpolationDelay(0);
            float s = 0.22f;
            disp.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(-s / 2f, -s / 2f, -s / 2f),
                    new org.joml.Quaternionf(),
                    new org.joml.Vector3f(s, s, s),
                    new org.joml.Quaternionf()));
        });

        new BukkitRunnable() {
            int step_ = 0;
            Location current = start.clone();
            @Override public void run() {
                if (step_ >= totalSteps) {
                    head.remove();
                    // Impact
                    spawnBrief(world, end, Material.WHITE_STAINED_GLASS, crit ? 0.5f : 0.3f, crit ? 4L : 2L);
                    if (crit) {
                        world.playSound(end, Sound.ENTITY_ARROW_HIT_PLAYER, 0.7f, 0.8f);
                    } else {
                        world.playSound(end, Sound.ENTITY_ARROW_HIT, 0.6f, 1.1f);
                    }
                    cancel();
                    return;
                }
                current.add(step);
                head.teleport(current);
                step_++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);

        // Son de tir immédiat
        world.playSound(attacker.getLocation(),
                crit ? Sound.ENTITY_ARROW_SHOOT : Sound.ENTITY_ARROW_SHOOT, 0.8f,
                crit ? 0.9f : 1.1f);
    }

    private void playADCAnimationLE(Player attacker, LivingEntity target, boolean crit) {
        playADCAnimation(attacker, target.getLocation().add(0,1,0), crit);
    }

    // ════════════════════════════════════════════════════════
    // UTILITAIRES
    // ════════════════════════════════════════════════════════

    /** Vérifie la cadence d'attaque. Renvoie false si trop tôt. */
    private boolean checkRangeAndCooldown(Player attacker, BaseChampion champ) {
        long now = System.currentTimeMillis();
        double attackSpeed = champ.getStats().getFinalAttackSpeed();
        long cooldownMs = (long)(1000.0 / Math.max(0.1, attackSpeed));
        Long last = lastAttack.get(attacker.getUniqueId());
        if (last != null && (now - last) < cooldownMs) return false;
        lastAttack.put(attacker.getUniqueId(), now);
        return true;
    }

    /** Vérifie si le joueur peut auto-attaquer (cadence respectée), sans déclencher. */
    public boolean canAutoAttack(Player attacker) {
        if (!LolPlugin.getInstance().getChampionManager().hasChampion(attacker)) return false;
        var champ = LolPlugin.getInstance().getChampionManager().getChampion(attacker);
        long now = System.currentTimeMillis();
        double attackSpeed = champ.getStats().getFinalAttackSpeed();
        long cooldownMs = (long)(1000.0 / Math.max(0.1, attackSpeed));
        Long last = lastAttack.get(attacker.getUniqueId());
        return last == null || (now - last) >= cooldownMs;
    }

    /** Déclenche le cooldown d'AA sans infliger de dégâts. */
    public void triggerCooldown(Player attacker) {
        lastAttack.put(attacker.getUniqueId(), System.currentTimeMillis());
    }

    public void cleanup(UUID uuid) {
        lastAttack.remove(uuid);
    }

    /**
     * Fait apparaître un BlockDisplay bref (impact ponctuel) qui se retire
     * de lui-même après [lifetimeTicks]. Visible par tous les joueurs proches
     * (visibilité par défaut normale, pas de showEntity/hideEntity ici).
     */
    private void spawnBrief(World world, Location loc, Material block, float size, long lifetimeTicks) {
        var d = world.spawn(loc, org.bukkit.entity.BlockDisplay.class, disp -> {
            disp.setBlock(block.createBlockData());
            disp.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(2);
            disp.setInterpolationDelay(0);
            disp.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(-size / 2f, -size / 2f, -size / 2f),
                    new org.joml.Quaternionf(),
                    new org.joml.Vector3f(size, size, size),
                    new org.joml.Quaternionf()));
        });
        new BukkitRunnable() {
            @Override public void run() {
                if (!d.isDead()) d.remove();
            }
        }.runTaskLater(LolPlugin.getInstance(), lifetimeTicks);
    }
}
