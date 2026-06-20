package fr.lolmc.rune;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RuneRegistry.Path;
import fr.lolmc.rune.RuneRegistry.Rune;
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

import java.util.*;

/**
 * Éditeur complet de page de runes.
 *
 * Le joueur construit sa page en plusieurs étapes dans un seul inventaire :
 *  - Ligne 0 : choix de la voie primaire (5 voies)
 *  - Ligne 1 : keystone de la voie primaire
 *  - Lignes 2-4 : les 3 slots de runes mineures primaires
 *  - Ligne 5 : voie secondaire + ses 2 runes + fragments de stats
 *
 * L'état en cours d'édition est gardé par joueur jusqu'à validation.
 */
public class RuneGUI implements Listener {

    private static final String TITLE = "§5Éditeur de Runes";

    // Page en cours d'édition par joueur
    private final Map<UUID, RunePage> editing = new HashMap<>();

    public void open(Player player) {
        // Repartir de la page sauvegardée du joueur
        RunePage current = LolPlugin.getInstance().getRuneManager().getPage(player.getUniqueId());
        RunePage copy = clonePage(current);
        editing.put(player.getUniqueId(), copy);
        render(player);
    }

    // ══════════════════════════════════════════════════════════════
    // RENDU DU MENU
    // ══════════════════════════════════════════════════════════════

    private void render(Player player) {
        RunePage page = editing.get(player.getUniqueId());
        if (page == null) return;
        Inventory inv = Bukkit.createInventory(null, 54, TITLE);

        // ── Ligne 0 : voies primaires (slots 0-4) ──
        int i = 0;
        for (Path path : Path.values()) {
            boolean selected = page.primaryPath == path;
            inv.setItem(i, pathIcon(path, selected, "PRIMAIRE"));
            i++;
        }

        // ── Ligne 1 : keystones de la voie primaire (slots 9-12) ──
        if (page.primaryPath != null) {
            List<Rune> keystones = RuneRegistry.getKeystones(page.primaryPath);
            int col = 9;
            for (Rune ks : keystones) {
                boolean sel = ks.id().equals(page.keystone);
                inv.setItem(col, runeIcon(ks, sel));
                col++;
            }
        }

        // ── Lignes 2-4 : slots mineurs primaires (1, 2, 3) ──
        if (page.primaryPath != null) {
            for (int slot = 1; slot <= 3; slot++) {
                List<Rune> runes = RuneRegistry.getByPathAndSlot(page.primaryPath, slot);
                int row = 1 + slot; // lignes 2,3,4
                int col = 0;
                for (Rune r : runes) {
                    boolean sel = page.primaryRunes.contains(r.id());
                    inv.setItem(row * 9 + col, runeIcon(r, sel));
                    col++;
                }
            }
        }

        // ── Ligne 5 : voie secondaire (slots 45-49) ──
        i = 45;
        for (Path path : Path.values()) {
            if (path == page.primaryPath) continue; // pas la même que primaire
            boolean selected = page.secondaryPath == path;
            inv.setItem(i, pathIcon(path, selected, "SECONDAIRE"));
            i++;
            if (i > 49) break;
        }

        // ── Boutons : fragments de stats + valider ──
        inv.setItem(51, button(Material.NETHER_STAR, "§eFragments: " + shardSummary(page),
                "Clique pour changer les fragments"));
        inv.setItem(53, validateButton(page));

        player.openInventory(inv);
    }

    private String shardSummary(RunePage page) {
        return shardName(page.statShard1) + "/" + shardName(page.statShard2) + "/" + shardName(page.statShard3);
    }

    private String shardName(String s) {
        return switch (s) {
            case "adaptive" -> "AdF";
            case "attack_speed" -> "AS";
            case "ability_haste" -> "Hâte";
            case "armor" -> "Arm";
            case "mr" -> "RM";
            case "health" -> "PV";
            default -> s;
        };
    }

    // ══════════════════════════════════════════════════════════════
    // GESTION DES CLICS
    // ══════════════════════════════════════════════════════════════

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;

        RunePage page = editing.get(player.getUniqueId());
        if (page == null) return;
        int slot = e.getSlot();

        // Ligne 0 : voie primaire
        if (slot >= 0 && slot <= 4) {
            Path chosen = Path.values()[slot];
            if (page.primaryPath != chosen) {
                page.primaryPath = chosen;
                page.keystone = null;          // reset keystone
                page.primaryRunes.clear();     // reset runes primaires
                if (page.secondaryPath == chosen) page.secondaryPath = null;
            }
            render(player);
            return;
        }

        // Ligne 1 : keystone (slots 9-12)
        if (slot >= 9 && slot <= 12 && page.primaryPath != null) {
            List<Rune> keystones = RuneRegistry.getKeystones(page.primaryPath);
            int idx = slot - 9;
            if (idx < keystones.size()) {
                page.keystone = keystones.get(idx).id();
                render(player);
            }
            return;
        }

