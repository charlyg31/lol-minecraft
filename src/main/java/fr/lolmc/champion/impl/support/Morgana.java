package fr.lolmc.champion.impl.support;

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

public class Morgana extends BaseChampion {
    public Morgana() {
        super("morgana", "Morgana", ChampionRole.SUPPORT,
            new ChampionStats(630,56,0,25,30,0.625,0,335,4.5,5.5));
        getStats().setGrowthStats(104.0,3.5,4.5,1.30,0.01530,0.55);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(560, 7.0, ResourceSystem.ResourceType.MANA, 480, 12.0);
        setAutoAttackRange(13.8);
    }

    // Passif Siphon de l'Âme : les dégâts infligés aux champions et gros monstres
    // soignent Morgana (20% des dégâts infligés)
    static class AA extends BasicAttackAbility {
        AA(){super("morgana",Material.PURPLE_DYE,5.5f,DamageType.MAGICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            // Siphon de l'Âme : 20% vol de vie sur AA contre champions
            var cm = LolPlugin.getInstance().getChampionManager();
            if (tgt instanceof Player || fr.lolmc.game.JungleManager.isJungleMonster(tgt)) {
                if (cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(dmg * 0.20);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_morgana","Filet des Ombres",Material.LEAD,AbilitySlot.Q,
            new double[]{11,10.5,10,9.5,9},20,0,DamageType.MAGICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            double[] base=fr.lolmc.util.Balance.base("q_morgana",new double[]{80,135,190,245,300});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_morgana","ap",0.9);
            DamageUtil.abilityDamageMagicEntity(c, tgt, dmg);
            tgt.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,10,false,true));
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🕸 Root 2s — Morgana Q!",NamedTextColor.DARK_PURPLE));
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    tgt.getLocation(), Material.PURPLE_STAINED_GLASS, 0.28f, 0.5, 8, 6L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + root 2s (80+90%%AP).",80+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_morgana","ap",0.9));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_morgana","Emprisonnement Torturé",Material.NETHERRACK,AbilitySlot.W,
            new double[]{12,12,12,12,12},20,4,DamageType.MAGICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            Location loc=tgt.getLocation();
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=100){cancel();return;}
                    // Dégâts/tick min 7/14/21/28/35 +8.5%AP, +0-100%% selon PV manquants cible
                    double[] tickMin={7,14,21,28,35};
                    double baseTick=tickMin[getLevel()-1]+s.getFinalAP()*0.085;
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 4.0)){
                        double missingPct=0;
                        var cm=LolPlugin.getInstance().getChampionManager();
                        if(__t instanceof Player __tp && cm.hasChampion(__tp)){var hp=cm.getChampion(__tp).getHPSystem();missingPct=1.0-hp.getHPRatio();}
                        double dmg=baseTick*(1.0+missingPct); // jusqu'à x2 sur cible basse vie
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(loc.getWorld(),
                            loc, Material.PURPLE_STAINED_GLASS, 0.22f, 2.0, 5, 6L);
                    tick+=10; // tick toutes les 0.5s (LoL)
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,10L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] tickMin={7,14,21,28,35};
            return String.format("Zone 4 blocs: %.0f dégâts magiques/0.5s (5s), +0-100%% selon PV manquants.",tickMin[getLevel()-1]+s.getFinalAP()*0.085);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_morgana","Bouclier Noir",Material.BLACK_STAINED_GLASS,AbilitySlot.E,
            new double[]{26,24,22,20,18},0,0,DamageType.TRUE);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Bouclier Noir : protège un allié (ou soi), absorbe les dégâts magiques + immunité CC tant qu'il tient
            Player dest = (t != null) ? t : c;
            double[] shieldBase = {60,120,180,240,300};
            double shield = shieldBase[getLevel()-1] + s.getFinalAP()*0.50;
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(dest)) cm.getChampion(dest).getStats().addShield(shield);
            dest.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,1,false,true));
            dest.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,100,1,false,true));
            dest.sendActionBar(Component.text("🛡 Bouclier Noir! ("+(int)shield+")",NamedTextColor.DARK_GRAY));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] shieldBase = {60,120,180,240,300};
            return String.format("Bouclier %.0f (+50%%AP) anti-dégâts magiques + immunité CC sur un allié.",shieldBase[getLevel()-1]+s.getFinalAP()*0.50);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_morgana","Chaînes de la Corruption",Material.IRON_NUGGET,AbilitySlot.R,
            new double[]{110,100,90},20,5,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] rBase={150,225,300};double dmg=rBase[Math.min(getLevel()-1,2)]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_morgana","ap",0.7);
            for(var __t : TargetingUtil.enemiesAround(c, 5.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p){
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,10,false,true));
                    __p.sendActionBar(Component.text("⛓ STUN Morgana R!",NamedTextColor.DARK_PURPLE));
                }
            }
            fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                    c.getLocation(), 3.0, Material.PURPLE_STAINED_GLASS, 20, 0.35f, 0.1f, 12L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] rBase={150,225,300};
            return String.format("%.0f dégâts + stun 1.5s tous ennemis 5 blocs.",rBase[Math.min(getLevel()-1,2)]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_morgana","ap",0.7));
        }
    }
}