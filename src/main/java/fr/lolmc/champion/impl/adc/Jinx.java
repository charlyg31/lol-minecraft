package fr.lolmc.champion.impl.adc;

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

public class Jinx extends BaseChampion {
    public Jinx() {
        super("jinx", "Jinx", ChampionRole.ADC,
                new ChampionStats(630,59,0,26,30,0.625,0,325,5.25,3.8));
        getStats().setGrowthStats(100.0,3.4,4.7,1.30,0.01360,0.50);
    }
    // Passif Get Excited : kill/assist → +175% vitesse 6s (décroît après 3s)
    public static void getExcited(Player jinx) {
        jinx.addPotionEffect(new org.bukkit.potion.PotionEffect(
            org.bukkit.potion.PotionEffectType.SPEED, 120, 4, false, true)); // 6s vitesse
        jinx.getWorld().spawnParticle(org.bukkit.Particle.FIREWORK,
            jinx.getLocation().add(0,1,0), 15, 0.5,0.5,0.5);
        jinx.sendActionBar(net.kyori.adventure.text.Component.text(
            "🎉 GET EXCITED! +175% vitesse 6s!", net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    public static void resetState(java.util.UUID id) { fishbonesMode.remove(id); }
    public static void resetAllState()               { fishbonesMode.clear(); }
    private static final java.util.Map<java.util.UUID, Boolean> fishbonesMode
        = new java.util.concurrent.ConcurrentHashMap<>();

    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(516, 3.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
        setAutoAttackRange(6.0);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("jinx",Material.CROSSBOW,6.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            // Mode Fishbones : AoE autour de la cible principale (107% AD total réparti)
            if (fishbonesMode.getOrDefault(c.getUniqueId(), false)) {
                var tm = LolPlugin.getInstance().getTeamManager();
                for (var nearby : fr.lolmc.util.TargetingUtil.enemiesAround(c, 2.5)) {
                    if (nearby.equals(tgt)) continue; // cible principale déjà touchée
                    fr.lolmc.util.TargetingUtil.dealDamage(c, nearby,
                        dmg * 0.87, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
                }
                tgt.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION,
                    tgt.getLocation().add(0,0.5,0), 3, 0.5,0.2,0.5);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_jinx","Choix des Armes",Material.TNT,AbilitySlot.Q,
                new double[]{0,0,0,0,0},25,3,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            boolean fish = !fishbonesMode.getOrDefault(c.getUniqueId(), false);
            fishbonesMode.put(c.getUniqueId(), fish);
            if (fish) {
                // Mode Fishbones (bazooka) : portée étendue +2 blocs, dégâts AoE
                c.sendActionBar(Component.text("💥 Fishbones! Bazooka activé (+portée, AoE)", NamedTextColor.RED));
                // Augmenter la portée AA
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(c)) cm.getChampion(c).setAutoAttackRange(cm.getChampion(c).getAutoAttackRange() + 2);
            } else {
                // Mode Mini-canons : vitesse d'attaque
                c.sendActionBar(Component.text("⚡ Mini-canons! Vitesse d'attaque", NamedTextColor.YELLOW));
                c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 40, 1, false, true));
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(c)) cm.getChampion(c).setAutoAttackRange(6.0);
            }
            c.getWorld().playSound(c.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.5f, fish ? 0.7f : 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Toggle Fishbones/Mini-canons. Fishbones: +portée +AoE. Mini-canons: +AS.";}
    }

    static class W extends BaseAbility {
        W(){super("w_jinx","Zap!",Material.LIGHTNING_ROD,AbilitySlot.W,
                new double[]{8,7,6,5,4},25,0,DamageType.PHYSICAL);
            resourceCost = 20;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("w_jinx",new double[]{10,60,110,160,210});double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("w_jinx","ad",1.4);
            var hits=TargetingUtil.skillshot(c, 14.0, 0.8, false);
            if(hits.isEmpty()){c.sendActionBar(Component.text("⚡ Zap manqué!",NamedTextColor.GRAY));return;}
            var main=hits.get(0);
            TargetingUtil.dealDamage(c, main, dmg, TargetingUtil.DmgType.PHYSICAL);
            // Slow 40/50/60/70/80% selon rang (2s = 40 ticks)
            if(main instanceof Player __p){
                int slowAmp=Math.min(4, 1+getLevel()/2);
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,slowAmp,false,true));
            }
            main.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,main.getLocation().add(0,1,0),15,0.5,0.5,0.5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1.4f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_jinx",new double[]{10,60,110,160,210});
            return String.format("Skillshot: %.0f dégâts (+140%%AD) au 1er touché + slow 40-80%%.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("w_jinx","ad",1.4));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_jinx","Croque-Flammes!",Material.TRIPWIRE_HOOK,AbilitySlot.E,
                new double[]{24,20.5,17,13.5,10},25,2,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Location loc=TargetingUtil.getAimedGroundLocation(c, 9.0);
            double[] base=fr.lolmc.util.Balance.base("e_jinx",new double[]{90,140,190,240,290});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_jinx","ap",1.0);
            for(var __t : TargetingUtil.entitiesInRadius(c, loc, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p){
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,35,10,false,true));
                    __p.sendActionBar(Component.text("🦷 ENRACINÉ par les Croque-Flammes!",NamedTextColor.RED));
                }
            }
            loc.getWorld().spawnParticle(Particle.FLAME,loc,30,3,0.3,3,0.02);
            loc.getWorld().spawnParticle(Particle.LAVA,loc,10,3,0.2,3);
            c.getWorld().playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_jinx",new double[]{90,140,190,240,290});
            return String.format("Pièges (zone visée): %.0f dégâts magiques + enracine 1.5s.",base[getLevel()-1]+s.getFinalAP());
        }
    }

    static class R extends BaseAbility {
        R(){super("r_jinx","Super Méga Roquette de la Mort!",Material.FIREWORK_ROCKET,AbilitySlot.R,
                new double[]{75,65,55},25,5,DamageType.PHYSICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            var hits=TargetingUtil.skillshot(c, 30.0, 1.2, false);
            if(hits.isEmpty()){c.sendActionBar(Component.text("🚀 Roquette tirée (aucune cible)",NamedTextColor.GRAY));return;}
            var main=hits.get(0);
            double dist=c.getLocation().distance(main.getLocation());
            double distMult=Math.min(1.0, 0.1+dist*0.06);
            double[] baseMax={250,400,550};int rr=Math.min(getLevel()-1,2);
            double[] missPct={0.25,0.30,0.35};
            double baseDmg=baseMax[rr]*distMult+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_jinx","ad",1.3)*distMult;

            for(var __t : TargetingUtil.entitiesInRadius(c, main.getLocation(), 5.0)){
                // CORRECTION : Utilisation de l'attribut GENERIC_MAX_HEALTH au lieu de getMaxHealth() déprécié
                var maxHealthAttr = __t.getAttribute(fr.lolmc.util.Compat.maxHealth());
                double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;

                double missingHP=maxHealth-__t.getHealth();
                double dmg=baseDmg+missingHP*missPct[rr];
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
            }
            main.getWorld().spawnParticle(Particle.EXPLOSION,main.getLocation(),8,2,1,2);
            main.getWorld().spawnParticle(Particle.FLAME,main.getLocation(),50,3,2,3,0.1);
            main.getWorld().playSound(main.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
            if(main instanceof Player _tp)_tp.sendMessage(Component.text("🚀 SUPER MÉGA ROQUETTE DE LA MORT!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_jinx",new double[]{250,400,550});int r=Math.min(getLevel()-1,2);
            return String.format("Roquette globale: jusqu'à %.0f dégâts (+130%%AD) selon distance + %% PV manquants. Explose en zone.",base[r]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_jinx","ad",1.3));
        }
    }
}