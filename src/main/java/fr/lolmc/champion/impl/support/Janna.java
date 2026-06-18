package fr.lolmc.champion.impl.support;
import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;import org.bukkit.util.Vector;

public class Janna extends BaseChampion {
    public Janna(){super("janna","Janna",ChampionRole.SUPPORT,new ChampionStats(476,47,0,15,30,0.658,0,355,20,7));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("janna_aa","Attaque","Frappe pour {ad} dégâts.",Material.FEATHER,AbilitySlot.AA,new double[]{0.5},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null)t.damage(s.calcMagicalDamage(s.getFinalAD(),null));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("janna_q","Tailwind","Tornades: knockup + {dmg} dégâts.",Material.WHITE_DYE,AbilitySlot.Q,new double[]{12,11,10,9,8},20,2,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcMagicalDamage(60+s.getFinalAP()*0.35,null);t.damage(dmg);t.setVelocity(new Vector(0,0.8,0));t.sendActionBar(Component.text("🌪 Tornade!",NamedTextColor.WHITE));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + knockup.",60+s.getFinalAP()*0.35);}}
    static class W extends BaseAbility{W(){super("janna_w","Tempête de Zéphyr","Ralentit et inflige {dmg} dégâts.",Material.CYAN_DYE,AbilitySlot.W,new double[]{8,7,6,5,4},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.damage(s.calcMagicalDamage(55+s.getFinalAP()*0.5,null));t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,2,false,true));t.sendActionBar(Component.text("💨 Zéphyr!",NamedTextColor.AQUA));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + ralentit 3s (55 + 50%% AP).",55+s.getFinalAP()*0.5);}}
    static class E extends BaseAbility{E(){super("janna_e","Oeil de la Tempête","Bouclier puissant sur allié + {ad} AD bonus.",Material.LIGHT_BLUE_DYE,AbilitySlot.E,new double[]{10,9,8,7,6},0,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,2,false,true));t.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,100,0,false,true));t.sendActionBar(Component.text("🌀 Oeil de la Tempête!",NamedTextColor.AQUA));}}
        @Override public String getDynamicDescription(ChampionStats s){return "Bouclier +absorption + force sur allié pendant 5s.";}}
    static class R extends BaseAbility{R(){super("janna_r","Réveil","Repousse tous les ennemis + soigne alliés {heal}/s pendant 3s.",Material.HEART_OF_THE_SEA,AbilitySlot.R,new double[]{150,120,90},20,4,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){Player j=t;if(j==null)return;j.getWorld().getNearbyEntities(j.getLocation(),4,2,4).stream().filter(e->e instanceof Player&&!e.equals(j)).forEach(e->{Vector kb=e.getLocation().toVector().subtract(j.getLocation().toVector()).normalize().multiply(2);kb.setY(0.5);e.setVelocity(kb);});j.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,60,3,false,true));j.sendActionBar(Component.text("🌪 RÉVEIL!",NamedTextColor.WHITE));}
        @Override public String getDynamicDescription(ChampionStats s){return "Repousse ennemis proches. Soigne alliés proches 3s.";}}
}