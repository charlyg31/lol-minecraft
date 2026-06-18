package fr.lolmc.champion.impl.adc;
import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;

public class Jinx extends BaseChampion {
    public Jinx(){super("jinx","Jinx",ChampionRole.ADC,new ChampionStats(516,57,0,21,30,0.625,0,325,25,3));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("jinx_aa","Attaque","Pistolets: {ad} dégâts.",Material.CROSSBOW,AbilitySlot.AA,new double[]{0.5},25,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.damage(s.calcAutoAttackDamage(null));s.applyVamp(s.getFinalAD(),false);}}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts rapides.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("jinx_q","Choix des Armes","Alterne pistolets (vitesse) et bazooka (AoE).",Material.TNT,AbilitySlot.Q,new double[]{0,0,0,0,0},25,3,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,40,1,false,true));t.sendActionBar(Component.text("💥 Bazooka!",NamedTextColor.RED));}
        @Override public String getDynamicDescription(ChampionStats s){return "Alterne mini-canons (vitesse+portée) et Fishbones (AoE rockets).";}}
    static class W extends BaseAbility{W(){super("jinx_w","Zap","Rayon: {dmg} dégâts + ralentit.",Material.LIGHTNING_ROD,AbilitySlot.W,new double[]{10,9,8,7,6},25,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcPhysicalDamage(20+s.getFinalAD()*1.6,null);t.damage(dmg);t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,50,2,false,true));t.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,t.getLocation(),15);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts (20 + 160%% AD) + ralentit 2.5s.",20+s.getFinalAD()*1.6);}}
    static class E extends BaseAbility{E(){super("jinx_e","Champ de Mines","Pose des mines: ralentit + {dmg} dégâts.",Material.TRIPWIRE_HOOK,AbilitySlot.E,new double[]{20,18,16,14,12},25,2,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcMagicalDamage(80+s.getFinalAP()*0.7,null);t.damage(dmg);t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,2,false,true));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("Mine: %.0f dégâts magiques + ralentit 3s.",80+s.getFinalAP()*0.7);}}
    static class R extends BaseAbility{R(){super("jinx_r","Super Méga Rocket","Rocket mondiale: {dmg} dégâts + AoE à l'impact.",Material.FIREWORK_ROCKET,AbilitySlot.R,new double[]{90,75,60},25,5,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double missingHp=1.0+(1.0-t.getHealth()/t.getMaxHealth())*1.5;double dmg=s.calcPhysicalDamage((300+s.getFinalAD()*1.5)*missingHp,null);t.getWorld().getNearbyEntities(t.getLocation(),5,2,5).stream().filter(e->e instanceof Player).forEach(e->((Player)e).damage(dmg));t.getWorld().createExplosion(t.getLocation(),2f,false,false);t.sendMessage(Component.text("🚀 SUPER MÉGA ROCKET!",NamedTextColor.RED));}
        @Override public String getDynamicDescription(ChampionStats s){double d=300+s.getFinalAD()*1.5;return String.format("%.0f dégâts (x2.5 sur cible basse vie) + AoE 5 blocs.",d);}}
}