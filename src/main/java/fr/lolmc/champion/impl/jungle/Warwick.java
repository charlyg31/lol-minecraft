package fr.lolmc.champion.impl.jungle;

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
import org.bukkit.attribute.Attribute; // AJOUT : Import de l'attribut
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class Warwick extends BaseChampion {
    public Warwick() {
        super("warwick", "Warwick", ChampionRole.JUNGLE,
                new ChampionStats(620,65,0,33,32,0.638,0,335,1.25,5.0));
        getStats().setGrowthStats(99.0,3.0,4.6,2.05,0.02300,0.80);
        setAutoAttackRange(2.0);
    }
    // Passif Instinct de Chasse : révèle les ennemis sous 50% HP dans un rayon de 25 blocs
    // Bonus de vitesse vers eux, fureur au combat contre eux
    public static void tickWarwickPassive(Player ww, fr.lolmc.champion.base.BaseChampion champ) {
        var tm = LolPlugin.getInstance().getTeamManager();
        for (Player nearby : ww.getWorld().getPlayers()) {
            if (nearby.equals(ww)) continue;
            if (!tm.areEnemies(ww, nearby)) continue;
            if (ww.getLocation().distance(nearby.getLocation()) > 25) continue;
            var cm = LolPlugin.getInstance().getChampionManager();
            if (!cm.hasChampion(nearby)) continue;
            double hpRatio = cm.getChampion(nearby).getHPSystem().getHPRatio();
            if (hpRatio < 0.50) {
                // Révéler via GLOWING
                nearby.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.GLOWING, 25, 0, false, false));
                // Vitesse bonus vers la cible blessée
                if (ww.getLocation().distance(nearby.getLocation()) < 12) {
                    ww.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SPEED, 25, 1, false, false));
                }
                ww.sendActionBar(net.kyori.adventure.text.Component.text(
                    "🐺 Instinct de Chasse! " + nearby.getName() + " (" + (int)(hpRatio*100) + "% HP)",
                    net.kyori.adventure.text.format.NamedTextColor.DARK_RED));
            }
        }
    }

    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(577, 9.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("warwick",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            // Aussi vérifier l'Instinct de Chasse (passif géré dans tickWarwickPassive)

            // CORRECTION : Utilisation de l'attribut GENERIC_MAX_HEALTH pour les PV de Warwick
            var cMaxHealthAttr = c.getAttribute(fr.lolmc.util.Compat.maxHealth());
            double cMaxHealth = cMaxHealthAttr != null ? cMaxHealthAttr.getValue() : 20.0;

            // Passif Faim Eternelle : soin sur AA (triplé sous 25% PV)
            double healBase=dmg*0.1;
            if(c.getHealth()<cMaxHealth*0.25) healBase*=3;
            if(c.getHealth()<cMaxHealth*0.5){
                var cm=LolPlugin.getInstance().getChampionManager();
                if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(healBase);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_warwick","Crocs de la Bête",Material.BONE,AbilitySlot.Q,
                new double[]{6,6,6,6,6},20,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : bond + morsure, 100% AD + 6-10% PV max de la cible, soigne 100% des dégâts post-mitigation
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,3.0); if(tgt==null){c.sendActionBar(Component.text("🐺 Aucune cible",NamedTextColor.GRAY));return;}
            // Bond vers la cible
            var dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(1.0));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            double[] pct={0.06,0.07,0.08,0.09,0.10};

            // CORRECTION : Utilisation de l'attribut GENERIC_MAX_HEALTH pour les PV max de la cible
            var tgtMaxHealthAttr = tgt.getAttribute(fr.lolmc.util.Compat.maxHealth());
            double tgtMaxHealth = tgtMaxHealthAttr != null ? tgtMaxHealthAttr.getValue() : 20.0;

            double dmg=s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_warwick","ad",1.0)+tgtMaxHealth*pct[getLevel()-1];
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            // Soin 90% des dégâts (LoL)
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(dmg*0.90);
            c.getWorld().spawnParticle(Particle.HEART,c.getLocation().add(0,1,0),5,0.5,0.5,0.5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] pct={6,7,8,9,10};
            return String.format("Bond + morsure: %.0f dégâts + %.0f%% PV max cible. Soigne 100%% des dégâts.",s.getFinalAD(),pct[getLevel()-1]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_warwick","Meute de Prédateurs",Material.RED_WOOL,AbilitySlot.W,
                new double[]{0,0,0,0,0},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,60,2,false,true));
            c.sendActionBar(Component.text("🐺 Meute actif!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Passif: +50%% vitesse près d'ennemis <50%% HP. Actif: boost vitesse 3s.";}
    }

    static class E extends BaseAbility {
        E(){super("e_warwick","Hurlement Primal",Material.SPIDER_EYE,AbilitySlot.E,
                new double[]{20,18,16,14,12},5,3,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] redux={1,2,2,3,3}; // amplifier RESISTANCE approx 35-55%
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,50,(int)redux[getLevel()-1],false,true));
            c.sendActionBar(Component.text("🛡 Hurlement Primal! Réduction de dégâts",NamedTextColor.GOLD));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 1f);
            new BukkitRunnable(){
                @Override public void run(){
                    for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                        if(__t instanceof Player __p){
                            __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,5,false,true));
                            __p.sendActionBar(Component.text("😱 Peur! (Hurlement Primal)",NamedTextColor.DARK_RED));
                        }
                    }
                    c.getWorld().spawnParticle(Particle.SONIC_BOOM,c.getLocation().add(0,1,0),2,2,0.5,2);
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.5f, 0.6f);
                }
            }.runTaskLater(LolPlugin.getInstance(),30L);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Réduit les dégâts reçus (35-55%) 2.5s, puis terrifie + ralentit 90% les ennemis autour 1s.";}
    }

    static class R extends BaseAbility {
        R(){super("r_warwick","Emprise Infinie",Material.RED_WOOL,AbilitySlot.R,
                new double[]{110,90,70},25,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("🐺 Aucune cible visée",NamedTextColor.GRAY));return;}
            var dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(0.5));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            double[] base=fr.lolmc.util.Balance.base("r_warwick",new double[]{175,350,525});int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_warwick","ad",1.675);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(dmg); // soin 100% au R
            // Suppression 1.5s (30 ticks) — vrai CC verrouillant (stun)
            var cc=LolPlugin.getInstance().getCCManager();
            if(cc!=null) cc.stun(tgt, 30);
            if(tgt instanceof Player __p){
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,20,false,true));
                __p.sendActionBar(Component.text("🐺 EMPRISE INFINIE! Supprimé 1.5s!",NamedTextColor.DARK_RED));
            }
            tgt.setVelocity(new Vector(0,0.2,0));
            c.getWorld().spawnParticle(Particle.HEART,c.getLocation().add(0,1,0),10,1,1,1);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.5f, 0.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_warwick",new double[]{175,350,525});int r=Math.min(getLevel()-1,2);
            return String.format("Bond: supprime la cible 1.5s, %.0f dégâts (+167%%AD), soigne 100%%.",base[r]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_warwick","ad",1.675));
        }
    }
}