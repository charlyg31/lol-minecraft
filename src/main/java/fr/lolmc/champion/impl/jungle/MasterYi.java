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

public class MasterYi extends BaseChampion {
    public MasterYi() {
        super("masteryi", "Master Yi", ChampionRole.JUNGLE,
            new ChampionStats(669,68,0,33,32,0.679,0,355,1.25,7.0));
        getStats().setGrowthStats(100.0,3.3,4.3,2.05,0.02000,0.65);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(559, 7.0, ResourceSystem.ResourceType.NONE, 0, 0.0);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("masteryi",Material.IRON_SWORD,6.0f,DamageType.PHYSICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_masteryi","Frappe Alpha",Material.ENDER_PEARL,AbilitySlot.Q,
            new double[]{18,16,14,12,10},15,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            Location dest=safeTeleport(c.getLocation(),tgt.getLocation());
            c.teleport(dest);
            double[] base={25,60,95,130,165};double dmg=base[getLevel()-1]+s.getFinalAD()*1.0;
            DamageUtil.trueDamageEntity(c, tgt, dmg);
            c.getWorld().spawnParticle(Particle.CRIT,tgt.getLocation(),10,0.5,0.5,0.5);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts vrais (25+110%%AD). Dash sur la cible.",25+s.getFinalAD()*1.1);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_masteryi","Méditation",Material.GOLDEN_APPLE,AbilitySlot.W,
            new double[]{35,30,25,20,15},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,80,4,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,80,2,false,true));
            c.sendActionBar(Component.text("🧘 Méditation 4s...",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Régénère HP pendant 4s. Réduit dégâts de 60%%.";}
    }

    static class E extends BaseAbility {
        E(){super("e_masteryi","Style Wuju",Material.BLAZE_ROD,AbilitySlot.E,
            new double[]{18,17,16,15,14},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,100,1,false,true));
            c.sendActionBar(Component.text("⚔ Wuju actif 5s!",NamedTextColor.YELLOW));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("5s: +%.0f dégâts vrais par AA (10+10%%AD).",10+s.getFinalAD()*0.1);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_masteryi","Highlander",Material.FEATHER,AbilitySlot.R,
            new double[]{85,70,55},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,140,3,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,140,2,false,true));
            c.sendActionBar(Component.text("⚡ HIGHLANDER 7s!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+35%% vitesse, +55%% haste, immunité ralentissements 7s. Kills reset CD.";}
    }
}