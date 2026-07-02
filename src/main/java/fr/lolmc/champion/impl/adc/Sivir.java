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

public class Sivir extends BaseChampion {
    public Sivir() {
        super("sivir", "Sivir", ChampionRole.ADC,
            new ChampionStats(600,58,0,26,30,0.625,0,335,5.0,3.2));
        getStats().setGrowthStats(104.0,3.3,4.5,1.30,0.02000,0.55);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(532, 3.0, ResourceSystem.ResourceType.MANA, 450, 11.0);
        setAutoAttackRange(7.7);
    }

    private static final java.util.Map<java.util.UUID, Boolean> ricochetActive = new java.util.concurrent.ConcurrentHashMap<>();
    public static void resetState(java.util.UUID id) { ricochetActive.remove(id); }
    public static void resetAllState()               { ricochetActive.clear(); }
    public static void setRicochet(java.util.UUID id, boolean v) { ricochetActive.put(id, v); }

    static class AA extends BasicAttackAbility {
        AA(){super("sivir",Material.CROSSBOW,5.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            if (!ricochetActive.getOrDefault(c.getUniqueId(), false)) return;
            // Ricochet W : AA rebondit sur jusqu'à 6 cibles proches (60% dégâts)
            int bounces = 0;
            for (var e : fr.lolmc.util.TargetingUtil.enemiesAround(c, 5.0)) {
                if (e.equals(tgt) || bounces >= 8) continue; // jusqu'à 8 rebonds (LoL)
                fr.lolmc.util.TargetingUtil.dealDamage(c, e, dmg * 0.60, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
                e.getWorld().spawnParticle(org.bukkit.Particle.CRIT, e.getLocation().add(0,1,0), 3, 0.2,0.2,0.2);
                bounces++;
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_sivir","Lame Boomerang",Material.IRON_AXE,AbilitySlot.Q,
            new double[]{14,13,12,11,10},25,0,DamageType.PHYSICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double[] base=fr.lolmc.util.Balance.base("q_sivir",new double[]{60,105,150,195,240});double dmg=base[getLevel()-1]+s.getFinalAD()*1.0;
            DamageUtil.abilityDamageEntity(c, tgt, dmg);
            // Retour: dégâts pleins (plus de falloff en LoL actuel)
            new BukkitRunnable(){@Override public void run(){
                if(tgt.isDead())return;
                DamageUtil.abilityDamageEntity(c, tgt, dmg);
                c.getWorld().spawnParticle(Particle.CRIT,tgt.getLocation(),5);
            }}.runTaskLater(LolPlugin.getInstance(),15L);
            c.getWorld().spawnParticle(Particle.CRIT,tgt.getLocation(),10);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={60,105,150,195,240};double d=base[getLevel()-1]+s.getFinalAD();
            return String.format("Aller: %.0f dégâts. Retour: %.0f dégâts (boomerang).",d,d);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_sivir","Ricochets",Material.MUSIC_DISC_13,AbilitySlot.W,
            new double[]{7,7,7,7,7},0,0,DamageType.PHYSICAL);
            resourceCost = 22;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // W Ricochet : active le rebond des AA pendant 6s
            setRicochet(c.getUniqueId(), true);
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,120,1,false,true));
            c.sendActionBar(Component.text("🎯 Ricochet actif 6s!",NamedTextColor.YELLOW));
            new BukkitRunnable(){@Override public void run(){ setRicochet(c.getUniqueId(), false); }}.runTaskLater(LolPlugin.getInstance(),120L);
                }
        @Override public String getDynamicDescription(ChampionStats s){return "6s: AA rebondissent sur 7 cibles proches.";}
    }

    static class E extends BaseAbility {
        E(){super("e_sivir","Bouclier Maléfique",Material.SHIELD,AbilitySlot.E,
            new double[]{22,20,18,16,14},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,40,3,false,true));
            c.sendActionBar(Component.text("🛡 Bouclier Maléfique!",NamedTextColor.GREEN));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Bloque un sort ennemi. Annule le CC.";}
    }

    static class R extends BaseAbility {
        R(){super("r_sivir","Appel des Flèches",Material.GOLDEN_AXE,AbilitySlot.R,
            new double[]{120,90,60},0,0,DamageType.TRUE);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,2,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,80,2,false,true));
            // Aura d'équipe : +vitesse aux alliés proches (LoL "On The Hunt")
            var tm0=LolPlugin.getInstance().getTeamManager();
            for(var ally : c.getWorld().getPlayers()){
                if(ally.equals(c)) continue;
                if(tm0!=null && !tm0.areEnemies(c, ally) && ally.getLocation().distance(c.getLocation())<=10){
                    ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,1,false,true));
                }
            }
            c.sendActionBar(Component.text("⚡ Appel des Flèches 4s! Alliés boostés, AA reset Surrégime",NamedTextColor.GOLD));
            c.getWorld().playSound(c.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
            // Passif Volonté de Bataille : chaque ennemi touché par une AA pendant R
            // reset le CD de Surrégime (W)
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() {
                    // Vérifier si Surrégime est en CD et le reset
                    var cm = LolPlugin.getInstance().getChampionManager();
                    if (!cm.hasChampion(c)) { cancel(); return; }
                    var w = cm.getChampion(c).getAbility(2);
                    if (w != null && w.getRemainingCooldown(c) > 0) {
                        w.setDynamicCooldown(0.001); w.triggerCooldown(c);
                        new org.bukkit.scheduler.BukkitRunnable() {
                            @Override public void run() { w.setDynamicCooldown(-1); }
                        }.runTaskLater(LolPlugin.getInstance(), 1L);
                        c.sendActionBar(Component.text("⚡ Volonté de Bataille — Surrégime reset!", NamedTextColor.GOLD));
                        cancel();
                    }
                }
            }.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+30%% vitesse et haste 4s. Touch = reset Surrégime (Volonté de Bataille)";}
    }
}
