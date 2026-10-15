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

public final class Ashe extends BaseChampion {
    public Ashe() {
        super("ashe", "Ashe", ChampionRole.ADC,
            new ChampionStats(610,59,0,26,33,0.658,0,325,6,3.5));
        getStats().setGrowthStats(101,3,4.6,1.1,0.03,0.55);
        setLolAttackRange(600); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(3.5, ResourceSystem.ResourceType.MANA, 280, 35, 7, 0.65);
    }

    /**
     * Passif Tir de Givre (données du jeu) : les critiques d'Ashe n'infligent pas de dégâts bonus
     * mais appliquent un ralentissement renforcé (40 → 60 %) ; ses attaques contre une cible
     * ralentie sont multipliées par 1 + chance de critique × (1 + dégâts critiques bonus).
     * Avant : critiques classiques et aucun bonus contre les cibles ralenties.
     */
    private static final java.util.Map<java.util.UUID, Boolean> lastCrit = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public double basicAttackDamage(Player attacker, org.bukkit.entity.LivingEntity target, double ad, boolean crit) {
        lastCrit.put(attacker.getUniqueId(), crit);
        // Tir Concentré : chaque attaque est une rafale de 5 flèches de 22-26 % AD (110-130 %)
        if (rangersFocusUntil.getOrDefault(attacker.getUniqueId(), 0L) > System.currentTimeMillis())
            ad *= flurryMult.getOrDefault(attacker.getUniqueId(), 1.10);
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null && cc.isSlowed(target)) {
            var st = getStats();
            return ad * (1.0 + st.getFinalCritChance() * (1.0 + st.getBonusCritDamage()));
        }
        return ad;
    }
    static double empoweredFrost(int lvl) { return 0.40 + 0.20 * (lvl - 1) / 17.0; }

    /** Ralentissement de Tir de Givre selon le niveau (20 % → 30 %, wiki). */
    static double frostShot(int lvl) { return 0.20 + 0.10 * (lvl - 1) / 17.0; }
    static double frostShot(Player ashe) {
        var cm = LolPlugin.getInstance().getChampionManager();
        return frostShot(cm.hasChampion(ashe) ? cm.getChampion(ashe).getLevelSystem().getLevel() : 1);
    }

    /**
     * Concentration (wiki) : hors Tir Concentré, chaque attaque donne une charge (4 max, 4 s,
     * rafraîchies par les attaques suivantes, puis perdues une par seconde). Il en faut 4 pour
     * lancer le Q. Avant : Q lançable à tout moment.
     */
    private static final java.util.Map<java.util.UUID, int[]> focus = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> focusUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> rangersFocusUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Double> flurryMult = new java.util.concurrent.ConcurrentHashMap<>();
    /** Charges actuelles, en appliquant la perte d'une charge par seconde après les 4 s. */
    static int focusStacks(java.util.UUID id) {
        int[] f = focus.get(id);
        if (f == null) return 0;
        long over = System.currentTimeMillis() - focusUntil.getOrDefault(id, 0L);
        return over <= 0 ? f[0] : Math.max(0, f[0] - 1 - (int) (over / 1000));
    }
    static void gainFocus(Player c) {
        var id = c.getUniqueId();
        if (rangersFocusUntil.getOrDefault(id, 0L) > System.currentTimeMillis()) return;
        int n = Math.min(4, focusStacks(id) + 1);
        focus.put(id, new int[]{n});
        focusUntil.put(id, System.currentTimeMillis() + 4000L);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("ashe",Material.ARROW,6.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            gainFocus(c);
            // Passif Tir de Givre : slow 15-30% selon niveau (2s = 40 ticks)
            if(tgt instanceof Player __p){
                var cm=LolPlugin.getInstance().getChampionManager();
                int lvl=cm.hasChampion(c)?cm.getChampion(c).getLevelSystem().getLevel():1;
                // Tir de Givre : 20 → 30 % selon le niveau, 40 → 60 % sur un critique, 2 s
                boolean crit = lastCrit.getOrDefault(c.getUniqueId(), false);
                fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p, crit ? empoweredFrost(lvl) : frostShot(lvl), 40);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_ashe","Tir Concentré",Material.SPECTRAL_ARROW,AbilitySlot.Q,
            new double[]{6,6,6,6,6},25,0,DamageType.PHYSICAL);
            resourceCost = 30;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            int stacks=focusStacks(c.getUniqueId());
            if(stacks<4){
                cancelCast();
                c.sendActionBar(Component.text("🏹 Concentration "+stacks+"/4 : attaque pour charger le Tir Concentré",NamedTextColor.GRAY));
                return;
            }
            focus.remove(c.getUniqueId()); focusUntil.remove(c.getUniqueId());
            rangersFocusUntil.put(c.getUniqueId(), System.currentTimeMillis()+6000L);
            // Wiki : 6 s, +20-60 % de vitesse d'attaque ; la rafale inflige 110-130 % AD par attaque
            int r=getLevel()-1;
            double asBonus=new double[]{0.20,0.30,0.40,0.50,0.60}[r];
            // Rafale : multiplicateur appliqué aux attaques de base (avant : +10-30 % d'AD, qui
            // renforçait aussi ses sorts)
            flurryMult.put(c.getUniqueId(), new double[]{1.10,1.15,1.20,1.25,1.30}[r]);
            s.addBonusAttackSpeed(asBonus);
            new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){
                s.addBonusAttackSpeed(-asBonus);
            }}.runTaskLater(LolPlugin.getInstance(), 120L);
            c.sendActionBar(Component.text("🏹 Tir Concentré 6s!",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "6s: +20-60% vitesse d'attaque, attaques en rafale (110-130% AD).";}
    }

    static class W extends BaseAbility {
        W(){super("w_ashe","Volée",Material.TIPPED_ARROW,AbilitySlot.W,
            new double[]{18,14.5,11,7.5,4},25,4,DamageType.PHYSICAL);
            resourceCost = 75; resourceCosts = new double[]{75,70,65,60,55};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : tire des flèches en cône. 60/95/130/165/200 + 100% AD, applique Tir de Givre (ralentit)
            double[] base=fr.lolmc.util.Balance.base("w_ashe",new double[]{60,95,130,165,200});double dmg=base[getLevel()-1]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("w_ashe","ad",1); // AD bonus (wiki)
            // Wiki : 7/8/9/10/11 flèches en éventail (40°), 2000 unités/s, chacune s'arrête au 1er
            // ennemi ; un même ennemi n'est touché qu'une fois par volée
            int arrows=7+getLevel()-1;
            var dir=c.getEyeLocation().getDirection().normalize();
            var struck=java.util.concurrent.ConcurrentHashMap.<java.util.UUID>newKeySet();
            for(int i=0;i<arrows;i++){
                double ang=Math.toRadians(-20+40.0*i/(arrows-1));
                var d=dir.clone().rotateAroundY(ang);
                TargetingUtil.launchSkillshot(c, c.getEyeLocation(), d, 12.0, 0.4, false, 15.0, // 1200 unités à 1500/s (données du jeu)
                        new org.bukkit.inventory.ItemStack(Material.ARROW), 0.6f, Material.WHITE_STAINED_GLASS,
                        __t -> {
                            if(!struck.add(__t.getUniqueId())) return;
                            TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                            if(__t instanceof Player __p) fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p, frostShot(c), 40);
                        }, null);
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_ashe",new double[]{60,95,130,165,200});
            return String.format("%d flèches en éventail: %.0f dégâts (+100%%AD bonus) + ralentit.",7+getLevel()-1,base[getLevel()-1]+s.getBonusAD());
        }
    }

    static class E extends BaseAbility {
        E(){super("e_ashe","Faucon Explorateur",Material.FEATHER,AbilitySlot.E,
            new double[]{90,80,70,60,50},60,0,DamageType.TRUE);
            resourceCost = 0; maxCharges = 2;} // 2 charges (wiki)
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki / données du jeu : le faucon vole jusqu'au point visé (portée globale) à 1400
            // unités/s, révèle son trajet, puis la zone d'arrivée (rayon 1000) pendant 5 s
            org.bukkit.Location start = c.getEyeLocation();
            org.bukkit.Location goal = TargetingUtil.getAimedGroundLocation(c, 250.0);
            org.bukkit.util.Vector dir0 = goal.toVector().subtract(start.toVector());
            if (dir0.lengthSquared() < 1e-6) dir0 = start.getDirection();
            int maxSteps = (int) Math.max(1, Math.ceil(dir0.length() / 0.7)); // 0.7 bloc/tick = 1400 unités/s
            final org.bukkit.util.Vector dir = dir0.normalize();
            var tm = LolPlugin.getInstance().getTeamManager();
            var fogMgr = fr.lolmc.instance.InstanceHelper.fogOfWarManager(c);
            var casterTeam = tm.getTeam(c);
            // Faucon affiché comme un projectile lissé (avant : cube jaune téléporté à chaque tick)
            var hawkDisplay = fr.lolmc.util.VisualEffectUtil.spawnProjectile(start, dir, new org.bukkit.inventory.ItemStack(Material.FEATHER), 0.9f);
            new org.bukkit.scheduler.BukkitRunnable() {
                int step = 0;
                final org.bukkit.Location pos = start.clone();
                @Override public void run() {
                    if (step >= maxSteps || !c.isOnline() || pos.getBlock().getType().isSolid()) {
                        fr.lolmc.util.VisualEffectUtil.endProjectile(hawkDisplay, pos, null);
                        // Vision de la zone d'arrivée : rayon 1000, 5 s
                        if (fogMgr != null && casterTeam != null) fogMgr.revealPoint(casterTeam, pos, 10.0, 100L);
                        for (var nearby : pos.getWorld().getNearbyEntities(pos, 10, 5, 10))
                            if (nearby instanceof Player enemy && tm.areEnemies(c, enemy))
                                enemy.addPotionEffect(new org.bukkit.potion.PotionEffect(
                                        org.bukkit.potion.PotionEffectType.GLOWING, 100, 0, false, false));
                        cancel(); return;
                    }
                    pos.add(dir.clone().multiply(0.7));
                    fr.lolmc.util.VisualEffectUtil.moveProjectile(hawkDisplay, pos, dir, 0.9f, 0);
                    // Révèle la zone survolée pour l'équipe d'Ashe pendant 0.5s
                    // (fenêtre glissante qui suit le faucon le long de son trajet)
                    if (fogMgr != null && casterTeam != null) {
                        fogMgr.revealPoint(casterTeam, pos, 10.0, 10L);
                    }
                    // Révéler les ennemis dans un rayon de 8 blocs
                    for (var nearby : pos.getWorld().getNearbyEntities(pos, 10, 5, 10)) {
                        if (nearby instanceof Player enemy
                                && !enemy.equals(c)
                                && tm.areEnemies(c, enemy)) {
                            // Rendre temporairement visible via GLOWING
                            enemy.addPotionEffect(new org.bukkit.potion.PotionEffect(
                                org.bukkit.potion.PotionEffectType.GLOWING, 60, 0, false, false));
                            c.sendActionBar(Component.text(
                                "🦅 Faucon: " + enemy.getName() + " détecté!",
                                NamedTextColor.YELLOW));
                        }
                    }
                    step++;
                }
            }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);
            c.sendActionBar(Component.text("🦅 Faucon envoyé!", NamedTextColor.YELLOW));
            c.getWorld().playSound(c.getLocation(), org.bukkit.Sound.ENTITY_PARROT_FLY, 0.8f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return "Envoie un faucon au point visé (portée globale) : révèle son trajet, puis la zone d'arrivée 5 s.";
        }
    }

    static class R extends BaseAbility {
        R(){super("r_ashe","Flèche de Cristal Enchantée",Material.DIAMOND,AbilitySlot.R,
            new double[]{100,80,60},25,2,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot longue portée. Stun 1-3.5s selon distance parcourue. Zone autour = 50% dégâts + slow
            double[] base=fr.lolmc.util.Balance.base("r_ashe",new double[]{200,400,600});int rr=Math.min(getLevel()-1,2);double dmg=base[rr]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_ashe","ap",1.2);
            // Projectile réel : flèche de cristal (≈1800 unités/s), s'arrête au 1er ennemi
            final Location from=c.getLocation();
            // Portée globale ; 1500 unités/s, +200 unités/s², 2100 max (données du jeu)
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), c.getEyeLocation().getDirection(), 250.0, 1.2, false,
                    travelled -> Math.min(21.0, Math.sqrt(15.0*15.0 + 2*2.0*travelled)),
                    new org.bukkit.inventory.ItemStack(Material.BLUE_ICE), 0.8f, Material.LIGHT_BLUE_STAINED_GLASS,
                    e -> e instanceof Player, // ne percute que les champions (wiki)
                    main -> arrowHit(c, s, main, dmg, from.distance(main.getLocation())),
                    (hits, at) -> { if(hits.isEmpty()) c.sendActionBar(Component.text("❄ Flèche tirée (aucune cible touchée)",NamedTextColor.AQUA)); });
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.2f, 0.6f);
        }

        private void arrowHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity main, double dmg, double dist){
            // Distance parcourue → durée du stun (1 à 3.5s = 20 à 70 ticks)
            int stunTicks=(int)Math.min(70, 20+dist*2.5);
            fr.lolmc.rune.RuneManager.beginSingleTarget(); // cible principale : dégâts à cible unique
            try { TargetingUtil.dealDamage(c, main, dmg, TargetingUtil.DmgType.MAGICAL); }
            finally { fr.lolmc.rune.RuneManager.endSingleTarget(); }
            // Vrai stun (immobilise + empêche d'agir, réduit par la ténacité)
            fr.lolmc.LolPlugin.getInstance().getCCManager().stun(main, stunTicks);
            fr.lolmc.LolPlugin.getInstance().getCCManager().markCoordinatedFire(c, main);
            fr.lolmc.LolPlugin.getInstance().getCCManager().applyZekeConduit(c, main);
            fr.lolmc.LolPlugin.getInstance().getCCManager().applyAftershock(c, main);
            if(main instanceof Player __p){
                __p.sendActionBar(Component.text("❄ ÉTOURDI par la Flèche de Cristal!",NamedTextColor.AQUA));
            }
            // Zone autour de la cible principale (rayon 400) : mêmes dégâts + ralentissement (wiki)
            for(var __t : TargetingUtil.entitiesInRadius(c, main.getLocation(), 4.0)){
                if(__t.equals(main)) continue;
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p2) fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p2, frostShot(c), 40);
            }
            fr.lolmc.util.VisualEffectUtil.groundRing(main.getWorld(),
                    main.getLocation().add(0,1,0), 1.5, Material.LIGHT_BLUE_STAINED_GLASS, 14, 0.3f, 0.1f, 8L);
            main.getWorld().playSound(main.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.5f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_ashe",new double[]{200,400,600});int r=Math.min(getLevel()-1,2);
            return String.format("Skillshot: %.0f dégâts (+120%%AP) + étourdit 1-3.5s selon distance + zone.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_ashe","ap",1.2));
        }
    }
}