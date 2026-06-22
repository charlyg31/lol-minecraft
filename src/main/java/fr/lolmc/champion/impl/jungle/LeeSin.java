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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class LeeSin extends BaseChampion {
    public LeeSin() {
        super("leesin", "Lee Sin", ChampionRole.JUNGLE,
            new ChampionStats(645,68,0,36,32,0.651,0,345,1.25,7.5));
        getStats().setGrowthStats(109.0,3.7,4.5,2.05,0.03000,0.70);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(570, 8.0, ResourceSystem.ResourceType.ENERGY, 200, 50.0);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("leesin",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
    }

    // Cible marquée par l'Onde Sonique (pour la Frappe Résonnante au recast)
    private static final Map<UUID,UUID> sonicTarget=new HashMap<>();
    public static void resetState(UUID id){ sonicTarget.remove(id); }
    public static void resetAllState(){ sonicTarget.clear(); }

    static class Q extends BaseAbility {
        Q(){super("q_leesin","Onde Sonique",Material.ECHO_SHARD,AbilitySlot.Q,
            new double[]{9,8,7,6,5},15,0,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // 1er cast = Onde Sonique (skillshot). 2e cast (cible déjà marquée) = Frappe Résonnante (dash + PV manquants)
            UUID marked=sonicTarget.get(c.getUniqueId());
            if(marked!=null){
                // Frappe Résonnante : dash sur la cible marquée + dégâts selon PV manquants
                org.bukkit.entity.LivingEntity tgt=null;
                for(var e: c.getWorld().getNearbyLivingEntities(c.getLocation(),15)) if(e.getUniqueId().equals(marked)) tgt=e;
                if(tgt!=null){
                    var dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(1.0));
                    dest.setY(c.getLocation().getY());
                    c.teleport(dest);
                    double[] base=fr.lolmc.util.Balance.base("q_leesin",new double[]{60,90,120,150,180});
                    double missingPct=1.0-(tgt.getHealth()/tgt.getMaxHealth());
                    double dmg=(base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_leesin","ad",0.9))*(1.0+missingPct); // +PV manquants
                    TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.9f);
                }
                sonicTarget.remove(c.getUniqueId());
                return;
            }
            // Onde Sonique : skillshot ligne
            double[] base=fr.lolmc.util.Balance.base("q_leesin",new double[]{60,90,120,150,180});double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_leesin","ad",0.9);
            var hits=TargetingUtil.skillshot(c, 12.0, 0.8, false);
            if(hits.isEmpty()){c.sendActionBar(Component.text("🌊 Onde Sonique manquée!",NamedTextColor.GRAY));return;}
            var tgt=hits.get(0);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            sonicTarget.put(c.getUniqueId(), tgt.getUniqueId());
            // La marque expire après 3s
            new BukkitRunnable(){@Override public void run(){sonicTarget.remove(c.getUniqueId());}}.runTaskLater(LolPlugin.getInstance(),60L);
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🌊 Onde Sonique! (recast pour dash)",NamedTextColor.YELLOW));
            c.getWorld().spawnParticle(Particle.SONIC_BOOM,tgt.getLocation().add(0,1,0),1);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_leesin",new double[]{60,90,120,150,180});
            return String.format("Skillshot: %.0f dégâts (+90%%AD). Recast = dash + bonus PV manquants.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_leesin","ad",0.9));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_leesin","Protection",Material.SHIELD,AbilitySlot.W,
            new double[]{14,13,12,11,10},15,0,DamageType.TRUE);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Protection : dash vers un allié (ou soi), bouclier 60-240 + 80% AP. Recast = omnivamp
            Player dest = (t!=null) ? t : c;
            dest.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,40,1,false,true));
            if(t!=null && !t.equals(c)){
                Location safe=safeTeleport(c.getLocation(),t.getLocation());
                c.teleport(safe);
            }
            // Lee gagne aussi un bouclier
            c.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,40,1,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,80,1,false,true)); // omnivamp approx
            c.sendActionBar(Component.text("🛡 Protection / Volonté de Fer!",NamedTextColor.GREEN));
            c.getWorld().playSound(c.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Dash vers un allié + bouclier (les deux). Recast: omnivamp.";}
    }

    static class E extends BaseAbility {
        E(){super("e_leesin","Tempête de Flammes",Material.FIRE_CHARGE,AbilitySlot.E,
            new double[]{10,9,8,7,6},5,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Tempête : dégâts magiques autour 35-135 + 90% AD, puis ralentit (Boiterie)
            double[] base=fr.lolmc.util.Balance.base("e_leesin",new double[]{35,60,85,110,135});double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("e_leesin","ad",0.9);
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p)
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,80,2,false,true)); // Boiterie 4s
            }
            c.getWorld().spawnParticle(Particle.SONIC_BOOM,c.getLocation().add(0,1,0),3,2,0.5,2);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_leesin",new double[]{35,60,85,110,135});
            return String.format("%.0f dégâts magiques autour (+90%%AD) + ralentit (Boiterie).",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("e_leesin","ad",0.9));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_leesin","Dragon's Rage",Material.DRAGON_EGG,AbilitySlot.R,
            new double[]{90,75,60},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Furie du Dragon : kick une cible, 150/300/450 + 200% AD bonus, la projette en arrière
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,4.0); if(tgt==null){c.sendActionBar(Component.text("🐉 Aucune cible visée",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("r_leesin",new double[]{150,300,450});
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_leesin","ad",2.0);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            // Direction du kick = de Lee vers la cible
            Vector dir=tgt.getLocation().toVector().subtract(c.getLocation().toVector()).normalize();
            Vector kb=dir.clone().multiply(2.5); kb.setY(0.8); tgt.setVelocity(kb);
            // Ennemis percutés sur la trajectoire prennent les mêmes dégâts + knockup
            Location landing=tgt.getLocation().clone().add(dir.clone().multiply(4));
            for(var __t : TargetingUtil.entitiesInRadius(c, landing, 3.0)){
                if(__t.equals(tgt)) continue;
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                __t.setVelocity(new Vector(0,1.0,0)); // knockup
            }
            if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("🐉 FURIE DU DRAGON!",NamedTextColor.RED));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_leesin",new double[]{150,300,450});int r=Math.min(getLevel()-1,2);
            return String.format("Kick: %.0f dégâts (+200%%AD), projette la cible. Ennemis percutés: mêmes dégâts + knockup.",base[r]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_leesin","ad",2.0));
        }
    }
}