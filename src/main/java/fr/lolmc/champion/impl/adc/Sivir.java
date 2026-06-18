package fr.lolmc.champion.impl.adc;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
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
            new ChampionStats(532,57,0,24,30,0.658,0,345,25,3));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
    }

    static class AA extends BaseAbility {
        AA(){super("aa_sivir","Attaque de base",Material.STONE_SWORD,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            t.damage(dmg); s.applyVamp(dmg,false);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_sivir","Lame Boomerang",Material.IRON_AXE,AbilitySlot.Q,
            new double[]{9,8,7,6,5},25,0,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcPhysicalDamage(50+s.getFinalAD()*0.5,null);
            t.damage(dmg);
            // Retour: dégâts réduits
            new BukkitRunnable(){@Override public void run(){
                if(!t.isOnline())return;
                t.damage(s.calcPhysicalDamage(dmg*0.7,null));
                c.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),5);
            }}.runTaskLater(LolPlugin.getInstance(),15L);
            c.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),10);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double d=50+s.getFinalAD()*0.5;
            return String.format("Aller: %.0f dégâts. Retour: %.0f dégâts.",d,d*0.7);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_sivir","Ricochets",Material.MUSIC_DISC_CAT,AbilitySlot.W,
            new double[]{10,9,8,7,6},0,0,DamageType.PHYSICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,100,0,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,100,1,false,true));
            c.sendActionBar(Component.text("🔄 Ricochets 6s!",NamedTextColor.YELLOW));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "6s: AA rebondissent sur 7 cibles proches.";}
    }

    static class E extends BaseAbility {
        E(){super("e_sivir","Bouclier Maléfique",Material.SHIELD,AbilitySlot.E,
            new double[]{22,20,18,16,14},0,0,DamageType.TRUE);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,40,3,false,true));
            c.sendActionBar(Component.text("🛡 Bouclier Maléfique!",NamedTextColor.GREEN));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Bloque un sort ennemi. Annule le CC.";}
    }

    static class R extends BaseAbility {
        R(){super("r_sivir","Appel des Flèches",Material.GOLDEN_AXE,AbilitySlot.R,
            new double[]{120,100,80},0,0,DamageType.TRUE);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,2,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,80,2,false,true));
            c.sendActionBar(Component.text("⚡ Appel des Flèches 4s!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+30%% vitesse et haste pendant 4s.";}
    }
}