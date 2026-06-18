package fr.lolmc.item;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.listener.ShopListener;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.stats.HPSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gère les effets passifs des items qui ont un impact en combat.
 * Tournant en tâches répétées ou sur événements.
 */
public class PassiveManager {

    private final ChampionManager championManager;
    private final HUDManager hudManager;
    private final ShopListener shopListener;

    // Stacks Kraken Slayer par joueur
    private final Map<UUID, Integer> krakenStacks = new HashMap<>();
    // Stacks Sunfire par joueur
    private final Map<UUID, Integer> sunfireStacks = new HashMap<>();
    // Guardian Angel en cours de résurrection
    private final Set<UUID> reviving = new HashSet<>();
    // Sterak's Gage actif
    private final Set<UUID> sterakActive = new HashSet<>();
    // Warmog's actif (regen augmentée)
    private final Set<UUID> warmogActive = new HashSet<>();

    public PassiveManager(ChampionManager cm, HUDManager hud, ShopListener sl) {
        this.championManager = cm;
        this.hudManager = hud;
        this.shopListener = sl;
        startTasks();
    }

    private void startTasks() {
        // ── Tâche principale: passifs permanents toutes les 20 ticks (1s) ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    BaseChampion champ = championManager.getChampion(p);
                    LolItem[] items = shopListener.getOrCreate(p).getEquippedItems();
                    processCombatPassives(p, champ, items);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);

