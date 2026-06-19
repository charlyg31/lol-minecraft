package fr.lolmc.champion.impl.adc;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class Jinx extends BaseChampion {
    public Jinx() {
        super("jinx", "Jinx", ChampionRole.ADC,
            new ChampionStats(516,57,0,21,30,0.625,0,325,25,3));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(516, 3.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_jinx","Attaque de base",Material.CROSSBOW,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            DamageUtil.damage(c, t, dmg, false, DamageUtil.Type.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_jinx","Choix des Armes",Material.TNT,AbilitySlot.Q,
            new double[]{0,0,0,0,0},25,3,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,40,1,false,true));
            c.sendActionBar(Component.text("💥 Bazooka actif!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Alterne mini-canons (vitesse) et Fishbones (AoE, portée+).";}
    }

    static class W extends BaseAbility {
        W(){super("w_jinx","Zap",Material.LIGHTNING_ROD,AbilitySlot.W,
            new double[]{10,9,8,7,6},25,0,DamageType.PHYSICAL);
            resourceCost = 20;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=20+s.getFinalAD()*1.6;
            DamageUtil.abilityDamage(c, t, dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,50,2,false,true));
            c.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,t.getLocation(),15,0.5,0.5,0.5);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + ralentit 2.5s (20+160%%AD).",20+s.getFinalAD()*1.6);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_jinx","Champ de Mines",Material.TRIPWIRE_HOOK,AbilitySlot.E,
            new double[]{20,18,16,14,12},25,2,DamageType.MAGICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=80+s.getFinalAP()*0.7;
            DamageUtil.abilityDamageMagic(c, t, dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,2,false,true));
            c.getWorld().spawnParticle(Particle.EXPLOSION,t.getLocation(),3,1,0,1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Mine: %.0f dégâts + ralentit 3s (80+70%%AP).",80+s.getFinalAP()*0.7);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_jinx","Super Méga Rocket",Material.FIREWORK_ROCKET,AbilitySlot.R,
            new double[]{90,75,60},25,5,DamageType.PHYSICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double missing=1.0+(1.0-t.getHealth()/t.getMaxHealth())*1.5;
            double dmg=(300+s.getFinalAD()*1.5)*missing;
            t.getWorld().getNearbyEntities(t.getLocation(),5,2,5).stream()
                .filter(e->e instanceof Player)
                .forEach(e->DamageUtil.abilityDamage(c, (Player)e, dmg));
            t.getWorld().createExplosion(t.getLocation(),2f,false,false);
            t.sendMessage(Component.text("🚀 SUPER MÉGA ROCKET!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts AoE 5 blocs (x2.5 sur cible basse vie).",300+s.getFinalAD()*1.5);
        }
    }
}