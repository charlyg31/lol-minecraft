package fr.lolmc.champion.impl.top;

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

public class Nasus extends BaseChampion {
    public Nasus() {
        super("nasus", "Nasus", ChampionRole.TOP,
            new ChampionStats(631,67,0,34,32,0.638,0,350,1.25,9.0));
        getStats().setGrowthStats(104.0,3.5,4.3,2.05,0.03480,0.90);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(616, 9.0, ResourceSystem.ResourceType.MANA, 380, 9.0);
    }

    // Stacks Q globaux par UUID
    public static final Map<UUID,Integer> qStacks=new HashMap<>();

    static class AA extends BaseAbility {
        AA(){super("aa_nasus","Attaque de base",Material.BONE,AbilitySlot.AA,
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
        Q(){super("q_nasus","Frappe du Faucheur",Material.BONE,AbilitySlot.Q,
            new double[]{8,7,6,5,4},5,0,DamageType.PHYSICAL);
            resourceCost = 20;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            int stacks=qStacks.getOrDefault(c.getUniqueId(),0);
            double dmg=s.getFinalAD()+stacks;
            DamageUtil.abilityDamageEntity(c, tgt, dmg);
            if(tgt.getHealth()-dmg<=0) {qStacks.merge(c.getUniqueId(),6,Integer::sum);}
        }
        @Override public String getDynamicDescription(ChampionStats s){
            int stacks=qStacks.getOrDefault(UUID.randomUUID(),0);
            return String.format("AD + %d stacks Q = %.0f dégâts physiques. +6 stacks par kill.",stacks,s.getFinalAD()+stacks);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_nasus","Flétrissure",Material.WITHER_ROSE,AbilitySlot.W,
            new double[]{15,14,13,12,11},20,0,DamageType.MAGICAL);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            tgt.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,100,2,false,true));
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("💀 Flétrissure de Nasus -35%% vitesse!",NamedTextColor.DARK_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Réduit vitesse déplacement et attaque de 35%% pendant 5s.";}
    }

    static class E extends BaseAbility {
        E(){super("e_nasus","Esprit du Vide",Material.PURPLE_WOOL,AbilitySlot.E,
            new double[]{12,11,10,9,8},20,3,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            Location loc=tgt.getLocation();
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=100){cancel();return;}
                    double dmg=(55+s.getFinalMaxHP()*0.05)/5.0;
                    TargetingUtil.dealDamageAll(c, TargetingUtil.entitiesInRadius(c, loc, 3), dmg, TargetingUtil.DmgType.MAGICAL);
                    loc.getWorld().spawnParticle(Particle.WITCH,loc,5,1,1,1);
                    tick+=20;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Zone 3 blocs: %.0f dégâts magiques/s pendant 5s.",55+s.getFinalMaxHP()*0.05);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_nasus","Furie des Sables",Material.GOLDEN_HELMET,AbilitySlot.R,
            new double[]{120,100,80},0,3,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST,300,2,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,300,1,false,true));
            c.sendActionBar(Component.text("☀ Furie des Sables!",NamedTextColor.GOLD));
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=300){cancel();return;}
                    double dmg=3+s.getFinalMaxHP()*0.01;
                    TargetingUtil.dealDamageAll(c, TargetingUtil.entitiesInRadius(c, c.getLocation(), 3), dmg, TargetingUtil.DmgType.MAGICAL);
                    tick+=20;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("+HP/Armure/MR. Drain %.0f dégâts/s autour.",3+s.getFinalMaxHP()*0.01);
        }
    }
}