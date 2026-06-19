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
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public enum AbilitySlot { AA, Q, W, E, R }
    public enum DamageType  { PHYSICAL, MAGICAL, TRUE, MIXED }

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
        Long last = cooldowns.get(player.getUniqueId());
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < (getCurrentCooldown(null) * 1000L);
    }

    public double getRemainingCooldown(Player player) {
        Long last = cooldowns.get(player.getUniqueId());
        if (last == null) return 0;
        double remaining = getCurrentCooldown(null) * 1000.0 - (System.currentTimeMillis() - last);
        return Math.max(0, remaining / 1000.0);
    }

    public void triggerCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public double getCurrentCooldown(ChampionStats stats) {
        // Si override dynamique (ex: AA dont le CD dépend de l'AS en jeu)
        if (dynamicCooldownOverride > 0) return dynamicCooldownOverride;
        double base = baseCooldown[Math.min(level - 1, baseCooldown.length - 1)];
        if (stats == null) return base;
        return base * stats.getCooldownMultiplier();
    }

    /** Permet de modifier le cooldown de l'AA dynamiquement selon la vitesse d'attaque. */
    public void setDynamicCooldown(double seconds) {
        this.dynamicCooldownOverride = Math.max(0.1, seconds);
    }

    // ─── Affichage portée (particules visibles uniquement pour le caster) ──

    public void displayRangeForPlayer(Player player) {
        Location eye = player.getEyeLocation();

        // Cercle de portée
        drawCircle(player, eye, range, Color.WHITE);

        // Zone d'effet là où le joueur regarde
        if (aoeRadius > 0) {
            org.bukkit.block.Block tb = player.getTargetBlockExact((int) Math.min(range, 64));
            Location target = tb != null ? tb.getLocation() : null;
            if (target == null)
                target = eye.clone().add(player.getLocation().getDirection().multiply(range));
            drawCircle(player, target, aoeRadius, Color.fromRGB(255, 100, 0));
        }
    }

    private void drawCircle(Player player, Location center, double radius, Color color) {
        int points = Math.max(32, (int)(radius * 8));
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            player.spawnParticle(Particle.DUST,
                new Location(center.getWorld(), x, center.getY() + 0.1, z),
                1, 0, 0, 0, 0, dust);
        }
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
        for (String line : wrap(getDynamicDescription(stats), 38))
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
    private List<String> wrap(String text, int w) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            if (cur.length() + word.length() + 1 > w) { lines.add(cur.toString().trim()); cur = new StringBuilder(); }
            cur.append(word).append(" ");
        }
        if (!cur.isEmpty()) lines.add(cur.toString().trim());
        return lines;
    }

    // ─── Ressource ───────────────────────────────────────────────
    public double getResourceCost() { return resourceCost; }
    public void setResourceCost(double cost) { this.resourceCost = cost; }

    // ─── Getters ─────────────────────────────────────────────────

    public String       getId()         { return id; }
    public String       getName()       { return name; }
    public AbilitySlot  getSlot()       { return slot; }
    public double       getRange()      { return range; }
    public double       getAoeRadius()  { return aoeRadius; }
    public int          getLevel()      { return level; }
    public void         setLevel(int l) { this.level = Math.max(1, Math.min(5, l)); }
    public DamageType   getDamageType() { return damageType; }
}
