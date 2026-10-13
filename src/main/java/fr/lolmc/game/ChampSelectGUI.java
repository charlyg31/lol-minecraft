package fr.lolmc.game;

import fr.lolmc.champion.skin.ChampionSkin;
import fr.lolmc.champion.skin.SkinRegistry;

import fr.lolmc.LolPlugin;
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
 * <p>Les clics sont interceptés et routés vers le ChampSelectManager.
 */
public class ChampSelectGUI implements Listener {

    private static final Component CHAMP_TITLE = Component.text("Choisis ton Champion", NamedTextColor.DARK_PURPLE)
            .decoration(TextDecoration.ITALIC, false);
    private static final Component RUNE_TITLE = Component.text("Choisis tes Runes", NamedTextColor.DARK_PURPLE)
            .decoration(TextDecoration.ITALIC, false);

    private static final Component SPELL_TITLE = Component.text("Sorts d'invocateur", NamedTextColor.DARK_PURPLE)
            .decoration(TextDecoration.ITALIC, false);
    /** Sorts proposés (Flash compris), dans l'ordre d'affichage. */
    private static final String[] SPELLS = {
            "FLASH", "IGNITE", "HEAL", "BARRIER", "EXHAUST", "TELEPORT", "SMITE", "CLEANSE", "GHOST"
    };

    private static final Component BAN_TITLE = Component.text("Bannissez un Champion", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false);
    private static final String SKIN_TITLE = "🎨 Choisir un skin";

