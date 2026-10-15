package fr.lolmc.champion.impl.jungle;

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
import org.bukkit.util.Vector;

public final class Warwick extends BaseChampion {
    public Warwick() {
        super("warwick", "Warwick", ChampionRole.JUNGLE,
                new ChampionStats(620,65,0,33,32,0.638,0,335,1.25,4));
        getStats().setGrowthStats(99,2.5,4.4,2.05,0.02,0.75);
        setLolAttackRange(125); // stats LoL (wiki)
    }
    // Passif de W, Traque sanglante (données du jeu) : dès que W est appris, révèle les ennemis sous
    // 50 % de PV, +35-65 % de vitesse vers eux et +70-110 % de vitesse d'attaque contre eux.
    // Avant : actif sans W, potion Vitesse II, et sans le bonus de vitesse d'attaque.
    private static final java.util.Map<java.util.UUID, Double> bloodHuntAS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, ChampionStats> bloodHuntStats = new java.util.concurrent.ConcurrentHashMap<>();

    public static void tickWarwickPassive(Player ww) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var cmW = LolPlugin.getInstance().getChampionManager();
        if (!cmW.hasChampion(ww)) return;
        int wRank = cmW.getChampion(ww).getLevelSystem().getAbilityRank(2);
        boolean woundedInMelee = false;
        if (wRank <= 0) { setBloodHuntAS(ww, 0); return; }
        for (Player nearby : ww.getWorld().getPlayers()) {
            if (nearby.equals(ww)) continue;
            if (!tm.areEnemies(ww, nearby)) continue;
            if (ww.getLocation().distance(nearby.getLocation()) > 25) continue;
            var cm = LolPlugin.getInstance().getChampionManager();
            if (!cm.hasChampion(nearby)) continue;
            double hpRatio = cm.getChampion(nearby).getHPSystem().getHPRatio();
            if (hpRatio < 0.50) {
                // Révéler via GLOWING
                nearby.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.GLOWING, 25, 0, false, false));
                // Vitesse bonus vers la cible blessée
                double dist = ww.getLocation().distance(nearby.getLocation());
                if (dist < 12) {
                    LolPlugin.getInstance().getCCManager().speedPct(ww, new double[]{0.35,0.425,0.50,0.575,0.65}[wRank-1], 25);
                }
                if (dist < 4) woundedInMelee = true;
                ww.sendActionBar(net.kyori.adventure.text.Component.text(
                    "🐺 Instinct de Chasse! " + nearby.getName() + " (" + (int)(hpRatio*100) + "% HP)",
                    net.kyori.adventure.text.format.NamedTextColor.DARK_RED));
            }
        }
        setBloodHuntAS(ww, woundedInMelee ? new double[]{0.70,0.80,0.90,1.00,1.10}[wRank-1] : 0);
    }

    /** Applique (ou retire) le bonus de vitesse d'attaque de Traque sanglante. */
    private static void setBloodHuntAS(Player ww, double want) {
        var id = ww.getUniqueId();
        double prev = bloodHuntAS.getOrDefault(id, 0.0);
        if (prev == want) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        var st = bloodHuntStats.get(id);
        if (st != null) st.addBonusAttackSpeed(-prev);
        bloodHuntAS.remove(id); bloodHuntStats.remove(id);
        if (want > 0 && cm.hasChampion(ww)) {
            st = cm.getChampion(ww).getStats();
            st.addBonusAttackSpeed(want);
            bloodHuntAS.put(id, want); bloodHuntStats.put(id, st);
        }
    }

    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(4, ResourceSystem.ResourceType.MANA, 280, 35, 7.45, 0.6);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("warwick",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            // Passif Faim Éternelle (wiki) : 6-60.76 (+15 % AD bonus +10 % AP) dégâts magiques à
            // l'impact ; sous 50 % de PV il se soigne de 100 % de ces dégâts (250 % sous 25 %).
            var cm=LolPlugin.getInstance().getChampionManager();
            if(!cm.hasChampion(c)) return;
            var champ=cm.getChampion(c);
            int lvl=champ.getLevelSystem().getLevel();
            double bonus=6+(60.76-6)*(lvl-1)/17.0+s.getBonusAD()*0.15+s.getFinalAP()*0.10;
            TargetingUtil.dealDamage(c, tgt, bonus, TargetingUtil.DmgType.MAGICAL);
            double ratio=champ.getHPSystem().getHPRatio();
            if(ratio<0.50) champ.getHPSystem().heal(bonus*(ratio<0.25?2.5:1.0));
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_warwick","Crocs de la Bête",Material.BONE,AbilitySlot.Q,
                new double[]{8,7.5,7,6.5,6},20,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : bond + morsure, 100% AD + 6-10% PV max de la cible, soigne 100% des dégâts post-mitigation
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,4.3); if(tgt==null){cancelCast();c.sendActionBar(Component.text("🐺 Aucune cible",NamedTextColor.GRAY));return;}
            // Bond vers la cible
            dashTo(c, tgt, -1.0);
            double[] pct={0.06,0.07,0.08,0.09,0.10};

            // PV max LoL de la cible (l'attribut Minecraft vaut 40 pour un champion)
            double tgtMaxHealth = TargetingUtil.getRealMaxHealth(tgt);

            // Wiki : 6-10 % des PV max de la cible (+120 % AD +100 % AP), soin 25-75 % des dégâts
            double dmg=s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_warwick","ad",1.2)+s.getFinalAP()+tgtMaxHealth*pct[getLevel()-1];
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            double[] healPct={0.25,0.375,0.50,0.625,0.75};
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(dmg*healPct[getLevel()-1]);
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    c.getLocation().add(0,1,0), Material.PINK_STAINED_GLASS, 0.25f, 0.5, 6, 6L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] pct={6,7,8,9,10};
            double[] hp={25,37.5,50,62.5,75};
            return String.format("Bond + morsure: %.0f dégâts (+120%%AD +100%%AP) + %.0f%% PV max cible. Soigne %.1f%% des dégâts.",
                    s.getFinalAD()*1.2+s.getFinalAP(),pct[getLevel()-1],hp[getLevel()-1]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_warwick","Meute de Prédateurs",Material.RED_WOOL,AbilitySlot.W,
                new double[]{80,70,60,50,40},0,0,DamageType.TRUE);
            resourceCost = 55;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(c, new double[]{0.35,0.425,0.50,0.575,0.65}[getLevel()-1], 60); // wiki
            c.sendActionBar(Component.text("🐺 Meute actif!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Passif : ennemis sous 50% PV révélés, +35-65% vitesse vers eux et +70-110% vitesse d'attaque contre eux. Actif : boost vitesse 3s.";}
    }

    static class E extends BaseAbility {
        E(){super("e_warwick","Hurlement Primal",Material.SPIDER_EYE,AbilitySlot.E,
                new double[]{15,14,13,12,11},5,3,DamageType.PHYSICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 35-55 % de réduction de dégâts pendant 2.75 s
            double[] drArr={0.35,0.40,0.45,0.50,0.55};
            double dr=drArr[getLevel()-1];
            s.addPercentDamageReduction(dr);
            new BukkitRunnable(){@Override public void run(){ s.addPercentDamageReduction(-dr); }}
                    .runTaskLater(LolPlugin.getInstance(), 55L);
            c.sendActionBar(Component.text("🛡 Hurlement Primal! Réduction de dégâts",NamedTextColor.GOLD));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 1f);
            new BukkitRunnable(){
                @Override public void run(){
                    // Peur 1 s sur tous les ennemis proches, sbires et monstres compris (wiki ; avant :
                    // champions seulement)
                    for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                        fr.lolmc.LolPlugin.getInstance().getCCManager().stun(__t, 20); // peur : ni déplacement ni action
                        if(__t instanceof Player __p)
                            __p.sendActionBar(Component.text("😱 Peur! (Hurlement Primal)",NamedTextColor.DARK_RED));
                    }
                    fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                            c.getLocation().add(0,1,0), Material.GRAY_STAINED_GLASS, 0.5f, 6L);
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.5f, 0.6f);
                }
            }.runTaskLater(LolPlugin.getInstance(),55L); // hurlement à la fin des 2,75 s (avant : 1,5 s)
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Réduit les dégâts reçus (35-55%) 2.5s, puis terrifie + ralentit 90% les ennemis autour 1s.";}
    }

    static class R extends BaseAbility {
        R(){super("r_warwick","Emprise Infinie",Material.RED_WOOL,AbilitySlot.R,
                new double[]{110,90,70},25,0,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : bond dans la direction visée sur 250 % de la vitesse de déplacement ; le 1er
            // champion ennemi percuté est supprimé (avant : sort ciblé à 8 blocs)
            double leap=Math.max(3.0, s.getFinalMovementSpeed()*2.5/100.0);
            org.bukkit.util.Vector dir=c.getLocation().getDirection().setY(0);
            if(dir.lengthSquared()<1e-6) dir=new org.bukkit.util.Vector(1,0,0);
            dir.normalize();
            org.bukkit.entity.LivingEntity tgt=null;
            var cmL=LolPlugin.getInstance().getChampionManager();
            for(double d=0.5; d<=leap && tgt==null; d+=0.5){
                Location pt=c.getLocation().add(dir.clone().multiply(d));
                for(var e : TargetingUtil.entitiesInRadius(c, pt, 1.3))
                    if(e instanceof Player p && cmL.hasChampion(p)){ tgt=e; break; }
            }
            if(tgt==null){
                Location end=safeTeleport(c.getLocation(), c.getLocation().add(dir.clone().multiply(leap)));
                end.setYaw(c.getLocation().getYaw()); end.setPitch(c.getLocation().getPitch());
                c.teleport(end);
                c.sendActionBar(Component.text("🐺 Emprise Infinie : aucun champion percuté",NamedTextColor.GRAY));
                return;
            }
            dashTo(c, tgt, -0.8);
            double[] base=fr.lolmc.util.Balance.base("r_warwick",new double[]{175,350,525});int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("r_warwick","ad",1.67); // AD bonus (wiki)
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(dmg); // soin 100% au R
            // Suppression 1.5s (30 ticks) — vrai CC verrouillant (stun)
            var cc=LolPlugin.getInstance().getCCManager();
            if(cc!=null) cc.stun(tgt, 30);
            if(tgt instanceof Player __p){
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,20,false,true));
                __p.sendActionBar(Component.text("🐺 EMPRISE INFINIE! Supprimé 1.5s!",NamedTextColor.DARK_RED));
            }
            tgt.setVelocity(new Vector(0,0.2,0));
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    c.getLocation().add(0,1,0), Material.PINK_STAINED_GLASS, 0.3f, 1.0, 10, 8L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.5f, 0.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_warwick",new double[]{175,350,525});int r=Math.min(getLevel()-1,2);
            return String.format("Bond (250%% de la vitesse) : supprime le 1er champion percuté 1.5s, %.0f dégâts (+167%%AD bonus), soigne 100%%.",base[r]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("r_warwick","ad",1.67));
        }
    }
}