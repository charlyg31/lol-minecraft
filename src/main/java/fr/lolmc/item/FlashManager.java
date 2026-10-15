package fr.lolmc.item;

import fr.lolmc.LolPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère le sort d'invocateur Flash.
 * Téléporte le joueur ~5 blocs dans la direction visée (distance LoL exacte).
 * Cooldown: 300 secondes (5 minutes) comme dans LoL.
 */
public class FlashManager {

    private static final double FLASH_DISTANCE = 4.0; // 400 unités LoL (wiki)
    private static final long FLASH_COOLDOWN_MS = 300_000L; // 5 minutes

    private final Map<UUID, Long> cooldowns = new HashMap<>();

    /**
     * Tente d'utiliser Flash.
     */
    public boolean useFlash(Player player) {
        var ssmFlash = LolPlugin.getInstance().getSummonerSpellManager();
        if (ssmFlash != null && !ssmFlash.hasFlash(player)) return false; // Flash non choisi en sélection
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false; // mort
        // Étourdi, immobilisé ou projeté : pas de Saut éclair (LoL)
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null && (cc.isStunned(player.getUniqueId()) || cc.isRooted(player.getUniqueId())
                || cc.isAirborne(player.getUniqueId()))) {
            player.sendActionBar(Component.text("❌ Impossible sous contrôle", NamedTextColor.RED));
            return false;
        }
        if (isOnCooldown(player)) {
            // Canaliportation Hextech : Saut Hextech remplace Flash quand il lui reste plus de 2 s
            var rmHex = LolPlugin.getInstance().getRuneManager();
            if (rmHex != null && rmHex.getPage(player.getUniqueId()).has("hextech_flashtraption")
                    && getRemaining(player) > 2.0) return hexflash(player);
            player.sendActionBar(Component.text(
                    String.format("✦ Flash en recharge — %.0fs", getRemaining(player)),
                    NamedTextColor.RED));
            return false;
        }

        Location from = player.getLocation();
        Vector dir = from.getDirection().normalize();

        // Calculer la destination à 5 blocs, en s'arrêtant avant les murs
        Location dest = from.clone();
        for (double d = 0.5; d <= FLASH_DISTANCE; d += 0.5) {
            Location check = from.clone().add(dir.clone().multiply(d));
            // Garder la hauteur du sol
            check.setY(from.getY());
            if (!isSafe(check)) {
                break; // Mur trouvé, on s'arrête à la dernière position sûre
            }
            dest = check;
        }

