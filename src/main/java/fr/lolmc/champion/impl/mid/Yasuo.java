package fr.lolmc.champion.impl.mid;

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

public class Yasuo extends BaseChampion {
    public Yasuo() {
        super("yasuo", "Yasuo", ChampionRole.MID,
            new ChampionStats(590,60,0,30,32,0.697,0,345,1.75,6.5));
        getStats().setGrowthStats(110.0,3.5,4.3,2.05,0.03500,0.90);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(523, 0.0, ResourceSystem.ResourceType.FLOW, 100, 0.0);
    }

    public static final Map<UUID,Integer> qCasts=new HashMap<>();

    static class AA extends BaseAbility {
        AA(){super("aa_yasuo","Attaque de base",Material.IRON_SWORD,AbilitySlot.AA,
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
        Q(){super("q_yasuo","Acier Tranchant",Material.BLAZE_POWDER,AbilitySlot.Q,
            new double[]{4,3.5,3,2.5,2},20,6,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            int casts=qCasts.merge(c.getUniqueId(),1,Integer::sum);
            double[] base={20,45,70,95,120};double dmg=base[getLevel()-1]+s.getFinalAD()*1.0;
            // Tornade linéaire devant
            Location front=c.getLocation().add(c.getLocation().getDirection().multiply(3));
            c.getWorld().getNearbyEntities(front,3,1,3).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamage(c, (Player)e, dmg);
                    if(casts>=2) {
                        ((Player)e).setVelocity(new Vector(0,1.2,0));
                        ((Player)e).sendActionBar(Component.text("🌪 Knockup Yasuo!",NamedTextColor.YELLOW));
                    }
                });
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,front,5,1,0.5,1);
            if(casts>=2) qCasts.put(c.getUniqueId(),0);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts devant. 2ème cast = knockup.",20+s.getFinalAD());
        }
    }

    static class W extends BaseAbility {
        W(){super("w_yasuo","Mur du Vent",Material.WHITE_WOOL,AbilitySlot.W,
            new double[]{26,24,22,20,18},0,6,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,80,0,false,true));
            c.sendActionBar(Component.text("💨 Mur du Vent 4s!",NamedTextColor.WHITE));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Crée un mur bloquant les projectiles ennemis pendant 4s.";}
    }

    static class E extends BaseAbility {
        E(){super("e_yasuo","Balayage de Lame",Material.FEATHER,AbilitySlot.E,
            new double[]{0.5,0.5,0.5,0.5,0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            Location dest=safeTeleport(c.getLocation(),t.getLocation());
            c.teleport(dest);
            double dmg=70+s.getFinalAD()*0.6;
            DamageUtil.abilityDamage(c, t, dmg);
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,dest,5);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Dash sur cible: %.0f dégâts (70+60%%AD). CD court.",70+s.getFinalAD()*0.6);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_yasuo","Dernier Souffle",Material.ELYTRA,AbilitySlot.R,
            new double[]{200,180,160},5,4,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=200+s.getFinalAD()*1.5;
            c.getWorld().getNearbyEntities(c.getLocation(),4,3,4).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamage(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,10,false,true));
                    ((Player)e).sendActionBar(Component.text("⚔ Dernier Souffle!",NamedTextColor.DARK_GRAY));
                });
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation(),10,2,2,2);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + suspend 1s cibles en l'air.",200+s.getFinalAD()*1.5);
        }
    }
}