package fr.lolmc.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

/**
 * Menu principal pré-game du lobby.
 *
 * Inventaire 54 slots, 3 grandes colonnes :
 *
 *  Colonne 1 (slots 0-1-2)  → Rôle souhaité
 *  Colonne 2 (slots 3-4-5)  → Runes + Sorts d'invocateur
 *  Colonne 3 (slots 6-7-8)  → Groupe + Chercher une game
 *
 * Les sous-menus (rôle, runes, sorts, groupe) sont des inventaires séparés
 * gérés dans LobbyGUIListener, toujours avec retour vers ce menu.
 */
public class LobbyMainMenu {

    // ── Titres (utilisés aussi dans LobbyGUIListener pour détecter les menus) ──
    public static final String T_MAIN   = "⚔ LoL — Menu Principal";
    public static final String T_ROLE   = "⚔ LoL — Rôle souhaité";
    public static final String T_RUNES  = "⚔ LoL — Runes";
    public static final String T_SPELLS = "⚔ LoL — Sorts d'invocateur";
    public static final String T_PARTY  = "⚔ LoL — Groupe";
    public static final String T_QUEUE  = "⚔ LoL — File d'attente";

    // ── Rôles ──────────────────────────────────────────────────────────────
    public static final String[] ROLES      = {"TOP","JUNGLE","MID","ADC","SUPPORT","FILL"};
    static final String[] ROLE_DESCS = {
        "Lane supérieure — tank ou bruiser",
        "Farmer la jungle, prendre les objectifs",
        "Lane du milieu — mage ou assassin",
        "Carry à distance — DPS en late game",
        "Soutenir et protéger l'ADC",
        "N'importe quel rôle disponible"
    };
    static final Material[] ROLE_MATS = {
        Material.SHIELD, Material.WOODEN_AXE, Material.BLAZE_ROD,
        Material.CROSSBOW, Material.GOLDEN_APPLE, Material.PAPER
    };

    // ── Keystones ──────────────────────────────────────────────────────────
    public static final String[] KEYSTONES = {
        "conqueror","electrocute","press_attack","dark_harvest",
        "arcane_comet","phase_rush","grasp_undying","fleet_footwork",
        "lethal_tempo","hail_blades","summon_aery","first_strike",
        "predator","glacial_augment"
    };
    static final String[] KEYSTONE_DESCS = {
        "Stacks → AD/AP permanent en combat",
        "3 attaques en 3s → explosion magique",
        "3 attaques consécutives → dégâts bonus",
        "Récolte âmes sur kills → dégâts croissants",
        "Sort → comète magique sur l'ennemi",
        "3 attaques/sorts → vitesse de déplacement",
        "4 AA/sorts sur champion → heal + HP max",
        "Stacks énergie → soin + vitesse bonus",
        "Stacks illimités de vitesse d'attaque",
        "3 AA rapides au début du combat",
        "Sort → dégâts à ennemi ou bouclier allié",
        "1er hit hors combat → dégâts bonus + or",
        "Long CD → charge rapide + dégâts",
        "AA → ralentissement + zones gelées"
    };
    static final Material[] KEYSTONE_MATS = {
        Material.NETHERITE_SWORD, Material.LIGHTNING_ROD, Material.IRON_SWORD,
        Material.SKELETON_SKULL, Material.SNOWBALL, Material.RABBIT_FOOT,
        Material.MOSS_BLOCK, Material.LEATHER_BOOTS, Material.CLOCK,
        Material.BLAZE_POWDER, Material.FEATHER, Material.GOLD_INGOT,
        Material.CHAINMAIL_BOOTS, Material.ICE
    };

    // ── Sorts d'invocateur ─────────────────────────────────────────────────
    public static final String[] SPELLS = {
        "FLASH","IGNITE","HEAL","BARRIER","EXHAUST",
        "TELEPORT","SMITE","CLEANSE","GHOST"
    };
    static final String[] SPELL_DESCS = {
        "Dash instantané — indispensable",
        "Dégâts vrais 5s + réduction soins 60%",
        "Soigne toi et un allié proche",
        "Bouclier absorbant les dégâts",
        "Réduit dégâts + ralentit un ennemi",
        "TP vers tourelle alliée (4s)",
        "Dégâts à monstre de jungle",
        "Retire tous les effets de CC",
        "+35% vitesse de déplacement 10s"
    };
    static final Material[] SPELL_MATS = {
        Material.ENDER_PEARL, Material.FIRE_CHARGE, Material.GOLDEN_APPLE,
        Material.SHIELD, Material.FERMENTED_SPIDER_EYE, Material.ENDER_EYE,
        Material.GOLDEN_SWORD, Material.MILK_BUCKET, Material.FEATHER
    };

    // ══════════════════════════════════════════════════════════════════════
    // MENU PRINCIPAL
    // ══════════════════════════════════════════════════════════════════════

