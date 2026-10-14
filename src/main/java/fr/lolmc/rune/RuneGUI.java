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
 * - Ligne 0 : choix de la voie primaire (5 voies)
 * - Ligne 1 : keystone de la voie primaire
 * - Lignes 2-4, colonnes 0-2 : les 3 rangées de runes mineures primaires
 * - Lignes 2-4, colonnes 6-8 : les rangées de la voie secondaire (2 runes, rangées différentes)
 * - Ligne 5 : voie secondaire (45-48), fragments de stats (50-52), valider (53)
 *
 * L'état en cours d'édition est gardé par joueur jusqu'à validation.
 */
public class RuneGUI implements Listener {

    // CORRECTION : Transformation du titre en Component moderne (DARK_PURPLE remplace §5)
    private static final Component TITLE = Component.text("Éditeur de Runes", NamedTextColor.DARK_PURPLE)
            .decoration(TextDecoration.ITALIC, false);

    // Page en cours d'édition par joueur
    private final Map<UUID, RunePage> editing = new HashMap<>();

    // Disposition
    private static final int SECONDARY_COL = 6;          // colonnes 6-8 des lignes 2-4
    private static final int SECONDARY_PATH_START = 45;  // 45-48
    private static final int[] SHARD_SLOTS = {50, 51, 52};
    private static final int VALIDATE_SLOT = 53;

    public void open(Player player) {
        // Repartir de la page sauvegardée du joueur
        RunePage current = LolPlugin.getInstance().getRuneManager().getPage(player.getUniqueId());
        RunePage copy = clonePage(current);
        copy.sanitize();
        editing.put(player.getUniqueId(), copy);
        render(player);
    }

    // ══════════════════════════════════════════════════════════════
    // RENDU DU MENU
    // ══════════════════════════════════════════════════════════════

    private void render(Player player) {
        RunePage page = editing.get(player.getUniqueId());
        if (page == null) return;

        // Appelle automatiquement la méthode non-dépréciée acceptant un Component
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

        // ── Lignes 2-4, colonnes 6-8 : runes de la voie secondaire (avant : jamais affichées,
        //    les 2 runes étaient choisies d'office, et dans la même rangée) ──
        if (page.secondaryPath != null) {
            for (int slot = 1; slot <= 3; slot++) {
                List<Rune> runes = RuneRegistry.getByPathAndSlot(page.secondaryPath, slot);
                int row = 1 + slot;
                for (int col = 0; col < runes.size() && col < 3; col++) {
                    Rune r = runes.get(col);
                    inv.setItem(row * 9 + SECONDARY_COL + col, runeIcon(r, page.secondaryRunes.contains(r.id())));
                }
            }
        }

        // ── Ligne 5 : voie secondaire (slots 45-48) ──
        i = SECONDARY_PATH_START;
        for (Path path : Path.values()) {
            if (path == page.primaryPath) continue; // pas la même que primaire
            boolean selected = page.secondaryPath == path;
            inv.setItem(i, pathIcon(path, selected, "SECONDAIRE"));
            i++;
        }

        // ── Fragments de stats (un bouton par rangée) + valider ──
        inv.setItem(SHARD_SLOTS[0], shardButton("Fragment offensif", RunePage.SHARDS_1, page.statShard1));
        inv.setItem(SHARD_SLOTS[1], shardButton("Fragment flexible", RunePage.SHARDS_2, page.statShard2));
        inv.setItem(SHARD_SLOTS[2], shardButton("Fragment défensif", RunePage.SHARDS_3, page.statShard3));
        inv.setItem(VALIDATE_SLOT, validateButton(page));

        player.openInventory(inv);
    }

    /** Libellé d'un fragment (valeurs LoL du patch 26). */
    public static String shardName(String s) {
        return switch (s) {
            case "adaptive" -> "+9 force adaptative";
            case "attack_speed" -> "+10% vitesse d'attaque";
            case "ability_haste" -> "+8 accélération de compétence";
            case "move_speed" -> "+2% vitesse de déplacement";
            case "health_scaling" -> "+10 à 180 PV (selon le niveau)";
            case "health" -> "+65 PV";
            case "tenacity" -> "+10% ténacité et résistance aux ralentissements";
            default -> s;
        };
    }

