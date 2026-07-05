package fr.lolmc.champion.impl.mid;

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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;

public class Annie extends BaseChampion {
    public Annie() {
        super("annie", "Annie", ChampionRole.MID,
                new ChampionStats(560,50,0,23,30,0.610,0,335,6.25,5.5));
        getStats().setGrowthStats(96.0,2.6,4.0,1.30,0.01360,0.55);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(528, 5.5, ResourceSystem.ResourceType.MANA, 528, 11.0);
        setAutoAttackRange(9.6);
    }

    // ── API publique Tibbers ──────────────────────────────────────
    public static org.bukkit.entity.LivingEntity getActiveTibbers(Player owner) {
        return activeTibbers.get(owner.getUniqueId());
    }

    // ── État statique ─────────────────────────────────────────────
    private static final Map<UUID, org.bukkit.entity.PolarBear> activeTibbers  = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long>    rCooldowns  = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Integer> pyroStacks  = new java.util.concurrent.ConcurrentHashMap<>();
    // Enrage : UUID de Tibbers → timestamp fin enrage
    private static final Map<UUID, Long>    enrageUntil = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Pyromanie ─────────────────────────────────────────────────
    public static void onSpellCast(UUID id) {
        int stacks = pyroStacks.merge(id, 1, Integer::sum);
        if (stacks >= 4) pyroStacks.put(id, 0);
    }
    public static boolean hasPyromancyStun(UUID id) {
        return pyroStacks.getOrDefault(id, 0) == 0 && pyroStacks.containsKey(id);
    }
    public static int pyroStunTicks(int level) {
        return level >= 13 ? 35 : (level >= 7 ? 30 : 25);
    }

    // ── Reset ─────────────────────────────────────────────────────
    public static void resetState(UUID id) {
        var t = activeTibbers.remove(id);
        if (t != null && t.isValid()) t.remove();
        pyroStacks.remove(id);
        rCooldowns.remove(id);
    }
    public static void resetAllState() {
        activeTibbers.values().forEach(t -> { if (t != null && t.isValid()) t.remove(); });
        activeTibbers.clear(); pyroStacks.clear(); rCooldowns.clear(); enrageUntil.clear();
    }

