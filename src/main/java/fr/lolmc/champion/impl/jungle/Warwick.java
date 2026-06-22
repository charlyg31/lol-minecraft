package fr.lolmc.champion.impl.jungle;

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

    static class AA extends BasicAttackAbility {
        AA(){super("warwick",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            // Passif Faim Eternelle : soin sur AA (triplé sous 25% PV)
            double healBase=dmg*0.1;
            if(c.getHealth()<c.getMaxHealth()*0.25) healBase*=3;
            if(c.getHealth()<c.getMaxHealth()*0.5){
                var cm=LolPlugin.getInstance().getChampionManager();
                if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(healBase);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_warwick","Crocs de la Bête",Material.BONE,AbilitySlot.Q,
            new double[]{8,7.5,7,6.5,6},20,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : bond + morsure, 100% AD + 6-10% PV max de la cible, soigne 100% des dégâts post-mitigation
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,3.0); if(tgt==null){c.sendActionBar(Component.text("🐺 Aucune cible",NamedTextColor.GRAY));return;}
            // Bond vers la cible
            var dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(1.0));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            double[] pct={0.06,0.07,0.08,0.09,0.10};
            double dmg=s.getFinalAD()*1.0+tgt.getMaxHealth()*pct[getLevel()-1];
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            // Soin 100% des dégâts
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(dmg);
            c.getWorld().spawnParticle(Particle.HEART,c.getLocation().add(0,1,0),5,0.5,0.5,0.5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] pct={6,7,8,9,10};
            return String.format("Bond + morsure: %.0f dégâts + %.0f%% PV max cible. Soigne 100%% des dégâts.",s.getFinalAD(),pct[getLevel()-1]);
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
        E(){super("e_warwick","Hurlement Primal",Material.SPIDER_EYE,AbilitySlot.E,
            new double[]{20,18,16,14,12},5,3,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : réduction de dégâts 35-55% pendant 2.5s, puis hurlement = peur 1s + ralentit 90%
            double[] redux={1,2,2,3,3}; // amplifier RESISTANCE approx 35-55%
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,50,(int)redux[getLevel()-1],false,true));
            c.sendActionBar(Component.text("🛡 Hurlement Primal! Réduction de dégâts",NamedTextColor.GOLD));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_HOWL, 1f, 1f);
            // Après 1.5s : hurlement de peur
            new BukkitRunnable(){
                @Override public void run(){
                    for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                        if(__t instanceof Player __p){
                            __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,5,false,true)); // peur+ralentit 90%
                            __p.sendActionBar(Component.text("😱 Peur! (Hurlement Primal)",NamedTextColor.DARK_RED));
                        }
                    }
                    c.getWorld().spawnParticle(Particle.SONIC_BOOM,c.getLocation().add(0,1,0),2,2,0.5,2);
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_HOWL, 1.5f, 0.6f);
                }
            }.runTaskLater(LolPlugin.getInstance(),30L);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Réduit les dégâts reçus (35-55%) 2.5s, puis terrifie + ralentit 90% les ennemis autour 1s.";}
    }

    static class R extends BaseAbility {
        R(){super("r_warwick","Emprise Infinie",Material.RED_WOOL,AbilitySlot.R,
            new double[]{110,90,70},25,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : bond, supprime le 1er champion touché 1.5s, dégâts magiques 175/300/425 + 167% AD, soigne 100%
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("🐺 Aucune cible visée",NamedTextColor.GRAY));return;}
            var dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(0.5));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            double[] base={175,300,425};int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAD()*1.675;
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            // Soin 100% des dégâts
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(dmg);
            // Suppression : immobilisation totale 1.5s
            if(tgt instanceof Player __p){
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,20,false,true));
                __p.sendActionBar(Component.text("🐺 EMPRISE INFINIE! Supprimé!",NamedTextColor.DARK_RED));
            }
            tgt.setVelocity(new Vector(0,0.2,0));
            c.getWorld().spawnParticle(Particle.HEART,c.getLocation().add(0,1,0),10,1,1,1);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.5f, 0.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={175,300,425};int r=Math.min(getLevel()-1,2);
            return String.format("Bond: supprime la cible 1.5s, %.0f dégâts (+167%%AD), soigne 100%%.",base[r]+s.getFinalAD()*1.675);
        }
    }
}