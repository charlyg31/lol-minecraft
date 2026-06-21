package fr.lolmc.champion.impl.adc;

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
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.0); if(tgt==null)return;
            TargetingUtil.dealDamage(c, tgt, s.getFinalAD(), TargetingUtil.DmgType.PHYSICAL);
            // Passif Tir de Givre : les attaques ralentissent la cible
            if(tgt instanceof Player __p)
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,0,false,true));
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
        W(){super("w_ashe","Volée",Material.TIPPED_ARROW,AbilitySlot.W,
            new double[]{18,14.5,11,7.5,4},25,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : tire des flèches en cône. 60/95/130/165/200 + 100% AD, applique Tir de Givre (ralentit)
            double[] base={60,95,130,165,200};double dmg=base[getLevel()-1]+s.getFinalAD()*1.0;
            var targets=TargetingUtil.enemiesInCone(c, 8.0, 45);
            for(var __t : targets){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if(__t instanceof Player __p)
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));
            }
            // Animation : flèches en éventail devant
            var dir=c.getEyeLocation().getDirection().normalize();
            for(double d=1; d<=8; d+=0.7){
                c.getWorld().spawnParticle(Particle.CRIT,c.getEyeLocation().add(dir.clone().multiply(d)),2,1.2,0.3,1.2,0);
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={60,95,130,165,200};
            return String.format("Cône de flèches: %.0f dégâts (+100%%AD) + ralentit.",base[getLevel()-1]+s.getFinalAD());
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
        R(){super("r_ashe","Flèche de Cristal Enchantée",Material.DIAMOND,AbilitySlot.R,
            new double[]{100,80,60},25,2,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot longue portée. Stun 1-3.5s selon distance parcourue. Zone autour = 50% dégâts + slow
            double[] base={200,400,600};int rr=Math.min(getLevel()-1,2);double dmg=base[rr]+s.getFinalAP()*1.2;
            var hits=TargetingUtil.skillshot(c, 25.0, 1.2, false); // s'arrête au 1er champion
            if(hits.isEmpty()){c.sendActionBar(Component.text("❄ Flèche tirée (aucune cible touchée)",NamedTextColor.AQUA));return;}
            var main=hits.get(0);
            // Distance parcourue → durée du stun (1 à 3.5s = 20 à 70 ticks)
            double dist=c.getLocation().distance(main.getLocation());
            int stunTicks=(int)Math.min(70, 20+dist*2.5);
            TargetingUtil.dealDamage(c, main, dmg, TargetingUtil.DmgType.MAGICAL);
            if(main instanceof Player __p){
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,stunTicks,10,false,true)); // stun approx
                __p.sendActionBar(Component.text("❄ ÉTOURDI par la Flèche de Cristal!",NamedTextColor.AQUA));
            }
            // Zone autour de la cible principale : 50% dégâts + ralentissement
            for(var __t : TargetingUtil.entitiesInRadius(c, main.getLocation(), 3.0)){
                if(__t.equals(main)) continue;
                TargetingUtil.dealDamage(c, __t, dmg*0.5, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p2)__p2.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,2,false,true));
            }
            main.getWorld().spawnParticle(Particle.END_ROD,main.getLocation().add(0,1,0),30,1.5,1,1.5);
            main.getWorld().playSound(main.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.5f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={200,400,600};int r=Math.min(getLevel()-1,2);
            return String.format("Skillshot: %.0f dégâts (+120%%AP) + étourdit 1-3.5s selon distance + zone.",base[r]+s.getFinalAP()*1.2);
        }
    }
}