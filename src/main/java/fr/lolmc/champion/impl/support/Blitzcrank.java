package fr.lolmc.champion.impl.support;

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
import org.bukkit.util.Vector;

public final class Blitzcrank extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Blitzcrank() {
        super("blitzcrank", "Blitzcrank", ChampionRole.SUPPORT,
            new ChampionStats(600,62,0,37,32,0.625,0,325,1.25,7.5));
        getStats().setGrowthStats(109,3.5,4.7,2.05,0.0113,0.75);
        setLolAttackRange(125); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        // Ordre actuel de LoL : W = Poing de Force, E = Surrégime
        setAbility(2,new E()); setAbility(3,new W()); setAbility(4,new R());
        initSystems(7.5, ResourceSystem.ResourceType.MANA, 267, 40, 8.5, 0.8);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("blitzcrank",Material.IRON_INGOT,2.0f,DamageType.PHYSICAL);}
    }

    // Passif Barrière de Mana (wiki) : sous 30 % des PV, bouclier = 35 % du mana max pendant 10 s (90 s de recharge)
    private static final java.util.Map<java.util.UUID, Long> staticShieldCD = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void resetState(java.util.UUID id) { staticShieldCD.remove(id); }
    @Override public void resetAllState()               { staticShieldCD.clear(); }
    public static void tickStaticShield(org.bukkit.entity.Player p, fr.lolmc.champion.base.BaseChampion champ) {
        var hp = champ.getHPSystem(); var res = champ.getResourceSystem();
        if (hp.getHPRatio() < 0.30 && champ.getStats().getShield() <= 0) {
            Long last = staticShieldCD.get(p.getUniqueId());
            if (last == null || System.currentTimeMillis() - last > 90_000L) {
                double shield = res.getMax() * 0.35;
                if (shield > 0) {
                    champ.getStats().addShield(shield);
                    final var st = champ.getStats();
                    new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ st.addShield(-shield); }}
                            .runTaskLater(LolPlugin.getInstance(), 200L);
                    staticShieldCD.put(p.getUniqueId(), System.currentTimeMillis());
                    p.sendActionBar(net.kyori.adventure.text.Component.text(
                        "⚡ Bouclier Statique! +" + (int)shield, net.kyori.adventure.text.format.NamedTextColor.YELLOW));
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
            var hits=TargetingUtil.skillshot(c, 11.2, 0.7, false); // 1115 unités
            if(hits.isEmpty()){c.sendActionBar(Component.text("🪝 Grappin manqué!",NamedTextColor.GRAY));return;}
            var tgt=hits.getFirst();
            double[] base=fr.lolmc.util.Balance.base("q_blitzcrank",new double[]{110,160,210,260,310});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_blitzcrank","ap",1.5);
            // Animation : une seule ligne étirée qui suit le grappin (au lieu
            // d'une trainée de particules répétée à chaque tick).
            org.bukkit.Location hookStart = c.getEyeLocation();
            org.bukkit.Location hookEnd = tgt.getLocation().add(0,1,0);
            double hookDist = hookStart.distance(hookEnd);
            org.bukkit.util.Vector hookDir = hookEnd.toVector().subtract(hookStart.toVector()).normalize();
            long hookSteps = Math.max(3L, (long)(hookDist / 0.6)); // délai de voyage réel du grappin
            fr.lolmc.util.VisualEffectUtil.skillshotLine(hookStart, hookDir, hookDist,
                    Material.YELLOW_STAINED_GLASS, 0.12f, hookSteps);
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_PISTON_EXTEND, 1f, 0.8f);
            // Dégâts et attraction après le voyage
            final var finalTgt = tgt;
            new org.bukkit.scheduler.BukkitRunnable(){
                @Override public void run(){
                    TargetingUtil.dealDamage(c, finalTgt, dmg, TargetingUtil.DmgType.MAGICAL);
                    Vector pull=c.getLocation().toVector().subtract(finalTgt.getLocation().toVector()).normalize().multiply(2.0);
                    pull.setY(0.3); finalTgt.setVelocity(pull);
                    if(finalTgt instanceof Player __p){
                        fr.lolmc.LolPlugin.getInstance().getCCManager().stun(__p, 13); // étourdi 0.65 s (wiki)
                        __p.sendActionBar(Component.text("🪝 ATTRAPÉ! Grappin Fusée",NamedTextColor.YELLOW));
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(finalTgt.getWorld(),
                            finalTgt.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.28f, 0.4, 8, 6L);
                    finalTgt.getWorld().playSound(finalTgt.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.8f);
                }
            }.runTaskLater(LolPlugin.getInstance(), hookSteps);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_blitzcrank",new double[]{110,160,210,260,310});
            return String.format("Skillshot: %.0f dégâts (+150%%AP), attrape et tire la cible vers toi + stun.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_blitzcrank","ap",1.5));
        }
    }

    static class W extends BaseAbility {
        W(){super("e_blitzcrank","Surrégime",Material.REDSTONE,AbilitySlot.E,
            new double[]{15,15,15,15,15},0,0,DamageType.TRUE);
            resourceCost = 75;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 5 s, +30-70 % de vitesse d'attaque ; +60-80 % de vitesse qui décroît sur 2.9 s,
            // puis Blitzcrank est ralenti de 30 % pendant 1.5 s
            double asBonus=new double[]{0.30,0.40,0.50,0.60,0.70}[getLevel()-1];
            s.addBonusAttackSpeed(asBonus);
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedDecaying(c, new double[]{0.60,0.65,0.70,0.75,0.80}[getLevel()-1], 58);
            new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){
                s.addBonusAttackSpeed(-asBonus);
                fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(c, 0.30, 30);
            }}.runTaskLater(LolPlugin.getInstance(), 100L);
            c.sendActionBar(Component.text("⚡ SURRÉGIME 5s!",NamedTextColor.YELLOW));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "+vitesse de déplacement et d'attaque pendant 5s (puis ralenti bref).";}
    }

    static class E extends BaseAbility {
        E(){super("w_blitzcrank","Poing de Force",Material.LIGHTNING_ROD,AbilitySlot.W,
            new double[]{7,6.5,6,5.5,5},5,0,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : prochaine attaque renforcée = knockup 1s + 200% AD
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){cancelCast();c.sendActionBar(Component.text("⚡ Poing de Force prêt (frappe un ennemi)",NamedTextColor.YELLOW));return;}
            // Double l'attaque de base : AA (100% AD) + 100% AD bonus = 200% AD total
            double dmg=s.getFinalAD()*2.0+s.getFinalAP()*0.25; // attaque + 100 % AD (+25 % AP) (wiki)
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            fr.lolmc.LolPlugin.getInstance().getCCManager().setAirborne(tgt, 20); // projeté 1 s (wiki)
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
            for(var __t : TargetingUtil.enemiesAround(c, 6.0)){ // rayon 600 (wiki)
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
            fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                    c.getLocation().add(0,1,0), 3.0, Material.YELLOW_STAINED_GLASS, 20, 0.35f, 0.1f, 10L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_blitzcrank",new double[]{275,400,525});int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts foudre autour (+100%%AP) + silence 0.5s + détruit boucliers.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_blitzcrank","ap",1.0));
        }
    }
}