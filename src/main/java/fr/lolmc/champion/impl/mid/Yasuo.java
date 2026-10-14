package fr.lolmc.champion.impl.mid;

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
import java.util.*;

public final class Yasuo extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Yasuo() {
        super("yasuo", "Yasuo", ChampionRole.MID,
            new ChampionStats(590,60,0,32,32,0.697,0,345,1.75,6.5));
        getStats().setGrowthStats(110,2.5,4.6,2.05,0.035,0.9);
        setLolAttackRange(175); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(6.5, ResourceSystem.ResourceType.FLOW, 100, 0, 0, 0);
        // Passif Voie du Vagabond : double la chance de critique des objets (plafonnée à 100 %)
        getStats().setCritChanceMultiplier(2.0);
        // Ses critiques font 95 % des dégâts critiques normaux (190 % au lieu de 200 %, wiki)
        getStats().setCritDamageScale(0.95);
    }

    public static final Map<UUID,Integer> qCasts=new java.util.concurrent.ConcurrentHashMap<>();
    // Les charges de Tempête d'Acier se perdent 6 s après la dernière (wiki ; avant : jamais)
    private static final Map<UUID,Long> qStackUntil=new java.util.concurrent.ConcurrentHashMap<>();

    // ── Mur du Vent : segment au sol qui détruit les projectiles ennemis (skillshots, AA à distance) ──
    private record WindWall(fr.lolmc.team.TeamManager.Team team, org.bukkit.World world,
                            double cx, double cy, double cz, double ax, double az, double half, long until) {}
    private static final List<WindWall> walls = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Un projectile du lanceur allant de {@code from} à {@code to} traverse-t-il un Mur du Vent
     * ennemi ? (intersection de segments sur le plan horizontal, hauteur du mur ~4 blocs)
     */
    public static boolean blocksProjectile(Player caster, Location from, Location to) {
        if (walls.isEmpty()) return false;
        long now = System.currentTimeMillis();
        walls.removeIf(w -> w.until() < now);
        var team = LolPlugin.getInstance().getTeamManager().getTeam(caster);
        for (WindWall w : walls) {
            if (w.team() == team || !w.world().equals(from.getWorld())) continue;
            double px = from.getX(), pz = from.getZ(), rx = to.getX() - px, rz = to.getZ() - pz;
            double qx = w.cx() - w.ax() * w.half(), qz = w.cz() - w.az() * w.half();
            double sx = w.ax() * 2 * w.half(), sz = w.az() * 2 * w.half();
            double denom = rx * sz - rz * sx;
            if (Math.abs(denom) < 1e-9) continue;
            double tt = ((qx - px) * sz - (qz - pz) * sx) / denom;
            double uu = ((qx - px) * rz - (qz - pz) * rx) / denom;
            if (tt < 0 || tt > 1 || uu < 0 || uu > 1) continue;
            double y = from.getY() + tt * (to.getY() - from.getY());
            if (y < w.cy() - 1 || y > w.cy() + 4) continue;
            fr.lolmc.util.VisualEffectUtil.impact(from.getWorld(),
                    new Location(from.getWorld(), px + rx * tt, y, pz + rz * tt), Material.WHITE_STAINED_GLASS, 0.3f, 4L);
            return true;
        }
        return false;
    }
    // E : chaque cible ne peut être traversée qu'une fois par 10/9/8/7/6 s (LoL)
    private static final Map<String,Long> eLockout=new java.util.concurrent.ConcurrentHashMap<>();
    /** Reinitialise l'etat de ce joueur (fin de partie / deconnexion). */
    @Override public void resetState(UUID id){ qCasts.remove(id); qStackUntil.remove(id); eStacks.remove(id); eLastCast.remove(id); }
    @Override public void resetAllState(){ qCasts.clear(); eStacks.clear(); eLastCast.clear(); walls.clear(); eLockout.clear(); }

    // Passif Voie du Vagabond : au double crit chance passif (déjà configuré via ChampionStats)
    // Passif Esprit du Vent : bouclier égal 100+50%AP quand Flow est plein
    public static void tickWindShield(org.bukkit.entity.Player p, fr.lolmc.champion.base.BaseChampion champ) {
        var res = champ.getResourceSystem();
        if (res.getType() == fr.lolmc.stats.ResourceSystem.ResourceType.FLOW
                && res.getCurrent() >= res.getMax()
                && champ.getStats().getShield() <= 0) {
            int lvl = champ.getLevelSystem().getLevel();
            // 125 → 600 du niveau 1 au 18 (données du jeu ; 674 est la valeur du niveau 20)
            double shield = 125 + (600 - 125) * (lvl - 1) / 17.0;
            champ.getStats().addShield(shield);
            res.setCurrent(0);
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                "💨 Bouclier de Vent! +" + (int)shield, net.kyori.adventure.text.format.NamedTextColor.WHITE));
        }
    }

    static class AA extends BasicAttackAbility {
        AA(){super("yasuo",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_yasuo","Tempête d'Acier",Material.BLAZE_POWDER,AbilitySlot.Q,
            new double[]{4,4,4,4,4},20,6,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // 2 coups touchés → le 3e Q est une tornade (avant : chaque lancer, même raté, comptait)
            int qStacks=qStackUntil.getOrDefault(c.getUniqueId(),0L)>System.currentTimeMillis()
                    ? qCasts.getOrDefault(c.getUniqueId(),0) : 0;
            // LoL : coup d'estoc en ligne. 20/45/70/95/120 + 100% AD (peut critiquer)
            double[] base=fr.lolmc.util.Balance.base("q_yasuo",new double[]{20,45,70,95,120});double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("q_yasuo","ad",1.05);
            boolean tornado = qStacks>=2;
            if(tornado){
                // Tornade : skillshot plus long qui projette en l'air
                // Projectile réel : tornade de 1150 unités à 1200/s qui traverse
                var ccQ = LolPlugin.getInstance().getCCManager();
                TargetingUtil.launchSkillshot(c, c.getLocation().add(0,0.6,0), c.getEyeLocation().getDirection().setY(0), 11.5, 1.5, true, 12.0,
                        new org.bukkit.inventory.ItemStack(Material.WHITE_STAINED_GLASS), 1.6f, Material.WHITE_STAINED_GLASS,
                        __t -> {
                            TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                            if(ccQ!=null) ccQ.setAirborne(__t, 18); // projeté 0.9 s (wiki)
                            if(__t instanceof Player __p)__p.sendActionBar(Component.text("🌪 TORNADE Yasuo!",NamedTextColor.YELLOW));
                        }, null);
                qCasts.put(c.getUniqueId(),0);
                c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.2f);
            } else {
                // Estoc en ligne droite
                var hits=TargetingUtil.skillshot(c, 5.0, 1.0, true);
                for(var __t : hits) TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if(!hits.isEmpty()){
                    qCasts.put(c.getUniqueId(), Math.min(2, qStacks+1));
                    qStackUntil.put(c.getUniqueId(), System.currentTimeMillis()+6000L);
                }
                c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.1f);
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_yasuo",new double[]{20,45,70,95,120});
            return String.format("%.0f dégâts en ligne (+100%%AD). 2 stacks = tornade qui projette.",base[getLevel()-1]+s.getFinalAD());
        }
    }

    static class W extends BaseAbility {
        W(){super("w_yasuo","Mur du Vent",Material.WHITE_WOOL,AbilitySlot.W,
            new double[]{25,23,21,19,17},0,6,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Mur perpendiculaire au regard, 2 blocs devant, largeur 3.2-5.2 blocs (320-520 unités),
            // 4 s. Avant : simple potion de Résistance, aucun projectile bloqué.
            Location base=c.getLocation();
            org.bukkit.util.Vector fwd=base.getDirection().setY(0);
            if(fwd.lengthSquared()<1e-6) fwd=new org.bukkit.util.Vector(1,0,0);
            fwd.normalize();
            Location center=base.clone().add(fwd.clone().multiply(2.0));
            double[] widths={3.2,3.9,4.6,5.3,6.0}; // 320-600 unités (wiki)
            double half=widths[getLevel()-1]/2.0;
            double ax=-fwd.getZ(), az=fwd.getX();
            walls.add(new WindWall(LolPlugin.getInstance().getTeamManager().getTeam(c), c.getWorld(),
                    center.getX(), center.getY(), center.getZ(), ax, az, half, System.currentTimeMillis()+4000L));
            new BukkitRunnable(){ int n=0;
                @Override public void run(){
                    if(n++>=16){cancel();return;}
                    for(double d=-half; d<=half; d+=0.8)
                        fr.lolmc.util.VisualEffectUtil.impact(center.getWorld(),
                                center.clone().add(ax*d, 1.2, az*d), Material.WHITE_STAINED_GLASS, 0.25f, 5L);
                }}.runTaskTimer(LolPlugin.getInstance(),0L,5L);
            c.sendActionBar(Component.text("💨 Mur du Vent 4s!",NamedTextColor.WHITE));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Mur devant toi : détruit les projectiles ennemis (sorts et attaques à distance) 4s.";}
    }

    // Stacks de dégâts E (Lame Déferlante) par joueur
    public static final Map<UUID,Integer> eStacks=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID,Long> eLastCast=new java.util.concurrent.ConcurrentHashMap<>();

    static class E extends BaseAbility {
        E(){super("e_yasuo","Lame Déferlante",Material.FEATHER,AbilitySlot.E,
            new double[]{0.5,0.4,0.3,0.2,0.1},5,0,DamageType.MAGICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : dash à travers la cible, dégâts magiques + 25% par stack (max 4 = +100%)
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,5.4); if(tgt==null){cancelCast();c.sendActionBar(Component.text("🍃 Aucune cible",NamedTextColor.GRAY));return;}
            // Une même cible ne peut être traversée qu'une fois par 10/9/8/7/6 s (avant : E à volonté)
            String lockKey=c.getUniqueId()+":"+tgt.getUniqueId();
            if(eLockout.getOrDefault(lockKey,0L)>System.currentTimeMillis()){
                c.sendActionBar(Component.text("🍃 Cible déjà traversée récemment",NamedTextColor.GRAY));
                cancelCast(); return;
            }
            double[] lockSec={10,9,8,7,6};
            eLockout.put(lockKey, System.currentTimeMillis()+(long)(lockSec[getLevel()-1]*1000));
            int stacks=Math.min(eStacks.getOrDefault(c.getUniqueId(),0),4);
            double[] base=fr.lolmc.util.Balance.base("e_yasuo",new double[]{70,85,100,115,130});
            double dmg=(base[getLevel()-1]+s.getBonusAD()*0.20+s.getFinalAP()*0.60)*(1.0+0.25*stacks); // AD bonus (wiki)
            // Dash à travers la cible (direction Yasuo → cible, sans traverser les murs)
            dashTo(c, tgt, 1.5);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            eStacks.merge(c.getUniqueId(),1,Integer::sum);
            // Reset des stacks 6s après le DERNIER E (avant : le reset du 1er E effaçait les suivants)
            final long castAt=System.currentTimeMillis();
            eLastCast.put(c.getUniqueId(), castAt);
            new BukkitRunnable(){@Override public void run(){
                if(eLastCast.getOrDefault(c.getUniqueId(),0L)==castAt) eStacks.put(c.getUniqueId(),0);
            }}.runTaskLater(LolPlugin.getInstance(),120L);
            fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                    c.getLocation().add(0,1,0), Material.WHITE_STAINED_GLASS, 0.3f, 4L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.4f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_yasuo",new double[]{70,85,100,115,130});
            return String.format("Dash à travers la cible: %.0f dégâts (+20%%AD bonus +60%%AP, +25%%/stack max +100%%).",base[getLevel()-1]+s.getBonusAD()*0.20+s.getFinalAP()*0.60);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_yasuo","Dernier Souffle",Material.ELYTRA,AbilitySlot.R,
            new double[]{70,50,30},5,4,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : se TP sur un ennemi (idéalement aérien), dégâts + maintient en l'air
            // Wiki : Dernier Souffle ne vise qu'un ennemi PROJETÉ EN L'AIR à moins de 1400 unités
            // (avant : n'importe quelle cible)
            var cc = LolPlugin.getInstance().getCCManager();
            org.bukkit.entity.LivingEntity tgt = null;
            if (t != null && TargetingUtil.isEnemy(c, t) && cc != null && cc.isAirborne(t.getUniqueId())) tgt = t;
            if (tgt == null) {
                var aimed = TargetingUtil.getTargetedEnemy(c, 14.0);
                if (aimed != null && cc != null && cc.isAirborne(aimed.getUniqueId())) tgt = aimed;
            }
            if (tgt == null && cc != null) {
                double best = 14.0 * 14.0;
                for (var e : TargetingUtil.enemiesAround(c, 14.0)) {
                    if (!cc.isAirborne(e.getUniqueId())) continue;
                    double d = e.getLocation().distanceSquared(c.getLocation());
                    if (d < best) { best = d; tgt = e; }
                }
            }
            if(tgt==null){cancelCast();c.sendActionBar(Component.text("⚔ Aucun ennemi projeté en l'air",NamedTextColor.GRAY));return;}
            // Dash sur la cible
            dashTo(c, tgt, -1.0);
            double[] base=fr.lolmc.util.Balance.base("r_yasuo",new double[]{200,350,500});
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("r_yasuo","ad",1.5); // AD bonus (wiki)
            // Frappe tous les ennemis projetés en l'air autour de la cible (rayon 400) et les y maintient
            for(var __t : TargetingUtil.entitiesInRadius(c, tgt.getLocation(), 4.0)){
                if (cc != null && !cc.isAirborne(__t.getUniqueId()) && !__t.equals(tgt)) continue;
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if (cc != null) cc.setAirborne(__t, 20); // 1 s en l'air (wiki)
                if(__t instanceof Player __p)
                    __p.sendActionBar(Component.text("⚔ DERNIER SOUFFLE! Propulsé!",NamedTextColor.DARK_GRAY));
            }

            // Flow max + crits ignorent 60% armure pendant 15s (LoL)
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(c)){
                var champ=cm.getChampion(c);
                if(champ.getResourceSystem()!=null) champ.getResourceSystem().setCurrent(champ.getResourceSystem().getMax());
                champ.getStats().addBonusArmorPenPercent(0.60);
                final var fstats=champ.getStats();
                new BukkitRunnable(){@Override public void run(){ fstats.addBonusArmorPenPercent(-0.60); }}.runTaskLater(LolPlugin.getInstance(), 300L);
            }
            fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                    c.getLocation().add(0,1,0), 3.0, Material.WHITE_STAINED_GLASS, 20, 0.4f, 0.12f, 8L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_yasuo",new double[]{200,350,500});int r=Math.min(getLevel()-1,2);
            return String.format("Sur un ennemi projeté en l'air: %.0f dégâts (+150%%AD bonus) + maintient en l'air 1s.",base[r]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("r_yasuo","ad",1.5));
        }
    }
}