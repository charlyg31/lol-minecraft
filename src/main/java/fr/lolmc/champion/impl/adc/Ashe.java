package fr.lolmc.champion.impl.adc;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;import org.bukkit.util.Vector;

public class Ashe extends BaseChampion {
    public Ashe(){super("ashe","Ashe",ChampionRole.ADC,new ChampionStats(528,59,0,26,30,0.658,0.15,325,25,3));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("ashe_aa","Attaque","Flèche: {ad} dégâts. Ralentit la cible.",Material.ARROW,AbilitySlot.AA,new double[]{0.5},25,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.damage(s.calcAutoAttackDamage(null));t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,15,0,false,true));s.applyVamp(s.getFinalAD(),false);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + ralentit 0.75s passif.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("ashe_q","Tir Concentré","Active: +{ad} AD et les AA ralentissent plus pendant 6s.",Material.SPECTRAL_ARROW,AbilitySlot.Q,new double[]{14,12,10,8,6},25,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,120,0,false,true));t.sendActionBar(Component.text("🏹 Tir Concentré!",NamedTextColor.AQUA));}}
        @Override public String getDynamicDescription(ChampionStats s){return "AA ralentissent 30% plus pendant 6s.";}}
    static class W extends BaseAbility{W(){super("ashe_w","Tir de Volée","Volée de flèches AoE: {dmg} dégâts + ralentissement.",Material.TIPPED_ARROW,AbilitySlot.W,new double[]{14,12,10,8,6},25,4,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcPhysicalDamage(s.getFinalAD()*1.1,null);t.getWorld().getNearbyEntities(t.getLocation(),4,2,4).stream().filter(e->e instanceof Player).forEach(e->{((Player)e).damage(dmg);((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));});t.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),20,2,1,2);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts AoE + ralentit (110%% AD).",s.getFinalAD()*1.1);}}
    static class E extends BaseAbility{E(){super("ashe_e","Faucon Explorateur","Envoie un faucon qui révèle la zone.",Material.FEATHER,AbilitySlot.E,new double[]{5,4,3,2,1},60,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null)t.sendActionBar(Component.text("🦅 Faucon envoyé!",NamedTextColor.YELLOW));}
        @Override public String getDynamicDescription(ChampionStats s){return "Envoie un faucon révélant une zone de la map.";}}
    static class R extends BaseAbility{R(){super("ashe_r","Flèche de Cristal","Flèche globale: {dmg} dégâts + stun 3.5s. Portée illimitée.",Material.DIAMOND,AbilitySlot.R,new double[]{100,80,60},25,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcPhysicalDamage(250+s.getFinalAP()*1.0,null);t.damage(dmg);t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,70,10,false,true));t.getWorld().spawnParticle(Particle.END_ROD,t.getLocation(),30,1,1,1);t.sendMessage(Component.text("❄ Flèche de Cristal d'Ashe!",NamedTextColor.AQUA));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + stun 3.5s (portée infinie).",250+s.getFinalAP()*1.0);}}
}