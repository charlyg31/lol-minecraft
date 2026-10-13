package fr.lolmc.champion.impl.top;

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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public final class Garen extends BaseChampion {
    public Garen() {
        super("garen", "Garen", ChampionRole.TOP,
            new ChampionStats(690,69,0,38,32,0.625,0,340,1.75,8));
        getStats().setGrowthStats(98,4.5,4.2,1.55,0.0365,0.5);
        setLolAttackRange(175); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(8, ResourceSystem.ResourceType.NONE, 0, 0, 0, 0);
    }

    // Passif Persévérance (wiki) : 1.5 % → 10.1 % des PV max toutes les 5 s (selon le niveau),
    // perdu 8 s après avoir subi des dégâts. Appelé chaque seconde (avant : 1.5 %/s dès le niveau 1).
    public static void tickGarenPassive(fr.lolmc.champion.base.BaseChampion champ) {
        if (champ.getHPSystem().msSinceLastDamage() < 8000L) return;
        int lvl = champ.getLevelSystem().getLevel();
        double pctPer5s = 0.015 + (0.101 - 0.015) * (lvl - 1) / 17.0;
        champ.getHPSystem().heal(champ.getHPSystem().getMaxHP() * pctPer5s / 5.0);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("garen",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);}
    }

    // Q — Jugement Décisif
    static class Q extends BaseAbility {
        Q(){super("q_garen","Jugement Décisif",Material.GOLDEN_SWORD,AbilitySlot.Q,
            new double[]{8,7.5,7,6.5,6},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){cancelCast();c.sendActionBar(Component.text("⚔ Aucune cible à portée",NamedTextColor.GRAY));return;}
            // LoL : 30/55/80/105/130 + 50% AD bonus, silence 1.5s, boost vitesse
            double[] base=fr.lolmc.util.Balance.base("q_garen",new double[]{30,65,100,135,170});
            double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_garen","ad",0.5);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            // Silence 1.5s (30 ticks) via CCManager
            var cc = LolPlugin.getInstance().getCCManager();
            if (cc != null) cc.silence(tgt, 30);
            // +35 % vitesse pendant 1.4/1.95/2.5/3.05/3.6 s (wiki)
            double[] msDur={1.4,1.95,2.5,3.05,3.6};
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(c, 0.35, (int)(msDur[getLevel()-1]*20));
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("⚠ Silence — Garen Q",NamedTextColor.RED));
            fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                    tgt.getLocation().add(0,1,0), Material.WHITE_STAINED_GLASS, 0.3f, 4L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_garen",new double[]{30,65,100,135,170});
            return String.format("%.0f dégâts physiques (+50%%AD). Silence 1.5s + 35%% vitesse 4s.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_garen","ad",0.5));
        }
    }

    // W — Courage
    static class W extends BaseAbility {
        W(){super("w_garen","Courage",Material.SHIELD,AbilitySlot.W,
            new double[]{23,21,19,17,15},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 25-41 % de réduction de dégâts 4 s ; bouclier 65-145 (+18 % PV bonus) et
            // 60 % de ténacité pendant 0.75 s
            double[] dr={0.25,0.29,0.33,0.37,0.41};
            double red=dr[getLevel()-1];
            s.addPercentDamageReduction(red);
            double[] shieldBase={65,85,105,125,145};
            double shield=shieldBase[getLevel()-1]+s.getBonusHP()*0.18;
            s.addShield(shield);
            s.addTenacity(0.60);
            new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ s.addShield(-shield); s.addTenacity(-0.60); }}
                    .runTaskLater(LolPlugin.getInstance(), 15L);
            new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ s.addPercentDamageReduction(-red); }}
                    .runTaskLater(LolPlugin.getInstance(), 80L);
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    c.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.3f, 0.5, 8, 8L);
            c.getWorld().playSound(c.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.2f);
            c.sendActionBar(Component.text("🛡 Courage actif!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] dr={25,29,33,37,41};double[] sb={65,85,105,125,145};
            return String.format("-%.0f%% de dégâts 4s + bouclier %.0f (+18%% PV bonus) et 60%% ténacité 0.75s.",
                    dr[getLevel()-1], sb[getLevel()-1]+s.getBonusHP()*0.18);
        }
    }

    // E — Tournoiement (Jugement) : 7 frappes sur 3s, +25% au plus proche
    static class E extends BaseAbility {
        E(){super("e_garen","Jugement",Material.COMPASS,AbilitySlot.E,
            new double[]{9,8,7,6,5},5,4,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.sendActionBar(Component.text("⚔ Jugement!",NamedTextColor.GOLD));
            // LoL : dégâts par tick = base[rang] + ratio AD, 7 frappes réparties sur 3s (~tous les 8.5 ticks)
            // Wiki : 4/7/10/13/16 (+40-52 % AD) par tour, 7 tours ; +25 % sur l'ennemi le plus proche
            double[] baseTick={4,7,10,13,16};
            double[] adRatio={0.40,0.43,0.46,0.49,0.52};
            int rank=getLevel()-1;
            double perTick=baseTick[rank]+s.getFinalAD()*adRatio[rank];
            new BukkitRunnable(){
                int hits=0;
                @Override public void run(){
                    if(hits>=7){cancel();return;}
                    var around=TargetingUtil.enemiesAround(c, 3.6);
                    org.bukkit.entity.LivingEntity nearest=null; double nd=Double.MAX_VALUE;
                    for(var e2 : around){ double d=e2.getLocation().distanceSquared(c.getLocation()); if(d<nd){nd=d;nearest=e2;} }
                    for(var __t : around){
                        double dmg=perTick;
                        if(__t.equals(nearest)) dmg*=1.25;
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                    }
                    fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                            c.getLocation().add(0,1,0), 1.8, Material.WHITE_STAINED_GLASS, 12, 0.3f, 0.1f, 4L);
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.2f);
                    hits++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,9L); // 7 frappes × 9 ticks ≈ 3.15s (LoL: 3s)
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] baseTick={4,7,10,13,16};double[] adRatio={0.40,0.43,0.46,0.49,0.52};int r=getLevel()-1;
            double total=(baseTick[r]+s.getFinalAD()*adRatio[r])*7;
            return String.format("7 tours sur 3s, total %.0f dégâts. Ennemi le plus proche: +25%%.",total);
        }
    }

    // R — Exécution
    static class R extends BaseAbility {
        R(){super("r_garen","Exécution",Material.NETHERITE_SWORD,AbilitySlot.R,
            new double[]{120,100,80},25,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Justice Démacienne : porté plus loin (sort à distance moyenne), cible visée
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,5.5); if(tgt==null){cancelCast();c.sendActionBar(Component.text("☠ Aucune cible visée",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("r_garen",new double[]{125,200,275});
            int r=Math.min(getLevel()-1,2);
            // Reveal de la cible pendant 1s (20 ticks) au début du cast
            tgt.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,20,0,false,false));
            c.sendActionBar(Component.text("☠ Justice Démacienne en préparation...",NamedTextColor.DARK_RED));
            final org.bukkit.entity.LivingEntity ftgt=tgt; final int fr_=r;
            // Délai de cast 0.5s (10 ticks) — LoL
            new BukkitRunnable(){@Override public void run(){
                if(ftgt.isDead()||!ftgt.isValid()){return;}
                var cm=LolPlugin.getInstance().getChampionManager();
                double missingHP=0;
                if((ftgt instanceof Player && cm.hasChampion((Player)ftgt))){var hp=cm.getChampion((Player)ftgt).getHPSystem();missingHP=hp.getMaxHP()-hp.getCurrentHP();}
                double[] missPct={0.25,0.30,0.35}; // wiki
                double dmg=base[fr_]+missingHP*missPct[fr_];
                ftgt.getWorld().strikeLightningEffect(ftgt.getLocation());
                fr.lolmc.util.VisualEffectUtil.impactBurst(ftgt.getWorld(),
                        ftgt.getLocation().add(0,1,0), Material.WHITE_STAINED_GLASS, 0.4f, 0.6, 12, 10L);
                TargetingUtil.dealDamage(c, ftgt, dmg, TargetingUtil.DmgType.TRUE);
                if(ftgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ DEMACIA! Exécution de Garen!",NamedTextColor.DARK_RED));
            }}.runTaskLater(LolPlugin.getInstance(), 10L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_garen",new double[]{125,200,275});int r=Math.min(getLevel()-1,2);double[] mp={25,30,35};return String.format("%.0f dégâts vrais + %.0f%% des PV manquants (cast 0.5s).",base[r],mp[r]);
        }
    }
}