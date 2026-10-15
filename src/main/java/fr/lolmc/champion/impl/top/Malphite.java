package fr.lolmc.champion.impl.top;

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

public final class Malphite extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Malphite() {
        super("malphite", "Malphite", ChampionRole.TOP,
            new ChampionStats(665,62,0,40,28,0.736,0,335,1.25,7));
        getStats().setGrowthStats(104,4,4.95,2.05,0.034,0.55);
        setLolAttackRange(125); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(7, ResourceSystem.ResourceType.MANA, 280, 60, 7.3, 0.55);
    }

    // Passif Granite Shield : bouclier 10% HP max, se recharge 10s hors combat
    private static final java.util.Map<java.util.UUID, Long> shieldCooldown = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void resetState(java.util.UUID id)    { shieldCooldown.remove(id); thunderclapUntil.remove(id); armorApplied.remove(id); }
    @Override public void resetAllState()                   { shieldCooldown.clear(); thunderclapUntil.clear(); armorApplied.clear(); }
    /** Armure bonus du passif de W actuellement appliquée (par joueur). */
    private static final java.util.Map<java.util.UUID, Double> armorApplied = new java.util.concurrent.ConcurrentHashMap<>();
    public static void onDamageTaken(java.util.UUID id) { shieldCooldown.put(id, System.currentTimeMillis()); }
    public static void tickGraniteShield(org.bukkit.entity.Player p, fr.lolmc.champion.base.BaseChampion champ) {
        Long last = shieldCooldown.get(p.getUniqueId());
        // Recharge après 8/7/6 s sans dégâts selon le niveau (wiki ; avant : 10 s).
        // Tous les dégâts comptent (avant : seulement ceux de l'environnement, donc en combat le
        // bouclier se rechargeait chaque seconde)
        int lvl = champ.getLevelSystem().getLevel();
        long refresh = lvl >= 13 ? 6000L : lvl >= 7 ? 7000L : 8000L;
        boolean envOk = last == null || (System.currentTimeMillis() - last) >= refresh;
        if (envOk && champ.getHPSystem().msSinceLastDamage() >= refresh) {
            double shield = champ.getStats().getFinalMaxHP() * 0.10;
            // Recharge jusqu'à 10 % des PV max (avant : effaçait aussi les autres boucliers)
            if (champ.getStats().getShield() < shield) {
                champ.getStats().addShield(shield - champ.getStats().getShield());
            }
        }
        // Passif de W (Brutalité) : +10/15/20/25/30 % d'armure, triplé tant que le bouclier tient
        int wRank = champ.getLevelSystem().getAbilityRank(2);
        var st = champ.getStats();
        double prev = armorApplied.getOrDefault(p.getUniqueId(), 0.0);
        double pct = wRank > 0 ? 0.05 + 0.05 * wRank : 0;
        if (st.getShield() > 0) pct *= 3;
        double want = (st.getFinalArmor() - prev) * pct;
        if (Math.abs(want - prev) > 0.01) {
            st.addBonusArmor(want - prev);
            armorApplied.put(p.getUniqueId(), want);
        }
    }

    static class AA extends BasicAttackAbility {
        AA(){super("malphite",Material.STONE_SWORD,2.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            // Coup de Tonnerre : la PROCHAINE attaque (5 s) inflige 30-70 (+20 % AP) (+15 % armure)
            // à la cible, et une onde de 15-55 (+30 % AP) (+15 % armure) aux ennemis proches.
            // Avant : toutes les attaques pendant 6 s, sans les dégâts bonus sur la cible.
            if (thunderclapUntil.remove(c.getUniqueId()) instanceof Long until && until > System.currentTimeMillis()) {
                int wlvl = LolPlugin.getInstance().getChampionManager().hasChampion(c)
                    ? Math.max(1, LolPlugin.getInstance().getChampionManager().getChampion(c).getAbility(2).getLevel()) : 1;
                int wr = Math.min(wlvl-1,4);
                double bonusDmg = new double[]{30,40,50,60,70}[wr] + s.getFinalAP()*0.20 + s.getFinalArmor()*0.15;
                double coneDmg = new double[]{15,25,35,45,55}[wr] + s.getFinalAP()*0.30 + s.getFinalArmor()*0.15;
                // Coup de Tonnerre : 180 % contre les monstres (wiki, V26.02)
                double monsterMod = fr.lolmc.game.JungleManager.isJungleMonster(tgt) ? 1.8 : 1.0;
                TargetingUtil.dealDamage(c, tgt, bonusDmg * monsterMod, TargetingUtil.DmgType.PHYSICAL);
                // Onde en cône derrière la cible, dans l'axe Malphite → cible (wiki ; avant : cercle
                // autour de Malphite)
                org.bukkit.util.Vector axis = tgt.getLocation().toVector().subtract(c.getLocation().toVector()).setY(0);
                for (var e : TargetingUtil.entitiesInRadius(c, tgt.getLocation(), 4.0)) {
                    if (e.equals(tgt)) continue;
                    org.bukkit.util.Vector to = e.getLocation().toVector().subtract(tgt.getLocation().toVector()).setY(0);
                    if (axis.lengthSquared() > 1e-6 && to.lengthSquared() > 1e-6 && axis.angle(to) > Math.toRadians(45)) continue;
                    TargetingUtil.dealDamage(c, e, coneDmg * (fr.lolmc.game.JungleManager.isJungleMonster(e) ? 1.8 : 1.0), TargetingUtil.DmgType.PHYSICAL);
                }
                fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                        tgt.getLocation().add(0,1,0), Material.STONE, 0.3f, 4L);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_malphite","Éclat de Pierre",Material.COBBLESTONE,AbilitySlot.Q,
            new double[]{8,8,8,8,8},20,0,DamageType.MAGICAL);
            resourceCost = 70; resourceCosts = new double[]{70,75,80,85,90};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,6.9); if(tgt==null){cancelCast();c.sendActionBar(Component.text("🪨 Aucune cible",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("q_malphite",new double[]{70,120,170,220,270});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_malphite","ap",0.6);
            // Éclat de roche à tête chercheuse (1200 unités/s)
            final org.bukkit.entity.LivingEntity ftgt=tgt;
            TargetingUtil.launchHoming(c, c.getEyeLocation(), tgt, 12.0,
                    new org.bukkit.inventory.ItemStack(Material.COBBLESTONE), 0.7f, Material.STONE, true, () -> shardHit(c, ftgt, dmg));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 0.8f);
        }

        private void shardHit(Player c, org.bukkit.entity.LivingEntity tgt, double dmg){
            TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.MAGICAL);
            // LoL : slow 30/35/40/45/50% sur 3s (60 ticks), Malphite vole la vitesse
            int qrank=getLevel()-1;
            // Wiki : ralentit 20-40 % pendant 3 s et Malphite gagne cette vitesse
            double slowPct=new double[]{0.20,0.25,0.30,0.35,0.40}[qrank];
            fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(tgt, slowPct, 60);
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(c, slowPct, 60);
            fr.lolmc.util.VisualEffectUtil.impactBurst(tgt.getWorld(),
                    tgt.getLocation().add(0,1,0), Material.STONE, 0.28f, 0.3, 6, 5L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_malphite",new double[]{70,120,170,220,270});
            return String.format("%.0f dégâts magiques (+60%%AP). Ralentit 3s et vole la vitesse.",
                base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_malphite","ap",0.6));
        }
    }

    // W — Coup de Tonnerre : 6s d'AA renforcées (+dégâts en cône)
    private static final java.util.Map<java.util.UUID, Long> thunderclapUntil = new java.util.concurrent.ConcurrentHashMap<>();
    public static boolean hasThunderclap(java.util.UUID id){ Long u=thunderclapUntil.get(id); return u!=null && u>System.currentTimeMillis(); }
    static class W extends BaseAbility {
        W(){super("w_malphite","Coup de Tonnerre",Material.IRON_CHESTPLATE,AbilitySlot.W,
            new double[]{10,9.5,9,8.5,8},0,3,DamageType.PHYSICAL);
            resourceCost = 30; resourceCosts = new double[]{30,35,40,45,50};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Active : la prochaine attaque dans les 5 s est renforcée (wiki)
            thunderclapUntil.put(c.getUniqueId(), System.currentTimeMillis()+5000L);
            c.sendActionBar(Component.text("⚡ Coup de Tonnerre : prochaine attaque renforcée (5s)",NamedTextColor.YELLOW));
            fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(),
                    c.getLocation().add(0,0.5,0), Material.STONE, 0.4f, 5L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.5f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            int r=getLevel()-1;
            return String.format("Passif : +%.0f%% d'armure (x3 avec le bouclier). Prochaine attaque : +%.0f dégâts et onde de %.0f autour.",
                    (0.05+0.05*(r+1))*100, new double[]{30,40,50,60,70}[r]+s.getFinalAP()*0.20+s.getFinalArmor()*0.15,
                    new double[]{15,25,35,45,55}[r]+s.getFinalAP()*0.30+s.getFinalArmor()*0.15);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_malphite","Sol Fracturé",Material.CRACKED_STONE_BRICKS,AbilitySlot.E,
            new double[]{7,7,7,7,7},5,4,DamageType.MAGICAL);
            resourceCost = 50; resourceCosts = new double[]{50,55,60,65,70};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // LoL : 60/95/130/165/200 + 60% AP + 40% armure, slow AS 30-50% sur 3s
            double[] eBase={60,95,130,165,200};
            double dmg=eBase[getLevel()-1]+s.getFinalAP()*0.6+s.getFinalArmor()*0.4;
            // Wiki : vitesse d'attaque réduite de 30-50 % pendant 3 s (avant : potion de lenteur,
            // qui ralentissait le déplacement et laissait la vitesse d'attaque intacte)
            double asMult=1.0-new double[]{0.30,0.35,0.40,0.45,0.50}[getLevel()-1];
            var cmE=LolPlugin.getInstance().getChampionManager();
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                if(__t instanceof Player __p && cmE.hasChampion(__p)){
                    var st=cmE.getChampion(__p).getStats();
                    st.multiplyAS(asMult);
                    new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ st.multiplyAS(1.0/asMult); }}
                            .runTaskLater(LolPlugin.getInstance(), 60L);
                }
            }
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    c.getLocation(), Material.STONE, 0.32f, 2.0, 10, 8L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] eBase={60,95,130,165,200};
            return String.format("%.0f dégâts magiques AoE (+60%%AP+40%%Armure). Slow AS 3s.",eBase[getLevel()-1]+s.getFinalAP()*0.6+s.getFinalArmor()*0.4);
        }
    }

    static class R extends BaseAbility {
        R(){super("r_malphite","Assaut Implacable",Material.ANVIL,AbilitySlot.R,
            new double[]{130,115,100},6.5,4,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : charge imparable (1500 + 100 % de la vitesse de déplacement) vers le point visé
            // (1000 unités) ; à l'arrivée, 200/300/400 (+90 % AP) et projection 1.5 s dans un rayon de 325
            Location aim=(t!=null && TargetingUtil.isEnemy(c,t)) ? t.getLocation() : TargetingUtil.getAimedGroundLocation(c, 10.0);
            Location dest=safeTeleport(c.getLocation(),aim);
            final Location from=c.getLocation().clone();
            org.bukkit.util.Vector path=dest.toVector().subtract(from.toVector());
            final double total=path.length();
            final org.bukkit.util.Vector unit=total>1e-6 ? path.multiply(1.0/total) : new org.bukkit.util.Vector();
            final double perTick=(1500+s.getFinalMovementSpeed())/100.0/20.0;
            double[] baseR={200,300,400};int rr=Math.min(getLevel()-1,2);
            final double dmg=baseR[rr]+s.getFinalAP()*0.9;
            fr.lolmc.LolPlugin.getInstance().getRuneManager().onDash(c); // Impact soudain
            new org.bukkit.scheduler.BukkitRunnable(){ double done=0;
                @Override public void run(){
                    if(!c.isOnline() || c.getGameMode()==org.bukkit.GameMode.SPECTATOR){ cancel(); return; }
                    done=Math.min(total, done+perTick);
                    Location p=from.clone().add(unit.clone().multiply(done));
                    p.setYaw(c.getLocation().getYaw()); p.setPitch(c.getLocation().getPitch());
                    c.teleport(p);
                    if(done<total) return;
                    cancel();
                    var cc=LolPlugin.getInstance().getCCManager();
                    for(var __t : TargetingUtil.enemiesAround(c, 3.9)){ // rayon 325 + collision (65)
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        if(cc!=null){ cc.setAirborne(__t, 30); cc.stun(__t, 30); } // projeté 1.5 s (wiki)
                        if(__t instanceof Player __p)
                            __p.sendActionBar(Component.text("🪨 Projeté par Malphite !",NamedTextColor.DARK_GRAY));
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                            c.getLocation(), Material.STONE, 0.45f, 1.0, 10, 8L);
                }
            }.runTaskTimer(LolPlugin.getInstance(), 0L, 1L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] baseR={200,300,400};int rr=Math.min(getLevel()-1,2);
            return String.format("Charge vers le point visé. %.0f dégâts (+90%%AP) + projette 1.5s autour.",baseR[rr]+s.getFinalAP()*0.9);
        }
    }
}