    // Les bans sont propres à chaque session de sélection (voir ChampSelectSession#getBannedChampions).
    private static java.util.Set<String> bansOf(Player p) {
        return LolPlugin.getInstance().getChampSelectManager().bansFor(p.getUniqueId());
    }

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
        var csmMenu = LolPlugin.getInstance().getChampSelectManager();
        for (int i = 0; i < CHAMPIONS.length; i++) {
            String id = CHAMPIONS[i];
            if (csmMenu.isBanned(player.getUniqueId(), id)) inv.setItem(i, unavailableIcon(id, "⛔ Banni"));
            else if (csmMenu.isTaken(player.getUniqueId(), id)) inv.setItem(i, unavailableIcon(id, "✖ Déjà pris"));
            else inv.setItem(i, championIcon(id));
        }
        // Bouton sorts + bouton runes + bouton lock
        String[] spells = LolPlugin.getInstance().getChampSelectManager().getChosenSpells(player.getUniqueId());
        inv.setItem(24, button(Material.BLAZE_POWDER, Component.text("Sorts d'invocateur", NamedTextColor.GOLD),
                "Actuels : " + spellName(spells[0]) + " + " + spellName(spells[1]) + " — clique pour changer"));
        inv.setItem(25, button(Material.ENCHANTED_BOOK, Component.text("Runes", NamedTextColor.LIGHT_PURPLE),
                "Clique pour configurer tes runes"));
        inv.setItem(26, button(Material.LIME_DYE, Component.text("🔒 Verrouiller", NamedTextColor.GREEN),
                "Confirme tes choix"));
        player.openInventory(inv);
    }

    /** Vrai si le joueur a l'écran de sélection des champions ouvert. */
    public boolean isChampionMenuOpen(Player player) {
        return CHAMP_TITLE.equals(player.getOpenInventory().title());
    }

    private ItemStack unavailableIcon(String champId, String reason) {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(capitalize(champId) + " — " + reason, NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Indisponible", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
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

    // ── Menu Sorts d'invocateur ──────────────────────────────────

    /** Ligne du haut = 1er sort, ligne du bas = 2e sort ; le sort actuel brille. */
    public void openSpellMenu(Player player) {
        String[] chosen = LolPlugin.getInstance().getChampSelectManager().getChosenSpells(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(null, 27, SPELL_TITLE);
        for (int i = 0; i < SPELLS.length; i++) {
            inv.setItem(i, spellIcon(SPELLS[i], 1, SPELLS[i].equals(chosen[0])));
            inv.setItem(9 + i, spellIcon(SPELLS[i], 2, SPELLS[i].equals(chosen[1])));
        }
        inv.setItem(22, button(Material.ARROW, Component.text("◀ Retour aux champions", NamedTextColor.YELLOW), ""));
        player.openInventory(inv);
    }

    private ItemStack spellIcon(String spell, int position, boolean selected) {
        Material mat = switch (spell) {
            case "FLASH"    -> Material.ENDER_PEARL;
            case "IGNITE"   -> Material.BLAZE_POWDER;
            case "HEAL"     -> Material.GLISTERING_MELON_SLICE;
            case "BARRIER"  -> Material.SHIELD;
            case "EXHAUST"  -> Material.FERMENTED_SPIDER_EYE;
            case "TELEPORT" -> Material.ENDER_EYE;
            case "SMITE"    -> Material.GOLDEN_SWORD;
            case "CLEANSE"  -> Material.MILK_BUCKET;
            default         -> Material.FEATHER;
        };
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text((selected ? "✔ " : "") + spellName(spell),
                    selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Sort " + position + (selected ? " (actuel)" : " — clique pour choisir"),
                    NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            if (selected) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String spellName(String spell) {
        if ("FLASH".equals(spell)) return "Flash";
        try {
            return fr.lolmc.game.SummonerSpellManager.Spell.valueOf(spell).displayName;
        } catch (IllegalArgumentException e) {
            return spell;
        }
    }

    // ── Menu Skins ───────────────────────────────────────────────

    /**
     * Ouvre le menu de sélection de skin pour un champion.
     * Seuls les skins accessibles (permission OK) sont affichés.
     * Retourne directement au lock si le champion n'a qu'un skin (base).
     */
    public void openSkinMenu(Player player, String champId) {
        var accessible = SkinRegistry.getAccessible(champId, player);
        var all = SkinRegistry.getAll(champId);

        // Si seulement le skin de base → pas de menu, on passe directement
        if (accessible.size() <= 1 && !SkinRegistry.hasSkins(champId)) {
            // Informer ChampSelectManager que le skin de base est choisi
            LolPlugin.getInstance().getChampSelectManager().onSkinChosen(player, champId, "base");
            player.closeInventory();
            return;
        }

        // Construire l'inventaire
        int rows = Math.max(2, (int) Math.ceil(all.size() / 9.0) + 1);
        Inventory inv = Bukkit.createInventory(null, rows * 9,
                Component.text(SKIN_TITLE + " — " + capitalize(champId), NamedTextColor.GOLD));

        // Filler gris
        ItemStack filler = fillerItem();
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        // Afficher TOUS les skins — ceux inaccessibles en gris avec cadenas
        for (int i = 0; i < all.size(); i++) {
            ChampionSkin skin = all.get(i);
            boolean hasAccess = skin.hasAccess(player);
            inv.setItem(i, skinIcon(skin, hasAccess));
        }

        // Bouton retour (avant-dernier slot) + lock (dernier)
        int last = inv.getSize() - 1;
        inv.setItem(last - 1, button(Material.ARROW,
                Component.text("◀ Retour aux champions", NamedTextColor.YELLOW), ""));
        var baseSkin = SkinRegistry.getBase(champId);
        String baseLabel = baseSkin != null ? baseSkin.displayName() : "Skin de base";
        inv.setItem(last, button(Material.LIME_DYE,
                Component.text("✔ " + baseLabel, NamedTextColor.GREEN),
                "Utiliser le skin de base"));

        player.openInventory(inv);
    }

    private ItemStack skinIcon(ChampionSkin skin, boolean hasAccess) {
        ItemStack item = new ItemStack(hasAccess ? skin.icon() : Material.GRAY_DYE);
        var meta = item.getItemMeta();
        if (meta == null) return item;

        if (hasAccess) {
            meta.displayName(Component.text("🎨 " + skin.displayName(), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Skin n°" + skin.skinNumber(), NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("▶ Clic pour choisir", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            meta.displayName(Component.text("🔒 " + skin.displayName(), NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Permission requise:", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(skin.getPermission(), NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false)
            ));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack fillerItem() {
        ItemStack f = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var m = f.getItemMeta();
        if (m != null) { m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false)); f.setItemMeta(m); }
        return f;
    }

    // ── Gestion des clics ─────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Component title = e.getView().title();
        String titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(title);

        // ── Menu de ban (classé) : le clic bannit le champion choisi ──
        if (title.equals(BAN_TITLE)) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player banner)) return;
            if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getView().getTopInventory())) return;
            int banSlot = e.getSlot();
            if (banSlot < 0 || banSlot >= CHAMPIONS.length) return;
            String banId = CHAMPIONS[banSlot];
            if (bansOf(banner).contains(banId)) {
                banner.sendMessage(Component.text("❌ " + capitalize(banId) + " est déjà banni.", NamedTextColor.RED));
                return;
            }
            LolPlugin.getInstance().getChampSelectManager().onBanClick(banner, banId); // ferme le menu si accepté
            return;
        }

        if (!title.equals(CHAMP_TITLE) && !title.equals(RUNE_TITLE) && !title.equals(SPELL_TITLE)
                && !titleStr.startsWith("🎨 Choisir un skin")) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        var csm = LolPlugin.getInstance().getChampSelectManager();

        if (title.equals(CHAMP_TITLE)) {
            int slot = e.getSlot();
            if (slot < CHAMPIONS.length) {
                // Skin seulement si le choix est accepté (pas banni, pas pris, bon tour)
                if (csm.chooseChampion(player, CHAMPIONS[slot])) openSkinMenu(player, CHAMPIONS[slot]);
            } else if (slot == 24) {
                openSpellMenu(player);
            } else if (slot == 25) {
                player.closeInventory();
                LolPlugin.getInstance().getRuneGUI().open(player);
            } else if (slot == 26) {
                csm.lock(player);
                player.closeInventory();
            }
        } else if (title.equals(SPELL_TITLE)) {
            if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getView().getTopInventory())) return;
            int slot = e.getSlot();
            if (slot == 22) { openChampionMenu(player); return; }
            int pos = slot / 9, idx = slot % 9;
            if (pos > 1 || idx >= SPELLS.length) return;
            String[] cur = csm.getChosenSpells(player.getUniqueId());
            String s1 = cur[0], s2 = cur[1];
            String picked = SPELLS[idx];
            if (pos == 0) { if (picked.equals(s2)) s2 = s1; s1 = picked; }   // même sort des 2 côtés → échange
            else          { if (picked.equals(s1)) s1 = s2; s2 = picked; }
            csm.chooseSpells(player, s1, s2);
            openSpellMenu(player); // rafraîchir la sélection
        } else if (titleStr.startsWith("🎨 Choisir un skin")) {
            // Extraire le champId du titre "🎨 Choisir un skin — garen"
            String champId = titleStr.contains(" — ")
                    ? titleStr.substring(titleStr.indexOf(" — ") + 3).toLowerCase() : "garen";
            int slot = e.getSlot();
            int invSize = e.getView().getTopInventory().getSize();

            // Bouton retour (avant-dernier) ou base (dernier)
            if (slot == invSize - 2) {
                // Retour au menu champion
                openChampionMenu(player);
                return;
            }
            if (slot == invSize - 1) {
                // Skin de base
                csm.onSkinChosen(player, champId, "base");
                return;
            }

            // Clic sur un skin
            var allSkins = fr.lolmc.champion.skin.SkinRegistry.getAll(champId);
            if (slot < allSkins.size()) {
                var skin = allSkins.get(slot);
                if (skin.hasAccess(player)) {
                    csm.onSkinChosen(player, champId, skin.id());
                } else {
                    player.sendActionBar(Component.text(
                            "🔒 Permission requise : " + skin.getPermission(),
                            NamedTextColor.RED));
                }
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
        for (int i = 0; i < CHAMPIONS.length; i++) {
            if (!bansOf(player).contains(CHAMPIONS[i]))
                inv.setItem(i, buildChampionIcon(CHAMPIONS[i], bansOf(player)));
        }
        player.openInventory(inv);
    }

    private org.bukkit.inventory.ItemStack buildChampionIcon(String championId, java.util.Set<String> bans) {
        org.bukkit.Material mat = org.bukkit.Material.PLAYER_HEAD;
        org.bukkit.inventory.ItemStack is = new org.bukkit.inventory.ItemStack(mat);
        org.bukkit.inventory.meta.ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            boolean banned = bans.contains(championId);
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