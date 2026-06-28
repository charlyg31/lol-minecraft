package fr.lolmc.champion.impl.support;

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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class Blitzcrank extends BaseChampion {
    public Blitzcrank() {
        super("blitzcrank", "Blitzcrank", ChampionRole.SUPPORT,
            new ChampionStats(600,62,0,37,32,0.625,0,325,1.25,7.5));
        getStats().setGrowthStats(109.0,3.5,4.7,2.05,0.01130,0.75);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(623, 8.0, ResourceSystem.ResourceType.MANA, 400, 10.0);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("blitzcrank",Material.IRON_INGOT,2.0f,DamageType.PHYSICAL);}

    // Passif Statique Mana : quand Blitzcrank tombe sous 20% HP, génère un bouclier = 50% de sa mana actuelle (CD 60s)
    private static final java.util.Map<java.util.UUID, Long> staticShieldCD = new java.util.concurrent.ConcurrentHashMap<>();
    public static void resetState(java.util.UUID id) { staticShieldCD.remove(id); }
    public static void resetAllState()               { staticShieldCD.clear(); }
    public static void tickStaticShield(org.bukkit.entity.Player p, fr.lolmc.champion.base.BaseChampion champ) {
        var hp = champ.getHPSystem(); var res = champ.getResourceSystem();
        if (hp.getHPRatio() < 0.20 && champ.getStats().getShield() <= 0) {
            Long last = staticShieldCD.get(p.getUniqueId());
            if (last == null || System.currentTimeMillis() - last > 60_000L) {
                double shield = res.getCurrent() * 0.50;
                if (shield > 0) {
                    champ.getStats().addShield(shield);
                    staticShieldCD.put(p.getUniqueId(), System.currentTimeMillis());
                    p.sendActionBar(net.kyori.adventure.text.Component.text(
                        "⚡ Bouclier Statique! +" + (int)shield, net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                }
            }
        }
    }
    }

    static class Q extends BaseAbility {
        Q(){super("q_blitzcrank","Grappin Fusée",Material.FISHING_ROD,AbilitySlot.Q,
            new double[]{11,10.5,10,9.5,9},20,0,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : SKILLSHOT signature. Attrape le 1er ennemi, 110-310 + 120% AP, stun + tire vers Blitz
            var hits=TargetingUtil.skillshot(c, 12.0, 1.0, false);
            if(hits.isEmpty()){c.sendActionBar(Component.text("🪝 Grappin manqué!",NamedTextColor.GRAY));return;}
            var tgt=hits.get(0);
            double[] base=fr.lolmc.util.Balance.base("q_blitzcrank",new double[]{110,160,210,260,310});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_blitzcrank","ap",1.5);
            // Animation : chaîne qui voyage vers la cible
            org.bukkit.Location hookStart = c.getEyeLocation();
            org.bukkit.Location hookEnd = tgt.getLocation().add(0,1,0);
            double hookDist = hookStart.distance(hookEnd);
            int hookSteps = Math.max(3,(int)(hookDist/0.6));
            org.bukkit.util.Vector hookStep = hookEnd.toVector().subtract(hookStart.toVector()).normalize().multiply(hookDist/hookSteps);
            new org.bukkit.scheduler.BukkitRunnable(){
                int si=0; org.bukkit.Location cur = hookStart.clone();
                @Override public void run(){
                    if(si>=hookSteps){cancel();return;}
                    cur.add(hookStep);
                    cur.getWorld().spawnParticle(org.bukkit.Particle.CRIT,cur,1,0,0,0,0);
                    si++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,1L);
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_PISTON_EXTEND, 1f, 0.8f);
            // Dégâts et attraction après le voyage
            final var finalTgt = tgt;
            new org.bukkit.scheduler.BukkitRunnable(){
                @Override public void run(){
                    TargetingUtil.dealDamage(c, finalTgt, dmg, TargetingUtil.DmgType.MAGICAL);
                    Vector pull=c.getLocation().toVector().subtract(finalTgt.getLocation().toVector()).normalize().multiply(2.0);
                    pull.setY(0.3); finalTgt.setVelocity(pull);
                    if(finalTgt instanceof Player __p){
                        __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,15,10,false,true));
                        __p.sendActionBar(Component.text("🪝 ATTRAPÉ! Grappin Fusée",NamedTextColor.YELLOW));
                    }
                    finalTgt.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,finalTgt.getLocation().add(0,1,0),12,0.4,0.4,0.4);
                    finalTgt.getWorld().playSound(finalTgt.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.8f);
                }
            }.runTaskLater(LolPlugin.getInstance(), (long)hookSteps);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_blitzcrank",new double[]{110,160,210,260,310});
            return String.format("Skillshot: %.0f dégâts (+150%%AP), attrape et tire la cible vers toi + stun.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_blitzcrank","ap",1.5));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_blitzcrank","Surrégime",Material.REDSTONE,AbilitySlot.W,
            new double[]{15,14.5,14,13.5,13},0,0,DamageType.TRUE);
            resourceCost = 75;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : vitesse d'attaque 30-70% + vitesse de déplacement 75-90% pendant 5s
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,100,3,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,100,getLevel(),false,true));
            c.sendActionBar(Component.text("⚡ SURRÉGIME 5s!",NamedTextColor.YELLOW));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+vitesse de déplacement et d'attaque pendant 5s (puis ralenti bref).";}
    }

    static class E extends BaseAbility {
        E(){super("e_blitzcrank","Poing de Force",Material.LIGHTNING_ROD,AbilitySlot.E,
            new double[]{9,8,7,6,5},5,0,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : prochaine attaque renforcée = knockup 1s + 200% AD
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){c.sendActionBar(Component.text("⚡ Poing de Force prêt (frappe un ennemi)",NamedTextColor.YELLOW));return;}
            // Double l'attaque de base : AA (100% AD) + 100% AD bonus = 200% AD total
            double dmg=s.getFinalAD()*2.0;
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            tgt.setVelocity(new Vector(0,1.0,0)); // knockup
            if(tgt instanceof Player __p)__p.sendActionBar(Component.text("⚡ KNOCKUP! Poing de Force",NamedTextColor.YELLOW));
            c.getWorld().strikeLightningEffect(tgt.getLocation());
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Prochaine attaque double les dégâts: %.0f (200%%AD) + knockup 1s.",s.getFinalAD()*2.0);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_blitzcrank","Champ Statique",Material.COPPER_INGOT,AbilitySlot.R,
            new double[]{30,20,10},5,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : 275/400/525 + 100% AP, silence 0.5s + détruit les boucliers, autour
            double[] base=fr.lolmc.util.Balance.base("r_blitzcrank",new double[]{275,400,525});int r=Math.min(getLevel()-1,2);double dmg=base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_blitzcrank","ap",1.0);
            var cc=LolPlugin.getInstance().getCCManager();
            var cm=LolPlugin.getInstance().getChampionManager();
            for(var __t : TargetingUtil.enemiesAround(c, 4.5)){
                // Détruit les boucliers avant les dégâts (signature du Champ Statique)
                if(__t instanceof Player __sp && cm.hasChampion(__sp)){
                    double sh=cm.getChampion(__sp).getStats().getShield();
                    if(sh>0) cm.getChampion(__sp).getStats().addShield(-sh);
                }
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p){
                    if(cc!=null) cc.silence(__p, 10); // vrai silence 0.5s
                    __p.sendActionBar(Component.text("⚡ CHAMP STATIQUE! Silence + boucliers détruits",NamedTextColor.AQUA));
                }
            }
            c.getWorld().strikeLightningEffect(c.getLocation());
            c.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,c.getLocation().add(0,1,0),30,3,1,3);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_blitzcrank",new double[]{275,400,525});int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts foudre autour (+100%%AP) + silence 0.5s + détruit boucliers.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_blitzcrank","ap",1.0));
        }
    }
}