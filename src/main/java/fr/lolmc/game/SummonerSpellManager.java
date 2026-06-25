package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.LolUnits;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les sorts d'invocateur de LoL (hors Flash, déjà géré par FlashManager).
 *
 * Sorts implémentés avec leurs cooldowns officiels :
 *   - Ignite (Embrasement) : dégâts bruts sur la durée + soins réduits — CD 180s
 *   - Heal (Soin) : soigne et donne un boost de vitesse — CD 240s
 *   - Barrier (Bouclier) : bouclier temporaire — CD 180s
 *   - Exhaust (Épuisement) : ralentit et affaiblit un ennemi — CD 210s
 *   - Teleport (Téléportation) : TP à une structure alliée — CD 360s→240s
 *   - Smite (Châtiment) : dégâts bruts aux monstres — CD 15s (charges)
 *   - Cleanse (Purification) : retire les CC — CD 210s
 *   - Ghost (Fantôme) : boost de vitesse traversant — CD 210s
 */
public class SummonerSpellManager {

    public enum Spell {
        IGNITE("Embrasement", 180),
        HEAL("Soin", 240),
        BARRIER("Bouclier", 180),
        EXHAUST("Épuisement", 210),
        TELEPORT("Téléportation", 360),
        SMITE("Châtiment", 15),
        CLEANSE("Purification", 210),
        GHOST("Fantôme", 210);

        public final String displayName;
        public final int cooldownSeconds;
        Spell(String displayName, int cd) { this.displayName = displayName; this.cooldownSeconds = cd; }
    }

    // Cooldowns par joueur et par sort : "uuid:SPELL" → timestamp de fin
    private final Map<String, Long> cooldowns = new HashMap<>();

    public boolean isOnCooldown(Player player, Spell spell) {
        Long until = cooldowns.get(player.getUniqueId() + ":" + spell.name());
        return until != null && System.currentTimeMillis() < until;
    }

    public double getRemainingCooldown(Player player, Spell spell) {
        Long until = cooldowns.get(player.getUniqueId() + ":" + spell.name());
        if (until == null) return 0;
        return Math.max(0, (until - System.currentTimeMillis()) / 1000.0);
    }

    private void triggerCooldown(Player player, Spell spell) {
        cooldowns.put(player.getUniqueId() + ":" + spell.name(),
                System.currentTimeMillis() + spell.cooldownSeconds * 1000L);
    }

    /**
     * Lance un sort d'invocateur. La cible est optionnelle (selon le sort).
     */
    public boolean cast(Player caster, Spell spell, Player target) {
        if (isOnCooldown(caster, spell)) {
            caster.sendActionBar(Component.text(
                    "⏳ " + spell.displayName + " en recharge (" 
                    + (int) getRemainingCooldown(caster, spell) + "s)", NamedTextColor.RED));
            return false;
        }

        boolean success = switch (spell) {
            case IGNITE -> castIgnite(caster, target);
            case HEAL -> castHeal(caster);
            case BARRIER -> castBarrier(caster);
            case EXHAUST -> castExhaust(caster, target);
            case TELEPORT -> castTeleport(caster);
            case SMITE -> castSmite(caster);
            case CLEANSE -> castCleanse(caster);
            case GHOST -> castGhost(caster);
        };

        if (success) triggerCooldown(caster, spell);
        return success;
    }

    // ── Embrasement : dégâts vrais sur 5s (valeurs LoL: 70-410 selon niveau) ──

    private boolean castIgnite(Player caster, Player target) {
        if (target == null) {
            caster.sendActionBar(Component.text("🔥 Embrasement nécessite une cible", NamedTextColor.GRAY));
            return false;
        }
        // Portée d'Embrasement : 600 unités = 6 blocs
        if (caster.getLocation().distance(target.getLocation()) > LolUnits.toBlocks(600)) {
            caster.sendActionBar(Component.text("🔥 Cible hors de portée", NamedTextColor.GRAY));
            return false;
        }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(target)) return false;

