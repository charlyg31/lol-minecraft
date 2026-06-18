package fr.lolmc.champion.impl.support;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;import org.bukkit.util.Vector;

public class Blitzcrank extends BaseChampion {
    public Blitzcrank(){super("blitzcrank","Blitzcrank",ChampionRole.SUPPORT,new ChampionStats(623,66,0,45,32,0.625,0,325,5,8));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("blitz_aa","Attaque","Poing de fer: {ad} dégâts.",Material.IRON_INGOT,AbilitySlot.AA,new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null)t.damage(s.calcAutoAttackDamage(null));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("blitz_q","Poing-Harpon","Attire la cible à toi: {dmg} dégâts magiques.",Material.FISHING_ROD,AbilitySlot.Q,new double[]{20,19,18,17,16},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;Player b=t;double dmg=s.calcMagicalDamage(75+s.getFinalAP()*0.6,null);t.damage(dmg);Vector pull=b.getLocation().toVector().subtract(t.getLocation().toVector()).normalize().multiply(1.5);pull.setY(0.3);t.setVelocity(pull);t.sendActionBar(Component.text("🪝 HARPON!",NamedTextColor.YELLOW));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + attire la cible (75 + 60%% AP).",75+s.getFinalAP()*0.6);}}
    static class W extends BaseAbility{W(){super("blitz_w","Surcharge","Vitesse x2 pendant 4s.",Material.REDSTONE,AbilitySlot.W,new double[]{22,20,18,16,14},0,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,4,false,true));t.sendActionBar(Component.text("⚡ SURCHARGE!",NamedTextColor.YELLOW));}}
        @Override public String getDynamicDescription(ChampionStats s){return "Vitesse de déplacement x2 pendant 4s.";}}
    static class E extends BaseAbility{E(){super("blitz_e","Poing Électrique","Knockup + {dmg} dégâts.",Material.LIGHTNING_ROD,AbilitySlot.E,new double[]{10,9,8,7,6},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcPhysicalDamage(80+s.getFinalAD()*1.0,null);t.damage(dmg);t.setVelocity(new Vector(0,0.9,0));t.sendActionBar(Component.text("⚡ Knockup!",NamedTextColor.YELLOW));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + knockup (80 + 100%% AD).",80+s.getFinalAD());}}
    static class R extends BaseAbility{R(){super("blitz_r","Champ Statique","AoE foudre: {dmg} dégâts magiques + silence 0.5s.",Material.COPPER_INGOT,AbilitySlot.R,new double[]{40,30,20},5,4,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){Player b=t;if(b==null)return;double dmg=s.calcMagicalDamage(275+s.getFinalAP()*0.7,null);b.getWorld().getNearbyEntities(b.getLocation(),4,2,4).stream().filter(e->e instanceof Player&&!e.equals(b)).forEach(e->{((Player)e).damage(dmg);((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,10,10,false,true));});b.getWorld().strikeLightningEffect(b.getLocation());}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts foudre AoE + silence.",275+s.getFinalAP()*0.7);}}
}