    public static void openMain(Player player) {
        LobbyPlugin lp = LobbyPlugin.getInstance();
        UUID uid = player.getUniqueId();

        String role    = lp.getRoleManager().getRole(uid);
        var runeData   = lp.getRuneManager().getPageJson(uid);
        String keystone = runeData.getOrDefault("keystone", "conqueror");
        String spell1   = runeData.getOrDefault("spell1", "FLASH");
        String spell2   = runeData.getOrDefault("spell2", "IGNITE");
        int    qSize    = lp.getQueueManager().getQueueSize();
        int    qNeeded  = lp.getQueueManager().getPlayersNeeded();
        boolean inQueue = lp.getQueueManager().isInQueue(uid);
        int    partySize = lp.getPartyManager().getPartyMembers(uid).size();
        boolean isLeader = lp.getPartyManager().isLeader(player);

        Inventory inv = Bukkit.createInventory(null, 54, title(T_MAIN));

        // ── LIGNE 0 : titres des 3 colonnes ───────────────────────────────
        inv.setItem(0, sep(Material.BLUE_STAINED_GLASS_PANE,   "Rôle"));
        inv.setItem(1, sep(Material.BLUE_STAINED_GLASS_PANE,   "Rôle"));
        inv.setItem(2, sep(Material.PURPLE_STAINED_GLASS_PANE, "Runes & Sorts"));
        inv.setItem(3, sep(Material.PURPLE_STAINED_GLASS_PANE, "Runes & Sorts"));
        inv.setItem(4, sep(Material.PURPLE_STAINED_GLASS_PANE, "Runes & Sorts"));
        inv.setItem(5, sep(Material.GREEN_STAINED_GLASS_PANE,  "Groupe & File"));
        inv.setItem(6, sep(Material.GREEN_STAINED_GLASS_PANE,  "Groupe & File"));
        inv.setItem(7, sep(Material.GREEN_STAINED_GLASS_PANE,  "Groupe & File"));
        inv.setItem(8, sep(Material.GREEN_STAINED_GLASS_PANE,  "Groupe & File"));

        // ── COLONNE 1 : Rôle ──────────────────────────────────────────────
        // Slot 9 : bouton rôle
        int roleIdx = roleIndex(role);
        inv.setItem(9, mk(ROLE_MATS[roleIdx],
            "§9§l" + role,
            List.of("§7Rôle souhaité", "", "§7Clic pour changer", "§8Slot 1")));

        // Slot 18 : description du rôle
        inv.setItem(18, mk(Material.BOOK,
            "§7" + ROLE_DESCS[roleIdx],
            List.of()));

        // Slots 27-35 : tous les rôles en miniature
        for (int i = 0; i < ROLES.length; i++) {
            boolean sel = ROLES[i].equals(role);
            inv.setItem(27 + i, mk(ROLE_MATS[i],
                (sel ? "§a✔ " : "§8") + ROLES[i],
                List.of(sel ? "§aActuel" : "§7Clic → choisir ce rôle")));
        }

        // ── COLONNE 2 : Runes ─────────────────────────────────────────────
        // Slot 11 : keystone actuel
        int ksIdx = keystoneIndex(keystone);
        inv.setItem(11, mk(KEYSTONE_MATS[ksIdx],
            "§5§l" + formatName(keystone),
            List.of("§7Keystone actuel", "", "§7Clic pour modifier")));

        // Slot 20 : description keystone
        inv.setItem(20, mk(Material.ENCHANTED_BOOK,
            "§7" + (ksIdx >= 0 ? KEYSTONE_DESCS[ksIdx] : ""),
            List.of()));

        // Slot 13 : sorts d'invocateur
        int s1i = spellIndex(spell1), s2i = spellIndex(spell2);
        inv.setItem(13, mk(Material.NETHER_STAR,
            "§b§l" + spell1 + "  +  " + spell2,
            List.of("§7Sorts d'invocateur",
                    "§e" + spell1 + " §7— " + (s1i >= 0 ? SPELL_DESCS[s1i] : ""),
                    "§e" + spell2 + " §7— " + (s2i >= 0 ? SPELL_DESCS[s2i] : ""),
                    "", "§7Clic pour modifier")));

        // Slot 22 : bouton ouvrir runes
        inv.setItem(22, mk(Material.WRITABLE_BOOK,
            "§d§lModifier les Runes",
            List.of("§7Changer le keystone", "§7et les runes mineures")));

        // ── COLONNE 3 : Groupe + File ─────────────────────────────────────
        // Slot 15 : groupe
        inv.setItem(15, mk(partySize > 1 ? Material.GREEN_BANNER : Material.WHITE_BANNER,
            "§a§lGroupe (" + partySize + "/" + lp.getConfig().getInt("max-party-size", 5) + ")",
            List.of(isLeader ? "§6Tu es le chef" : "§7Membre",
                    "§7Clic pour gérer le groupe")));

        // Slot 24 : file d'attente — bouton principal
        inv.setItem(24, mk(
            inQueue ? Material.LIME_CONCRETE : Material.GREEN_CONCRETE,
            inQueue ? "§a§l▶ En file... (" + qSize + "/" + qNeeded + ")"
                    : "§a§l▶ CHERCHER UNE GAME",
            List.of(
                inQueue ? "§eTu es en file d'attente" : "§7Rejoindre la file et chercher",
                inQueue ? "§7Clic pour §cquitter la file" : "§7Clic pour lancer la recherche",
                "", "§8" + qSize + "/" + qNeeded + " joueurs en file"
            )));

        // Slot 33 : quitter la file si dedans
        if (inQueue) {
            inv.setItem(33, mk(Material.RED_CONCRETE,
                "§c§l⏹ Quitter la file",
                List.of("§7Arrêter la recherche de partie")));
        } else {
            inv.setItem(33, mk(Material.GRAY_CONCRETE,
                "§8Hors file",
                List.of("§7Lance une recherche via le bouton vert")));
        }

        // ── Rembourrage ───────────────────────────────────────────────────
        for (int i = 0; i < 54; i++) if (inv.getItem(i) == null) inv.setItem(i, filler());

        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOUS-MENU : RÔLE
    // ══════════════════════════════════════════════════════════════════════

    public static void openRole(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, title(T_ROLE));
        String current = LobbyPlugin.getInstance().getRoleManager().getRole(player.getUniqueId());

        // Les 6 rôles sur la ligne du milieu (slots 10-15)
        int[] slots = {10, 11, 12, 13, 14, 15};
        for (int i = 0; i < ROLES.length; i++) {
            boolean sel = ROLES[i].equals(current);
            inv.setItem(slots[i], mk(ROLE_MATS[i],
                (sel ? "§a✔ §l" : "§f§l") + ROLES[i],
                List.of("§7" + ROLE_DESCS[i], "",
                    sel ? "§aSélectionné" : "§eClic pour choisir ce rôle")));
        }

        inv.setItem(22, mk(Material.ARROW, "§7← Retour", List.of("Revenir au menu principal")));
        for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, filler());
        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOUS-MENU : RUNES (keystone)
    // ══════════════════════════════════════════════════════════════════════

