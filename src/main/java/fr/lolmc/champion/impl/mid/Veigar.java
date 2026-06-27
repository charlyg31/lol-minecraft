package fr.lolmc.champion.impl.mid;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.ability.base.BasicAttackAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.TargetingUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute; // AJOUT : Import de l'attribut
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

    public static final Map<UUID,Integer> apStacks=new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_AP_STACKS = 150;
    public static void resetState(UUID id){ apStacks.remove(id); }
    public static void resetAllState(){ apStacks.clear(); }

    /** Passif Malfaisance : +AP permanent sur kill/assist de champion. */
    public static void onTakedown(java.util.UUID id) {
        int stacks = apStacks.merge(id, 1, Integer::sum);
        var player = org.bukkit.Bukkit.getPlayer(id);
        if (player == null) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (cm.hasChampion(player)) {
            cm.getChampion(player).getStats().addBonusAP(5.0); // +5 AP par takedown de champion (LoL)
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                "📈 Malfaisance! +" + stacks + " AP total",
                net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE));
        }
    }

    static class AA extends BasicAttackAbility {
        AA(){super("veigar",Material.PURPLE_DYE,5.5f,DamageType.MAGICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_veigar","Frappe Baleful",Material.AMETHYST_SHARD,AbilitySlot.Q,
                new double[]{5.5,5,4.5,4,3.5},20,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("q_veigar",new double[]{80,120,160,200,240});
            double[] apR={0.50,0.55,0.60,0.65,0.70};
            double dmg=base[getLevel()-1]+s.getFinalAP()*apR[getLevel()-1];
            var hits=TargetingUtil.skillshot(c, 9.0, 0.8, true);
            int touched=0;
            for(var __t : hits){
                if(touched>=2) break;
                double hpBefore=__t.getHealth();
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(hpBefore-dmg<=0 && apStacks.getOrDefault(c.getUniqueId(),0) < MAX_AP_STACKS){
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
            double[] base=fr.lolmc.util.Balance.base("q_veigar",new double[]{80,120,160,200,240});
            double[] apR={0.50,0.55,0.60,0.65,0.70};
            return String.format("Skillshot (2 cibles): %.0f dégâts (+%.0f%%AP). Kill = AP permanent.",base[getLevel()-1]+s.getFinalAP()*apR[getLevel()-1],apR[getLevel()-1]*100);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_veigar","Matière Noire",Material.OBSIDIAN,AbilitySlot.W,
                new double[]{10,9,8,7,6},20,3,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Location loc=TargetingUtil.getAimedGroundLocation(c, 9.0);
            loc.getWorld().spawnParticle(Particle.ENCHANT,loc,30,2,2,2);
            loc.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.5f);
            new BukkitRunnable(){
                @Override public void run(){
                    double[] base=fr.lolmc.util.Balance.base("w_veigar",new double[]{80,120,160,200,240});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_veigar","ap",1.0);
                    TargetingUtil.dealDamageAll(c,
                            TargetingUtil.entitiesInRadius(c, loc, 3.0), dmg, TargetingUtil.DmgType.MAGICAL);
                    loc.getWorld().spawnParticle(Particle.EXPLOSION,loc,8,1.5,0,1.5);
                    loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
                }
            }.runTaskLater(LolPlugin.getInstance(),24L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_veigar",new double[]{80,120,160,200,240});
            return String.format("%.0f dégâts (+100%%AP) après 1.2s sur zone visée.",base[getLevel()-1]+s.getFinalAP());
        }
    }

    static class E extends BaseAbility {
        E(){super("e_veigar","Cage Événementielle",Material.DARK_OAK_FENCE,AbilitySlot.E,
                new double[]{23,21.5,20,18.5,17},0,4,DamageType.TRUE);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Location center=TargetingUtil.getAimedGroundLocation(c, 7.0);
            if(center==null) center=c.getLocation();
            final Location fcenter=center.clone();
            // Stun selon le rang : 1.5/1.75/2/2.25/2.5s
            double[] stunSec={1.5,1.75,2.0,2.25,2.5};
            final int stunTicks=(int)(stunSec[getLevel()-1]*20);
            final int r=4;
            // Délai 0.5s (10 ticks) avant apparition de la cage — LoL
            fcenter.getWorld().spawnParticle(Particle.WITCH, fcenter, 30, r, 0.5, r);
            new BukkitRunnable(){@Override public void run(){
                List<org.bukkit.block.Block> cage=new ArrayList<>();
                for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) {
                    if(Math.abs(dx)==r||Math.abs(dz)==r) {
                        org.bukkit.block.Block b=fcenter.clone().add(dx,0,dz).getBlock();
                        if(b.getType().isAir()) { b.setType(Material.IRON_BARS); cage.add(b); }
                    }
                }
                // Surveiller les ennemis qui touchent le périmètre pendant 3s (60 ticks)
                var cc=LolPlugin.getInstance().getCCManager();
                final java.util.Set<UUID> stunned=new java.util.HashSet<>();
                new BukkitRunnable(){
                    int t=0;
                    @Override public void run(){
                        if(t>=60){cage.forEach(b->b.setType(Material.AIR));cancel();return;}
                        for(var e : TargetingUtil.entitiesInRadius(c, fcenter, r+0.5)){
                            double dist=e.getLocation().distance(fcenter);
                            if(dist>=r-1 && !stunned.contains(e.getUniqueId())){
                                stunned.add(e.getUniqueId());
                                if(cc!=null) cc.stun(e, stunTicks);
                                if(e instanceof Player ep) ep.sendActionBar(Component.text("⬛ Étourdi par la Cage!",NamedTextColor.DARK_PURPLE));
                            }
                        }
                        t++;
                    }
                }.runTaskTimer(LolPlugin.getInstance(),0L,1L);
            }}.runTaskLater(LolPlugin.getInstance(),10L);
            c.sendActionBar(Component.text("⬛ Cage Événementielle!",NamedTextColor.DARK_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] stunSec={1.5,1.75,2.0,2.25,2.5};
            return String.format("Cage 3s. Ennemis touchant les barreaux : stun %.2fs.",stunSec[getLevel()-1]);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_veigar","Explosion Primordiale",Material.NETHER_STAR,AbilitySlot.R,
                new double[]{120,90,60},20,0,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("☠ Aucune cible visée",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("r_veigar",new double[]{175,350,525});int rr=Math.min(getLevel()-1,2);
            double baseDmg=base[rr]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_veigar","ap",1.2);

            // CORRECTION : Utilisation de l'attribut GENERIC_MAX_HEALTH au lieu de getMaxHealth() déprécié
            var maxHealthAttr = tgt.getAttribute(fr.lolmc.util.Compat.maxHealth());
            double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;

            // Augmenté jusqu'à +100% selon PV manquants de la cible
            double missingPct=1.0-(tgt.getHealth()/maxHealth);
            double dmg=baseDmg*(1.0+missingPct);
            tgt.getWorld().strikeLightningEffect(tgt.getLocation());
            tgt.getWorld().spawnParticle(Particle.FLASH,tgt.getLocation().add(0,1,0),3);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ EXPLOSION PRIMORDIALE!",NamedTextColor.DARK_PURPLE));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1.5f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_veigar",new double[]{175,350,525});int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts (+120%%AP), jusqu'à ×2 selon PV manquants.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_veigar","ap",1.2));
        }
    }
}