        // ── Sunfire: dégâts AoE toutes les 2 ticks ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    if (!hasItem(p, "sunfire_aegis") && !hasItem(p, "sunfire_cape")) continue;
                    BaseChampion champ = championManager.getChampion(p);
                    int stacks = sunfireStacks.getOrDefault(p.getUniqueId(), 0);
                    double dmg = (12 + stacks * 1.44) * (2.0 / 20.0); // dégâts/tick
                    p.getWorld().getNearbyEntities(p.getLocation(), 3, 2, 3).stream()
                        .filter(e -> e instanceof Player victim && !e.equals(p))
                        .forEach(e -> {
                            Player victim = (Player) e;
                            if (championManager.hasChampion(victim)) {
                                BaseChampion vc = championManager.getChampion(victim);
                                double reduced = champ.getStats().calcMagicalDamage(dmg, vc.getStats());
                                vc.getHPSystem().takeDamage(reduced);
                                // Augmenter les stacks si en combat
                                if (stacks < 6) sunfireStacks.put(p.getUniqueId(), stacks + 1);
                            }
                        });
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
    }

    /**
     * Passifs déclenchés sur attaque de base (appelé depuis AbilityListener slot 0).
     */
    public void onAutoAttack(Player attacker, Player victim) {
        if (!championManager.hasChampion(attacker) || !championManager.hasChampion(victim)) return;
        BaseChampion ac = championManager.getChampion(attacker);
        BaseChampion vc = championManager.getChampion(victim);
        ChampionStats as = ac.getStats();
        HPSystem vhp = vc.getHPSystem();

        // ── Blade of the Ruined King: 6% HP actuels ──
        if (hasItem(attacker, "botrk")) {
            double botrkDmg = as.calcPhysicalDamage(
                Math.max(15, vhp.getCurrentHP() * 0.06), vc.getStats());
            vhp.takeDamage(botrkDmg);
            ac.getHPSystem().heal(botrkDmg * 0.10); // léger lifesteal bonus
        }

        // ── Kraken Slayer: 3ème AA = vrais dégâts ──
        if (hasItem(attacker, "kraken_slayer") || hasItem(attacker, "kraken_slayer2")) {
            int stacks = krakenStacks.merge(attacker.getUniqueId(), 1, Integer::sum);
            if (stacks >= 3) {
                vhp.takeDamage(as.calcTrueDamage(150));
                krakenStacks.put(attacker.getUniqueId(), 0);
                attacker.sendActionBar(Component.text("⚡ Kraken Slayer!", NamedTextColor.AQUA));
            }
        }

        // ── Wit's End: +42 dégâts magiques on-hit ──
        if (hasItem(attacker, "wits_end") || hasItem(attacker, "wit_s_end2")) {
            vhp.takeDamage(as.calcMagicalDamage(42, vc.getStats()));
        }

        // ── Nashor's Tooth: +15+20%AP dégâts on-hit ──
        if (hasItem(attacker, "nashors_tooth") || hasItem(attacker, "nashors_tooth2")) {
            double nashDmg = as.calcMagicalDamage(15 + as.getFinalAP() * 0.20, vc.getStats());
            vhp.takeDamage(nashDmg);
        }

        // ── Lifesteal: soin sur dégât AA ──
        double aaDmg = as.calcAutoAttackDamage(vc.getStats());
        double lifeStealHeal = aaDmg * as.getFinalLifeSteal();
        if (lifeStealHeal > 0) ac.getHPSystem().heal(lifeStealHeal);

        // ── Thornmail: réfléchit dégâts ──
        if (hasItem(victim, "thornmail")) {
            double reflected = as.calcMagicalDamage(
                25 + vc.getStats().getFinalArmor() * 0.10, as);
            ac.getHPSystem().takeDamage(reflected);
        }

        // ── BotRK passif vol de vie du champion ──
        as.applyVamp(aaDmg, false);

        // Sync HP
        hudManager.updateHUD(attacker, ac);
        hudManager.updateHUD(victim, vc);
    }

    /**
     * Passifs défensifs: boucliers (Sterak's, Guardian Angel).
     * Appelé quand un joueur descend sous un seuil de HP.
     */
    public void onDamageTaken(Player victim, double damageAmount) {
        if (!championManager.hasChampion(victim)) return;
        BaseChampion champ = championManager.getChampion(victim);
        HPSystem hp = champ.getHPSystem();

        // ── Sterak's Gage: bouclier à <30% HP ──
        if ((hasItem(victim, "steraks_gage") || hasItem(victim, "sterak_gage2"))
                && hp.getCurrentHP() < hp.getMaxHP() * 0.30
                && !sterakActive.contains(victim.getUniqueId())) {
            double shield = champ.getStats().getBonusAD() * 0.75;
            hp.heal(shield); // Simuler le bouclier comme un soin temporaire
            sterakActive.add(victim.getUniqueId());
            victim.sendActionBar(Component.text(
                String.format("🛡 Sterak's Gage! +%.0f bouclier!", shield),
                NamedTextColor.GREEN));
            // Retirer après 4s
            new BukkitRunnable() {
                @Override public void run() { sterakActive.remove(victim.getUniqueId()); }
            }.runTaskLater(LolPlugin.getInstance(), 80L);
        }

        // ── Guardian Angel: résurrection ──
        if (hasItem(victim, "guardian_angel")
                && hp.isDead()
                && !reviving.contains(victim.getUniqueId())) {
            reviving.add(victim.getUniqueId());
            hp.setCurrentHP(1); // Garder en vie 1 tick
            victim.sendTitle("☠", "Résurrection dans 4s...", 5, 60, 10);
            new BukkitRunnable() {
                @Override public void run() {
                    if (!victim.isOnline()) { reviving.remove(victim.getUniqueId()); return; }
                    hp.setCurrentHP(hp.getMaxHP() * 0.50);
                    reviving.remove(victim.getUniqueId());
                    victim.sendTitle("", "✅ Guardian Angel!", 5, 30, 10);
                    hudManager.updateHUD(victim, champ);
                }
            }.runTaskLater(LolPlugin.getInstance(), 80L); // 4s
        }
    }

    /**
     * Passifs permanents: Warmog's regen, etc.
     */
    private void processCombatPassives(Player p, BaseChampion champ, LolItem[] items) {
        HPSystem hp = champ.getHPSystem();
        ChampionStats stats = champ.getStats();

        // ── Warmog's: regen 5% HP max/s si >1000 HP bonus hors combat ──
        boolean hasWarmogs = hasItem(p, "warmogs_armor") || hasItem(p, "warmogs");
        if (hasWarmogs && stats.getBonusHP() > 1000 && !hp.isInCombat()) {
            hp.tickRegenForced(hp.getMaxHP() * 0.05); // +5% HP max par seconde
        }

        // ── Spirit Visage: +30% soins reçus (appliqué dans HPSystem) ──
        // Géré via un multiplicateur dans HPSystem.heal()
    }

    /** Vérifie si un joueur a un item spécifique équipé */
    private boolean hasItem(Player p, String itemId) {
        var invMgr = shopListener.getOrCreate(p);
        for (LolItem item : invMgr.getEquippedItems()) {
            if (item != null && item.getId().equals(itemId)) return true;
        }
        return false;
    }

    public boolean isReviving(UUID uuid) { return reviving.contains(uuid); }
}
