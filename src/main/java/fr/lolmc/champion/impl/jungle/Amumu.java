package fr.lolmc.champion.impl.jungle;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.DamageUtil;
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
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            DamageUtil.damage(c, t, dmg, false, DamageUtil.Type.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_amumu","Bandage",Material.STRING,AbilitySlot.Q,
            new double[]{10,9,8,7,6},20,0,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            Location dest=safeTeleport(c.getLocation(),t.getLocation());
            c.teleport(dest);
            double[] base={70,95,120,145,170};double dmg=base[getLevel()-1]+s.getFinalAP()*0.85;
            DamageUtil.abilityDamageMagic(c, t, dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,10,false,true));
            t.sendActionBar(Component.text("🧻 Bandage! Stun 1s!",NamedTextColor.YELLOW));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Téléporte sur la cible. %.0f dégâts + stun 1s.",80+s.getFinalAP()*0.7);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_amumu","Désespoir",Material.CRYING_OBSIDIAN,AbilitySlot.W,
            new double[]{0,0,0,0,0},0,3,DamageType.MAGICAL);
            resourceCost = 8;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.sendActionBar(Component.text("😢 Désespoir actif 5s!",NamedTextColor.DARK_BLUE));
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=100){cancel();return;}
                    double dmg=s.getFinalMaxHP()*0.01+s.getFinalAP()*0.01;
                    c.getWorld().getNearbyEntities(c.getLocation(),3,2,3).stream()
                        .filter(e->e instanceof Player&&!e.equals(c))
                        .forEach(e->DamageUtil.abilityDamageMagic(c, (Player)e, dmg));
                    tick+=20;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Aura 5s: %.2f%% HP max + 1%% AP dégâts/s.",s.getFinalMaxHP()*0.01);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_amumu","Tantrum",Material.SLIME_BALL,AbilitySlot.E,
            new double[]{9,8,7,6,5},5,3,DamageType.MAGICAL);
            resourceCost = 35;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double dmg=65+s.getFinalAP()*0.3;
            c.getWorld().getNearbyEntities(c.getLocation(),3,2,3).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->DamageUtil.abilityDamageMagic(c, (Player)e, dmg));
            c.getWorld().spawnParticle(Particle.ANGRY_VILLAGER,c.getLocation(),8,1,1,1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts magiques AoE instant (65+30%%AP).",65+s.getFinalAP()*0.3);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_amumu","Malédiction Éternelle",Material.IRON_NUGGET,AbilitySlot.R,
            new double[]{150,130,110},5,5,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double dmg=150+s.getFinalAP()*0.8;
            c.getWorld().getNearbyEntities(c.getLocation(),5,2,5).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamageMagic(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,10,false,true));
                    ((Player)e).sendActionBar(Component.text("⛓ Malédiction Amumu! Root 2s",NamedTextColor.DARK_PURPLE));
                });
            c.getWorld().spawnParticle(Particle.END_ROD,c.getLocation(),20,3,1,3);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + root 2s tous ennemis 5 blocs (150+80%%AP).",150+s.getFinalAP()*0.8);
        }
    }
}