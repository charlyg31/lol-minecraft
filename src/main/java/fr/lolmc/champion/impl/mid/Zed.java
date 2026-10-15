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
import org.bukkit.util.Vector;
import java.util.*;

public final class Zed extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Zed() {
        super("zed", "Zed", ChampionRole.MID,
                new ChampionStats(654,63,0,32,29,0.651,0,345,1.25,7));
        getStats().setGrowthStats(99,3.4,4.7,2.05,0.033,0.65);
        setLolAttackRange(125); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(7, ResourceSystem.ResourceType.ENERGY, 200, 0, 50, 0);
    }

    // Gestion de l'ombre et des cooldowns manuels
    public static final Map<UUID,Location> shadows = new java.util.concurrent.ConcurrentHashMap<>();
    public static final Map<UUID, Long> wCooldowns = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, UUID> shadowEntities = new java.util.concurrent.ConcurrentHashMap<>();
    // Ombre laissée par Marque de Mort à la position de départ de Zed (6 s, wiki)
    private static final Map<UUID, Location> rShadows = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, UUID> rShadowEntities = new java.util.concurrent.ConcurrentHashMap<>();

    /** Ombres actives de Zed (W et R) : elles imitent son Q et son E. */
    static java.util.List<Location> activeShadows(UUID id) {
        var out = new java.util.ArrayList<Location>(2);
        if (shadows.get(id) != null) out.add(shadows.get(id));
        if (rShadows.get(id) != null) out.add(rShadows.get(id));
        return out;
    }

    private static void removeRShadow(UUID id) {
        rShadows.remove(id);
        UUID eid = rShadowEntities.remove(id);
        if (eid != null) { var e = org.bukkit.Bukkit.getEntity(eid); if (e != null) e.remove(); }
    }

    
    public void resetState(UUID id) {
        removeRShadow(id);
        shadows.remove(id);
        wCooldowns.remove(id);
        UUID entityId = shadowEntities.remove(id);
        if (entityId != null) {
            org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(entityId);
            if (e != null) e.remove();
        }
    }
    /** Retourne la position de l'ombre active (pour AbilityPreview). */
    public static org.bukkit.Location getShadowLocation(UUID id) { return shadows.get(id); }
    public static boolean isWOnCooldown(UUID id) {
        Long until = wCooldowns.get(id);
        return until != null && until > System.currentTimeMillis();
    }
    
    public void resetAllState() {
        for (UUID id : new java.util.ArrayList<>(rShadows.keySet())) removeRShadow(id);
        for (UUID entityId : shadowEntities.values()) {
            org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(entityId);
            if (e != null) e.remove();
        }
        shadows.clear(); wCooldowns.clear(); shadowEntities.clear(); contemptReady.clear();
    }

    private static final Map<String, Long> contemptReady = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Ombre vivante (données du jeu) : quand Zed et son ombre touchent le même champion avec le même
     * sort, Zed récupère 30/35/40/45/50 d'énergie (une fois par lancer). Manquait.
     */
    static void restoreDoubleHitEnergy(Player c, boolean[] done) {
        if (done[0]) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(c)) return;
        var champ = cm.getChampion(c);
        int wRank = champ.getLevelSystem().getAbilityRank(2);
        if (wRank <= 0 || champ.getResourceSystem() == null) return;
        done[0] = true;
        champ.getResourceSystem().addCurrent(new double[]{30,35,40,45,50}[wRank-1]);
    }

    static class AA extends BasicAttackAbility {
        AA(){super("zed",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            // Échelle LoL réelle (joueur=HPSystem, Baron=virtuel, sbire=vanilla)
            double realMax = TargetingUtil.getRealMaxHealth(tgt);
            double realCur = TargetingUtil.getRealHealth(tgt);

            // Une même cible ne subit le passif qu'une fois toutes les 10 s (wiki ; avant : à
            // chaque attaque, soit 5-10 % des PV max par coup)
            String key=c.getUniqueId()+":"+tgt.getUniqueId();
            if(realCur < realMax * 0.5 && contemptReady.getOrDefault(key,0L) <= System.currentTimeMillis()){
                contemptReady.put(key, System.currentTimeMillis()+10_000L);
                // 5/7.5/10% PV max selon niveau (1/7/17) — LoL
                var cm=LolPlugin.getInstance().getChampionManager();
                int lvl=cm.hasChampion(c)?cm.getChampion(c).getLevelSystem().getLevel():1;
                double pct = lvl>=17 ? 0.10 : (lvl>=7 ? 0.075 : 0.05);
                double bonus = realMax * pct;
                // Contre les monstres : 75 %, plafonné à 175 (wiki, V26.03)
                if (fr.lolmc.game.JungleManager.isJungleMonster(tgt)) bonus = Math.min(175, bonus * 0.75);
                TargetingUtil.dealDamage(c, tgt, bonus, TargetingUtil.DmgType.MAGICAL);
                fr.lolmc.util.VisualEffectUtil.impact(tgt.getWorld(),
                        tgt.getLocation().add(0,1,0), Material.GRAY_STAINED_GLASS, 0.25f, 4L);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_zed","Shuriken Tranchant",Material.ARROW,AbilitySlot.Q,
                new double[]{6,6,6,6,6},20,0,DamageType.PHYSICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] baseFirst={80,120,160,200,240};
            double[] baseNext={48,72,96,120,144};
            int rank=getLevel()-1;
            // Wiki : 80-240 (+100 % AD bonus), 60 % aux suivants ; portée 925
            double dmgFirst=baseFirst[rank]+s.getBonusAD();
            double dmgNext=baseNext[rank]+s.getBonusAD()*0.6;
            // Projectiles réels (925 unités à 1700/s) ; le 1er ennemi touché prend les dégâts pleins,
            // les suivants 60 %. L'ombre lance aussi un Shuriken dans la même direction (LoL) ; une
            // cible déjà touchée par l'autre ne prend que les dégâts réduits.
            var alreadyHit=java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
            final boolean[] energyDone={false};
            var dir=c.getEyeLocation().getDirection();
            java.util.function.Consumer<Location> throwFrom = from -> {
                final boolean[] first={true};
                TargetingUtil.launchSkillshot(c, from, dir, 9.25, 0.6, true, 17.0, // missile : 925 unités à 1700/s (données du jeu)
                        new org.bukkit.inventory.ItemStack(Material.NETHER_STAR), 0.7f, Material.GRAY_STAINED_GLASS,
                        __t -> {
                            boolean wasHit = alreadyHit.contains(__t.getUniqueId());
                            boolean full = first[0] && alreadyHit.add(__t.getUniqueId());
                            if(!full) alreadyHit.add(__t.getUniqueId());
                            if(wasHit && __t instanceof Player) restoreDoubleHitEnergy(c, energyDone);
                            first[0]=false;
                            TargetingUtil.dealDamage(c, __t, full?dmgFirst:dmgNext, TargetingUtil.DmgType.PHYSICAL);
                        }, null);
            };
            throwFrom.accept(c.getEyeLocation());
            for(Location shadow : activeShadows(c.getUniqueId())) // ombres du W et du R
                if(shadow.getWorld().equals(c.getWorld())) throwFrom.accept(shadow.clone().add(0,1.6,0));
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] baseFirst={80,120,160,200,240};
            return String.format("Skillshot: %.0f dégâts au 1er ennemi (+100%%AD bonus), 60%% aux suivants. L'ombre lance aussi.",baseFirst[getLevel()-1]+s.getBonusAD());
        }
    }

    static class W extends BaseAbility {
        W(){
            super("w_zed","Ombre Vivante",Material.GRAY_DYE,AbilitySlot.W,
                    new double[]{0,0,0,0,0},0,0,DamageType.TRUE);
            resourceCost = 0; // géré manuellement : coûte seulement au placement de l'ombre
        }
        @Override public void cast(Player c,ChampionStats s,Player t){
            UUID uuid = c.getUniqueId();
            int rank = Math.min(getLevel() - 1, 4);
            double[] realCooldowns = {20, 19, 18, 17, 16}; // wiki
            double[] energyCost = {40, 35, 30, 25, 20};
            long now = System.currentTimeMillis();

            if (wCooldowns.containsKey(uuid) && wCooldowns.get(uuid) > now && !shadows.containsKey(uuid)) {
                long remaining = (wCooldowns.get(uuid) - now) / 1000;
                c.sendActionBar(Component.text("⏳ Ombre Vivante en récupération (" + remaining + "s)", NamedTextColor.RED));
                return;
            }

            if(shadows.containsKey(uuid)) {
                Location shadowLoc = shadows.get(uuid);
                if (shadowLoc != null) {
                    shadows.remove(uuid);
                    UUID eid = shadowEntities.remove(uuid);
                    if (eid != null) { var se = org.bukkit.Bukkit.getEntity(eid); if (se != null) se.remove(); }
                    // Déclencher le CD après l'échange
                    wCooldowns.put(uuid, now + (long)(realCooldowns[rank] * 1000));
                    c.sendActionBar(Component.text("👤 Échange avec l'ombre !", NamedTextColor.DARK_GRAY));
                    fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                            shadowLoc, Material.GRAY_STAINED_GLASS, 0.3f, 0.5, 6, 6L);
                    fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                            c.getLocation(), Material.GRAY_STAINED_GLASS, 0.3f, 0.5, 6, 6L);
                    // Téléporter le joueur à la position de l'ombre
                    Location dest = shadowLoc.clone();
                    dest.setYaw(c.getLocation().getYaw());
                    dest.setPitch(c.getLocation().getPitch());
                    c.teleport(dest);
                    fr.lolmc.LolPlugin.getInstance().getRuneManager().onDash(c); // Impact soudain
                }
            } else {
                // Coût en énergie uniquement au placement de l'ombre (40/35/30/25/20, wiki)
                int cost = (int) energyCost[rank];
                var cm0 = LolPlugin.getInstance().getChampionManager();
                if (cm0.hasChampion(c)) {
                    var res = cm0.getChampion(c).getResourceSystem();
                    if (res != null && !res.consume(cost)) {
                        c.sendActionBar(Component.text("❌ Pas assez d'énergie (" + cost + ")", NamedTextColor.RED));
                        return;
                    }
                }
                Vector dir = c.getLocation().getDirection().setY(0).normalize();
                Location shadowLoc = c.getLocation().clone().add(dir.multiply(6.5)); // 650 unités

                // CORRECTION : Conserve le Y du joueur pour éviter que l'ombre spawn sur les toits en intérieur
                shadowLoc.setY(c.getLocation().getY());
                shadowLoc.setYaw(c.getLocation().getYaw());
                shadowLoc.setPitch(c.getLocation().getPitch());

                shadows.put(uuid, shadowLoc);
                // Spawner un ArmorStand visible représentant l'ombre
                final org.bukkit.entity.ArmorStand shadowStand =
                        shadowLoc.getWorld().spawn(shadowLoc, org.bukkit.entity.ArmorStand.class, as -> {
                            // CORRECTION : Supprime la hitbox de l'entité pour ne pas bloquer les événements de clic
                            as.setMarker(true);

                            as.setVisible(true);
                            as.setGravity(false);
                            as.setInvulnerable(true);
                            as.setCustomNameVisible(true);
                            as.customName(Component.text("👤 Ombre de Zed", NamedTextColor.DARK_GRAY));
                            // Armure en cuir noir
                            var eq = as.getEquipment();
                            eq.setHelmet(blackLeather(org.bukkit.Material.LEATHER_HELMET));
                            eq.setChestplate(blackLeather(org.bukkit.Material.LEATHER_CHESTPLATE));
                            eq.setLeggings(blackLeather(org.bukkit.Material.LEATHER_LEGGINGS));
                            eq.setBoots(blackLeather(org.bukkit.Material.LEATHER_BOOTS));
                            as.getScoreboardTags().add("zed_shadow");
                        });
                shadowEntities.put(uuid, shadowStand.getUniqueId());
                fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                        shadowLoc, Material.GRAY_STAINED_GLASS, 0.32f, 0.5, 8, 8L);
                c.getWorld().playSound(shadowLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.5f);
                c.sendActionBar(Component.text("👤 Ombre créée ! Re-cast pour échanger de place.", NamedTextColor.DARK_GRAY));

                new BukkitRunnable(){
                    @Override public void run(){
                        if (shadows.containsKey(uuid)) {
                            shadows.remove(uuid);
                            wCooldowns.put(uuid, System.currentTimeMillis() + (long)(realCooldowns[rank] * 1000));
                            UUID entityId = shadowEntities.remove(uuid);
                            if (entityId != null) {
                                org.bukkit.entity.Entity se = org.bukkit.Bukkit.getEntity(entityId);
                                if (se != null) se.remove();
                            }
                        }
                    }
                }.runTaskLater(LolPlugin.getInstance(), 105L); // l'ombre dure 5.25 s (wiki)
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return "Projette une ombre au sol. Re-cast pour échanger de position avec elle.";
        }
    }

    static class E extends BaseAbility {
        E(){super("e_zed","Taillade des Ombres",Material.IRON_SWORD,AbilitySlot.E,
                new double[]{5,4.5,4,3.5,3},5,4,DamageType.PHYSICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("e_zed",new double[]{70,92.5,115,137.5,160});
            double dmg=base[getLevel()-1]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("e_zed","ad",0.7); // AD bonus (wiki)
            int rank=getLevel()-1;
            int champsHit=0;
            var slashed=new java.util.HashSet<UUID>(); // un ennemi ne subit les dégâts qu'une fois
            final boolean[] energyDone={false};
            for(var __t : TargetingUtil.enemiesAround(c, 3.8)){ // rayon 315
                slashed.add(__t.getUniqueId());
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if(__t instanceof Player __p){
                    fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p, new double[]{0.20,0.25,0.30,0.35,0.40}[rank], 30); // wiki
                    champsHit++;
                }
            }

            // CORRECTION : Réduction du cooldown en manipulant directement la map manuelle wCooldowns
            if (champsHit > 0) {
                UUID id = c.getUniqueId();
                if (Zed.wCooldowns.containsKey(id)) {
                    long currentCd = Zed.wCooldowns.get(id);
                    long newCd = currentCd - (champsHit * 3000L); // -3 s par champion touché (wiki)
                    // S'assure que le CD ne tombe pas dans le négatif
                    if (newCd < System.currentTimeMillis()) {
                        newCd = System.currentTimeMillis();
                    }
                    Zed.wCooldowns.put(id, newCd);
                }
            }

            fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                    c.getLocation(), 2.0, Material.GRAY_STAINED_GLASS, 14, 0.35f, 0.1f, 5L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.9f);

            for (Location shadowLoc : activeShadows(c.getUniqueId())) { // ombres du W et du R
                if (shadowLoc.getWorld().equals(c.getWorld())) {
                    for (Entity entity : shadowLoc.getWorld().getNearbyEntities(shadowLoc, 4.0, 4.0, 4.0)) {
                        // Ennemis uniquement (avant : alliés, sbires alliés et Tibbers étaient touchés)
                        if (entity instanceof org.bukkit.entity.LivingEntity tgtEnnemi
                                && TargetingUtil.isEnemy(c, tgtEnnemi)) {
                            // Déjà tranché par Zed : pas de seconds dégâts, mais ralentissement renforcé
                            // et énergie rendue (avant : dégâts appliqués deux fois)
                            boolean both = !slashed.add(tgtEnnemi.getUniqueId());
                            if (both) {
                                if (tgtEnnemi instanceof Player) restoreDoubleHitEnergy(c, energyDone);
                            } else {
                                TargetingUtil.dealDamage(c, tgtEnnemi, dmg, TargetingUtil.DmgType.PHYSICAL);
                            }
                            if (tgtEnnemi instanceof Player __p) // 2 taillades : 30-60 %, sinon 20-40 % (wiki)
                                fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__p, both
                                        ? new double[]{0.30,0.375,0.45,0.525,0.60}[rank]
                                        : new double[]{0.20,0.25,0.30,0.35,0.40}[rank], 30);
                        }
                    }
                    fr.lolmc.util.VisualEffectUtil.groundRing(shadowLoc.getWorld(),
                            shadowLoc, 2.0, Material.GRAY_STAINED_GLASS, 14, 0.35f, 0.1f, 5L);
                    shadowLoc.getWorld().playSound(shadowLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.9f);
                }
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_zed",new double[]{70,92.5,115,137.5,160});
            return String.format("%.0f dégâts AoE (+70%%AD bonus) + ralentit 20-40%% 1.5s. -3s de recharge du W par champion.",base[getLevel()-1]+s.getBonusAD()*fr.lolmc.util.Balance.ratio("e_zed","ad",0.7));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_zed","Marque de Mort",Material.WITHER_ROSE,AbilitySlot.R,
                new double[]{120,110,100},20,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            UUID uuid=c.getUniqueId();
            // Relance (wiki) : Zed échange sa place avec l'ombre laissée à son point de départ
            Location back=rShadows.get(uuid);
            if(back!=null){
                removeRShadow(uuid);
                Location dest=back.clone();
                dest.setYaw(c.getLocation().getYaw()); dest.setPitch(c.getLocation().getPitch());
                fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                        c.getLocation(), Material.GRAY_STAINED_GLASS, 0.3f, 0.5, 6, 6L);
                c.teleport(dest);
                fr.lolmc.LolPlugin.getInstance().getRuneManager().onDash(c); // Impact soudain
                triggerCooldown(c, s);
                c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
                return;
            }
            org.bukkit.entity.LivingEntity tgt = (t!=null && TargetingUtil.isEnemy(c,t))?t:TargetingUtil.getTargetedEnemy(c,6.9); if(tgt==null){cancelCast();c.sendActionBar(Component.text("💀 Aucune cible visée",NamedTextColor.GRAY));return;} // 625 unités
            // Ombre à la position de départ (6 s) ; la recharge démarre à la relance ou à sa fin
            final Location start=c.getLocation().clone();
            deferCooldown();
            final BaseAbility rAbility=this;
            rShadows.put(uuid, start);
            final org.bukkit.entity.ArmorStand stand = start.getWorld().spawn(start, org.bukkit.entity.ArmorStand.class, as -> {
                as.setMarker(true);
                as.setGravity(false);
                as.setInvulnerable(true);
                as.setCustomNameVisible(true);
                as.customName(Component.text("👤 Ombre de Zed (R)", NamedTextColor.DARK_GRAY));
                var eq = as.getEquipment();
                eq.setHelmet(blackLeather(org.bukkit.Material.LEATHER_HELMET));
                eq.setChestplate(blackLeather(org.bukkit.Material.LEATHER_CHESTPLATE));
                eq.setLeggings(blackLeather(org.bukkit.Material.LEATHER_LEGGINGS));
                eq.setBoots(blackLeather(org.bukkit.Material.LEATHER_BOOTS));
                as.getScoreboardTags().add("zed_shadow");
            });
            rShadowEntities.put(uuid, stand.getUniqueId());
            new BukkitRunnable(){@Override public void run(){
                if(start.equals(rShadows.get(uuid))){ removeRShadow(uuid); rAbility.triggerCooldown(c, s); }
            }}.runTaskLater(LolPlugin.getInstance(), 120L);

            fr.lolmc.util.Untargetable.apply(c, 15); // intouchable pendant le bond (wiki, ~0.75 s)
            dashTo(c, tgt, 1.5); // derrière la cible
            final double startHP=TargetingUtil.getRealHealth(tgt);
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("💀 MARQUE DE MORT — 3s...",NamedTextColor.DARK_RED));
            fr.lolmc.util.VisualEffectUtil.impactBurst(tgt.getWorld(),
                    tgt.getLocation().add(0,1,0), Material.RED_STAINED_GLASS, 0.28f, 0.5, 8, 8L);
            double[] ampPct={0.25,0.40,0.55};
            int rank=Math.min(getLevel()-1,2);
            double ad=s.getFinalAD();
            // (avant : à la détonation, une ombre placée derrière la cible infligeait d'office des
            // dégâts inventés de Q et E ; dans LoL l'ombre imite seulement les sorts lancés par Zed)
            new BukkitRunnable(){
                @Override public void run(){
                    if(tgt.isDead())return;
                    double subis=Math.max(0, startHP - TargetingUtil.getRealHealth(tgt));
                    double dmg=ad + subis*ampPct[rank]; // 100 % AD + 25/40/55 % des dégâts stockés (wiki)
                    tgt.getWorld().strikeLightningEffect(tgt.getLocation());
                    fr.lolmc.util.VisualEffectUtil.impactBurst(tgt.getWorld(),
                            tgt.getLocation().add(0,1,0), Material.RED_STAINED_GLASS, 0.4f, 0.6, 10, 10L);
                    TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.TRUE);
                    if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ DÉTONATION! Marque de Mort",NamedTextColor.DARK_RED));
                }
            }.runTaskLater(LolPlugin.getInstance(),60L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] ampPct={25,40,55};int r=Math.min(getLevel()-1,2);
            return String.format("Dash + marque 3s (ombre au départ 6s, relancer pour échanger). Détonation: 100%%AD + %.0f%% des dégâts infligés.",ampPct[r]);
        }
    }

    private static org.bukkit.inventory.ItemStack blackLeather(org.bukkit.Material mat) {
        var is = new org.bukkit.inventory.ItemStack(mat);
        var meta = (org.bukkit.inventory.meta.LeatherArmorMeta) is.getItemMeta();
        if (meta != null) {
            meta.setColor(org.bukkit.Color.fromRGB(20, 20, 20));
            is.setItemMeta(meta);
        }
        return is;
    }
}