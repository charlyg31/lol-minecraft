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
        // Ordre de LoL (Data Dragon 16.20.1) : W = Surrégime, E = Poing de Force
        // (avant : inversés, avec les délais et coûts de l'autre sort)
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(7.5, ResourceSystem.ResourceType.MANA, 267, 40, 8.5, 0.8);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("blitzcrank",Material.IRON_INGOT,2.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            staticFieldMark(c, s, tgt);
        }
    }

    // Passif du Champ Statique (wiki) : tant que le R n'est pas en recharge, chaque attaque ajoute
    // une charge à la cible ; une charge par seconde la frappe : 50/100/150 (+30/40/50 % AP)
    // (+2 % du mana max) dégâts magiques. Manquait.
    private static final java.util.Map<java.util.UUID, Integer> staticCharges = new java.util.concurrent.ConcurrentHashMap<>();
    static void staticFieldMark(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(c)) return;
        var champ = cm.getChampion(c);
        int rRank = champ.getLevelSystem().getAbilityRank(4);
        var r = champ.getAbility(4);
        if (rRank <= 0 || r == null || r.isOnCooldown(c)) return;
        java.util.UUID id = tgt.getUniqueId();
        if (staticCharges.merge(id, 1, Integer::sum) > 1) return; // une tâche par cible
        int ri = Math.min(rRank, 3) - 1;
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                int left = staticCharges.getOrDefault(id, 0);
                if (left <= 0 || tgt.isDead() || !tgt.isValid() || !c.isOnline()) { staticCharges.remove(id); cancel(); return; }
                staticCharges.put(id, left - 1);
                double mana = champ.getResourceSystem() != null ? champ.getResourceSystem().getMax() : 0;
                double dmg = new double[]{50,100,150}[ri] + s.getFinalAP() * new double[]{0.30,0.40,0.50}[ri] + mana * 0.02;
                tgt.getWorld().strikeLightningEffect(tgt.getLocation());
                TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
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
            new double[]{20,19,18,17,16},20,0,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : SKILLSHOT signature. Attrape le 1er ennemi, 110-310 + 120% AP, stun + tire vers Blitz
            double[] base=fr.lolmc.util.Balance.base("q_blitzcrank",new double[]{110,160,210,260,310});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_blitzcrank","ap",1.2);
            // Projectile réel : la main du grappin part à 1800 unités/s et ramène le 1er ennemi
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), c.getEyeLocation().getDirection(), 10.8, 0.7, false, 18.0, // missile : 1080 unités à 1800/s (données du jeu)
                    new org.bukkit.inventory.ItemStack(Material.IRON_CHAIN), 0.9f, Material.YELLOW_STAINED_GLASS,
                    finalTgt -> {
                        TargetingUtil.dealDamage(c, finalTgt, dmg, TargetingUtil.DmgType.MAGICAL);
                        // Ramené jusque devant Blitzcrank (avant : poussée qui s'arrêtait à mi-chemin)
                        TargetingUtil.pullTo(finalTgt, c, 1.5, 25.0);
                        if(finalTgt instanceof Player __p){
                            fr.lolmc.LolPlugin.getInstance().getCCManager().stun(__p, 13); // étourdi 0.65 s (wiki)
                            __p.sendActionBar(Component.text("🪝 ATTRAPÉ! Grappin Fusée",NamedTextColor.YELLOW));
                        }
                        // Chaîne tendue entre Blitzcrank et sa prise
                        var hs=c.getEyeLocation(); var he=finalTgt.getLocation().add(0,1,0);
                        fr.lolmc.util.VisualEffectUtil.skillshotLine(hs, he.toVector().subtract(hs.toVector()).normalize(),
                                hs.distance(he), Material.YELLOW_STAINED_GLASS, 0.1f, 8L);
                        finalTgt.getWorld().playSound(finalTgt.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.8f);
                    },
                    hits -> { if(hits.isEmpty()) c.sendActionBar(Component.text("🪝 Grappin manqué!",NamedTextColor.GRAY)); });
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_PISTON_EXTEND, 1f, 0.8f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_blitzcrank",new double[]{110,160,210,260,310});
            return String.format("Skillshot: %.0f dégâts (+120%%AP), attrape et tire la cible vers toi + stun.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_blitzcrank","ap",1.2));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_blitzcrank","Surrégime",Material.REDSTONE,AbilitySlot.W,
            new double[]{15,15,15,15,15},0,0,DamageType.TRUE);
            resourceCost = 75;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 5 s, +30-70 % de vitesse d'attaque ; +60-80 % de vitesse qui décroît sur 2.9 s,
            // puis Blitzcrank est ralenti de 30 % pendant 1.5 s
            double asBonus=new double[]{0.30,0.40,0.50,0.60,0.70}[getLevel()-1];
            s.addBonusAttackSpeed(asBonus);
            // Données du jeu : de 60-80 % jusqu'à 10 % en 2.5 s, puis 10 % jusqu'à la fin des 5 s
            double ms=new double[]{0.60,0.65,0.70,0.75,0.80}[getLevel()-1];
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedDecaying(c, ms-0.10, 50);
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(c, 0.10, 100);
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
        E(){super("e_blitzcrank","Poing de Force",Material.LIGHTNING_ROD,AbilitySlot.E,
            new double[]{7,6.5,6,5.5,5},5,0,DamageType.PHYSICAL);
            resourceCost = 25;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : la prochaine attaque (5 s, remise à zéro de l'attaque) inflige 100 % AD (+25 % AP)
            // physiques en plus et projette la cible 1 s. Avant : frappe immédiate de la cible visée.
            fr.lolmc.champion.base.EmpoweredAttack.arm(c, 5000L, (att, tgt, normal) -> {
                TargetingUtil.dealDamage(att, tgt, s.getFinalAD()+s.getFinalAP()*0.25, TargetingUtil.DmgType.PHYSICAL);
                fr.lolmc.LolPlugin.getInstance().getCCManager().setAirborne(tgt, 20); // projeté 1 s (wiki)
                att.getWorld().strikeLightningEffect(tgt.getLocation());
                return false; // s'ajoute aux dégâts de l'attaque
            });
            c.sendActionBar(Component.text("👊 Poing de Force : prochaine attaque renforcée",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return String.format("Prochaine attaque (5s) : +%.0f dégâts (100%%AD +25%%AP) et projette 1s.",s.getFinalAD()+s.getFinalAP()*0.25);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_blitzcrank","Champ Statique",Material.COPPER_INGOT,AbilitySlot.R,
            new double[]{60,40,20},5,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : 275/400/525 + 100% AP, silence 0.5s + détruit les boucliers, autour
            double[] base=fr.lolmc.util.Balance.base("r_blitzcrank",new double[]{275,400,525});int r=Math.min(getLevel()-1,2);double dmg=base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_blitzcrank","ap",1);
            var cc=LolPlugin.getInstance().getCCManager();
            var cm=LolPlugin.getInstance().getChampionManager();
            for(var __t : TargetingUtil.enemiesAround(c, 6.0)){ // rayon 600 (wiki)
                // Détruit les boucliers avant les dégâts (signature du Champ Statique)
                // Tous les boucliers, anti-magie compris (avant : seulement les boucliers normaux)
                if(__t instanceof Player __sp && cm.hasChampion(__sp))
                    cm.getChampion(__sp).getStats().clearShields();
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p){
                    if(cc!=null) cc.silence(__p, 10); // vrai silence 0.5s
                    __p.sendActionBar(Component.text("⚡ CHAMP STATIQUE! Silence + boucliers détruits",NamedTextColor.AQUA));
                }
            }
            c.getWorld().strikeLightningEffect(c.getLocation());
            fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                    c.getLocation().add(0,1,0), 6.0, Material.YELLOW_STAINED_GLASS, 32, 0.6f, 0.1f, 10L); // rayon réel (600)
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_blitzcrank",new double[]{275,400,525});int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts foudre autour (+100%%AP) + silence 0.5s + détruit boucliers.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_blitzcrank","ap",1));
        }
    }
}