package fr.lolmc.champion.impl.mid;

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
    /** Reinitialise l'etat de ce joueur (fin de partie / deconnexion). */
    public static void resetState(UUID id){ qCasts.remove(id); eStacks.remove(id); }
    public static void resetAllState(){ qCasts.clear(); eStacks.clear(); }

    static class AA extends BasicAttackAbility {
        AA(){super("yasuo",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_yasuo","Tempête d'Acier",Material.BLAZE_POWDER,AbilitySlot.Q,
            new double[]{4,3.5,3,2.5,2},20,6,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            int casts=qCasts.merge(c.getUniqueId(),1,Integer::sum);
            // LoL : coup d'estoc en ligne. 20/45/70/95/120 + 100% AD (peut critiquer)
            double[] base=fr.lolmc.util.Balance.base("q_yasuo",new double[]{20,45,70,95,120});double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_yasuo","ad",1.0);
            boolean tornado = casts>=3; // 2 stacks accumulés -> 3e cast = tornade
            if(tornado){
                // Tornade : skillshot plus long qui projette en l'air
                var hits=TargetingUtil.skillshot(c, 9.0, 1.5, true);
                for(var __t : hits){
                    TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                    __t.setVelocity(new Vector(0,1.0,0));
                    if(__t instanceof Player __p)__p.sendActionBar(Component.text("🌪 TORNADE Yasuo!",NamedTextColor.YELLOW));
                }
                qCasts.put(c.getUniqueId(),0);
                c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.2f);
            } else {
                // Estoc en ligne droite
                var hits=TargetingUtil.skillshot(c, 5.0, 1.0, true);
                for(var __t : hits) TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.1f);
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_yasuo",new double[]{20,45,70,95,120});
            return String.format("%.0f dégâts en ligne (+100%%AD). 2 stacks = tornade qui projette.",base[getLevel()-1]+s.getFinalAD());
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

    // Stacks de dégâts E (Lame Déferlante) par joueur
    public static final Map<UUID,Integer> eStacks=new HashMap<>();

    static class E extends BaseAbility {
        E(){super("e_yasuo","Lame Déferlante",Material.FEATHER,AbilitySlot.E,
            new double[]{0.5,0.5,0.5,0.5,0.5},5,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : dash à travers la cible, dégâts magiques + 25% par stack (max 4 = +100%)
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,5.0); if(tgt==null){c.sendActionBar(Component.text("🍃 Aucune cible",NamedTextColor.GRAY));return;}
            int stacks=Math.min(eStacks.getOrDefault(c.getUniqueId(),0),4);
            double[] base=fr.lolmc.util.Balance.base("e_yasuo",new double[]{60,70,80,90,100});
            double dmg=(base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_yasuo","ap",0.6))*(1.0+0.25*stacks);
            // Dash à travers/derrière la cible
            var dest=tgt.getLocation().clone().add(tgt.getLocation().getDirection().multiply(1.5));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            eStacks.merge(c.getUniqueId(),1,Integer::sum);
            // Reset stacks après 6s
            new BukkitRunnable(){@Override public void run(){eStacks.put(c.getUniqueId(),0);}}.runTaskLater(LolPlugin.getInstance(),120L);
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,dest.add(0,1,0),5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.4f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_yasuo",new double[]{60,70,80,90,100});
            return String.format("Dash à travers la cible: %.0f dégâts magiques (+25%%/stack, max +100%%).",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_yasuo","ap",0.6));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_yasuo","Dernier Souffle",Material.ELYTRA,AbilitySlot.R,
            new double[]{200,180,160},5,4,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : se TP sur un ennemi (idéalement aérien), dégâts + maintient en l'air
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("⚔ Aucune cible visée",NamedTextColor.GRAY));return;}
            // Dash sur la cible
            var dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(1.0));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            double[] base=fr.lolmc.util.Balance.base("r_yasuo",new double[]{200,350,500});
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_yasuo","ad",1.5);
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                __t.setVelocity(new Vector(0,0.8,0)); // maintient en l'air
                if(__t instanceof Player __p)
                    __p.sendActionBar(Component.text("⚔ DERNIER SOUFFLE!",NamedTextColor.DARK_GRAY));
            }
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation().add(0,1,0),10,2,2,2);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_yasuo",new double[]{200,350,500});int r=Math.min(getLevel()-1,2);
            return String.format("Dash sur la cible: %.0f dégâts (+150%%AD) + maintient en l'air.",base[r]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_yasuo","ad",1.5));
        }
    }
}