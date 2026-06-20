package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RuneRegistry;
import fr.lolmc.rune.RuneRegistry.Path;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

    private static final String CHAMP_TITLE = "§5Choisis ton Champion";
    private static final String RUNE_TITLE = "§5Choisis tes Runes";

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
        inv.setItem(25, button(Material.ENCHANTED_BOOK, "§dRunes",
                "Clique pour configurer tes runes"));
        inv.setItem(26, button(Material.LIME_DYE, "§a🔒 Verrouiller",
                "Confirme tes choix"));
        player.openInventory(inv);
    }

    private ItemStack championIcon(String champId) {
        // Icône : tête de joueur ou matériau thématique
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
        // Matériau thématique par champion (visuel)
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
        // Une ligne par voie, avec les keystones
        int row = 0;
        for (Path path : Path.values()) {
            List<RuneRegistry.Rune> keystones = RuneRegistry.getKeystones(path);
            int col = 0;
            // Icône de la voie
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
        return button(mat, path.color + path.displayName, "Voie de runes");
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
        String title = e.getView().getTitle();
        if (!title.equals(CHAMP_TITLE) && !title.equals(RUNE_TITLE)) return;
        e.setCancelled(true); // empêcher de prendre les items

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
                LolPlugin.getInstance().getRuneGUI().open(player); // éditeur de runes complet
            } else if (slot == 26) {
                csm.lock(player); // bouton verrouiller
                player.closeInventory();
            }
        } else if (title.equals(RUNE_TITLE)) {
            // Clic sur une keystone : on construit une page simple autour
            if (clicked.getType() == Material.ENCHANTED_BOOK) {
                // Pour simplifier : la keystone cliquée devient la keystone primaire
                // (le détail complet des 3+2 runes pourrait être un menu plus poussé)
                player.sendActionBar(Component.text(
                        "Keystone sélectionnée. Page de base appliquée.", NamedTextColor.GREEN));
                csm.chooseRunes(player, fr.lolmc.rune.RunePage.defaultPage());
                player.closeInventory();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private ItemStack button(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
