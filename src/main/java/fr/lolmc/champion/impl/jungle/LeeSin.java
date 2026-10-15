package fr.lolmc.champion.impl.jungle;

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

public final class LeeSin extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public LeeSin() {
        super("leesin", "Lee Sin", ChampionRole.JUNGLE,
                new ChampionStats(645,66,0,36,32,0.651,0,345,1.25,7.5));
        getStats().setGrowthStats(108,3.4,4.5,2.05,0.03,0.7);
        setLolAttackRange(125); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(7.5, ResourceSystem.ResourceType.ENERGY, 200, 0, 50, 0);
    }

    // Passif Rafale : après chaque sort, les 2 prochaines attaques (3 s) gagnent +40 % de vitesse
    // d'attaque et rendent de l'énergie. Avant : énergie seulement, sans la vitesse d'attaque.
    private static final java.util.Map<java.util.UUID, Integer> flurryCharges
        = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> flurryUntil = new java.util.concurrent.ConcurrentHashMap<>();
    /** Stats sur lesquelles le bonus de vitesse d'attaque est appliqué (pour le retirer). */
    private static final java.util.Map<java.util.UUID, ChampionStats> flurryAS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final double FLURRY_AS = 0.40;

    public static void onSpellCast(java.util.UUID id) {
        flurryCharges.put(id, 2); // 2 attaques renforcées
        flurryUntil.put(id, System.currentTimeMillis() + 3000L);
        var p = org.bukkit.Bukkit.getPlayer(id);
        var cm = LolPlugin.getInstance().getChampionManager();
        if (p != null && cm.hasChampion(p) && !flurryAS.containsKey(id)) {
            var st = cm.getChampion(p).getStats();
            st.addBonusAttackSpeed(FLURRY_AS);
            flurryAS.put(id, st);
        }
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
            if (System.currentTimeMillis() >= flurryUntil.getOrDefault(id, 0L)) endFlurry(id);
        }, 61L);
    }

    private static void endFlurry(java.util.UUID id) {
        flurryCharges.remove(id);
        var st = flurryAS.remove(id);
        if (st != null) st.addBonusAttackSpeed(-FLURRY_AS);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("leesin",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            int charges = flurryCharges.getOrDefault(c.getUniqueId(), 0);
            if (charges > 0) {
                flurryCharges.put(c.getUniqueId(), charges - 1);
                if (charges - 1 == 0) endFlurry(c.getUniqueId());
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(c)) {
                    // Wiki : 20/30/40 énergie à la 1re attaque, 10/15/20 à la 2e (selon le niveau)
                    int lvl = cm.getChampion(c).getLevelSystem().getLevel();
                    int tier = lvl >= 13 ? 2 : lvl >= 7 ? 1 : 0;
                    int gain = charges == 2 ? new int[]{20,30,40}[tier] : new int[]{10,15,20}[tier];
                    cm.getChampion(c).getResourceSystem().addCurrent(gain);
                    c.sendActionBar(net.kyori.adventure.text.Component.text(
                        "⚡ Rafale! +" + gain + " énergie (" + (charges-1) + " charge restante)",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                }
            }
        }
    }

    private static final Map<UUID,UUID> sonicTarget=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void resetState(UUID id){ sonicTarget.remove(id); endFlurry(id); flurryUntil.remove(id); ironWillUntil.remove(id); crippleUntil.remove(id); tempestMarked.remove(id); }
    @Override public void resetAllState(){
        sonicTarget.clear(); flurryCharges.clear(); flurryUntil.clear(); ironWillUntil.clear(); crippleUntil.clear(); tempestMarked.clear();
        flurryAS.values().forEach(st -> st.addBonusAttackSpeed(-FLURRY_AS)); flurryAS.clear();
    }

    static class Q extends BaseAbility {
        // onSpellCast appelé dans cast() pour activer Flurry
        Q(){super("q_leesin","Onde Sonique",Material.ECHO_SHARD,AbilitySlot.Q,
                new double[]{10,9,8,7,6},15,0,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            fr.lolmc.champion.impl.jungle.LeeSin.onSpellCast(c.getUniqueId()); // Flurry

            UUID marked=sonicTarget.get(c.getUniqueId());
            if(marked!=null){
                org.bukkit.entity.LivingEntity tgt=null;
                for(var e: c.getWorld().getNearbyLivingEntities(c.getLocation(),15)) if(e.getUniqueId().equals(marked)) tgt=e;
                if(tgt!=null){
                    dashTo(c, tgt, -1.0);
                    double[] base=fr.lolmc.util.Balance.base("q_leesin",new double[]{60,90,120,150,180});

                    double missingPct=1.0-(TargetingUtil.getRealHealth(tgt)/Math.max(1, TargetingUtil.getRealMaxHealth(tgt)));
                    double dmg=(base[getLevel()-1]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("q_leesin","ad",0.9))*(1.0+missingPct);
                    TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
                    c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.9f);
                }
                sonicTarget.remove(c.getUniqueId());
                resourceCost = 50;
                return;
            }
            double[] base=fr.lolmc.util.Balance.base("q_leesin",new double[]{60,90,120,150,180});double dmg=base[getLevel()-1]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("q_leesin","ad",0.9);
            // Projectile réel (1200 unités à 1800/s). La recharge attend son résultat : manqué →
            // recharge tout de suite ; touché → 3 s pour la 2e activation (Coup Résonnant)
            deferCooldown();
            final BaseAbility q = this;
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), c.getEyeLocation().getDirection(), 12.0, 0.6, false, 18.0,
                    new org.bukkit.inventory.ItemStack(Material.ECHO_SHARD), 0.8f, Material.LIGHT_GRAY_STAINED_GLASS,
                    tgt -> sonicHit(c, s, tgt, dmg, q),
                    hits -> { if(hits.isEmpty()){
                        c.sendActionBar(Component.text("🌊 Onde Sonique manquée!",NamedTextColor.GRAY));
                        q.triggerCooldown(c, s);
                    } });
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.5f);
        }

        private void sonicHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg, BaseAbility q){
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            sonicTarget.put(c.getUniqueId(), tgt.getUniqueId());
            resourceCost = 25; // Coup Résonnant : 25 d'énergie (wiki)
            final UUID markedId = tgt.getUniqueId();
            new BukkitRunnable(){@Override public void run(){
                if(markedId.equals(sonicTarget.get(c.getUniqueId()))){
                    sonicTarget.remove(c.getUniqueId());
                    resourceCost = 50;
                    q.triggerCooldown(c, s);
                }
            }}.runTaskLater(LolPlugin.getInstance(),60L);
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🌊 Onde Sonique! (recast pour dash)",NamedTextColor.YELLOW));
            fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                    tgt.getLocation().add(0,1,0), Material.LIGHT_GRAY_STAINED_GLASS, 0.4f, 5L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_leesin",new double[]{60,90,120,150,180});
            return String.format("Skillshot: %.0f dégâts (+90%%AD bonus). Recast = dash + bonus PV manquants.",base[getLevel()-1]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("q_leesin","ad",0.9));
        }
    }

    private static final Map<UUID,Long> ironWillUntil=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID,Long> crippleUntil=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID,java.util.Set<UUID>> tempestMarked=new java.util.concurrent.ConcurrentHashMap<>();

    static class W extends BaseAbility {
        W(){super("w_leesin","Protection",Material.SHIELD,AbilitySlot.W,
                new double[]{7,7,7,7,7},15,0,DamageType.TRUE);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            fr.lolmc.champion.impl.jungle.LeeSin.onSpellCast(c.getUniqueId()); // Flurry
            var cm=LolPlugin.getInstance().getChampionManager();
            UUID id=c.getUniqueId();
            // 2e activation, Volonté de fer (wiki) : 10-26 % d'omnivampirisme pendant 4 s, 25 d'énergie.
            // Avant : appliquée d'office avec la Protection.
            if(ironWillUntil.getOrDefault(id,0L)>System.currentTimeMillis()){
                ironWillUntil.remove(id);
                resourceCost=50;
                double[] omni={0.10,0.14,0.18,0.22,0.26};
                double o=omni[getLevel()-1];
                if(cm.hasChampion(c)){
                    final var fstats=cm.getChampion(c).getStats();
                    fstats.addBonusOmnivamp(o);
                    new BukkitRunnable(){@Override public void run(){ fstats.addBonusOmnivamp(-o); }}.runTaskLater(LolPlugin.getInstance(), 80L);
                }
                triggerCooldown(c, s);
                c.sendActionBar(Component.text("🛡 Volonté de Fer : omnivampirisme 4s",NamedTextColor.GREEN));
                return;
            }
            Player dest = (t!=null && LolPlugin.getInstance().getTeamManager().areAllies(c,t)) ? t : c; // allié visé, sinon soi
            if(!dest.equals(c)){ // bond vers l'allié uniquement
                Location safe=safeTeleport(c.getLocation(),dest.getLocation());
                c.teleport(safe);
            }
            // Bouclier 60/105/150/195/240 +80%AP pendant 2s (40 ticks) sur Lee Sin (et la cible si champion)
            double[] shieldBase={60,105,150,195,240};
            double shield=shieldBase[getLevel()-1]+s.getFinalAP()*0.80;
            if(cm.hasChampion(c)) cm.getChampion(c).getStats().addShield(shield);
            if(!dest.equals(c) && cm.hasChampion(dest)) { cm.getChampion(dest).getStats().addShield(shield); fr.lolmc.item.ItemEffects.onAllyHealShield(c, dest, 0, shield); }
            final Player fdest=dest; final double fsh=shield; final Player fc=c;
            new BukkitRunnable(){@Override public void run(){
                if(cm.hasChampion(fc)) cm.getChampion(fc).getStats().addShield(-fsh);
                if(!fdest.equals(fc) && cm.hasChampion(fdest)) cm.getChampion(fdest).getStats().addShield(-fsh);
            }}.runTaskLater(LolPlugin.getInstance(), 40L);
            // Fenêtre de 3 s pour la Volonté de fer ; la recharge démarre après
            deferCooldown();
            resourceCost=25;
            final long until=System.currentTimeMillis()+3000L;
            ironWillUntil.put(id, until);
            final BaseAbility w=this;
            new BukkitRunnable(){@Override public void run(){
                if(ironWillUntil.getOrDefault(id,0L)==until){ ironWillUntil.remove(id); resourceCost=50; w.triggerCooldown(c, s); }
            }}.runTaskLater(LolPlugin.getInstance(), 60L);
            c.sendActionBar(Component.text(String.format("🛡 Protection %.0f ! (relancer : Volonté de Fer)",shield),NamedTextColor.GREEN));
            c.getWorld().playSound(c.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] shieldBase={60,105,150,195,240};
            return String.format("Dash vers un allié + bouclier %.0f (+80%%AP) 2s. Recast: omnivamp 4s.",shieldBase[getLevel()-1]+s.getFinalAP()*0.80);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_leesin","Tempête de Flammes",Material.FIRE_CHARGE,AbilitySlot.E,
                new double[]{8,8,8,8,8},5,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            fr.lolmc.champion.impl.jungle.LeeSin.onSpellCast(c.getUniqueId()); // Flurry
            UUID id=c.getUniqueId();
            // 2e activation, Estropier (wiki) : ralentit de 35-75 % (décroît sur 4 s) les ennemis
            // marqués par la Tempête et encore proches. Avant : ralentissement appliqué d'office.
            if(crippleUntil.getOrDefault(id,0L)>System.currentTimeMillis()){
                crippleUntil.remove(id);
                resourceCost=50;
                var marked=tempestMarked.remove(id);
                if(marked!=null) for(UUID mid : marked){
                    var e=org.bukkit.Bukkit.getEntity(mid);
                    if(e instanceof org.bukkit.entity.LivingEntity le && !le.isDead() && le.getWorld().equals(c.getWorld())
                            && le.getLocation().distanceSquared(c.getLocation())<=5.0*5.0)
                        fr.lolmc.LolPlugin.getInstance().getCCManager().slowDecaying(le, new double[]{0.35,0.45,0.55,0.65,0.75}[getLevel()-1], 80);
                }
                triggerCooldown(c, s);
                c.sendActionBar(Component.text("🔥 Estropier!",NamedTextColor.GOLD));
                return;
            }
            // 90 % de l'AD total (wiki, patch 26.10) ; avant : AD bonus
            double[] base=fr.lolmc.util.Balance.base("e_leesin",new double[]{35,60,85,110,135});
            double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("e_leesin","ad",0.9);
            var marked=new java.util.HashSet<UUID>();
            for(var __t : TargetingUtil.enemiesAround(c, 5.0)){ // rayon 450 (wiki)
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                marked.add(__t.getUniqueId());
                // Marqués et révélés 4 s
                __t.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, 80, 0, false, false));
            }
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    c.getLocation().add(0,1,0), Material.LIGHT_GRAY_STAINED_GLASS, 0.4f, 2.0, 8, 6L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1.2f);
            if(marked.isEmpty()) return; // personne touché : pas de 2e activation
            tempestMarked.put(id, marked);
            deferCooldown();
            resourceCost=25;
            final long until=System.currentTimeMillis()+3000L;
            crippleUntil.put(id, until);
            final BaseAbility e=this;
            new BukkitRunnable(){@Override public void run(){
                if(crippleUntil.getOrDefault(id,0L)==until){ crippleUntil.remove(id); tempestMarked.remove(id); resourceCost=50; e.triggerCooldown(c, s); }
            }}.runTaskLater(LolPlugin.getInstance(), 60L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_leesin",new double[]{35,60,85,110,135});
            return String.format("%.0f dégâts magiques autour (+90%%AD), marque et révèle 4s. Relance (3s) : Estropier, ralentit les marqués.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("e_leesin","ad",0.9));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_leesin","Dragon's Rage",Material.DRAGON_EGG,AbilitySlot.R,
                new double[]{110,85,60},5,0,DamageType.PHYSICAL);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,4.4); if(tgt==null){cancelCast();c.sendActionBar(Component.text("🐉 Aucune cible visée",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("r_leesin",new double[]{175,400,625});
            int r=Math.min(getLevel()-1,2);
            double dmg=base[r]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("r_leesin","ad",2);
            // Ennemis percutés : + 12/15/18 % des PV bonus de la cible projetée (wiki)
            double kickedBonusHP=0;
            var cmR=LolPlugin.getInstance().getChampionManager();
            if(tgt instanceof Player kp && cmR.hasChampion(kp)) kickedBonusHP=cmR.getChampion(kp).getStats().getBonusHP();
            double collideDmg=dmg+kickedBonusHP*new double[]{0.12,0.15,0.18}[r];
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.PHYSICAL);
            Vector dir=tgt.getLocation().toVector().subtract(c.getLocation().toVector()).normalize();
            Vector kb=dir.clone().multiply(2.5); kb.setY(0.8);
            fr.lolmc.LolPlugin.getInstance().getCCManager().setAirborne(tgt, 20); // projeté 1 s (wiki)
            tgt.setVelocity(kb);
            Location landing=tgt.getLocation().clone().add(dir.clone().multiply(4));
            for(var __t : TargetingUtil.entitiesInRadius(c, landing, 3.0)){
                if(__t.equals(tgt)) continue;
                TargetingUtil.dealDamage(c, __t, collideDmg, TargetingUtil.DmgType.PHYSICAL);
                fr.lolmc.LolPlugin.getInstance().getCCManager().setAirborne(__t, 20); // percuté : projeté 1 s
            }
            if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("🐉 FURIE DU DRAGON!",NamedTextColor.RED));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.2f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_leesin",new double[]{175,400,625});int r=Math.min(getLevel()-1,2);
            return String.format("Kick: %.0f dégâts (+200%%AD bonus), projette la cible. Ennemis percutés: + %% PV bonus de la cible.",base[r]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("r_leesin","ad",2));
        }
    }
}