    private ItemStack shardButton(String title, List<String> options, String current) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(title + " : " + shardName(current), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (String o : options)
                lore.add(Component.text((o.equals(current) ? "▶ " : "  ") + shardName(o),
                        o.equals(current) ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Clic pour changer", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ══════════════════════════════════════════════════════════════
    // GESTION DES CLICS
    // ══════════════════════════════════════════════════════════════

    /**
     * Éditeur fermé (Échap) pendant la sélection de champion : revenir à l'écran de sélection.
     * Vérifié au tick suivant, car l'éditeur se rouvre lui-même pour se rafraîchir.
     */
    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!TITLE.equals(e.getView().title())) return;
        if (!(e.getPlayer() instanceof Player player)) return;
        var csm = LolPlugin.getInstance().getChampSelectManager();
        if (!csm.isParticipant(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(LolPlugin.getInstance(), () -> {
            if (!player.isOnline() || !csm.isParticipant(player.getUniqueId())) return;
            // Un autre menu est déjà ouvert (rafraîchissement de l'éditeur, retour déjà fait…)
            if (player.getOpenInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) return;
            LolPlugin.getInstance().getChampSelectGUI().openChampionMenu(player);
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        // CORRECTION : Utilisation de e.getView().title() au lieu de getTitle() déprécié
        if (!TITLE.equals(e.getView().title())) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;

        RunePage page = editing.get(player.getUniqueId());
        if (page == null) return;
        // Slot brut : avant, getSlot() faisait aussi réagir l'inventaire du joueur (en bas)
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;

        // Ligne 0 : voie primaire
        if (slot >= 0 && slot <= 4) {
            Path chosen = Path.values()[slot];
            if (page.primaryPath != chosen) {
                page.primaryPath = chosen;
                page.keystone = null;          // reset keystone
                page.primaryRunes.clear();     // reset runes primaires
                if (page.secondaryPath == chosen) { page.secondaryPath = null; page.secondaryRunes.clear(); }
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

        // Lignes 2-4 : runes mineures (colonnes 0-2 : primaires, 6-8 : secondaires)
        if (slot >= 18 && slot <= 44) {
            int row = slot / 9;       // 2, 3 ou 4
            int col = slot % 9;
            int runeSlot = row - 1;   // rangée 1, 2 ou 3
            if (col < 3 && page.primaryPath != null) {
                List<Rune> runes = RuneRegistry.getByPathAndSlot(page.primaryPath, runeSlot);
                if (col < runes.size()) {
                    // Un seul choix par rangée : retirer les autres runes de la même rangée
                    for (Rune r : runes) page.primaryRunes.remove(r.id());
                    page.primaryRunes.add(runes.get(col).id());
                    render(player);
                }
            } else if (col >= SECONDARY_COL && page.secondaryPath != null) {
                List<Rune> runes = RuneRegistry.getByPathAndSlot(page.secondaryPath, runeSlot);
                int idx = col - SECONDARY_COL;
                if (idx < runes.size()) {
                    selectSecondary(page, runes, runes.get(idx).id());
                    render(player);
                }
            }
            return;
        }

        // Ligne 5 : voie secondaire (slots 45-48)
        if (slot >= SECONDARY_PATH_START && slot < SECONDARY_PATH_START + 4) {
            List<Path> secondaryOptions = new ArrayList<>();
            for (Path p : Path.values()) if (p != page.primaryPath) secondaryOptions.add(p);
            int idx = slot - SECONDARY_PATH_START;
            if (idx < secondaryOptions.size() && page.secondaryPath != secondaryOptions.get(idx)) {
                page.secondaryPath = secondaryOptions.get(idx);
                page.secondaryRunes.clear();
                render(player);
            }
            return;
        }

        // Fragments : chaque bouton fait défiler les options de sa rangée
        if (slot == SHARD_SLOTS[0]) { page.statShard1 = next(RunePage.SHARDS_1, page.statShard1); render(player); return; }
        if (slot == SHARD_SLOTS[1]) { page.statShard2 = next(RunePage.SHARDS_2, page.statShard2); render(player); return; }
        if (slot == SHARD_SLOTS[2]) { page.statShard3 = next(RunePage.SHARDS_3, page.statShard3); render(player); return; }

        if (slot == VALIDATE_SLOT) {
            validateAndSave(player, page);
        }
    }

    /**
     * Rune secondaire (règle LoL) : 2 runes de rangées différentes. Même rangée → remplace ;
     * une 3e rangée → remplace la rune choisie le plus anciennement.
     */
    private void selectSecondary(RunePage page, List<Rune> sameRow, String runeId) {
        for (Rune r : sameRow) page.secondaryRunes.remove(r.id());
        if (page.secondaryRunes.size() >= 2) page.secondaryRunes.remove(page.secondaryRunes.iterator().next());
        page.secondaryRunes.add(runeId);
    }

    private String next(List<String> options, String current) {
        int i = options.indexOf(current);
        return options.get((i + 1) % options.size()); // inconnu (-1) → première option
    }

    private void validateAndSave(Player player, RunePage page) {
        page.sanitize();
        // Compléter automatiquement si incomplet (pour éviter de bloquer)
        if (page.primaryPath == null) page.primaryPath = Path.PRECISION;
        if (page.keystone == null) {
            page.keystone = RuneRegistry.getKeystones(page.primaryPath).getFirst().id();
        }
        // Compléter les runes primaires manquantes
        for (int s = 1; s <= 3; s++) {
            List<Rune> runes = RuneRegistry.getByPathAndSlot(page.primaryPath, s);
            boolean hasOne = runes.stream().anyMatch(r -> page.primaryRunes.contains(r.id()));
            if (!hasOne && !runes.isEmpty()) page.primaryRunes.add(runes.getFirst().id());
        }
        // Compléter les runes secondaires, une par rangée encore libre
        if (page.secondaryPath == null) {
            for (Path p : Path.values()) if (p != page.primaryPath) { page.secondaryPath = p; break; }
        }
        for (int s = 1; s <= 3 && page.secondaryRunes.size() < 2; s++) {
            List<Rune> runes = RuneRegistry.getByPathAndSlot(page.secondaryPath, s);
            boolean hasOne = runes.stream().anyMatch(r -> page.secondaryRunes.contains(r.id()));
            if (!hasOne && !runes.isEmpty()) page.secondaryRunes.add(runes.getFirst().id());
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
        // (retour à l'écran de sélection : géré par onClose, déclenché par closeInventory())
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
                var glow = fr.lolmc.util.Compat.glowEnchant();
                if (glow != null) meta.addEnchant(glow, 1, true);
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
        boolean ready = page.isValid();
        ItemStack item = new ItemStack(ready ? Material.LIME_DYE : Material.GRAY_DYE);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✔ Valider la page", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            if (!ready)
                meta.lore(List.of(Component.text("Les choix manquants seront complétés automatiquement",
                        NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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