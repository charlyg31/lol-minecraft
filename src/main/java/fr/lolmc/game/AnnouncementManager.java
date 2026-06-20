package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les annonces de kills façon LoL (Premier Sang, Double Kill, etc.)
 * et le système de ping (alertes envoyées à l'équipe).
 */
public class AnnouncementManager {

    private boolean firstBloodDone = false;
    // Série de kills par joueur (pour Double/Triple/Quadra/Penta)
    private final Map<UUID, Integer> killStreak = new HashMap<>();
    // Dernier kill (pour réinitialiser la série après un délai)
    private final Map<UUID, Long> lastKillTime = new HashMap<>();

    private static final long MULTI_KILL_WINDOW = 10_000L; // 10s entre kills

    public void reset() {
        firstBloodDone = false;
        killStreak.clear();
        lastKillTime.clear();
    }

    /**
     * Annonce un kill de champion.
     */
    public void announceKill(Player killer, Player victim) {
        long now = System.currentTimeMillis();

        // ── Premier Sang ──
        if (!firstBloodDone) {
            firstBloodDone = true;
            broadcastTitle("§4PREMIER SANG", "§7" + killer.getName() + " a frappé en premier!",
                    Sound.sound(net.kyori.adventure.key.Key.key("entity.wither.spawn"), Sound.Source.MASTER, 1f, 1f));
        }

        // ── Kills multiples ──
        Long last = lastKillTime.get(killer.getUniqueId());
        int streak = (last != null && (now - last) < MULTI_KILL_WINDOW)
                ? killStreak.getOrDefault(killer.getUniqueId(), 0) + 1 : 1;
        killStreak.put(killer.getUniqueId(), streak);
        lastKillTime.put(killer.getUniqueId(), now);

        String multiKill = switch (streak) {
            case 2 -> "§eDOUBLE KILL";
            case 3 -> "§6TRIPLE KILL";
            case 4 -> "§cQUADRA KILL";
            case 5 -> "§4§lPENTA KILL";
            default -> null;
        };
        if (streak >= 6) multiKill = "§4§lMASSACRE (" + streak + ")";

        if (multiKill != null) {
            broadcastTitle(multiKill, "§7" + killer.getName(),
                    Sound.sound(net.kyori.adventure.key.Key.key("entity.ender_dragon.growl"), Sound.Source.MASTER, 1f, 1f));
        }
    }

    /** Réinitialise la série d'un joueur à sa mort. */
    public void onPlayerDeath(Player victim) {
        killStreak.remove(victim.getUniqueId());
        lastKillTime.remove(victim.getUniqueId());
    }

    private void broadcastTitle(String title, String subtitle, Sound sound) {
        Component titleComp = LegacyText.parse(title);
        Component subComp = LegacyText.parse(subtitle);
        Title t = Title.title(titleComp, subComp,
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2000), Duration.ofMillis(500)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(t);
            p.playSound(sound);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SYSTÈME DE PING
    // ══════════════════════════════════════════════════════════════

    public enum PingType {
        DANGER("⚠ Danger!", NamedTextColor.RED),
        ON_MY_WAY("🏃 En chemin", NamedTextColor.GREEN),
        MISSING("❓ Ennemi disparu", NamedTextColor.YELLOW),
        ASSIST("🆘 À l'aide!", NamedTextColor.GOLD),
        ENEMY("🔴 Ennemi ici", NamedTextColor.RED);

        public final String label;
        public final NamedTextColor color;
        PingType(String label, NamedTextColor color) { this.label = label; this.color = color; }
    }

    /**
     * Envoie un ping à tous les membres de l'équipe du joueur.
     */
    public void sendPing(Player sender, PingType type) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(sender);
        if (team == null) return;

        Location loc = sender.getLocation();
        Component msg = Component.text(type.label + " ", type.color)
                .append(Component.text("(" + (int) loc.getX() + ", " + (int) loc.getZ() + ") par "
                        + sender.getName(), NamedTextColor.GRAY));

        for (UUID memberId : tm.getTeamMembers(team)) {
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline()) continue;
            member.sendMessage(msg);
            member.playSound(member.getLocation(),
                    org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.5f);
        }
    }

    /** Convertisseur de codes couleur legacy (§) vers Component. */
    private static class LegacyText {
        static Component parse(String legacy) {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(legacy);
        }
    }
}
