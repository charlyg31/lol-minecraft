package fr.lolmc.champion.impl.mid;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.ability.base.BasicAttackAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.TargetingUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public class Zed extends BaseChampion {
    public Zed() {
        super("zed", "Zed", ChampionRole.MID,
                new ChampionStats(654,63,0,32,32,0.651,0,345,1.25,7.0));
        getStats().setGrowthStats(99.0,3.4,4.7,2.05,0.03300,0.65);
        setAutoAttackRange(2.0);
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(582, 7.0, ResourceSystem.ResourceType.ENERGY, 200, 50.0);
    }

    // Gestion de l'ombre et des cooldowns manuels
    public static final Map<UUID,Location> shadows = new java.util.concurrent.ConcurrentHashMap<>();
    public static final Map<UUID, Long> wCooldowns = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, UUID> shadowEntities = new java.util.concurrent.ConcurrentHashMap<>();

    public static void resetState(UUID id) {
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
    public static void resetAllState() {
        for (UUID entityId : shadowEntities.values()) {
            org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(entityId);
            if (e != null) e.remove();
        }
        shadows.clear(); wCooldowns.clear(); shadowEntities.clear();
    }

    static class AA extends BasicAttackAbility {
        AA(){super("zed",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            var maxHealthAttr = tgt.getAttribute(fr.lolmc.util.Compat.maxHealth());
            double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;

            if(tgt.getHealth() < maxHealth * 0.5){
                // 5/7.5/10% PV max selon niveau (1/7/17) — LoL
                var cm=LolPlugin.getInstance().getChampionManager();
                int lvl=cm.hasChampion(c)?cm.getChampion(c).getLevelSystem().getLevel():1;
                double pct = lvl>=17 ? 0.10 : (lvl>=7 ? 0.075 : 0.05);
                double bonus = maxHealth * pct;
                TargetingUtil.dealDamage(c, tgt, bonus, TargetingUtil.DmgType.MAGICAL);
                tgt.getWorld().spawnParticle(Particle.SMOKE,tgt.getLocation().add(0,1,0),8,0.3,0.5,0.3);
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_zed","Shuriken Tranchant",Material.ARROW,AbilitySlot.Q,
                new double[]{6,5.5,5,4.5,4},20,0,DamageType.PHYSICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] baseFirst={80,120,160,200,240};
            double[] baseNext={48,72,96,120,144};
            int rank=getLevel()-1;
            double dmgFirst=baseFirst[rank]+s.getFinalAD()*1.0;
            double dmgNext=baseNext[rank]+s.getFinalAD()*0.6;
            var hits=TargetingUtil.skillshot(c, 12.0, 1.0, true);
            boolean first=true;
            for(var __t : hits){
                TargetingUtil.dealDamage(c, __t, first?dmgFirst:dmgNext, TargetingUtil.DmgType.PHYSICAL);
                first=false;
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] baseFirst={80,120,160,200,240};
            return String.format("Skillshot: %.0f dégâts au 1er ennemi (+100%%AD), réduit aux suivants.",baseFirst[getLevel()-1]+s.getFinalAD());
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
            double[] realCooldowns = {20, 18, 16, 14, 12};
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
                    c.getWorld().spawnParticle(Particle.SMOKE, shadowLoc, 15, 0.5, 1, 0.5);
                    c.getWorld().spawnParticle(Particle.SMOKE, c.getLocation(), 15, 0.5, 1, 0.5);
                    // Téléporter le joueur à la position de l'ombre
                    Location dest = shadowLoc.clone();
                    dest.setYaw(c.getLocation().getYaw());
                    dest.setPitch(c.getLocation().getPitch());
                    c.teleport(dest);
                }
            } else {
                // Coût en énergie uniquement au placement de l'ombre (40)
                var cm0 = LolPlugin.getInstance().getChampionManager();
                if (cm0.hasChampion(c)) {
                    var res = cm0.getChampion(c).getResourceSystem();
                    if (res != null && !res.consume(40)) {
                        c.sendActionBar(Component.text("❌ Pas assez d'énergie (40)", NamedTextColor.RED));
                        return;
                    }
                }
                Vector dir = c.getLocation().getDirection().setY(0).normalize();
                Location shadowLoc = c.getLocation().clone().add(dir.multiply(8));

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
                            if (eq != null) {
                                eq.setHelmet(blackLeather(org.bukkit.Material.LEATHER_HELMET));
                                eq.setChestplate(blackLeather(org.bukkit.Material.LEATHER_CHESTPLATE));
                                eq.setLeggings(blackLeather(org.bukkit.Material.LEATHER_LEGGINGS));
                                eq.setBoots(blackLeather(org.bukkit.Material.LEATHER_BOOTS));
                            }
                            as.getScoreboardTags().add("zed_shadow");
                        });
                shadowEntities.put(uuid, shadowStand.getUniqueId());
                c.getWorld().spawnParticle(Particle.SMOKE, shadowLoc, 20, 0.5, 1, 0.5);
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
                }.runTaskLater(LolPlugin.getInstance(), 80L);
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            return "Projette une ombre au sol. Re-cast pour échanger de position avec elle.";
        }
    }

    static class E extends BaseAbility {
        E(){super("e_zed","Taillade des Ombres",Material.IRON_SWORD,AbilitySlot.E,
                new double[]{4,3,2,1,0.5},5,4,DamageType.PHYSICAL);
            resourceCost = 50;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("e_zed",new double[]{70,92.5,115,137.5,160});
            double dmg=base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("e_zed","ad",0.7);
            int rank=getLevel()-1;
            int champsHit=0;
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if(__t instanceof Player __p){
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,rank,false,true));
                    champsHit++;
                }
            }

            // CORRECTION : Réduction du cooldown en manipulant directement la map manuelle wCooldowns
            if (champsHit > 0) {
                UUID id = c.getUniqueId();
                if (Zed.wCooldowns.containsKey(id)) {
                    long currentCd = Zed.wCooldowns.get(id);
                    long newCd = currentCd - (champsHit * 2000L); // Enlève 2000ms par champion
                    // S'assure que le CD ne tombe pas dans le négatif
                    if (newCd < System.currentTimeMillis()) {
                        newCd = System.currentTimeMillis();
                    }
                    Zed.wCooldowns.put(id, newCd);
                }
            }

            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation().add(0,1,0),6,2,0.5,2);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.9f);

            if (shadows.containsKey(c.getUniqueId())) {
                Location shadowLoc = shadows.get(c.getUniqueId());
                if (shadowLoc != null) {
                    for (Entity entity : shadowLoc.getWorld().getNearbyEntities(shadowLoc, 4.0, 4.0, 4.0)) {
                        if (entity instanceof org.bukkit.entity.LivingEntity tgtEnnemi && !entity.equals(c)) {
                            TargetingUtil.dealDamage(c, tgtEnnemi, dmg, TargetingUtil.DmgType.PHYSICAL);
                            if (tgtEnnemi instanceof Player __p)
                                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, rank, false, true));
                        }
                    }
                    shadowLoc.getWorld().spawnParticle(Particle.SWEEP_ATTACK, shadowLoc.clone().add(0,1,0), 6, 2, 0.5, 2);
                    shadowLoc.getWorld().playSound(shadowLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.9f);
                }
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_zed",new double[]{70,92.5,115,137.5,160});
            return String.format("%.0f dégâts AoE (+70%%AD) + ralentit 20-40%% 1.5s.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("e_zed","ad",0.7));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_zed","Marque de Mort",Material.WITHER_ROSE,AbilitySlot.R,
                new double[]{120,90,60},20,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            org.bukkit.entity.LivingEntity tgt = (t!=null)?t:TargetingUtil.getTargetedEnemy(c,8.0); if(tgt==null){c.sendActionBar(Component.text("💀 Aucune cible visée",NamedTextColor.GRAY));return;}
            var dest=tgt.getLocation().clone().add(tgt.getLocation().getDirection().multiply(1.5));
            dest.setY(c.getLocation().getY());
            c.teleport(dest);
            final double startHP=tgt.getHealth();
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("💀 MARQUE DE MORT — 3s...",NamedTextColor.DARK_RED));
            tgt.getWorld().spawnParticle(Particle.SMOKE,tgt.getLocation().add(0,1,0),20,0.5,1,0.5);
            double[] ampPct={0.25,0.40,0.55};
            int rank=Math.min(getLevel()-1,2);
            double ad=s.getFinalAD();
            // Créer une ombre miroir (à l'opposé de la cible)
            Location shadowRLoc = tgt.getLocation().clone().subtract(
                    c.getLocation().toVector().subtract(tgt.getLocation().toVector()).normalize().multiply(2.0));
            shadowRLoc.setY(tgt.getLocation().getY());
            tgt.getWorld().spawnParticle(Particle.SMOKE, shadowRLoc.add(0,1,0), 15, 0.5,1,0.5);
            // L'ombre lance Q (shuriken) et E (taillade) depuis sa position
            double shadowQDmg = ad * 0.65; // Q de l'ombre
            double shadowEDmg = ad * 0.49; // E de l'ombre (70% du E réel)
            final Location shadowFinalLoc = shadowRLoc;
            new BukkitRunnable(){
                @Override public void run(){
                    if(tgt.isDead())return;
                    double subis=Math.max(0, startHP - tgt.getHealth());
                    double dmg=ad*0.65 + subis*ampPct[rank];
                    tgt.getWorld().strikeLightningEffect(tgt.getLocation());
                    tgt.getWorld().spawnParticle(Particle.FLASH,tgt.getLocation().add(0,1,0),2);
                    TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.TRUE);
                    if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ DÉTONATION! Marque de Mort",NamedTextColor.DARK_RED));
                    // Ombre miroir : lance Q puis E sur les ennemis proches
                    for (var nearTgt : TargetingUtil.entitiesInRadius(c, shadowFinalLoc.clone().subtract(0,1,0), 5.0)) {
                        TargetingUtil.dealDamage(c, nearTgt, shadowQDmg, TargetingUtil.DmgType.PHYSICAL);
                    }
                    for (var nearTgt : TargetingUtil.entitiesInRadius(c, shadowFinalLoc.clone().subtract(0,1,0), 4.0)) {
                        TargetingUtil.dealDamage(c, nearTgt, shadowEDmg, TargetingUtil.DmgType.PHYSICAL);
                    }
                    shadowFinalLoc.getWorld().spawnParticle(Particle.FLASH, shadowFinalLoc, 2);
                }
            }.runTaskLater(LolPlugin.getInstance(),60L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] ampPct={25,40,55};int r=Math.min(getLevel()-1,2);
            return String.format("Dash + marque 3s. Détonation: 65%%AD + %.0f%% des dégâts infligés.",ampPct[r]);
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