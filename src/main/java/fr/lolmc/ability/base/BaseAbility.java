package fr.lolmc.ability.base;

import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
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

    // ─── Cooldown ────────────────────────────────────────────────

    public boolean isOnCooldown(Player player) {
        Long end = cooldownEnds.get(player.getUniqueId());
        if (end == null) return false;
        return System.currentTimeMillis() < end;
    }

    public double getRemainingCooldown(Player player) {
        Long end = cooldownEnds.get(player.getUniqueId());
        if (end == null) return 0;
        double remaining = (end - System.currentTimeMillis()) / 1000.0;
        return Math.max(0, remaining);
    }

    /** Déclenche le cooldown en appliquant la réduction de CD (CDR) des stats. */
    public void triggerCooldown(Player player, ChampionStats stats) {
        long now = System.currentTimeMillis();
        double cd = getCurrentCooldown(stats); // avec CDR appliqué
        cooldownEnds.put(player.getUniqueId(), now + (long)(cd * 1000));
    }

    /** Version sans stats (fallback — CD de base sans CDR). */
    public void triggerCooldown(Player player) {
        long now = System.currentTimeMillis();
        double cd = getCurrentCooldown(null);
        cooldownEnds.put(player.getUniqueId(), now + (long)(cd * 1000));
    }

    public double getCurrentCooldown(ChampionStats stats) {
        // Si override dynamique (ex: AA dont le CD dépend de l'AS en jeu)
        if (dynamicCooldownOverride > 0) return dynamicCooldownOverride;
        // Cooldown depuis champions.yml si défini, sinon valeur du code
        double[] cdArr = fr.lolmc.util.Balance.cd(id);
        if (cdArr == null) cdArr = baseCooldown;
        double base = cdArr[Math.min(level - 1, cdArr.length - 1)];
        if (stats == null) return base;
        return base * stats.getCooldownMultiplier();
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
    public double getResourceCost() { return fr.lolmc.util.Balance.cost(id, resourceCost); }
    public void setResourceCost(double cost) { this.resourceCost = cost; }

    // ─── Getters ─────────────────────────────────────────────────

    public String       getId()         { return id; }
    public String       getName()       { return name; }
    public AbilitySlot  getSlot()       { return slot; }
    public double       getRange()      { return range; }
    public int          getLevel()      { return level; }
    public void         setLevel(int l) { this.level = Math.clamp(l, 1, 5); }
    public DamageType   getDamageType() { return damageType; }
}