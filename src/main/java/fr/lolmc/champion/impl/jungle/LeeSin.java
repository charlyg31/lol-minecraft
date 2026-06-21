package fr.lolmc.champion.impl.jungle;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.TargetingUtil;
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

public class LeeSin extends BaseChampion {
    public LeeSin() {
        super("leesin", "Lee Sin", ChampionRole.JUNGLE,
            new ChampionStats(645,68,0,36,32,0.651,0,345,1.25,7.5));
        getStats().setGrowthStats(109.0,3.7,4.5,2.05,0.03000,0.70);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(570, 8.0, ResourceSystem.ResourceType.ENERGY, 200, 50.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_leesin","Attaque de base",Material.IRON_SWORD,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            DamageUtil.damage(c, t, dmg, false);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_leesin","Frappe Sonique",Material.ECHO_SHARD,AbilitySlot.Q,
            new double[]{11,10,9,8,7},15,0,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double dmg=55+s.getFinalAD()*0.9;
            DamageUtil.abilityDamageEntity(c, tgt, dmg);
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🦵 Frappe Sonique!",NamedTextColor.YELLOW));
            c.getWorld().spawnParticle(Particle.SONIC_BOOM,tgt.getLocation(),1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts physiques (55+90%%AD).",55+s.getFinalAD()*0.9);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_leesin","Protection",Material.SHIELD,AbilitySlot.W,
            new double[]{14,13,12,11,10},15,0,DamageType.TRUE);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Player dest=t!=null?t:c;
            destgt.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,60,1,false,true));
            destgt.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,60,1,false,true));
            if(t!=null&&!t.equals(c)) {
                Location safe=safeTeleport(c.getLocation(),tgt.getLocation());
                c.teleport(safe);
            }
            desif(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🛡 Bouclier Protection!",NamedTextColor.GREEN));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Dash vers allié. Donne un bouclier + régén HP.";}
    }

    static class E extends BaseAbility {
        E(){super("e_leesin","Tempête de Flammes",Material.FIRE_CHARGE,AbilitySlot.E,
            new double[]{10,9,8,7,6},5,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double dmg=60+s.getFinalAD()*0.5;
            c.getWorld().getNearbyEntities(c.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamage(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));
                });
            c.getWorld().spawnParticle(Particle.FLAME,c.getLocation(),20,2,1,2,0.05);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + ralentit 2s dans 4 blocs (60+50%%AD).",60+s.getFinalAD()*0.5);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_leesin","Dragon's Rage",Material.DRAGON_EGG,AbilitySlot.R,
            new double[]{90,75,60},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double[] base={150,300,450};
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAD()*2.0;
            DamageUtil.abilityDamageEntity(c, tgt, dmg);
            Vector kb=tgt.getLocation().toVector().subtract(c.getLocation().toVector()).normalize().multiply(2.5);
            kb.setY(1.5); tgt.setVelocity(kb);
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🐉 Dragon's Rage!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + knockback fort (175+200%%AD).",175+s.getFinalAD()*2);
        }
    }
}