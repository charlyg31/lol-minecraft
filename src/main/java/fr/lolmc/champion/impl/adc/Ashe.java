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
import java.util.*;

public class Ashe extends BaseChampion {
    public Ashe() {
        super("ashe", "Ashe", ChampionRole.ADC,
            new ChampionStats(610,59,0,26,30,0.658,0,325,6.0,3.5));
        getStats().setGrowthStats(101.0,3.5,4.6,1.30,0.03000,0.55);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(528, 3.0, ResourceSystem.ResourceType.MANA, 480, 11.0);
        setAutoAttackRange(9.2);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("ashe",Material.ARROW,6.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            // Passif Tir de Givre : slow 15-30% selon niveau (2s = 40 ticks)
            if(tgt instanceof Player __p){
                var cm=LolPlugin.getInstance().getChampionManager();
                int lvl=cm.hasChampion(c)?cm.getChampion(c).getLevelSystem().getLevel():1;
                int amp=lvl>=10?1:0; // ~15% puis ~30%
                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,amp,false,true));
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_ashe","Tir Concentré",Material.SPECTRAL_ARROW,AbilitySlot.Q,
            new double[]{15,12.5,10,7.5,5},25,0,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            c.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,120,0,false,true));
            c.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,120,0,false,true));
            c.sendActionBar(Component.text("🏹 Tir Concentré 6s!",NamedTextColor.AQUA));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "6s: +15%% AD, les AA ralentissent davantage.";}
    }

    static class W extends BaseAbility {
        W(){super("w_ashe","Volée",Material.TIPPED_ARROW,AbilitySlot.W,
            new double[]{18,14.5,11,7.5,4},25,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : tire des flèches en cône. 60/95/130/165/200 + 100% AD, applique Tir de Givre (ralentit)
            double[] base=fr.lolmc.util.Balance.base("w_ashe",new double[]{60,95,130,165,200});double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("w_ashe","ad",1.0);
            var targets=TargetingUtil.enemiesInCone(c, 8.0, 45);
            for(var __t : targets){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if(__t instanceof Player __p)
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));
            }
            // Animation : flèches en éventail devant
            var dir=c.getEyeLocation().getDirection().normalize();
            for(double d=1; d<=8; d+=0.7){
                fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                        c.getEyeLocation().add(dir.clone().multiply(d)), Material.WHITE_STAINED_GLASS, 0.2f, 3L);
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_ashe",new double[]{60,95,130,165,200});
            return String.format("Cône de flèches: %.0f dégâts (+100%%AD) + ralentit.",base[getLevel()-1]+s.getFinalAD());
        }
    }

    static class E extends BaseAbility {
        E(){super("e_ashe","Faucon Explorateur",Material.FEATHER,AbilitySlot.E,
            new double[]{90,80,70,60,50},60,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Faucon qui voyage 40 blocs dans la direction du regard
            // Révèle les ennemis dans un rayon de 8 blocs autour de son trajet
            org.bukkit.Location start = c.getEyeLocation();
            org.bukkit.util.Vector dir = start.getDirection().normalize();
            int maxSteps = 80; // 40 blocs à 0.5 bloc/tick
            var tm = LolPlugin.getInstance().getTeamManager();
            var fogMgr = LolPlugin.getInstance().getFogOfWarManager();
            var casterTeam = tm.getTeam(c);
            var hawkDisplay = start.getWorld().spawn(start, org.bukkit.entity.BlockDisplay.class, disp -> {
                disp.setBlock(Material.YELLOW_STAINED_GLASS.createBlockData());
                disp.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                disp.setPersistent(false);
                disp.setInterpolationDuration(1);
                disp.setInterpolationDelay(0);
                float size = 0.22f;
                disp.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(-size / 2f, -size / 2f, -size / 2f),
                        new org.joml.Quaternionf(),
                        new org.joml.Vector3f(size, size, size),
                        new org.joml.Quaternionf()));
            });
            new org.bukkit.scheduler.BukkitRunnable() {
                int step = 0;
                final org.bukkit.Location pos = start.clone();
                @Override public void run() {
                    if (step >= maxSteps || !c.isOnline()) {
                        if (!hawkDisplay.isDead()) hawkDisplay.remove();
                        cancel(); return;
                    }
                    pos.add(dir.clone().multiply(0.5));
                    hawkDisplay.teleport(pos);
                    // Révèle la zone survolée pour l'équipe d'Ashe pendant 0.5s
                    // (fenêtre glissante qui suit le faucon le long de son trajet)
                    if (fogMgr != null && casterTeam != null) {
                        fogMgr.revealPoint(casterTeam, pos, 8.0, 10L);
                    }
                    // Révéler les ennemis dans un rayon de 8 blocs
                    for (var nearby : pos.getWorld().getNearbyEntities(pos, 8, 4, 8)) {
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
                    // S'arrêter sur un bloc solide
                    if (pos.getBlock().getType().isSolid()) {
                        if (!hawkDisplay.isDead()) hawkDisplay.remove();
                        cancel();
                    }
                }
            }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);
            c.sendActionBar(Component.text("🦅 Faucon envoyé!", NamedTextColor.YELLOW));
            c.getWorld().playSound(c.getLocation(), org.bukkit.Sound.ENTITY_PARROT_FLY, 0.8f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return "Envoie un faucon 40 blocs: révèle ennemis dans son trajet (GLOWING 3s).";
        }
    }

    static class R extends BaseAbility {
        R(){super("r_ashe","Flèche de Cristal Enchantée",Material.DIAMOND,AbilitySlot.R,
            new double[]{100,90,80},25,2,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot longue portée. Stun 1-3.5s selon distance parcourue. Zone autour = 50% dégâts + slow
            double[] base=fr.lolmc.util.Balance.base("r_ashe",new double[]{200,400,600});int rr=Math.min(getLevel()-1,2);double dmg=base[rr]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_ashe","ap",1.2);
            var hits=TargetingUtil.skillshot(c, 25.0, 1.2, false); // s'arrête au 1er champion
            if(hits.isEmpty()){c.sendActionBar(Component.text("❄ Flèche tirée (aucune cible touchée)",NamedTextColor.AQUA));return;}
            var main=hits.getFirst();
            // Distance parcourue → durée du stun (1 à 3.5s = 20 à 70 ticks)
            double dist=c.getLocation().distance(main.getLocation());
            int stunTicks=(int)Math.min(70, 20+dist*2.5);
            TargetingUtil.dealDamage(c, main, dmg, TargetingUtil.DmgType.MAGICAL);
            // Vrai stun (immobilise + empêche d'agir, réduit par la ténacité)
            fr.lolmc.LolPlugin.getInstance().getCCManager().stun(main, stunTicks);
            if(main instanceof Player __p){
                __p.sendActionBar(Component.text("❄ ÉTOURDI par la Flèche de Cristal!",NamedTextColor.AQUA));
            }
            // Zone autour de la cible principale : 50% dégâts + ralentissement
            for(var __t : TargetingUtil.entitiesInRadius(c, main.getLocation(), 3.0)){
                if(__t.equals(main)) continue;
                TargetingUtil.dealDamage(c, __t, dmg*0.5, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p2)__p2.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,2,false,true));
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