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

public class Warwick extends BaseChampion {
    public Warwick() {
        super("warwick", "Warwick", ChampionRole.JUNGLE,
            new ChampionStats(620,65,0,33,32,0.638,0,335,1.25,5.0));
        getStats().setGrowthStats(99.0,3.0,4.6,2.05,0.02300,0.80);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(577, 9.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_warwick","Attaque de base",Material.IRON_SWORD,AbilitySlot.AA,
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
        Q(){super("q_warwick","Morsure Infectée",Material.BONE,AbilitySlot.Q,
            new double[]{6,5,4,3,2},20,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double[] pct={0.06,0.07,0.08,0.09,0.10};double dmg=tgt.getMaxHealth()*pct[getLevel()-1]*4+s.getFinalAD()*1.0;
            DamageUtil.abilityDamageMagicEntity(c, tgt, dmg);
            double heal=dmg*0.7;
            c.setHealth(Math.min(c.getMaxHealth(),c.getHealth()+heal));
            c.getWorld().spawnParticle(Particle.HEART,c.getLocation(),5,0.5,0.5,0.5);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double dmg=10+s.getFinalAD()+s.getFinalAP()*0.5;
            return String.format("%.0f dégâts magiques. Soigne %.0f HP (70%% des dégâts).",dmg,dmg*0.7);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_warwick","Meute de Prédateurs",Material.RED_WOOL,AbilitySlot.W,
            new double[]{0,0,0,0,0},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,60,2,false,true));
            c.sendActionBar(Component.text("🐺 Meute actif!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Passif: +50%% vitesse près d'ennemis <50%% HP. Actif: boost vitesse 3s.";}
    }

    static class E extends BaseAbility {
        E(){super("e_warwick","Peur au Ventre",Material.SPIDER_EYE,AbilitySlot.E,
            new double[]{22,20,18,16,14},5,3,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            for(var __t : TargetingUtil.enemiesAround(c, 3.0)){
                if(__t instanceof Player __p){
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,4,false,true));
                    __p.sendActionBar(Component.text("😱 Peur!",NamedTextColor.DARK_RED));
                }
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Terrifie les ennemis dans 3 blocs pendant 1.5s (ralentissement extrême).";}
    }

    static class R extends BaseAbility {
        R(){super("r_warwick","Chasse Infinie",Material.RED_WOOL,AbilitySlot.R,
            new double[]{110,85,60},25,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            Location dest=safeTeleport(c.getLocation(),tgt.getLocation());
            c.teleport(dest);
            double dmg=175+s.getFinalAD()*1.5;
            DamageUtil.abilityDamageEntity(c, tgt, dmg);
            tgt.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,10,false,true));
            tgt.setVelocity(new Vector(0,0.8,0));
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🐺 SUPPRESSION!",NamedTextColor.DARK_RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Bondit. %.0f dégâts + suppression 1.5s.",175+s.getFinalAD()*1.5);
        }
    }
}