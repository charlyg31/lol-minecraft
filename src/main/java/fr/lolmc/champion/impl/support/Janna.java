package fr.lolmc.champion.impl.support;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.DamageUtil;
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

public class Janna extends BaseChampion {
    public Janna() {
        super("janna", "Janna", ChampionRole.SUPPORT,
            new ChampionStats(476,47,0,15,30,0.658,0,355,20,7));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(476, 7.0, ResourceSystem.ResourceType.MANA, 480, 11.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_janna","Attaque de base",Material.FEATHER,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.getFinalAP()*0.3;
            DamageUtil.damage(c, t, dmg, false, DamageUtil.Type.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAP());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_janna","Tailwind",Material.WHITE_DYE,AbilitySlot.Q,
            new double[]{12,11,10,9,8},20,2,DamageType.MAGICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=60+s.getFinalAP()*0.35;
            DamageUtil.abilityDamageMagic(c, t, dmg);
            t.setVelocity(new Vector(0,0.8,0));
            t.sendActionBar(Component.text("🌪 Tornade!",NamedTextColor.WHITE));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + knockup (60+35%%AP).",60+s.getFinalAP()*0.35);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_janna","Zéphyr",Material.CYAN_DYE,AbilitySlot.W,
            new double[]{8,7,6,5,4},20,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=55+s.getFinalAP()*0.5;
            DamageUtil.abilityDamageMagic(c, t, dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,2,false,true));
            t.sendActionBar(Component.text("💨 Zéphyr -30%%!",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + ralentit 3s (55+50%%AP).",55+s.getFinalAP()*0.5);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_janna","Oeil de la Tempête",Material.LIGHT_BLUE_DYE,AbilitySlot.E,
            new double[]{10,9,8,7,6},0,0,DamageType.TRUE);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Player dest=t!=null?t:c;
            dest.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,2,false,true));
            dest.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,100,0,false,true));
            dest.sendActionBar(Component.text("🌀 Oeil de la Tempête!",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Bouclier + force sur soi ou un allié pendant 5s.";}
    }

    static class R extends BaseAbility {
        R(){super("r_janna","Réveil",Material.HEART_OF_THE_SEA,AbilitySlot.R,
            new double[]{150,120,90},20,4,DamageType.TRUE);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Repousse ennemis
            c.getWorld().getNearbyEntities(c.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    Vector kb=e.getLocation().toVector().subtract(c.getLocation().toVector()).normalize().multiply(2.5);
                    kb.setY(0.5); e.setVelocity(kb);
                });
            // Soigne alliés proches
            c.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,60,3,false,true));
            c.sendActionBar(Component.text("🌪 RÉVEIL!",NamedTextColor.WHITE));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Repousse tous les ennemis proches. Soigne alliés 3s.";}
    }
}