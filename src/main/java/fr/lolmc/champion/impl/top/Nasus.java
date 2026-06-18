package fr.lolmc.champion.impl.top;

import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Nasus extends BaseChampion {
    // Stacks Q par joueur
    public static final Map<UUID,Integer> qStacks = new HashMap<>();

    public Nasus() {
        super("nasus","Nasus",ChampionRole.TOP,
            new ChampionStats(616,67,0,33,32,0.638,0,345,5,9));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new NAA()); setAbility(1,new NQ());
        setAbility(2,new NW()); setAbility(3,new NE()); setAbility(4,new NR());
    }

    static class NAA extends BaseAbility {
        NAA(){super("nasus_aa","Attaque","Frappe pour {ad} dégâts physiques.",
            Material.STONE_SWORD,AbilitySlot.AA,new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.damage(s.calcAutoAttackDamage(null));s.applyVamp(s.getFinalAD(),false);}}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("Frappe pour %.0f dégâts.",s.getFinalAD());}
    }
    static class NQ extends BaseAbility {
        NQ(){super("nasus_q","Frappe du Faucheur","Inflige {dmg} dégâts physiques + {stacks} stacks accumulés. Soigne si kill.",
            Material.BONE,AbilitySlot.Q,new double[]{8,7,6,5,4},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){
            if(t==null)return;
            UUID uid=((Player)t).getUniqueId();
            int stacks=qStacks.getOrDefault(uid,0);
            double dmg=s.calcPhysicalDamage(s.getFinalAD()+stacks,null);
            t.damage(dmg);
            if(t.getHealth()<=0) qStacks.merge(uid,6,Integer::sum);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return "Inflige AD + stacks Q accumulés. +6 stacks par kill/last hit.";
        }
    }
    static class NW extends BaseAbility {
        NW(){super("nasus_w","Flétrissure","Réduit le mouvement et la vitesse d'attaque de la cible de 35% pendant 5s.",
            Material.WITHER_ROSE,AbilitySlot.W,new double[]{15,14,13,12,11},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){
            if(t==null)return;
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,100,2,false,true));
            t.sendActionBar(Component.text("💀 Flétrissure de Nasus!",NamedTextColor.DARK_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Réduit les déplacements et la vitesse d'attaque de 35% pendant 5s.";}
    }
    static class NE extends BaseAbility {
        NE(){super("nasus_e","Esprit du Vide","Crée une zone de 3 blocs infligeant {dmg} dégâts magiques/s pendant 5s.",
            Material.PURPLE_WOOL,AbilitySlot.E,new double[]{12,11,10,9,8},20,3,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){
            if(t==null)return;
            Location loc=t.getLocation();
            new org.bukkit.scheduler.BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>=100){cancel();return;}
                    double dmg=(55+0.05*s.getFinalMaxHP())/5.0;
                    loc.getWorld().getNearbyEntities(loc,3,2,3).stream()
                        .filter(e->e instanceof Player&&!e.equals(t))
                        .forEach(e->((Player)e).damage(s.calcMagicalDamage(dmg,null)));
                    loc.getWorld().spawnParticle(Particle.WITCH,loc,5,1,1,1);
                    ticks+=20;
                }
            }.runTaskTimer(fr.lolmc.LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double dps=55+0.05*s.getFinalMaxHP();
            return String.format("Zone 3 blocs: %.0f dégâts magiques/s pendant 5s (55 + 5%% HP).",dps);
        }
    }
    static class NR extends BaseAbility {
        NR(){super("nasus_r","Furie des Sables","Gagne +{hp} HP et +{armor} armure/MR pendant 15s. Drain ennemis proches.",
            Material.GOLDEN_HELMET,AbilitySlot.R,new double[]{120,100,80},0,3,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){
            Player n=t; if(n==null)return;
            n.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST,300,2,false,true));
            n.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,300,1,false,true));
            n.sendActionBar(Component.text("☀ Furie des Sables!",NamedTextColor.GOLD));
            new org.bukkit.scheduler.BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>=300){cancel();return;}
                    double dmg=(3+0.01*s.getFinalMaxHP());
                    n.getLocation().getWorld().getNearbyEntities(n.getLocation(),3,2,3).stream()
                        .filter(e->e instanceof Player&&!e.equals(n))
                        .forEach(e->((Player)e).damage(s.calcMagicalDamage(dmg,null)));
                    ticks+=20;
                }
            }.runTaskTimer(fr.lolmc.LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Drain %.0f dégâts/s autour (3%% HP max). +bonus armure et HP pendant 15s.",
                3+0.01*s.getFinalMaxHP());
        }
    }
}
