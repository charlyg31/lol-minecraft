package fr.lolmc.champion.impl.adc;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;

public class Sivir extends BaseChampion {
    public Sivir(){super("sivir","Sivir",ChampionRole.ADC,new ChampionStats(532,57,0,24,30,0.658,0,345,25,3));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("sivir_aa","Attaque","Frappe pour {ad} dégâts.",Material.STONE_SWORD,AbilitySlot.AA,new double[]{0.5},25,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.damage(s.calcAutoAttackDamage(null));s.applyVamp(s.getFinalAD(),false);}}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("sivir_q","Lame Boomerang","Lame A/R: {dmg} dégâts aller, {dmg2} dégâts retour.",Material.IRON_AXE,AbilitySlot.Q,new double[]{9,8,7,6,5},25,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcPhysicalDamage(50+s.getFinalAD()*0.5,null);t.damage(dmg);t.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),10);}
        @Override public String getDynamicDescription(ChampionStats s){double d=50+s.getFinalAD()*0.5;return String.format("Aller: %.0f dégâts. Retour: %.0f dégâts.",d,d*0.7);}}
    static class W extends BaseAbility{W(){super("sivir_w","Ricochets","Passif actif: les AA rebondissent sur 7 cibles.",Material.MUSIC_DISC_CAT,AbilitySlot.W,new double[]{10,9,8,7,6},0,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,100,0,false,true));t.sendActionBar(Component.text("🔄 Ricochets actifs!",NamedTextColor.YELLOW));}}
        @Override public String getDynamicDescription(ChampionStats s){return "Les AA rebondissent sur jusqu'à 7 cibles 6s.";}}
    static class E extends BaseAbility{E(){super("sivir_e","Bouclier Maléfique","Bloque 1 sort: rembourse du mana.",Material.SHIELD,AbilitySlot.E,new double[]{22,20,18,16,14},0,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,40,3,false,true));t.sendActionBar(Component.text("🛡 Bouclier Maléfique!",NamedTextColor.GREEN));}}
        @Override public String getDynamicDescription(ChampionStats s){return "Bloque 1 sort ennemi. Annule toujours le CC.";}}
    static class R extends BaseAbility{R(){super("sivir_r","Appel des Flèches","Boost de vitesse d'attaque et mouvement pour l'équipe.",Material.GOLDEN_AXE,AbilitySlot.R,new double[]{120,100,80},0,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,2,false,true));t.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,80,2,false,true));t.sendActionBar(Component.text("⚡ Appel des Flèches!",NamedTextColor.GOLD));}
        @Override public String getDynamicDescription(ChampionStats s){return "+30% vitesse et haste pendant 4s.";}}
}