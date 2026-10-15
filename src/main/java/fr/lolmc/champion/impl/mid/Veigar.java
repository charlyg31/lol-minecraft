package fr.lolmc.champion.impl.mid;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.ability.base.BasicAttackAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.TargetingUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;

public final class Veigar extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Veigar() {
        super("veigar", "Veigar", ChampionRole.MID,
                new ChampionStats(580,52,0,18,32,0.625,0,340,5.5,6.5));
        getStats().setGrowthStats(108,2.7,5.2,1.3,0.0224,0.6);
        setLolAttackRange(550); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(6.5, ResourceSystem.ResourceType.MANA, 490, 26, 8, 0.8);
    }

    public static final Map<UUID,Integer> apStacks=new java.util.concurrent.ConcurrentHashMap<>();
    // Pas de plafond dans LoL (avant : 150)
    private static final int MAX_AP_STACKS = Integer.MAX_VALUE;
    @Override public void resetState(UUID id){ apStacks.remove(id); }
    @Override public void resetAllState(){ apStacks.clear(); }

    /** Passif Malfaisance : +5 stacks (1 AP chacun) par kill/assist de champion (LoL). */
    public static void onTakedown(java.util.UUID id) {
        var player = org.bukkit.Bukkit.getPlayer(id);
        if (player == null) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;
        int gain = Math.max(0, Math.min(5, MAX_AP_STACKS - apStacks.getOrDefault(id, 0)));
        if (gain == 0) return;
        int stacks = apStacks.merge(id, gain, Integer::sum);
        cm.getChampion(player).getStats().addBonusAP(gain);
        // (avant : le message affichait le nombre de takedowns comme « AP total »)
        player.sendActionBar(net.kyori.adventure.text.Component.text(
            "📈 Malfaisance! +" + gain + " AP (" + stacks + " au total)",
            net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE));
    }

    static class AA extends BasicAttackAbility {
        AA(){super("veigar",Material.PURPLE_DYE,5.5f,DamageType.MAGICAL);}
    }

    /** Malfaisance : +n stacks (1 AP chacun), dans la limite du plafond. */
    static void gainStacks(Player c, ChampionStats s, int n) {
        int gain = Math.max(0, Math.min(n, MAX_AP_STACKS - apStacks.getOrDefault(c.getUniqueId(), 0)));
        if (gain == 0) return;
        s.addBonusAP(gain);
        apStacks.merge(c.getUniqueId(), gain, Integer::sum);
    }

    /** Passif (wiki) : +1 stack par champion ennemi touché par un sort. */
    static void onChampionHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity t) {
        if (t instanceof Player) gainStacks(c, s, 1);
    }

    static class Q extends BaseAbility {
        Q(){super("q_veigar","Frappe Baleful",Material.AMETHYST_SHARD,AbilitySlot.Q,
                new double[]{6,5.5,5,4.5,4},20,0,DamageType.MAGICAL);
            resourceCost = 30; resourceCosts = new double[]{30,35,40,45,50};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("q_veigar",new double[]{80,120,160,200,240});
            double[] apR={0.50,0.55,0.60,0.65,0.70};
            double dmg=base[getLevel()-1]+s.getFinalAP()*apR[getLevel()-1];
            // Projectile réel (1050 unités à 2200/s), touche 2 ennemis au plus
            final int[] touched={0};
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), c.getEyeLocation().getDirection(), 10.5, 0.7, true, 22.0, // missile : 1050 unités à 2200/s (données du jeu)
                    new org.bukkit.inventory.ItemStack(Material.AMETHYST_SHARD), 0.8f, Material.PURPLE_STAINED_GLASS,
                    __t -> { if(touched[0]++ < 2) balefulHit(c, s, __t, dmg); }, null);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1.3f);
        }

        private void balefulHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity __t, double dmg){
            {
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                onChampionHit(c, s, __t);
                boolean killed = __t.isDead() || TargetingUtil.getRealHealth(__t) <= 0;
                if(killed){
                    // Wiki : 1 stack par kill, 3 sur gros sbire ou monstre
                    String mt=fr.lolmc.game.MinionManager.getMinionTypeTag(__t);
                    boolean big=fr.lolmc.game.JungleManager.isJungleMonster(__t) || "cannon".equals(mt) || "super".equals(mt);
                    gainStacks(c, s, big?3:1);
                    c.sendActionBar(Component.text("📈 Malfaisance! Total stacks: "+apStacks.getOrDefault(c.getUniqueId(),0),NamedTextColor.DARK_PURPLE));
                }
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_veigar",new double[]{80,120,160,200,240});
            double[] apR={0.50,0.55,0.60,0.65,0.70};
            return String.format("Skillshot (2 cibles): %.0f dégâts (+%.0f%%AP). Kill = AP permanent.",base[getLevel()-1]+s.getFinalAP()*apR[getLevel()-1],apR[getLevel()-1]*100);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_veigar","Matière Noire",Material.OBSIDIAN,AbilitySlot.W,
                new double[]{8,8,8,8,8},20,3,DamageType.MAGICAL);
            resourceCost = 60; resourceCosts = new double[]{60,65,70,75,80};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Passif : -10 % de recharge par tranche de 50 effets, en cumul multiplicatif (données du jeu)
            int stacks=apStacks.getOrDefault(c.getUniqueId(),0);
            double keep=Math.pow(0.9, stacks/50);
            final BaseAbility w=this;
            if(keep<1) new BukkitRunnable(){@Override public void run(){
                w.reduceCooldown(c, w.getRemainingCooldown(c)*(1-keep));
            }}.runTaskLater(LolPlugin.getInstance(),1L);
            Location loc=TargetingUtil.getAimedGroundLocation(c, 9.5);
            fr.lolmc.util.VisualEffectUtil.groundRing(loc.getWorld(), loc, 3.0,
                    Material.PURPLE_STAINED_GLASS, 18, 0.35f, 0.1f, 25L);
            loc.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.5f);
            new BukkitRunnable(){
                @Override public void run(){
                    // Wiki : 85-305 (+70/80/90/100/110 % AP)
                    double[] base=fr.lolmc.util.Balance.base("w_veigar",new double[]{85,140,195,250,305});
                    double[] apR={0.70,0.80,0.90,1.00,1.10};
                    double dmg=base[getLevel()-1]+s.getFinalAP()*apR[getLevel()-1];
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 2.9)){ // rayon 225 + collision (65)
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        onChampionHit(c, s, __t);
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(loc.getWorld(),
                            loc, Material.PURPLE_STAINED_GLASS, 0.35f, 1.5, 10, 8L);
                    loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
                }
            }.runTaskLater(LolPlugin.getInstance(),24L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_veigar",new double[]{85,140,195,250,305});
            double[] apR={0.70,0.80,0.90,1.00,1.10};
            return String.format("%.0f dégâts (+%.0f%%AP) après 1.2s sur zone visée.",base[getLevel()-1]+s.getFinalAP()*apR[getLevel()-1],apR[getLevel()-1]*100);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_veigar","Cage Événementielle",Material.DARK_OAK_FENCE,AbilitySlot.E,
                new double[]{20,18.5,17,15.5,14},0,4,DamageType.TRUE);
            resourceCost = 70; resourceCosts = new double[]{70,75,80,85,90};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Location center=TargetingUtil.getAimedGroundLocation(c, 7.0);
            final Location fcenter=center.clone();
            // Stun selon le rang : 1.5/1.75/2/2.25/2.5s
            double[] stunSec={1.5,1.75,2.0,2.25,2.5};
            final int stunTicks=(int)(stunSec[getLevel()-1]*20);
            final int r=4;
            // Délai 0.5s (10 ticks) avant apparition de la cage — LoL
            fr.lolmc.util.VisualEffectUtil.groundRing(fcenter.getWorld(), fcenter, r,
                    Material.PURPLE_STAINED_GLASS, 20, 0.35f, 0.1f, 10L);
            new BukkitRunnable(){@Override public void run(){
                // Barreaux affichés (entités) : avant, de vrais blocs de fer étaient posés dans la
                // map, bloquaient le passage dans les deux sens et restaient si le serveur s'arrêtait
                fr.lolmc.util.VisualEffectUtil.groundRing(fcenter.getWorld(), fcenter.clone().add(0,0.05,0), r,
                        Material.IRON_BARS, 28, 0.95f, 1.4f, 60L);
                // Surveiller les ennemis qui touchent le périmètre pendant 3s (60 ticks)
                var cc=LolPlugin.getInstance().getCCManager();
                final java.util.Set<UUID> stunned=new java.util.HashSet<>();
                new BukkitRunnable(){
                    int t=0;
                    @Override public void run(){
                        if(t>=60){cancel();return;}
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
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,7.2); if(tgt==null){cancelCast();c.sendActionBar(Component.text("☠ Aucune cible visée",NamedTextColor.GRAY));return;}
            // Wiki : 175/250/325 (+65/70/75 % AP), jusqu'à ×2 selon les PV manquants (max à 66.7 %)
            double[] base=fr.lolmc.util.Balance.base("r_veigar",new double[]{175,250,325});int rr=Math.min(getLevel()-1,2);
            double baseDmg=base[rr]+s.getFinalAP()*new double[]{0.65,0.70,0.75}[rr];
            // Orbe à tête chercheuse : les dégâts dépendent des PV manquants À L'IMPACT
            final org.bukkit.entity.LivingEntity ftgt=tgt;
            TargetingUtil.launchHoming(c, c.getEyeLocation(), tgt, 10.0,
                    new org.bukkit.inventory.ItemStack(Material.NETHER_STAR), 1.0f, Material.PURPLE_STAINED_GLASS, true, () -> burstHit(c, s, ftgt, baseDmg));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1.5f, 0.6f);
        }

        private void burstHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double baseDmg){
            double missingPct=1.0-(TargetingUtil.getRealHealth(tgt)/Math.max(1, TargetingUtil.getRealMaxHealth(tgt)));
            double dmg=baseDmg*(1.0+Math.min(1.0, missingPct/0.6667));
            tgt.getWorld().strikeLightningEffect(tgt.getLocation());
            fr.lolmc.util.VisualEffectUtil.impactBurst(tgt.getWorld(),
                    tgt.getLocation().add(0,1,0), Material.PURPLE_STAINED_GLASS, 0.42f, 0.6, 12, 10L);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            onChampionHit(c, s, tgt);
            if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ EXPLOSION PRIMORDIALE!",NamedTextColor.DARK_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_veigar",new double[]{175,250,325});int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts (+%.0f%%AP), jusqu'à ×2 selon PV manquants.",base[r]+s.getFinalAP()*new double[]{0.65,0.70,0.75}[r],new double[]{65,70,75}[r]);
        }
    }
}