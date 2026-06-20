package fr.lolmc.champion.impl.mid;

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

public class Zed extends BaseChampion {
    public Zed() {
        super("zed", "Zed", ChampionRole.MID,
            new ChampionStats(654,63,0,32,32,0.651,0,345,1.25,7.0));
        getStats().setGrowthStats(99.0,3.4,4.7,2.05,0.03300,0.65);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(582, 7.0, ResourceSystem.ResourceType.ENERGY, 200, 50.0);
    }

    // Ombre active par joueur
    private static final Map<UUID,Location> shadows=new HashMap<>();

    static class AA extends BaseAbility {
        AA(){super("aa_zed","Attaque de base",Material.IRON_SWORD,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcAutoAttackDamage(null);
            DamageUtil.damage(c, t, dmg, false);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAD());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_zed","Lames Tourbillonnantes",Material.ARROW,AbilitySlot.Q,
            new double[]{6,5.5,5,4.5,4},20,0,DamageType.PHYSICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=70+s.getFinalAD()*0.9;
            DamageUtil.abilityDamage(c, t, dmg);
            c.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),8,0.3,0.3,0.3);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts physiques (70+90%%AD).",70+s.getFinalAD()*0.9);
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
        E(){super("e_zed","Ombre Tranchante",Material.DARK_OAK_SWORD,AbilitySlot.E,
            new double[]{4,3,2,1,0.5},5,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double dmg=45+s.getFinalAD()*0.8;
            c.getWorld().getNearbyEntities(c.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->{
                    DamageUtil.abilityDamage(c, (Player)e, dmg);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,1,false,true));
                });
            c.getWorld().spawnParticle(Particle.CRIT,c.getLocation(),10,2,1,2);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + ralentit 1.5s AoE (45+80%%AD).",45+s.getFinalAD()*0.8);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_zed","Mort en Sursis",Material.WITHER_ROSE,AbilitySlot.R,
            new double[]{120,100,80},20,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            t.sendActionBar(Component.text("💀 Mort en Sursis — 3s...",NamedTextColor.DARK_RED));
            new BukkitRunnable(){
                @Override public void run(){
                    if(!t.isOnline())return;
                    double dmg=t.getMaxHealth()*0.2+s.getFinalAD()*0.75;
                    t.getWorld().strikeLightningEffect(t.getLocation());
                    DamageUtil.trueDamage(c, t, dmg);
                    t.sendMessage(Component.text("☠ Mort en Sursis!",NamedTextColor.DARK_RED));
                }
            }.runTaskLater(LolPlugin.getInstance(),60L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Marque la cible. Après 3s: %.0f dégâts vrais (20%% HP + 75%% AD).",
                s.getFinalAD()*0.75);
        }
    }
}