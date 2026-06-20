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

public class Ashe extends BaseChampion {
    public Ashe() {
        super("ashe", "Ashe", ChampionRole.ADC,
            new ChampionStats(610,59,0,26,30,0.658,0,325,6.0,3.5));
        getStats().setGrowthStats(101.0,3.5,4.6,1.30,0.03000,0.55);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(528, 3.0, ResourceSystem.ResourceType.MANA, 480, 11.0);
        setAutoAttackRange(6.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_ashe","Attaque de base",Material.ARROW,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            DamageUtil.damage(c, t, dmg, false);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_ashe","Tir Concentré",Material.SPECTRAL_ARROW,AbilitySlot.Q,
            new double[]{14,12,10,8,6},25,0,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,120,0,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,120,0,false,true));
            c.sendActionBar(Component.text("🏹 Tir Concentré 6s!",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "6s: +15%% AD, les AA ralentissent davantage.";}
    }

    static class W extends BaseAbility {
        W(){super("w_ashe","Tir de Volée",Material.TIPPED_ARROW,AbilitySlot.W,
            new double[]{14,12,10,8,6},25,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.getFinalAD()*1.1;
            t.getWorld().getNearbyEntities(t.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player)
                .forEach(e->{
                    DamageUtil.abilityDamage(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));
                });
            c.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),20,2,1,2);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts AoE + ralentit (110%%AD).",s.getFinalAD()*1.1);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_ashe","Faucon Explorateur",Material.FEATHER,AbilitySlot.E,
            new double[]{5,4,3,2,1},60,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.sendActionBar(Component.text("🦅 Faucon envoyé! Zone révélée.",NamedTextColor.YELLOW));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Envoie un faucon révélant une zone de la carte.";}
    }

    static class R extends BaseAbility {
        R(){super("r_ashe","Flèche de Cristal",Material.DIAMOND,AbilitySlot.R,
            new double[]{100,80,60},25,2,DamageType.PHYSICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=250+s.getFinalAP();
            DamageUtil.abilityDamage(c, t, dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,70,10,false,true));
            t.getWorld().spawnParticle(Particle.END_ROD,t.getLocation(),30,1,1,1);
            t.sendMessage(Component.text("❄ Flèche de Cristal!",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + stun 3.5s (portée infinie).",250+s.getFinalAP());
        }
    }
}