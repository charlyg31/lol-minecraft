package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RuneRegistry;
import fr.lolmc.rune.RuneRegistry.Path;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer; // AJOUT : Import pour convertir l'ancien format coloré
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Menus graphiques (GUI) pour la phase de sélection :
 *  - Menu de choix du champion (grille des 20 champions)
 *  - Menu de choix des runes (voies + keystones)
 *
 * Les clics sont interceptés et routés vers le ChampSelectManager.
 */
public class ChampSelectGUI implements Listener {

    private static final Component CHAMP_TITLE = Component.text("Choisis ton Champion", NamedTextColor.DARK_PURPLE)
            .decoration(TextDecoration.ITALIC, false);
    private static final Component RUNE_TITLE = Component.text("Choisis tes Runes", NamedTextColor.DARK_PURPLE)
            .decoration(TextDecoration.ITALIC, false);

    private static final Component BAN_TITLE = Component.text("Bannissez un Champion", NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false);

    // Bans actifs (empêchés d'être sélectionnés)
    private static final java.util.Set<String> bannedChampions
        = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static int banRound = 0; // 0 = pas en ban, 1-10 = tour de ban

    // Les 20 champions disponibles (id → matériau d'icône)
    private static final String[] CHAMPIONS = {
            "garen", "darius", "malphite", "nasus",            // top
            "warwick", "amumu", "masteryi", "leesin",          // jungle
            "annie", "veigar", "zed", "yasuo",                 // mid
            "morgana", "leona", "blitzcrank", "janna",         // support
            "ashe", "sivir", "jinx", "missfortune"             // adc
    };

    // ── Menu Champion ─────────────────────────────────────────────

    public void openChampionMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, CHAMP_TITLE);
        for (int i = 0; i < CHAMPIONS.length; i++) {
            inv.setItem(i, championIcon(CHAMPIONS[i]));
        }
        // Bouton runes + bouton lock
        inv.setItem(25, button(Material.ENCHANTED_BOOK, Component.text("Runes", NamedTextColor.LIGHT_PURPLE),
                "Clique pour configurer tes runes"));
        inv.setItem(26, button(Material.LIME_DYE, Component.text("🔒 Verrouiller", NamedTextColor.GREEN),
                "Confirme tes choix"));
        player.openInventory(inv);
    }

    private ItemStack championIcon(String champId) {
        ItemStack item = new ItemStack(championMaterial(champId));
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(capitalize(champId), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Clique pour choisir", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material championMaterial(String champId) {
        return switch (champId) {
            case "garen" -> Material.IRON_SWORD;
            case "darius" -> Material.IRON_AXE;
            case "malphite" -> Material.STONE;
            case "nasus" -> Material.GOLD_INGOT;
            case "warwick" -> Material.BONE;
            case "amumu" -> Material.STRING;
            case "masteryi" -> Material.GOLDEN_SWORD;
            case "leesin" -> Material.LEATHER_BOOTS;
            case "annie" -> Material.BLAZE_POWDER;
            case "veigar" -> Material.DRAGON_BREATH;
            case "zed" -> Material.NETHERITE_SWORD;
            case "yasuo" -> Material.FEATHER;
            case "morgana" -> Material.WITHER_ROSE;
            case "leona" -> Material.SHIELD;
            case "blitzcrank" -> Material.PISTON;
            case "janna" -> Material.PHANTOM_MEMBRANE;
            case "ashe" -> Material.BOW;
            case "sivir" -> Material.GOLDEN_HOE;
            case "jinx" -> Material.CROSSBOW;
            case "missfortune" -> Material.TIPPED_ARROW;
            default -> Material.PLAYER_HEAD;
        };
    }

    // ── Menu Runes ────────────────────────────────────────────────

    public void openRuneMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, RUNE_TITLE);
        int row = 0;
        for (Path path : Path.values()) {
            List<RuneRegistry.Rune> keystones = RuneRegistry.getKeystones(path);
            int col = 0;
            inv.setItem(row * 9, pathIcon(path));
            for (var ks : keystones) {
                inv.setItem(row * 9 + 1 + col, runeIcon(ks.name(), ks.description()));
                col++;
            }
            row++;
        }
        player.openInventory(inv);
    }

    private ItemStack pathIcon(Path path) {
        Material mat = switch (path) {
            case PRECISION -> Material.GOLD_INGOT;
            case DOMINATION -> Material.REDSTONE;
            case SORCERY -> Material.LAPIS_LAZULI;
            case RESOLVE -> Material.EMERALD;
            case INSPIRATION -> Material.PRISMARINE_CRYSTALS;
        };

        // CORRECTION : On reprend path.color d'origine et on le convertit proprement en Component moderne
        Component display = LegacyComponentSerializer.legacySection().deserialize(path.color + path.displayName);
        return button(mat, display, "Voie de runes");
    }

    private ItemStack runeIcon(String name, String desc) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(desc, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Gestion des clics ─────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Component title = e.getView().title();
        if (!title.equals(CHAMP_TITLE) && !title.equals(RUNE_TITLE)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        var csm = LolPlugin.getInstance().getChampSelectManager();

        if (title.equals(CHAMP_TITLE)) {
            int slot = e.getSlot();
            if (slot < CHAMPIONS.length) {
                csm.chooseChampion(player, CHAMPIONS[slot]);
                player.closeInventory();
            } else if (slot == 25) {
                player.closeInventory();
                LolPlugin.getInstance().getRuneGUI().open(player);
            } else if (slot == 26) {
                csm.lock(player);
                player.closeInventory();
            }
        } else if (title.equals(RUNE_TITLE)) {
            if (clicked.getType() == Material.ENCHANTED_BOOK) {
                player.sendActionBar(Component.text(
                        "Keystone sélectionnée. Page de base appliquée.", NamedTextColor.GREEN));
                csm.chooseRunes(player, fr.lolmc.rune.RunePage.defaultPage());
                player.closeInventory();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private ItemStack button(Material mat, Component modernName, String lore) {
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(modernName.decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /** Ouvre le menu de ban pour un joueur. */
    public void openBanMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, BAN_TITLE);
        for (int i = 0; i < CHAMPIONS.length && i < 27; i++) {
            if (!bannedChampions.contains(CHAMPIONS[i]))
                inv.setItem(i, buildChampionIcon(CHAMPIONS[i]));
        }
        player.openInventory(inv);
    }

    /** Bannit un champion (appelé depuis ChampSelectManager). */
    public static void banChampion(String championId) {
        bannedChampions.add(championId);
        banRound++;
        Bukkit.broadcast(Component.text(
            "🚫 " + championId + " est banni! (ban " + banRound + "/10)",
            NamedTextColor.RED));
    }

    public static boolean isBanned(String championId) { return bannedChampions.contains(championId); }

    public static void resetBans() { bannedChampions.clear(); banRound = 0; }


    private org.bukkit.inventory.ItemStack buildChampionIcon(String championId) {
        org.bukkit.Material mat = org.bukkit.Material.PLAYER_HEAD;
        org.bukkit.inventory.ItemStack is = new org.bukkit.inventory.ItemStack(mat);
        org.bukkit.inventory.meta.ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            boolean banned = isBanned(championId);
            meta.displayName(net.kyori.adventure.text.Component.text(
                (banned ? "§c✗ " : "§f") + championId,
                net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (banned) meta.addEnchant(
                org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            is.setItemMeta(meta);
        }
        return is;
    }
}
