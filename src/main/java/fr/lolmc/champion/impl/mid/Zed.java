package fr.lolmc.champion.impl.mid;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.potion.*;import org.bukkit.util.Vector;

public class Zed extends BaseChampion {
    public Zed(){super("zed","Zed",ChampionRole.MID,new ChampionStats(582,68,0,34,32,0.651,0,345,5,7));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("zed_aa","Attaque","Frappe pour {ad} dégâts.",Material.IRON_SWORD,AbilitySlot.AA,new double[]{0.5},5,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null){t.damage(s.calcAutoAttackDamage(null));s.applyVamp(s.getFinalAD(),false);}}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts.",s.getFinalAD());}}
    static class Q extends BaseAbility{Q(){super("zed_q","Lames Tourbillonnantes","Shuriken: {dmg} dégâts physiques.",Material.ARROW,AbilitySlot.Q,new double[]{6,5.5,5,4.5,4},20,0,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcPhysicalDamage(70+s.getFinalAD()*0.9,null);t.damage(dmg);t.getWorld().spawnParticle(Particle.CRIT,t.getLocation(),8);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts (70 + 90%% AD).",70+s.getFinalAD()*0.9);}}
    static class W extends BaseAbility{W(){super("zed_w","Ombre Vivante","Crée une ombre. Échange de position.",Material.GRAY_DYE,AbilitySlot.W,new double[]{20,18,16,14,12},0,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;Location shadow=t.getLocation().clone().add(t.getLocation().getDirection().multiply(8));shadow=safeTeleportLocationFromTo(t.getLocation(),shadow);t.getWorld().spawnParticle(Particle.SMOKE,shadow,20,1,1,1);t.sendActionBar(Component.text("👤 Ombre créée!",NamedTextColor.DARK_GRAY));}
        @Override public String getDynamicDescription(ChampionStats s){return "Projette une ombre. Réactiver pour échanger de position.";}}
    static class E extends BaseAbility{E(){super("zed_e","Ombre Tranchante","Ralentit et blesse ennemis proches: {dmg} dégâts.",Material.DARK_OAK_SWORD,AbilitySlot.E,new double[]{4,3,2,1,0.5},5,4,DamageType.PHYSICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){Player z=t;if(z==null)return;double dmg=45+s.getFinalAD()*0.8;z.getWorld().getNearbyEntities(z.getLocation(),4,2,4).stream().filter(e->e instanceof Player&&!e.equals(z)).forEach(e->{((Player)e).damage(s.calcPhysicalDamage(dmg,null));((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,1,false,true));});}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + ralentit 1.5s AoE.",45+s.getFinalAD()*0.8);}}
    static class R extends BaseAbility{R(){super("zed_r","Mort en Sursis","Marque la cible. Échange de position après 3s: {dmg}%% HP vrais.",Material.WITHER_ROSE,AbilitySlot.R,new double[]{120,100,80},20,0,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;t.sendActionBar(Component.text("💀 Mort en Sursis - 3s...",NamedTextColor.DARK_RED));Location zedLoc=t.getLocation().add(t.getLocation().getDirection().multiply(2));new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){double dmg=s.calcTrueDamage(t.getMaxHealth()*0.2+s.getFinalAD()*0.75);t.damage(dmg);t.getWorld().strikeLightningEffect(t.getLocation());t.sendMessage(Component.text("☠ Mort en Sursis!",NamedTextColor.DARK_RED));}}.runTaskLater(fr.lolmc.LolPlugin.getInstance(),60L);}
        @Override public String getDynamicDescription(ChampionStats s){return "Marque la cible. Après 3s: inflige 20% HP + 75% AD en dégâts vrais.";}}
}