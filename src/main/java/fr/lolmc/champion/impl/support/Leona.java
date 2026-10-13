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
import org.bukkit.scheduler.BukkitRunnable;

public final class Leona extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Leona() {
        super("leona", "Leona", ChampionRole.SUPPORT,
            new ChampionStats(646,60,0,43,32,0.625,0,335,1.25,8.5));
        getStats().setGrowthStats(101,3,4.8,2.05,0.029,0.85);
        setLolAttackRange(125); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(8.5, ResourceSystem.ResourceType.MANA, 302, 40, 6, 0.8);
    }

    // Passif Lumière du Soleil : les SORTS de Leona marquent les ennemis (pas ses attaques).
    // Un ALLIÉ qui frappe un ennemi marqué inflige 35-80 dégâts magiques bonus (pas Leona elle-même).
    static final java.util.Map<java.util.UUID, Long> sunlightMark
        = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Map<java.util.UUID, java.util.UUID> markOwner
        = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void resetState(java.util.UUID id)  { sunlightMark.remove(id); markOwner.remove(id); }
    @Override public void resetAllState()                 { sunlightMark.clear(); markOwner.clear(); }
    public static void applyMark(Player leona, org.bukkit.entity.LivingEntity target) {
        sunlightMark.put(target.getUniqueId(), System.currentTimeMillis() + 2500L); // 2.5 s (wiki)
        markOwner.put(target.getUniqueId(), leona.getUniqueId());
        fr.lolmc.util.VisualEffectUtil.impact(target.getWorld(),
            target.getLocation().add(0,2,0), Material.YELLOW_STAINED_GLASS, 0.24f, 5L);
    }
    /** Un allié de Leona (pas elle) frappe la cible marquée : la marque est consommée. */
    public static boolean consumeMark(Player attacker, org.bukkit.entity.LivingEntity target) {
        Long exp = sunlightMark.get(target.getUniqueId());
        if (exp == null || System.currentTimeMillis() > exp) return false;
        java.util.UUID owner = markOwner.get(target.getUniqueId());
        if (owner == null || owner.equals(attacker.getUniqueId())) return false;
        Player leona = org.bukkit.Bukkit.getPlayer(owner);
        if (leona == null || !LolPlugin.getInstance().getTeamManager().areAllies(leona, attacker)) return false;
        sunlightMark.remove(target.getUniqueId());
        markOwner.remove(target.getUniqueId());
        return true;
    }

    static class AA extends BasicAttackAbility {
        AA(){super("leona",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
        // (LoL : seuls les sorts de Leona appliquent Lumière du Soleil, pas ses attaques)
    }

    static class Q extends BaseAbility {
        Q(){super("q_leona","Bouclier de l'Aube",Material.GOLD_INGOT,AbilitySlot.Q,
            new double[]{6,6,6,6,6},5,0,DamageType.MAGICAL);
            resourceCost = 45;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : prochaine attaque renforcée = stun 1s + dégâts magiques 20/45/70/95/120 + 30% AP
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,2.5); if(tgt==null){cancelCast();c.sendActionBar(Component.text("☀ Bouclier de l'Aube prêt (frappe un ennemi)",NamedTextColor.YELLOW));return;}
            double[] base=fr.lolmc.util.Balance.base("q_leona",new double[]{20,45,70,95,120});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_leona","ap",0.3);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            if(tgt instanceof Player __p){
                fr.lolmc.LolPlugin.getInstance().getCCManager().stun(__p, 20); // stun 1s
                __p.sendActionBar(Component.text("☀ STUN 1s — Bouclier de l'Aube!",NamedTextColor.YELLOW));
            }
            applyMark(c, tgt); // marque Lumière du Soleil
            fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                    tgt.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.3f, 5L);
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
            // Wiki : +20-50 armure et RM (+20 % des bonus) pendant 3 s (+3 s si l'explosion touche)
            var __cm = LolPlugin.getInstance().getChampionManager();
            final double res = new double[]{20,27.5,35,42.5,50}[getLevel()-1]
                    + (__cm.hasChampion(c) ? __cm.getChampion(c).getStats().getBonusArmor()*0.20 : 0);
            if (__cm.hasChampion(c)) {
                __cm.getChampion(c).getStats().addBonusArmor(res);
                __cm.getChampion(c).getStats().addBonusMR(res);
            }
            c.sendActionBar(Component.text("🛡 Éclipse! +" + (int)res + " Armure/RM + explosion dans 3s",NamedTextColor.GOLD));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 0.8f);
            double[] base=fr.lolmc.util.Balance.base("w_leona",new double[]{80,120,160,200,240});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_leona","ap",0.7);
            new BukkitRunnable(){
                @Override public void run(){
                    boolean hit=false;
                    for(var __t : TargetingUtil.enemiesAround(c, 5.0)){ // rayon 450 (wiki)
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        applyMark(c, __t);
                        hit=true;
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                            c.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.35f, 2.5, 12, 8L);
                    // Si touché un ennemi, garde les bonus 3s de plus
                    if(hit){
                        // bonus conservés 3 s de plus (retirés par la tâche ci-dessous)
                    } else {
                        // Retirer les bonus si pas d'ennemi touché
                        if (__cm.hasChampion(c)) {
                            __cm.getChampion(c).getStats().addBonusArmor(-res);
                            __cm.getChampion(c).getStats().addBonusMR(-res);
                        }
                    }
                    // Si touché : retirer après les 3 s supplémentaires (sinon déjà retirés juste au-dessus ;
                    // les retirer une seconde fois faisait perdre 20 armure/RM pour toute la partie)
                    if (hit) new BukkitRunnable() {
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
            return String.format("Armure/RM + réduction de dégâts 3s, puis explosion %.0f dégâts (+70%%AP).",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_leona","ap",0.7));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_leona","Lame du Zénith",Material.BLAZE_ROD,AbilitySlot.E,
            new double[]{14,13.5,13,12.5,12},20,0,DamageType.MAGICAL);
            resourceCost = 55;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : skillshot ligne 60-200 + 40% AP, dash sur le DERNIER ennemi touché + root 0.5s
            double[] base=fr.lolmc.util.Balance.base("e_leona",new double[]{60,95,130,165,200});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_leona","ap",0.4);
            var hits=TargetingUtil.skillshot(c, 9.0, 1.0, true); // traverse, touche tous
            if(hits.isEmpty()){c.sendActionBar(Component.text("⚔ Lame manquée!",NamedTextColor.GRAY));return;}
            for(var __t : hits){ TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL); applyMark(c, __t); }
            // Dash sur le dernier ennemi touché + root
            var last=hits.getLast();
            dashTo(c, last, -1.0);
            if(last instanceof Player __p){
                fr.lolmc.LolPlugin.getInstance().getCCManager().root(__p, 10); // root 0.5s
                __p.sendActionBar(Component.text("⚔ Enraciné — Lame du Zénith!",NamedTextColor.YELLOW));
            }
            fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                    c.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.28f, 4L);
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
            Location loc=TargetingUtil.getAimedGroundLocation(c, 12.0); // 1200 unités
            double[] base=fr.lolmc.util.Balance.base("r_leona",new double[]{150,250,350});int r=Math.min(getLevel()-1,2);double dmg=base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_leona","ap",1.0);
            fr.lolmc.util.VisualEffectUtil.groundRing(loc.getWorld(), loc, aoeRadius,
                    Material.YELLOW_STAINED_GLASS, 20, 0.4f, 0.12f, 15L);
            new BukkitRunnable(){
                @Override public void run(){
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 3.9)){ // rayon 325 (wiki)
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        applyMark(c, __t);
                        double distCenter=__t.getLocation().distance(loc);
                        if(__t instanceof Player __p){
                            var cc=LolPlugin.getInstance().getCCManager();
                            if(distCenter<=1.5){ // centre = stun 1.75s (35 ticks)
                                if(cc!=null) cc.stun(__p, 35);
                                __p.sendActionBar(Component.text("☀ ÉRUPTION SOLAIRE! STUN 1.75s",NamedTextColor.GOLD));
                            } else { // extérieur = ralentit 80% pendant 1.75s
                                fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p, 0.80, 35); // 80 % 1.75 s (wiki)
                                __p.sendActionBar(Component.text("☀ Éruption Solaire! Ralenti 80%%",NamedTextColor.GOLD));
                            }
                        }
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(loc.getWorld(),
                            loc, Material.YELLOW_STAINED_GLASS, 0.4f, 3.0, 16, 10L);
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