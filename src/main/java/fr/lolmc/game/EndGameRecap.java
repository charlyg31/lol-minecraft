package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.stats.persistence.MatchRecord;
import fr.lolmc.team.TeamManager.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Écran de fin de partie : récapitulatif des 10 joueurs (KDA, CS, or, dégâts, soins, balises,
 * objets, runes, sorts) dans un menu, et vote d'honneur façon LoL.
 *
 * Pendant les 30 s qui précèdent le retour au lobby, chaque joueur peut honorer UN allié
 * (clic sur sa tête, ou {@code /l honor <pseudo>}). Les honneurs sont enregistrés à la fermeture
 * de l'écran, après l'enregistrement de la partie.
 */
public class EndGameRecap implements Listener {

    /** Résultat d'une partie terminée, construit par {@link MatchScoreboard#showEndScreen}. */
    public record Summary(String matchId, Team winner, int durationSeconds, String feats,
                          List<MatchRecord> records, CompletableFuture<Void> saved) {}

    private static final class Session {
        final Summary summary;
        final Map<UUID, MatchRecord> byPlayer = new HashMap<>();
        /** votant → allié honoré */
        final Map<UUID, UUID> honorBy = new HashMap<>();
        Session(Summary summary) {
            this.summary = summary;
            for (MatchRecord r : summary.records()) byPlayer.put(r.uuid, r);
        }
        int honorsOf(UUID id) {
            int n = 0;
            for (UUID t : honorBy.values()) if (t.equals(id)) n++;
            return n;
        }
    }

