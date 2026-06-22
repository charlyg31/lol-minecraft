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

    static class AA extends BasicAttackAbility {
        AA(){super("darius",Material.IRON_AXE,2.5f,DamageType.PHYSICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_darius","Décimation",Material.NETHERITE_AXE,AbilitySlot.Q,
            new double[]{9,8,7,6,5},5,5,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : bord 14/24.5/35/45.5/56 + 35-49% AD (plein), centre (manche) = 35% des dégâts
            double[] base={56,98,140,182,224}; // valeur au bord (x4 du tick pour gameplay MC)
            double[] adR={0.35,0.385,0.42,0.455,0.49};
            int rank=getLevel()-1;
            double edgeDmg=base[rank]+s.getFinalAD()*adR[rank];
            int hitChamps=0;
            // Délai de 0.75s (canalisation) puis frappe
            c.sendActionBar(Component.text("🪓 Décimation...",NamedTextColor.DARK_RED));
            for(var __t : TargetingUtil.enemiesAround(c, 4.5)){
                double dist=__t.getLocation().distance(c.getLocation());
                // Centre (< 2 blocs) = manche = 35%, bord = plein
                double dmg = dist<2.0 ? edgeDmg*0.35 : edgeDmg;
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if(__t instanceof Player) hitChamps++;
            }
            // Soin : 15% PV manquants par champion touché au bord (max 45%)
            if(hitChamps>0){
                var cm=LolPlugin.getInstance().getChampionManager();
                if(cm.hasChampion(c)){
                    var hp=cm.getChampion(c).getHPSystem();
                    double missing=hp.getMaxHP()-hp.getCurrentHP();
                    double healPct=Math.min(0.45, 0.15*hitChamps);
                    hp.heal(missing*healPct);
                }
            }
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation().add(0,1,0),8,2.5,0.5,2.5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={56,98,140,182,224};double[] adR={0.35,0.385,0.42,0.455,0.49};int r=getLevel()-1;
            return String.format("Bord: %.0f dégâts. Centre: 35%%. Soigne 15%%/champion touché.",base[r]+s.getFinalAD()*adR[r]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_darius","Frappe Estropiante",Material.GOLDEN_AXE,AbilitySlot.W,
            new double[]{9,8,7,6,5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : prochaine attaque renforcée, dégâts bonus + ralentit 90% 1s
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){c.sendActionBar(Component.text("🪓 Aucune cible",NamedTextColor.GRAY));return;}
            // Dégâts = 40-120% AD selon rang (renforce l'attaque de base)
            double[] adMult={0.4,0.6,0.8,1.0,1.2};
            double dmg=s.getFinalAD()*(1.0+adMult[getLevel()-1]); // AA + bonus
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            // Ralentit 90% pendant 1s
            if(tgt instanceof Player __p){
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,4,false,true));
                __p.sendActionBar(Component.text("🪓 Estropié! (ralenti)",NamedTextColor.DARK_RED));
            }
            c.getWorld().spawnParticle(Particle.CRIT,tgt.getLocation().add(0,1,0),12);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] adMult={0.4,0.6,0.8,1.0,1.2};
            return String.format("Attaque renforcée: %.0f dégâts + ralentit 90%% 1s.",s.getFinalAD()*(1.0+adMult[getLevel()-1]));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_darius","Appréhension",Material.TRIPWIRE_HOOK,AbilitySlot.E,
            new double[]{24,21,18,15,12},5,5,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : cône devant, tire les ennemis vers Darius + ralentit 40% 1s
            var targets=TargetingUtil.enemiesInCone(c, 5.5, 60);
            if(targets.isEmpty()){c.sendActionBar(Component.text("🪝 Personne à attraper",NamedTextColor.GRAY));return;}
            for(var __t : targets){
                Vector pull=c.getLocation().toVector().subtract(__t.getLocation().toVector()).normalize().multiply(1.2);
                pull.setY(0.3); __t.setVelocity(pull);
                if(__t instanceof Player __p){
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,1,false,true)); // 40% ~ amplifier 1
                    __p.sendActionBar(Component.text("🪝 Appréhension!",NamedTextColor.DARK_RED));
                }
            }
            // Animation : ligne de cône devant
            var dir=c.getEyeLocation().getDirection().normalize();
            for(double d=1; d<=5.5; d+=0.5){
                c.getWorld().spawnParticle(Particle.CRIT,c.getEyeLocation().add(dir.clone().multiply(d)),3,0.4,0.4,0.4,0);
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Tire les ennemis dans un cône devant (5.5 blocs) + ralentit 40% 1s.";}
    }

    static class R extends BaseAbility {
        R(){super("r_darius","Guillotine Noxienne",Material.NETHERITE_AXE,AbilitySlot.R,
            new double[]{120,100,80},5,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : exécution, bond sur la cible, dégâts vrais 100/200/300 + 75% AD bonus
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("☠ Aucune cible visée",NamedTextColor.GRAY));return;}
            double[] base={100,200,300};
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAD()*0.75;
            // Bond vers la cible (téléportation proche)
            var dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(1.5));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            tgt.getWorld().strikeLightningEffect(tgt.getLocation());
            tgt.getWorld().spawnParticle(Particle.FLASH,tgt.getLocation().add(0,1,0),2);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.TRUE);
            if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ GUILLOTINE NOXIENNE!",NamedTextColor.DARK_RED));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={100,200,300};int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts vrais (+75%%AD). Exécution, reset si kill.",base[r]+s.getFinalAD()*0.75);
        }
    }
}