    public static void openRunes(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, title(T_RUNES));
        String current = LobbyPlugin.getInstance().getRuneManager()
            .getPageJson(player.getUniqueId()).getOrDefault("keystone", "conqueror");

        // 14 keystones sur les 2 premières lignes (slots 0-13)
        for (int i = 0; i < KEYSTONES.length; i++) {
            boolean sel = KEYSTONES[i].equals(current);
            inv.setItem(i, mk(KEYSTONE_MATS[i],
                (sel ? "§a✔ §l" : "§f") + formatName(KEYSTONES[i]),
                List.of("§7" + KEYSTONE_DESCS[i], "",
                    sel ? "§aKeystone actuel" : "§eClic pour sélectionner")));
        }

        // Séparateur + bouton sorts
        inv.setItem(18, sep(Material.PURPLE_STAINED_GLASS_PANE, "──────────────────"));
        for (int i = 19; i < 27; i++) inv.setItem(i, sep(Material.PURPLE_STAINED_GLASS_PANE, ""));

        inv.setItem(27, mk(Material.NETHER_STAR, "§b§lSorts d'invocateur →",
            List.of("§7Modifier Flash, Ignite, etc.")));

        inv.setItem(53, mk(Material.ARROW, "§7← Retour", List.of("Menu principal")));
        for (int i = 0; i < 54; i++) if (inv.getItem(i) == null) inv.setItem(i, filler());
        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOUS-MENU : SORTS D'INVOCATEUR
    // ══════════════════════════════════════════════════════════════════════

    public static void openSpells(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, title(T_SPELLS));
        var runeData = LobbyPlugin.getInstance().getRuneManager().getPageJson(player.getUniqueId());
        String spell1 = runeData.getOrDefault("spell1", "FLASH");
        String spell2 = runeData.getOrDefault("spell2", "IGNITE");

        // Ligne du haut : légende
        inv.setItem(0, mk(Material.YELLOW_DYE, "§eSlot D (gauche)", List.of("§fActuel : §e" + spell1, "", "§7Clic gauche pour assigner")));
        inv.setItem(1, mk(Material.CYAN_DYE,   "§bSlot F (droite)", List.of("§fActuel : §b" + spell2, "", "§7Clic droit pour assigner")));

