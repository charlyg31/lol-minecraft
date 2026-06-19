package fr.lolmc.champion.impl.top;

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

public class Malphite extends BaseChampion {
    public Malphite() {
        super("malphite", "Malphite", ChampionRole.TOP,
            new ChampionStats(574,52,0,36,32,0.625,0,335,5,8.5));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(574, 8.5, ResourceSystem.ResourceType.MANA, 480, 11.0);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_malphite","Attaque de base",Material.STONE_SWORD,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            DamageUtil.damage(c, t, dmg, false, DamageUtil.Type.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_malphite","Éclat de Pierre",Material.COBBLESTONE,AbilitySlot.Q,
            new double[]{8,7.5,7,6.5,6},20,0,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=70+s.getFinalAP()*0.6+s.getFinalMaxHP()*0.1;
            DamageUtil.abilityDamageMagic(c, t, dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));
            t.getWorld().spawnParticle(Particle.BLOCK,t.getLocation(),15,0.3,0.3,0.3,
                Material.STONE.createBlockData());
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts magiques (70+60%%AP+10%%HP). Ralentit 2s.",
                70+s.getFinalAP()*0.6+s.getFinalMaxHP()*0.1);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_malphite","Frappe Sismique",Material.IRON_CHESTPLATE,AbilitySlot.W,
            new double[]{12,11,10,9,8},5,3,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.getWorld().getNearbyEntities(c.getLocation(),3,2,3).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,1,false,true));
                    ((Player)e).sendActionBar(Component.text("💥 Frappe Sismique!",NamedTextColor.GRAY));
                });
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Réduit la vitesse d'attaque des ennemis proches de 30%% pendant 3s.";}
    }

    static class E extends BaseAbility {
        E(){super("e_malphite","Sol Fracturé",Material.CRACKED_STONE_BRICKS,AbilitySlot.E,
            new double[]{10,9,8,7,6},5,4,DamageType.MAGICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double dmg=60+s.getFinalAP()*0.3+s.getFinalArmor()*0.2;
            c.getWorld().getNearbyEntities(c.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamageMagic(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,1,false,true));
                });
            c.getWorld().spawnParticle(Particle.BLOCK,c.getLocation(),25,2,0,2,Material.STONE.createBlockData());
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts magiques AoE (60+30%%AP+20%%Armure).",60+s.getFinalAP()*0.3+s.getFinalArmor()*0.2);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_malphite","Assaut Implacable",Material.ANVIL,AbilitySlot.R,
            new double[]{130,105,80},20,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            Location dest=safeTeleport(c.getLocation(),t.getLocation());
            c.teleport(dest);
            double dmg=200+s.getFinalAP()*0.7+s.getFinalArmor()*0.4;
            c.getWorld().getNearbyEntities(c.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamageMagic(c, (Player)e, dmg);
                    ((Player)e).setVelocity(new Vector(0,1.2,0));
                    ((Player)e).sendActionBar(Component.text("🪨 KNOCKUP Malphite!",NamedTextColor.DARK_GRAY));
                });
            c.getWorld().spawnParticle(Particle.EXPLOSION,c.getLocation(),5,1,0,1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Bondit sur la cible. %.0f dégâts + knockup 1.5s dans 4 blocs.",200+s.getFinalAP()*0.7+s.getFinalArmor()*0.4);
        }
    }
}