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

public class Malphite extends BaseChampion {
    public Malphite() {
        super("malphite", "Malphite", ChampionRole.TOP,
            new ChampionStats(644,62,0,37,32,0.736,0,335,1.25,7.0));
        getStats().setGrowthStats(90.0,4.0,4.2,2.05,0.02600,0.55);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(574, 8.5, ResourceSystem.ResourceType.MANA, 480, 11.0);
    }

    // Passif Granite Shield : bouclier 10% HP max, se recharge 10s hors combat
    private static final java.util.Map<java.util.UUID, Long> shieldCooldown = new java.util.concurrent.ConcurrentHashMap<>();
    public static void onDamageTaken(java.util.UUID id) { shieldCooldown.put(id, System.currentTimeMillis()); }
    public static void tickGraniteShield(org.bukkit.entity.Player p, fr.lolmc.champion.base.BaseChampion champ) {
        Long last = shieldCooldown.get(p.getUniqueId());
        if (last == null || (System.currentTimeMillis() - last) >= 10_000L) {
            double shield = champ.getStats().getFinalMaxHP() * 0.10;
            if (champ.getStats().getShield() < shield) {
                champ.getStats().clearShields(); champ.getStats().addShield(shield);
            }
        }
    }

    static class AA extends BasicAttackAbility {
        AA(){super("malphite",Material.STONE_SWORD,2.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            // Éclat de Roche : chaque AA envoie des éclats (10% AP) sur les ennemis proches
            double splash = s.getFinalAP() * 0.10;
            if (splash > 0)
                for (var e : TargetingUtil.enemiesAround(c, 2.5))
                    if (!e.equals(tgt)) TargetingUtil.dealDamage(c, e, splash, TargetingUtil.DmgType.MAGICAL);
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_malphite","Éclat de Pierre",Material.COBBLESTONE,AbilitySlot.Q,
            new double[]{8,7.5,7,6.5,6},20,0,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null){c.sendActionBar(Component.text("🪨 Aucune cible",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("q_malphite",new double[]{70,120,170,220,270});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_malphite","ap",0.6);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            // LoL : ralentit la cible ET donne un boost de vitesse à Malphite
            if(tgt instanceof Player __p) __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,1,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,60,0,false,true));
            tgt.getWorld().spawnParticle(Particle.BLOCK,tgt.getLocation().add(0,1,0),15,0.3,0.3,0.3,
                Material.STONE.createBlockData());
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts magiques (70+60%%AP+10%%HP). Ralentit 2s.",
                70+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_malphite","ap",0.6)+s.getFinalMaxHP()*0.1);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_malphite","Frappe Sismique",Material.IRON_CHESTPLATE,AbilitySlot.W,
            new double[]{12,11,10,9,8},5,3,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            for(var __t : TargetingUtil.enemiesAround(c, 3.0)){
                if(__t instanceof Player __p){
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,1,false,true));
                    __p.sendActionBar(Component.text("💥 Frappe Sismique!",NamedTextColor.GRAY));
                }
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Réduit la vitesse d'attaque des ennemis proches de 30%% pendant 3s.";}
    }

    static class E extends BaseAbility {
        E(){super("e_malphite","Sol Fracturé",Material.CRACKED_STONE_BRICKS,AbilitySlot.E,
            new double[]{10,9,8,7,6},5,4,DamageType.MAGICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double dmg=60+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_malphite","ap",0.3)+s.getFinalArmor()*0.2;
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p)
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,1,false,true));
            }
            c.getWorld().spawnParticle(Particle.BLOCK,c.getLocation(),25,2,0,2,Material.STONE.createBlockData());
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts magiques AoE (60+30%%AP+20%%Armure).",60+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_malphite","ap",0.3)+s.getFinalArmor()*0.2);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_malphite","Assaut Implacable",Material.ANVIL,AbilitySlot.R,
            new double[]{130,105,80},20,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            Location dest=safeTeleport(c.getLocation(),tgt.getLocation());
            c.teleport(dest);
            double[] baseR={200,300,400};int rr=Math.min(getLevel()-1,2);double dmg=baseR[rr]+s.getFinalAP()*1.0;
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                __t.setVelocity(new Vector(0,1.2,0));
                if(__t instanceof Player __p)
                    __p.sendActionBar(Component.text("🪨 KNOCKUP Malphite!",NamedTextColor.DARK_GRAY));
            }
            c.getWorld().spawnParticle(Particle.EXPLOSION,c.getLocation(),5,1,0,1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Bondit sur la cible. %.0f dégâts + knockup 1.5s dans 4 blocs.",200+s.getFinalAP()*0.7+s.getFinalArmor()*0.4);
        }
    }
}