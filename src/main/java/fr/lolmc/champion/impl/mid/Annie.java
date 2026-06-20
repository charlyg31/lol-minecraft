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

public class Annie extends BaseChampion {
    public Annie() {
        super("annie", "Annie", ChampionRole.MID,
            new ChampionStats(560,50,0,23,30,0.610,0,335,6.25,5.5));
        getStats().setGrowthStats(96.0,2.6,4.0,1.30,0.01360,0.55);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(528, 5.5, ResourceSystem.ResourceType.MANA, 528, 11.0);
        setAutoAttackRange(5.5);
    }

    static class AA extends BaseAbility {
        AA(){super("aa_annie","Attaque de base",Material.FIRE_CHARGE,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double dmg=s.getFinalAP()*0.2+s.getFinalAD();
            DamageUtil.damage(c, t, dmg, false, DamageUtil.Type.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAP());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_annie","Brasier",Material.BLAZE_POWDER,AbilitySlot.Q,
            new double[]{4,3.5,3,2.5,2},20,0,DamageType.MAGICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double[] base={80,130,180,230,280};
            double dmg=base[getLevel()-1]+s.getFinalAP()*0.85;
            DamageUtil.abilityDamageMagic(c, t, dmg);
            t.getWorld().spawnParticle(Particle.FLAME,t.getLocation(),15,0.5,0.5,0.5,0.1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={80,130,180,230,280};
            return String.format("%.0f dégâts magiques (%.0f+85%%AP).",base[getLevel()-1]+s.getFinalAP()*0.85,base[getLevel()-1]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_annie","Incinération",Material.CAMPFIRE,AbilitySlot.W,
            new double[]{8,7,6,5,4},6,4,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base={70,115,160,205,250};
            double dmg=base[getLevel()-1]+s.getFinalAP()*0.75;
            c.getWorld().getNearbyEntities(c.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player&&!e.equals(c))
                .forEach(e->DamageUtil.abilityDamageMagic(c, (Player)e, dmg));
            c.getWorld().spawnParticle(Particle.FLAME,c.getLocation(),25,2,1,2,0.08);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={70,115,160,205,250};
            return String.format("Cône de feu: %.0f dégâts (%.0f+75%%AP) dans 4 blocs.",base[getLevel()-1]+s.getFinalAP()*0.75,base[getLevel()-1]);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_annie","Molten Shield",Material.ORANGE_STAINED_GLASS,AbilitySlot.E,
            new double[]{10,9,8,7,6},0,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Player dest=t!=null?t:c;
            dest.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,0,false,true));
            dest.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,100,0,false,true));
            dest.sendActionBar(Component.text("🔥 Molten Shield 5s!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Bouclier 5s + immunité feu. Dégâts en retour aux attaquants.";}
    }

    static class R extends BaseAbility {
        R(){super("r_annie","Invocation de Tibbers",Material.NETHERITE_BLOCK,AbilitySlot.R,
            new double[]{100,80,60},20,3,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            if(t==null)return;
            double[] base={175,300,425};
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getFinalAP()*0.75;
            t.getWorld().getNearbyEntities(t.getLocation(),3,2,3).stream()
                .filter(e->e instanceof Player)
                .forEach(e->{DamageUtil.abilityDamageMagic(c, (Player)e, dmg);((Player)e).setFireTicks(60);
                    ((Player)e).sendActionBar(Component.text("🔥 TIBBERS!",NamedTextColor.RED));});
            t.getWorld().spawnParticle(Particle.LAVA,t.getLocation(),30,2,1,2);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={175,300,425};
            int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts AoE + brûlure (%.0f+75%%AP).",base[r]+s.getFinalAP()*0.75,base[r]);
        }
    }
}