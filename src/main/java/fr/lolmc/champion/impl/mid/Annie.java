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

public final class Annie extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Annie() {
        super("annie", "Annie", ChampionRole.MID,
                new ChampionStats(560,50,0,23,30,0.61,0,335,6.25,5.5));
        getStats().setGrowthStats(96,2.65,4,1.3,0.0136,0.55);
        setLolAttackRange(625); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(5.5, ResourceSystem.ResourceType.MANA, 418, 25, 8, 0.8);
    }

    // ── API publique Tibbers ──────────────────────────────────────
    public static org.bukkit.entity.LivingEntity getActiveTibbers(Player owner) {
        return activeTibbers.get(owner.getUniqueId());
    }

    /** Annie qui contrôle ce Tibbers (ou null). */
    public static Player getTibbersOwner(org.bukkit.entity.Entity e) {
        for (var en : activeTibbers.entrySet())
            if (en.getValue().getUniqueId().equals(e.getUniqueId())) return org.bukkit.Bukkit.getPlayer(en.getKey());
        return null;
    }

    // Dégâts d'une attaque de Tibbers (wiki : 30/45/60 +10 % AP, magiques) par Annie
    private static final Map<UUID, Double> tibbersAttack = new java.util.concurrent.ConcurrentHashMap<>();
    public static double getTibbersAttackDamage(org.bukkit.entity.Entity tibbers) {
        return tibbersAttack.getOrDefault(tibbers.getUniqueId(), 30.0);
    }

    /** Annie est-elle morte (en attente de réapparition) ? */
    private static boolean isAnnieDead(Player annie) {
        var cm = LolPlugin.getInstance().getChampionManager();
        return annie.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || (cm.hasChampion(annie) && cm.getChampion(annie).getHPSystem().isDead());
    }

    /** Perte de PV de Tibbers par seconde (part des PV max) tant qu'Annie est morte : estimation. */
    private static final double TIBBERS_DECAY_PER_SEC = 0.05;

    /** Vitesse d'attaque des 5 attaques enragées après la mort d'Annie (wiki), base 0.625. */
    private static final double[] ENRAGED_AS = {1.736, 1.536, 1.307, 1.043, 0.739};
    private static final Map<UUID, Integer> enragedAttacks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Vitesse d'attaque de la frappe de Tibbers qui part : 0.625, ou celle de l'une des 5 attaques
     * enragées (consommée). Les coups vanilla tombent à cadence fixe : les dégâts en tiennent compte.
     */
    public static double consumeTibbersAttackSpeed(org.bukkit.entity.Entity tibbers) {
        Integer n = enragedAttacks.get(tibbers.getUniqueId());
        if (n == null || n >= ENRAGED_AS.length) { enragedAttacks.remove(tibbers.getUniqueId()); return 0.625; }
        enragedAttacks.put(tibbers.getUniqueId(), n + 1);
        return ENRAGED_AS[n];
    }

    /** Ennemi le plus proche de Tibbers (champions et sbires, pas les monstres neutres), ou null. */
    private static org.bukkit.entity.LivingEntity nearestTibbersTarget(Player annie, org.bukkit.entity.PolarBear t, double range) {
        org.bukkit.entity.LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : t.getNearbyEntities(range, range, range)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le) || fr.lolmc.game.JungleManager.isJungleMonster(le)) continue;
            if (!TargetingUtil.isEnemy(annie, le)) continue;
            double d = le.getLocation().distanceSquared(t.getLocation());
            if (d < bestD) { bestD = d; best = le; }
        }
        return best;
    }

    /** Rage de l'invocation (wiki) : +100 % de vitesse de déplacement qui décroît sur 3 s. */
    private static void summonEnrage(org.bukkit.entity.PolarBear t) {
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                var sp = t.getAttribute(fr.lolmc.util.Compat.movementSpeed());
                if (!t.isValid() || sp == null || ticks >= 60) {
                    if (sp != null && t.isValid() && !enrageUntil.containsKey(t.getUniqueId())) sp.setBaseValue(tibbersSpeed.getOrDefault(t.getUniqueId(), 0.27));
                    cancel(); return;
                }
                sp.setBaseValue(tibbersSpeed.getOrDefault(t.getUniqueId(), 0.27) * (1.0 + (60 - ticks) / 60.0));
                ticks += 5;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);
    }

    // ── État statique ─────────────────────────────────────────────
    private static final Map<UUID, org.bukkit.entity.PolarBear> activeTibbers  = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long>    rCooldowns  = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Integer> pyroStacks  = new java.util.concurrent.ConcurrentHashMap<>();
    // Enrage : UUID de Tibbers → timestamp fin enrage
    private static final Map<UUID, Long>    enrageUntil = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Pyromanie ─────────────────────────────────────────────────
    /** Relance de R pour diriger Tibbers : ce n'est pas un sort, pas de charge de Pyromanie. */
    private static final java.util.Set<UUID> tibbersControlCast = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void onSpellCast(UUID id, int slot) {
        if (slot == 4 && tibbersControlCast.remove(id)) return;
        // Le Bouclier en Fusion n'inflige pas de dégâts : il ne consomme pas l'étourdissement chargé
        if (slot == 3 && hasPyromancyStun(id)) return;
        int stacks = pyroStacks.merge(id, 1, Integer::sum);
        if (stacks >= 4) pyroStacks.put(id, 0);
    }
    public static boolean hasPyromancyStun(UUID id) {
        return pyroStacks.getOrDefault(id, 0) == 0 && pyroStacks.containsKey(id);
    }
    /** PV de base de Tibbers (données du jeu) : 1150, +50/niveau dès 7, +250 dès 11, +300 dès 16. */
    static double tibbersBaseHP(int lvl) {
        if (lvl <= 6) return 1150;
        if (lvl <= 10) return 1150 + 50 * (lvl - 6);
        if (lvl <= 15) return 1350 + 250 * (lvl - 10);
        return 2600 + 300 * (lvl - 15);
    }
    /** Vitesse de Tibbers : 350 (+0/25/50 selon le rang du R), même échelle que les sbires (350 = 0.25). */
    static double tibbersSpeed(int rankIndex) { return 0.25 * (350 + 25 * rankIndex) / 350.0; }
    private static final Map<UUID, Double> tibbersSpeed = new java.util.concurrent.ConcurrentHashMap<>();

    public static int pyroStunTicks(int level) {
        return level >= 11 ? 35 : (level >= 6 ? 30 : 25); // 1.25/1.5/1.75 s aux niveaux 1/6/11 (wiki)
    }

    // ── Reset ─────────────────────────────────────────────────────

    // Passif de R (données du jeu) : +10/15/20 % de pénétration magique. Manquait.
    private double appliedPen = 0;
    @Override public void onAbilityRanksChanged() {
        int r = getLevelSystem().getAbilityRank(4);
        double pen = r > 0 ? 0.05 + 0.05 * r : 0;
        getStats().addBonusMagicPenPercent(pen - appliedPen);
        appliedPen = pen;
    }

    public void resetState(UUID id) {
        var t = activeTibbers.remove(id);
        if (t != null && t.isValid()) t.remove();
        pyroStacks.remove(id);
        rCooldowns.remove(id);
    }

    public void resetAllState() {
        activeTibbers.values().forEach(t -> { if (t != null && t.isValid()) t.remove(); });
        activeTibbers.clear(); pyroStacks.clear(); rCooldowns.clear(); enrageUntil.clear();
    }

    // ── Bouclier en Fusion : renvoi de dégâts (wiki 25-65 +40 % AP) aux attaquants ──
    private record MoltenShield(UUID annie, double damage, long until) {}
    private static final Map<UUID, MoltenShield> moltenShield = new java.util.concurrent.ConcurrentHashMap<>();

    /** Un champion protégé par le Bouclier en Fusion subit une attaque : l'attaquant est brûlé. */
    public static void onShieldedAttacked(Player shielded, Player attacker) {
        MoltenShield ms = moltenShield.get(shielded.getUniqueId());
        if (ms == null || ms.until() < System.currentTimeMillis()) return;
        Player annie = org.bukkit.Bukkit.getPlayer(ms.annie());
        if (annie == null) return;
        fr.lolmc.util.DamageUtil.damage(annie, attacker, ms.damage(), true, fr.lolmc.util.DamageUtil.Type.MAGICAL);
    }

    // ── Annie meurt → Tibbers s'enrage ───────────────────────────
    /** Appelé par HealthListener quand Annie meurt. */
    public static void onAnnieDeath(Player annie) {
        var t = activeTibbers.get(annie.getUniqueId());
        if (t == null || !t.isValid()) return;
        // Wiki : Tibbers se soigne de 50 % de ses PV manquants, s'enrage 10 s et ses 5 attaques
        // suivantes sont bien plus rapides ; il vise le champion ennemi le plus proche et le plus
        // blessé. Avant : +50 % à un attribut de dégâts Minecraft que le jeu n'utilise pas.
        enrageUntil.put(t.getUniqueId(), System.currentTimeMillis() + 10000L);
        enragedAttacks.put(t.getUniqueId(), 0);
        if (fr.lolmc.util.VirtualHP.has(t))
            fr.lolmc.util.VirtualHP.heal(t, (fr.lolmc.util.VirtualHP.getMax(t) - fr.lolmc.util.VirtualHP.getCurrent(t)) * 0.5);
        var cm = LolPlugin.getInstance().getChampionManager();
        Player best = null; double bestScore = Double.MAX_VALUE;
        for (Entity e : t.getNearbyEntities(12, 12, 12)) {
            if (!(e instanceof Player p) || !cm.hasChampion(p) || !TargetingUtil.isEnemy(annie, p)) continue;
            double score = p.getLocation().distance(t.getLocation()) + 10 * cm.getChampion(p).getHPSystem().getHPRatio();
            if (score < bestScore) { bestScore = score; best = p; }
        }
        if (best != null) t.setTarget(best);
        fr.lolmc.util.VisualEffectUtil.impactBurst(t.getWorld(),
                t.getLocation().add(0,1,0), Material.ORANGE_STAINED_GLASS, 0.32f, 1.0, 10, 8L);
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_POLAR_BEAR_WARNING, 2f, 0.5f);
        new BukkitRunnable() { @Override public void run() {
            enrageUntil.remove(t.getUniqueId());
            enragedAttacks.remove(t.getUniqueId());
        }}.runTaskLater(LolPlugin.getInstance(), 200L);
        // Tant qu'Annie est morte (wiki) : Tibbers perd des PV régulièrement et doit rester à moins
        // de 1200 unités de son corps. Le rythme n'est publié nulle part (wiki, données du jeu) :
        // 5 % des PV max par seconde est une estimation (« décroît rapidement »).
        final Location body = annie.getLocation().clone();
        new BukkitRunnable() { @Override public void run() {
            if (!t.isValid() || t.isDead() || !annie.isOnline() || !isAnnieDead(annie)) { cancel(); return; }
            if (fr.lolmc.util.VirtualHP.has(t))
                fr.lolmc.util.VirtualHP.damage(t, fr.lolmc.util.VirtualHP.getMax(t) * TIBBERS_DECAY_PER_SEC / 2, null);
            if (t.getWorld().equals(body.getWorld()) && t.getLocation().distanceSquared(body) > 12 * 12) {
                t.setTarget(null);
                t.getPathfinder().moveTo(body, 1.4);
            }
        }}.runTaskTimer(LolPlugin.getInstance(), 10L, 10L);
        // Notifier les alliés
        for (Player p : annie.getWorld().getPlayers()) {
            if (!LolPlugin.getInstance().getTeamManager().areEnemies(annie, p))
                p.sendActionBar(Component.text("🔥 TIBBERS ENRAGÉ!", NamedTextColor.RED));
        }
    }

    // ── Contrôle Tibbers (clic gauche avec R en main) ─────────────
    public static void onTibbersControl(Player annie, org.bukkit.entity.PolarBear tibbers) {
        Location aimed = TargetingUtil.getAimedGroundLocation(annie, 20.0);
        final Location dest = aimed.clone();

        // Ennemi proche du point visé ?
        org.bukkit.entity.LivingEntity nearEnemy = null;
        double best = 5.0;
        for (Entity e : dest.getWorld().getNearbyEntities(dest, 5, 5, 5)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le)) continue;
            if (e.equals(annie) || e.equals(tibbers)) continue;
            if (!TargetingUtil.isEnemy(annie, le)) continue; // ni alliés ni sbires alliés
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
        fr.lolmc.util.VisualEffectUtil.impactBurst(dest.getWorld(),
                dest.clone().add(0,0.1,0), Material.ORANGE_STAINED_GLASS, 0.2f, 0.5, 4, 5L);
    }

    // ── Sorts ─────────────────────────────────────────────────────

    static class AA extends BasicAttackAbility {
        AA(){super("annie",Material.FIRE_CHARGE,5.5f,DamageType.MAGICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_annie","Brasier",Material.BLAZE_POWDER,AbilitySlot.Q,
                new double[]{4,4,4,4,4},20,0,DamageType.MAGICAL);
            resourceCost = 60; resourceCosts = new double[]{60,65,70,75,80};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            var target = TargetingUtil.getTargetedEnemy(c, 6.9); // 625 unités
            if(target==null){cancelCast();c.sendActionBar(Component.text("Aucune cible",NamedTextColor.GRAY));return;}
            double[] base=fr.lolmc.util.Balance.base("q_annie",new double[]{80,125,170,215,260});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_annie","ap",0.8);
            final boolean stun=hasPyromancyStun(c.getUniqueId()); // chargé au lancer
            final double cost=getResourceCost();
            final BaseAbility q=this;
            // Boule de feu à tête chercheuse (1400 unités/s)
            TargetingUtil.launchHoming(c, c.getEyeLocation(), target, 14.0,
                    new org.bukkit.inventory.ItemStack(Material.FIRE_CHARGE), 0.8f, Material.ORANGE_STAINED_GLASS, true, () -> {
                if(stun){
                    var cc=LolPlugin.getInstance().getCCManager();
                    int lvl=LolPlugin.getInstance().getChampionManager().hasChampion(c)?LolPlugin.getInstance().getChampionManager().getChampion(c).getLevelSystem().getLevel():1;
                    if(cc!=null) cc.stun(target, pyroStunTicks(lvl));
                }
                TargetingUtil.dealDamage(c, target, dmg, TargetingUtil.DmgType.MAGICAL);
                // Kill : la moitié de la recharge et le mana sont rendus (wiki)
                if(target.isDead() || TargetingUtil.getRealHealth(target) <= 0){
                    q.reduceCooldown(c, q.getRemainingCooldown(c)*0.5);
                    var cm=LolPlugin.getInstance().getChampionManager();
                    if(cm.hasChampion(c)&&cm.getChampion(c).getResourceSystem()!=null)cm.getChampion(c).getResourceSystem().addCurrent(cost);
                }
            });
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_annie",new double[]{80,125,170,215,260});
            return String.format("%.0f dégâts magiques (+80%%AP). Refund si kill.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_annie","ap",0.8));
        }
    }

    static class W extends BaseAbility {
        W(){super("w_annie","Incinération",Material.CAMPFIRE,AbilitySlot.W,
                new double[]{7,7,7,7,7},6,4,DamageType.MAGICAL);
            resourceCost = 70; resourceCosts = new double[]{70,75,80,85,90};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("w_annie",new double[]{70,110,150,190,230});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_annie","ap",0.8);
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
                fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(), p, Material.ORANGE_STAINED_GLASS, 0.3f, 3L);
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_annie",new double[]{70,110,150,190,230});
            return String.format("Cône de feu: %.0f dégâts (+80%%AP).",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_annie","ap",0.8));
        }
    }

    static class E extends BaseAbility {
        E(){super("e_annie","Molten Shield",Material.ORANGE_STAINED_GLASS,AbilitySlot.E,
                new double[]{10,10,10,10,10},0,0,DamageType.MAGICAL);
            resourceCost = 60; resourceCosts = new double[]{60,65,70,75,80};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Player dest = (t!=null && LolPlugin.getInstance().getTeamManager().areAllies(c,t)) ? t : c; // allié visé, sinon soi
            double[] shieldBase={60,95,130,165,200};
            double shield=shieldBase[getLevel()-1]+s.getFinalAP()*0.40;
            var cm=LolPlugin.getInstance().getChampionManager();
            if(cm.hasChampion(dest)) cm.getChampion(dest).getStats().addShield(shield);
            fr.lolmc.item.ItemEffects.onAllyHealShield(c, dest, 0, shield); // objets de soin/bouclier
            // Vitesse 20-50 % (selon le niveau) qui décroît sur 1.5 s (wiki)
            int alvl=cm.hasChampion(c)?cm.getChampion(c).getLevelSystem().getLevel():1;
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedDecaying(dest, 0.20+0.30*(alvl-1)/17.0, 30);
            dest.sendActionBar(Component.text(String.format("🔥 Bouclier Ardent %.0f!",shield),NamedTextColor.GOLD));
            double reflect=new double[]{25,35,45,55,65}[getLevel()-1]+s.getFinalAP()*0.40;
            moltenShield.put(dest.getUniqueId(), new MoltenShield(c.getUniqueId(), reflect, System.currentTimeMillis()+3000L));
            final Player fd=dest; final double fsh=shield;
            new BukkitRunnable(){@Override public void run(){
                if(cm.hasChampion(fd)) cm.getChampion(fd).getStats().addShield(-fsh);
            }}.runTaskLater(LolPlugin.getInstance(),60L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] shieldBase={60,95,130,165,200};
            return String.format("Bouclier %.0f (+40%%AP) 3s + vitesse. Les attaquants subissent %.0f dégâts.",
                    shieldBase[getLevel()-1]+s.getFinalAP()*0.40, new double[]{25,35,45,55,65}[getLevel()-1]+s.getFinalAP()*0.40);
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
            double[] realCDs = {130,115,100}; // wiki
            int r = Math.min(getLevel()-1,2);
            R thisAbility = this;

            // ── Re-cast : Tibbers actif → l'envoyer à la position visée ──
            if (activeTibbers.containsKey(uuid)) {
                org.bukkit.entity.PolarBear tib = activeTibbers.get(uuid);
                if (tib != null && tib.isValid()) { tibbersControlCast.add(uuid); onTibbersControl(c, tib); return; }
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
            var ground = TargetingUtil.getAimedGroundLocation(c,6.0); // portée 600

            // Stun Pyromanie à l'invocation si chargé
            boolean pyroActive = hasPyromancyStun(uuid);
            int lvl = LolPlugin.getInstance().getChampionManager().hasChampion(c)
                    ? LolPlugin.getInstance().getChampionManager().getChampion(c).getLevelSystem().getLevel() : 1;

            for (var e : TargetingUtil.entitiesInRadius(c, ground, 3.15)) { // rayon 250 + collision (65)
                TargetingUtil.dealDamage(c, e, dmg, TargetingUtil.DmgType.MAGICAL);
                e.setFireTicks(60);
                // Stun Pyromanie sur tous les ennemis de la zone d'invocation
                if (pyroActive) {
                    var cc = LolPlugin.getInstance().getCCManager();
                    if (cc != null) cc.stun(e, pyroStunTicks(lvl));
                }
            }
            fr.lolmc.util.VisualEffectUtil.groundRing(ground.getWorld(), ground, 3.0,
                    Material.ORANGE_STAINED_GLASS, 24, 0.4f, 0.12f, 10L);
            fr.lolmc.util.VisualEffectUtil.impactBurst(ground.getWorld(),
                    ground, Material.MAGMA_BLOCK, 0.3f, 3.0, 10, 10L);
            ground.getWorld().playSound(ground, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
            if (pyroActive) ground.getWorld().playSound(ground, Sound.ENTITY_POLAR_BEAR_WARNING, 2f, 0.5f);

            // ── Spawn Tibbers : PolarBear en feu, taille ×2 ──
            final Location spawnLoc = ground.clone();
            final double finalAP = s.getFinalAP();
            org.bukkit.entity.PolarBear tibbers = spawnLoc.getWorld().spawn(spawnLoc, org.bukkit.entity.PolarBear.class, bear -> {
                bear.customName(Component.text("🔥 Tibbers", NamedTextColor.RED));
                bear.setCustomNameVisible(true);
                bear.setVisualFire(net.kyori.adventure.util.TriState.TRUE);
                bear.setRemoveWhenFarAway(false);
                bear.setInvulnerable(false);
                // (PV LoL appliqués juste après l'apparition : 1150-4100 selon le niveau +50 % AP)
                // Vitesse
                var sp = bear.getAttribute(fr.lolmc.util.Compat.movementSpeed());
                if (sp != null) sp.setBaseValue(tibbersSpeed(r));
                // Dégâts
                var da = bear.getAttribute(fr.lolmc.util.Compat.attackDamage());
                if (da != null) da.setBaseValue(20.0 + finalAP*0.15);
                // Taille ×2
                var sc = bear.getAttribute(org.bukkit.attribute.Attribute.SCALE);
                if (sc != null) sc.setBaseValue(2.0);
                bear.getScoreboardTags().add("tibbers_"+c.getUniqueId().toString().substring(0,8));
            });

            // PV de Tibbers (données du jeu) : 1150, +50/niveau dès 7, +250 dès 11, +300 dès 16 (+50 % AP)
            fr.lolmc.util.VirtualHP.init(tibbers, tibbersBaseHP(lvl) + finalAP * 0.5);
            tibbersSpeed.put(tibbers.getUniqueId(), tibbersSpeed(r));
            // Armure / RM 30 → 100 selon le niveau (wiki), dégâts d'attaque 30/45/60 (+10 % AP)
            double tibRes = 30 + 5.0 * Math.max(0, lvl - 6); // 30, +5 par niveau dès 7 (données du jeu)
            fr.lolmc.util.MobCombat.setStats(tibbers, 0, 0.625, tibRes, tibRes, 0, 0);
            tibbersAttack.put(tibbers.getUniqueId(), new double[]{30,45,60}[r] + finalAP * 0.10);
            activeTibbers.put(uuid, tibbers);
            thisAbility.overrideCost(0); // relance (contrôle de Tibbers) gratuite

            // Cibler l'ennemi le plus proche (champions et sbires) et rage d'invocation
            var first = nearestTibbersTarget(c, tibbers, 10);
            if (first != null) tibbers.setTarget(first);
            summonEnrage(tibbers);

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
                            fr.lolmc.util.VisualEffectUtil.impactBurst(tibbers.getWorld(),
                                    tibbers.getLocation().add(0,1,0), Material.ORANGE_STAINED_GLASS, 0.3f, 1.0, 10, 8L);
                            tibbers.remove();
                        }
                        activeTibbers.remove(uuid);
                        tibbersSpeed.remove(tibbers.getUniqueId());
                        thisAbility.clearCostOverride();
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
                    if (c.isOnline() && tLoc.getWorld().equals(c.getWorld())
                            && !isAnnieDead(c)) { // Annie morte : laisse sur son corps
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
                    if (tibbers.getTarget() == null || !tibbers.getTarget().isValid() || tibbers.getTarget().isDead()) {
                        var closest = nearestTibbersTarget(c, tibbers, 12); // sbires compris (wiki)
                        if (closest != null) tibbers.setTarget(closest);
                    }

                    // ── 3. Aura de feu (toutes les 0.5s) ──
                    fr.lolmc.util.VisualEffectUtil.impact(tLoc.getWorld(), tLoc.clone().add(0,1,0),
                            Material.ORANGE_STAINED_GLASS, enraged ? 0.4f : 0.28f, 8L);
                    if (enraged) fr.lolmc.util.VisualEffectUtil.impact(tLoc.getWorld(),
                            tLoc.clone().add(0,1,0), Material.MAGMA_BLOCK, 0.3f, 8L);

                    // Dégâts aura (toutes les 2 ticks = 1s pour ne pas flood)
                    if (ticks % 2 == 0) {
                        // Aura (wiki) : 2/3/4 (+1 % AP) toutes les 0.25 s → ×4 par seconde
                        double auraDmg = (new double[]{2,3,4}[r] + finalAP*0.01) * 4; // la rage ne renforce pas l'aura (wiki)
                        for (Entity entity : tibbers.getNearbyEntities(3.5,3.5,3.5)) {
                            if (!(entity instanceof org.bukkit.entity.LivingEntity target)) continue;
                            if (entity.equals(c) || entity.equals(tibbers)) continue;
                            if (!TargetingUtil.isEnemy(c, target)) continue; // jamais les alliés ni les neutres
                            TargetingUtil.dealDamage(c, target, auraDmg, TargetingUtil.DmgType.MAGICAL);
                            target.setVisualFire(net.kyori.adventure.util.TriState.TRUE);
                            new BukkitRunnable(){@Override public void run(){target.setVisualFire(net.kyori.adventure.util.TriState.FALSE);}}.runTaskLater(LolPlugin.getInstance(),60L);
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