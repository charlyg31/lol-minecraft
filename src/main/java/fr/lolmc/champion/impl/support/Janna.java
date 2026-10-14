package fr.lolmc.champion.impl.support;

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

public final class Janna extends BaseChampion {
    public Janna() {
        super("janna", "Janna", ChampionRole.SUPPORT,
            new ChampionStats(570,47,0,28,30,0.625,0,325,5.5,5.5));
        getStats().setGrowthStats(90,2.5,4.5,1.3,0.03,0.55);
        setLolAttackRange(550); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(5.5, ResourceSystem.ResourceType.MANA, 360, 50, 11.5, 0.4);
    }

    // Passif Vent Arrière (wiki) : Janna et ses alliés à moins de 12 blocs (1200 unités) ont +6 %
    // de vitesse de déplacement. Appelé chaque seconde : effet d'une seconde, renouvelé.
    // (avant : « 8 % de la vitesse d'attaque » ajoutés en vitesse plate, soit quasiment rien)
    public static void tickJannaPassive(Player janna) {
        var cc = LolPlugin.getInstance().getCCManager();
        var tm = LolPlugin.getInstance().getTeamManager();
        var cm = LolPlugin.getInstance().getChampionManager();
        for (Player p : janna.getWorld().getPlayers()) {
            if (!cm.hasChampion(p) || !(p.equals(janna) || tm.areAllies(janna, p))) continue;
            if (p.getLocation().distanceSquared(janna.getLocation()) > 12 * 12) continue;
            cc.speedPct(p, 0.06, 20);
        }
        // Passif de Zéphyr (données du jeu) : +6-10 % (+2 % par 100 AP) de vitesse pour Janna
        // (avant : potion Vitesse II quand le sort touchait)
        if (cm.hasChampion(janna)) {
            var champ = cm.getChampion(janna);
            int wr = champ.getLevelSystem().getAbilityRank(2);
            if (wr > 0) cc.speedPct(janna, new double[]{0.06,0.07,0.08,0.09,0.10}[wr-1]
                    + 0.0002 * champ.getStats().getFinalAP(), 20);
        }
    }

