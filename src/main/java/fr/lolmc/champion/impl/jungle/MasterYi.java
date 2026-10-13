package fr.lolmc.champion.impl.jungle;

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
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class MasterYi extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public MasterYi() {
        super("masteryi", "Master Yi", ChampionRole.JUNGLE,
            new ChampionStats(640,65,0,33,32,0.679,0,355,1.75,7.5));
        getStats().setGrowthStats(105,2.5,5,2.05,0.025,0.65);
        setLolAttackRange(175); // stats LoL (wiki)
    }
    @Override public void resetState(java.util.UUID id) { aaCount.remove(id); wujuUntil.remove(id); }
    @Override public void resetAllState() { aaCount.clear(); wujuUntil.clear(); }
    // Style Wuju actif (5 s) : vrais dégâts à chaque attaque
    private static final java.util.Map<java.util.UUID, Long> wujuUntil = new java.util.concurrent.ConcurrentHashMap<>();

    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(7.5, ResourceSystem.ResourceType.MANA, 251, 42, 7.25, 0.45);
    }

    // Passif Double Frappe (LoL) : la 4e attaque consécutive frappe deux fois (2e coup : 50 % AD physique)
    private static final java.util.Map<java.util.UUID, Integer> aaCount
        = new java.util.concurrent.ConcurrentHashMap<>();

    static class AA extends BasicAttackAbility {
        AA(){super("masteryi",Material.IRON_SWORD,6.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            // Style Wuju : vrais dégâts bonus à l'impact (18-50 + 35 % AD bonus)
            if (wujuUntil.getOrDefault(c.getUniqueId(), 0L) > System.currentTimeMillis()) {
                var cmW = LolPlugin.getInstance().getChampionManager();
                int er = cmW.hasChampion(c) ? Math.max(1, cmW.getChampion(c).getAbility(3).getLevel()) : 1;
                double[] tb = {20,25,30,35,40}; // wiki
                DamageUtil.damageEntity(c, tgt, tb[Math.min(er,5)-1] + s.getBonusAD() * 0.35, false, DamageUtil.Type.TRUE);
            }
            int count = aaCount.merge(c.getUniqueId(), 1, Integer::sum);
            if (count >= 4) {
                aaCount.put(c.getUniqueId(), 0);
                // Double Frappe : 2e coup = 50 % AD physiques (avant : vrais dégâts, à la 3e attaque)
                DamageUtil.damageEntity(c, tgt, s.getFinalAD() * 0.50, false, DamageUtil.Type.PHYSICAL);
                fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                        tgt.getLocation().add(0,1.5,0), Material.WHITE_STAINED_GLASS, 0.28f, 4L);
                c.sendActionBar(Component.text("⚔⚔ Double Frappe!", NamedTextColor.YELLOW));
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_masteryi","Frappe Alpha",Material.ENDER_PEARL,AbilitySlot.Q,
            new double[]{18,16,14,12,10},15,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,6.5); if(tgt==null){cancelCast();return;}
            Location dest=safeTeleport(c.getLocation(),tgt.getLocation());
            c.teleport(dest);
            double[] base=fr.lolmc.util.Balance.base("q_masteryi",new double[]{20,40,60,80,100});double dmg=base[getLevel()-1]+s.getFinalAD()*0.70; // wiki
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    tgt.getLocation(), Material.WHITE_STAINED_GLASS, 0.25f, 0.5, 6, 5L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_masteryi",new double[]{20,40,60,80,100});
            return String.format("%.0f dégâts physiques (+70%%AD). Dash sur la cible, intouchable.",base[getLevel()-1]+s.getFinalAD()*0.70);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_masteryi","Méditation",Material.GOLDEN_APPLE,AbilitySlot.W,
            new double[]{35,30,25,20,15},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : -70 % de dégâts la 1re demi-seconde puis -45 à -55 %, 4 s ; soin 120-440 (+100 % AP)
            double[] drArr={0.45,0.475,0.50,0.525,0.55};
            double dr=drArr[getLevel()-1];
            s.addPercentDamageReduction(0.70);
            new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ s.addPercentDamageReduction(dr-0.70); }}
                    .runTaskLater(LolPlugin.getInstance(), 10L);
            new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ s.addPercentDamageReduction(-dr); }}
                    .runTaskLater(LolPlugin.getInstance(), 80L);
            double[] heals={120,200,280,360,440};
            double perTick=(heals[getLevel()-1]+s.getFinalAP()*1.0)/8.0;
            new org.bukkit.scheduler.BukkitRunnable(){ int n=0;
                @Override public void run(){
                    var cm=LolPlugin.getInstance().getChampionManager();
                    if(n++>=8 || !c.isOnline() || !cm.hasChampion(c)){ cancel(); return; }
                    cm.getChampion(c).getHPSystem().heal(perTick);
                }}.runTaskTimer(LolPlugin.getInstance(), 10L, 10L);
            c.sendActionBar(Component.text("🧘 Méditation 4s...",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Régénère des PV pendant 4s. Réduit les dégâts de 70% puis 45-55%.";}
    }

    static class E extends BaseAbility {
        E(){super("e_masteryi","Style Wuju",Material.BLAZE_ROD,AbilitySlot.E,
            new double[]{18,17,16,15,14},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Avant : potion de Force (aucun vrai dégât) et vol de vie retiré (ancienne version)
            wujuUntil.put(c.getUniqueId(), System.currentTimeMillis() + 5000L);
            c.sendActionBar(Component.text("⚔ Style Wuju 5s! Vrais dégâts à chaque attaque",NamedTextColor.YELLOW));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] tb={20,25,30,35,40};
            return String.format("5s: +%.0f dégâts vrais par AA (+35%%AD bonus).",tb[getLevel()-1]+s.getBonusAD()*0.35);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_masteryi","Highlander",Material.FEATHER,AbilitySlot.R,
            new double[]{85,70,55},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 7 s, +25/45/65 % de vitesse d'attaque et +40/50/60 % de vitesse
            int r=Math.min(getLevel()-1,2);
            double asBonus=new double[]{0.25,0.45,0.65}[r];
            s.addBonusAttackSpeed(asBonus);
            new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ s.addBonusAttackSpeed(-asBonus); }}
                    .runTaskLater(LolPlugin.getInstance(), 140L);
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(c, new double[]{0.40,0.50,0.60}[r], 140);
            c.removePotionEffect(PotionEffectType.SLOWNESS); // immunité aux ralentissements
            fr.lolmc.LolPlugin.getInstance().getCCManager().clearSlows(c.getUniqueId());
            c.sendActionBar(Component.text("⚡ HIGHLANDER 7s!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "7s: +25-65% vitesse d'attaque, +40-60% vitesse. Passif: les takedowns réduisent les sorts de 70%.";}
    }

    /** Appelé par EntityDeathListener sur kill ou assist de MasterYi pendant Highlander. */
    public static void onKillDuringHighlander(Player yi) {
        // Passif du Highlander (wiki) : dès que R est appris, un takedown réduit de 70 % la recharge
        // actuelle de Q, W et E (avant : Q/E remis à zéro, et seulement pendant l'ultime)
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(yi)) return;
        var champ = cm.getChampion(yi);
        if (champ.getLevelSystem().getAbilityRank(4) <= 0) return;
        for (int i = 1; i <= 3; i++) {
            var a = champ.getAbility(i);
            if (a != null) a.reduceCooldown(yi, a.getRemainingCooldown(yi) * 0.70);
        }
        yi.sendActionBar(Component.text("⚡ HIGHLANDER — Sorts -70% de recharge!", NamedTextColor.GOLD));
    }
}