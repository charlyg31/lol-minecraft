package fr.lolmc.champion.impl.jungle;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;import org.bukkit.util.Vector;

public class LeeSin extends BaseChampion {
    public LeeSin(){super("leesin","Lee Sin",ChampionRole.JUNGLE,new ChampionStats(570,68,0,37,32,0.651,0,345,5,8));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("ls_aa","Attaque","Frappe pour {ad} dégâts.",Material.IRON_SWORD,AbilitySlot.AA,new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.damage(s.calcAutoAttackDamage(null));s.applyVamp(s.getFinalAD(),false);}}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("Frappe pour %.0f dégâts.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("ls_q","Frappe Sonique","Coup sonique: {dmg} dégâts physiques.",Material.ECHO_SHARD,AbilitySlot.Q,new double[]{11,10,9,8,7},15,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.damage(s.calcPhysicalDamage(55+s.getFinalAD()*0.9,null));t.sendActionBar(Component.text("🦵 Frappe Sonique!",NamedTextColor.YELLOW));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts (55 + 90%% AD).",55+s.getFinalAD()*0.9);}}
    static class W extends BaseAbility{W(){super("ls_w","Protection Safeguard","Dash vers allié + bouclier.",Material.SHIELD,AbilitySlot.W,new double[]{14,13,12,11,10},15,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,60,1,false,true));t.sendActionBar(Component.text("🛡 Bouclier!",NamedTextColor.GREEN));}}
        @Override public String getDynamicDescription(ChampionStats s){return "Dash + bouclier. Double cast: régén énergie.";}}
    static class E extends BaseAbility{E(){super("ls_e","Tempête de Flammes","Ralentit ennemis proches: {dmg} dégâts physiques.",Material.FIRE_CHARGE,AbilitySlot.E,new double[]{10,9,8,7,6},5,4,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){Player ls=t;if(ls==null)return;double dmg=60+s.getFinalAD()*0.5;ls.getWorld().getNearbyEntities(ls.getLocation(),4,2,4).stream().filter(e->e instanceof Player&&!e.equals(ls)).forEach(e->{((Player)e).damage(s.calcPhysicalDamage(dmg,null));((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));});}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + ralentit 2s dans 4 blocs.",60+s.getFinalAD()*0.5);}}
    static class R extends BaseAbility{R(){super("ls_r","Dragon's Rage","Coup de pied: {dmg} dégâts. Envoie la cible en l'air.",Material.DRAGON_EGG,AbilitySlot.R,new double[]{90,75,60},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.damage(s.calcPhysicalDamage(175+s.getFinalAD()*2.0,null));t.setVelocity(new Vector(t.getVelocity().getX()*2,1.5,t.getVelocity().getZ()*2));t.sendActionBar(Component.text("🐉 Dragon's Rage!",NamedTextColor.RED));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts (175 + 200%% AD). Knockback.",175+s.getFinalAD()*2);}}
}