package fr.lolmc.champion.impl.top;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
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

public class Garen extends BaseChampion {
    public Garen() {
        super("garen", "Garen", ChampionRole.TOP,
            new ChampionStats(620,66,0,36,32,0.625,0,345,5,8));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
    }

    static class AA extends BaseAbility {
        AA(){super("aa_garen","Attaque de base",Material.IRON_SWORD,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            t.damage(dmg); s.applyVamp(dmg,false);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    // Q — Jugement Décisif
    static class Q extends BaseAbility {
        Q(){super("q_garen","Jugement Décisif",Material.GOLDEN_SWORD,AbilitySlot.Q,
            new double[]{8,7.5,7,6.5,6},5,0,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcPhysicalDamage(30+s.getFinalAD()*1.4,null);
            t.damage(dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,4,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,30,1,false,true));
            t.sendActionBar(Component.text("⚠ Silence — Garen Q",NamedTextColor.RED));
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,t.getLocation(),3);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts physiques (30+140%%AD). Silence 1.5s + boost vitesse.",30+s.getFinalAD()*1.4);
        }
    }

    // W — Courage
    static class W extends BaseAbility {
        W(){super("w_garen","Courage",Material.SHIELD,AbilitySlot.W,
            new double[]{23,21,19,17,15},0,0,DamageType.TRUE);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,40,1,false,true));
            c.sendActionBar(Component.text("🛡 Courage actif!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return "Réduit les dégâts reçus de 30%% pendant 2s.";
        }
    }

    // E — Tournoiement
    static class E extends BaseAbility {
        E(){super("e_garen","Tournoiement",Material.COMPASS,AbilitySlot.E,
            new double[]{9,8,7,6,5},5,4,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.sendActionBar(Component.text("⚔ Tournoiement!",NamedTextColor.GOLD));
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=60){cancel();return;}
                    double dmg=(4+s.getFinalAD()*0.14)/10.0;
                    c.getWorld().getNearbyEntities(c.getLocation(),4,2,4).stream()
                        .filter(e->e instanceof Player&&!e.equals(c))
                        .forEach(e->{Player v=(Player)e;v.damage(s.calcPhysicalDamage(dmg,null));});
                    c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation(),2,1.5,0.5,1.5);
                    tick+=2;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,2L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Dégâts AoE rayon 4 pendant 3s. Total: %.0f dégâts physiques.",
                (4+s.getFinalAD()*0.14)*3);
        }
    }

    // R — Exécution
    static class R extends BaseAbility {
        R(){super("r_garen","Exécution",Material.NETHERITE_SWORD,AbilitySlot.R,
            new double[]{120,100,80},25,0,DamageType.TRUE);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double missing=1.0-t.getHealth()/t.getMaxHealth();
            double dmg=s.calcTrueDamage((150+level*20)*(1+missing*1.5));
            t.getWorld().strikeLightningEffect(t.getLocation());
            t.damage(dmg);
            t.sendMessage(Component.text("☠ Exécution de Garen!",NamedTextColor.DARK_RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts vrais (x2.5 sur cible à 0%% HP).",(150+level*20));
        }
    }
}