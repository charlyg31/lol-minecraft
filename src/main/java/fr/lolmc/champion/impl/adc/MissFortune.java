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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class MissFortune extends BaseChampion {
    public MissFortune() {
        super("missfortune", "Miss Fortune", ChampionRole.ADC,
            new ChampionStats(640,52,0,28,30,0.656,0,325,5.5,3.8));
        getStats().setGrowthStats(103.0,3.0,4.5,1.30,0.03010,0.65);
    }
    public static void resetState(java.util.UUID id) { lastTarget.remove(id); }
    public static void resetAllState() { lastTarget.clear(); }

    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(523, 3.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
        setAutoAttackRange(5.5);
    }

    // Passif Love Tap : première AA sur une nouvelle cible inflige 50-110% AD bonus physiques
    private static final java.util.Map<java.util.UUID, java.util.UUID> lastTarget = new java.util.concurrent.ConcurrentHashMap<>();

    static class AA extends BasicAttackAbility {
        AA(){super("missfortune",Material.CROSSBOW,5.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            java.util.UUID prev = lastTarget.get(c.getUniqueId());
            if (prev == null || !prev.equals(tgt.getUniqueId())) {
                // Nouvelle cible : Love Tap (70% AD bonus physique)
                double bonusDmg = s.getFinalAD() * 0.70;
                fr.lolmc.util.TargetingUtil.dealDamage(c, tgt, bonusDmg, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
                c.getWorld().spawnParticle(org.bukkit.Particle.HEART, tgt.getLocation().add(0,1.5,0), 3, 0.3, 0.3, 0.3);
                lastTarget.put(c.getUniqueId(), tgt.getUniqueId());
            }
        }
    }
    }

    static class Q extends BaseAbility {
        Q(){super("q_missfortune","Doublé",Material.FLINT_AND_STEEL,AbilitySlot.Q,
            new double[]{7,6,5,4,3},25,0,DamageType.PHYSICAL);
            resourceCost = 43;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : tir sur 1 cible qui ricoche sur celle derrière. 1ère: 20-100+100%AD. 2ème: 20-80+85%AD +crit
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.0); if(tgt==null){c.sendActionBar(Component.text("🔫 Aucune cible",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("q_missfortune",new double[]{20,40,60,80,100});double d1=base[getLevel()-1]+s.getFinalAD()*1.0;
            TargetingUtil.dealDamage(c, tgt, d1, TargetingUtil.DmgType.PHYSICAL);
            // Ricochet : 2e cible la plus proche derrière la 1ère
            double[] base2={20,35,50,65,80};double d2=(base2[getLevel()-1]+s.getFinalAD()*0.85)*1.35;
            org.bukkit.entity.LivingEntity second=null; double best=Double.MAX_VALUE;
            for(var __t : TargetingUtil.entitiesInRadius(c, tgt.getLocation(), 5.0)){
                if(__t.equals(tgt)) continue;
                double dd=__t.getLocation().distance(tgt.getLocation());
                if(dd<best){best=dd;second=__t;}
            }
            if(second!=null){
                TargetingUtil.dealDamage(c, second, d2, TargetingUtil.DmgType.PHYSICAL);
                second.getWorld().spawnParticle(Particle.CRIT,second.getLocation().add(0,1,0),8);
            }
            tgt.getWorld().spawnParticle(Particle.CRIT,tgt.getLocation().add(0,1,0),10,0.3,0.3,0.3);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_missfortune",new double[]{20,40,60,80,100});
            return String.format("1ère balle: %.0f (+100%%AD). Ricoche sur la 2ème cible (+35%%).",base[getLevel()-1]+s.getFinalAD());
        }
    }

    static class W extends BaseAbility {
        W(){super("w_missfortune","Strut",Material.LEATHER_BOOTS,AbilitySlot.W,
            new double[]{19,17,15,13,11},0,0,DamageType.TRUE);
            resourceCost = 30;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,60,1,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,60,2,false,true));
            c.sendActionBar(Component.text("💃 Strut 3s!",NamedTextColor.LIGHT_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+20%% vitesse et +haste 3s. Passif: vitesse hors combat.";}
    }

    static class E extends BaseAbility {
        E(){super("e_missfortune","Pluie de Balles",Material.GUNPOWDER,AbilitySlot.E,
            new double[]{14.5,13,11.5,10,8.5},25,3,DamageType.MAGICAL);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : zone au sol visée, dégâts magiques toutes les 0.25s sur 2s + ralentit 40-60%
            Location loc=TargetingUtil.getAimedGroundLocation(c, 8.0);
            double[] tickBase={15,20,25,30,35};
            double perTick=tickBase[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_missfortune","ap",0.1);
            loc.getWorld().spawnParticle(Particle.ENCHANT,loc,20,3,0.2,3);
            new BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>=8){cancel();return;} // 8 ticks sur 2s
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 3.5)){
                        TargetingUtil.dealDamage(c, __t, perTick, TargetingUtil.DmgType.MAGICAL);
                        if(__t instanceof Player __p)
                            __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,10,2,false,true));
                    }
                    loc.getWorld().spawnParticle(Particle.CRIT,loc,15,3,0.2,3);
                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,5L); // toutes les 0.25s
            c.getWorld().playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] tickBase={15,20,25,30,35};
            return String.format("Zone visée: %.0f dégâts/0.25s sur 2s + ralentit (zone de contrôle).",tickBase[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_missfortune","ap",0.1));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_missfortune","Déluge de Balles",Material.NETHERITE_HOE,AbilitySlot.R,
            new double[]{120,100,80},25,8,DamageType.PHYSICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : canalise 3s, ~8 vagues de balles en CÔNE devant. Chaque vague = 75% AD + 35% AP (peut critiquer)
            c.sendActionBar(Component.text("🔫 DÉLUGE DE BALLES! 3s",NamedTextColor.RED));
            double perWave=s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_missfortune","ad",0.75)+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_missfortune","ap",0.35);
            new BukkitRunnable(){
                int waves=0;
                @Override public void run(){
                    if(waves>=8){cancel();return;} // 8 vagues sur 3s
                    // Vague en cône devant
                    var targets=TargetingUtil.enemiesInCone(c, 9.0, 40);
                    for(var __t : targets){
                        double dmg=perWave;
                        // Chaque vague peut critiquer
                        if(Math.random()<s.getFinalCritChance()) dmg*=1.3;
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                    }
                    // Animation : balles en éventail devant
                    var dir=c.getEyeLocation().getDirection().normalize();
                    for(double d=1; d<=9; d+=1){
                        c.getWorld().spawnParticle(Particle.CRIT,c.getEyeLocation().add(dir.clone().multiply(d)),3,1.5,0.3,1.5,0);
                    }
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1.5f);
                    waves++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,7L); // 8 vagues × 7 ticks ≈ 3s
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double perWave=s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_missfortune","ad",0.75)+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_missfortune","ap",0.35);
            return String.format("Canalise 3s: 8 vagues en cône, %.0f dégâts/vague (peut critiquer).",perWave);
        }
    }
