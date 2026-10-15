package fr.lolmc.champion.impl.adc;

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

public final class MissFortune extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public MissFortune() {
        super("missfortune", "Miss Fortune", ChampionRole.ADC,
            new ChampionStats(625,55,0,25,33,0.656,0,325,5.5,3.75));
        getStats().setGrowthStats(100,2.4,4,1.1,0.03,0.65);
        setLolAttackRange(550); // stats LoL (wiki)
    }
    @Override public void resetState(java.util.UUID id) { lastTarget.remove(id); }
    // Passif de Fanfaronne (wiki) : après 4 s sans subir de dégâts non périodiques, +30-50 de
    // vitesse, puis +60-100 au bout de 3 s de plus ; l'actif donne tout de suite le maximum
    private double strutApplied = 0;
    private long strutForcedFrom = 0;
    public static void tickStrut(Player p, fr.lolmc.champion.base.BaseChampion ch) {
        if (!(ch instanceof MissFortune mf)) return;
        int w = mf.getLevelSystem().getAbilityRank(2);
        long quiet = mf.getHPSystem().msSinceLastDamage();
        double want = 0;
        if (w > 0) {
            boolean forced = mf.strutForcedFrom > 0 && System.currentTimeMillis() - mf.strutForcedFrom < quiet;
            if (forced || quiet >= 7000) want = new double[]{60,70,80,90,100}[w-1];
            else if (quiet >= 4000) want = new double[]{30,35,40,45,50}[w-1];
        }
        if (want != mf.strutApplied) {
            mf.getStats().addBonusMoveSpeed(want - mf.strutApplied);
            mf.strutApplied = want;
        }
    }
    @Override public void resetAllState() { lastTarget.clear(); }

    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(3.75, ResourceSystem.ResourceType.MANA, 300, 40, 8, 0.8);
    }

    // Passif Love Tap : première AA sur une nouvelle cible inflige 50-110% AD bonus physiques
    private static final java.util.Map<java.util.UUID, java.util.UUID> lastTarget = new java.util.concurrent.ConcurrentHashMap<>();

    static class AA extends BasicAttackAbility {
        AA(){super("missfortune",Material.CROSSBOW,5.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            java.util.UUID prev = lastTarget.get(c.getUniqueId());
            if (prev == null || !prev.equals(tgt.getUniqueId())) {
                // Nouvelle cible : Love Tap, 49-105% AD selon niveau (LoL)
                var cm0=LolPlugin.getInstance().getChampionManager();
                int lvl=cm0.hasChampion(c)?cm0.getChampion(c).getLevelSystem().getLevel():1;
                // Données du jeu : 50 % AD, +10 % aux niveaux 4, 7, 9, 11 et 13 (100 % dès 13)
                double lovePct=0.50;
                for(int bp : new int[]{4,7,9,11,13}) if(lvl>=bp) lovePct+=0.10;
                if (!(tgt instanceof Player)) lovePct *= 0.5;     // moitié contre les sbires
                double bonusDmg = s.getFinalAD() * lovePct;
                fr.lolmc.util.TargetingUtil.dealDamage(c, tgt, bonusDmg, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
                fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                        tgt.getLocation().add(0,1.5,0), Material.PINK_STAINED_GLASS, 0.25f, 5L);
                lastTarget.put(c.getUniqueId(), tgt.getUniqueId());
                // Love Tap réduit la recharge de Fanfaronnade (W) de 2 s (données du jeu ; manquait)
                if (cm0.hasChampion(c)) {
                    var w = cm0.getChampion(c).getAbility(2);
                    if (w != null) w.reduceCooldown(c, 2.0);
                }
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_missfortune","Doublé",Material.FLINT_AND_STEEL,AbilitySlot.Q,
            new double[]{7,6,5,4,3},25,0,DamageType.PHYSICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : tir sur 1 cible qui ricoche sur celle derrière. 1ère: 20-100+100%AD. 2ème: 20-80+85%AD +crit
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,6.2); if(tgt==null){cancelCast();c.sendActionBar(Component.text("🔫 Aucune cible",NamedTextColor.GRAY));return;}
            // Wiki : 20-120 (+100 % AD +35 % AP) ; la balle ricoche avec les mêmes dégâts, critique
            // (×1.5) si la 1re cible meurt
            double[] base=fr.lolmc.util.Balance.base("q_missfortune",new double[]{20,45,70,95,120});double d1=base[getLevel()-1]+s.getFinalAD()+s.getFinalAP()*0.35;
            // Balle à tête chercheuse (1400 unités/s), puis ricochet vers la cible derrière
            final org.bukkit.entity.LivingEntity ftgt=tgt;
            TargetingUtil.launchHoming(c, c.getEyeLocation(), tgt, 14.0,
                    new org.bukkit.inventory.ItemStack(Material.IRON_NUGGET), 0.6f, Material.WHITE_STAINED_GLASS, true, () -> doubleUpHit(c, ftgt, d1));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1.5f);
        }

        private void doubleUpHit(Player c, org.bukkit.entity.LivingEntity tgt, double d1){
            TargetingUtil.dealDamage(c, tgt, d1, TargetingUtil.DmgType.PHYSICAL);
            double d2 = (tgt.isDead() || TargetingUtil.getRealHealth(tgt) <= 0) ? d1*1.5 : d1;
            // Ricochet vers un ennemi DERRIÈRE la 1re cible (cône), champions en priorité (avant :
            // l'ennemi le plus proche, même devant ou à côté)
            org.bukkit.entity.LivingEntity second=null; double best=Double.MAX_VALUE;
            org.bukkit.util.Vector fwd=tgt.getLocation().toVector().subtract(c.getLocation().toVector()).setY(0);
            for(var __t : TargetingUtil.entitiesInRadius(c, tgt.getLocation(), 5.0)){
                if(__t.equals(tgt)) continue;
                org.bukkit.util.Vector to=__t.getLocation().toVector().subtract(tgt.getLocation().toVector()).setY(0);
                if(fwd.lengthSquared()>1e-6 && to.lengthSquared()>1e-6 && fwd.angle(to)>Math.toRadians(45)) continue;
                double dd=to.length()-(__t instanceof Player ? 100 : 0);
                if(dd<best){best=dd;second=__t;}
            }
            if(second!=null){
                final org.bukkit.entity.LivingEntity sec=second; final double fd2=d2;
                TargetingUtil.launchHoming(c, tgt.getLocation().add(0,1,0), second, 14.0,
                        new org.bukkit.inventory.ItemStack(Material.IRON_NUGGET), 0.6f, Material.WHITE_STAINED_GLASS, true,
                        () -> TargetingUtil.dealDamage(c, sec, fd2, TargetingUtil.DmgType.PHYSICAL));
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_missfortune",new double[]{20,45,70,95,120});
            return String.format("Balle: %.0f (+100%%AD +35%%AP), ricoche sur la cible derrière (critique si la 1re meurt).",base[getLevel()-1]+s.getFinalAD()+s.getFinalAP()*0.35);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_missfortune","Strut",Material.LEATHER_BOOTS,AbilitySlot.W,
            new double[]{12,12,12,12,12},0,0,DamageType.TRUE);
            resourceCost = 45;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : +40-100 % de vitesse d'attaque pendant 4 s (+ vitesse de déplacement)
            double asBonus=new double[]{0.40,0.55,0.70,0.85,1.00}[getLevel()-1];
            s.addBonusAttackSpeed(asBonus);
            new BukkitRunnable(){@Override public void run(){ s.addBonusAttackSpeed(-asBonus); }}.runTaskLater(LolPlugin.getInstance(), 80L);
            // L'actif donne tout de suite le bonus maximal de Fanfaronne (jusqu'aux prochains dégâts)
            var cmS=LolPlugin.getInstance().getChampionManager();
            if(cmS.hasChampion(c) && cmS.getChampion(c) instanceof MissFortune mf){
                mf.strutForcedFrom=System.currentTimeMillis();
                tickStrut(c, mf);
            }
            c.sendActionBar(Component.text("💃 Fanfaronnade 4s!",NamedTextColor.LIGHT_PURPLE));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "4s: +40-100% vitesse d'attaque et vitesse de déplacement.";}
    }

    static class E extends BaseAbility {
        E(){super("e_missfortune","Pluie de Balles",Material.GUNPOWDER,AbilitySlot.E,
            new double[]{18,17,16,15,14},25,3,DamageType.MAGICAL);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : zone au sol visée, dégâts magiques toutes les 0.25s sur 2s + ralentit 40-60%
            Location loc=TargetingUtil.getAimedGroundLocation(c, 10.0); // portée 1000
            // Total 70/100/130/160/190 sur 8 ticks + 120% AP total (15% AP/tick)
            double[] totalBase={70,100,130,160,190};
            double perTick=totalBase[getLevel()-1]/8.0+s.getFinalAP()*0.15;
            fr.lolmc.util.VisualEffectUtil.groundRing(loc.getWorld(), loc, 3.5,
                    Material.PURPLE_STAINED_GLASS, 18, 0.35f, 0.1f, 40L);
            new BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>=8){cancel();return;} // 8 ticks sur 2s
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 3.5)){
                        TargetingUtil.dealDamage(c, __t, perTick, TargetingUtil.DmgType.MAGICAL);
                        if(__t instanceof Player __p)
                            fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p, 0.40+0.06*s.getFinalAP()/100.0, 10); // 40 % (+6 %/100 AP)
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(loc.getWorld(),
                            loc, Material.PURPLE_STAINED_GLASS, 0.2f, 3.0, 5, 5L);
                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),5L,5L); // délai de 0.25 s, puis toutes les 0.25 s
            c.getWorld().playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] totalBase={70,100,130,160,190};
            return String.format("Zone visée: %.0f dégâts magiques sur 2s (+120%%AP) + slow 40%%.",totalBase[getLevel()-1]+s.getFinalAP()*1.2);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_missfortune","Déluge de Balles",Material.NETHERITE_HOE,AbilitySlot.R,
            new double[]{120,110,100},25,8,DamageType.PHYSICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : canalise 3s, ~8 vagues de balles en CÔNE devant. Chaque vague = 75% AD + 35% AP (peut critiquer)
            c.sendActionBar(Component.text("🔫 DÉLUGE DE BALLES! 3s",NamedTextColor.RED));
            // Wiki : 14/16/18 vagues, 20/30/40 (+60 % AD +25 % AP) par vague, cône de 30° sur 1450
            double perWave=new double[]{20,30,40}[Math.min(getLevel()-1,2)]
                    +s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_missfortune","ad",0.6)+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_missfortune","ap",0.25);
            int[] waveCount={14,16,18};
            final int totalWaves=waveCount[Math.min(getLevel()-1,2)];
            // Canalisation (wiki) : cône FIXE dans la direction du lancer, interrompue si Miss
            // Fortune se déplace ou est neutralisée (avant : elle marchait en arrosant où elle visait)
            final Location origin=c.getLocation().clone();
            final org.bukkit.util.Vector aim=c.getEyeLocation().getDirection().setY(0);
            if(aim.lengthSquared()<1e-6) aim.setX(1);
            aim.normalize();
            var ccR=LolPlugin.getInstance().getCCManager();
            new BukkitRunnable(){
                int waves=0;
                @Override public void run(){
                    if(waves>=totalWaves || !c.isOnline() || c.getGameMode()==GameMode.SPECTATOR
                            || !c.getWorld().equals(origin.getWorld()) || c.getLocation().distanceSquared(origin)>1.0
                            || (ccR!=null && (ccR.isStunned(c.getUniqueId()) || ccR.isAirborne(c.getUniqueId())))){
                        cancel();return;
                    }
                    var targets=new java.util.ArrayList<org.bukkit.entity.LivingEntity>();
                    for(var __t : TargetingUtil.entitiesInRadius(c, origin, 14.5)){
                        org.bukkit.util.Vector to=__t.getLocation().toVector().subtract(origin.toVector()).setY(0);
                        if(to.lengthSquared()>1e-6 && aim.angle(to)<=Math.toRadians(17)) targets.add(__t);
                    }
                    for(var __t : targets){
                        double dmg=perWave;
                        // Chaque vague peut critiquer
                        // Critique : +30 % des dégâts critiques au-delà de 100 % (×1.3 avec 200 %)
                        if(Math.random()<s.getFinalCritChance()) dmg*=1.0+0.3*(s.getFinalCritDamage()-1.0);
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                    }
                    // Balles qui partent dans le cône (purement visuelles : la vague touche toute la zone)
                    for(int k=0;k<3;k++){
                        var d=aim.clone().rotateAroundY(Math.toRadians((Math.random()-0.5)*34));
                        TargetingUtil.launchSkillshot(c, c.getEyeLocation(), d, 14.5, 0.2, true, 35.0,
                                new org.bukkit.inventory.ItemStack(Material.IRON_NUGGET), 0.4f, null, null, null);
                    }
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1.5f);
                    waves++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,Math.max(1L,(long)(60.0/totalWaves))); // étalé sur ~3s
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double perWave=new double[]{20,30,40}[Math.min(getLevel()-1,2)]
                    +s.getFinalAD()*fr.lolmc.util.Balance.ratio("r_missfortune","ad",0.6)+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_missfortune","ap",0.25);
            int[] waveCount={14,16,18};int n=waveCount[Math.min(getLevel()-1,2)];
            return String.format("Canalise 3s: %d vagues en cône, %.0f dégâts/vague (peut critiquer).",n,perWave);
        }
    }
}
