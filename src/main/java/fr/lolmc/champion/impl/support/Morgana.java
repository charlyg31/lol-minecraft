package fr.lolmc.champion.impl.support;

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

public class Morgana extends BaseChampion {
    public Morgana() {
        super("morgana", "Morgana", ChampionRole.SUPPORT,
            new ChampionStats(630,56,0,25,30,0.625,0,335,4.5,5.5));
        getStats().setGrowthStats(104.0,3.5,4.5,1.30,0.01530,0.55);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(560, 7.0, ResourceSystem.ResourceType.MANA, 480, 12.0);
        setAutoAttackRange(5.5);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_morgana","Attaque de base",Material.PURPLE_DYE,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.getFinalAP()*0.3;
            DamageUtil.damage(c, t, dmg, false, DamageUtil.Type.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAP());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_morgana","Filet des Ombres",Material.LEAD,AbilitySlot.Q,
            new double[]{11,10.5,10,9.5,9},20,0,DamageType.MAGICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double[] base={80,135,190,245,300};double dmg=base[getLevel()-1]+s.getFinalAP()*0.9;
            DamageUtil.abilityDamageMagic(c, t, dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,10,false,true));
            t.sendActionBar(Component.text("🕸 Root 2s — Morgana Q!",NamedTextColor.DARK_PURPLE));
            c.getWorld().spawnParticle(Particle.WITCH,t.getLocation(),10,0.5,0.5,0.5);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + root 2s (80+90%%AP).",80+s.getFinalAP()*0.9);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_morgana","Emprisonnement Torturé",Material.NETHERRACK,AbilitySlot.W,
            new double[]{10,9,8,7,6},20,4,DamageType.MAGICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            Location loc=t.getLocation();
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=100){cancel();return;}
                    double dmg=(24+s.getFinalAP()*0.11);
                    loc.getWorld().getNearbyEntities(loc,4,2,4).stream()
                        .filter(e->e instanceof Player)
                        .forEach(e->DamageUtil.abilityDamageMagic(c, (Player)e, dmg));
                    loc.getWorld().spawnParticle(Particle.WITCH,loc,5,2,0,2);
                    tick+=20;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Zone 4 blocs: %.0f dégâts magiques/s pendant 5s.",24+s.getFinalAP()*0.11);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_morgana","Bouclier Noir",Material.BLACK_STAINED_GLASS,AbilitySlot.E,
            new double[]{23,21,19,17,15},0,0,DamageType.TRUE);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Player dest=t!=null?t:c;
            dest.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,2,false,true));
            dest.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,80,1,false,true));
            dest.sendActionBar(Component.text("🛡 Bouclier Noir!",NamedTextColor.DARK_GRAY));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Bouclier anti-dégâts magiques + immunité CC sur allié.";}
    }

    static class R extends BaseAbility {
        R(){super("r_morgana","Chaînes de la Corruption",Material.IRON_NUGGET,AbilitySlot.R,
            new double[]{120,110,100},20,5,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double dmg=150+s.getFinalAP()*0.7;
            c.getWorld().getNearbyEntities(c.getLocation(),5,2,5).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamageMagic(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,10,false,true));
                    ((Player)e).sendActionBar(Component.text("⛓ STUN Morgana R!",NamedTextColor.DARK_PURPLE));
                });
            c.getWorld().spawnParticle(Particle.END_ROD,c.getLocation(),20,3,1,3);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + stun 1.5s tous ennemis 5 blocs.",150+s.getFinalAP()*0.7);
        }
    }
}