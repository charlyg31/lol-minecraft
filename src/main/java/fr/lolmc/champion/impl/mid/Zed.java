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

public class Zed extends BaseChampion {
    public Zed() {
        super("zed", "Zed", ChampionRole.MID,
            new ChampionStats(654,63,0,32,32,0.651,0,345,1.25,7.0));
        getStats().setGrowthStats(99.0,3.4,4.7,2.05,0.03300,0.65);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(582, 7.0, ResourceSystem.ResourceType.ENERGY, 200, 50.0);
    }

    // Ombre active par joueur
    private static final Map<UUID,Location> shadows=new HashMap<>();

    static class AA extends BasicAttackAbility {
        AA(){super("zed",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            // Passif Mépris des Faibles : cible sous 50% PV = dégâts magiques bonus (8% PV max)
            if(tgt.getHealth() < tgt.getMaxHealth()*0.5){
                double bonus=tgt.getMaxHealth()*0.08;
                TargetingUtil.dealDamage(c, tgt, bonus, TargetingUtil.DmgType.MAGICAL);
                tgt.getWorld().spawnParticle(Particle.SMOKE,tgt.getLocation().add(0,1,0),8,0.3,0.5,0.3);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_zed","Shuriken Tranchant",Material.ARROW,AbilitySlot.Q,
            new double[]{6,5.5,5,4.5,4},20,0,DamageType.PHYSICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot ligne droite. 1er ennemi: 80-240 +100% AD bonus. Suivants: 48-144 +60% AD
            double[] baseFirst={80,120,160,200,240};
            double[] baseNext={48,72,96,120,144};
            int rank=getLevel()-1;
            double dmgFirst=baseFirst[rank]+s.getFinalAD()*1.0;
            double dmgNext=baseNext[rank]+s.getFinalAD()*0.6;
            var hits=TargetingUtil.skillshot(c, 12.0, 1.0, true); // traverse tous
            boolean first=true;
            for(var __t : hits){
                TargetingUtil.dealDamage(c, __t, first?dmgFirst:dmgNext, TargetingUtil.DmgType.PHYSICAL);
                first=false;
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] baseFirst={80,120,160,200,240};
            return String.format("Skillshot: %.0f dégâts au 1er ennemi (+100%%AD), réduit aux suivants.",baseFirst[getLevel()-1]+s.getFinalAD());
        }
    }

    static class W extends BaseAbility {
        W(){super("w_zed","Ombre Vivante",Material.GRAY_DYE,AbilitySlot.W,
            new double[]{20,18,16,14,12},0,0,DamageType.TRUE);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(shadows.containsKey(c.getUniqueId())) {
                // Double cast: téléportation sur l'ombre
                Location shadow=shadows.get(c.getUniqueId());
                c.teleport(safeTeleport(c.getLocation(),shadow));
                shadows.remove(c.getUniqueId());
                c.sendActionBar(Component.text("👤 Retour à l'ombre!",NamedTextColor.DARK_GRAY));
            } else {
                // Premier cast: crée une ombre devant
                Location shadowLoc=c.getLocation().clone().add(c.getLocation().getDirection().multiply(8));
                Location safe=safeTeleport(c.getLocation(),shadowLoc);
                shadows.put(c.getUniqueId(),safe);
                c.getWorld().spawnParticle(Particle.SMOKE,safe,20,1,1,1);
                c.sendActionBar(Component.text("👤 Ombre créée! Re-cast pour aller dessus.",NamedTextColor.DARK_GRAY));
                // Supprimer l'ombre après 4s si pas re-cast
                new BukkitRunnable(){@Override public void run(){shadows.remove(c.getUniqueId());}}.runTaskLater(LolPlugin.getInstance(),80L);
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Projette une ombre. Re-cast pour échanger de position avec elle.";}
    }

    static class E extends BaseAbility {
        E(){super("e_zed","Taillade des Ombres",Material.IRON_SWORD,AbilitySlot.E,
            new double[]{4,3,2,1,0.5},5,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : 70/92.5/115/137.5/160 + 70% AD bonus, ralentit 20-40% 1.5s
            double[] base={70,92.5,115,137.5,160};
            double dmg=base[getLevel()-1]+s.getFinalAD()*0.7;
            int rank=getLevel()-1;
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if(__t instanceof Player __p)
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,rank,false,true)); // 20%->40%
            }
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation().add(0,1,0),6,2,0.5,2);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.9f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={70,92.5,115,137.5,160};
            return String.format("%.0f dégâts AoE (+70%%AD) + ralentit 20-40%% 1.5s.",base[getLevel()-1]+s.getFinalAD()*0.7);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_zed","Marque de Mort",Material.WITHER_ROSE,AbilitySlot.R,
            new double[]{120,100,80},20,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("💀 Aucune cible visée",NamedTextColor.GRAY));return;}
            // Dash derrière la cible (intouchable approx)
            var dest=tgt.getLocation().clone().add(tgt.getLocation().getDirection().multiply(1.5));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            // Marque : on note les PV de la cible au début pour calculer les dégâts subis
            final double startHP=tgt.getHealth();
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("💀 MARQUE DE MORT — 3s...",NamedTextColor.DARK_RED));
            tgt.getWorld().spawnParticle(Particle.SMOKE,tgt.getLocation().add(0,1,0),20,0.5,1,0.5);
            double[] ampPct={0.25,0.40,0.55};
            int rank=Math.min(getLevel()-1,2);
            double ad=s.getFinalAD();
            new BukkitRunnable(){
                @Override public void run(){
                    if(tgt.isDead())return;
                    // Dégâts subis pendant la marque = startHP - HP actuel (approx des dégâts infligés)
                    double subis=Math.max(0, startHP - tgt.getHealth());
                    double dmg=ad*0.65 + subis*ampPct[rank];
                    tgt.getWorld().strikeLightningEffect(tgt.getLocation());
                    tgt.getWorld().spawnParticle(Particle.FLASH,tgt.getLocation().add(0,1,0),2);
                    TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.TRUE);
                    if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ DÉTONATION! Marque de Mort",NamedTextColor.DARK_RED));
                }
            }.runTaskLater(LolPlugin.getInstance(),60L); // 3s
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] ampPct={25,40,55};int r=Math.min(getLevel()-1,2);
            return String.format("Dash + marque 3s. Détonation: 65%%AD + %.0f%% des dégâts infligés.",ampPct[r]);
        }
    }
}