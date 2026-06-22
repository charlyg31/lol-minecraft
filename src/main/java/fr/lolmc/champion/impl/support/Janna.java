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

public class Janna extends BaseChampion {
    public Janna() {
        super("janna", "Janna", ChampionRole.SUPPORT,
            new ChampionStats(570,49,0,28,30,0.625,0,325,5.5,11.5));
        getStats().setGrowthStats(90.0,3.0,4.4,1.30,0.02610,0.50);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(476, 7.0, ResourceSystem.ResourceType.MANA, 480, 11.0);
        setAutoAttackRange(5.5);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_janna","Attaque de base",Material.FEATHER,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,5.5); if(tgt==null)return;
            TargetingUtil.dealDamage(c, tgt, s.getFinalAD(), TargetingUtil.DmgType.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_janna","Bourrasque Hurlante",Material.WHITE_DYE,AbilitySlot.Q,
            new double[]{12,11,10,9,8},20,2,DamageType.MAGICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot tornade en ligne, 60/90/120/150/180 + 50% AP, knockup les ennemis traversés
            double[] base={60,90,120,150,180};double dmg=base[getLevel()-1]+s.getFinalAP()*0.5;
            var hits=TargetingUtil.skillshot(c, 10.0, 1.2, true); // traverse
            if(hits.isEmpty()){c.sendActionBar(Component.text("🌪 Tornade lancée!",NamedTextColor.WHITE));return;}
            for(var __t : hits){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                __t.setVelocity(new Vector(0,0.9,0)); // knockup
                if(__t instanceof Player __p)__p.sendActionBar(Component.text("🌪 Bourrasque! Knockup",NamedTextColor.WHITE));
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={60,90,120,150,180};
            return String.format("Skillshot tornade: %.0f dégâts (+50%%AP) + knockup les ennemis.",base[getLevel()-1]+s.getFinalAP()*0.5);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_janna","Zéphyr",Material.CYAN_DYE,AbilitySlot.W,
            new double[]{8,7,6,5,4},20,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : envoie un élémentaire qui inflige des dégâts + ralentit. 60/105/150/195/240 + 60% AP
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,7.0); if(tgt==null){c.sendActionBar(Component.text("💨 Aucune cible",NamedTextColor.GRAY));return;}
            double[] base={60,105,150,195,240};double dmg=base[getLevel()-1]+s.getFinalAP()*0.6;
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            if(tgt instanceof Player __p)
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,2,false,true));
            // Janna gagne aussi de la vitesse (passif Zéphyr)
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,1,false,true));
            tgt.getWorld().spawnParticle(Particle.CLOUD,tgt.getLocation().add(0,1,0),12,0.5,0.5,0.5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 1f, 1.4f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={60,105,150,195,240};
            return String.format("%.0f dégâts (+60%%AP) + ralentit. Janna gagne de la vitesse.",base[getLevel()-1]+s.getFinalAP()*0.6);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_janna","Oeil de la Tempête",Material.LIGHT_BLUE_DYE,AbilitySlot.E,
            new double[]{10,9,8,7,6},0,0,DamageType.TRUE);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : bouclier sur allié (ou soi) 75-175 + 50% AP + AD bonus 5s
            Player dest = (t!=null) ? t : c;
            double[] shieldBase={75,100,125,150,175};
            double shield=shieldBase[getLevel()-1]+s.getFinalAP()*0.5;
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(dest)) cm.getChampion(dest).getStats().addShield(shield);
            dest.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,1,false,true));
            dest.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,100,0,false,true)); // AD bonus
            dest.getWorld().spawnParticle(Particle.ENCHANTED_HIT,dest.getLocation().add(0,1,0),15,0.5,1,0.5);
            dest.sendActionBar(Component.text("🌀 Œil de la Tempête! Bouclier "+(int)shield,NamedTextColor.AQUA));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] shieldBase={75,100,125,150,175};
            return String.format("Bouclier %.0f (+50%%AP) + AD bonus sur soi ou un allié 5s.",shieldBase[getLevel()-1]+s.getFinalAP()*0.5);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_janna","Mousson",Material.HEART_OF_THE_SEA,AbilitySlot.R,
            new double[]{150,120,90},20,4,DamageType.TRUE);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : repousse les ennemis autour, puis canalise 3s en soignant les alliés (100/150/200/0.5s)
            for(var __t : TargetingUtil.enemiesAround(c, 5.0)){
                Vector kb=__t.getLocation().toVector().subtract(c.getLocation().toVector()).normalize().multiply(3.0);
                kb.setY(0.5); __t.setVelocity(kb);
                if(__t instanceof Player __p)__p.sendActionBar(Component.text("🌪 MOUSSON! Repoussé",NamedTextColor.WHITE));
            }
            // Soin sur 3s (canalisation)
            double[] healBase={100,150,200};int r=Math.min(getLevel()-1,2);
            double healPerTick=(healBase[r]+s.getFinalAP()*0.6)/2.0; // par 0.5s
            var cm=LolPlugin.getInstance().getChampionManager();
            new BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>=6){cancel();return;} // 6 ticks de 0.5s = 3s
                    // Soigne Janna et les alliés proches
                    if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(healPerTick);
                    for(var ent : c.getWorld().getNearbyLivingEntities(c.getLocation(),5)){
                        if(ent instanceof Player ally && !ally.equals(c) && cm.hasChampion(ally))
                            cm.getChampion(ally).getHPSystem().heal(healPerTick);
                    }
                    c.getWorld().spawnParticle(Particle.HEART,c.getLocation().add(0,1,0),5,2,0.5,2);
                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,10L);
            c.sendActionBar(Component.text("🌪 MOUSSON! Soin de zone",NamedTextColor.WHITE));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] healBase={100,150,200};int r=Math.min(getLevel()-1,2);
            return String.format("Repousse les ennemis + soigne les alliés %.0f/s sur 3s.",healBase[r]+s.getFinalAP()*0.6);
        }
    }
}