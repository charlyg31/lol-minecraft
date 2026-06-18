package fr.lolmc.champion.impl.top;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Collection;

public class Malphite extends BaseChampion {

    public Malphite() {
        super("malphite", "Malphite", ChampionRole.TOP,
            new ChampionStats(574, 52, 0, 36, 32, 0.625, 0, 335, 5.0, 8.5));
    }

    @Override
    protected void registerAbilities() {
        setAbility(0, new MalphiteAA());
        setAbility(1, new MalphiteQ());
        setAbility(2, new MalphiteW());
        setAbility(3, new MalphiteE());
        setAbility(4, new MalphiteR());
    }

    static class MalphiteAA extends BaseAbility {
        MalphiteAA() {
            super("malphite_aa","Attaque de base","Frappe pour {ad} dégâts physiques.",
                Material.STONE_SWORD, AbilitySlot.AA, new double[]{0.5,0.48,0.46,0.44,0.42},
                5,0,DamageType.PHYSICAL);
        }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            if(t==null)return; t.damage(s.calcAutoAttackDamage(null)); s.applyVamp(s.getFinalAD(),false);
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            return String.format("Frappe pour %.0f dégâts physiques.",s.getFinalAD());
        }
    }

    // Q — Éclat de pierre
    static class MalphiteQ extends BaseAbility {
        MalphiteQ() {
            super("malphite_q","Éclat de pierre",
                "Envoie un projectile infligeant {dmg} dégâts magiques et ralentissant la cible de 25% pendant 2s.",
                Material.COBBLESTONE, AbilitySlot.Q, new double[]{8,7.5,7,6.5,6},
                20, 0, DamageType.MAGICAL);
        }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            if(t==null)return;
            double dmg = s.calcMagicalDamage(70 + 0.6*s.getFinalAP() + 0.1*s.getFinalMaxHP(), null);
            t.damage(dmg);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,true));
            t.sendActionBar(Component.text("🪨 Ralenti par Malphite!",NamedTextColor.GRAY));
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            double dmg=70+0.6*s.getFinalAP()+0.1*s.getFinalMaxHP();
            return String.format("Inflige %.0f dégâts magiques (70 + 60%% AP + 10%% HP) et ralentit 25%%.",dmg);
        }
    }

    // W — Frappe sismique
    static class MalphiteW extends BaseAbility {
        MalphiteW() {
            super("malphite_w","Frappe sismique",
                "Passif: +{armor} armure. Actif: Réduit la vitesse d'attaque des ennemis proches de 30% pendant 3s.",
                Material.IRON_CHESTPLATE, AbilitySlot.W, new double[]{12,11,10,9,8},
                5, 3, DamageType.PHYSICAL);
        }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            Player malphite=t; if(malphite==null)return;
            malphite.getWorld().getNearbyEntities(malphite.getLocation(),3,2,3).stream()
                .filter(e->e instanceof Player && !e.equals(malphite))
                .forEach(e->((Player)e).addPotionEffect(
                    new PotionEffect(PotionEffectType.SLOWNESS,60,0,false,true)));
            malphite.sendActionBar(Component.text("💥 Frappe sismique!",NamedTextColor.DARK_GRAY));
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            return String.format("Passif: +%.0f armure. Actif: Réduit vitesse attaque ennemis proches.",s.getFinalArmor()*0.1);
        }
    }

    // E — Sol fracturé
    static class MalphiteE extends BaseAbility {
        MalphiteE() {
            super("malphite_e","Sol fracturé",
                "Inflige {dmg} dégâts magiques aux ennemis proches et les ralentit.",
                Material.CRACKED_STONE_BRICKS, AbilitySlot.E, new double[]{10,9,8,7,6},
                5, 4, DamageType.MAGICAL);
        }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            Player malphite=t; if(malphite==null)return;
            Location loc=malphite.getLocation();
            double dmg=60+0.3*s.getFinalAP()+0.2*s.getFinalArmor();
            loc.getWorld().getNearbyEntities(loc,4,2,4).stream()
                .filter(e->e instanceof Player&&!e.equals(malphite))
                .forEach(e->{
                    ((Player)e).damage(s.calcMagicalDamage(dmg,null));
                    ((Player)e).addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,1,false,true));
                });
            loc.getWorld().spawnParticle(Particle.BLOCK, loc,20,2,0,2,
                Material.STONE.createBlockData());
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            double dmg=60+0.3*s.getFinalAP()+0.2*s.getFinalArmor();
            return String.format("Inflige %.0f dégâts magiques (60 + 30%% AP + 20%% Armure) dans 4 blocs.",dmg);
        }
    }

    // R — Assaut implacable
    static class MalphiteR extends BaseAbility {
        MalphiteR() {
            super("malphite_r","Assaut implacable",
                "Bondit sur la cible, infligeant {dmg} dégâts magiques et projetant les ennemis en l'air 1.5s.",
                Material.ANVIL, AbilitySlot.R, new double[]{130,105,80},
                20, 4, DamageType.MAGICAL);
        }
        @Override public void cast(BaseChampion c, ChampionStats s, Player t) {
            if(t==null)return;
            Player malphite=(Player)c.getClass(); // NOTE: remplacer par le vrai player via manager
            // Téléportation sûre vers la cible
            Location dest=safeTeleportLocationFromTo(t.getLocation().add(0,0,0), t.getLocation());
            // Dégâts + knockup
            double dmg=s.calcMagicalDamage(200+0.7*s.getFinalAP()+0.4*s.getFinalArmor(),null);
            t.getLocation().getWorld().getNearbyEntities(t.getLocation(),4,2,4).stream()
                .filter(e->e instanceof Player)
                .forEach(e->{
                    Player victim=(Player)e;
                    victim.damage(s.calcMagicalDamage(dmg,null));
                    // Knockup = vitesse vers le haut
                    victim.setVelocity(new Vector(
                        victim.getVelocity().getX()*0.5,
                        1.2,
                        victim.getVelocity().getZ()*0.5));
                    victim.sendActionBar(Component.text("🪨 KNOCKUP!",NamedTextColor.DARK_RED));
                });
            t.getLocation().getWorld().spawnParticle(Particle.EXPLOSION,t.getLocation(),5,2,0,2);
        }
        @Override public String getDynamicDescription(ChampionStats s) {
            double dmg=200+0.7*s.getFinalAP()+0.4*s.getFinalArmor();
            return String.format("Bondit sur la cible. %.0f dégâts magiques (200 + 70%% AP + 40%% Armure). Knockup 1.5s sur 4 blocs.",dmg);
        }
    }
}