        // Lignes 2-4 : runes mineures primaires
        if (slot >= 18 && slot <= 44 && page.primaryPath != null) {
            int row = slot / 9;       // 2, 3 ou 4
            int col = slot % 9;
            int runeSlot = row - 1;   // slot 1, 2 ou 3
            List<Rune> runes = RuneRegistry.getByPathAndSlot(page.primaryPath, runeSlot);
            if (col < runes.size()) {
                String runeId = runes.get(col).id();
                // Un seul choix par slot : retirer les autres runes du même slot
                for (Rune r : runes) page.primaryRunes.remove(r.id());
                page.primaryRunes.add(runeId);
                render(player);
            }
            return;
        }

        // Ligne 5 : voie secondaire (slots 45-49)
        if (slot >= 45 && slot <= 49) {
            // Recalculer quelle voie est à ce slot
            List<Path> secondaryOptions = new ArrayList<>();
            for (Path p : Path.values()) if (p != page.primaryPath) secondaryOptions.add(p);
            int idx = slot - 45;
            if (idx < secondaryOptions.size()) {
                page.secondaryPath = secondaryOptions.get(idx);
                page.secondaryRunes.clear();
                render(player);
            }
            return;
        }

        // Bouton fragments (cycle entre les options)
        if (slot == 51) {
            cycleShards(page);
            render(player);
            return;
        }

        // Bouton valider
        if (slot == 53) {
            validateAndSave(player, page);
            return;
        }
    }

    private void cycleShards(RunePage page) {
        // Cycle simple : adaptive → attack_speed → ability_haste pour shard1
        String[] offensive = {"adaptive", "attack_speed", "ability_haste"};
        String[] defensive = {"health", "armor", "mr"};
        page.statShard1 = next(offensive, page.statShard1);
        page.statShard2 = next(offensive, page.statShard2);
        page.statShard3 = next(defensive, page.statShard3);
    }

    private String next(String[] arr, String current) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(current)) return arr[(i + 1) % arr.length];
        }
        return arr[0];
    }

    private void validateAndSave(Player player, RunePage page) {
        // Compléter automatiquement si incomplet (pour éviter de bloquer)
        if (page.primaryPath != null && page.keystone == null) {
            page.keystone = RuneRegistry.getKeystones(page.primaryPath).get(0).id();
        }
        // Compléter les runes primaires manquantes
        if (page.primaryPath != null) {
            for (int s = 1; s <= 3; s++) {
                List<Rune> runes = RuneRegistry.getByPathAndSlot(page.primaryPath, s);
                boolean hasOne = runes.stream().anyMatch(r -> page.primaryRunes.contains(r.id()));
                if (!hasOne && !runes.isEmpty()) page.primaryRunes.add(runes.get(0).id());
            }
        }
        // Compléter les runes secondaires
        if (page.secondaryPath != null && page.secondaryRunes.size() < 2) {
            List<Rune> all = new ArrayList<>();
            for (int s = 1; s <= 3; s++) all.addAll(RuneRegistry.getByPathAndSlot(page.secondaryPath, s));
            for (Rune r : all) {
                if (page.secondaryRunes.size() >= 2) break;
                page.secondaryRunes.add(r.id());
            }
        }

        LolPlugin.getInstance().getRuneManager().setPage(player.getUniqueId(), page);
        // Si en champ select, enregistrer aussi le choix
        var csm = LolPlugin.getInstance().getChampSelectManager();
        if (csm.isSelecting() && csm.isParticipant(player.getUniqueId())) {
            csm.chooseRunes(player, page);
        }
        player.closeInventory();
        player.sendMessage(Component.text("✔ Page de runes sauvegardée!", NamedTextColor.GREEN));
        editing.remove(player.getUniqueId());
    }

    // ══════════════════════════════════════════════════════════════
    // ICÔNES
    // ══════════════════════════════════════════════════════════════

    private ItemStack pathIcon(Path path, boolean selected, String role) {
        Material mat = switch (path) {
            case PRECISION -> Material.GOLD_INGOT;
            case DOMINATION -> Material.REDSTONE;
            case SORCERY -> Material.LAPIS_LAZULI;
            case RESOLVE -> Material.EMERALD;
            case INSPIRATION -> Material.PRISMARINE_CRYSTALS;
        };
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text((selected ? "✔ " : "") + path.displayName + " (" + role + ")",
                    selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            if (selected) {
                meta.addEnchant(fr.lolmc.util.Compat.glowEnchant(), 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack runeIcon(Rune rune, boolean selected) {
        ItemStack item = new ItemStack(selected ? Material.ENCHANTED_BOOK : Material.BOOK);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text((selected ? "✔ " : "") + rune.name(),
                    selected ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(rune.description(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack validateButton(RunePage page) {
        boolean ready = page.primaryPath != null && page.secondaryPath != null;
        ItemStack item = new ItemStack(ready ? Material.LIME_DYE : Material.GRAY_DYE);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✔ Valider la page", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

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

    private RunePage clonePage(RunePage src) {
        RunePage p = new RunePage();
        p.primaryPath = src.primaryPath;
        p.keystone = src.keystone;
        p.primaryRunes.addAll(src.primaryRunes);
        p.secondaryPath = src.secondaryPath;
        p.secondaryRunes.addAll(src.secondaryRunes);
        p.statShard1 = src.statShard1;
        p.statShard2 = src.statShard2;
        p.statShard3 = src.statShard3;
        return p;
    }
}
