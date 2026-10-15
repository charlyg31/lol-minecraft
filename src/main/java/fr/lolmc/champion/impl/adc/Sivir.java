package fr.lolmc.champion.impl.adc;

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
import org.bukkit.scheduler.BukkitRunnable;

public final class Sivir extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Sivir() {
        super("sivir", "Sivir", ChampionRole.ADC,
            new ChampionStats(600,60,0,30,33,0.625,0,335,5,3.25));
        getStats().setGrowthStats(104,2.5,4,1.1,0.016,0.55);
        setLolAttackRange(500); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(3.25, ResourceSystem.ResourceType.MANA, 340, 45, 6, 0.8);
    }

    private static final java.util.Map<java.util.UUID, Boolean> ricochetActive = new java.util.concurrent.ConcurrentHashMap<>();
    // Ultime actif : chaque attaque réduit les recharges des sorts de 0.5 s (wiki)
    private static final java.util.Map<java.util.UUID, Long> huntUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> huntTicks = new java.util.concurrent.ConcurrentHashMap<>();
    /** En chasse (wiki) : l'élimination d'un ennemi récemment blessé relance la durée (et la vitesse). */
    public static void onTakedown(Player sivir) {
        Long until = huntUntil.get(sivir.getUniqueId());
        if (until == null || until <= System.currentTimeMillis()) return;
        int ticks = huntTicks.getOrDefault(sivir.getUniqueId(), 160);
        huntUntil.put(sivir.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(sivir)) return;
        int r = Math.min(cm.getChampion(sivir).getLevelSystem().getAbilityRank(4), 3);
        if (r <= 0) return;
        double msPct = new double[]{0.20,0.25,0.30}[r-1];
        var cc = LolPlugin.getInstance().getCCManager();
        cc.speedPct(sivir, msPct, ticks);
        var tm = LolPlugin.getInstance().getTeamManager();
        for (var ally : sivir.getWorld().getPlayers())
            if (!ally.equals(sivir) && tm != null && tm.areAllies(sivir, ally) && ally.getLocation().distance(sivir.getLocation()) <= 10)
                cc.speedPct(ally, msPct, ticks);
    }
    @Override public void resetState(java.util.UUID id) { ricochetActive.remove(id); }
    @Override public void resetAllState()               { ricochetActive.clear(); huntUntil.clear(); }
    public static void setRicochet(java.util.UUID id, boolean v) { ricochetActive.put(id, v); if (!v) ricochetLeft.remove(id); }
    /** Ricochet (données du jeu) : seules les 3 prochaines attaques rebondissent (4 s). */
    private static final java.util.Map<java.util.UUID, Integer> ricochetLeft = new java.util.concurrent.ConcurrentHashMap<>();

    // Passif Pied léger (données du jeu) : attaquer un champion donne 55-75 de vitesse pendant 1.5 s.
    // Manquait.
    private static final java.util.Map<java.util.UUID, Long> fleetUntil = new java.util.concurrent.ConcurrentHashMap<>();
    static void fleetOfFoot(Player c, ChampionStats s) {
        var cm = LolPlugin.getInstance().getChampionManager();
        int lvl = cm.hasChampion(c) ? cm.getChampion(c).getLevelSystem().getLevel() : 1;
        double ms = lvl >= 18 ? 75 : lvl >= 16 ? 70 : lvl >= 11 ? 65 : lvl >= 6 ? 60 : 55; // données du jeu
        long until = System.currentTimeMillis() + 1500L;
        boolean active = fleetUntil.getOrDefault(c.getUniqueId(), 0L) > System.currentTimeMillis();
        fleetUntil.put(c.getUniqueId(), until);
        if (active) return; // rafraîchi, pas cumulé
        s.addBonusMoveSpeed(ms);
        new BukkitRunnable() { @Override public void run() {
            // Rafraîchi par chaque attaque : retiré seulement 1.5 s après la dernière
            if (fleetUntil.getOrDefault(c.getUniqueId(), 0L) > System.currentTimeMillis()) return;
            s.addBonusMoveSpeed(-ms);
            cancel();
        }}.runTaskTimer(LolPlugin.getInstance(), 30L, 5L);
    }

    // Bouclier maléfique (données du jeu) : bloquer un sort soigne de 60-80 % AD (+50 % AP). Manquait.
    @Override public void onSpellShieldBlock(Player self) {
        var s = getStats();
        int r = Math.max(1, getLevelSystem().getAbilityRank(3));
        double heal = new double[]{0.60,0.65,0.70,0.75,0.80}[r-1] * s.getFinalAD() + 0.50 * s.getFinalAP();
        getHPSystem().heal(heal);
        self.sendActionBar(Component.text("🛡 Sort bloqué ! +" + (int) heal + " PV", NamedTextColor.GREEN));
        fleetOfFoot(self, s); // le blocage déclenche Mille-pattes (wiki)
    }

    static class AA extends BasicAttackAbility {
        AA(){super("sivir",Material.CROSSBOW,5.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            if (tgt instanceof Player) fleetOfFoot(c, s);
            // À la Chasse : chaque attaque réduit les recharges de Q, W et E de 0.5 s
            if (tgt instanceof Player && huntUntil.getOrDefault(c.getUniqueId(), 0L) > System.currentTimeMillis()) { // contre les champions
                var cmH = LolPlugin.getInstance().getChampionManager();
                if (cmH.hasChampion(c)) for (int i = 1; i <= 3; i++) {
                    var a = cmH.getChampion(c).getAbility(i);
                    if (a != null) a.reduceCooldown(c, 0.5);
                }
            }
            if (!ricochetActive.getOrDefault(c.getUniqueId(), false)) return;
            int left = ricochetLeft.getOrDefault(c.getUniqueId(), 0);
            if (left <= 0) return;
            if (left == 1) setRicochet(c.getUniqueId(), false); else ricochetLeft.put(c.getUniqueId(), left - 1);
            // Ricochets (wiki) : jusqu'à 8 rebonds, 40-50 % AD chacun
            var cmW = LolPlugin.getInstance().getChampionManager();
            int wr = cmW.hasChampion(c) ? Math.max(1, cmW.getChampion(c).getAbility(2).getLevel()) : 1;
            double bouncePct = new double[]{0.40,0.425,0.45,0.475,0.50}[Math.min(wr,5)-1];
            var struck = new java.util.HashSet<java.util.UUID>();
            struck.add(tgt.getUniqueId());
            // Les rebonds critiquent si l'attaque d'origine a critiqué (wiki)
            double bounceDmg = s.getFinalAD() * bouncePct * (fr.lolmc.item.ItemEffects.lastAttackCrit ? s.getFinalCritDamage() : 1.0);
            bounce(c, tgt, bounceDmg, struck, 8); // jusqu'à 8 rebonds (LoL)
        }
    }

    /**
     * Ricochet : la lame file de cible en cible (l'ennemi non touché le plus proche, à 5 blocs) et
     * chaque rebond frappe à son arrivée. Avant, tous les ennemis autour prenaient le coup en même temps.
     */
    static void bounce(Player c, org.bukkit.entity.LivingEntity from, double dmg, java.util.Set<java.util.UUID> struck, int left) {
        if (left <= 0 || !c.isOnline() || !from.isValid()) return;
        org.bukkit.entity.LivingEntity next = null;
        double best = 25;
        for (var e : TargetingUtil.entitiesInRadius(c, from.getLocation(), 5.0)) {
            if (struck.contains(e.getUniqueId())) continue;
            double d = e.getLocation().distanceSquared(from.getLocation());
            if (d < best) { best = d; next = e; }
        }
        if (next == null) return;
        struck.add(next.getUniqueId());
        final var tgt = next;
        TargetingUtil.launchHoming(c, from.getLocation().add(0, 1, 0), tgt, 10.0, // 1000 unités/s (données du jeu)
                new org.bukkit.inventory.ItemStack(Material.IRON_AXE), 0.5f, Material.WHITE_STAINED_GLASS, true, () -> {
            // 65 % contre les sbires (données du jeu)
            DamageUtil.damageEntity(c, tgt, fr.lolmc.game.MinionManager.isMinion(tgt) ? dmg * 0.65 : dmg, false, DamageUtil.Type.PHYSICAL);
            bounce(c, tgt, dmg, struck, left - 1);
        });
    }

    static class Q extends BaseAbility {
        Q(){super("q_sivir","Lame Boomerang",Material.IRON_AXE,AbilitySlot.Q,
            new double[]{10,9.5,9,8.5,8},25,0,DamageType.PHYSICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : lame lancée en ligne (1250 unités) qui revient : 60-160 (+70 % AD bonus
            // +60 % AP) à l'aller et au retour. Avant : sort ciblé à 6.5 blocs.
            double[] base=fr.lolmc.util.Balance.base("q_sivir",new double[]{60,85,110,135,160});
            double dmg=base[getLevel()-1]+s.getBonusAD()*0.70+s.getFinalAP()*0.60;
            // Projectile réel : la lame part (1450/s), traverse, puis revient vers Sivir (1200/s)
            var out=c.getEyeLocation().getDirection();
            var axe=new org.bukkit.inventory.ItemStack(Material.IRON_AXE);
            // Chaque non-champion touché réduit de 15 % les dégâts suivants (40 % min., données du jeu)
            final double[] mult={1.0};
            java.util.function.Consumer<org.bukkit.entity.LivingEntity> hit = e -> {
                DamageUtil.abilityDamageEntity(c, e, dmg*mult[0]);
                if(!(e instanceof Player)) mult[0]=Math.max(0.40, mult[0]-0.15);
            };
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), out, 12.5, 0.9, true, 14.5, axe, 1.0f, null, null,
                    hit,
                    (hits, end) -> {
                        // Retour depuis là où la lame s'est arrêtée (avant : toujours depuis 12.5 blocs,
                        // même si elle avait heurté un mur)
                        if(!c.isOnline() || !end.getWorld().equals(c.getWorld())) return;
                        var back=c.getEyeLocation().toVector().subtract(end.toVector());
                        if(back.lengthSquared()<1e-6) return;
                        TargetingUtil.launchSkillshot(c, end, back, back.length(), 0.9, true, 12.0, axe, 1.0f, null,
                                hit, null);
                    });
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_sivir",new double[]{60,85,110,135,160});double d=base[getLevel()-1]+s.getBonusAD()*0.70+s.getFinalAP()*0.60;
            return String.format("Lame en ligne: %.0f dégâts à l'aller et au retour (boomerang).",d);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_sivir","Ricochets",Material.MUSIC_DISC_13,AbilitySlot.W,
            new double[]{12,12,12,12,12},0,0,DamageType.PHYSICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // W Ricochet : active le rebond des AA pendant 6s
            // Wiki : 4 s, +20-40 % de vitesse d'attaque
            setRicochet(c.getUniqueId(), true);
            ricochetLeft.put(c.getUniqueId(), 3);
            double asBonus=new double[]{0.20,0.25,0.30,0.35,0.40}[getLevel()-1];
            s.addBonusAttackSpeed(asBonus);
            c.sendActionBar(Component.text("🎯 Ricochets 4s!",NamedTextColor.YELLOW));
            new BukkitRunnable(){@Override public void run(){ setRicochet(c.getUniqueId(), false); s.addBonusAttackSpeed(-asBonus); }}.runTaskLater(LolPlugin.getInstance(),80L);
                }
        @Override public String getDynamicDescription(ChampionStats s){return "4s: +vitesse d'attaque, les attaques rebondissent jusqu'à 8 fois (40-50% AD).";}
    }

    static class E extends BaseAbility {
        E(){super("e_sivir","Bouclier Maléfique",Material.SHIELD,AbilitySlot.E,
            new double[]{24,22.5,21,19.5,18},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Bouclier anti-sort : bloque le prochain sort ennemi pendant 1,5 s
            s.grantSpellShield(1500L);
            c.sendActionBar(Component.text("🛡 Bouclier Maléfique!",NamedTextColor.GREEN));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "1.5s : bloque le prochain sort ennemi et te soigne (60-80% AD +50% AP).";}
    }

    static class R extends BaseAbility {
        R(){super("r_sivir","Appel des Flèches",Material.GOLDEN_AXE,AbilitySlot.R,
            new double[]{120,100,80},0,0,DamageType.TRUE);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 8/10/12 s, +20/25/30 % de vitesse pour Sivir et ses alliés proches ; chaque
            // attaque réduit les recharges de 0.5 s (avant : 4 s et une remise à zéro du W)
            int r=Math.min(getLevel()-1,2);
            int ticks=new int[]{160,200,240}[r];
            huntUntil.put(c.getUniqueId(), System.currentTimeMillis()+ticks*50L);
            huntTicks.put(c.getUniqueId(), ticks);
            double msPct=new double[]{0.20,0.25,0.30}[r];
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(c, msPct, ticks);
            var tm0=LolPlugin.getInstance().getTeamManager();
            for(var ally : c.getWorld().getPlayers()){
                if(ally.equals(c)) continue;
                if(tm0!=null && tm0.areAllies(c, ally) && ally.getLocation().distance(c.getLocation())<=10)
                    fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(ally, msPct, ticks);
            }
            c.sendActionBar(Component.text("⚡ À la Chasse! Les attaques réduisent les recharges",NamedTextColor.GOLD));
            c.getWorld().playSound(c.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "8-12s: +20-30% vitesse (alliés compris), chaque attaque contre un champion réduit les recharges de 0.5s. Une élimination relance la durée.";}
    }
}
