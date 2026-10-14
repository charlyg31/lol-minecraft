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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public final class Amumu extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Amumu() {
        super("amumu", "Amumu", ChampionRole.JUNGLE,
                new ChampionStats(685,57,0,33,32,0.736,0,335,1.25,9));
        getStats().setGrowthStats(94,3.8,4,2.05,0.0218,0.85);
        setLolAttackRange(125); // stats LoL (wiki)
    }
    // Caprice (wiki) : chaque attaque de base subie réduit la recharge du E de 0.75 s
    @Override public void onBasicAttacked(Player self, Player attacker) {
        var e = getAbility(3);
        if (e != null && getLevelSystem().getAbilityRank(3) > 0) e.reduceCooldown(self, 0.75);
    }

    // Passif de Caprice (données du jeu) : dégâts physiques reçus réduits de 5/7/9/11/13 (max 50 %)
    // (+3 % armure et RM bonus, non modélisés). Manquait.
    private double appliedReduction = 0;
    @Override public void onAbilityRanksChanged() {
        int r = getLevelSystem().getAbilityRank(3);
        double red = r > 0 ? 3 + 2 * r : 0;
        getStats().addFlatPhysicalReduction(red - appliedReduction);
        appliedReduction = red;
    }

    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(9, ResourceSystem.ResourceType.MANA, 285, 40, 7.4, 0.55);
    }

    // Passif Toucher Maudit : les AA et le R appliquent la Malédiction 3 s. Les cibles maudites
    // subissent 10 % des dégâts magiques reçus en dégâts bruts, de TOUTE source (DamageUtil).
    private static final java.util.Map<java.util.UUID, Long> cursed = new java.util.concurrent.ConcurrentHashMap<>();
    // Désespoir actif (une seule aura par Amumu : la relancer la désactive)
    private static final java.util.Map<java.util.UUID, org.bukkit.scheduler.BukkitTask> despair = new java.util.concurrent.ConcurrentHashMap<>();
    public static void applyCurse(org.bukkit.entity.LivingEntity tgt) { cursed.put(tgt.getUniqueId(), System.currentTimeMillis()+3000L); }
    public static boolean isCursed(java.util.UUID id) { Long u=cursed.get(id); return u!=null && u>System.currentTimeMillis(); }
    @Override public void resetState(java.util.UUID id) { cursed.remove(id); }
    @Override public void resetAllState()                { cursed.clear(); }

    static class AA extends BasicAttackAbility {
        AA(){super("amumu",Material.IRON_SWORD,2.0f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            applyCurse(tgt); // Toucher Maudit
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_amumu","Lancer de Bandage",Material.STRING,AbilitySlot.Q,
                new double[]{16,15,14,13,12},20,0,DamageType.MAGICAL);
            resourceCost = 50; maxCharges = 2; chargeLockout = 3;} // 2 charges, 3 s entre deux (wiki)
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Projectile réel : 1100 unités à 2000 unités/s (esquivable)
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), c.getEyeLocation().getDirection(), 11.0, 0.8, false, 20.0,
                    new org.bukkit.inventory.ItemStack(Material.STRING), 0.7f, Material.WHITE_STAINED_GLASS,
                    tgt -> bandageHit(c, s, tgt),
                    hits -> { if(hits.isEmpty()) c.sendActionBar(Component.text("🧻 Bandage manqué!",NamedTextColor.GRAY)); });
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1f, 0.8f);
        }

        private void bandageHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt){
            dashTo(c, tgt, -1.2); // tiré vers la cible
            double[] base=fr.lolmc.util.Balance.base("q_amumu",new double[]{70,95,120,145,170});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_amumu","ap",0.85);
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            fr.lolmc.LolPlugin.getInstance().getCCManager().stun(tgt, 20);
            fr.lolmc.LolPlugin.getInstance().getCCManager().markCoordinatedFire(c, tgt);
            fr.lolmc.LolPlugin.getInstance().getCCManager().applyZekeConduit(c, tgt);
            fr.lolmc.LolPlugin.getInstance().getCCManager().applyAftershock(c, tgt);
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
                new double[]{1,1,1,1,1},0,3,DamageType.MAGICAL);
            resourceCost = 0;} // 8 mana/s prélevés pendant l'aura
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : aura à (dés)activer, 8 mana/s ; toutes les 0.5 s : 5 + 0.5-1 % (+0.25 %/100 AP)
            // des PV max de la cible. Avant : 5 s seulement et 1-3 % des PV max par 0.5 s.
            var running = despair.remove(c.getUniqueId());
            if (running != null && !running.isCancelled()) {
                running.cancel();
                c.sendActionBar(Component.text("😢 Désespoir désactivé",NamedTextColor.GRAY));
                return;
            }
            c.sendActionBar(Component.text("😢 Désespoir activé (8 mana/s)",NamedTextColor.DARK_BLUE));
            double[] pctHP={0.005,0.00625,0.0075,0.00875,0.01};
            final W self=this;
            despair.put(c.getUniqueId(), new BukkitRunnable(){
                int ticks=0;
                @Override public void run(){
                    var cm=LolPlugin.getInstance().getChampionManager();
                    if(!c.isOnline() || !cm.hasChampion(c) || c.getGameMode()==GameMode.SPECTATOR){
                        despair.remove(c.getUniqueId()); cancel(); return;
                    }
                    // 8 mana par seconde ; arrêt si plus assez de mana
                    if(ticks%2==0){
                        var res=cm.getChampion(c).getResourceSystem();
                        if(res!=null && !res.consume(8)){
                            despair.remove(c.getUniqueId());
                            c.sendActionBar(Component.text("😢 Désespoir : plus de mana",NamedTextColor.GRAY));
                            cancel(); return;
                        }
                    }
                    double pct=pctHP[self.getLevel()-1]+s.getFinalAP()/100.0*0.0025;
                    for(var __t : TargetingUtil.entitiesInRadius(c, c.getLocation(), 4.0)){
                        double dmg=5+TargetingUtil.getRealMaxHealth(__t)*pct;
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                    }
                    fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                            c.getLocation().add(0,1,0), 2.0, Material.GRAY_STAINED_GLASS, 14, 0.3f, 0.1f, 6L);
                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,10L));
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] pctHP={0.5,0.625,0.75,0.875,1};
            return String.format("Aura (relancer : désactive, 8 mana/s): 5 + %.2f%% PV max de la cible/0.5s.",pctHP[getLevel()-1]);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_amumu","Caprice",Material.SLIME_BALL,AbilitySlot.E,
                new double[]{9,8,7,6,5},5,3,DamageType.MAGICAL);
            resourceCost = 35;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("e_amumu",new double[]{65,95,125,155,185});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_amumu","ap",0.5);
            for(var __t : TargetingUtil.entitiesInRadius(c, c.getLocation(), 4.0)){ // rayon 350 (wiki)
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
            }
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    c.getLocation().add(0,1,0), Material.GRAY_STAINED_GLASS, 0.3f, 1.5, 8, 8L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_amumu",new double[]{65,95,125,155,185});
            return String.format("%.0f dégâts magiques autour (+50%%AP). -0.75s de recharge par attaque subie.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("e_amumu","ap",0.5));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_amumu","Malédiction de la Momie",Material.IRON_NUGGET,AbilitySlot.R,
                new double[]{150,125,100},5,5,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("r_amumu",new double[]{200,300,400});int rr=Math.min(getLevel()-1,2);double dmg=base[rr]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_amumu","ap",0.8);
            for(var __t : TargetingUtil.enemiesAround(c, 6.0)){ // rayon 550 (wiki)
                applyCurse(__t); // R applique la Malédiction
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                __t.setVelocity(new Vector(0,0.4,0));
                fr.lolmc.LolPlugin.getInstance().getCCManager().stun(__t, 30);
                fr.lolmc.LolPlugin.getInstance().getCCManager().markCoordinatedFire(c, __t);
                fr.lolmc.LolPlugin.getInstance().getCCManager().applyZekeConduit(c, __t);
                fr.lolmc.LolPlugin.getInstance().getCCManager().applyAftershock(c, __t);
                if(__t instanceof Player __p) __p.sendActionBar(Component.text("⛓ MALÉDICTION DE LA MOMIE! Stun 1.5s",NamedTextColor.DARK_PURPLE));
            }
            fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                    c.getLocation().add(0,1,0), 3.0, Material.PURPLE_STAINED_GLASS, 20, 0.35f, 0.1f, 12L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_amumu",new double[]{200,300,400});int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts (+80%%AP) + étourdit 1.5s tous les ennemis autour.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_amumu","ap",0.8));
        }
    }
}