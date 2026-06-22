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

public class Annie extends BaseChampion {
    public Annie() {
        super("annie", "Annie", ChampionRole.MID,
            new ChampionStats(560,50,0,23,30,0.610,0,335,6.25,5.5));
        getStats().setGrowthStats(96.0,2.6,4.0,1.30,0.01360,0.55);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(528, 5.5, ResourceSystem.ResourceType.MANA, 528, 11.0);
        setAutoAttackRange(5.5);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("annie",Material.FIRE_CHARGE,5.5f,DamageType.MAGICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_annie","Brasier",Material.BLAZE_POWDER,AbilitySlot.Q,
            new double[]{4,3.5,3,2.5,2},20,0,DamageType.MAGICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Brasier : cible l'ennemi visé (champion, sbire ou monstre)
            var target = TargetingUtil.getTargetedEnemy(c, 6.5);
            if(target==null){ c.sendActionBar(net.kyori.adventure.text.Component.text("Aucune cible",net.kyori.adventure.text.format.NamedTextColor.GRAY)); return; }
            double[] base=fr.lolmc.util.Balance.base("q_annie",new double[]{80,130,180,230,280});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_annie","ap",0.85);
            TargetingUtil.dealDamage(c, target, dmg, TargetingUtil.DmgType.MAGICAL);
            target.getWorld().spawnParticle(Particle.FLAME,target.getLocation().add(0,1,0),20,0.5,0.5,0.5,0.1);
            target.getWorld().spawnParticle(Particle.SMALL_FLAME,target.getLocation().add(0,1,0),15,0.3,0.5,0.3,0.05);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_annie",new double[]{80,130,180,230,280});
            return String.format("%.0f dégâts magiques (%.0f+85%%AP).",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_annie","ap",0.85),base[getLevel()-1]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_annie","Incinération",Material.CAMPFIRE,AbilitySlot.W,
            new double[]{8,7,6,5,4},6,4,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Incinération : cône de feu devant Annie (touche tout)
            double[] base=fr.lolmc.util.Balance.base("w_annie",new double[]{70,115,160,205,250});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_annie","ap",0.75);
            var targets = TargetingUtil.enemiesInCone(c, 6.0, 50);
            TargetingUtil.dealDamageAll(c, targets, dmg, TargetingUtil.DmgType.MAGICAL);
            // Effet visuel : cône de flammes devant
            var dir = c.getEyeLocation().getDirection().normalize();
            for(double d=1; d<=6; d+=0.5){
                var p=c.getEyeLocation().add(dir.clone().multiply(d));
                c.getWorld().spawnParticle(Particle.FLAME,p,8,0.6,0.4,0.6,0.02);
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_annie",new double[]{70,115,160,205,250});
            return String.format("Cône de feu: %.0f dégâts (%.0f+75%%AP) dans 4 blocs.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_annie","ap",0.75),base[getLevel()-1]);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_annie","Molten Shield",Material.ORANGE_STAINED_GLASS,AbilitySlot.E,
            new double[]{10,9,8,7,6},0,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Player dest=t!=null?t:c;
            dest.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,0,false,true));
            dest.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,100,0,false,true));
            dest.sendActionBar(Component.text("🔥 Molten Shield 5s!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Bouclier 5s + immunité feu. Dégâts en retour aux attaquants.";}
    }

    static class R extends BaseAbility {
        R(){super("r_annie","Invocation de Tibbers",Material.NETHERITE_BLOCK,AbilitySlot.R,
            new double[]{100,80,60},20,3,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Tibbers : zone d'explosion là où on vise (touche tout dans le rayon)
            double[] base=fr.lolmc.util.Balance.base("r_annie",new double[]{175,300,425});
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_annie","ap",0.75);
            var ground = TargetingUtil.getAimedGroundLocation(c, 7.0);
            var targets = TargetingUtil.entitiesInRadius(c, ground, 4.0);
            for(var e: targets){
                TargetingUtil.dealDamage(c, e, dmg, TargetingUtil.DmgType.MAGICAL);
                e.setFireTicks(60);
            }
            // Explosion visuelle
            ground.getWorld().spawnParticle(Particle.LAVA,ground,40,3,1,3);
            ground.getWorld().spawnParticle(Particle.FLAME,ground,50,3,1.5,3,0.1);
            ground.getWorld().playSound(ground, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_annie",new double[]{175,300,425});
            int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts AoE + brûlure (%.0f+75%%AP).",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_annie","ap",0.75),base[r]);
        }
    }
}