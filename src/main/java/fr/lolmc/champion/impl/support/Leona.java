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

public class Leona extends BaseChampion {
    public Leona() {
        super("leona", "Leona", ChampionRole.SUPPORT,
            new ChampionStats(646,60,0,38,32,0.625,0,335,1.25,8.5));
        getStats().setGrowthStats(104.0,3.0,4.7,2.05,0.02900,0.85);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(576, 7.0, ResourceSystem.ResourceType.MANA, 420, 9.0);
    }

    // Passif Lumière du Soleil : les sorts de Leona marquent les ennemis.
    // Un allié qui frappe un ennemi marqué inflige 35-80 dégâts magiques bonus.
    static final java.util.Map<java.util.UUID, Long> sunlightMark
        = new java.util.concurrent.ConcurrentHashMap<>();
    public static void applyMark(org.bukkit.entity.LivingEntity target) {
        sunlightMark.put(target.getUniqueId(), System.currentTimeMillis() + 3500L);
        target.getWorld().spawnParticle(org.bukkit.Particle.END_ROD,
            target.getLocation().add(0,2,0), 3, 0.2,0.2,0.2);
    }
    public static boolean consumeMark(org.bukkit.entity.LivingEntity target) {
        Long exp = sunlightMark.get(target.getUniqueId());
        if (exp == null || System.currentTimeMillis() > exp) return false;
        sunlightMark.remove(target.getUniqueId());
        return true;
    }