        int casterLevel = cm.hasChampion(caster)
                ? cm.getChampion(caster).getLevelSystem().getLevel()
                : 1;
        // Dégâts LoL : 70 + 20 par niveau (70 au niv 1 → 410 au niv 18), sur 5s
        double totalDamage = 50 + 20 * casterLevel;
        double perTick = totalDamage / 5.0;

        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 5 || target.isDead() || !target.isOnline()) { cancel(); return; }
                DamageUtil.damage(caster, target, perTick, true, DamageUtil.Type.TRUE);
                target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0,1,0), 8, 0.3, 0.5, 0.3);
                ticks++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);

        // Embrasement applique aussi Grievous Wounds (soins réduits) — marqué via effet
        caster.sendActionBar(Component.text("🔥 Embrasement sur " + target.getName() + "!", NamedTextColor.GOLD));
        return true;
    }

    // ── Soin : rend des PV + vitesse (LoL: 90-345 PV selon niveau) ──

    private boolean castHeal(Player caster) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(caster)) return false;
        BaseChampion champ = cm.getChampion(caster);
        int level = champ.getLevelSystem().getLevel();
        double healAmount = 75 + 15 * level; // approximation LoL
        champ.getHPSystem().heal(healAmount);
        caster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 1, false, true));
        caster.getWorld().spawnParticle(Particle.HEART, caster.getLocation().add(0,1,0), 10, 0.5, 0.5, 0.5);
        caster.sendActionBar(Component.text("💚 Soin! +" + (int) healAmount + " PV", NamedTextColor.GREEN));
        var hud = LolPlugin.getInstance().getHUDManager();
        if (hud != null) hud.updateHUD(caster, champ);
        return true;
    }

    // ── Bouclier : absorbe des dégâts temporairement (LoL: 115-455) ──

    private boolean castBarrier(Player caster) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(caster)) return false;
        BaseChampion champ = cm.getChampion(caster);
        int level = champ.getLevelSystem().getLevel();
        double shield = 100 + 20 * level;
        champ.getStats().addShield(shield);
        caster.getWorld().spawnParticle(Particle.END_ROD, caster.getLocation().add(0,1,0), 15, 0.5, 0.8, 0.5);
        caster.sendActionBar(Component.text("🛡 Bouclier! +" + (int) shield, NamedTextColor.YELLOW));
        // Le bouclier disparaît après 2s (comme LoL)
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() { champ.getStats().clearShields(); }
        }.runTaskLater(LolPlugin.getInstance(), 40L);
        return true;
    }

    // ── Épuisement : ralentit fortement + réduit dégâts d'un ennemi ──

    private boolean castExhaust(Player caster, Player target) {
        if (target == null) {
            caster.sendActionBar(Component.text("Épuisement nécessite une cible", NamedTextColor.GRAY));
            return false;
        }
        // Portée Exhaust : 650 unités = 6.5 blocs
        if (caster.getLocation().distance(target.getLocation()) > LolUnits.toBlocks(650)) return false;
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 2, false, true)); // ~2.5s
        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 50, 1, false, true));
        var cm = LolPlugin.getInstance().getChampionManager();
        if (cm.hasChampion(target)) {
            cm.getChampion(target).getStats().addFlatDamageReduction(0); // marqueur
        }
        target.sendActionBar(Component.text("😵 Épuisé par " + caster.getName() + "!", NamedTextColor.GRAY));
        caster.sendActionBar(Component.text("Épuisement lancé!", NamedTextColor.AQUA));
        return true;
    }

    // ── Téléportation : retour à la base (simplifié) ──

    private boolean castTeleport(Player caster) {
        // LoL : TP vers une tourelle alliee, un sbire allié ou la base
        // On cherche la cible la plus proche dans la direction du regard
        var cm = LolPlugin.getInstance().getChampionManager();
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(caster);

        // 1. Chercher une tourelle alliee proche du curseur (via MapManager)
        var mapMgr = LolPlugin.getInstance().getMapManager();
        if (mapMgr != null && team != null) {
            var structures = mapMgr.getStructuresForTeam(team);
            if (structures != null && !structures.isEmpty()) {
                // Trouver la structure la plus proche du regard (dans 500 blocs)
                var eye = caster.getEyeLocation();
                var dir = eye.getDirection().normalize();
                fr.lolmc.game.GameStructure best = null; double bestDot = 0.8;
                for (var s : structures) {
                    if (s.isDestroyed()) continue;
                    var toS = s.getCenter().toVector().subtract(eye.toVector());
                    double dist = toS.length();
                    if (dist > 500 || dist < 1) continue;
                    double dot = toS.normalize().dot(dir);
                    if (dot > bestDot) { bestDot = dot; best = s; }
                }
                if (best != null) {
                    final var target = best;
                    caster.sendActionBar(net.kyori.adventure.text.Component.text(
                        "\uD83C\uDF00 Teleportation vers " + target.getType().name() + " dans 4s...",
                        net.kyori.adventure.text.format.NamedTextColor.AQUA));
                    new org.bukkit.scheduler.BukkitRunnable() {
                        @Override public void run() {
                            if (caster.isOnline()) {
                                caster.teleport(target.getCenter());
                                caster.playSound(target.getCenter(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                            }
                        }
                    }.runTaskLater(LolPlugin.getInstance(), 80L); // 4s
                    return true;
                }
            }
        }

        // 2. Fallback : TP vers la base de l'equipe
        var base = mapMgr != null && team != null ? mapMgr.getSpawn(team, 0) : null;
        if (base != null && caster.isOnline()) {
            caster.sendActionBar(net.kyori.adventure.text.Component.text(
                "\uD83C\uDF00 Retour a la base dans 4s...", net.kyori.adventure.text.format.NamedTextColor.AQUA));
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() {
                    if (caster.isOnline()) {
                        caster.teleport(base);
                        caster.playSound(base, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    }
                }
            }.runTaskLater(LolPlugin.getInstance(), 80L);
            return true;
        }
        return false;
    }

    // ── Châtiment : dégâts bruts à un monstre proche (LoL: 600 vrais) ──

    private boolean castSmite(Player caster) {
        // Portée Smite : 500 unités = 5 blocs
        double range = LolUnits.toBlocks(500);
        LivingEntity target = null;
        double closest = Double.MAX_VALUE;
        for (var e : caster.getNearbyEntities(range, range, range)) {
            if (e instanceof LivingEntity le && JungleManager.isJungleMonster(le)) {
                double d = le.getLocation().distance(caster.getLocation());
                if (d < closest) { closest = d; target = le; }
            }
        }
        if (target == null) {
            caster.sendActionBar(Component.text("⚔ Aucun monstre à châtier", NamedTextColor.GRAY));
            return false;
        }
        // Châtiment = dégâts bruts ; sécurise l'objectif (crédit/or au lanceur)
        double smiteDamage = 600;
        double cur = target.getHealth();
        if (smiteDamage >= cur) {
            // EXÉCUTION : on garantit la mort attribuée au lanceur (vol de Dragon/Baron)
            target.damage(cur + 100, caster);
            caster.sendActionBar(Component.text("⚔ Châtiment — EXÉCUTÉ!", NamedTextColor.GOLD));
        } else {
            target.damage(smiteDamage, caster);
            caster.sendActionBar(Component.text("⚔ Châtiment! (" + (int) smiteDamage + ")", NamedTextColor.GOLD));
        }
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0,1,0), 20, 0.4, 0.4, 0.4);
        return true;
    }

    // ── Purification : retire les effets de contrôle ──

    private boolean castCleanse(Player caster) {
        caster.removePotionEffect(PotionEffectType.SLOWNESS);
        caster.removePotionEffect(PotionEffectType.WEAKNESS);
        caster.removePotionEffect(PotionEffectType.BLINDNESS);
        // Réduit aussi la durée des CC futurs (non modélisé en détail)
        caster.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, caster.getLocation().add(0,1,0), 20, 0.5, 0.5, 0.5);
        caster.sendActionBar(Component.text("✨ Purification!", NamedTextColor.AQUA));
        return true;
    }

    // ── Fantôme : boost de vitesse prolongé ──

    private boolean castGhost(Player caster) {
        // Ghost dure ~10s avec boost de vitesse croissant
        caster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1, false, true));
        caster.getWorld().spawnParticle(Particle.CLOUD, caster.getLocation(), 15, 0.5, 0.2, 0.5);
        caster.sendActionBar(Component.text("👻 Fantôme! Vitesse accrue (10s)", NamedTextColor.WHITE));
        return true;
    }

    public void cleanup(UUID uuid) {
        cooldowns.entrySet().removeIf(e -> e.getKey().startsWith(uuid.toString()));
    }
}