        // Les sorts (slots 9-17)
        for (int i = 0; i < SPELLS.length; i++) {
            boolean isS1 = SPELLS[i].equals(spell1);
            boolean isS2 = SPELLS[i].equals(spell2);
            String prefix = isS1 ? "§e[D] " : isS2 ? "§b[F] " : "§f";
            List<String> lore = new java.util.ArrayList<>();
            lore.add("§7" + SPELL_DESCS[i]);
            lore.add("");
            if (isS1) lore.add("§e✔ Sort D actuel");
            else if (isS2) lore.add("§b✔ Sort F actuel");
            lore.add("§e§lClic gauche §r§7→ Sort D");
            lore.add("§b§lClic droit  §r§7→ Sort F");
            inv.setItem(9 + i, mk(SPELL_MATS[i], prefix + SPELLS[i], lore));
        }

        inv.setItem(26, mk(Material.ARROW, "§7← Retour", List.of("Menu des runes")));
        for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, filler());
        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOUS-MENU : GROUPE
    // ══════════════════════════════════════════════════════════════════════

    public static void openParty(Player player) {
        LobbyPlugin lp = LobbyPlugin.getInstance();
        var members = lp.getPartyManager().getPartyMembers(player.getUniqueId());
        boolean isLeader = lp.getPartyManager().isLeader(player);
        int maxSize = lp.getConfig().getInt("max-party-size", 5);

        Inventory inv = Bukkit.createInventory(null, 54, title(T_PARTY));

        // Titre
        inv.setItem(4, mk(Material.GOLDEN_HELMET,
            "§6§lGroupe (" + members.size() + "/" + maxSize + ")",
            List.of(isLeader ? "§6Tu es le chef du groupe" : "§7Tu es membre",
                    "§8Les membres voient ce menu")));

        // Membres (ligne 2, slots 9-13)
        for (int i = 0; i < maxSize; i++) {
            if (i < members.size()) {
                UUID mid = members.get(i);
                var mp = org.bukkit.Bukkit.getPlayer(mid);
                String name = mp != null ? mp.getName() : mid.toString().substring(0, 8) + "…";
                boolean isMe = mid.equals(player.getUniqueId());
                boolean isChef = (i == 0);
                List<String> lore = new java.util.ArrayList<>();
                lore.add(isChef ? "§6Chef du groupe" : "§7Membre");
                if (isLeader && !isMe) lore.add("§c§lClic droit §r§7→ exclure du groupe");
                inv.setItem(9 + i, mk(
                    isMe ? Material.LIME_TERRACOTTA : Material.WHITE_TERRACOTTA,
                    (isMe ? "§a" : "§f") + name + (isChef ? " §6⭐" : ""),
                    lore));
            } else {
                inv.setItem(9 + i, mk(Material.GRAY_TERRACOTTA,
                    "§8Slot libre",
                    List.of("§7/party invite <joueur>")));
            }
        }

        // Actions
        inv.setItem(18, mk(Material.WRITABLE_BOOK,
            "§e§l/party invite <joueur>",
            List.of("§7Inviter un joueur à rejoindre", "§7votre groupe")));

        inv.setItem(19, mk(Material.RED_BED,
            "§c§lQuitter le groupe",
            List.of("§7Te retirer du groupe actuel")));

        if (isLeader) {
            inv.setItem(20, mk(Material.TNT,
                "§4§lDissoudre le groupe",
                List.of("§7Exclure tous les membres")));
        }

        inv.setItem(53, mk(Material.ARROW, "§7← Retour", List.of("Menu principal")));
        for (int i = 0; i < 54; i++) if (inv.getItem(i) == null) inv.setItem(i, filler());
        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS INTERNES
    // ══════════════════════════════════════════════════════════════════════

    static ItemStack mk(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return is;
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty())
            meta.lore(lore.stream()
                .map(l -> Component.text(l).decoration(TextDecoration.ITALIC, false))
                .toList());
        is.setItemMeta(meta);
        return is;
    }

    private static ItemStack sep(Material mat, String label) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§8" + label).decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(meta);
        }
        return is;
    }

    static ItemStack filler() {
        ItemStack is = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = is.getItemMeta();
        if (m != null) { m.displayName(Component.empty()); is.setItemMeta(m); }
        return is;
    }

    private static Component title(String t) {
        return Component.text(t).decoration(TextDecoration.ITALIC, false);
    }

    static String formatName(String id) {
        if (id == null || id.isEmpty()) return id;
        String s = id.replace("_", " ");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    static int roleIndex(String role) {
        for (int i = 0; i < ROLES.length; i++) if (ROLES[i].equals(role)) return i;
        return 5; // FILL
    }

    static int keystoneIndex(String ks) {
        for (int i = 0; i < KEYSTONES.length; i++) if (KEYSTONES[i].equals(ks)) return i;
        return 0;
    }

    static int spellIndex(String spell) {
        for (int i = 0; i < SPELLS.length; i++) if (SPELLS[i].equals(spell)) return i;
        return -1;
    }
}
