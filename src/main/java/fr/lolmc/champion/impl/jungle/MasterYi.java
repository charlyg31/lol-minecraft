package fr.lolmc.champion.impl.jungle;

import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class MasterYi extends BaseChampion {
    public MasterYi() {
        super("masteryi", "Master Yi", ChampionRole.JUNGLE,
            new ChampionStats(559, 65, 0, 26, 32, 0.679, 0, 355, 5, 7));
    }
    @Override
    protected void registerAbilities() {
        setAbility(0, new YiAA()); setAbility(1, new YiQ());
        setAbility(2, new YiW()); setAbility(3, new YiE()); setAbility(4, new YiR());
    }

    static class YiAA extends BaseAbility {
        YiAA() { super("yi_aa","Attaque","Frappe pour {ad} dégâts.", Material.IRON_SWORD,
            AbilitySlot.AA, new double[]{0.5}, 5, 0, DamageType.PHYSICAL); }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            if (t != null) { t.damage(s.calcAutoAttackDamage(null)); s.applyVamp(s.getFinalAD(), false); }
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            return String.format("Frappe pour %.0f dégâts.", s.getFinalAD()); }
    }

    static class YiQ extends BaseAbility {
        YiQ() { super("yi_q","Frappe Alpha",
            "Se téléporte sur la cible: {dmg} dégâts vrais. Réinitialise l'AA.",
            Material.ENDER_PEARL, AbilitySlot.Q, new double[]{18,16,14,12,10}, 15, 0, DamageType.TRUE); }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            if (t == null) return;
            double dmg = s.calcTrueDamage(25 + s.getFinalAD() * 1.1);
            t.damage(dmg);
            t.getWorld().spawnParticle(Particle.CRIT, t.getLocation(), 10, 0.5, 0.5, 0.5);
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            return String.format("%.0f dégâts vrais (25 + 110%% AD).", 25 + s.getFinalAD() * 1.1); }
    }

    static class YiW extends BaseAbility {
        YiW() { super("yi_w","Méditation",
            "Régénère HP rapidement 4s et réduit les dégâts de 60%%.",
            Material.GOLDEN_APPLE, AbilitySlot.W, new double[]{35,30,25,20,15}, 0, 0, DamageType.TRUE); }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            if (t == null) return;
            t.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 4, false, true));
            t.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 80, 2, false, true));
            t.sendActionBar(Component.text("🧘 Méditation...", NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            return "Régénère HP pendant 4s. Réduit les dégâts reçus de 60%."; }
    }

    static class YiE extends BaseAbility {
        YiE() { super("yi_e","Style Wuju",
            "Actif 5s: les AA infligent +{dmg} dégâts vrais bonus.",
            Material.BLAZE_ROD, AbilitySlot.E, new double[]{18,17,16,15,14}, 0, 0, DamageType.TRUE); }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            if (t == null) return;
            t.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 1, false, true));
            t.sendActionBar(Component.text("⚔ Style Wuju actif!", NamedTextColor.YELLOW));
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            return String.format("Actif 5s: +%.0f dégâts vrais par AA (10 + 10%% AD).", 10 + 0.1 * s.getFinalAD()); }
    }

    static class YiR extends BaseAbility {
        YiR() { super("yi_r","Highlander",
            "Vitesse +35%% et haste +55%% pendant 7s. Immunité ralentissements. Kills reset CD.",
            Material.FEATHER, AbilitySlot.R, new double[]{85,70,55}, 0, 0, DamageType.TRUE); }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            if (t == null) return;
            t.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 140, 3, false, true));
            t.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 140, 2, false, true));
            t.sendActionBar(Component.text("⚡ HIGHLANDER!", NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            return "Vitesse +35%, haste +55% pendant 7s. Immunité aux ralentissements."; }
    }
}