    // ── Annie meurt → Tibbers s'enrage ───────────────────────────
    /** Appelé par HealthListener quand Annie meurt. */
    public static void onAnnieDeath(Player annie) {
        var t = activeTibbers.get(annie.getUniqueId());
        if (t == null || !t.isValid()) return;
        enrageUntil.put(t.getUniqueId(), System.currentTimeMillis() + 5000L);
        // Boost temporaire : +50% vitesse, +50% dégâts pendant 5s
        var spAttr = t.getAttribute(fr.lolmc.util.Compat.movementSpeed());
        if (spAttr != null) spAttr.setBaseValue(0.57); // 0.38 × 1.5
        var dmgAttr = t.getAttribute(fr.lolmc.util.Compat.attackDamage());
        if (dmgAttr != null) dmgAttr.setBaseValue(dmgAttr.getBaseValue() * 1.5);
        t.getWorld().spawnParticle(Particle.FLAME, t.getLocation().add(0,1,0), 30, 1,1,1,0.1);
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_POLAR_BEAR_WARNING, 2f, 0.5f);
        // Remettre les stats normales après 5s
        new BukkitRunnable() { @Override public void run() {
            if (!t.isValid()) { cancel(); return; }
            enrageUntil.remove(t.getUniqueId());
            var sp2 = t.getAttribute(fr.lolmc.util.Compat.movementSpeed());
            if (sp2 != null) sp2.setBaseValue(0.38);
            var dm2 = t.getAttribute(fr.lolmc.util.Compat.attackDamage());
            if (dm2 != null) dm2.setBaseValue(dm2.getBaseValue() / 1.5);
        }}.runTaskLater(LolPlugin.getInstance(), 100L);
        // Notifier les alliés
        for (Player p : annie.getWorld().getPlayers()) {
            if (!LolPlugin.getInstance().getTeamManager().areEnemies(annie, p))
                p.sendActionBar(Component.text("🔥 TIBBERS ENRAGÉ!", NamedTextColor.RED));
        }
    }

    // ── Contrôle Tibbers (clic gauche avec R en main) ─────────────
    public static void onTibbersControl(Player annie, org.bukkit.entity.PolarBear tibbers) {
        Location aimed = TargetingUtil.getAimedGroundLocation(annie, 20.0);
        if (aimed == null) aimed = annie.getLocation().add(annie.getLocation().getDirection().multiply(10));
        final Location dest = aimed.clone();

        // Ennemi proche du point visé ?
        org.bukkit.entity.LivingEntity nearEnemy = null;
        double best = 5.0;
        for (Entity e : dest.getWorld().getNearbyEntities(dest, 5, 5, 5)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le)) continue;
            if (e.equals(annie) || e.equals(tibbers)) continue;
            if (e instanceof Player ep && !LolPlugin.getInstance().getTeamManager().areEnemies(annie, ep)) continue;
            double d = e.getLocation().distance(dest);
            if (d < best) { best = d; nearEnemy = le; }
        }
        if (nearEnemy != null) {
            tibbers.setTarget(nearEnemy);
            annie.sendActionBar(Component.text("🐻 Tibbers → attaque " + nearEnemy.getName() + "!", NamedTextColor.RED));
        } else {
            tibbers.getPathfinder().moveTo(dest, 1.4);
            annie.sendActionBar(Component.text("🐻 Tibbers → déplacement!", NamedTextColor.GOLD));
        }
        dest.getWorld().spawnParticle(Particle.FLAME, dest.clone().add(0,0.1,0), 12, 0.5,0,0.5,0.02);
    }

    // ── Sorts ─────────────────────────────────────────────────────

    static class AA extends BasicAttackAbility {
        AA(){super("annie",Material.FIRE_CHARGE,5.5f,DamageType.MAGICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_annie","Brasier",Material.BLAZE_POWDER,AbilitySlot.Q,
                new double[]{4,3.5,3,2.5,2},20,0,DamageType.MAGICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            var target = TargetingUtil.getTargetedEnemy(c, 6.5);
            if(target==null){c.sendActionBar(Component.text("Aucune cible",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("q_annie",new double[]{80,125,170,215,260});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_annie","ap",0.80);
            boolean kills=TargetingUtil.getRealHealth(target)-dmg<=0;
            if(hasPyromancyStun(c.getUniqueId())){
                var cc=LolPlugin.getInstance().getCCManager();
                int lvl=LolPlugin.getInstance().getChampionManager().hasChampion(c)?LolPlugin.getInstance().getChampionManager().getChampion(c).getLevelSystem().getLevel():1;
                if(cc!=null) cc.stun(target, pyroStunTicks(lvl));
            }
            TargetingUtil.dealDamage(c, target, dmg, TargetingUtil.DmgType.MAGICAL);
            if(kills){
                triggerCooldown(c,s);
                setDynamicCooldown(getCurrentCooldown(s)*0.5);
                var cm=LolPlugin.getInstance().getChampionManager();
                if(cm.hasChampion(c)&&cm.getChampion(c).getResourceSystem()!=null)cm.getChampion(c).getResourceSystem().addCurrent(60);
            }
            target.getWorld().spawnParticle(Particle.FLAME,target.getLocation().add(0,1,0),20,0.5,0.5,0.5,0.1);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_annie",new double[]{80,125,170,215,260});
            return String.format("%.0f dégâts magiques (+80%%AP). Refund si kill.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_annie","ap",0.80));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_annie","Incinération",Material.CAMPFIRE,AbilitySlot.W,
                new double[]{8,7,6,5,4},6,4,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("w_annie",new double[]{70,110,150,190,230});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_annie","ap",0.80);
            var targets=TargetingUtil.enemiesInCone(c,6.0,50);
            if(hasPyromancyStun(c.getUniqueId())){
                var cc=LolPlugin.getInstance().getCCManager();
                int lvl=LolPlugin.getInstance().getChampionManager().hasChampion(c)?LolPlugin.getInstance().getChampionManager().getChampion(c).getLevelSystem().getLevel():1;
                if(cc!=null) for(var e:targets) cc.stun(e, pyroStunTicks(lvl));
            }
            TargetingUtil.dealDamageAll(c, targets, dmg, TargetingUtil.DmgType.MAGICAL);
            var dir=c.getEyeLocation().getDirection().normalize();
            for(double d=1;d<=6;d+=0.5){
                var p=c.getEyeLocation().add(dir.clone().multiply(d));
                c.getWorld().spawnParticle(Particle.FLAME,p,8,0.6,0.4,0.6,0.02);
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_annie",new double[]{70,110,150,190,230});
            return String.format("Cône de feu: %.0f dégâts (+80%%AP).",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_annie","ap",0.80));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_annie","Molten Shield",Material.ORANGE_STAINED_GLASS,AbilitySlot.E,
                new double[]{10,9,8,7,6},0,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Player dest=t!=null?t:c;
            double[] shieldBase={60,95,130,165,200};
            double shield=shieldBase[getLevel()-1]+s.getFinalAP()*0.40;
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(dest)) cm.getChampion(dest).getStats().addShield(shield);
            dest.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,30,1,false,true));
            dest.sendActionBar(Component.text(String.format("🔥 Bouclier Ardent %.0f!",shield),NamedTextColor.GOLD));
            final Player fd=dest; final double fsh=shield;
            new BukkitRunnable(){@Override public void run(){
                if(cm.hasChampion(fd)) cm.getChampion(fd).getStats().addShield(-fsh);
            }}.runTaskLater(LolPlugin.getInstance(),60L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] shieldBase={60,95,130,165,200};
            return String.format("Bouclier %.0f (+40%%AP) 3s + vitesse.",shieldBase[getLevel()-1]+s.getFinalAP()*0.40);
        }
    }

    // ── R : Invocation de Tibbers ─────────────────────────────────
    static class R extends BaseAbility {
        R(){super("r_annie","Invocation de Tibbers",Material.NETHERITE_BLOCK,AbilitySlot.R,
                new double[]{0,0,0},20,3,DamageType.MAGICAL);
            resourceCost = 100;}

        @Override public void cast(Player c, ChampionStats s, Player t){
            UUID uuid = c.getUniqueId();
            long now = System.currentTimeMillis();
            double[] realCDs = {120,100,80};
            int r = Math.min(getLevel()-1,2);
            R thisAbility = this;

            // ── Re-cast : Tibbers actif → l'envoyer à la position visée ──
            if (activeTibbers.containsKey(uuid)) {
                org.bukkit.entity.PolarBear tib = activeTibbers.get(uuid);
                if (tib != null && tib.isValid()) { onTibbersControl(c, tib); return; }
                activeTibbers.remove(uuid);
            }

            // ── Cooldown ──
            if (rCooldowns.containsKey(uuid) && rCooldowns.get(uuid) > now) {
                long rem = (rCooldowns.get(uuid)-now)/1000;
                c.sendActionBar(Component.text("⏳ Ultime en récupération ("+rem+"s)",NamedTextColor.RED));
                return;
            }

            // ── Explosion d'invocation ──
            double[] base = fr.lolmc.util.Balance.base("r_annie",new double[]{150,275,400});
            double dmg = base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_annie","ap",0.75);
            var ground = TargetingUtil.getAimedGroundLocation(c,7.0);
            if (ground == null) ground = c.getLocation();

            // Stun Pyromanie à l'invocation si chargé
            boolean pyroActive = hasPyromancyStun(uuid);
            int lvl = LolPlugin.getInstance().getChampionManager().hasChampion(c)
                    ? LolPlugin.getInstance().getChampionManager().getChampion(c).getLevelSystem().getLevel() : 1;

            for (var e : TargetingUtil.entitiesInRadius(c, ground, 4.0)) {
                TargetingUtil.dealDamage(c, e, dmg, TargetingUtil.DmgType.MAGICAL);
                e.setFireTicks(60);
                // Stun Pyromanie sur tous les ennemis de la zone d'invocation
                if (pyroActive && e instanceof org.bukkit.entity.LivingEntity le) {
                    var cc = LolPlugin.getInstance().getCCManager();
                    if (cc != null) cc.stun(le, pyroStunTicks(lvl));
                }
            }
            ground.getWorld().spawnParticle(Particle.LAVA,  ground, 40, 3,1,3);
            ground.getWorld().spawnParticle(Particle.FLAME, ground, 80, 3,1.5,3,0.1);
            ground.getWorld().playSound(ground, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
            if (pyroActive) ground.getWorld().playSound(ground, Sound.ENTITY_POLAR_BEAR_WARNING, 2f, 0.5f);

            // ── Spawn Tibbers : PolarBear en feu, taille ×2 ──
            final Location spawnLoc = ground.clone();
            final double finalAP = s.getFinalAP();
            org.bukkit.entity.PolarBear tibbers = spawnLoc.getWorld().spawn(spawnLoc, org.bukkit.entity.PolarBear.class, bear -> {
                bear.customName(Component.text("🔥 Tibbers", NamedTextColor.RED));
                bear.setCustomNameVisible(true);
                bear.setVisualFire(true);
                bear.setRemoveWhenFarAway(false);
                bear.setInvulnerable(false);
                // HP
                var hp = bear.getAttribute(fr.lolmc.util.Compat.maxHealth());
                if (hp != null) hp.setBaseValue(200.0 + finalAP*0.5);
                bear.setHealth(bear.getAttribute(fr.lolmc.util.Compat.maxHealth()).getValue());
                // Vitesse
                var sp = bear.getAttribute(fr.lolmc.util.Compat.movementSpeed());
                if (sp != null) sp.setBaseValue(0.38);
                // Dégâts
                var da = bear.getAttribute(fr.lolmc.util.Compat.attackDamage());
                if (da != null) da.setBaseValue(20.0 + finalAP*0.15);
                // Taille ×2
                var sc = bear.getAttribute(org.bukkit.attribute.Attribute.SCALE);
                if (sc != null) sc.setBaseValue(2.0);
                bear.getScoreboardTags().add("tibbers_"+c.getUniqueId().toString().substring(0,8));
            });

            activeTibbers.put(uuid, tibbers);
            thisAbility.resourceCost = 0;

            // Cibler ennemi proche immédiatement
            for (Entity e : tibbers.getNearbyEntities(10,10,10)) {
                if (e instanceof Player ep && LolPlugin.getInstance().getTeamManager().areEnemies(c,ep)) {
                    tibbers.setTarget(ep); break;
                }
            }

            c.sendActionBar(Component.text(pyroActive
                    ? "🔥 TIBBERS + STUN DE PYROMANIE!"
                    : "🔥 TIBBERS INVOQUÉ! R ou clic gauche pour contrôler.",
                    NamedTextColor.RED));

            // ── Boucle de vie (toutes les 10 ticks = 0.5s pour plus de réactivité) ──
            new BukkitRunnable() {
                int ticks = 0; // 1 tick = 0.5s ici, max 90 = 45s
                @Override public void run() {
                    // ── Mort ou expiration ──
                    if (!tibbers.isValid() || ticks >= 90 || !c.isOnline()) {
                        if (tibbers.isValid()) {
                            tibbers.getWorld().spawnParticle(Particle.FLAME,tibbers.getLocation().add(0,1,0),30,1,1,1,0.05);
                            tibbers.remove();
                        }
                        activeTibbers.remove(uuid);
                        thisAbility.resourceCost = 100;
                        rCooldowns.put(uuid, System.currentTimeMillis()+(long)(realCDs[r]*1000));
                        if (c.isOnline()) c.sendActionBar(Component.text("🐻 Tibbers s'est dissipé.",NamedTextColor.GRAY));
                        enrageUntil.remove(tibbers.getUniqueId());
                        cancel();
                        return;
                    }

                    Location tLoc = tibbers.getLocation();
                    boolean enraged = enrageUntil.containsKey(tibbers.getUniqueId())
                            && System.currentTimeMillis() < enrageUntil.get(tibbers.getUniqueId());

                    // ── 1. Suivi d'Annie : si elle s'éloigne de >8 blocs et Tibbers n'a pas de cible ──
                    if (c.isOnline() && tLoc.getWorld().equals(c.getWorld())) {
                        double dist = tLoc.distance(c.getLocation());
                        if (dist > 22.0) {
                            // Trop loin → TP derrière Annie
                            Location returnLoc = c.getLocation().clone().subtract(
                                c.getLocation().getDirection().normalize().multiply(1.5));
                            returnLoc.setY(c.getLocation().getY());
                            tibbers.teleportAsync(returnLoc);
                            c.sendActionBar(Component.text("🐻 Tibbers revient!",NamedTextColor.GOLD));
                        } else if (tibbers.getTarget() == null && dist > 8.0) {
                            // Pas de cible + trop loin d'Annie → la suivre
                            tibbers.getPathfinder().moveTo(c.getLocation(), 1.3);
                        }
                    }

                    // ── 2. Re-ciblage automatique : si pas de cible, chercher l'ennemi le plus proche ──
                    if (tibbers.getTarget() == null || !tibbers.getTarget().isValid()) {
                        org.bukkit.entity.LivingEntity closest = null;
                        double bestDist = 12.0;
                        for (Entity e : tibbers.getNearbyEntities(12,12,12)) {
                            if (!(e instanceof Player ep)) continue;
                            if (!LolPlugin.getInstance().getTeamManager().areEnemies(c,ep)) continue;
                            double d = e.getLocation().distance(tLoc);
                            if (d < bestDist) { bestDist=d; closest=ep; }
                        }
                        if (closest != null) tibbers.setTarget(closest);
                    }

                    // ── 3. Aura de feu (toutes les 0.5s) ──
                    tLoc.getWorld().spawnParticle(Particle.FLAME, tLoc.clone().add(0,1,0),
                            enraged ? 20 : 12, 0.6,0.7,0.6, enraged ? 0.06 : 0.03);
                    if (enraged) tLoc.getWorld().spawnParticle(Particle.LAVA, tLoc.clone().add(0,1,0), 5, 0.5,0.5,0.5);

                    // Dégâts aura (toutes les 2 ticks = 1s pour ne pas flood)
                    if (ticks % 2 == 0) {
                        double auraDmg = (20 + finalAP*0.12) * (enraged ? 1.5 : 1.0);
                        for (Entity entity : tibbers.getNearbyEntities(3.5,3.5,3.5)) {
                            if (!(entity instanceof org.bukkit.entity.LivingEntity target)) continue;
                            if (entity.equals(c) || entity.equals(tibbers)) continue;
                            TargetingUtil.dealDamage(c, target, auraDmg, TargetingUtil.DmgType.MAGICAL);
                            target.setVisualFire(true);
                            new BukkitRunnable(){@Override public void run(){target.setVisualFire(false);}}.runTaskLater(LolPlugin.getInstance(),60L);
                        }
                    }

                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(), 10L, 10L);
        }

        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_annie",new double[]{150,275,400});
            int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts AoE + Invoque Tibbers 45s. R ou clic gauche=contrôle. Stun si 4 stacks Pyromanie.",
                    base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_annie","ap",0.75));
        }
    }
}
