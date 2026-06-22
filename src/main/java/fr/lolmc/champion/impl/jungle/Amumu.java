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

public class Amumu extends BaseChampion {
    public Amumu() {
        super("amumu", "Amumu", ChampionRole.JUNGLE,
            new ChampionStats(685,57,0,33,32,0.736,0,335,1.25,9.0));
        getStats().setGrowthStats(94.0,3.8,4.0,2.05,0.02180,0.85);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(613, 8.0, ResourceSystem.ResourceType.MANA, 480, 10.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_amumu","Attaque de base",Material.IRON_SWORD,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,2.0); if(tgt==null)return;
            TargetingUtil.dealDamage(c, tgt, s.getFinalAD(), TargetingUtil.DmgType.PHYSICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_amumu","Lancer de Bandage",Material.STRING,AbilitySlot.Q,
            new double[]{12,11,10,9,8},20,0,DamageType.MAGICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot ligne, 1er ennemi touche, stun 1s + tire Amumu vers lui. 80/130/180/230/280 + 85% AP
            var hits=TargetingUtil.skillshot(c, 12.0, 1.0, false);
            if(hits.isEmpty()){c.sendActionBar(Component.text("🧻 Bandage manqué!",NamedTextColor.GRAY));return;}
            var tgt=hits.get(0);
            // Tire Amumu vers la cible
            Location dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(1.5));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            double[] base={80,130,180,230,280};double dmg=base[getLevel()-1]+s.getFinalAP()*0.85;
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            if(tgt instanceof Player __p){
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,10,false,true)); // stun 1s
                __p.sendActionBar(Component.text("🧻 Lancer de Bandage! Stun 1s!",NamedTextColor.YELLOW));
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_LEASH_KNOT_PLACE, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={80,130,180,230,280};
            return String.format("Skillshot: %.0f dégâts (+85%%AP), stun 1s + tire Amumu vers la cible.",base[getLevel()-1]+s.getFinalAP()*0.85);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_amumu","Désespoir",Material.CRYING_OBSIDIAN,AbilitySlot.W,
            new double[]{0,0,0,0,0},0,3,DamageType.MAGICAL);
            resourceCost = 8;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : aura, dégâts toutes les 0.5s = 1/1.5/2/2.5/3% PV max de la cible + AP
            c.sendActionBar(Component.text("😢 Désespoir actif 5s!",NamedTextColor.DARK_BLUE));
            double[] pctHP={0.01,0.015,0.02,0.025,0.03};
            double pct=pctHP[getLevel()-1];
            new BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>=10){cancel();return;} // 10 ticks de 0.5s = 5s
                    for(var __t : TargetingUtil.entitiesInRadius(c, c.getLocation(), 3.5)){
                        double dmg=__t.getMaxHealth()*pct+s.getFinalAP()*0.01;
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                    }
                    c.getWorld().spawnParticle(Particle.FALLING_WATER,c.getLocation().add(0,1,0),10,2,0.5,2);
                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,10L); // toutes les 0.5s
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] pctHP={1,1.5,2,2.5,3};
            return String.format("Aura 5s: %.1f%% PV max de la cible/0.5s (dégâts magiques).",pctHP[getLevel()-1]);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_amumu","Caprice",Material.SLIME_BALL,AbilitySlot.E,
            new double[]{9,8,7,6,5},5,3,DamageType.MAGICAL);
            resourceCost = 35;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : dégâts magiques autour, 75/100/125/150/175 + 50% AP
            double[] base={75,100,125,150,175};double dmg=base[getLevel()-1]+s.getFinalAP()*0.5;
            TargetingUtil.dealDamageAll(c, TargetingUtil.entitiesInRadius(c, c.getLocation(), 3.0), dmg, TargetingUtil.DmgType.MAGICAL);
            c.getWorld().spawnParticle(Particle.ANGRY_VILLAGER,c.getLocation().add(0,1,0),12,1.5,0.5,1.5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={75,100,125,150,175};
            return String.format("%.0f dégâts magiques autour (+50%%AP). CD réduit quand frappé.",base[getLevel()-1]+s.getFinalAP()*0.5);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_amumu","Malédiction de la Momie",Material.IRON_NUGGET,AbilitySlot.R,
            new double[]{150,130,110},5,5,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : 200/300/400 + 80% AP, étourdit 1.5s tous les ennemis autour + knockdown
            double[] base={200,300,400};int rr=Math.min(getLevel()-1,2);double dmg=base[rr]+s.getFinalAP()*0.8;
            for(var __t : TargetingUtil.enemiesAround(c, 5.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                __t.setVelocity(new Vector(0,0.4,0)); // knockdown
                if(__t instanceof Player __p){
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,10,false,true)); // stun 1.5s
                    __p.sendActionBar(Component.text("⛓ MALÉDICTION DE LA MOMIE! Stun 1.5s",NamedTextColor.DARK_PURPLE));
                }
            }
            c.getWorld().spawnParticle(Particle.END_ROD,c.getLocation().add(0,1,0),30,3,1,3);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={200,300,400};int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts (+80%%AP) + étourdit 1.5s tous les ennemis autour.",base[r]+s.getFinalAP()*0.8);
        }
    }
}