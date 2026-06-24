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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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

    // Gestion de l'état global des ombres de Zed
    private static final Map<UUID, Location> shadows = new HashMap<>();
    private static final Map<UUID, ArmorStand> shadowEntities = new HashMap<>();
    private static final Map<UUID, Long> wCooldowns = new HashMap<>();

    public static void resetState(UUID id){
        shadows.remove(id);
        ArmorStand stand = shadowEntities.remove(id);
        if (stand != null && stand.isValid()) stand.remove();
    }
    public static void resetAllState(){
        shadows.clear();
        for (ArmorStand stand : shadowEntities.values()) {
            if (stand != null && stand.isValid()) stand.remove();
        }
        shadowEntities.clear();
    }

    static class AA extends BasicAttackAbility {
        AA(){super("zed",Material.IRON_SWORD,2.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg){
            if(tgt.getHealth() < tgt.getMaxHealth()*0.5){
                double bonus=tgt.getMaxHealth()*0.08;
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

            // 1. Shuriken du joueur
            var hits=TargetingUtil.skillshot(c, 12.0, 1.0, true);
            boolean first=true;
            for(var __t : hits){
                TargetingUtil.dealDamage(c, __t, first?dmgFirst:dmgNext, TargetingUtil.DmgType.PHYSICAL);
                first=false;
            }
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.3f);

            // 2. Shuriken de l'Ombre
            UUID uuid = c.getUniqueId();
            if (shadows.containsKey(uuid)) {
                Location shadowLoc = shadows.get(uuid);
                if (shadowLoc != null) {
                    Vector dir = c.getLocation().getDirection().setY(0).normalize();
                    shadowLoc.getWorld().playSound(shadowLoc, Sound.ENTITY_ARROW_SHOOT, 1f, 1.3f);

                    List<LivingEntity> shadowHits = new ArrayList<>();
                    for (double d = 1.0; d <= 12.0; d += 0.5) {
                        Location checkLoc = shadowLoc.clone().add(dir.clone().multiply(d)).add(0, 1, 0);
                        checkLoc.getWorld().spawnParticle(Particle.CRIT, checkLoc, 1, 0, 0, 0, 0);

                        for (Entity entity : checkLoc.getWorld().getNearbyEntities(checkLoc, 1.0, 1.0, 1.0)) {
                            if (entity instanceof LivingEntity tgtEnnemi && !entity.equals(c) && !(entity instanceof ArmorStand)) {
                                if (!shadowHits.contains(tgtEnnemi)) {
                                    shadowHits.add(tgtEnnemi);
                                    double dmg = (shadowHits.size() == 1) ? dmgFirst : dmgNext;
                                    TargetingUtil.dealDamage(c, tgtEnnemi, dmg, TargetingUtil.DmgType.PHYSICAL);
                                }
                            }
                        }
                    }
                }
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] baseFirst={80,120,160,200,240};
            return String.format("Skillshot: %.0f dégâts au 1er ennemi (+100%%AD), réduit aux suivants. Répliqué par l'ombre.",baseFirst[getLevel()-1]+s.getFinalAD());
        }
    }

    static class W extends BaseAbility {
        private double energyCost = 40; // Variable locale étanche
        private long lastClickTime = 0; // Sécurité anti-double clic Spigot

        W(){
            super("w_zed","Ombre Vivante",Material.GRAY_DYE,AbilitySlot.W,
                    new double[]{0,0,0,0,0},0,0,DamageType.TRUE);
        }

        // --- FORÇAGE DU FRAMEWORK COMPORTEMENTAL ---
        @Override
        public double getResourceCost() {
            return this.energyCost;
        }

        @Override
        public boolean isOnCooldown(Player player) {
            if (shadows.containsKey(player.getUniqueId())) return false; // Permet le recast immédiat
            return wCooldowns.containsKey(player.getUniqueId()) && wCooldowns.get(player.getUniqueId()) > System.currentTimeMillis();
        }

        @Override
        public double getRemainingCooldown(Player player) {
            if (shadows.containsKey(player.getUniqueId())) return 0;
            UUID uuid = player.getUniqueId();
            if (!wCooldowns.containsKey(uuid)) return 0;
            long rem = wCooldowns.get(uuid) - System.currentTimeMillis();
            return rem > 0 ? rem / 1000.0 : 0;
        }

        @Override public void cast(Player c,ChampionStats s,Player t){
            UUID uuid = c.getUniqueId();
            long now = System.currentTimeMillis();

            // Filtrage anti double-envoi simultané (Main Droite / Main Gauche)
            if (now - lastClickTime < 200) return;
            lastClickTime = now;

            int rank = Math.min(getLevel() - 1, 4);
            double[] realCooldowns = {20, 18, 16, 14, 12};

            if(shadows.containsKey(uuid)) {
                // ─── RE-CAST : TELEPORTATION ───
                Location shadowLoc = shadows.get(uuid);
                if (shadowLoc != null) {
                    ArmorStand stand = shadowEntities.remove(uuid);
                    if (stand != null && stand.isValid()) stand.remove();

                    c.teleport(shadowLoc.add(0, 0.1, 0));
                    shadows.remove(uuid);

                    c.sendActionBar(Component.text("👤 Échange avec l'ombre !", NamedTextColor.DARK_GRAY));
                    wCooldowns.put(uuid, now + (long)(realCooldowns[rank] * 1000));

                    this.energyCost = 40; // Restaure le coût
                }
            } else {
                // ─── PREMIER CAST : INVOCATION ───
                Vector dir = c.getLocation().getDirection().setY(0).normalize();
                Location shadowLoc = c.getLocation().clone().add(dir.multiply(7));
                shadowLoc.setY(c.getLocation().getY());
                shadowLoc.setYaw(c.getLocation().getYaw());
                shadowLoc.setPitch(c.getLocation().getPitch());

                shadows.put(uuid, shadowLoc);
                this.energyCost = 0; // Le second clic devient gratuit au niveau du framework

                ArmorStand stand = shadowLoc.getWorld().spawn(shadowLoc, ArmorStand.class, entity -> {
                    entity.setVisible(false);
                    entity.setGravity(false);
                    entity.setBasePlate(false);
                    entity.setArms(true);
                    entity.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(Material.WITHER_SKELETON_SKULL));

                    org.bukkit.inventory.ItemStack chest = new org.bukkit.inventory.ItemStack(Material.LEATHER_CHESTPLATE);
                    var meta = (org.bukkit.inventory.meta.LeatherArmorMeta) chest.getItemMeta();
                    if (meta != null) {
                        meta.setColor(Color.BLACK);
                        chest.setItemMeta(meta);
                    }
                    entity.getEquipment().setChestplate(chest);
                });
                shadowEntities.put(uuid, stand);

                c.getWorld().spawnParticle(Particle.SMOKE, shadowLoc.clone().add(0, 1, 0), 20, 0.5, 1, 0.5);
                c.sendActionBar(Component.text("👤 Ombre créée ! Re-cast pour échanger de place.", NamedTextColor.DARK_GRAY));

                new BukkitRunnable(){
                    @Override public void run(){
                        if (shadows.containsKey(uuid)) {
                            shadows.remove(uuid);
                            ArmorStand st = shadowEntities.remove(uuid);
                            if (st != null && st.isValid()) st.remove();

                            wCooldowns.put(uuid, System.currentTimeMillis() + (long)(realCooldowns[rank] * 1000));
                            W.this.energyCost = 40;
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

            // 1. Dégâts autour du corps de Zed
            for(var __t : TargetingUtil.enemiesAround(c, 4.0)){
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.PHYSICAL);
                if(__t instanceof Player __p)
                    __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,rank,false,true));
            }
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK,c.getLocation().add(0,1,0),6,2,0.5,2);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.9f);

            // 2. Dégâts autour de l'Ombre de Zed
            if (shadows.containsKey(c.getUniqueId())) {
                Location shadowLoc = shadows.get(c.getUniqueId());
                if (shadowLoc != null) {
                    shadowLoc.getWorld().spawnParticle(Particle.SWEEP_ATTACK, shadowLoc.clone().add(0,1,0), 6, 2, 0.5, 2);
                    shadowLoc.getWorld().playSound(shadowLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.9f);

                    for (Entity entity : shadowLoc.getWorld().getNearbyEntities(shadowLoc, 4.0, 4.0, 4.0)) {
                        if (entity instanceof LivingEntity tgtEnnemi && !entity.equals(c) && !(entity instanceof ArmorStand)) {
                            TargetingUtil.dealDamage(c, tgtEnnemi, dmg, TargetingUtil.DmgType.PHYSICAL);
                            if (tgtEnnemi instanceof Player __p)
                                __p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, rank, false, true));
                        }
                    }
                }
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("e_zed",new double[]{70,92.5,115,137.5,160});
            return String.format("%.0f dégâts AoE (+70%%AD) + ralentit 20-40%% 1.5s. Répliqué par l'ombre.",base[getLevel()-1]+s.getFinalAD()*fr.lolmc.util.Balance.ratio("e_zed","ad",0.7));
        }
    }

    static class R extends BaseAbility {
        R(){super("r_zed","Marque de Mort",Material.WITHER_ROSE,AbilitySlot.R,
                new double[]{120,100,80},20,0,DamageType.TRUE);
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
            new BukkitRunnable(){
                @Override public void run(){
                    if(tgt.isDead())return;
                    double subis=Math.max(0, startHP - tgt.getHealth());
                    double dmg=ad*0.65 + subis*ampPct[rank];
                    tgt.getWorld().strikeLightningEffect(tgt.getLocation());
                    tgt.getWorld().spawnParticle(Particle.FLASH,tgt.getLocation().add(0,1,0),2);
                    TargetingUtil.dealDamage(c, tgt, dmg, TargetingUtil.DmgType.TRUE);
                    if(tgt instanceof Player _tp)_tp.sendMessage(Component.text("☠ DÉTONATION! Marque de Mort",NamedTextColor.DARK_RED));
                }
            }.runTaskLater(LolPlugin.getInstance(),60L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.7f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] ampPct={25,40,55};int r=Math.min(getLevel()-1,2);
            return String.format("Dash + marque 3s. Détonation: 65%%AD + %.0f%% des dégâts infligés.",ampPct[r]);
        }
    }
}