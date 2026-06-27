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

public class Garen extends BaseChampion {
    public Garen() {
        super("garen", "Garen", ChampionRole.TOP,
            new ChampionStats(690,66,0,36,32,0.625,0,340,1.75,8.0));
        getStats().setGrowthStats(98.0,5.0,4.5,2.05,0.03650,0.50);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(620, 8.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
    }

    // Passif Détermination : regen 1.5% HP max/s hors combat (>5s sans dégâts)
    public static void tickGarenPassive(Player p, fr.lolmc.champion.base.BaseChampion champ) {
        if (!champ.getHPSystem().isInCombat()) {
            double regen = champ.getHPSystem().getMaxHP() * 0.015; // 1.5%/s
            champ.getHPSystem().heal(regen);
        }
    }

    static class AA extends BasicAttackAbility {
        AA(){super("garen",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);}
    }

    // Q — Jugement Décisif
    static class Q extends BaseAbility {
        Q(){super("q_garen","Jugement Décisif",Material.GOLDEN_SWORD,AbilitySlot.Q,
            new double[]{8,7.5,7,6.5,6},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){c.sendActionBar(Component.text("⚔ Aucune cible à portée",NamedTextColor.GRAY));return;}
            // LoL : 30/55/80/105/130 + 50% AD bonus, silence 1.5s, boost vitesse
            double[] base=fr.lolmc.util.Balance.base("q_garen",new double[]{30,65,100,135,170});
            double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_garen","ad",0.5);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            // Silence 1.5s (30 ticks) via CCManager
            var cc = LolPlugin.getInstance().getCCManager();
            if (cc != null) cc.silence(tgt, 30);
            // +35% vitesse pendant 4s (80 ticks) — LoL
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,1,false,true));
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("⚠ Silence — Garen Q",NamedTextColor.RED));
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,tgt.getLocation().add(0,1,0),3);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_garen",new double[]{30,65,100,135,170});
            return String.format("%.0f dégâts physiques (+50%%AD). Silence 1.5s + 35%% vitesse 4s.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_garen","ad",0.5));
        }
    }

    // W — Courage
    static class W extends BaseAbility {
        W(){super("w_garen","Courage",Material.SHIELD,AbilitySlot.W,
            new double[]{23,21,19,17,15},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : réduction de dégâts 30% (4s), bouclier + ténacité (0.75s initial)
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,80,1,false,true)); // ~30% réduction 4s
            // Bouclier (absorption) : approximé via PV temporaires d'absorption
            c.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,40,1,false,true));
            // Ténacité (immunité aux ralentissements 0.75s) via nettoyage
            c.removePotionEffect(PotionEffectType.SLOWNESS);
            c.getWorld().spawnParticle(Particle.ENCHANTED_HIT,c.getLocation().add(0,1,0),20,0.5,1,0.5);
            c.getWorld().playSound(c.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.2f);
            c.sendActionBar(Component.text("🛡 Courage actif!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return "Réduit les dégâts reçus de 30%% (4s) + bouclier + ténacité.";
        }
    }

    // E — Tournoiement (Jugement) : 7 frappes sur 3s, +25% au plus proche
    static class E extends BaseAbility {
        E(){super("e_garen","Jugement",Material.COMPASS,AbilitySlot.E,
            new double[]{9,8,7,6,5},5,4,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.sendActionBar(Component.text("⚔ Jugement!",NamedTextColor.GOLD));
            // LoL : dégâts par tick = base[rang] + ratio AD, 7 frappes réparties sur 3s (~tous les 8.5 ticks)
            double[] baseTick={14,18,22,26,30};
            double[] adRatio={0.36,0.37,0.38,0.39,0.40};
            int rank=getLevel()-1;
            double perTick=baseTick[rank]+s.getFinalAD()*adRatio[rank];
            new BukkitRunnable(){
                int hits=0;
                @Override public void run(){
                    if(hits>=7){cancel();return;}
                    var around=TargetingUtil.enemiesAround(c, 4.0);
                    boolean single = around.size()==1; // +33%% si une seule cible (LoL)
                    for(var __t : around){
                        double dmg=perTick;
                        if(single) dmg*=1.33;
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                    }
                    c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation().add(0,1,0),3,1.5,0.3,1.5);
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.2f);
                    hits++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,9L); // 7 frappes × 9 ticks ≈ 3.15s (LoL: 3s)
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] baseTick={14,18,22,26,30};double[] adRatio={0.36,0.37,0.38,0.39,0.40};int r=getLevel()-1;
            double total=(baseTick[r]+s.getFinalAD()*adRatio[r])*7;
            return String.format("7 frappes sur 3s, total %.0f dégâts (rayon 4). Cible unique: +33%%.",total);
        }
    }

    // R — Exécution
    static class R extends BaseAbility {
        R(){super("r_garen","Exécution",Material.NETHERITE_SWORD,AbilitySlot.R,
            new double[]{120,100,80},25,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Justice Démacienne : porté plus loin (sort à distance moyenne), cible visée
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("☠ Aucune cible visée",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("r_garen",new double[]{150,250,350});
            int r=Math.min(getLevel()-1,2);
            // Reveal de la cible pendant 1s (20 ticks) au début du cast
            tgt.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,20,0,false,false));
            c.sendActionBar(Component.text("☠ Justice Démacienne en préparation...",NamedTextColor.DARK_RED));
            final org.bukkit.entity.LivingEntity ftgt=tgt; final int fr_=r;
            // Délai de cast 0.5s (10 ticks) — LoL
            new BukkitRunnable(){@Override public void run(){
                if(ftgt.isDead()||!ftgt.isValid()){return;}
                var cm=LolPlugin.getInstance().getChampionManager();
                double missingHP=0;
                if((ftgt instanceof Player && cm.hasChampion((Player)ftgt))){var hp=cm.getChampion((Player)ftgt).getHPSystem();missingHP=hp.getMaxHP()-hp.getCurrentHP();}
                double dmg=base[fr_]+missingHP*0.25;
                ftgt.getWorld().strikeLightningEffect(ftgt.getLocation());
                ftgt.getWorld().spawnParticle(Particle.FLASH,ftgt.getLocation().add(0,1,0),3);
                TargetingUtil.dealDamage(c, ftgt, dmg, TargetingUtil.DmgType.TRUE);
                if(ftgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ DEMACIA! Exécution de Garen!",NamedTextColor.DARK_RED));
            }}.runTaskLater(LolPlugin.getInstance(), 10L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_garen",new double[]{150,250,350});int r=Math.min(getLevel()-1,2);return String.format("%.0f dégâts vrais + 25%% PV manquants (cast 0.5s).",base[r]);
        }
    }
}