        // Effets visuels et sonores
        fr.lolmc.util.VisualEffectUtil.impactBurst(from.getWorld(),
                from.add(0, 1, 0), Material.LIGHT_BLUE_STAINED_GLASS, 0.28f, 0.4, 8, 6L);
        player.teleport(dest);
        fr.lolmc.LolPlugin.getInstance().getRuneManager().onDash(player); // Impact soudain
        fr.lolmc.util.VisualEffectUtil.impactBurst(dest.getWorld(),
                dest.clone().add(0, 1, 0), Material.LIGHT_BLUE_STAINED_GLASS, 0.28f, 0.4, 8, 6L);
        player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);

        var rm = LolPlugin.getInstance().getRuneManager();
        // Fin de recharge stockée (l'accélération de sort d'invocateur des runes la raccourcit)
        long cd = rm != null ? rm.summonerCooldownMs(player, FLASH_COOLDOWN_MS) : FLASH_COOLDOWN_MS;
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cd);
        player.sendActionBar(Component.text("✦ Flash!", NamedTextColor.AQUA));
        fr.lolmc.instance.InstanceHelper.baseManager(player).onAction(player);
        // Icône grisée pendant la recharge, puis remise en couleur à la fin
        setFlashReady(player);
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
            if (!isOnCooldown(player)) setFlashReady(player);
        }, cd / 50L + 1L);
        if (rm != null) rm.applyNimbusCloak(player, (int) (FLASH_COOLDOWN_MS / 1000));
        return true;
    }

    // ── Saut Hextech (rune Canaliportation Hextech) ──
    private final Map<UUID, Long> hexflashReady = new HashMap<>();
    private final java.util.Set<UUID> hexChanneling = new java.util.HashSet<>();
    /** Relance demandée pendant la canalisation, et canalisations interrompues par un sort. */
    private final java.util.Set<UUID> hexRecast = new java.util.HashSet<>(), hexInterrupted = new java.util.HashSet<>();
    private static final long HEXFLASH_COOLDOWN_MS = 20_000L, HEXFLASH_COMBAT_COOLDOWN_MS = 10_000L;

    /**
     * Saut Hextech (wiki) : canalisation jusqu'à 2 s, immobile et sans attaquer ; la portée passe de
     * 200 à 400 unités. Relance possible après 1 s (avant : annulé, 10 s de récupération), sinon
     * départ automatique à 2 s. 20 s de récupération (accélération de sort d'invocateur comprise) ;
     * entrer en combat contre un champion le met en récupération pendant 10 s. Les sorts, les
     * étourdissements, entraves et projections l'interrompent.
     */
    private boolean hexflash(Player player) {
        UUID id = player.getUniqueId();
        if (hexChanneling.contains(id)) { hexRecast.add(id); return true; } // relance : saut anticipé
        long left = hexflashReady.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (left > 0) {
            player.sendActionBar(Component.text(String.format("✦ Saut Hextech en recharge — %.0fs", left / 1000.0), NamedTextColor.RED));
            return false;
        }
        hexChanneling.add(id);
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, 40, 6, false, false)); // vitesse à 0
        player.sendActionBar(Component.text("✦ Saut Hextech : canalisation…", NamedTextColor.AQUA));
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                var cc = LolPlugin.getInstance().getCCManager();
                boolean broken = !player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                        || hexInterrupted.remove(id)
                        || (cc != null && (cc.isStunned(id) || cc.isRooted(id) || cc.isAirborne(id) || cc.isSilenced(id)));
                boolean early = hexRecast.remove(id);
                if (broken || (early && t < 20)) {
                    hexChanneling.remove(id);
                    player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
                    // Relâché avant 1 s : 10 s de récupération
                    if (early && !broken) hexflashReady.put(id, System.currentTimeMillis() + HEXFLASH_COMBAT_COOLDOWN_MS);
                    cancel();
                    return;
                }
                if (++t % 5 == 0) fr.lolmc.util.VisualEffectUtil.impact(player.getWorld(),
                        player.getLocation().add(0, 1, 0), Material.LIGHT_BLUE_STAINED_GLASS, 0.2f, 4L);
                if (t < 40 && !early) return;
                double range = FLASH_DISTANCE * (200 + 200 * Math.min(1.0, t / 40.0)) / 400.0; // 200-400 unités
                cancel();
                hexChanneling.remove(id);
                player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
                Location from = player.getLocation();
                Vector dir = from.getDirection().setY(0);
                if (dir.lengthSquared() < 1e-6) return;
                dir.normalize();
                Location dest = from.clone();
                for (double d = 0.5; d <= range; d += 0.5) {
                    Location check = from.clone().add(dir.clone().multiply(d));
                    check.setY(from.getY());
                    if (!isSafe(check)) break;
                    dest = check;
                }
                player.teleport(dest);
                LolPlugin.getInstance().getRuneManager().onDash(player); // Impact soudain
                player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.5f);
                var rm = LolPlugin.getInstance().getRuneManager();
                long cd = rm != null ? rm.summonerCooldownMs(player, HEXFLASH_COOLDOWN_MS) : HEXFLASH_COOLDOWN_MS;
                hexflashReady.put(id, System.currentTimeMillis() + cd);
                fr.lolmc.instance.InstanceHelper.baseManager(player).onAction(player);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 1L, 1L);
        return true;
    }

    /** Un sort lancé interrompt la canalisation du Saut Hextech. */
    public void interruptHexflash(Player player) {
        if (hexChanneling.contains(player.getUniqueId())) hexInterrupted.add(player.getUniqueId());
    }

    /** Canalisation du Saut Hextech en cours (pas d'attaque de base). */
    public boolean isHexChanneling(Player player) { return hexChanneling.contains(player.getUniqueId()); }

    /** Entrée en combat contre un champion : Saut Hextech en récupération pendant 10 s. */
    public void onChampionCombat(Player player) {
        hexflashReady.merge(player.getUniqueId(), System.currentTimeMillis() + HEXFLASH_COMBAT_COOLDOWN_MS, Math::max);
    }

    private boolean isSafe(Location loc) {
        // La position est sûre si le bloc et celui au-dessus sont traversables
        return loc.getBlock().isPassable()
                && loc.clone().add(0, 1, 0).getBlock().isPassable();
    }

    public boolean isOnCooldown(Player player) {
        Long end = cooldowns.get(player.getUniqueId());
        return end != null && System.currentTimeMillis() < end;
    }

    public double getRemaining(Player player) {
        Long end = cooldowns.get(player.getUniqueId());
        if (end == null) return 0;
        return Math.max(0, (end - System.currentTimeMillis()) / 1000.0);
    }

    public void reset(Player player) {
        cooldowns.remove(player.getUniqueId());
    }

    public void cleanup(UUID uuid) {
        cooldowns.remove(uuid);
        hexflashReady.remove(uuid);
        hexChanneling.remove(uuid);
        hexRecast.remove(uuid);
        hexInterrupted.remove(uuid);
    }

    /** Remet le cooldown de Flash à 0 (admin). */
    public void resetCooldown(org.bukkit.entity.Player player) {
        cooldowns.remove(player.getUniqueId());
        setFlashReady(player);
    }

    public void setFlashReady(org.bukkit.entity.Player player) {
        // Redessine la barre (l'icône Flash est en slot 5 ; refreshAbilitySlot(…, 0) visait l'AA)
        var champ = LolPlugin.getInstance().getChampionManager().getChampion(player);
        if (champ != null && player.isOnline())
            LolPlugin.getInstance().getHotbarManager().renderPage(player, champ);
    }
}