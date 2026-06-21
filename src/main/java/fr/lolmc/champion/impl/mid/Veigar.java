package fr.lolmc.champion.impl.mid;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.TargetingUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class Veigar extends BaseChampion {
    public Veigar() {
        super("veigar", "Veigar", ChampionRole.MID,
            new ChampionStats(550,52,0,18,30,0.625,0,340,5.5,6.0));
        getStats().setGrowthStats(108.0,2.6,4.0,1.30,0.02240,0.60);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(491, 6.0, ResourceSystem.ResourceType.MANA, 490, 11.0);
        setAutoAttackRange(5.5);
    }

    public static final Map<UUID,Integer> apStacks=new HashMap<>();

    static class AA extends BaseAbility {
        AA(){super("aa_veigar","Attaque de base",Material.PURPLE_DYE,AbilitySlot.AA,
            new double[]{0.5},5,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,5.5); if(tgt==null)return;
            TargetingUtil.dealDamage(c, tgt, s.getFinalAD(), TargetingUtil.DmgType.MAGICAL);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Inflige %.0f dégâts.", s.getFinalAP());
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_veigar","Frappe Baleful",Material.AMETHYST_SHARD,AbilitySlot.Q,
            new double[]{7,6.5,6,5.5,5},20,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot qui touche les 2 premiers ennemis. 80-240 + 65% AP. Kill = stacks AP permanents
            double[] base={80,120,160,200,240};double dmg=base[getLevel()-1]+s.getFinalAP()*0.65;
            var hits=TargetingUtil.skillshot(c, 9.0, 0.8, true);
            int touched=0;
            for(var __t : hits){
                if(touched>=2) break; // max 2 cibles
                double hpBefore=__t.getHealth();
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                // Kill = +1 AP permanent (+2 si gros monstre/champion)
                if(hpBefore-dmg<=0){
                    boolean big=(__t instanceof Player)||fr.lolmc.game.JungleManager.isJungleMonster(__t);
                    int gain=big?2:1;
                    s.addBonusAP(gain);
                    apStacks.merge(c.getUniqueId(),gain,Integer::sum);
                    c.sendActionBar(Component.text("📈 +"+gain+" AP! Total stacks: "+apStacks.get(c.getUniqueId()),NamedTextColor.DARK_PURPLE));
                }
                touched++;
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={80,120,160,200,240};
            return String.format("Skillshot (2 cibles): %.0f dégâts (+65%%AP). Kill = AP permanent.",base[getLevel()-1]+s.getFinalAP()*0.65);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_veigar","Matière Noire",Material.OBSIDIAN,AbilitySlot.W,
            new double[]{10,9,8,7,6},20,3,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : tombe du ciel sur la zone visée après ~1.2s. 80-280 + 100% AP
            Location loc=TargetingUtil.getAimedGroundLocation(c, 9.0);
            loc.getWorld().spawnParticle(Particle.ENCHANT,loc,30,2,2,2);
            loc.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.5f);
            new BukkitRunnable(){
                @Override public void run(){
                    double[] base={80,130,180,230,280};double dmg=base[getLevel()-1]+s.getFinalAP()*1.0;
                    TargetingUtil.dealDamageAll(c,
                        TargetingUtil.entitiesInRadius(c, loc, 3.0), dmg, TargetingUtil.DmgType.MAGICAL);
                    loc.getWorld().spawnParticle(Particle.EXPLOSION,loc,8,1.5,0,1.5);
                    loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
                }
            }.runTaskLater(LolPlugin.getInstance(),24L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={80,130,180,230,280};
            return String.format("%.0f dégâts (+100%%AP) après 1.2s sur zone visée.",base[getLevel()-1]+s.getFinalAP());
        }
    }

    static class E extends BaseAbility {
        E(){super("e_veigar","Cage Événementielle",Material.DARK_OAK_FENCE,AbilitySlot.E,
            new double[]{18,16,14,12,10},20,4,DamageType.TRUE);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null)return;
            Location center=tgt.getLocation();
            List<org.bukkit.block.Block> cage=new ArrayList<>();
            int r=4;
            for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) {
                if(Math.abs(dx)==r||Math.abs(dz)==r) {
                    org.bukkit.block.Block b=center.clone().add(dx,0,dz).getBlock();
                    if(b.getType().isAir()) { b.setType(Material.IRON_BARS); cage.add(b); }
                }
            }
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("⬛ Cage Veigar 3s!",NamedTextColor.DARK_PURPLE));
            new BukkitRunnable(){@Override public void run(){cage.forEach(b->b.setType(Material.AIR));}}.runTaskLater(LolPlugin.getInstance(),60L);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Cage de barreaux autour de la cible pendant 3s.";}
    }

    static class R extends BaseAbility {
        R(){super("r_veigar","Explosion Primordiale",Material.NETHER_STAR,AbilitySlot.R,
            new double[]{120,100,80},20,0,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : ciblé. 200/350/500 + 80% AP, augmenté de 0-100% selon PV manquants
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("☠ Aucune cible visée",NamedTextColor.GRAY));return;}
            double[] base={200,350,500};int rr=Math.min(getLevel()-1,2);
            double baseDmg=base[rr]+s.getFinalAP()*0.8;
            // Augmenté jusqu'à +100% selon PV manquants de la cible
            double missingPct=1.0-(tgt.getHealth()/tgt.getMaxHealth());
            double dmg=baseDmg*(1.0+missingPct);
            tgt.getWorld().strikeLightningEffect(tgt.getLocation());
            tgt.getWorld().spawnParticle(Particle.FLASH,tgt.getLocation().add(0,1,0),3);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ EXPLOSION PRIMORDIALE!",NamedTextColor.DARK_PURPLE));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1.5f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base={200,350,500};int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts (+80%%AP), jusqu'à ×2 selon PV manquants.",base[r]+s.getFinalAP()*0.8);
        }
    }
}