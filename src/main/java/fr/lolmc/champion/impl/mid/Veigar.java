package fr.lolmc.champion.impl.mid;

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

public class Veigar extends BaseChampion {
    public Veigar() {
        super("veigar", "Veigar", ChampionRole.MID,
            new ChampionStats(491,50,0,18,30,0.625,0,325,20,6));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
    }

    public static final Map<UUID,Integer> apStacks=new HashMap<>();

    static class AA extends BaseAbility {
        AA(){super("aa_veigar","Attaque de base",Material.PURPLE_DYE,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.MAGICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcMagicalDamage(s.getFinalAP()*0.6,null);
            t.damage(dmg); s.applyVamp(dmg,false);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAP());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_veigar","Singularité Primordiale",Material.AMETHYST_SHARD,AbilitySlot.Q,
            new double[]{5,4.5,4,3.5,3},20,0,DamageType.MAGICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcMagicalDamage(90+s.getFinalAP()*0.6,null);
            t.damage(dmg);
            apStacks.merge(c.getUniqueId(),5,Integer::sum);
            s.addBonusAP(5);
            t.getWorld().spawnParticle(Particle.WITCH,t.getLocation(),10,0.3,0.3,0.3);
            c.sendActionBar(Component.text("📈 +5 AP Veigar! Total: "+(s.getFinalAP()),NamedTextColor.DARK_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts + gain permanent de 5 AP (90+60%%AP).",90+s.getFinalAP()*0.6);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_veigar","Météorite Sombre",Material.OBSIDIAN,AbilitySlot.W,
            new double[]{10,9,8,7,6},20,3,DamageType.MAGICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            Location loc=t.getLocation();
            loc.getWorld().spawnParticle(Particle.ENCHANT,loc,30,2,2,2);
            new BukkitRunnable(){
                @Override public void run(){
                    double dmg=120+s.getFinalAP()*0.7;
                    loc.getWorld().getNearbyEntities(loc,3,2,3).stream()
                        .filter(e->e instanceof Player)
                        .forEach(e->((Player)e).damage(s.calcMagicalDamage(dmg,null)));
                    loc.getWorld().spawnParticle(Particle.EXPLOSION,loc,5,1,0,1);
                }
            }.runTaskLater(LolPlugin.getInstance(),25L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts après 1.25s sur zone 3 blocs (120+70%%AP).",120+s.getFinalAP()*0.7);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_veigar","Cage Événementielle",Material.DARK_OAK_FENCE,AbilitySlot.E,
            new double[]{18,16,14,12,10},20,4,DamageType.TRUE);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            Location center=t.getLocation();
            List<org.bukkit.block.Block> cage=new ArrayList<>();
            int r=4;
            for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) {
                if(Math.abs(dx)==r||Math.abs(dz)==r) {
                    org.bukkit.block.Block b=center.clone().add(dx,0,dz).getBlock();
                    if(b.getType().isAir()) { b.setType(Material.IRON_BARS); cage.add(b); }
                }
            }
            t.sendActionBar(Component.text("⬛ Cage Veigar 3s!",NamedTextColor.DARK_PURPLE));
            new BukkitRunnable(){@Override public void run(){cage.forEach(b->b.setType(Material.AIR));}}.runTaskLater(LolPlugin.getInstance(),60L);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Cage de barreaux autour de la cible pendant 3s.";}
    }

    static class R extends BaseAbility {
        R(){super("r_veigar","Doom",Material.NETHER_STAR,AbilitySlot.R,
            new double[]{120,100,80},20,0,DamageType.MAGICAL);}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double missing=1.0+(1.0-t.getHealth()/t.getMaxHealth())*0.75;
            double dmg=s.calcMagicalDamage((250+s.getFinalAP()*0.75)*missing,null);
            t.getWorld().strikeLightningEffect(t.getLocation());
            t.damage(dmg);
            t.sendMessage(Component.text("☠ DOOM!",NamedTextColor.DARK_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("%.0f dégâts (250+75%%AP x bonus HP manquants).",250+s.getFinalAP()*0.75);
        }
    }
}