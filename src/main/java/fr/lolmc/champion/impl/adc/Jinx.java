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

public final class Jinx extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Jinx() {
        super("jinx", "Jinx", ChampionRole.ADC,
                new ChampionStats(630,59,0,26,33,0.625,0,325,5.25,3.75));
        getStats().setGrowthStats(105,3.25,4.2,1.1,0.01,0.5);
        setLolAttackRange(525); // stats LoL (wiki)
    }
    // Passif Get Excited : kill/assist → +175% vitesse 6s (décroît après 3s)
    public static void getExcited(Player jinx) {
        fr.lolmc.LolPlugin.getInstance().getCCManager().speedDecaying(jinx, 1.75, 120); // +175 %, décroît sur 6 s (wiki)
        // +25 % de vitesse d'attaque pendant 6 s (données du jeu ; manquait)
        var cm = LolPlugin.getInstance().getChampionManager();
        if (cm.hasChampion(jinx)) {
            var st = cm.getChampion(jinx).getStats();
            st.addBonusAttackSpeed(0.25);
            new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ st.addBonusAttackSpeed(-0.25); }}
                    .runTaskLater(LolPlugin.getInstance(), 120L);
        }
        fr.lolmc.util.VisualEffectUtil.impactBurst(jinx.getWorld(),
            jinx.getLocation().add(0,1,0), Material.RED_STAINED_GLASS, 0.28f, 0.5, 8, 8L);
        jinx.sendActionBar(net.kyori.adventure.text.Component.text(
            "🎉 GET EXCITED! +175% vitesse 6s!", net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    @Override public void resetState(java.util.UUID id) { fishbonesMode.remove(id); powStacks.remove(id); }
    @Override public void resetAllState()               { fishbonesMode.clear(); powStacks.clear(); }

    // Mini-canon (wiki) : chaque attaque donne un stack (max 3, 2.5 s) ; bonus de vitesse
    // d'attaque 15-65 % au 1er stack, +7.5-32.5 % par stack suivant
    private record Pow(int stacks, double applied, long until) {}
    private static final java.util.Map<java.util.UUID, Pow> powStacks = new java.util.concurrent.ConcurrentHashMap<>();

    static void powPowHit(Player c, ChampionStats s) {
        var cm = LolPlugin.getInstance().getChampionManager();
        int qr = cm.hasChampion(c) ? cm.getChampion(c).getLevelSystem().getAbilityRank(1) : 0;
        if (qr <= 0) return;
        Pow old = powStacks.get(c.getUniqueId());
        long now = System.currentTimeMillis();
        int stacks = (old != null && old.until() > now) ? Math.min(3, old.stacks() + 1) : 1;
        double first = new double[]{0.15,0.275,0.40,0.525,0.65}[qr-1];
        double extra = new double[]{0.075,0.1375,0.20,0.2625,0.325}[qr-1];
        double bonus = first + extra * (stacks - 1);
        s.addBonusAttackSpeed(bonus - (old != null ? old.applied() : 0));
        long until = now + 2500L;
        powStacks.put(c.getUniqueId(), new Pow(stacks, bonus, until));
        new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){
            Pow cur = powStacks.get(c.getUniqueId());
            if (cur != null && cur.until() == until) { s.addBonusAttackSpeed(-cur.applied()); powStacks.remove(c.getUniqueId()); }
        }}.runTaskLater(LolPlugin.getInstance(), 50L);
    }

    /** Retire le bonus de Mini-canon (passage à Fishbones). */
    static void clearPow(Player c, ChampionStats s) {
        Pow cur = powStacks.remove(c.getUniqueId());
        if (cur != null) s.addBonusAttackSpeed(-cur.applied());
    }
    private static final java.util.Map<java.util.UUID, Boolean> fishbonesMode
        = new java.util.concurrent.ConcurrentHashMap<>();
    /** Portée de base de Jinx (525 unités) et bonus de Fishbones (100-200 unités, wiki). */
    static final double BASE_RANGE = (525 + 130) / 100.0;
    static final double[] FISHBONES_BONUS = {1.0, 1.25, 1.5, 1.75, 2.0};

    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(3.75, ResourceSystem.ResourceType.MANA, 260, 50, 6.7, 1);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("jinx",Material.CROSSBOW,6.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            if (!fishbonesMode.getOrDefault(c.getUniqueId(), false)) { powPowHit(c, s); return; }
            // Mode Fishbones (wiki) : 20 mana par attaque, 110 % AD à la cible et autour d'elle
            if (fishbonesMode.getOrDefault(c.getUniqueId(), false)) {
                var cmF = LolPlugin.getInstance().getChampionManager();
                var res = cmF.hasChampion(c) ? cmF.getChampion(c).getResourceSystem() : null;
                if (res != null && !res.consume(20)) {
                    c.sendActionBar(Component.text("💥 Plus assez de mana pour Fishbones", NamedTextColor.GRAY));
                    return;
                }
                fr.lolmc.util.DamageUtil.damageEntity(c, tgt, dmg * 0.10, false, fr.lolmc.util.DamageUtil.Type.PHYSICAL);
                for (var nearby : fr.lolmc.util.TargetingUtil.entitiesInRadius(c, tgt.getLocation(), 2.5)) {
                    if (nearby.equals(tgt)) continue; // cible principale déjà touchée
                    fr.lolmc.util.DamageUtil.damageEntity(c, nearby, dmg * 1.10, false, fr.lolmc.util.DamageUtil.Type.PHYSICAL);
                }
                fr.lolmc.util.VisualEffectUtil.impact(tgt.getWorld(),
                    tgt.getLocation().add(0,0.5,0), Material.ORANGE_STAINED_GLASS, 0.3f, 4L);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_jinx","Choix des Armes",Material.TNT,AbilitySlot.Q,
                new double[]{0.9,0.9,0.9,0.9,0.9},25,3,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            boolean fish = !fishbonesMode.getOrDefault(c.getUniqueId(), false);
            fishbonesMode.put(c.getUniqueId(), fish);
            if (fish) {
                // Mode Fishbones (bazooka) : portée étendue +2 blocs, dégâts AoE
                c.sendActionBar(Component.text("💥 Fishbones! Bazooka activé (+portée, AoE)", NamedTextColor.RED));
                clearPow(c, s);
                s.multiplyAS(0.90); // Fishbones : -10 % de vitesse d'attaque (données du jeu ; manquait)
                // Augmenter la portée AA
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(c)) cm.getChampion(c).setAutoAttackRange(BASE_RANGE + FISHBONES_BONUS[getLevel()-1]);
            } else {
                // Mode Mini-canons : vitesse d'attaque
                c.sendActionBar(Component.text("⚡ Mini-canon! Chaque attaque augmente la vitesse d'attaque", NamedTextColor.YELLOW));
                s.multiplyAS(1/0.90);
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(c)) cm.getChampion(c).setAutoAttackRange(BASE_RANGE);
            }
            c.getWorld().playSound(c.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.5f, fish ? 0.7f : 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Toggle Fishbones/Mini-canons. Fishbones: +portée +AoE. Mini-canons: +AS.";}
    }

    static class W extends BaseAbility {
        W(){super("w_jinx","Zap!",Material.LIGHTNING_ROD,AbilitySlot.W,
                new double[]{8,7,6,5,4},25,0,DamageType.PHYSICAL);
            resourceCost = 40; resourceCosts = new double[]{40,45,50,55,60};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("w_jinx",new double[]{10,60,110,160,210});double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("w_jinx","ad",1.4);
            // Projectile réel : 1500 unités à 3300/s
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), c.getEyeLocation().getDirection(), 15.0, 0.6, false, 33.0, // missile : 1500 unités (données du jeu)
                    new org.bukkit.inventory.ItemStack(Material.LIGHTNING_ROD), 0.8f, Material.YELLOW_STAINED_GLASS,
                    main -> zapHit(c, s, main, dmg),
                    hits -> { if(hits.isEmpty()) c.sendActionBar(Component.text("⚡ Zap manqué!",NamedTextColor.GRAY)); });
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1.4f);
        }

        private void zapHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity main, double dmg){
            TargetingUtil.dealDamage(c, main, dmg, TargetingUtil.DmgType.PHYSICAL);
            // Slow 40/50/60/70/80% selon rang (2s = 40 ticks)
            if(main instanceof Player __p){
                fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p, new double[]{0.40,0.50,0.60,0.70,0.80}[getLevel()-1], 40); // wiki
            }
            fr.lolmc.util.VisualEffectUtil.impactBurst(main.getWorld(),
                    main.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.25f, 0.5, 6, 6L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_jinx",new double[]{10,60,110,160,210});
            return String.format("Skillshot: %.0f dégâts (+140%%AD) au 1er touché + slow 40-80%%.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("w_jinx","ad",1.4));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_jinx","Croque-Flammes!",Material.TRIPWIRE_HOOK,AbilitySlot.E,
                new double[]{24,20.5,17,13.5,10},25,2,DamageType.MAGICAL);
            resourceCost = 90;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 3 pièges posés en ligne perpendiculaire à Jinx au point visé ; armés après
            // 0.5 s, ils durent 5 s et explosent au contact d'un champion ennemi (dégâts magiques
            // et enracinement 1.5 s autour). Avant : explosion immédiate sur toute la zone.
            Location loc=TargetingUtil.getAimedGroundLocation(c, 9.0);
            double[] base=fr.lolmc.util.Balance.base("e_jinx",new double[]{90,140,190,240,290});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_jinx","ap",1);
            org.bukkit.util.Vector fwd=loc.toVector().subtract(c.getLocation().toVector()).setY(0);
            if(fwd.lengthSquared()<1e-6) fwd=c.getLocation().getDirection().setY(0);
            if(fwd.lengthSquared()<1e-6) fwd=new org.bukkit.util.Vector(1,0,0);
            fwd.normalize();
            org.bukkit.util.Vector side=new org.bukkit.util.Vector(-fwd.getZ(),0,fwd.getX());
            var traps=new java.util.ArrayList<Location>();
            for(int i=-1;i<=1;i++) traps.add(loc.clone().add(side.clone().multiply(1.5*i)));
            var cm=LolPlugin.getInstance().getChampionManager();
            new org.bukkit.scheduler.BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>110 || traps.isEmpty() || !c.isOnline()){cancel();return;}
                    if(ticks%10==0) for(var tr : traps)
                        fr.lolmc.util.VisualEffectUtil.impact(tr.getWorld(), tr.clone().add(0,0.2,0),
                                ticks<10 ? Material.YELLOW_STAINED_GLASS : Material.ORANGE_STAINED_GLASS, 0.35f, 10L);
                    if(ticks>=10) for(var it=traps.iterator(); it.hasNext();){
                        var tr=it.next();
                        boolean sprung=false;
                        for(var e : TargetingUtil.entitiesInRadius(c, tr, 1.0))
                            if(e instanceof Player p && cm.hasChampion(p) && p.getGameMode()!=GameMode.SPECTATOR){ sprung=true; break; }
                        if(!sprung) continue;
                        it.remove();
                        for(var __t : TargetingUtil.entitiesInRadius(c, tr, 1.5)){
                            TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                            if(__t instanceof Player __p){
                                fr.lolmc.LolPlugin.getInstance().getCCManager().root(__p, 30); // root 1.5 s (wiki)
                                __p.sendActionBar(Component.text("🦷 ENRACINÉ par les Croque-Flammes!",NamedTextColor.RED));
                            }
                        }
                        fr.lolmc.util.VisualEffectUtil.impactBurst(tr.getWorld(), tr, Material.MAGMA_BLOCK, 0.28f, 1.0, 6, 8L);
                        tr.getWorld().playSound(tr, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
                    }
                    ticks+=2;
                }
            }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
            c.getWorld().playSound(loc, Sound.BLOCK_TRIPWIRE_ATTACH, 1f, 1f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_jinx",new double[]{90,140,190,240,290});
            return String.format("3 pièges (5s) : au contact d'un champion, %.0f dégâts magiques + enracine 1.5s.",base[getLevel()-1]+s.getFinalAP());
        }
    }

    static class R extends BaseAbility {
        R(){super("r_jinx","Super Méga Roquette de la Mort!",Material.FIREWORK_ROCKET,AbilitySlot.R,
                new double[]{85,65,45},25,5,DamageType.PHYSICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Projectile réel : roquette (≈2000 unités/s) qui explose sur le 1er champion
            final Location from=c.getLocation();
            // Portée globale ; 1700 unités/s, puis 2200 après 1350 unités (données du jeu, wiki)
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), c.getEyeLocation().getDirection(), 250.0, 1.2, false,
                    travelled -> travelled < 13.5 ? 17.0 : 22.0,
                    new org.bukkit.inventory.ItemStack(Material.FIREWORK_ROCKET), 1.2f, Material.ORANGE_STAINED_GLASS,
                    e -> e instanceof Player, // explose sur le 1er champion (wiki), traverse les sbires
                    main -> rocketHit(c, s, main, from.distance(main.getLocation())),
                    (hits, at) -> { if(hits.isEmpty()) c.sendActionBar(Component.text("🚀 Roquette tirée (aucune cible)",NamedTextColor.GRAY)); });
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 0.6f);
        }

        private void rocketHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity main, double dist){
            // Wiki : 10 % → 100 % des dégâts sur les 1500 premières unités ; max 200/350/500
            // (+120 % AD bonus) + 25/30/35 % des PV manquants ; 80 % autour de la cible
            double distMult=Math.min(1.0, 0.1+0.9*dist/15.0);
            double[] baseMax=fr.lolmc.util.Balance.base("r_jinx",new double[]{200,350,500});int rr=Math.min(getLevel()-1,2);
            double[] missPct={0.25,0.30,0.35};
            double baseDmg=(baseMax[rr]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("r_jinx","ad",1.2))*distMult;

            for(var __t : TargetingUtil.entitiesInRadius(c, main.getLocation(), 4.65)){ // rayon 400 + collision (65)
                // PV manquants à l'échelle LoL (joueur=HPSystem, Baron=virtuel, sbire=vanilla)
                double missingHP = TargetingUtil.getRealMaxHealth(__t) - TargetingUtil.getRealHealth(__t);
                double missingBonus=missingHP*missPct[rr];
                if(!(__t instanceof Player)) missingBonus=Math.min(1200, missingBonus); // plafond contre les monstres (wiki)
                double dmg=baseDmg+missingBonus;
                if(!__t.equals(main)) dmg*=0.80; // explosion : 80 % autour de la cible
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
            }
            fr.lolmc.util.VisualEffectUtil.impactBurst(main.getWorld(),
                    main.getLocation(), Material.ORANGE_STAINED_GLASS, 0.45f, 2.5, 16, 10L);
            main.getWorld().playSound(main.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
            if(main instanceof Player _tp)_tp.sendMessage(Component.text("🚀 SUPER MÉGA ROQUETTE DE LA MORT!",NamedTextColor.RED));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_jinx",new double[]{200,350,500});int r=Math.min(getLevel()-1,2);
            return String.format("Roquette globale: jusqu'à %.0f dégâts (+120%%AD bonus) selon distance + %% PV manquants. Explose en zone.",base[r]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("r_jinx","ad",1.2));
        }
    }
}