    /** Menu de récapitulatif (identifié par son holder, pas par son titre). */
    private static final class Holder implements InventoryHolder {
        final Session session;
        final Map<Integer, UUID> slots = new HashMap<>();
        Inventory inventory;
        Holder(Session session) { this.session = session; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private static final int[] BLUE_SLOTS = {20, 21, 22, 23, 24};
    private static final int[] RED_SLOTS  = {38, 39, 40, 41, 42};

    private final Map<UUID, Session> sessions = new HashMap<>();

    // ── Cycle ─────────────────────────────────────────────────────

    /** Ouvre l'écran de fin de partie pour ces joueurs (après le titre de victoire). */
    public void start(Summary summary, Collection<UUID> players) {
        if (summary == null) return;
        Session session = new Session(summary);
        for (UUID id : players) if (session.byPlayer.containsKey(id)) sessions.put(id, session);
        Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
            for (UUID id : players) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && sessions.get(id) == session) {
                    open(p);
                    p.sendMessage(Component.text("🎖 Honore un allié : clique sur sa tête, ou /l honor <pseudo>.",
                            NamedTextColor.LIGHT_PURPLE));
                }
            }
        }, 60L);
    }

    /** Ferme l'écran de ces joueurs et enregistre les honneurs (retour au lobby). */
    public void finish(Collection<UUID> players) {
        Set<Session> done = new HashSet<>();
        for (UUID id : players) {
            Session s = sessions.remove(id);
            if (s != null) done.add(s);
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
        var db = LolPlugin.getInstance().getDatabaseManager();
        for (Session s : done) {
            if (s.honorBy.isEmpty() || db == null) continue;
            Map<UUID, Integer> counts = new HashMap<>();
            for (UUID target : s.honorBy.values()) counts.merge(target, 1, Integer::sum);
            String matchId = s.summary.matchId();
            // Après l'enregistrement de la partie (lignes de match_history déjà présentes)
            Runnable write = () -> db.addHonors(matchId, counts);
            if (LolPlugin.getInstance().isEnabled()) {
                s.summary.saved().whenComplete((v, err) ->
                        Bukkit.getScheduler().runTaskAsynchronously(LolPlugin.getInstance(), write));
            } else {
                write.run();
            }
        }
    }

    /** Le joueur a-t-il un écran de fin de partie en cours ? */
    public boolean hasSession(Player p) { return sessions.containsKey(p.getUniqueId()); }

    // ── Honneur ───────────────────────────────────────────────────

    /** Honore l'allié nommé {@code targetName}. */
    public void honor(Player voter, String targetName) {
        Session s = sessions.get(voter.getUniqueId());
        if (s == null) {
            voter.sendMessage(Component.text("❌ Aucun vote d'honneur en cours.", NamedTextColor.RED));
            return;
        }
        for (MatchRecord r : s.summary.records()) {
            if (r.name != null && r.name.equalsIgnoreCase(targetName)) { honor(voter, s, r.uuid); return; }
        }
        voter.sendMessage(Component.text("❌ Joueur introuvable dans cette partie.", NamedTextColor.RED));
    }

    private void honor(Player voter, Session s, UUID target) {
        MatchRecord me = s.byPlayer.get(voter.getUniqueId());
        MatchRecord other = s.byPlayer.get(target);
        if (me == null || other == null) return;
        if (target.equals(voter.getUniqueId())) {
            voter.sendMessage(Component.text("❌ Tu ne peux pas t'honorer toi-même.", NamedTextColor.RED));
            return;
        }
        if (!Objects.equals(me.team, other.team)) {
            voter.sendMessage(Component.text("❌ Tu ne peux honorer qu'un allié.", NamedTextColor.RED));
            return;
        }
        if (s.honorBy.containsKey(voter.getUniqueId())) {
            voter.sendMessage(Component.text("Tu as déjà honoré un allié.", NamedTextColor.GRAY));
            return;
        }
        s.honorBy.put(voter.getUniqueId(), target);
        voter.sendMessage(Component.text("🎖 Tu as honoré " + other.name + " !", NamedTextColor.LIGHT_PURPLE));
        voter.playSound(voter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        Player t = Bukkit.getPlayer(target);
        if (t != null) {
            t.sendMessage(Component.text("🎖 " + voter.getName() + " t'a honoré !", NamedTextColor.LIGHT_PURPLE));
            t.playSound(t.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
        // Rafraîchir les écrans ouverts de la partie
        for (var e : sessions.entrySet()) {
            if (e.getValue() != s) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) open(p);
        }
    }

    // ── Menu ──────────────────────────────────────────────────────

    /** Ouvre (ou rouvre) le récapitulatif de la dernière partie du joueur. */
    public boolean open(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        Summary sum = s.summary;
        Holder holder = new Holder(s);
        MatchRecord me = s.byPlayer.get(p.getUniqueId());
        boolean won = me != null && me.won;
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(
                won ? "Victoire" : "Défaite", won ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_RED)
                .append(Component.text(" — récapitulatif", NamedTextColor.DARK_GRAY)));
        holder.inventory = inv;

        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);

        // Bannière : vainqueur, durée, feats
        inv.setItem(4, named(sum.winner() == Team.BLUE ? Material.BLUE_BANNER : Material.RED_BANNER,
                Component.text("Victoire de l'équipe " + (sum.winner() == Team.BLUE ? "Bleue" : "Rouge"),
                        sum.winner().chatColor),
                List.of(line("Durée : " + formatTime(sum.durationSeconds()), NamedTextColor.GRAY),
                        line("Feats : " + sum.feats(), NamedTextColor.YELLOW))));

        MatchRecord mvp = mvp(sum);
        fillTeam(inv, holder, s, "BLUE", BLUE_SLOTS, 18, p, mvp);
        fillTeam(inv, holder, s, "RED", RED_SLOTS, 36, p, mvp);

        // Aide au vote
        boolean voted = s.honorBy.containsKey(p.getUniqueId());
        inv.setItem(49, named(voted ? Material.AMETHYST_SHARD : Material.NETHER_STAR,
                Component.text(voted ? "Vote d'honneur envoyé" : "Honorer un allié", NamedTextColor.LIGHT_PURPLE),
                voted ? List.of(line("Merci pour ton vote !", NamedTextColor.GRAY))
                      : List.of(line("Clique sur la tête d'un allié", NamedTextColor.GRAY),
                                line("ou /l honor <pseudo>", NamedTextColor.GRAY))));
        p.openInventory(inv);
        return true;
    }

    private void fillTeam(Inventory inv, Holder holder, Session s, String team, int[] slots, int labelSlot,
                          Player viewer, MatchRecord mvp) {
        List<MatchRecord> members = new ArrayList<>();
        int kills = 0, gold = 0;
        for (MatchRecord r : s.summary.records()) {
            if (!team.equals(r.team)) continue;
            members.add(r);
            kills += r.kills;
            gold += r.gold;
        }
        boolean blue = "BLUE".equals(team);
        inv.setItem(labelSlot, named(blue ? Material.BLUE_WOOL : Material.RED_WOOL,
                Component.text(blue ? "Équipe Bleue" : "Équipe Rouge", blue ? NamedTextColor.BLUE : NamedTextColor.RED),
                List.of(line(kills + " éliminations · " + String.format("%,d", gold) + " or", NamedTextColor.GRAY))));

        MatchRecord me = s.byPlayer.get(viewer.getUniqueId());
        boolean ally = me != null && team.equals(me.team);
        for (int i = 0; i < members.size() && i < slots.length; i++) {
            MatchRecord r = members.get(i);
            inv.setItem(slots[i], playerCard(r, s, viewer, ally, r == mvp));
            holder.slots.put(slots[i], r.uuid);
        }
    }

    private ItemStack playerCard(MatchRecord r, Session s, Player viewer, boolean ally, boolean mvp) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;
        Player online = Bukkit.getPlayer(r.uuid);
        if (online != null) meta.setOwningPlayer(online);

        var champ = r.champion != null ? LolPlugin.getInstance().getChampionManager().getPrototype(r.champion) : null;
        String champName = champ != null ? champ.getDisplayName() : r.champion;
        NamedTextColor color = "BLUE".equals(r.team) ? NamedTextColor.AQUA : NamedTextColor.RED;
        meta.displayName(Component.text((mvp ? "★ " : "") + r.name, color)
                .append(Component.text(" — " + champName, NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (mvp) lore.add(line("Meilleur joueur de la partie", NamedTextColor.GOLD));
        if (r.afk) lore.add(line("⚠ Signalé absent (AFK)", NamedTextColor.RED));
        lore.add(line(String.format(Locale.FRANCE, "K/D/A : %d / %d / %d  (KDA %.2f)",
                r.kills, r.deaths, r.assists, r.kda()), NamedTextColor.WHITE));
        lore.add(line(String.format(Locale.FRANCE, "CS : %d (%.1f/min) · Or : %,d", r.cs, r.csPerMin(), r.gold),
                NamedTextColor.YELLOW));
        lore.add(line(String.format(Locale.FRANCE, "Dégâts aux champions : %,d", r.damageDealt), NamedTextColor.RED));
        lore.add(line(String.format(Locale.FRANCE, "Dégâts subis : %,d · Soins : %,d", r.damageTaken, r.healingDone),
                NamedTextColor.GRAY));
        lore.add(line("Balises : " + r.wardsPlaced + " posées, " + r.wardsKilled + " détruites", NamedTextColor.GRAY));

        List<String> items = new ArrayList<>();
        for (String id : MatchRecord.parseJsonArray(r.items)) {
            var item = fr.lolmc.item.ItemRegistry.get(id);
            items.add(item != null ? item.getDisplayName() : id);
        }
        lore.add(Component.empty());
        lore.add(line("Objets :", NamedTextColor.GOLD));
        if (items.isEmpty()) lore.add(line("  aucun", NamedTextColor.DARK_GRAY));
        for (String it : items) lore.add(line("  • " + it, NamedTextColor.WHITE));

        String keystone = runeName(r.keystone);
        if (keystone != null) lore.add(line("Rune : " + keystone, NamedTextColor.LIGHT_PURPLE));
        List<String> spells = new ArrayList<>();
        for (String sp : MatchRecord.parseJsonArray(r.spells)) spells.add(spellName(sp));
        if (!spells.isEmpty()) lore.add(line("Sorts : " + String.join(", ", spells), NamedTextColor.AQUA));

        int honors = s.honorsOf(r.uuid);
        if (honors > 0) lore.add(line("🎖 " + honors + " honneur" + (honors > 1 ? "s" : ""), NamedTextColor.LIGHT_PURPLE));
        lore.add(Component.empty());
        UUID myVote = s.honorBy.get(viewer.getUniqueId());
        if (r.uuid.equals(viewer.getUniqueId())) lore.add(line("C'est toi !", NamedTextColor.DARK_GRAY));
        else if (r.uuid.equals(myVote)) lore.add(line("✔ Tu as honoré ce joueur", NamedTextColor.GREEN));
        else if (ally && myVote == null) lore.add(line("➤ Clique pour honorer", NamedTextColor.LIGHT_PURPLE));

        meta.lore(lore);
        if (mvp) meta.setEnchantmentGlintOverride(true);
        head.setItemMeta(meta);
        return head;
    }

    /** Meilleur joueur : meilleur score (KDA, dégâts, CS) parmi les vainqueurs. */
    private static MatchRecord mvp(Summary sum) {
        MatchRecord best = null;
        double bestScore = -1;
        for (MatchRecord r : sum.records()) {
            if (!r.won || r.afk) continue;
            double score = r.kda() * 3 + r.damageDealt / 1000.0 + r.cs / 20.0 + r.wardsPlaced * 0.5;
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return best;
    }

    private static String runeName(String id) {
        if (id == null || id.isEmpty()) return null;
        var rune = fr.lolmc.rune.RuneRegistry.get(id);
        return rune != null ? rune.name() : id;
    }

    private static String spellName(String id) {
        if ("FLASH".equalsIgnoreCase(id)) return "Flash";
        try { return SummonerSpellManager.Spell.valueOf(id).displayName; }
        catch (IllegalArgumentException e) { return id; }
    }

    private static String formatTime(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(Material mat, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Clics ─────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        UUID target = holder.slots.get(e.getRawSlot());
        if (target == null || sessions.get(p.getUniqueId()) != holder.session) return;
        honor(p, holder.session, target);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
