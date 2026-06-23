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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
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
        setAutoAttackRange(5.5);
    }

    // Suivi de Tibbers et cooldowns manuels
    private static final Map<UUID, org.bukkit.entity.IronGolem> activeTibbers = new HashMap<>();
    private static final Map<UUID, Long> rCooldowns = new HashMap<>();

    public static void resetState(UUID id) {
        if (activeTibbers.containsKey(id)) {
            var golem = activeTibbers.remove(id);
            if (golem != null && golem.isValid()) golem.remove();
        }
    }
    public static void resetAllState() {
        activeTibbers.values().forEach(g -> { if (g != null) g.remove(); });
        activeTibbers.clear();
    }

    static class AA extends BasicAttackAbility {
        AA(){super("annie",Material.FIRE_CHARGE,5.5f,DamageType.MAGICAL);}
    }

    static class Q extends BaseAbility {
        Q(){super("q_annie","Brasier",Material.BLAZE_POWDER,AbilitySlot.Q,
            new double[]{4,3.5,3,2.5,2},20,0,DamageType.MAGICAL);
            resourceCost = 60;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            var target = TargetingUtil.getTargetedEnemy(c, 6.5);
            if(target==null){ c.sendActionBar(Component.text("Aucune cible",NamedTextColor.GRAY)); return; }
            double[] base=fr.lolmc.util.Balance.base("q_annie",new double[]{80,130,180,230,280});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_annie","ap",0.85);
            TargetingUtil.dealDamage(c, target, dmg, TargetingUtil.DmgType.MAGICAL);
            target.getWorld().spawnParticle(Particle.FLAME,target.getLocation().add(0,1,0),20,0.5,0.5,0.5,0.1);
            target.getWorld().spawnParticle(Particle.SMALL_FLAME,target.getLocation().add(0,1,0),15,0.3,0.5,0.3,0.05);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_annie",new double[]{80,130,180,230,280});
            return String.format("%.0f dégâts magiques (%.0f+85%%AP).",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_annie","ap",0.85),base[getLevel()-1]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_annie","Incinération",Material.CAMPFIRE,AbilitySlot.W,
            new double[]{8,7,6,5,4},6,4,DamageType.MAGICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            double[] base=fr.lolmc.util.Balance.base("w_annie",new double[]{70,115,160,205,250});
            double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_annie","ap",0.75);
            var targets = TargetingUtil.enemiesInCone(c, 6.0, 50);
            TargetingUtil.dealDamageAll(c, targets, dmg, TargetingUtil.DmgType.MAGICAL);
            var dir = c.getEyeLocation().getDirection().normalize();
            for(double d=1; d<=6; d+=0.5){
                var p=c.getEyeLocation().add(dir.clone().multiply(d));
                c.getWorld().spawnParticle(Particle.FLAME,p,8,0.6,0.4,0.6,0.02);
            }
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("w_annie",new double[]{70,115,160,205,250});
            return String.format("Cône de feu: %.0f dégâts (%.0f+75%%AP) dans 4 blocs.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("w_annie","ap",0.75),base[getLevel()-1]);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_annie","Molten Shield",Material.ORANGE_STAINED_GLASS,AbilitySlot.E,
            new double[]{10,9,8,7,6},0,0,DamageType.MAGICAL);
            resourceCost = 40;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            Player dest=t!=null?t:c;
            dest.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,100,0,false,true));
            dest.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,100,0,false,true));
            dest.sendActionBar(Component.text("🔥 Molten Shield 5s!",NamedTextColor.GOLD));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Bouclier 5s + immunité feu. Dégâts en retour aux attaquants.";}
    }

    static class R extends BaseAbility {
        R(){
            // Cooldown de l'infrastructure à 0 pour autoriser les re-casts de ciblage
            super("r_annie","Invocation de Tibbers",Material.NETHERITE_BLOCK,AbilitySlot.R,
                new double[]{0,0,0},20,3,DamageType.MAGICAL);
            resourceCost = 100;
        }

        @Override public void cast(Player c,ChampionStats s,Player t){
            UUID uuid = c.getUniqueId();
            long now = System.currentTimeMillis();
            double[] realCooldowns = {100, 80, 60};
            int r = Math.min(getLevel() - 1, 2);
            R thisAbility = this;

            // 1. RE-CAST : Si Tibbers est vivant, on redirige ses attaques
            if (activeTibbers.containsKey(uuid)) {
                org.bukkit.entity.IronGolem tibbers = activeTibbers.get(uuid);
                if (tibbers != null && tibbers.isValid()) {
                    org.bukkit.entity.LivingEntity enemy = TargetingUtil.getTargetedEnemy(c, 15.0);
                    if (enemy != null) {
                        tibbers.setTarget(enemy);
                        c.sendActionBar(Component.text("🐻 Tibbers cible ➔ " + enemy.getName() + " !", NamedTextColor.RED));
                    } else {
                        Location ground = TargetingUtil.getAimedGroundLocation(c, 15.0);
                        if (ground != null) {
                            tibbers.getPathfinder().moveTo(ground, 1.4);
                            c.sendActionBar(Component.text("🐻 Tibbers se déplace !", NamedTextColor.GOLD));
                        }
                    }
                    return;
                }
            }

            // 2. VÉRIFICATION DU COOLDOWN MANUEL (Premier cast uniquement)
            if (rCooldowns.containsKey(uuid) && rCooldowns.get(uuid) > now) {
                long remaining = (rCooldowns.get(uuid) - now) / 1000;
                c.sendActionBar(Component.text("⏳ Ultime en récupération (" + remaining + "s)", NamedTextColor.RED));
                return;
            }

            // 3. PREMIER CAST : Explosion de zone + Invocation
            double[] base = fr.lolmc.util.Balance.base("r_annie", new double[]{175, 300, 425});
            double dmg = base[r] + s.getFinalAP() * fr.lolmc.util.Balance.ratio("r_annie", "ap", 0.75);
            var ground = TargetingUtil.getAimedGroundLocation(c, 7.0);
            if (ground == null) ground = c.getLocation();

            var targets = TargetingUtil.entitiesInRadius(c, ground, 4.0);
            for(var e: targets){
                TargetingUtil.dealDamage(c, e, dmg, TargetingUtil.DmgType.MAGICAL);
                e.setFireTicks(60);
            }

            ground.getWorld().spawnParticle(Particle.LAVA, ground, 40, 3, 1, 3);
            ground.getWorld().spawnParticle(Particle.FLAME, ground, 50, 3, 1.5, 3, 0.1);
            ground.getWorld().playSound(ground, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);

            // Spawner de Tibbers (IronGolem modifié)
            org.bukkit.entity.IronGolem tibbers = ground.getWorld().spawn(ground, org.bukkit.entity.IronGolem.class, golem -> {
                golem.setCustomName("§cTibbers de " + c.getName());
                golem.setCustomNameVisible(true);
                
                // Boost de statistiques (Vitesse de LoL + Dégâts scalés AP)
                var speedAttr = golem.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
                if (speedAttr != null) speedAttr.setBaseValue(0.35);
                
                var dmgAttr = golem.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
                if (dmgAttr != null) dmgAttr.setBaseValue(20.0 + (s.getFinalAP() * 0.15));
            });

            activeTibbers.put(uuid, tibbers);
            thisAbility.resourceCost = 0; // Les re-casts pour ordonner d'attaquer deviennent gratuits !

            // Agression automatique instantanée sur le plus proche si existant
            for (Entity entity : tibbers.getNearbyEntities(10.0, 10.0, 10.0)) {
                if (entity instanceof org.bukkit.entity.LivingEntity le && !entity.equals(c) && !entity.equals(tibbers)) {
                    tibbers.setTarget(le);
                    break;
                }
            }

            // Boucle de cycle de vie : Aura de feu et expiration après 45 secondes
            new BukkitRunnable() {
                int elapsed = 0;
                @Override public void run() {
                    if (!tibbers.isValid() || elapsed >= 45 || !c.isOnline()) {
                        if (tibbers.isValid()) tibbers.remove();
                        activeTibbers.remove(uuid);
                        thisAbility.resourceCost = 100; // Restauration du coût en mana
                        rCooldowns.put(uuid, System.currentTimeMillis() + (long)(realCooldowns[r] * 1000));
                        if (c.isOnline()) c.sendActionBar(Component.text("🐻 Tibbers s'est dissipé.", NamedTextColor.GRAY));
                        cancel();
                        return;
                    }

                    // Particules de l'aura brûlante
                    tibbers.getWorld().spawnParticle(Particle.FLAME, tibbers.getLocation().add(0, 1, 0), 8, 0.4, 0.5, 0.4, 0.02);
                    
                    // Dégâts magiques continus autour de Tibbers
                    double auraDmg = 20 + (s.getFinalAP() * 0.12);
                    for (Entity entity : tibbers.getNearbyEntities(3.5, 3.5, 3.5)) {
                        if (entity instanceof org.bukkit.entity.LivingEntity target && !entity.equals(c) && !entity.equals(tibbers)) {
                            TargetingUtil.dealDamage(c, target, auraDmg, TargetingUtil.DmgType.MAGICAL);
                            target.setFireTicks(30);
                        }
                    }
                    elapsed++;
                }
            }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
        }

        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("r_annie",new double[]{175,300,425});
            int r=Math.min(getLevel()-1,2);
            return String.format("%.0f dégâts AoE + Invoque Tibbers (45s). Re-cast R pour ordonner d'attaquer.",base[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_annie","ap",0.75));
        }
    }
}