    static class AA extends BasicAttackAbility {
        AA(){super("leona",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            // Appliquer la marque sur chaque AA de Leona
            applyMark(tgt);
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_leona","Bouclier de l'Aube",Material.GOLD_INGOT,AbilitySlot.Q,
            new double[]{6,6,6,6,6},5,0,DamageType.MAGICAL);
            resourceCost = 45;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : prochaine attaque renforcée = stun 1s + dégâts magiques 20/45/70/95/120 + 30% AP
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){c.sendActionBar(Component.text("☀ Bouclier de l'Aube prêt (frappe un ennemi)",NamedTextColor.YELLOW));return;}
            double[] base=fr.lolmc.util.Balance.base("q_leona",new double[]{20,45,70,95,120});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_leona","ap",0.3);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            if(tgt instanceof Player __p){
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,10,false,true)); // stun 1s
                __p.sendActionBar(Component.text("☀ STUN 1s — Bouclier de l'Aube!",NamedTextColor.YELLOW));
            }
            applyMark(tgt); // marque Lumière du Soleil
            c.getWorld().spawnParticle(Particle.END_ROD,tgt.getLocation().add(0,1,0),8,0.3,0.3,0.3);
            c.getWorld().playSound(c.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_leona",new double[]{20,45,70,95,120});
            return String.format("Prochaine attaque: %.0f dégâts (+30%%AP) + stun 1s.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_leona","ap",0.3));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_leona","Éclipse",Material.GOLDEN_CHESTPLATE,AbilitySlot.W,
            new double[]{14,13.5,13,12.5,12},0,3,DamageType.MAGICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : +20 armure, +20 RM pendant 3s puis explosion
            c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,60,2,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,60,0,false,true));
            var __cm = LolPlugin.getInstance().getChampionManager();
            if (__cm.hasChampion(c)) {
                __cm.getChampion(c).getStats().addBonusArmor(20);
                __cm.getChampion(c).getStats().addBonusMR(20);
            }
            c.sendActionBar(Component.text("🛡 Éclipse! +20 Armure/RM + explosion dans 3s",NamedTextColor.GOLD));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 0.8f);
            double[] base=fr.lolmc.util.Balance.base("w_leona",new double[]{80,120,160,200,240});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_leona","ap",0.4);
            new BukkitRunnable(){
                @Override public void run(){
                    boolean hit=false;
                    for(var __t : TargetingUtil.enemiesAround(c, 3.5)){
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        hit=true;
                    }
                    c.getWorld().spawnParticle(Particle.FLASH,c.getLocation().add(0,1,0),2);
                    c.getWorld().spawnParticle(Particle.END_ROD,c.getLocation().add(0,1,0),25,2.5,1,2.5);
                    // Si touché un ennemi, garde les bonus 3s de plus
                    if(hit){
                        c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,60,2,false,true));
                        c.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,60,0,false,true));
                    } else {
                        // Retirer les bonus si pas d'ennemi touché
                        if (__cm.hasChampion(c)) {
                            __cm.getChampion(c).getStats().addBonusArmor(-20);
                            __cm.getChampion(c).getStats().addBonusMR(-20);
                        }
                    }
                    // Toujours retirer après 6s (3s init + 3s bonus)
                    new BukkitRunnable() {
                        @Override public void run() {
                            if (__cm.hasChampion(c)) {
                                __cm.getChampion(c).getStats().addBonusArmor(-20);
                                __cm.getChampion(c).getStats().addBonusMR(-20);
                            }
                        }
                    }.runTaskLater(LolPlugin.getInstance(), 60L);
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
                }
            }.runTaskLater(LolPlugin.getInstance(),60L); // explosion après 3s
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_leona",new double[]{80,120,160,200,240});
            return String.format("Armure/RM + réduction de dégâts 3s, puis explosion %.0f dégâts (+40%%AP).",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_leona","ap",0.4));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_leona","Lame du Zénith",Material.BLAZE_ROD,AbilitySlot.E,
            new double[]{12,10.5,9,7.5,6},20,0,DamageType.MAGICAL);
            resourceCost = 55;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot ligne 60-200 + 40% AP, dash sur le DERNIER ennemi touché + root 0.5s
            double[] base=fr.lolmc.util.Balance.base("e_leona",new double[]{60,95,130,165,200});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_leona","ap",0.4);
            var hits=TargetingUtil.skillshot(c, 9.0, 1.0, true); // traverse, touche tous
            if(hits.isEmpty()){c.sendActionBar(Component.text("⚔ Lame manquée!",NamedTextColor.GRAY));return;}
            for(var __t : hits) TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
            // Dash sur le dernier ennemi touché + root
            var last=hits.get(hits.size()-1);
            var dest=last.getLocation().clone().subtract(last.getLocation().getDirection().multiply(1.0));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            if(last instanceof Player __p){
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,10,10,false,true)); // root 0.5s
                __p.sendActionBar(Component.text("⚔ Enraciné — Lame du Zénith!",NamedTextColor.YELLOW));
            }
            c.getWorld().spawnParticle(Particle.END_ROD,dest.add(0,1,0),5,1,0,1);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_leona",new double[]{60,95,130,165,200});
            return String.format("Skillshot: %.0f dégâts (+40%%AP), dash sur le dernier ennemi touché + root.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_leona","ap",0.4));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_leona","Éruption Solaire",Material.SUNFLOWER,AbilitySlot.R,
            new double[]{90,75,60},25,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : zone au sol visée, 150/250/350 + 100% AP. Centre = STUN, extérieur = ralentit 80%
            Location loc=TargetingUtil.getAimedGroundLocation(c, 10.0);
            double[] base=fr.lolmc.util.Balance.base("r_leona",new double[]{150,250,350});int r=Math.min(getLevel()-1,2);double dmg=base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_leona","ap",1.0);
            loc.getWorld().spawnParticle(Particle.END_ROD,loc,15,2,0.2,2);
            new BukkitRunnable(){
                @Override public void run(){
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 4.0)){
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        double distCenter=__t.getLocation().distance(loc);
                        if(__t instanceof Player __p){
                            if(distCenter<=1.5){ // centre = stun
                                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,35,10,false,true));
                                __p.sendActionBar(Component.text("☀ ÉRUPTION SOLAIRE! STUN",NamedTextColor.GOLD));
                            } else { // extérieur = ralentit 80%
                                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,35,4,false,true));
                                __p.sendActionBar(Component.text("☀ Éruption Solaire! Ralenti",NamedTextColor.GOLD));
                            }
                        }
                    }
                    loc.getWorld().spawnParticle(Particle.FLASH,loc,3);
                    loc.getWorld().spawnParticle(Particle.END_ROD,loc,40,3,1,3);
                    loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1f);
                }
            }.runTaskLater(LolPlugin.getInstance(),13L); // délai 0.625s
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_leona",new double[]{150,250,350});int r=Math.min(getLevel()-1,2);
            return String.format("Zone visée: %.0f dégâts (+100%%AP). Centre = stun, extérieur = ralentit 80%%.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_leona","ap",1.0));
        }
    }
}