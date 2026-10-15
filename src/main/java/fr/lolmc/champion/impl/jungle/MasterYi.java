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
    @Override public void resetState(java.util.UUID id) { aaCount.remove(id); aaLast.remove(id); wujuUntil.remove(id); highlanderUntil.remove(id); }
    @Override public void resetAllState() { aaCount.clear(); aaLast.clear(); wujuUntil.clear(); highlanderUntil.clear(); }
    /** Méditation en cours : réduction de dégâts appliquée (moitié contre les tourelles, données du jeu). */
    private static final java.util.Map<java.util.UUID, Double> meditate = new java.util.concurrent.ConcurrentHashMap<>();
    public static double meditateDR(java.util.UUID id) { return meditate.getOrDefault(id, 0.0); }
    /** Coup double : instant du dernier effet (les effets durent 4 s). */
    private static final java.util.Map<java.util.UUID, Long> aaLast = new java.util.concurrent.ConcurrentHashMap<>();
    /** Highlander : fin de l'effet (prolongée de 7 s par élimination). */
    private static final java.util.Map<java.util.UUID, Long> highlanderUntil = new java.util.concurrent.ConcurrentHashMap<>();
    /** Ajoute un effet de Coup double (3 max, 4 s). */
    static void addDoubleStrikeStack(java.util.UUID id) {
        long now = System.currentTimeMillis();
        Long last = aaLast.put(id, now);
        if (last == null || now - last > 4000) aaCount.put(id, 0);
        aaCount.merge(id, 1, (a, b) -> Math.min(3, a + b));
    }
    /**
     * Effets à l'impact d'un coup de Frappe alpha à {@code eff} d'efficacité (75 % ou 18.75 %) :
     * Style Wuju et effets à l'impact des objets.
     */
    static void alphaOnHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg, double eff) {
        if (wujuUntil.getOrDefault(c.getUniqueId(), 0L) > System.currentTimeMillis()) {
            var cmW = LolPlugin.getInstance().getChampionManager();
            int er = cmW.hasChampion(c) ? Math.max(1, cmW.getChampion(c).getAbility(3).getLevel()) : 1;
            DamageUtil.damageEntity(c, tgt, (new double[]{20,25,30,35,40}[Math.min(er,5)-1] + s.getBonusAD() * 0.35) * eff, false, DamageUtil.Type.TRUE);
        }
        fr.lolmc.item.ItemEffects.applyOnHit(c, tgt, dmg, eff);
    }
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
        AA(){super("masteryi",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);} // mêlée (avant : 6 blocs)
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            // Style Wuju : vrais dégâts bonus à l'impact (18-50 + 35 % AD bonus)
            if (wujuUntil.getOrDefault(c.getUniqueId(), 0L) > System.currentTimeMillis()) {
                var cmW = LolPlugin.getInstance().getChampionManager();
                int er = cmW.hasChampion(c) ? Math.max(1, cmW.getChampion(c).getAbility(3).getLevel()) : 1;
                double[] tb = {20,25,30,35,40}; // wiki
                DamageUtil.damageEntity(c, tgt, tb[Math.min(er,5)-1] + s.getBonusAD() * 0.35, false, DamageUtil.Type.TRUE);
            }
            // Frappe alpha : chaque attaque réduit sa recharge de 1 s (données du jeu ; manquait)
            var cmQ = LolPlugin.getInstance().getChampionManager();
            if (cmQ.hasChampion(c)) {
                var q = cmQ.getChampion(c).getAbility(1);
                if (q != null) q.reduceCooldown(c, 1.0);
            }
            // Coup double (wiki) : 1 effet par attaque (4 s, 3 max) ; à 3, l'attaque suivante frappe deux fois
            long now = System.currentTimeMillis();
            Long last = aaLast.get(c.getUniqueId());
            int stacks = (last == null || now - last > 4000) ? 0 : aaCount.getOrDefault(c.getUniqueId(), 0);
            if (stacks < 3) { addDoubleStrikeStack(c.getUniqueId()); }
            else {
                aaCount.put(c.getUniqueId(), 0); aaLast.remove(c.getUniqueId());
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
            new double[]{20,19.5,19,18.5,18},15,0,DamageType.TRUE);
            resourceCost = 50; resourceCosts = new double[]{50,55,60,65,70};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,6.65); if(tgt==null){cancelCast();return;}
            // Wiki : 4 marques, une toutes les 0.231 s : la cible, puis l'ennemi non marqué le plus
            // proche (600 unités) ; sans autre ennemi, une cible déjà marquée est reprise pour 25 %
            // des dégâts et 18.75 % des effets à l'impact (75 % sur la 1re marque de chaque cible).
            // Peut critiquer. Yi est intouchable (~1.09 s) puis réapparaît devant la cible.
            fr.lolmc.util.Untargetable.apply(c, 22);
            int rank=getLevel()-1;
            double[] base=fr.lolmc.util.Balance.base("q_masteryi",new double[]{20,40,60,80,100});
            final double full=base[rank]+s.getFinalAD()*0.70;
            final double monsterBonus=new double[]{60,85,110,135,160}[rank];
            final org.bukkit.entity.LivingEntity primary=tgt;
            final java.util.List<org.bukkit.entity.LivingEntity> marked=new java.util.ArrayList<>();
            new org.bukkit.scheduler.BukkitRunnable(){
                int n=0; org.bukkit.entity.LivingEntity last=primary;
                @Override public void run(){
                    org.bukkit.entity.LivingEntity next=null;
                    if(n==0) next=primary;
                    else if(n<4 && c.isOnline()){
                        double best=36;
                        for(var e : TargetingUtil.entitiesInRadius(c, last.getLocation(), 6.0)){
                            if(marked.contains(e)) continue;
                            double d=e.getLocation().distanceSquared(last.getLocation());
                            if(d<best){best=d;next=e;}
                        }
                        if(next==null){
                            var alive=marked.stream().filter(e -> e.isValid() && !e.isDead()).toList();
                            if(!alive.isEmpty()) next=alive.get(n % alive.size());
                        }
                    }
                    if(next==null || n>=4 || !c.isOnline()){ finish(); cancel(); return; }
                    boolean repeat=marked.contains(next);
                    if(!repeat) marked.add(next);
                    double dmg=repeat ? full*0.25 : full;
                    if(Math.random()<s.getFinalCritChance()) dmg*=s.getFinalCritDamage(); // critique (wiki)
                    if(!repeat && fr.lolmc.game.JungleManager.isJungleMonster(next)) dmg+=monsterBonus;
                    TargetingUtil.dealDamage(c, next, dmg, TargetingUtil.DmgType.PHYSICAL);
                    alphaOnHit(c, s, next, dmg, repeat ? 0.1875 : 0.75);
                    fr.lolmc.util.VisualEffectUtil.impactBurst(next.getWorld(),
                            next.getLocation().add(0,1,0), Material.WHITE_STAINED_GLASS, 0.25f, 0.5, 6, 5L);
                    last=next; n++;
                }
                void finish(){
                    if(!c.isOnline()) return;
                    var anchor=primary.isValid() ? primary : last;
                    Location dest=safeTeleport(c.getLocation(), anchor.getLocation());
                    c.teleport(dest);
                    fr.lolmc.LolPlugin.getInstance().getRuneManager().onDash(c); // Impact soudain
                }
            }.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_masteryi",new double[]{20,40,60,80,100});
            return String.format("4 marques : %.0f dégâts physiques (+70%%AD) par cible, 25%% sur une cible reprise. Peut critiquer, intouchable.",base[getLevel()-1]+s.getFinalAD()*0.70);
        }
    }
    static class W extends BaseAbility {
        W(){super("w_masteryi","Méditation",Material.GOLDEN_APPLE,AbilitySlot.W,
            new double[]{10,10,10,10,10},0,0,DamageType.TRUE);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : canalisation de 4 s, -70 % de dégâts la 1re demi-seconde puis -45 à -55 % ; soin
            // 120-440 (+100 % AP) toutes les 0.5 s. Se déplacer interrompt la canalisation (avant :
            // Yi pouvait marcher et combattre avec la réduction de dégâts et le soin)
            double[] drArr={0.45,0.475,0.50,0.525,0.55};
            final double dr=drArr[getLevel()-1];
            double[] heals={120,200,280,360,440};
            final double perTick=(heals[getLevel()-1]+s.getFinalAP()*1.0)/8.0;
            final Location anchor=c.getLocation().clone();
            s.addPercentDamageReduction(0.70);
            meditate.put(c.getUniqueId(), 0.70);
            new org.bukkit.scheduler.BukkitRunnable(){
                int t=0; double applied=0.70;
                void end(){ s.addPercentDamageReduction(-applied); applied=0; meditate.remove(c.getUniqueId()); cancel(); }
                @Override public void run(){
                    var cm=LolPlugin.getInstance().getChampionManager();
                    t+=2;
                    if(t>80 || !c.isOnline() || !cm.hasChampion(c) || c.getGameMode()==GameMode.SPECTATOR
                            || !c.getWorld().equals(anchor.getWorld())
                            || c.getLocation().distanceSquared(anchor)>0.25){ end(); return; }
                    if(t==10){ s.addPercentDamageReduction(dr-applied); applied=dr; meditate.put(c.getUniqueId(), dr); }
                    if(t%20==0) addDoubleStrikeStack(c.getUniqueId()); // 1 effet de Coup double par seconde (wiki)
                    if(t%10==0){
                        // Soin augmenté de 1 % par 1 % de PV manquants (jusqu'à +100 %, données du jeu)
                        var hp=cm.getChampion(c).getHPSystem();
                        hp.heal(perTick*(2.0-hp.getHPRatio()));
                    }
                }}.runTaskTimer(LolPlugin.getInstance(), 2L, 2L);
            c.sendActionBar(Component.text("🧘 Méditation 4s (bouger interrompt)...",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Canalise 4s (bouger interrompt) : régénère des PV, réduit les dégâts de 70% puis 45-55%.";}
    }

    static class E extends BaseAbility {
        E(){super("e_masteryi","Style Wuju",Material.BLAZE_ROD,AbilitySlot.E,
            new double[]{14,14,14,14,14},0,0,DamageType.TRUE);
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
            new double[]{85,85,85},0,0,DamageType.TRUE);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 7 s (+7 s par élimination de champion), +25/45/65 % de vitesse d'attaque,
            // +40/50/60 % de vitesse, insensible aux ralentissements
            int r=Math.min(getLevel()-1,2);
            final double asBonus=new double[]{0.25,0.45,0.65}[r];
            final double ms=new double[]{0.40,0.50,0.60}[r];
            final java.util.UUID id=c.getUniqueId();
            highlanderUntil.put(id, System.currentTimeMillis()+7000L);
            s.addBonusAttackSpeed(asBonus);
            var cc=fr.lolmc.LolPlugin.getInstance().getCCManager();
            new org.bukkit.scheduler.BukkitRunnable(){
                @Override public void run(){
                    if(!c.isOnline() || System.currentTimeMillis()>=highlanderUntil.getOrDefault(id,0L)){
                        s.addBonusAttackSpeed(-asBonus); highlanderUntil.remove(id); cancel(); return;
                    }
                    cc.speedPct(c, ms, 6);
                    c.removePotionEffect(PotionEffectType.SLOWNESS);
                    cc.clearSlows(id);
                }}.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "7s (+7 s par élimination) : +25-65% vitesse d'attaque, +40-60% vitesse, insensible aux ralentissements. Passif : les éliminations réduisent les sorts de 70%.";}
    }
    /** Appelé par EntityDeathListener sur kill ou assist de MasterYi pendant Highlander. */
    public static void onKillDuringHighlander(Player yi) {
        // Passif du Highlander (wiki) : dès que R est appris, un takedown réduit de 70 % la recharge
        // actuelle de Q, W et E (avant : Q/E remis à zéro, et seulement pendant l'ultime)
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(yi)) return;
        var champ = cm.getChampion(yi);
        if (champ.getLevelSystem().getAbilityRank(4) <= 0) return;
        // Highlander actif : +7 s par élimination de champion (wiki)
        Long until = highlanderUntil.get(yi.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) highlanderUntil.put(yi.getUniqueId(), until + 7000L);
        for (int i = 1; i <= 3; i++) {
            var a = champ.getAbility(i);
            if (a != null) a.reduceCooldown(yi, a.getRemainingCooldown(yi) * 0.70);
        }
        yi.sendActionBar(Component.text("⚡ HIGHLANDER — Sorts -70% de recharge!", NamedTextColor.GOLD));
    }
}