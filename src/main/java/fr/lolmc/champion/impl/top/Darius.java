package fr.lolmc.champion.impl.top;

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

public class Darius extends BaseChampion {
    public Darius() {
        super("darius", "Darius", ChampionRole.TOP,
            new ChampionStats(652,64,0,37,32,0.625,0,340,1.75,10.0));
        getStats().setGrowthStats(114.0,5.0,5.2,2.05,0.01000,0.95);
        setAutoAttackRange(2.5);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(582, 8.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_darius","Attaque de base",Material.IRON_AXE,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            DamageUtil.damage(c, t, dmg, false);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_darius","Lacération",Material.NETHERITE_AXE,AbilitySlot.Q,
            new double[]{9,8,7,6,5},5,5,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.getWorld().getNearbyEntities(c.getLocation(),5,2,5).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    double dist=e.getLocation().distance(c.getLocation());
                    double mult=dist>3?1.5:1.0; // bord = +50%
                    double dmg=(40+s.getFinalAD()*0.6)*mult;
                    DamageUtil.abilityDamage(c, (Player)e, dmg);
                });
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation(),5,2,1,2);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double base=40+s.getFinalAD()*0.6;
            return String.format("Zone 5 blocs: %.0f dégâts. Bord (>3 blocs): %.0f (+50%%).",base,base*1.5);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_darius","Destruction",Material.GOLDEN_AXE,AbilitySlot.W,
            new double[]{9,8,7,6,5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.getFinalAD()*2.0;
            DamageUtil.abilityDamage(c, t, dmg);
            c.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),10);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Frappe puissante: %.0f dégâts physiques (200%% AD).",s.getFinalAD()*2);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_darius","Appréhension",Material.FISHING_ROD,AbilitySlot.E,
            new double[]{24,21,18,15,12},5,5,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.getWorld().getNearbyEntities(c.getLocation(),5,2,5).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    Vector pull=c.getLocation().toVector().subtract(e.getLocation().toVector()).normalize().multiply(1.0);
                    pull.setY(0.25); e.setVelocity(pull);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,2,false,true));
                    ((Player)e).sendActionBar(Component.text("🪝 Appréhension!",NamedTextColor.DARK_RED));
                });
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Attire les ennemis dans 5 blocs + ralentit 1s.";}
    }

    static class R extends BaseAbility {
        R(){super("r_darius","Guillotine Noxienne",Material.NETHERITE_AXE,AbilitySlot.R,
            new double[]{120,100,80},5,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double[] base={125,250,375};
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAD()*0.75;
            t.getWorld().strikeLightningEffect(t.getLocation());
            DamageUtil.trueDamage(c, t, dmg);
            t.sendMessage(Component.text("☠ Guillotine Noxienne!",NamedTextColor.DARK_RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts vrais (100+40/niv+75%%AD). Reset CD si kill.",(100+level*40+s.getFinalAD()*0.75));
        }
    }
}