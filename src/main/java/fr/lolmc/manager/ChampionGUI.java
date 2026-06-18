package fr.lolmc.manager;

import fr.lolmc.champion.base.BaseChampion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class ChampionGUI {

    private final ChampionManager championManager;
    private final HeadManager headManager;

    // Titre du GUI — utilisé pour l'identifier dans le listener
    public static final String GUI_TITLE = "§6⚔ Choix du Champion ⚔";

    // Mapping slot GUI → champion ID
    private static final Map<Integer, String> SLOT_TO_CHAMPION = new LinkedHashMap<>();

    static {
        // Rangée 1 — TOP (slots 1-4)
        SLOT_TO_CHAMPION.put(1,  "garen");
        SLOT_TO_CHAMPION.put(2,  "malphite");
        SLOT_TO_CHAMPION.put(3,  "darius");
        SLOT_TO_CHAMPION.put(4,  "nasus");
        // Rangée 2 — JUNGLE (slots 10-13)
        SLOT_TO_CHAMPION.put(10, "warwick");
        SLOT_TO_CHAMPION.put(11, "amumu");
        SLOT_TO_CHAMPION.put(12, "masteryi");
        SLOT_TO_CHAMPION.put(13, "leesin");
        // Rangée 3 — MID (slots 19-22)
        SLOT_TO_CHAMPION.put(19, "annie");
        SLOT_TO_CHAMPION.put(20, "veigar");
        SLOT_TO_CHAMPION.put(21, "zed");
        SLOT_TO_CHAMPION.put(22, "yasuo");
        // Rangée 4 — SUPPORT (slots 28-31)
        SLOT_TO_CHAMPION.put(28, "morgana");
        SLOT_TO_CHAMPION.put(29, "leona");
        SLOT_TO_CHAMPION.put(30, "blitzcrank");
        SLOT_TO_CHAMPION.put(31, "janna");
        // Rangée 5 — ADC (slots 37-40)
        SLOT_TO_CHAMPION.put(37, "ashe");
        SLOT_TO_CHAMPION.put(38, "sivir");
        SLOT_TO_CHAMPION.put(39, "jinx");
        SLOT_TO_CHAMPION.put(40, "missfortune");
    }

    public ChampionGUI(ChampionManager championManager, HeadManager headManager) {
        this.championManager = championManager;
        this.headManager = headManager;
    }

    /**
     * Ouvre le GUI de sélection pour le joueur.
     */
    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(GUI_TITLE));

        // ── Étiquettes de rôle (colonne 0) ──
        inv.setItem(0,  roleLabel("TOP",     NamedTextColor.RED));
        inv.setItem(9,  roleLabel("JUNGLE",  NamedTextColor.GREEN));
        inv.setItem(18, roleLabel("MID",     NamedTextColor.BLUE));
        inv.setItem(27, roleLabel("SUPPORT", NamedTextColor.AQUA));
        inv.setItem(36, roleLabel("ADC",     NamedTextColor.YELLOW));

        // ── Champions ──
        for (Map.Entry<Integer, String> entry : SLOT_TO_CHAMPION.entrySet()) {
            int slot = entry.getKey();
            String champId = entry.getValue();
            BaseChampion champ = getChampionBySlot(slot);
            if (champ == null) continue;

            ItemStack head = headManager.getChampionHead(champId);
            ItemMeta meta = head.getItemMeta();
            if (meta == null) continue;

            // Nom du champion
            NamedTextColor roleColor = getRoleColor(champ.getRole());
            meta.displayName(Component.text(champ.getDisplayName(), roleColor)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false));

            // Lore : rôle + sorts
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("[" + champ.getRole().name() + "]", roleColor)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("─────────────────", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));

            // Afficher les sorts
            String[] slotNames = {"AA","Q","W","E","R"};
            var abilities = champ.getAbilities();
            for (int i = 0; i < 5; i++) {
                if (abilities[i] == null) continue;
                NamedTextColor slotColor = switch (i) {
                    case 0 -> NamedTextColor.WHITE;
                    case 1 -> NamedTextColor.AQUA;
                    case 2 -> NamedTextColor.GREEN;
                    case 3 -> NamedTextColor.YELLOW;
                    case 4 -> NamedTextColor.GOLD;
                    default -> NamedTextColor.GRAY;
                };
                lore.add(Component.text("[" + slotNames[i] + "] " + abilities[i].getName(), slotColor)
                        .decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.text("─────────────────", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Clic pour sélectionner", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, true));

            meta.lore(lore);
            head.setItemMeta(meta);
            inv.setItem(slot, head);
        }

        // ── Remplir les cases vides avec du verre noir ──
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        player.openInventory(inv);
    }

    /**
     * Retourne l'ID du champion selon le slot cliqué, ou null.
     */
    public String getChampionIdFromSlot(int slot) {
        return SLOT_TO_CHAMPION.get(slot);
    }

    private BaseChampion getChampionBySlot(int slot) {
        String id = SLOT_TO_CHAMPION.get(slot);
        if (id == null) return null;
        return championManager.getAllChampions().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst().orElse(null);
    }

    private ItemStack roleLabel(String role, NamedTextColor color) {
        ItemStack item = new ItemStack(switch (role) {
            case "TOP"     -> Material.RED_WOOL;
            case "JUNGLE"  -> Material.GREEN_WOOL;
            case "MID"     -> Material.BLUE_WOOL;
            case "SUPPORT" -> Material.CYAN_WOOL;
            case "ADC"     -> Material.YELLOW_WOOL;
            default        -> Material.WHITE_WOOL;
        });
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("⚔ " + role, color)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    private NamedTextColor getRoleColor(BaseChampion.ChampionRole role) {
        return switch (role) {
            case TOP     -> NamedTextColor.RED;
            case JUNGLE  -> NamedTextColor.GREEN;
            case MID     -> NamedTextColor.BLUE;
            case SUPPORT -> NamedTextColor.AQUA;
            case ADC     -> NamedTextColor.YELLOW;
        };
    }
}
