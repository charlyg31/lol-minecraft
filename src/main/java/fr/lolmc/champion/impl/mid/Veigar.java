package fr.lolmc.champion.impl.mid;
import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;import org.bukkit.block.Block;
import org.bukkit.entity.Player;import org.bukkit.potion.*;
import java.util.*;

public class Veigar extends BaseChampion {
    public static final Map<UUID,Integer> apStacks = new HashMap<>();
    public Veigar(){super("veigar","Veigar",ChampionRole.MID,new ChampionStats(491,50,0,18,30,0.625,0,325,20,6));}
    @Override protected void registerAbilities(){
        setAbility(0,new AA());setAbility(1,new Q());setAbility(2,new W());setAbility(3,new E());setAbility(4,new R());
    }
    static class AA extends BaseAbility{AA(){super("veigar_aa","Attaque","Boule magique: {ap} dégâts.",Material.PURPLE_DYE,AbilitySlot.AA,new double[]{0.5},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t!=null)t.damage(s.calcMagicalDamage(s.getFinalAP()*0.6,null));}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts magiques (60%% AP).",s.getFinalAP()*0.6);}}
    static class Q extends BaseAbility{Q(){super("veigar_q","Singularité Primordiale","+{stacks} AP permanent. {dmg} dégâts magiques.",Material.AMETHYST_SHARD,AbilitySlot.Q,new double[]{5,4.5,4,3.5,3},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double dmg=s.calcMagicalDamage(90+s.getFinalAP()*0.6,null);t.damage(dmg);UUID uid=((Player)t).getUniqueId();apStacks.merge(uid,5,Integer::sum);s.addBonusAP(5);t.getWorld().spawnParticle(Particle.WITCH,t.getLocation(),10);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts + gagne 5 AP permanent.",90+s.getFinalAP()*0.6);}}
    static class W extends BaseAbility{W(){super("veigar_w","Météorite Sombre","{dmg} dégâts magiques après 1.25s d'atterrissage.",Material.OBSIDIAN,AbilitySlot.W,new double[]{10,9,8,7,6},20,3,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;Location loc=t.getLocation();loc.getWorld().spawnParticle(Particle.ENCHANT,loc,30,2,2,2);new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){double dmg=120+s.getFinalAP()*0.7;loc.getWorld().getNearbyEntities(loc,3,2,3).stream().filter(e->e instanceof Player).forEach(e->((Player)e).damage(s.calcMagicalDamage(dmg,null)));loc.getWorld().createExplosion(loc,0f,false,false);}}.runTaskLater(LolPlugin.getInstance(),25L);}
        @Override public String getDynamicDescription(ChampionStats s){return String.format("%.0f dégâts magiques après 1.25s (120 + 70%% AP).",120+s.getFinalAP()*0.7);}}
    static class E extends BaseAbility{E(){super("veigar_e","Cage Événementielle","Crée une cage de blocs emprisonnant les ennemis 3s.",Material.DARK_OAK_FENCE,AbilitySlot.E,new double[]{18,16,14,12,10},20,4,DamageType.TRUE);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;Location center=t.getLocation();List<Block> cageBlocks=new ArrayList<>();int r=4;for(int dx=-r;dx<=r;dx++){for(int dz=-r;dz<=r;dz++){if(Math.abs(dx)==r||Math.abs(dz)==r){Block b=center.clone().add(dx,0,dz).getBlock();if(b.getType().isAir()){b.setType(Material.IRON_BARS);cageBlocks.add(b);}}}}new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){cageBlocks.forEach(b->b.setType(Material.AIR));}}.runTaskLater(LolPlugin.getInstance(),60L);t.sendActionBar(Component.text("⬛ Cage Événementielle!",NamedTextColor.DARK_PURPLE));}
        @Override public String getDynamicDescription(ChampionStats s){return "Crée une cage en barreaux autour de la cible pendant 3s.";}}
    static class R extends BaseAbility{R(){super("veigar_r","Doom","Exécution: {dmg} dégâts magiques massifs (+AP cible).",Material.NETHER_STAR,AbilitySlot.R,new double[]{120,100,80},20,0,DamageType.MAGICAL);}
        @Override public void cast(BaseChampion c,ChampionStats s,Player t){if(t==null)return;double missingHp=1+(1-t.getHealth()/t.getMaxHealth())*0.75;double dmg=s.calcMagicalDamage((250+s.getFinalAP()*0.75)*missingHp,null);t.damage(dmg);t.getWorld().strikeLightningEffect(t.getLocation());t.sendMessage(Component.text("☠ DOOM de Veigar!",NamedTextColor.DARK_PURPLE));}
        @Override public String getDynamicDescription(ChampionStats s){double dmg=250+s.getFinalAP()*0.75;return String.format("%.0f dégâts magiques (250 + 75%% AP). ×bonus vie manquante.",dmg);}}
}