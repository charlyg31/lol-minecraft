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
        setAutoAttackRange(5.5);
    }

    private static final java.util.Map<java.util.UUID, Boolean> ricochetActive = new java.util.concurrent.ConcurrentHashMap<>();
    public static void setRicochet(java.util.UUID id, boolean v) { ricochetActive.put(id, v); }

    static class AA extends BasicAttackAbility {
        AA(){super("sivir",Material.CROSSBOW,5.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            if (!ricochetActive.getOrDefault(c.getUniqueId(), false)) return;
            // Ricochet W : AA rebondit sur jusqu'à 6 cibles proches (60% dégâts)
            int bounces = 0;
            for (var e : fr.lolmc.util.TargetingUtil.enemiesAround(c, 5.0)) {
                if (e.equals(tgt) || bounces >= 6) continue;
                fr.lolmc.util.TargetingUtil.dealDamage(c, e, dmg * 0.60, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
                e.getWorld().spawnParticle(org.bukkit.Particle.CRIT, e.getLocation().add(0,1,0), 3, 0.2,0.2,0.2);
                bounces++;
            }
        }
    }
    }

    static class Q extends BaseAbility {
        Q(){super("q_sivir","Lame Boomerang",Material.IRON_AXE,AbilitySlot.Q,
            new double[]{9,8,7,6,5},25,0,DamageType.PHYSICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double[] base=fr.lolmc.util.Balance.base("q_sivir",new double[]{25,45,65,85,105});double dmg=base[getLevel()-1]+s.getFinalAD()*1.0;
            DamageUtil.abilityDamageEntity(c, tgt, dmg);
            // Retour: dégâts réduits
            new BukkitRunnable(){@Override public void run(){
                if(!(!tgt.isDead()))return;
                DamageUtil.abilityDamageEntity(c, tgt, dmg*0.7);
                c.getWorld().spawnParticle(Particle.CRIT,tgt.getLocation(),5);
            }}.runTaskLater(LolPlugin.getInstance(),15L);
            c.getWorld().spawnParticle(Particle.CRIT,tgt.getLocation(),10);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double d=50+s.getFinalAD()*0.5;
            return String.format("Aller: %.0f dégâts. Retour: %.0f dégâts.",d,d*0.7);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_sivir","Ricochets",Material.MUSIC_DISC_13,AbilitySlot.W,
            new double[]{10,9,8,7,6},0,0,DamageType.PHYSICAL);
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
            new double[]{120,100,80},0,0,DamageType.TRUE);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,2,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,80,2,false,true));
            c.sendActionBar(Component.text("⚡ Appel des Flèches 4s!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+30%% vitesse et haste pendant 4s.";}
    }
}