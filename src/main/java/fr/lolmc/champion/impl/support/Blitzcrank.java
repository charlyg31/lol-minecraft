package fr.lolmc.champion.impl.support;

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

public class Blitzcrank extends BaseChampion {
    public Blitzcrank() {
        super("blitzcrank", "Blitzcrank", ChampionRole.SUPPORT,
            new ChampionStats(600,62,0,37,32,0.625,0,325,1.25,7.5));
        getStats().setGrowthStats(109.0,3.5,4.7,2.05,0.01130,0.75);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(623, 8.0, ResourceSystem.ResourceType.MANA, 400, 10.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_blitzcrank","Attaque de base",Material.IRON_INGOT,AbilitySlot.AA,
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
        Q(){super("q_blitzcrank","Poing-Harpon",Material.FISHING_ROD,AbilitySlot.Q,
            new double[]{20,19,18,17,16},20,0,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double[] base={90,130,170,210,250};double dmg=base[getLevel()-1]+s.getFinalAP()*1.2;
            DamageUtil.abilityDamageMagicEntity(c, tgt, dmg);
            Vector pull=c.getLocation().toVector().subtract(tgt.getLocation().toVector()).normalize().multiply(1.8);
            pull.setY(0.3); tgt.setVelocity(pull);
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🪝 HARPON!",NamedTextColor.YELLOW));
            c.getWorld().spawnParticle(Particle.CRIT,tgt.getLocation(),8,0.3,0.3,0.3);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + attire la cible vers toi (75+60%%AP).",75+s.getFinalAP()*0.6);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_blitzcrank","Surcharge",Material.REDSTONE,AbilitySlot.W,
            new double[]{22,20,18,16,14},0,0,DamageType.TRUE);
            resourceCost = 75;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,4,false,true));
            c.sendActionBar(Component.text("⚡ SURCHARGE 4s!",NamedTextColor.YELLOW));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Vitesse x2 pendant 4s.";}
    }

    static class E extends BaseAbility {
        E(){super("e_blitzcrank","Poing Électrique",Material.LIGHTNING_ROD,AbilitySlot.E,
            new double[]{10,9,8,7,6},5,0,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double dmg=80+s.getFinalAD();
            DamageUtil.abilityDamageEntity(c, tgt, dmg);
            tgt.setVelocity(new Vector(0,0.9,0));
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("⚡ Knockup!",NamedTextColor.YELLOW));
            c.getWorld().strikeLightningEffect(tgt.getLocation());
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + knockup (80+100%%AD).",80+s.getFinalAD());
        }
    }

    static class R extends BaseAbility {
        R(){super("r_blitzcrank","Champ Statique",Material.COPPER_INGOT,AbilitySlot.R,
            new double[]{40,30,20},5,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double dmg=275+s.getFinalAP()*0.7;
            c.getWorld().getNearbyEntities(c.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamageMagic(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,10,10,false,true));
                });
            c.getWorld().strikeLightningEffect(c.getLocation());
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts foudre AoE + silence (275+70%%AP).",275+s.getFinalAP()*0.7);
        }
    }
}