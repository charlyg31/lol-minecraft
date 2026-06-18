package fr.lolmc.champion.impl.support;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;import org.bukkit.util.Vector;

public class Leona extends BaseChampion {
    public Leona(){super("leona","Leona",ChampionRole.SUPPORT,new ChampionStats(576,55,0,44,32,0.625,0,335,5,7));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("leona_aa","Attaque","Frappe pour {ad} dégâts.",Material.IRON_SWORD,AbilitySlot.AA,new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null)t.damage(s.calcAutoAttackDamage(null));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("leona_q","Lumière du Zénith","Stun 1.25s: {dmg} dégâts magiques.",Material.GOLD_INGOT,AbilitySlot.Q,new double[]{11,10,9,8,7},5,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.damage(s.calcMagicalDamage(40+s.getFinalAP()*0.4,null));t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,25,10,false,true));t.sendActionBar(Component.text("☀ Stun Leona!",NamedTextColor.YELLOW));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + stun 1.25s.",40+s.getFinalAP()*0.4);}}
    static class W extends BaseAbility{W(){super("leona_w","Éclat Solaire","Gain +{armor} armure/MR pendant 3s.",Material.GOLDEN_CHESTPLATE,AbilitySlot.W,new double[]{14,13,12,11,10},0,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,60,2,false,true));t.sendActionBar(Component.text("🛡 Éclat Solaire!",NamedTextColor.GOLD));}}
        @Override public String getDynamicDescription(ChampionStats s){return "Gagne +30 armure et MR pendant 3s.";}}
    static class E extends BaseAbility{E(){super("leona_e","Zenith Blade","Dash sur cible: {dmg} dégâts + stun.",Material.BLAZE_ROD,AbilitySlot.E,new double[]{13,12,11,10,9},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;Player leo=t;Location dest=safeTeleportLocationFromTo(leo.getLocation(),t.getLocation());leo.teleport(dest);double dmg=s.calcMagicalDamage(60+s.getFinalAP()*0.4,null);t.damage(dmg);t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,15,10,false,true));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("Dash sur cible: %.0f dégâts + stun.",60+s.getFinalAP()*0.4);}}
    static class R extends BaseAbility{R(){super("leona_r","Eclipse Solaire","Zone solaire: {dmg} dégâts + stun centre 1.5s.",Material.SUNFLOWER,AbilitySlot.R,new double[]{130,105,80},25,4,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcMagicalDamage(100+s.getFinalAP()*0.7,null);t.getWorld().getNearbyEntities(t.getLocation(),4,2,4).stream().filter(e->e instanceof Player).forEach(e->{((Player)e).damage(dmg);((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,10,false,true));((Player)e).sendActionBar(Component.text("☀ ECLIPSE!",NamedTextColor.YELLOW));});t.getWorld().spawnParticle(Particle.END_ROD,t.getLocation(),30,2,1,2);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts AoE + stun 1.5s au centre.",100+s.getFinalAP()*0.7);}}
}