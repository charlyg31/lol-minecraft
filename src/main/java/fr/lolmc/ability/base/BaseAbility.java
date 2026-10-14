package fr.lolmc.ability.base;

import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class BaseAbility {

    protected final String id;
    protected final String name;
    protected final Material icon;
    protected final AbilitySlot slot;
    protected final double[] baseCooldown;
    protected int level = 1;
    protected final double range;
    protected final double aoeRadius;
    protected final DamageType damageType;

    protected double resourceCost; // coût en mana/énergie par cast (0 = gratuit)
    protected double dynamicCooldownOverride = -1; // -1 = utiliser baseCooldown, sinon override (pour l'AA)
    private final Map<UUID, Long> cooldownEnds = new HashMap<>();    // timestamp fin (avec CDR appliqué)
    // Positionnés par cast() : sort annulé (aucune cible → ni recharge ni ressource dépensée),
    // ou recharge différée (sort en deux temps, ex. Onde Sonique de Lee Sin)
    private boolean castCancelled = false;
    private boolean cooldownDeferred = false;

    public enum AbilitySlot { AA, Q, W, E, R }
    public enum DamageType  { PHYSICAL, MAGICAL, TRUE, MIXED }

    /** Largeur (en caractères) du retour à la ligne des descriptions dans le lore. */
    private static final int LORE_WRAP_WIDTH = 38;

    public BaseAbility(String id, String name, Material icon, AbilitySlot slot,
                       double[] baseCooldown, double range, double aoeRadius,
                       DamageType damageType) {
        this.id           = id;
        this.name         = name;
        this.icon         = icon;
        this.slot         = slot;
        this.baseCooldown = baseCooldown;
        this.range        = range;
        this.aoeRadius    = aoeRadius;
        this.damageType   = damageType;
    }

    // ─── À implémenter par chaque sort ───────────────────────────

    /**
     * Exécute le sort.
     * @param caster le joueur qui lance le sort
     * @param stats  ses stats finales (base + objets)
     * @param target la cible (null si sort directionnel ou self-only)
     */
    public abstract void cast(Player caster, ChampionStats stats, Player target);

    /**
     * Description dynamique avec les vraies valeurs calculées.
     */
    public abstract String getDynamicDescription(ChampionStats stats);

    // ─── Annulation / recharge différée ──────────────────────────

    /**
     * À appeler dans cast() quand le sort ne part pas (aucune cible valide) : BaseChampion
     * rembourse alors la ressource et ne déclenche pas la recharge. Avant, appuyer sur un sort
     * ciblé sans cible le mettait en recharge et dépensait le mana.
     */
    protected void cancelCast() { castCancelled = true; }

    /** À appeler dans cast() pour gérer soi-même la recharge (sort réactivable). */
    protected void deferCooldown() { cooldownDeferred = true; }

    /** Lit et remet à zéro l'annulation (usage BaseChampion). */
    public boolean consumeCancelled() { boolean c = castCancelled; castCancelled = false; return c; }
    /** Lit et remet à zéro la recharge différée (usage BaseChampion). */
    public boolean consumeDeferred() { boolean d = cooldownDeferred; cooldownDeferred = false; return d; }

    /**
     * Dash du lanceur vers une cible, sans traverser les murs. {@code offset} > 0 : au-delà de la
     * cible (« à travers »), < 0 : juste devant. La direction est celle lanceur → cible (avant :
     * l'orientation de la cible, qui pouvait téléporter n'importe où, y compris dans un mur).
     */
    protected void dashTo(Player caster, org.bukkit.entity.LivingEntity target, double offset) {
        Location from = caster.getLocation();
        Location to = target.getLocation();
        org.bukkit.util.Vector dir = to.toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 1e-6) return;
        dir.normalize();
        Location dest = to.clone().add(dir.clone().multiply(offset));
        dest.setY(to.getY());
        Location safe = safeTeleport(from, dest);
        safe.setDirection(offset > 0 ? dir.clone().multiply(-1) : dir);
        caster.teleport(safe);
    }

    // ─── Cooldown ────────────────────────────────────────────────

    // ── Charges (LoL : Faucon d'Ashe, Bandage d'Amumu…) ──
    // maxCharges > 1 : la recharge (cooldown) régénère UNE charge ; chargeLockout = délai minimal
    // entre deux lancers.
    protected int maxCharges = 1;
    protected double chargeLockout = 0;
    private final Map<UUID, Integer> charges = new HashMap<>();
    private final Map<UUID, Long> nextChargeAt = new HashMap<>();
    private final Map<UUID, Long> rechargeMs = new HashMap<>();
    private final Map<UUID, Long> lockoutUntil = new HashMap<>();

    private int refreshCharges(UUID id) {
        long now = System.currentTimeMillis();
        int c = charges.getOrDefault(id, maxCharges);
        Long next = nextChargeAt.get(id);
        while (next != null && now >= next && c < maxCharges) {
            c++;
            next = c < maxCharges ? next + rechargeMs.getOrDefault(id, 1000L) : null;
        }
        charges.put(id, c);
        if (next == null) nextChargeAt.remove(id); else nextChargeAt.put(id, next);
        return c;
    }

    /** Charges disponibles (1 pour un sort sans charges). */
    public int getCharges(Player player) {
        return maxCharges > 1 ? refreshCharges(player.getUniqueId()) : (isOnCooldown(player) ? 0 : 1);
    }

    public boolean isOnCooldown(Player player) {
        if (maxCharges > 1) {
            UUID id = player.getUniqueId();
            return refreshCharges(id) <= 0 || System.currentTimeMillis() < lockoutUntil.getOrDefault(id, 0L);
        }
        Long end = cooldownEnds.get(player.getUniqueId());
        if (end == null) return false;
        return System.currentTimeMillis() < end;
    }

    public double getRemainingCooldown(Player player) {
        long now = System.currentTimeMillis();
        if (maxCharges > 1) {
            UUID id = player.getUniqueId();
            if (refreshCharges(id) > 0) return Math.max(0, (lockoutUntil.getOrDefault(id, 0L) - now) / 1000.0);
            Long next = nextChargeAt.get(id);
            return next == null ? 0 : Math.max(0, (next - now) / 1000.0);
        }
        Long end = cooldownEnds.get(player.getUniqueId());
        if (end == null) return 0;
        double remaining = (end - now) / 1000.0;
        return Math.max(0, remaining);
    }

    private void startCooldown(Player player, double cd) {
        long now = System.currentTimeMillis();
        if (maxCharges > 1 && cd > 0.01) {
            UUID id = player.getUniqueId();
            int c = refreshCharges(id);
            charges.put(id, Math.max(0, c - 1));
            rechargeMs.put(id, (long) (cd * 1000));
            nextChargeAt.putIfAbsent(id, now + (long) (cd * 1000));
            lockoutUntil.put(id, now + (long) (chargeLockout * 1000));
            return;
        }
        if (maxCharges > 1) { // remise à zéro (cd ~0) : toutes les charges reviennent
            charges.put(player.getUniqueId(), maxCharges);
            nextChargeAt.remove(player.getUniqueId());
        }
        cooldownEnds.put(player.getUniqueId(), now + (long)(cd * 1000));
    }

    /** Déclenche le cooldown en appliquant la réduction de CD (CDR) des stats. */
    public void triggerCooldown(Player player, ChampionStats stats) {
        startCooldown(player, getCurrentCooldown(stats)); // avec CDR appliqué
    }

    /** Version sans stats (fallback — CD de base sans CDR). */
    public void triggerCooldown(Player player) {
        startCooldown(player, getCurrentCooldown(null));
    }

    /**
     * Réduit le cooldown actif de ce sort pour ce joueur d'un nombre de
     * secondes donné (jamais sous le temps présent). Sans effet si le sort
     * n'est pas actuellement en recharge.
     */
    public void reduceCooldown(Player player, double seconds) {
        if (maxCharges > 1) {
            Long next = nextChargeAt.get(player.getUniqueId());
            if (next != null) nextChargeAt.put(player.getUniqueId(),
                    Math.max(System.currentTimeMillis(), next - (long)(seconds * 1000)));
            return;
        }
        Long end = cooldownEnds.get(player.getUniqueId());
        if (end == null) return; // pas en cooldown, rien à réduire
        long now = System.currentTimeMillis();
        long newEnd = Math.max(now, end - (long)(seconds * 1000));
        cooldownEnds.put(player.getUniqueId(), newEnd);
    }

    public double getCurrentCooldown(ChampionStats stats) {
        // Si override dynamique (ex: AA dont le CD dépend de l'AS en jeu)
        if (dynamicCooldownOverride > 0) return dynamicCooldownOverride;
        // Cooldown depuis champions.yml si défini, sinon valeur du code
        double[] cdArr = fr.lolmc.util.Balance.cd(id);
        if (cdArr == null) cdArr = baseCooldown;
        double base = cdArr[Math.min(level - 1, cdArr.length - 1)];
        if (stats == null) return base;
        return base * (slot == AbilitySlot.R ? stats.getUltimateCooldownMultiplier() : stats.getCooldownMultiplier());
    }

    /** Permet de modifier le cooldown de l'AA dynamiquement selon la vitesse d'attaque. */
    public void setDynamicCooldown(double seconds) {
        this.dynamicCooldownOverride = Math.max(0.1, seconds);
    }

    // ─── Affichage portée (le rendu réel se fait dans AbilityListener,
    //     qui gère le cycle de vie des BlockDisplay par-joueur) ──────

    /** Rayon de la zone d'effet, ou 0 si le sort n'en a pas. */
    public double getAoeRadius() { return aoeRadius; }

    /** Centre de la zone d'effet visée par ce joueur (ou null si aoeRadius == 0). */
    public Location getAoeTargetFor(Player player) {
        if (aoeRadius <= 0) return null;
        Location eye = player.getEyeLocation();
        org.bukkit.block.Block tb = player.getTargetBlockExact((int) Math.min(range, 64));
        Location target = tb != null ? tb.getLocation() : null;
        if (target == null)
            target = eye.clone().add(player.getLocation().getDirection().multiply(range));
        return target;
    }

    // ─── Téléportation sécurisée (s'arrête avant les murs) ───────

    protected Location safeTeleport(Location from, Location to) {
        if (from.getWorld() == null) return to;
        double dist = from.distance(to);
        if (dist < 0.5) return to;
        var dir = to.toVector().subtract(from.toVector()).normalize();
        Location last = from.clone().add(0, 0.1, 0);
        for (double d = 0.5; d <= dist; d += 0.5) {
            Location check = from.clone().add(dir.clone().multiply(d)).add(0, 0.1, 0);
            if (!check.getBlock().getType().isAir() ||
                    !check.clone().add(0, 1, 0).getBlock().getType().isAir()) {
                return last;
            }
            last = check;
        }
        return to.clone().add(0, 0.1, 0);
    }

    // ─── ItemStack (tooltip inventaire) ──────────────────────────

    public ItemStack buildItemStack(ChampionStats stats) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        // La quantité de cet objet sert uniquement à afficher les secondes de CD restantes
        // (voir AbilityListener.updateCooldownDisplay) : ce n'est jamais un vrai nombre
        // d'exemplaires. Sans ce verrou, Minecraft traite l'objet comme une vraie pile
        // empilable et peut en scinder une partie (touche Q ne drope qu'une unité de la
        // pile), créant un second exemplaire distinct au sol avec le CD figé dessus.
        meta.setMaxStackSize(1);

        NamedTextColor slotColor = switch (slot) {
            case Q  -> NamedTextColor.AQUA;
            case W  -> NamedTextColor.GREEN;
            case E  -> NamedTextColor.YELLOW;
            case R  -> NamedTextColor.GOLD;
            case AA -> NamedTextColor.WHITE;
        };

        meta.displayName(Component.text("[" + slot + "] " + name, slotColor)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));

        List<Component> lore = new ArrayList<>();
        lore.add(sep());
        for (String line : fr.lolmc.util.TextWrapUtil.wrap(getDynamicDescription(stats), LORE_WRAP_WIDTH))
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(sep());
        lore.add(stat("Cooldown", String.format("%.1fs", getCurrentCooldown(stats))));
        if (range > 0)     lore.add(stat("Portée",         String.format("%.0f blocs", range)));
        if (aoeRadius > 0) lore.add(stat("Zone d'effet",   String.format("%.0f blocs", aoeRadius)));
        if (resourceCost > 0) {
            lore.add(stat("Coût", String.format("%.0f", resourceCost)));
        }
        lore.add(stat("Dégâts", switch (damageType) {
            case PHYSICAL -> "§cPhysiques";
            case MAGICAL  -> "§9Magiques";
            case TRUE     -> "§fVrais";
            case MIXED    -> "§dMixtes";
        }));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Component sep() {
        return Component.text("─────────────────────", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }
    private Component stat(String key, String val) {
        return Component.text(key + ": ", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(val, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
    }
    // ─── Ressource ───────────────────────────────────────────────
    public double getResourceCost() {
        double[] byRank = fr.lolmc.util.Balance.costs(id); // coût par rang (LoL)
        if (byRank != null) return byRank[Math.max(0, Math.min(level - 1, byRank.length - 1))];
        return fr.lolmc.util.Balance.cost(id, resourceCost);
    }
    public void setResourceCost(double cost) { this.resourceCost = cost; }

    // ─── Getters ─────────────────────────────────────────────────

    public String       getId()         { return id; }
    public String       getName()       { return name; }
    public Material     getIcon()       { return icon; }
    public AbilitySlot  getSlot()       { return slot; }
    public double       getRange()      { return range; }
    public int          getLevel()      { return level; }
    public void         setLevel(int l) { this.level = Math.clamp(l, 1, 5); }
    public DamageType   getDamageType() { return damageType; }
}