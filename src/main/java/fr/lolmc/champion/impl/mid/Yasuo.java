package fr.lolmc.champion.impl.mid;
import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;import org.bukkit.util.Vector;
import java.util.*;

public class Yasuo extends BaseChampion {
    public static final Map<UUID,Integer> flowStacks = new HashMap<>();
    public Yasuo(){super("yasuo","Yasuo",ChampionRole.MID,new ChampionStats(523,60,0,30,32,0.667,0.19,345,5,0));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("yasuo_aa","Acier Tranchant","Frappe pour {ad} dégâts. 3ème frappe = tornades.",Material.IRON_SWORD,AbilitySlot.AA,new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.damage(s.calcAutoAttackDamage(null));s.applyVamp(s.getFinalAD(),false);}}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts. Double-crit passif (critx2).",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("yasuo_q","Acier Tranchant","Tornade linéaire: {dmg} dégâts. 2ème cast = lancé en l'air.",Material.BLAZE_POWDER,AbilitySlot.Q,new double[]{4,3.5,3,2.5,2},20,8,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){Player y=t;if(y==null)return;UUID uid=y.getUniqueId();int stacks=flowStacks.getOrDefault(uid,0)+1;flowStacks.put(uid,stacks);double dmg=s.calcPhysicalDamage(20+s.getFinalAD()*1.0,null);y.getWorld().getNearbyEntities(y.getLocation().add(y.getLocation().getDirection().multiply(3)),2,1,2).stream().filter(e->e instanceof Player&&!e.equals(y)).forEach(e->{((Player)e).damage(dmg);if(stacks>=2){((Player)e).setVelocity(new Vector(0,1.2,0));((Player)e).sendActionBar(Component.text("🌪 Knockup!",NamedTextColor.YELLOW));}});if(stacks>=2)flowStacks.put(uid,0);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts (AD + 100%% AD). 2ème cast = knockup.",20+s.getFinalAD());}}
    static class W extends BaseAbility{W(){super("yasuo_w","Mur du Vent","Mur de vent bloquant projectiles pendant 4s.",Material.WHITE_WOOL,AbilitySlot.W,new double[]{26,24,22,20,18},0,6,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,80,0,false,true));t.sendActionBar(Component.text("💨 Mur du Vent actif!",NamedTextColor.WHITE));}
        @Override public String getDynamicDescription(ChampionStats s){return "Mur de vent bloquant projectiles ennemis. Dure 4s.";}}
    static class E extends BaseAbility{E(){super("yasuo_e","Balayage de Lame","Dash sur une cible: {dmg} dégâts.",Material.FEATHER,AbilitySlot.E,new double[]{0.5,0.5,0.5,0.5,0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;Player y=t;Location dest=safeTeleportLocationFromTo(y.getLocation(),t.getLocation());y.teleport(dest);double dmg=s.calcPhysicalDamage(70+s.getFinalAD()*0.6,null);t.damage(dmg);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("Dash sur cible: %.0f dégâts (70 + 60%% AD). CD court.",70+s.getFinalAD()*0.6);}}
    static class R extends BaseAbility{R(){super("yasuo_r","Dernier Souffle","Nécessite cible en l'air. {dmg} dégâts physiques + suspend 1s.",Material.ELYTRA,AbilitySlot.R,new double[]{200,180,160},5,3,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcPhysicalDamage(200+s.getFinalAD()*1.5,null);t.getWorld().getNearbyEntities(t.getLocation(),3,3,3).stream().filter(e->e instanceof Player&&!e.equals(t)).forEach(e->{((Player)e).damage(dmg);((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,10,false,true));((Player)e).sendActionBar(Component.text("⚔ Dernier Souffle!",NamedTextColor.DARK_GRAY));});t.getWorld().spawnParticle(Particle.SWEEP_ATTACK,t.getLocation(),10,2,1,2);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + suspend 1s. Targets dans les airs.",200+s.getFinalAD()*1.5);}}
}