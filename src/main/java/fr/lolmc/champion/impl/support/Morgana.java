package fr.lolmc.champion.impl.support;
import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;

public class Morgana extends BaseChampion {
    public Morgana(){super("morgana","Morgana",ChampionRole.SUPPORT,new ChampionStats(560,55,0,20,32,0.625,0,335,20,7));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("morgana_aa","Attaque","{ad} dégâts magiques.",Material.PURPLE_DYE,AbilitySlot.AA,new double[]{0.5},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null)t.damage(s.calcMagicalDamage(s.getFinalAD(),null));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("morgana_q","Filet des Ombres","Root 2s: {dmg} dégâts magiques.",Material.LEAD,AbilitySlot.Q,new double[]{11,10.5,10,9.5,9},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcMagicalDamage(80+s.getFinalAP()*0.9,null);t.damage(dmg);t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,10,false,true));t.sendActionBar(Component.text("🕸 Root 2s!",NamedTextColor.DARK_PURPLE));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts (80 + 90%% AP) + root 2s.",80+s.getFinalAP()*0.9);}}
    static class W extends BaseAbility{W(){super("morgana_w","Emprisonnement Torturé","Zone de brûlure 5s: {dmg} dégâts/s.",Material.NETHERRACK,AbilitySlot.W,new double[]{10,9,8,7,6},20,4,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;Location loc=t.getLocation();new org.bukkit.scheduler.BukkitRunnable(){int ticks=0;@Override public void run(){if(ticks>=100){cancel();return;}double dmg=(24+s.getFinalAP()*0.11);loc.getWorld().getNearbyEntities(loc,4,2,4).stream().filter(e->e instanceof Player).forEach(e->((Player)e).damage(s.calcMagicalDamage(dmg,null)));loc.getWorld().spawnParticle(Particle.WITCH,loc,5,2,0,2);ticks+=20;}}.runTaskTimer(LolPlugin.getInstance(),0,20L);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("Zone 4 blocs: %.0f dégâts/s pendant 5s.",24+s.getFinalAP()*0.11);}}
    static class E extends BaseAbility{E(){super("morgana_e","Bouclier Noir","Bouclier anti-CC: absorbe {shield} dégâts magiques et annule CC.",Material.BLACK_STAINED_GLASS,AbilitySlot.E,new double[]{23,21,19,17,15},0,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,2,false,true));t.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,80,1,false,true));t.sendActionBar(Component.text("🛡 Bouclier Noir!",NamedTextColor.DARK_GRAY));}}
        @Override public String getDynamicDescription(ChampionStats s){return "Bouclier sur allié: absorbe 95+70% AP en dégâts magiques + immunité CC.";}}
    static class R extends BaseAbility{R(){super("morgana_r","Chaînes de la Corruption","Chaînes sur tous les ennemis proches: {dmg} dégâts + stun 1.5s.",Material.CHAIN,AbilitySlot.R,new double[]{120,110,100},20,5,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){Player m=t;if(m==null)return;double dmg=s.calcMagicalDamage(150+s.getFinalAP()*0.7,null);m.getWorld().getNearbyEntities(m.getLocation(),5,2,5).stream().filter(e->e instanceof Player&&!e.equals(m)).forEach(e->{((Player)e).damage(dmg);((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,10,false,true));((Player)e).sendActionBar(Component.text("⛓ STUN Morgana!",NamedTextColor.DARK_PURPLE));});m.getWorld().spawnParticle(Particle.END_ROD,m.getLocation(),20,3,1,3);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + stun 1.5s tous ennemis 5 blocs.",150+s.getFinalAP()*0.7);}}
}