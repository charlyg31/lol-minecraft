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
import org.bukkit.attribute.Attribute; // AJOUT : Import de l'attribut
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class Amumu extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Amumu() {
        super("amumu", "Amumu", ChampionRole.JUNGLE,
                new ChampionStats(685,57,0,33,32,0.736,0,335,1.25,9.0));
        getStats().setGrowthStats(94.0,3.8,4.0,2.05,0.02180,0.85);
        setAutoAttackRange(2.3);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(613, 8.0, ResourceSystem.ResourceType.MANA, 480, 10.0);
    }

    // Passif Toucher Maudit : les AA et le R appliquent la Malédiction 3s.
    // Les cibles maudites subissent +10% de dégâts VRAIS de toute source magique.
    private static final java.util.Map<java.util.UUID, Long> cursed = new java.util.concurrent.ConcurrentHashMap<>();
    public static void applyCurse(org.bukkit.entity.LivingEntity tgt) { cursed.put(tgt.getUniqueId(), System.currentTimeMillis()+3000L); }
    public static boolean isCursed(java.util.UUID id) { Long u=cursed.get(id); return u!=null && u>System.currentTimeMillis(); }
    @Override public void resetState(java.util.UUID id) { cursed.remove(id); }
    @Override public void resetAllState()                { cursed.clear(); }
    /** Bonus de dégâts vrais (10% des dégâts magiques) si la cible est maudite. */
    public static void onMagicHit(org.bukkit.entity.Player amumu, org.bukkit.entity.LivingEntity tgt, double magicDmg) {
        if (isCursed(tgt.getUniqueId())) {
            fr.lolmc.util.TargetingUtil.dealDamage(amumu, tgt, magicDmg*0.10, fr.lolmc.util.TargetingUtil.DmgType.TRUE);
        }
    }

    static class AA extends BasicAttackAbility {
        AA(){super("amumu",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            applyCurse(tgt); // Toucher Maudit
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_amumu","Lancer de Bandage",Material.STRING,AbilitySlot.Q,
                new double[]{12,11,10,9,8},20,0,DamageType.MAGICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            var hits=TargetingUtil.skillshot(c, 12.0, 1.0, false);
            if(hits.isEmpty()){c.sendActionBar(Component.text("🧻 Bandage manqué!",NamedTextColor.GRAY));return;}
            var tgt=hits.get(0);
            Location dest=tgt.getLocation().clone().subtract(tgt.getLocation().getDirection().multiply(1.5));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            double[] base=fr.lolmc.util.Balance.base("q_amumu",new double[]{70,95,120,145,170});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_amumu","ap",0.85);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            onMagicHit(c, tgt, dmg);
            fr.lolmc.LolPlugin.getInstance().getCCManager().stun(tgt, 20);
            if(tgt instanceof Player __p) __p.sendActionBar(Component.text("🧻 Lancer de Bandage! Stun 1s!",NamedTextColor.YELLOW));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_WOOL_PLACE, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_amumu",new double[]{70,95,120,145,170});
            return String.format("Skillshot: %.0f dégâts (+85%%AP), stun 1s + tire Amumu vers la cible.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_amumu","ap",0.85));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_amumu","Désespoir",Material.CRYING_OBSIDIAN,AbilitySlot.W,
                new double[]{0,0,0,0,0},0,3,DamageType.MAGICAL);
            resourceCost = 8;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.sendActionBar(Component.text("😢 Désespoir actif 5s!",NamedTextColor.DARK_BLUE));
            double[] pctHP={0.01,0.015,0.02,0.025,0.03};
            double pct=pctHP[getLevel()-1];
            new BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    if(ticks>=10){cancel();return;}
                    for(var __t : TargetingUtil.entitiesInRadius(c, c.getLocation(), 3.5)){
                        // CORRECTION : Utilisation de l'attribut GENERIC_MAX_HEALTH au lieu de getMaxHealth() déprécié
                        var maxHealthAttr = __t.getAttribute(fr.lolmc.util.Compat.maxHealth());
                        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;

                        double dmg=maxHealth*pct+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_amumu","ap",0.01);
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        onMagicHit(c, __t, dmg);
                    }
                    c.getWorld().spawnParticle(Particle.FALLING_WATER,c.getLocation().add(0,1,0),10,2,0.5,2);
                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,10L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] pctHP={1,1.5,2,2.5,3};
            return String.format("Aura 5s: %.1f%% PV max de la cible/0.5s (dégâts magiques).",pctHP[getLevel()-1]);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_amumu","Caprice",Material.SLIME_BALL,AbilitySlot.E,
                new double[]{9,8,7,6,5},5,3,DamageType.MAGICAL);
            resourceCost = 35;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("e_amumu",new double[]{65,100,135,170,205});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_amumu","ap",0.5);
            for(var __t : TargetingUtil.entitiesInRadius(c, c.getLocation(), 3.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                onMagicHit(c, __t, dmg);
            }
            c.getWorld().spawnParticle(Particle.ANGRY_VILLAGER,c.getLocation().add(0,1,0),12,1.5,0.5,1.5);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_amumu",new double[]{65,100,135,170,205});
            return String.format("%.0f dégâts magiques autour (+50%%AP). CD réduit quand frappé.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_amumu","ap",0.5));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_amumu","Malédiction de la Momie",Material.IRON_NUGGET,AbilitySlot.R,
                new double[]{150,130,110},5,5,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("r_amumu",new double[]{200,300,400});int rr=Math.min(getLevel()-1,2);double dmg=base[rr]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_amumu","ap",0.8);
            for(var __t : TargetingUtil.enemiesAround(c, 5.0)){
                applyCurse(__t); // R applique la Malédiction
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                onMagicHit(c, __t, dmg);
                __t.setVelocity(new Vector(0,0.4,0));
                fr.lolmc.LolPlugin.getInstance().getCCManager().stun(__t, 30);
                if(__t instanceof Player __p) __p.sendActionBar(Component.text("⛓ MALÉDICTION DE LA MOMIE! Stun 1.5s",NamedTextColor.DARK_PURPLE));
            }
            c.getWorld().spawnParticle(Particle.END_ROD,c.getLocation().add(0,1,0),30,3,1,3);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_amumu",new double[]{200,300,400});int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts (+80%%AP) + étourdit 1.5s tous les ennemis autour.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_amumu","ap",0.8));
        }
    }
}