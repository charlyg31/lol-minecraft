package fr.lolmc.champion.impl.adc;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
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

public class MissFortune extends BaseChampion {
    public MissFortune() {
        super("missfortune", "Miss Fortune", ChampionRole.ADC,
            new ChampionStats(523,56,0,23,30,0.656,0,325,25,3));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
    }

    static class AA extends BaseAbility {
        AA(){super("aa_missfortune","Attaque de base",Material.BOW,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            t.damage(dmg); s.applyVamp(dmg,false);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_missfortune","Double Tir",Material.FLINT_AND_STEEL,AbilitySlot.Q,
            new double[]{7,6,5,4,3},25,0,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double d1=s.calcPhysicalDamage(20+s.getFinalAD()*0.85,null);
            double d2=s.calcPhysicalDamage((20+s.getFinalAD()*0.85)*1.35,null);
            t.damage(d1+d2);
            c.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),10,0.3,0.3,0.3);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double d=20+s.getFinalAD()*0.85;
            return String.format("1ère balle: %.0f. 2ème balle: %.0f (+35%%).",d,d*1.35);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_missfortune","Strut",Material.LEATHER_BOOTS,AbilitySlot.W,
            new double[]{19,17,15,13,11},0,0,DamageType.TRUE);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,60,1,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,60,2,false,true));
            c.sendActionBar(Component.text("💃 Strut 3s!",NamedTextColor.LIGHT_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+20%% vitesse et +haste 3s. Passif: vitesse hors combat.";}
    }

    static class E extends BaseAbility {
        E(){super("e_missfortune","Pluie de Balles",Material.GUNPOWDER,AbilitySlot.E,
            new double[]{18,15,12,9,6},25,3,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcPhysicalDamage(60+s.getFinalAD()*0.8,null);
            t.getWorld().getNearbyEntities(t.getLocation(),3,2,3).stream()
                .filter(e->e instanceof Player)
                .forEach(e->{
                    ((Player)e).damage(dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));
                });
            c.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),20,1.5,1,1.5);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Zone 3 blocs: %.0f dégâts + ralentit 2s (60+80%%AD).",60+s.getFinalAD()*0.8);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_missfortune","Pluie de Balles R",Material.NETHERITE_HOE,AbilitySlot.R,
            new double[]{120,100,80},25,8,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.sendActionBar(Component.text("🔫 PLUIE DE BALLES! 3s",NamedTextColor.RED));
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=60){cancel();return;}
                    double dmg=(110+s.getFinalAP()*0.2)/3.0;
                    c.getWorld().getNearbyEntities(c.getLocation(),8,2,8).stream()
                        .filter(e->e instanceof Player&&!e.equals(c))
                        .forEach(e->((Player)e).damage(s.calcPhysicalDamage(dmg,null)));
                    c.getWorld().spawnParticle(Particle.CRIT,c.getLocation().add(
                        c.getLocation().getDirection().multiply(4)),5,3,0,3);
                    tick+=20;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts/s dans 8 blocs pendant 3s.",(110+s.getFinalAP()*0.2)/3.0);
        }
    }
}