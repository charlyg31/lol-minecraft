package fr.lolmc.champion.impl.top;

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
    /** Réinitialise les stacks de ce joueur (fin de partie / déconnexion). */
    public static void resetState(UUID id){ qStacks.remove(id); }
    public static void resetAllState(){ qStacks.clear(); }

    static class AA extends BasicAttackAbility {
        AA(){super("nasus",Material.BONE,2.5f,DamageType.PHYSICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_nasus","Frappe Siphon",Material.BONE,AbilitySlot.Q,
            new double[]{8,7,6,5,4},5,0,DamageType.PHYSICAL);
            resourceCost = 20;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){c.sendActionBar(Component.text("💀 Aucune cible",NamedTextColor.GRAY));return;}
            // LoL : AD + bonus par rang (30/50/70/90/110) + stacks permanents
            double[] bonus={30,50,70,90,110};
            int stacks=qStacks.getOrDefault(c.getUniqueId(),0);
            double dmg=s.getFinalAD()+bonus[getLevel()-1]+stacks;
            boolean kills = tgt.getHealth()-dmg<=0;
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            // Stacks : +12 sur champion/gros monstre tué, +3 sinon
            if(kills){
                boolean big = (tgt instanceof Player) || fr.lolmc.game.JungleManager.isJungleMonster(tgt);
                qStacks.merge(c.getUniqueId(), big?12:3, Integer::sum);
                c.sendActionBar(Component.text("💀 Stacks Q: "+qStacks.get(c.getUniqueId()),NamedTextColor.GOLD));
            }
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,tgt.getLocation().add(0,1,0),5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_SKELETON_HURT, 1f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] bonus={30,50,70,90,110};
            return String.format("AD + %.0f + stacks. +3/kill (+12 champion/gros). RESET attaque.",bonus[getLevel()-1]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_nasus","Flétrissure",Material.WITHER_ROSE,AbilitySlot.W,
            new double[]{15,14,13,12,11},20,0,DamageType.MAGICAL);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.0); if(tgt==null){c.sendActionBar(Component.text("💀 Aucune cible",NamedTextColor.GRAY));return;}
            // LoL : ralentit 35% montant jusqu'à 95% sur 5s (+ ralentit vitesse d'attaque)
            // Approximation : lenteur progressive (amplifier croît) sur 5s
            new BukkitRunnable(){
                int sec=0;
                @Override public void run(){
                    if(sec>=5 || tgt.isDead()){cancel();return;}
                    int amp=2+sec; // amplifier croît -> ralentissement de plus en plus fort
                    tgt.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,25,Math.min(amp,6),false,true));
                    tgt.getWorld().spawnParticle(Particle.WITCH,tgt.getLocation().add(0,1,0),8,0.4,0.8,0.4);
                    sec++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("💀 Flétri! Ralentissement croissant",NamedTextColor.DARK_PURPLE));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Ralentit 35%% montant à 95%% sur 5s (déplacement + vitesse d'attaque).";}
    }

    static class E extends BaseAbility {
        E(){super("e_nasus","Feu Spirituel",Material.SOUL_LANTERN,AbilitySlot.E,
            new double[]{12,11,10,9,8},20,3,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : zone au sol visée, dégâts magiques/s + réduction armure 30-50% sur 5s
            Location loc=TargetingUtil.getAimedGroundLocation(c, 8.0);
            double[] dotBase={55,95,135,175,215};
            double perSec=(dotBase[getLevel()-1]+s.getFinalAP()*0.12);
            new BukkitRunnable(){
                int sec=0;
                @Override public void run(){
                    if(sec>=5){cancel();return;}
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 3.5)){
                        TargetingUtil.dealDamage(c, __t, perSec, TargetingUtil.DmgType.MAGICAL);
                        // Réduction d'armure (approx via faiblesse)
                        if(__t instanceof Player __p)
                            __p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,40,0,false,true));
                    }
                    loc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,loc,15,3,0.3,3,0.02);
                    sec++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
            c.getWorld().playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] dotBase={55,95,135,175,215};
            return String.format("Zone au sol: %.0f dégâts magiques/s pendant 5s + réduit l'armure.",dotBase[getLevel()-1]+s.getFinalAP()*0.12);
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