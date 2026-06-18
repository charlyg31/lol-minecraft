package fr.lolmc.champion.impl.top;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class Darius extends BaseChampion {
    public Darius() {
        super("darius","Darius",ChampionRole.TOP,
            new ChampionStats(582,64,0,39,32,0.625,0,340,5,8));
    }
    @Override protected void registerAbilities() {
        setAbility(0,new DAA()); setAbility(1,new DQ());
        setAbility(2,new DW()); setAbility(3,new DE()); setAbility(4,new DR());
    }

    static class DAA extends BaseAbility {
        DAA(){super("darius_aa","Attaque","Frappe pour {ad} dégâts physiques.",
            Material.IRON_AXE,AbilitySlot.AA,new double[]{0.5,0.48,0.46,0.44,0.42},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.damage(s.calcAutoAttackDamage(null));s.applyVamp(s.getFinalAD(),false);}}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("Frappe pour %.0f dégâts physiques.",s.getFinalAD());}
    }
    // Q — Lacération
    static class DQ extends BaseAbility {
        DQ(){super("darius_q","Lacération","Tourne sa hache infligeant {dmg} dégâts. Bord=+50%%. Soigne {heal} HP.",
            Material.NETHERITE_AXE,AbilitySlot.Q,new double[]{9,8,7,6,5},5,5,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){
            if(t==null)return;
            Player d=t; Location loc=d.getLocation();
            loc.getWorld().getNearbyEntities(loc,5,2,5).stream()
                .filter(e->e instanceof Player&&!e.equals(d)).forEach(e->{
                    double dist=e.getLocation().distance(loc);
                    double dmg=s.calcPhysicalDamage((40+0.6*s.getFinalAD())*(dist>3?1.5:1),null);
                    ((Player)e).damage(dmg);
                });
            loc.getWorld().spawnParticle(Particle.SWEEP_ATTACK,loc,5,2,1,2);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double dmg=40+0.6*s.getFinalAD();
            return String.format("Zone 5 blocs: %.0f dmg physiques. Bord (>3 blocs): %.0f dmg (+50%%).",dmg,dmg*1.5);
        }
    }
    // W — Destruction
    static class DW extends BaseAbility {
        DW(){super("darius_w","Destruction","Frappe avec le tranchant. {dmg} dégâts physiques + 100%% dmg bonus. Réduit Cooldown Q.",
            Material.GOLDEN_AXE,AbilitySlot.W,new double[]{9,8,7,6,5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.calcPhysicalDamage(s.getFinalAD()*2,null);
            t.damage(dmg);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts physiques (2× AD = 2× %.0f).",s.getFinalAD()*2,s.getFinalAD());
        }
    }
    // E — Appréhension
    static class DE extends BaseAbility {
        DE(){super("darius_e","Appréhension","Tire les ennemis vers soi et les ralentit 1s.",
            Material.FISHING_ROD,AbilitySlot.E,new double[]{24,21,18,15,12},5,5,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){
            if(t==null)return;
            Player d=t; Location loc=d.getLocation();
            loc.getWorld().getNearbyEntities(loc,5,2,5).stream()
                .filter(e->e instanceof Player&&!e.equals(d)).forEach(e->{
                    Vector pull=loc.toVector().subtract(e.getLocation().toVector()).normalize().multiply(0.8);
                    pull.setY(0.2);
                    e.setVelocity(pull);
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,2,false,true));
                });
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Attire les ennemis dans 5 blocs vers toi et les ralentit 1s.";}
    }
    // R — Noxian Guillotine
    static class DR extends BaseAbility {
        DR(){super("darius_r","Guillotine Noxienne","Exécute la cible: {dmg} dégâts vrais. Reset si kill.",
            Material.NETHERITE_AXE,AbilitySlot.R,new double[]{120,100,80},5,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){
            if(t==null)return;
            int stacks=3; // TODO: tracker les stacks Hémorragie
            double dmg=s.calcTrueDamage((100+40*level)*stacks*0.2+s.getFinalAD()*0.75);
            t.damage(dmg);
            t.getWorld().strikeLightningEffect(t.getLocation());
            t.sendMessage(Component.text("☠ Guillotine Noxienne!",NamedTextColor.DARK_RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double dmg=(100+40*level)+s.getFinalAD()*0.75;
            return String.format("%.0f dégâts vrais (×stacks Hémorragie). Reset CD si kill.",dmg);
        }
    }
}
