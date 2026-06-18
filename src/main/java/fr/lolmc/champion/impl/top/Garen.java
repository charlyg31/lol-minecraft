package fr.lolmc.champion.impl.top;

import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import fr.lolmc.LolPlugin;

import java.util.Collection;

public class Garen extends BaseChampion {

    public Garen() {
        super("garen", "Garen", ChampionRole.TOP,
            new ChampionStats(
                620,   // HP
                66,    // AD
                0,     // AP
                36,    // Armure
                32,    // MR
                0.625, // Attaque/sec
                0,     // Crit
                345,   // Vitesse
                5.0,   // Portée AA (mêlée)
                8.0    // HP regen/5s
            )
        );
    }

    @Override
    protected void registerAbilities() {
        setAbility(0, new GarenAA());
        setAbility(1, new GarenQ());
        setAbility(2, new GarenW());
        setAbility(3, new GarenE());
        setAbility(4, new GarenR());
    }

    // ─── AA ───────────────────────────────────────
    static class GarenAA extends BaseAbility {
        GarenAA() {
            super("garen_aa", "Attaque de base", "Frappe la cible pour {ad} dégâts physiques.",
                Material.IRON_SWORD, AbilitySlot.AA,
                new double[]{0.5, 0.48, 0.46, 0.44, 0.42}, 5, 0, DamageType.PHYSICAL);
        }
        @Override
        public void cast(BaseChampion caster, ChampionStats stats, Player target) {
            if (target == null) return;
            double dmg = stats.calcAutoAttackDamage(null);
            target.damage(dmg);
            stats.applyVamp(dmg, false);
        }
        @Override
        public String getDynamicDescription(ChampionStats stats) {
            return String.format("Frappe la cible pour %.0f dégâts physiques.", stats.getFinalAD());
        }
    }

    // ─── Q : Jugement décisif ──────────────────────
    static class GarenQ extends BaseAbility {
        GarenQ() {
            super("garen_q", "Jugement décisif",
                "Silence la cible 1.5s et inflige {dmg} dégâts physiques ({ad_ratio}x AD).",
                Material.GOLDEN_SWORD, AbilitySlot.Q,
                new double[]{8, 7.5, 7, 6.5, 6}, 5, 0, DamageType.PHYSICAL);
        }
        @Override
        public void cast(BaseChampion caster, ChampionStats stats, Player target) {
            if (target == null) return;
            double dmg = stats.calcPhysicalDamage(30 + 1.4 * stats.getFinalAD(), null);
            target.damage(dmg);
            // Silence = slowness 4 pendant 1.5s + message
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 4, false, true));
            target.sendMessage(Component.text("⚠ Tu es silencé par Garen !", NamedTextColor.RED));
            // Boost vitesse Garen
            LivingEntity gEntity = (LivingEntity) caster.getClass();
        }
        @Override
        public String getDynamicDescription(ChampionStats stats) {
            double dmg = 30 + 1.4 * stats.getFinalAD();
            return String.format("Silence la cible 1.5s et inflige %.0f dégâts physiques (30 + 1.4× %.0f AD).",
                dmg, stats.getFinalAD());
        }
    }

    // ─── W : Courage ──────────────────────────────
    static class GarenW extends BaseAbility {
        GarenW() {
            super("garen_w", "Courage",
                "Réduit les dégâts reçus de 30% pendant 2s. Passif: +{armor} armure et MR.",
                Material.SHIELD, AbilitySlot.W,
                new double[]{23, 21, 19, 17, 15}, 0, 0, DamageType.TRUE);
        }
        @Override
        public void cast(BaseChampion caster, ChampionStats stats, Player target) {
            Player garen = (Player) target; // target = soi-même ici
            if (garen == null) return;
            // Résistance = reduction dégâts simulée avec Resistance potion
            garen.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 1, false, true));
            garen.sendActionBar(Component.text("🛡 Courage actif !", NamedTextColor.GOLD));
        }
        @Override
        public String getDynamicDescription(ChampionStats stats) {
            return String.format("Réduit les dégâts reçus de 30%% pendant 2s. Passif: +%.0f armure et MR bonus.",
                10 + level * 2.0);
        }
    }

    // ─── E : Tournoiement ────────────────────────
    static class GarenE extends BaseAbility {
        GarenE() {
            super("garen_e", "Tournoiement",
                "Tourne sur lui-même pendant 3s, infligeant {dmg} dégâts par tick aux ennemis proches.",
                Material.COMPASS, AbilitySlot.E,
                new double[]{9, 8, 7, 6, 5}, 5, 4, DamageType.PHYSICAL);
        }
        @Override
        public void cast(BaseChampion caster, ChampionStats stats, Player target) {
            // target = caster lui-même (self cast AoE)
            Player garen = target;
            if (garen == null) return;
            garen.sendActionBar(Component.text("⚔ Tournoiement !", NamedTextColor.GOLD));

            new BukkitRunnable() {
                int ticks = 0;
                @Override
                public void run() {
                    if (ticks >= 60) { cancel(); return; } // 3 secondes
                    Location loc = garen.getLocation();
                    // Dégâts AoE rayon 4
                    double dmgPerTick = (4 + 0.14 * stats.getFinalAD()) / 20.0;
                    Collection<Entity> nearby = loc.getWorld().getNearbyEntities(loc, 4, 2, 4);
                    for (Entity e : nearby) {
                        if (e instanceof Player victim && !victim.equals(garen)) {
                            double dmg = stats.calcPhysicalDamage(dmgPerTick, null);
                            victim.damage(dmg);
                        }
                    }
                    // Particules
                    garen.getWorld().spawnParticle(Particle.SWEEP_ATTACK, loc, 3, 2, 1, 2);
                    ticks += 2;
                }
            }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
        }
        @Override
        public String getDynamicDescription(ChampionStats stats) {
            double totalDmg = (4 + 0.14 * stats.getFinalAD()) * 3;
            return String.format("Tourne 3s, infligeant %.0f dégâts physiques totaux aux ennemis dans 4 blocs (14%% AD par seconde).",
                totalDmg);
        }
    }

    // ─── R : Exécution ────────────────────────────
    static class GarenR extends BaseAbility {
        GarenR() {
            super("garen_r", "Exécution",
                "Invoque la Main de la Justice sur la cible. Inflige {dmg} dégâts vrais. Dégâts augmentés sur cibles basses vie.",
                Material.NETHERITE_SWORD, AbilitySlot.R,
                new double[]{120, 100, 80}, 15, 0, DamageType.TRUE);
        }
        @Override
        public void cast(BaseChampion caster, ChampionStats stats, Player target) {
            if (target == null) return;
            double missingHpBonus = 1.0 + (1.0 - target.getHealth() / target.getMaxHealth()) * 1.5;
            double baseDmg = 150 + 20.0 * level;
            double dmg = baseDmg * missingHpBonus;

            // Effet visuel
            target.getLocation().getWorld().strikeLightningEffect(target.getLocation());
            target.getWorld().spawnParticle(Particle.FLASH, target.getLocation(), 1);
            target.damage(dmg);
            target.sendMessage(Component.text("☠ Exécution de Garen !", NamedTextColor.DARK_RED));
        }
        @Override
        public String getDynamicDescription(ChampionStats stats) {
            double base = 150 + 20.0 * level;
            return String.format("Inflige %.0f dégâts vrais (×2.5 sur cible à 0%% de vie). Idéal pour finisher.",
                base);
        }
    }
}
