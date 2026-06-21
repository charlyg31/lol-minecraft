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

public class Leona extends BaseChampion {
    public Leona() {
        super("leona", "Leona", ChampionRole.SUPPORT,
            new ChampionStats(646,60,0,38,32,0.625,0,335,1.25,8.5));
        getStats().setGrowthStats(104.0,3.0,4.7,2.05,0.02900,0.85);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(576, 7.0, ResourceSystem.ResourceType.MANA, 420, 9.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_leona","Attaque de base",Material.IRON_SWORD,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            DamageUtil.damage(c, t, dmg, false, DamageUtil.Type.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_leona","Lumière du Zénith",Material.GOLD_INGOT,AbilitySlot.Q,
            new double[]{11,10,9,8,7},5,0,DamageType.MAGICAL);
            resourceCost = 45;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double[] base={10,35,60,85,110};double dmg=base[getLevel()-1]+s.getFinalAP()*0.3;
            DamageUtil.abilityDamageMagicEntity(c, tgt, dmg);
            tgt.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,25,10,false,true));
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("☀ Stun 1.25s — Leona Q!",NamedTextColor.YELLOW));
            c.getWorld().spawnParticle(Particle.END_ROD,tgt.getLocation(),8,0.3,0.3,0.3);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + stun 1.25s.",40+s.getFinalAP()*0.4);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_leona","Éclat Solaire",Material.GOLDEN_CHESTPLATE,AbilitySlot.W,
            new double[]{14,13,12,11,10},0,0,DamageType.TRUE);
            resourceCost = 45;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,60,2,false,true));
            c.sendActionBar(Component.text("🛡 Éclat Solaire 3s!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+30 armure et MR pendant 3s. Bonus dégâts AA.";}
    }

    static class E extends BaseAbility {
        E(){super("e_leona","Zenith Blade",Material.BLAZE_ROD,AbilitySlot.E,
            new double[]{13,12,11,10,9},20,0,DamageType.MAGICAL);
            resourceCost = 55;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            Location dest=safeTeleport(c.getLocation(),tgt.getLocation());
            c.teleport(dest);
            double dmg=60+s.getFinalAP()*0.4;
            DamageUtil.abilityDamageMagicEntity(c, tgt, dmg);
            tgt.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,15,10,false,true));
            c.getWorld().spawnParticle(Particle.END_ROD,dest,5,1,0,1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Dash sur cible: %.0f dégâts + stun.",60+s.getFinalAP()*0.4);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_leona","Éclipse Solaire",Material.SUNFLOWER,AbilitySlot.R,
            new double[]{130,105,80},25,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double dmg=100+s.getFinalAP()*0.7;
            tgt.getWorld().getNearbyEntities(tgt.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player)
                .forEach(e->{
                    DamageUtil.abilityDamageMagic(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,10,false,true));
                    ((Player)e).sendActionBar(Component.text("☀ ECLIPSE!",NamedTextColor.YELLOW));
                });
            tgt.getWorld().spawnParticle(Particle.END_ROD,tgt.getLocation(),30,2,1,2);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts AoE + stun 1.5s au centre.",100+s.getFinalAP()*0.7);
        }
    }
}