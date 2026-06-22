package fr.lolmc.champion.impl.adc;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.ability.base.BasicAttackAbility;
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

public class Jinx extends BaseChampion {
    public Jinx() {
        super("jinx", "Jinx", ChampionRole.ADC,
            new ChampionStats(630,59,0,26,30,0.625,0,325,5.25,3.8));
        getStats().setGrowthStats(100.0,3.4,4.7,1.30,0.01360,0.50);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(516, 3.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
        setAutoAttackRange(6.0);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("jinx",Material.CROSSBOW,6.0f,DamageType.PHYSICAL);}
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
        W(){super("w_jinx","Zap!",Material.LIGHTNING_ROD,AbilitySlot.W,
            new double[]{8,7,6,5,4},25,0,DamageType.PHYSICAL);
            resourceCost = 20;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot ligne. 10/80/150/220/290 + 160% AD au 1er touché + ralentit 30-60%
            double[] base=fr.lolmc.util.Balance.base("w_jinx",new double[]{10,80,150,220,290});double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("w_jinx","ad",1.6);
            var hits=TargetingUtil.skillshot(c, 14.0, 0.8, false); // s'arrête au 1er
            if(hits.isEmpty()){c.sendActionBar(Component.text("⚡ Zap manqué!",NamedTextColor.GRAY));return;}
            var main=hits.get(0);
            TargetingUtil.dealDamage(c, main, dmg, TargetingUtil.DmgType.PHYSICAL);
            if(main instanceof Player __p)
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,2,false,true)); // ralentit fort
            main.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,main.getLocation().add(0,1,0),15,0.5,0.5,0.5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1.4f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_jinx",new double[]{10,80,150,220,290});
            return String.format("Skillshot: %.0f dégâts (+160%%AD) au 1er touché + ralentit.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("w_jinx","ad",1.6));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_jinx","Croque-Flammes!",Material.TRIPWIRE_HOOK,AbilitySlot.E,
            new double[]{24,20.5,17,13.5,10},25,2,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : lance 3 pièges sur zone visée. Enracinent les ennemis + dégâts magiques 70-270
            Location loc=TargetingUtil.getAimedGroundLocation(c, 9.0);
            double[] base=fr.lolmc.util.Balance.base("e_jinx",new double[]{70,120,170,220,270});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_jinx","ap",1.0);
            for(var __t : TargetingUtil.entitiesInRadius(c, loc, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                // Root (enracinement) : lenteur extrême brève
                if(__t instanceof Player __p){
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,35,10,false,true));
                    __p.sendActionBar(Component.text("🦷 ENRACINÉ par les Croque-Flammes!",NamedTextColor.RED));
                }
            }
            // Animation : pièges-mâchoires sur la zone
            loc.getWorld().spawnParticle(Particle.FLAME,loc,30,3,0.3,3,0.02);
            loc.getWorld().spawnParticle(Particle.LAVA,loc,10,3,0.2,3);
            c.getWorld().playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_jinx",new double[]{70,120,170,220,270});
            return String.format("Pièges (zone visée): %.0f dégâts magiques + enracine.",base[getLevel()-1]+s.getFinalAP());
        }
    }

    static class R extends BaseAbility {
        R(){super("r_jinx","Super Méga Roquette de la Mort!",Material.FIREWORK_ROCKET,AbilitySlot.R,
            new double[]{75,65,55},25,5,DamageType.PHYSICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : roquette skillshot longue portée. 25-250/40-400/55-550 selon distance + 15-150% AD + % PV manquants. Explose en zone
            var hits=TargetingUtil.skillshot(c, 30.0, 1.2, false);
            if(hits.isEmpty()){c.sendActionBar(Component.text("🚀 Roquette tirée (aucune cible)",NamedTextColor.GRAY));return;}
            var main=hits.get(0);
            // Dégâts selon distance parcourue (10% à 100%)
            double dist=c.getLocation().distance(main.getLocation());
            double distMult=Math.min(1.0, 0.1+dist*0.06); // monte avec la distance
            double[] baseMax={250,400,550};int rr=Math.min(getLevel()-1,2);
            double[] missPct={0.25,0.30,0.35};
            double baseDmg=baseMax[rr]*distMult+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_jinx","ad",1.5)*distMult;
            // Explosion en zone : chaque cible prend dégâts + bonus selon SES PV manquants
            for(var __t : TargetingUtil.entitiesInRadius(c, main.getLocation(), 5.0)){
                double missingHP=__t.getMaxHealth()-__t.getHealth();
                double dmg=baseDmg+missingHP*missPct[rr];
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
            }
            main.getWorld().spawnParticle(Particle.EXPLOSION,main.getLocation(),8,2,1,2);
            main.getWorld().spawnParticle(Particle.FLAME,main.getLocation(),50,3,2,3,0.1);
            main.getWorld().playSound(main.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
            if(main instanceof Player _tp)_tp.sendMessage(Component.text("🚀 SUPER MÉGA ROQUETTE DE LA MORT!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_jinx",new double[]{250,400,550});int r=Math.min(getLevel()-1,2);
            return String.format("Roquette globale: jusqu'à %.0f dégâts (+150%%AD) selon distance + %% PV manquants. Explose en zone.",base[r]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_jinx","ad",1.5));
        }
    }
}