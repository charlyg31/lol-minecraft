package fr.lolmc.champion.impl.mid;
import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;

public class Annie extends BaseChampion {
    public Annie(){super("annie","Annie",ChampionRole.MID,new ChampionStats(528,50,0,21,30,0.579,0,335,20,5.5));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("annie_aa","Attaque","Boule de feu: {ad}+{ap} dégâts.",Material.FIRE_CHARGE,AbilitySlot.AA,new double[]{0.5},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null)t.damage(s.calcMagicalDamage(s.getFinalAD()+s.getFinalAP()*0.2,null));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts magiques (AD + 20%% AP).",s.getFinalAD()+s.getFinalAP()*0.2);}}
    static class Q extends BaseAbility{Q(){super("annie_q","Brasier","Boule de feu: {dmg} dégâts magiques.",Material.BLAZE_POWDER,AbilitySlot.Q,new double[]{4,3.5,3,2.5,2},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcMagicalDamage(80+s.getFinalAP()*0.75,null);t.damage(dmg);t.getWorld().spawnParticle(Particle.FLAME,t.getLocation(),15,0.5,0.5,0.5,0.1);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts magiques (80 + 75%% AP).",80+s.getFinalAP()*0.75);}}
    static class W extends BaseAbility{W(){super("annie_w","Incinération","Cône de feu AoE: {dmg} dégâts.",Material.CAMPFIRE,AbilitySlot.W,new double[]{8,7,6,5,4},6,4,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){Player an=t;if(an==null)return;double dmg=70+s.getFinalAP()*0.65;an.getWorld().getNearbyEntities(an.getLocation(),4,2,4).stream().filter(e->e instanceof Player&&!e.equals(an)).forEach(e->((Player)e).damage(s.calcMagicalDamage(dmg,null)));an.getWorld().spawnParticle(Particle.FLAME,an.getLocation(),20,2,1,2,0.1);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts magiques (70 + 65%% AP) AoE.",70+s.getFinalAP()*0.65);}}
    static class E extends BaseAbility{E(){super("annie_e","Molten Shield","Bouclier + résistance au feu + dégâts retour.",Material.ORANGE_STAINED_GLASS,AbilitySlot.E,new double[]{10,9,8,7,6},0,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,0,false,true));t.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,100,0,false,true));t.sendActionBar(Component.text("🔥 Molten Shield!",NamedTextColor.GOLD));}}
        @Override public String getDynamicDescription(ChampionStats s){return "Bouclier 5s + immunité feu + dégâts retour aux attaquants.";}}
    static class R extends BaseAbility{R(){super("annie_r","Tibbers","Invoque Tibbers: {dmg} dégâts + brûlure AoE.",Material.NETHERITE_BLOCK,AbilitySlot.R,new double[]{100,80,60},20,3,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcMagicalDamage(200+s.getFinalAP()*0.7,null);t.getWorld().getNearbyEntities(t.getLocation(),3,2,3).stream().filter(e->e instanceof Player).forEach(e->{((Player)e).damage(dmg);((Player)e).setFireTicks(60);((Player)e).sendActionBar(Component.text("🔥 TIBBERS!",NamedTextColor.RED));});t.getWorld().spawnParticle(Particle.LAVA,t.getLocation(),30,2,1,2);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + brûlure 3s (200 + 70%% AP).",200+s.getFinalAP()*0.7);}}
}