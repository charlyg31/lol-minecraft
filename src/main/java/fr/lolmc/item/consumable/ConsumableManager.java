package fr.lolmc.item.consumable;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.stats.HPSystem;
import fr.lolmc.stats.ResourceSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère tous les consommables LoL :
 * Potions de vie, Fiole rechargeable, Biscuit, Élixirs, Wards (trinkets)
 *
 * Les consommables sont dans un slot spécial (slot 9 de l'inventaire)
 * Clic droit = utiliser le consommable tenu en main dans les slots 9+
 */
public class ConsumableManager {

    private final ChampionManager championManager;
    private final HUDManager hudManager;

    // Slot dédié aux consommables dans l'inventaire Minecraft (slot 9 = 2ème rangée début)
    public static final int CONSUMABLE_SLOT_START = 20; // slots 20-26 (rangée 3)

    // Potions actives par joueur: UUID → liste de tâches BukkitRunnable
    private final Map<UUID, List<BukkitRunnable>> activePotions = new HashMap<>();
    // Charges fiole rechargeable
    private final Map<UUID, Integer> refillableCharges = new HashMap<>();
    // Wards placées par joueur
    private final Map<UUID, Integer> wardCharges = new HashMap<>();
    // Élixir actif
    private final Map<UUID, String> activeElixir = new HashMap<>();
    private final Map<UUID, Long> elixirExpire = new HashMap<>();
    // Effets élixirs actifs sur les stats
    private final Map<UUID, double[]> elixirStatBoosts = new HashMap<>(); // [ad, ap, hp, ms]

    public ConsumableManager(ChampionManager cm, HUDManager hud) {
        this.championManager = cm;
        this.hudManager = hud;
        startElixirTask();
    }

    // ════════════════════════════════════════════════════════
    // POTION DE VIE (50g — soigne 150 HP sur 15s)
    // ════════════════════════════════════════════════════════
    public boolean useHealthPotion(Player player) {
        if (!championManager.hasChampion(player)) return false;
        if (getActivePotion(player) != null) {
            player.sendActionBar(Component.text("❌ Potion déjà active!", NamedTextColor.RED));
            return false;
        }
        BaseChampion champ = championManager.getChampion(player);
        HPSystem hp = champ.getHPSystem();
        if (hp.getCurrentHP() >= hp.getMaxHP()) {
            player.sendActionBar(Component.text("❌ HP déjà pleins!", NamedTextColor.RED));
            return false;
        }

        player.sendActionBar(Component.text("🧪 Potion de vie (150 HP sur 15s)", NamedTextColor.RED));
        double healPerTick = 150.0 / 15.0; // 10 HP/s

        BukkitRunnable task = new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 15 || !player.isOnline()) { cancel(); cleanPotion(player); return; }
                hp.heal(healPerTick);
                hudManager.updateHUD(player, champ);
                player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0,1,0), 1, 0.3, 0.3, 0.3);
                ticks++;
            }
        };
        task.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);
        setActivePotion(player, task);
        return true;
    }

    // ════════════════════════════════════════════════════════
    // FIOLE RECHARGEABLE (150g — 2 charges, 125 HP/12s chacune)
    // ════════════════════════════════════════════════════════
    public void giveRefillable(Player player) {
        refillableCharges.put(player.getUniqueId(), 2);
    }

    public boolean useRefillablePotion(Player player) {
        if (!championManager.hasChampion(player)) return false;
        int charges = refillableCharges.getOrDefault(player.getUniqueId(), 0);
        if (charges <= 0) {
            player.sendActionBar(Component.text("❌ Fiole rechargeable: plus de charges!", NamedTextColor.RED));
            return false;
        }
        refillableCharges.put(player.getUniqueId(), charges - 1);

        BaseChampion champ = championManager.getChampion(player);
        HPSystem hp = champ.getHPSystem();
        double healPerTick = 125.0 / 12.0;

        player.sendActionBar(Component.text(
            String.format("🧪 Fiole rechargeable (%d charge(s) restante(s))", charges - 1),
            NamedTextColor.GREEN));

        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 12 || !player.isOnline()) { cancel(); return; }
                hp.heal(healPerTick);
                hudManager.updateHUD(player, champ);
                ticks++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);
        return true;
    }

    // ════════════════════════════════════════════════════════
    // BISCUIT DE LA VOLONTÉ INDESTRUCTIBLE (gratuit via rune)
    // Soigne 150 HP + 100 mana, +50 mana max permanent si mana plein
    // ════════════════════════════════════════════════════════
    public boolean useBiscuit(Player player) {
        if (!championManager.hasChampion(player)) return false;
        BaseChampion champ = championManager.getChampion(player);
        HPSystem hp = champ.getHPSystem();
        ResourceSystem res = champ.getResourceSystem();

        // Vérifier si mana plein pour le +50 mana max permanent
        boolean manaFull = res.getCurrent() >= res.getMax();
        if (manaFull && res.getType() == ResourceSystem.ResourceType.MANA) {
            res.addMaxResource(50);
        }

        // Soin progressif
        double hpPerTick = 150.0 / 15.0;
        double manaPerTick = 100.0 / 15.0;

        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 15 || !player.isOnline()) { cancel(); return; }
                hp.heal(hpPerTick);
                if (res.getType() == ResourceSystem.ResourceType.MANA)
                    res.setCurrent(res.getCurrent() + manaPerTick);
                hudManager.updateHUD(player, champ);
                ticks++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);

        player.sendActionBar(Component.text(
            "🍪 Biscuit! +150 HP +100 mana" + (manaFull ? " +50 mana max permanent!" : ""),
            NamedTextColor.GOLD));
        return true;
    }

    // ════════════════════════════════════════════════════════
    // ÉLIXIRS (500g — 3 minutes d'effet)
    // ════════════════════════════════════════════════════════

    /** Élixir de Fureur (AD) — +30 AD + lifesteal */
    public boolean useElixirWrath(Player player) {
        return activateElixir(player, "wrath", new double[]{30, 0, 0, 0}, 0.15);
    }

    /** Élixir de Fer (Tank) — +300 HP + tenacité */
    public boolean useElixirIron(Player player) {
        return activateElixir(player, "iron", new double[]{0, 0, 300, 0}, 0);
    }

    /** Élixir de Sorcellerie (AP) — +50 AP */
    public boolean useElixirSorcery(Player player) {
        return activateElixir(player, "sorcery", new double[]{0, 50, 0, 0}, 0);
    }

    private boolean activateElixir(Player player, String type, double[] boosts, double lifesteal) {
        if (!championManager.hasChampion(player)) return false;

        // Retirer l'élixir précédent si actif
        removeElixirEffects(player);

        BaseChampion champ = championManager.getChampion(player);
        // Appliquer les boosts [ad, ap, hp, ms]
        if (boosts[0] > 0) champ.getStats().addBonusAD(boosts[0]);
        if (boosts[1] > 0) champ.getStats().addBonusAP(boosts[1]);
        if (boosts[2] > 0) champ.getHPSystem().addBonusHP(boosts[2]);
        if (boosts[3] > 0) champ.getStats().addBonusMoveSpeed(boosts[3]);
        if (lifesteal > 0) champ.getStats().addBonusLifeSteal(lifesteal);

        activeElixir.put(player.getUniqueId(), type);
        elixirExpire.put(player.getUniqueId(), System.currentTimeMillis() + 180_000L);
        elixirStatBoosts.put(player.getUniqueId(), boosts);

        String name = switch(type) {
            case "wrath" -> "⚔ Élixir de Fureur (+30 AD)";
            case "iron" -> "🛡 Élixir de Fer (+300 HP)";
            case "sorcery" -> "✨ Élixir de Sorcellerie (+50 AP)";
            default -> "Élixir";
        };
        player.sendActionBar(Component.text(name + " — 3 minutes!", NamedTextColor.GOLD));
        hudManager.updateHUD(player, champ);
        return true;
    }

    private void removeElixirEffects(Player player) {
        double[] boosts = elixirStatBoosts.remove(player.getUniqueId());
        if (boosts == null || !championManager.hasChampion(player)) return;
        BaseChampion champ = championManager.getChampion(player);
        if (boosts[0] > 0) champ.getStats().addBonusAD(-boosts[0]);
        if (boosts[1] > 0) champ.getStats().addBonusAP(-boosts[1]);
        if (boosts[2] > 0) champ.getHPSystem().addBonusHP(-boosts[2]);
        if (boosts[3] > 0) champ.getStats().addBonusMoveSpeed(-boosts[3]);
        activeElixir.remove(player.getUniqueId());
        elixirExpire.remove(player.getUniqueId());
    }

    // ════════════════════════════════════════════════════════
    // WARDS (vision)
    // ════════════════════════════════════════════════════════

    /** Place une ward visible (bloc de lumière temporaire) */
    public boolean placeWard(Player player, boolean visible) {
        Location loc = (player.getTargetBlockExact(20) != null ? player.getTargetBlockExact(20).getLocation() : player.getLocation().add(player.getLocation().getDirection().multiply(20)));
        if (loc == null) loc = player.getLocation();

        final Location wardLoc = loc.clone().add(0, 1, 0);

        // Simuler ward avec une torche temporaire
        Material wardMat = visible ? Material.TORCH : Material.SOUL_TORCH;
        wardLoc.getBlock().setType(wardMat);

        // Durée: ward normale = 150s visible, 60s invisible
        long duration = visible ? 3000L : 1200L;
        int wardDuration = visible ? 150 : 60;

        new BukkitRunnable() {
            @Override public void run() {
                if (wardLoc.getBlock().getType() == wardMat)
                    wardLoc.getBlock().setType(Material.AIR);
            }
        }.runTaskLater(LolPlugin.getInstance(), duration);

        // Enregistrer la ward dans le WardManager (détection des ennemis)
        LolPlugin.getInstance().getWardManager().placeWard(player, wardLoc, wardDuration);

        String wardType = visible ? "🔵 Totem de vision" : "👁 Ward furtive";
        player.sendActionBar(Component.text(
            String.format("%s placée! (dure %ds)", wardType, wardDuration),
            NamedTextColor.YELLOW));
        return true;
    }

    /** Control Ward: révèle et détruit les wards ennemies proches */
    public void placeControlWard(Player player) {
        Location loc = (player.getTargetBlockExact(15) != null ? player.getTargetBlockExact(15).getLocation() : player.getLocation().add(player.getLocation().getDirection().multiply(15)));
        if (loc == null) loc = player.getLocation();

        final Location wardLoc = loc.clone().add(0, 1, 0);
        wardLoc.getBlock().setType(Material.PINK_CANDLE);

        // Détruire les wards ennemies enregistrées dans un rayon de 6 blocs
        int destroyed = LolPlugin.getInstance().getWardManager()
                .destroyEnemyWards(player, wardLoc, 6.0);
        // Détruire aussi les blocs torche soul résiduels
        nearbyBlocks(wardLoc, 6).stream()
            .filter(b -> b.getType() == Material.SOUL_TORCH || b.getType() == Material.TORCH)
            .forEach(b -> {
                b.setType(Material.AIR);
                wardLoc.getWorld().spawnParticle(Particle.SMOKE, b.getLocation().add(0.5,0.5,0.5), 5);
            });
        // Enregistrer la control ward elle-même (équipe du poseur)
        LolPlugin.getInstance().getWardManager().placeWard(player, wardLoc, 240);

        // Dure jusqu'à destruction (peut être détruit par l'adversaire)
        new BukkitRunnable() {
            @Override public void run() {
                if (wardLoc.getBlock().getType() == Material.PINK_CANDLE)
                    wardLoc.getBlock().setType(Material.AIR);
            }
        }.runTaskLater(LolPlugin.getInstance(), 4800L); // ~240s max

        player.sendActionBar(Component.text("🔮 Control Ward placée!", NamedTextColor.BLUE));
    }

    // ════════════════════════════════════════════════════════
    // CONSTRUIRE LES ITEMSTACK CONSOMMABLES
    // ════════════════════════════════════════════════════════

    public static ItemStack buildConsumable(String id) {
        return switch (id) {
            case "health_potion"      -> buildItem(Material.POTION,          "🧪 Potion de vie",        "Soigne 150 HP sur 15s.",                 NamedTextColor.RED,    50);
            case "refillable_potion"  -> buildItem(Material.GLASS_BOTTLE,    "🧪 Fiole rechargeable",   "2 charges — Soigne 125 HP/12s chacune.", NamedTextColor.GREEN,  150);
            case "biscuit"            -> buildItem(Material.COOKIE,           "🍪 Biscuit de la Volonté","Soigne 150 HP + 100 mana. +50 mana max si mana plein.", NamedTextColor.GOLD, 0);
            case "elixir_wrath"       -> buildItem(Material.DRAGON_BREATH,    "⚔ Élixir de Fureur",      "+30 AD + vol de vie 3 minutes.",         NamedTextColor.RED,    500);
            case "elixir_iron"        -> buildItem(Material.FERMENTED_SPIDER_EYE,"🛡 Élixir de Fer",   "+300 HP + Ténacité 3 minutes.",          NamedTextColor.GRAY,   500);
            case "elixir_sorcery"     -> buildItem(Material.MAGENTA_DYE,      "✨ Élixir de Sorcellerie","+50 AP 3 minutes.",                      NamedTextColor.BLUE,   500);
            case "stealth_ward"       -> buildItem(Material.SOUL_TORCH,        "👁 Totem furtif",         "Place une ward furtive (60s).",          NamedTextColor.YELLOW, 0);
            case "control_ward"       -> buildItem(Material.PINK_CANDLE,       "🔮 Control Ward",         "Révèle et détruit wards ennemies (75g).",NamedTextColor.AQUA,   75);
            case "oracle_lens"        -> buildItem(Material.SPYGLASS,          "🔍 Lentille Oracle",      "Révèle wards proches 10s.",              NamedTextColor.YELLOW, 0);
            case "farsight"           -> buildItem(Material.COMPASS,           "🔭 Vision Lointaine",     "Place ward révélatrice à longue portée.",NamedTextColor.YELLOW, 0);
            default -> new ItemStack(Material.BARRIER);
        };
    }

    private static ItemStack buildItem(Material mat, String name, String desc,
                                       NamedTextColor color, int cost) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text(name, color)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
            .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("─────────────────────", NamedTextColor.DARK_GRAY)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(Component.text(desc, NamedTextColor.GRAY)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (cost > 0) {
            lore.add(Component.text("─────────────────────", NamedTextColor.DARK_GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lore.add(Component.text("💰 Coût: " + cost + " or", NamedTextColor.GOLD)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ── Helpers internes ──────────────────────────────────

    private void startElixirTask() {
        new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                new ArrayList<>(elixirExpire.keySet()).forEach(uuid -> {
                    if (now > elixirExpire.getOrDefault(uuid, 0L)) {
                        org.bukkit.entity.Player p = LolPlugin.getInstance()
                            .getServer().getPlayer(uuid);
                        if (p != null) {
                            removeElixirEffects(p);
                            p.sendActionBar(Component.text("⏱ Élixir expiré!", NamedTextColor.GRAY));
                        }
                    }
                });
            }
        }.runTaskTimer(LolPlugin.getInstance(), 200L, 100L);
    }

    private BukkitRunnable getActivePotion(Player player) {
        List<BukkitRunnable> tasks = activePotions.get(player.getUniqueId());
        return (tasks != null && !tasks.isEmpty()) ? tasks.get(0) : null;
    }

    private void setActivePotion(Player player, BukkitRunnable task) {
        activePotions.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(task);
    }

    private void cleanPotion(Player player) {
        List<BukkitRunnable> tasks = activePotions.get(player.getUniqueId());
        if (tasks != null) tasks.clear();
    }

    public void onPlayerDeath(Player player) {
        // Canceller potions + élixirs à la mort
        List<BukkitRunnable> tasks = activePotions.remove(player.getUniqueId());
        if (tasks != null) tasks.forEach(t -> { try { t.cancel(); } catch(Exception ignored){} });
        // Les élixirs persistent après mort dans LoL → on les garde
    }

    // Getters
    public int getRefillableCharges(Player p) { return refillableCharges.getOrDefault(p.getUniqueId(), 0); }
    public int getWardCharges(Player p) { return wardCharges.getOrDefault(p.getUniqueId(), 0); }
    public String getActiveElixir(Player p) { return activeElixir.get(p.getUniqueId()); }

    /** Récupère les blocs dans un rayon (getNearbyBlocks n'existe pas en Paper). */
    private static java.util.List<org.bukkit.block.Block> nearbyBlocks(Location center, int radius) {
        java.util.List<org.bukkit.block.Block> blocks = new java.util.ArrayList<>();
        for (int x = -radius; x <= radius; x++)
            for (int y = -radius; y <= radius; y++)
                for (int z = -radius; z <= radius; z++)
                    blocks.add(center.clone().add(x, y, z).getBlock());
        return blocks;
    }

}
