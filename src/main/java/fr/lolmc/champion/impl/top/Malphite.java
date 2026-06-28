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
    public static void resetState(java.util.UUID id)    { shieldCooldown.remove(id); thunderclapUntil.remove(id); }
    public static void resetAllState()                   { shieldCooldown.clear(); thunderclapUntil.clear(); }
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
            // Coup de Tonnerre actif : dégâts bonus en cône (+25-85 +15% AP)
            if (hasThunderclap(c.getUniqueId())) {
                int wlvl = LolPlugin.getInstance().getChampionManager().hasChampion(c)
                    ? Math.max(1, LolPlugin.getInstance().getChampionManager().getChampion(c).getAbility(2).getLevel()) : 1;
                double[] bonus={25,40,55,70,85};
                double coneDmg = bonus[Math.min(wlvl-1,4)] + s.getFinalAP()*0.15;
                for (var e : TargetingUtil.enemiesAround(c, 3.0))
                    TargetingUtil.dealDamage(c, e, coneDmg, TargetingUtil.DmgType.PHYSICAL);
                c.getWorld().spawnParticle(Particle.CRIT, tgt.getLocation().add(0,1,0), 8, 0.5,0.5,0.5);
            }
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
            // LoL : slow 30/35/40/45/50% sur 3s (60 ticks), Malphite vole la vitesse
            int qrank=getLevel()-1;
            int slowAmp=(qrank>=3)?2:1; // ~30-50% via amplifier
            if(tgt instanceof Player __p) __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,slowAmp,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,60,slowAmp,false,true)); // vol de vitesse 3s
            tgt.getWorld().spawnParticle(Particle.BLOCK,tgt.getLocation().add(0,1,0),15,0.3,0.3,0.3,
                Material.STONE.createBlockData());
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts magiques (70+60%%AP+10%%HP). Ralentit 2s.",
                70+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_malphite","ap",0.6)+s.getFinalMaxHP()*0.1);
        }
    }

    // W — Coup de Tonnerre : 6s d'AA renforcées (+dégâts en cône)
    private static final java.util.Map<java.util.UUID, Long> thunderclapUntil = new java.util.concurrent.ConcurrentHashMap<>();
    public static boolean hasThunderclap(java.util.UUID id){ Long u=thunderclapUntil.get(id); return u!=null && u>System.currentTimeMillis(); }
    static class W extends BaseAbility {
        W(){super("w_malphite","Coup de Tonnerre",Material.IRON_CHESTPLATE,AbilitySlot.W,
            new double[]{10,9,8,7,6},0,3,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Active : 6s (120 ticks) d'attaques renforcées en cône
            thunderclapUntil.put(c.getUniqueId(), System.currentTimeMillis()+6000L);
            c.sendActionBar(Component.text("⚡ Coup de Tonnerre actif (6s)!",NamedTextColor.YELLOW));
            c.getWorld().spawnParticle(Particle.EXPLOSION,c.getLocation().add(0,0.5,0),3,1,0.2,1);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] bonus={25,40,55,70,85};int r=getLevel()-1;
            return String.format("6s : tes attaques frappent en cône (+%.0f +15%%AP).",bonus[r]+s.getFinalAP()*0.15);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_malphite","Sol Fracturé",Material.CRACKED_STONE_BRICKS,AbilitySlot.E,
            new double[]{10,9,8,7,6},5,4,DamageType.MAGICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : 60/95/130/165/200 + 60% AP + 40% armure, slow AS 30-50% sur 3s
            double[] eBase={60,95,130,165,200};
            double dmg=eBase[getLevel()-1]+s.getFinalAP()*0.6+s.getFinalArmor()*0.4;
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p)
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,0,false,true)); // ralentit AS 3s
            }
            c.getWorld().spawnParticle(Particle.BLOCK,c.getLocation(),25,2,0,2,Material.STONE.createBlockData());
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] eBase={60,95,130,165,200};
            return String.format("%.0f dégâts magiques AoE (+60%%AP+40%%Armure). Slow AS 3s.",eBase[getLevel()-1]+s.getFinalAP()*0.6+s.getFinalArmor()*0.4);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_malphite","Assaut Implacable",Material.ANVIL,AbilitySlot.R,
            new double[]{130,105,80},6.5,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            Location dest=safeTeleport(c.getLocation(),tgt.getLocation());
            c.teleport(dest);
            // LoL : 200/300/400 + 90% AP, knockup + stun 1.5s (30 ticks)
            double[] baseR={200,300,400};int rr=Math.min(getLevel()-1,2);double dmg=baseR[rr]+s.getFinalAP()*0.9;
            var cc=LolPlugin.getInstance().getCCManager();
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                __t.setVelocity(new Vector(0,1.2,0));
                if(cc!=null) cc.stun(__t, 30); // stun 1.5s
                if(__t instanceof Player __p)
                    __p.sendActionBar(Component.text("🪨 KNOCKUP + STUN Malphite!",NamedTextColor.DARK_GRAY));
            }
            c.getWorld().spawnParticle(Particle.EXPLOSION,c.getLocation(),5,1,0,1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] baseR={200,300,400};int rr=Math.min(getLevel()-1,2);
            return String.format("Bondit sur la cible. %.0f dégâts (+90%%AP) + knockup/stun 1.5s.",baseR[rr]+s.getFinalAP()*0.9);
        }
    }
}