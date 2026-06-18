package fr.lolmc.ability.base;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class BaseAbility {

    // ── Identité ──
    protected final String id;           // identifiant unique ex: "garen_q"
    protected final String name;         // nom affiché
    protected final String description;  // description template (avec {valeurs})
    protected final Material icon;       // icône dans l'inventaire
    protected final AbilitySlot slot;    // Q/W/E/R/AA

    // ── Cooldown ──
    protected final double[] baseCooldown; // CD par niveau [niv1, niv2, niv3, niv4, niv5]
    protected int level = 1;

    // ── Portée & Zone ──
    protected final double range;        // portée en blocs
    protected final double aoeRadius;    // rayon zone d'effet (0 = ciblé simple)

    // ── Type de dégâts ──
    public enum DamageType { PHYSICAL, MAGICAL, TRUE, MIXED }
    protected final DamageType damageType;

    // ── Suivi des cooldowns par joueur ──
    private final java.util.Map<UUID, Long> cooldowns = new java.util.HashMap<>();

    public enum AbilitySlot { AA, Q, W, E, R }

    public BaseAbility(String id, String name, String description, Material icon,
                       AbilitySlot slot, double[] baseCooldown, double range,
                       double aoeRadius, DamageType damageType) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.slot = slot;
        this.baseCooldown = baseCooldown;
        this.range = range;
        this.aoeRadius = aoeRadius;
        this.damageType = damageType;
    }

    // ══════════════════════════════════════════════════════
    // MÉTHODES ABSTRAITES — chaque sort implémente sa logique
    // ══════════════════════════════════════════════════════

    /**
     * Exécute le sort.
     * @param caster  le champion qui lance
     * @param stats   les stats FINALES du caster (avec objets)
     * @param target  la cible (peut être null si sort à zone/direction)
     */
    public abstract void cast(BaseChampion caster, ChampionStats stats, Player target);

    /**
     * Retourne la description dynamique avec les vraies valeurs calculées.
     * Appelé pour le tooltip d'inventaire.
     */
    public abstract String getDynamicDescription(ChampionStats stats);

    // ══════════════════════════════════════════════════════
    // COOLDOWN
    // ══════════════════════════════════════════════════════

    public boolean isOnCooldown(Player player) {
        Long last = cooldowns.get(player.getUniqueId());
        if (last == null) return false;
        double cdSeconds = getCurrentCooldown(null);
        return (System.currentTimeMillis() - last) < (cdSeconds * 1000L);
    }

    public double getRemainingCooldown(Player player) {
        Long last = cooldowns.get(player.getUniqueId());
        if (last == null) return 0;
        double cdMillis = getCurrentCooldown(null) * 1000.0;
        double remaining = cdMillis - (System.currentTimeMillis() - last);
        return Math.max(0, remaining / 1000.0);
    }

    public void triggerCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * CD actuel après ability haste.
     */
    public double getCurrentCooldown(ChampionStats stats) {
        double base = baseCooldown[Math.min(level - 1, baseCooldown.length - 1)];
        if (stats == null) return base;
        return base * stats.getCooldownMultiplier();
    }

    // ══════════════════════════════════════════════════════
    // AFFICHAGE DE PORTÉE (particules)
    // ══════════════════════════════════════════════════════

    /**
     * Affiche la portée et la zone d'effet UNIQUEMENT pour le joueur qui tient le sort.
     * Utilise des particules côté serveur ciblant uniquement ce joueur.
     */
    public void displayRangeForPlayer(Player player) {
        Location center = player.getEyeLocation();

        // Cercle de portée
        displayCircle(player, center, range, Particle.END_ROD, 1);

        // Zone d'effet si AoE
        if (aoeRadius > 0) {
            // Affiche la zone d'effet là où le joueur regarde
            Location target = player.getTargetBlockLocation(range > 0 ? (int) range : 20, false);
            if (target == null) {
                // Direction du regard à portée max
                target = player.getEyeLocation().add(
                    player.getLocation().getDirection().multiply(range)
                );
            }
            displayCircle(player, target, aoeRadius, Particle.DUST,
                0, new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 100, 0), 1.0f));
        }
    }

    private void displayCircle(Player player, Location center, double radius,
                                Particle particle, int extra, Object... data) {
        int points = Math.max(32, (int)(radius * 8));
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            Location loc = new Location(center.getWorld(), x, center.getY() + 0.1, z);
            if (data.length > 0 && data[0] instanceof Particle.DustOptions dustOptions) {
                player.spawnParticle(Particle.DUST, loc, 1, 0, 0, 0, 0, dustOptions);
            } else {
                player.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // ITEM STACK (représentation dans l'inventaire)
    // ══════════════════════════════════════════════════════

    public ItemStack buildItemStack(ChampionStats stats) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Nom du sort coloré selon le slot
        NamedTextColor slotColor = switch (slot) {
            case Q -> NamedTextColor.AQUA;
            case W -> NamedTextColor.GREEN;
            case E -> NamedTextColor.YELLOW;
            case R -> NamedTextColor.GOLD;
            case AA -> NamedTextColor.WHITE;
        };

        meta.displayName(Component.text("[" + slot.name() + "] " + name, slotColor)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();

        // Séparateur
        lore.add(Component.text("─────────────────────", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        // Description dynamique (avec vraies valeurs)
        String desc = getDynamicDescription(stats);
        // Découper en lignes de 40 chars max
        for (String line : wrapText(desc, 40)) {
            lore.add(Component.text(line, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.text("─────────────────────", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        // CD + Portée
        double cd = getCurrentCooldown(stats);
        lore.add(Component.text("Cooldown: ", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(String.format("%.1fs", cd), NamedTextColor.WHITE)));

        if (range > 0) {
            lore.add(Component.text("Portée: ", NamedTextColor.DARK_AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(String.format("%.0f blocs", range), NamedTextColor.WHITE)));
        }
        if (aoeRadius > 0) {
            lore.add(Component.text("Zone d'effet: ", NamedTextColor.DARK_AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(String.format("%.0f blocs", aoeRadius), NamedTextColor.WHITE)));
        }

        // Type de dégâts
        Component dmgTypeComp = switch (damageType) {
            case PHYSICAL -> Component.text("Physiques", NamedTextColor.RED);
            case MAGICAL  -> Component.text("Magiques", NamedTextColor.BLUE);
            case TRUE     -> Component.text("Vrais", NamedTextColor.WHITE);
            case MIXED    -> Component.text("Mixtes", NamedTextColor.LIGHT_PURPLE);
        };
        lore.add(Component.text("Type: ", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .append(dmgTypeComp.decoration(TextDecoration.ITALIC, false)));

        // Niveau
        lore.add(Component.text("Niveau: " + level + "/5", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ══════════════════════════════════════════════════════
    // UTILITAIRES
    // ══════════════════════════════════════════════════════

    /**
     * Téléportation sûre : si la destination est dans un bloc solide,
     * recule jusqu'à trouver un espace libre. Même comportement que LoL.
     */
    protected Location safeTeleportLocation(Location destination) {
        Location loc = destination.clone();
        // Reculer bloc par bloc jusqu'à trouver un espace libre
        org.bukkit.util.Vector dir = destination.toVector()
                .subtract(destination.clone().subtract(0, 0, 0).toVector()).normalize();

        // Chercher en remontant vers la source si le point est dans un mur
        for (int i = 0; i < 10; i++) {
            if (loc.getBlock().getType().isAir() &&
                loc.clone().add(0, 1, 0).getBlock().getType().isAir()) {
                return loc;
            }
            // Reculer d'un bloc
            loc.subtract(dir);
        }
        return destination; // fallback
    }

    /**
     * Téléportation sûre dans une direction depuis une source.
     */
    protected Location safeTeleportLocationFromTo(Location from, Location to) {
        org.bukkit.util.Vector direction = to.toVector().subtract(from.toVector()).normalize();
        double distance = from.distance(to);

        // Avancer progressivement et s'arrêter avant un mur
        Location last = from.clone();
        for (double d = 1.0; d <= distance; d += 0.5) {
            Location check = from.clone().add(direction.clone().multiply(d));
            if (!check.getBlock().getType().isAir()) {
                // Mur trouvé : retourner la dernière position libre
                return last;
            }
            last = check;
        }
        return to; // destination libre
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() + word.length() + 1 > maxWidth) {
                lines.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(word).append(" ");
        }
        if (!current.isEmpty()) lines.add(current.toString().trim());
        return lines;
    }

    // Getters
    public String getId()          { return id; }
    public String getName()        { return name; }
    public AbilitySlot getSlot()   { return slot; }
    public double getRange()       { return range; }
    public double getAoeRadius()   { return aoeRadius; }
    public int getLevel()          { return level; }
    public void setLevel(int lvl)  { this.level = Math.max(1, Math.min(5, lvl)); }
    public DamageType getDamageType() { return damageType; }
}