    /** Contrôler un champion avec Bourrasque réduit de 20 % la recharge restante de l'E (données du jeu). */
    static void eRefundOnCC(Player janna) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(janna)) return;
        var e = cm.getChampion(janna).getAbility(3);
        if (e != null && e.isOnCooldown(janna)) e.reduceCooldown(janna, e.getRemainingCooldown(janna) * 0.20);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("janna",Material.FEATHER,5.5f,DamageType.MAGICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_janna","Bourrasque Hurlante",Material.WHITE_DYE,AbilitySlot.Q,
            new double[]{14,14,14,14,14},20,2,DamageType.MAGICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot tornade en ligne, 60/90/120/150/180 + 50% AP, knockup les ennemis traversés
            double[] base=fr.lolmc.util.Balance.base("q_janna",new double[]{55,90,125,160,195});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_janna","ap",0.5);
            // Projectile réel : tornade de 1100 unités à ~900/s qui traverse
            TargetingUtil.launchSkillshot(c, c.getLocation().add(0,0.6,0), c.getEyeLocation().getDirection().setY(0), 11.0, 1.2, true, 9.0,
                    new org.bukkit.inventory.ItemStack(Material.WHITE_STAINED_GLASS), 1.5f, Material.WHITE_STAINED_GLASS,
                    __t -> {
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        fr.lolmc.LolPlugin.getInstance().getCCManager().setAirborne(__t, 10); // projeté 0.5 s (wiki, sans charge)
                        if(__t instanceof Player __p){
                            __p.sendActionBar(Component.text("🌪 Bourrasque! Knockup",NamedTextColor.WHITE));
                            eRefundOnCC(c);
                        }
                    }, null);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_janna",new double[]{55,90,125,160,195});
            return String.format("Skillshot tornade: %.0f dégâts (+50%%AP) + knockup les ennemis.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_janna","ap",0.5));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_janna","Zéphyr",Material.CYAN_DYE,AbilitySlot.W,
            new double[]{8,7.5,7,6.5,6},20,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : envoie un élémentaire qui inflige des dégâts + ralentit. 60/105/150/195/240 + 60% AP
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,6.2); if(tgt==null){cancelCast();c.sendActionBar(Component.text("💨 Aucune cible",NamedTextColor.GRAY));return;} // 550 unités
            double[] base=fr.lolmc.util.Balance.base("w_janna",new double[]{55,85,115,145,175});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_janna","ap",0.5);
            // Élémentaire d'air à tête chercheuse (1600 unités/s)
            final org.bukkit.entity.LivingEntity ftgt=tgt;
            TargetingUtil.launchHoming(c, c.getEyeLocation(), tgt, 16.0,
                    new org.bukkit.inventory.ItemStack(Material.FEATHER), 0.8f, Material.WHITE_STAINED_GLASS, true, () -> zephyrHit(c, s, ftgt, dmg));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 1f, 1.4f);
        }

        private void zephyrHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            if(tgt instanceof Player __p)
                fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p, new double[]{0.20,0.24,0.28,0.32,0.36}[getLevel()-1]+0.06*s.getFinalAP()/100.0, 40); // wiki
            fr.lolmc.util.VisualEffectUtil.impactBurst(tgt.getWorld(),
                    tgt.getLocation().add(0,1,0), Material.WHITE_STAINED_GLASS, 0.28f, 0.5, 8, 6L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_janna",new double[]{55,85,115,145,175});
            return String.format("%.0f dégâts (+50%%AP) + ralentit 3s. Janna gagne de la vitesse.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_janna","ap",0.5));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_janna","Oeil de la Tempête",Material.LIGHT_BLUE_DYE,AbilitySlot.E,
            new double[]{16,15,14,13,12},0,0,DamageType.TRUE);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : bouclier sur allié (ou soi) 75-175 + 50% AP + AD bonus 5s
            Player dest = (t!=null && LolPlugin.getInstance().getTeamManager().areAllies(c,t)) ? t : c; // allié visé, sinon soi
            double[] shieldBase={80,120,160,200,240};
            double shield=shieldBase[getLevel()-1]+s.getFinalAP()*0.55;
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(dest)) {
                var destStats = cm.getChampion(dest).getStats();
                destStats.addShield(shield);
                // Wiki : bouclier 4 s, +10-30 (+10 % AP) AD tant qu'il tient
                double bonusAD=new double[]{10,15,20,25,30}[getLevel()-1]+s.getFinalAP()*0.10;
                destStats.addBonusAD(bonusAD);
                new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ destStats.addShield(-shield); destStats.addBonusAD(-bonusAD); }}
                        .runTaskLater(LolPlugin.getInstance(), 80L);
            }
            fr.lolmc.util.VisualEffectUtil.impactBurst(dest.getWorld(),
                    dest.getLocation().add(0,1,0), Material.LIGHT_BLUE_STAINED_GLASS, 0.3f, 0.5, 8, 6L);
            dest.sendActionBar(Component.text("🌀 Œil de la Tempête! Bouclier "+(int)shield,NamedTextColor.AQUA));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] shieldBase={80,120,160,200,240};
            return String.format("Bouclier %.0f (+55%%AP) + AD bonus sur soi ou un allié 4s.",shieldBase[getLevel()-1]+s.getFinalAP()*0.55);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_janna","Mousson",Material.HEART_OF_THE_SEA,AbilitySlot.R,
            new double[]{130,115,100},20,4,DamageType.TRUE);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : repousse les ennemis autour, puis canalise 3s en soignant les alliés (100/150/200/0.5s)
            for(var __t : TargetingUtil.enemiesAround(c, 7.0)){ // rayon 700 (wiki)
                Vector kb=__t.getLocation().toVector().subtract(c.getLocation().toVector()).normalize().multiply(3.0);
                kb.setY(0.5); __t.setVelocity(kb);
                if(__t instanceof Player __p)__p.sendActionBar(Component.text("🌪 MOUSSON! Repoussé",NamedTextColor.WHITE));
            }
            // Soin sur 3s (canalisation)
            // Total 150/225/300 +150%%AP sur 3s (6 ticks de 0.5s)
            double[] healTotal={300,450,600};int r=Math.min(getLevel()-1,2); // wiki
            double healPerTick=(healTotal[r]+s.getFinalAP()*1.5)/6.0; // réparti sur 6 ticks
            var cm=LolPlugin.getInstance().getChampionManager();
            new BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>=6){cancel();return;} // 6 ticks de 0.5s = 3s
                    // Soigne Janna et les alliés proches
                    if(cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(healPerTick);
                    for(var ent : c.getWorld().getNearbyLivingEntities(c.getLocation(),7)){
                        if(ent instanceof Player ally && !ally.equals(c) && cm.hasChampion(ally)
                                && LolPlugin.getInstance().getTeamManager().areAllies(c, ally)) // pas les ennemis
                            cm.getChampion(ally).getHPSystem().heal(healPerTick);
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                            c.getLocation().add(0,1,0), Material.PINK_STAINED_GLASS, 0.25f, 2.0, 5, 8L);
                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,10L);
            c.sendActionBar(Component.text("🌪 MOUSSON! Soin de zone",NamedTextColor.WHITE));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] healTotal={300,450,600};int r=Math.min(getLevel()-1,2);
            return String.format("Repousse les ennemis + soigne les alliés (total %.0f, +150%%AP) sur 3s.",healTotal[r]+s.getFinalAP()*1.5);
        }
    }
}