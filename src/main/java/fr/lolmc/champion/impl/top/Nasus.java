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
import java.util.*;

public final class Nasus extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Nasus() {
        super("nasus", "Nasus", ChampionRole.TOP,
            new ChampionStats(650,67,0,34,32,0.638,0,350,1.25,9));
        getStats().setGrowthStats(104,4,4.7,2.05,0.0348,0.9);
        setLolAttackRange(125); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(9, ResourceSystem.ResourceType.MANA, 326, 62, 7.45, 0.5);
    }

    // Stacks Q globaux par UUID
    public static final Map<UUID,Integer> qStacks=new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_Q_STACKS = Integer.MAX_VALUE; // pas de plafond dans LoL (avant : 500)
    /** Réinitialise les stacks de ce joueur (fin de partie / déconnexion). */
    @Override public void resetState(UUID id){ qStacks.remove(id); }
    @Override public void resetAllState(){ qStacks.clear(); }

    static class AA extends BasicAttackAbility {
        AA(){super("nasus",Material.BONE,2.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            soulEater(c, dmg);
        }
    }

    /** Passif Mangeur d'âmes : vol de vie de 10/15/20 % (niveaux 1/7/13), attaques et Frappe siphon. */
    static void soulEater(Player c, double dmg) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(c)) return;
        int lvl = cm.getChampion(c).getLevelSystem().getLevel();
        double vamp = lvl >= 13 ? 0.20 : (lvl >= 7 ? 0.15 : 0.10);
        cm.getChampion(c).getHPSystem().heal(dmg * vamp);
    }

    static class Q extends BaseAbility {
        Q(){super("q_nasus","Frappe Siphon",Material.BONE,AbilitySlot.Q,
            new double[]{7.5,6.5,5.5,4.5,3.5},5,0,DamageType.PHYSICAL);
            resourceCost = 20;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){cancelCast();c.sendActionBar(Component.text("💀 Aucune cible",NamedTextColor.GRAY));return;}
            // LoL : AD + bonus par rang (30/50/70/90/110) + stacks permanents
            double[] bonus={30,50,70,90,110}; // wiki
            int stacks=qStacks.getOrDefault(c.getUniqueId(),0);
            double dmg=s.getFinalAD()+bonus[getLevel()-1]+stacks;
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            soulEater(c, dmg); // Frappe siphon est une attaque : le vol de vie s'applique (oublié avant)
            // Kill vérifié après les dégâts (avant : sur les dégâts bruts, sans l'armure)
            boolean kills = tgt.isDead() || TargetingUtil.getRealHealth(tgt) <= 0;
            // Stacks (wiki) : +10 sur champion, gros sbire (canon/super) ou gros monstre, +4 sinon
            if(kills){
                String mt = fr.lolmc.game.MinionManager.getMinionTypeTag(tgt);
                boolean big = (tgt instanceof Player) || fr.lolmc.game.JungleManager.isJungleMonster(tgt)
                        || "cannon".equals(mt) || "super".equals(mt);
                qStacks.merge(c.getUniqueId(), big?10:4, Integer::sum);
                if(qStacks.get(c.getUniqueId()) > MAX_Q_STACKS) qStacks.put(c.getUniqueId(), MAX_Q_STACKS);
                c.sendActionBar(Component.text("💀 Stacks Q: "+qStacks.get(c.getUniqueId()),NamedTextColor.GOLD));
            }
            fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                    tgt.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.3f, 4L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_SKELETON_HURT, 1f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] bonus={30,50,70,90,110};
            return String.format("AD + %.0f + stacks. +4/kill (+10 champion/gros). RESET attaque.",bonus[getLevel()-1]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_nasus","Flétrissure",Material.WITHER_ROSE,AbilitySlot.W,
            new double[]{15,14,13,12,11},6.0,0,DamageType.MAGICAL);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,7.5); if(tgt==null){cancelCast();c.sendActionBar(Component.text("💀 Aucune cible",NamedTextColor.GRAY));return;}
            // LoL : ralentit 35% montant jusqu'à 95% sur 5s (+ ralentit vitesse d'attaque)
            // Approximation : lenteur progressive (amplifier croît) sur 5s
            new BukkitRunnable(){
                int sec=0;
                @Override public void run(){
                    if(sec>=5 || tgt.isDead()){cancel();return;}
                    // Wiki : +3/6/9/12/15 % par seconde jusqu'à 47/59/71/83/95 %
                    double[] maxSlow={0.47,0.59,0.71,0.83,0.95}; double[] perSec={0.03,0.06,0.09,0.12,0.15};
                    int r=getLevel()-1;
                    double slow=maxSlow[r]-perSec[r]*(4-sec);
                    fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(tgt, slow, 25);
                    // Vitesse d'attaque réduite de 75 % du ralentissement (données du jeu ; manquait)
                    var cmW=LolPlugin.getInstance().getChampionManager();
                    if(tgt instanceof Player wp && cmW.hasChampion(wp)){
                        var st=cmW.getChampion(wp).getStats(); double asMult=1.0-slow*0.75;
                        st.multiplyAS(asMult);
                        new BukkitRunnable(){@Override public void run(){ st.multiplyAS(1.0/asMult); }}.runTaskLater(LolPlugin.getInstance(),20L);
                    }
                    fr.lolmc.util.VisualEffectUtil.impact(tgt.getWorld(),
                            tgt.getLocation().add(0,1,0), Material.GRAY_STAINED_GLASS, 0.26f, 8L);
                    sec++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("💀 Flétri! Ralentissement croissant",NamedTextColor.DARK_PURPLE));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Ralentit 35% montant à 95% sur 5s (déplacement + vitesse d'attaque).";}
    }

    static class E extends BaseAbility {
        E(){super("e_nasus","Feu Spirituel",Material.SOUL_LANTERN,AbilitySlot.E,
            new double[]{12,12,12,12,12},0,3,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : burst initial + dot/s pendant 5s + réduction armure 30-50%
            Location loc=TargetingUtil.getAimedGroundLocation(c, 7.0); // 650 unités
            int rank=getLevel()-1;
            double[] burstBase={50,80,110,140,170};
            double[] dotBase={10,16,22,28,34};
            double burst=burstBase[rank]+s.getFinalAP()*0.6;
            double perSec=dotBase[rank]+s.getFinalAP()*0.12;
            // Délai 0.264s (5 ticks) avant le burst initial — LoL
            c.getWorld().playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
            new BukkitRunnable(){@Override public void run(){
                for(var __t : TargetingUtil.entitiesInRadius(c, loc, 4.0))
                    TargetingUtil.dealDamage(c, __t, burst, TargetingUtil.DmgType.MAGICAL);
            }}.runTaskLater(LolPlugin.getInstance(), 5L);
            // Zone persistante 5s : dot/s + réduction d'armure 25-45 %
            // (la potion Faiblesse n'avait aucun effet sur les dégâts LoL)
            double[] shredPct={0.30,0.35,0.40,0.45,0.50}; // wiki
            double shred=shredPct[rank];
            var shredded=new java.util.HashMap<java.util.UUID, Double>(); // champion -> armure retirée
            var cmE=LolPlugin.getInstance().getChampionManager();
            new BukkitRunnable(){
                int sec=0;
                @Override public void run(){
                    if(sec>=5){
                        // Fin de la zone : rendre exactement l'armure retirée
                        for(var e : shredded.entrySet()){
                            Player p=org.bukkit.Bukkit.getPlayer(e.getKey());
                            if(p!=null && cmE.hasChampion(p)) cmE.getChampion(p).getStats().addBonusArmor(e.getValue());
                        }
                        cancel();return;
                    }
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 4.0)){
                        TargetingUtil.dealDamage(c, __t, perSec, TargetingUtil.DmgType.MAGICAL);
                        if(__t instanceof Player __p && cmE.hasChampion(__p) && !shredded.containsKey(__p.getUniqueId())){
                            var st=cmE.getChampion(__p).getStats();
                            double amount=Math.max(0, st.getFinalArmor())*shred;
                            st.addBonusArmor(-amount);
                            shredded.put(__p.getUniqueId(), amount);
                        }
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(loc.getWorld(),
                            loc, Material.YELLOW_STAINED_GLASS, 0.3f, 3.0, 8, 15L);
                    sec++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            int rank=getLevel()-1;double[] burstBase={50,80,110,140,170};double[] dotBase={10,16,22,28,34};
            return String.format("Burst %.0f (+60%%AP) puis %.0f/s sur 5s (+12%%AP). Armure -30 à -50%%.",burstBase[rank]+s.getFinalAP()*0.6,dotBase[rank]+s.getFinalAP()*0.12);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_nasus","Furie des Sables",Material.GOLDEN_HELMET,AbilitySlot.R,
            new double[]{120,100,80},120,3,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            int rank=Math.min(getLevel()-1,2);
            // LoL : +HP 300/450/600, +Armure/MR 40/55/70, 15s
            double[] hpBonus={300,450,600};
            double[] pctArr={0.03,0.04,0.05};
            double pctHP=pctArr[rank]; // 3/4/5% HP max/s
            double[] resists={40,55,70}; // wiki
            double resist=resists[Math.min(rank,2)];
            c.sendActionBar(Component.text("☀ Furie des Sables! (15s, Q -50%% CD)",NamedTextColor.GOLD));
            // Bonus HP réel (300/450/600 selon rang), jamais appliqué auparavant
            var cm=LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(c)) {
                cm.getChampion(c).getStats().addBonusHP(hpBonus[rank]);
                cm.getChampion(c).getStats().addBonusArmor(resist);
                cm.getChampion(c).getStats().addBonusMR(resist);
            }
            // Réduction CD du Q de 50%% pendant la durée
            final BaseAbility qAbil = cm.hasChampion(c) ? cm.getChampion(c).getAbility(1) : null;
            if(qAbil!=null) qAbil.setDynamicCooldown(Math.max(0.5, qAbil.getCurrentCooldown(s)*0.5));
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=300){
                        if(qAbil!=null) qAbil.setDynamicCooldown(-1); // restaurer CD normal
                        if(cm.hasChampion(c)) {
                            cm.getChampion(c).getStats().addBonusHP(-hpBonus[rank]);
                            cm.getChampion(c).getStats().addBonusArmor(-resist);
                            cm.getChampion(c).getStats().addBonusMR(-resist);
                        }
                        cancel();return;
                    }
                    for(var __t : TargetingUtil.entitiesInRadius(c, c.getLocation(), 4.0)){
                        // PV max réels de la cible (avant : 200 pour tout sbire ou monstre)
                        double tgtMaxHP = TargetingUtil.getRealMaxHealth(__t);
                        double dmg=Math.min(tgtMaxHP*(pctHP+s.getFinalAP()/100*0.01), 240); // plafond 240/s (wiki)
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                    }
                    tick+=20;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,20L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return "15s : +HP/Armure/MR, draine 3-5% PV max des ennemis autour/s, Q -50% CD.";
        }
    }
}