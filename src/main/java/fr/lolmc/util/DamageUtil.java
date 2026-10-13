package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.stats.ChampionStats;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Routeur central de dégâts — chaîne complète LoL.
 * Ordre d'application (après que le sort a déjà calculé la réduction armure/MR) :
 *   1. Réductions défensives de la cible (plate + %, AA si auto-attaque)
 *   2. Absorption par les boucliers (anti-magie en priorité si dégât magique)
 *   3. Dégâts restants → HP
 *   4. Vol de vie / omnivamp de l'attaquant
 *   5. Passifs défensifs (Sterak's, Guardian Angel) + DoT (Liandry's)
 */
public class DamageUtil {

    public enum Type { PHYSICAL, MAGICAL, TRUE }

    // ══════════════════════════════════════════════════════════════
    // SURCHARGES UNIVERSELLES (acceptent sbires, monstres, joueurs)
    // ══════════════════════════════════════════════════════════════

    /** Dégâts sur n'importe quelle entité vivante (joueur, sbire, monstre). */
    public static void damageEntity(Player attacker, org.bukkit.entity.LivingEntity victim,
                                     double rawAmount, boolean isAbility, Type type) {
        if (victim == null || victim.isDead()) return;
        if (victim instanceof Player p) {
            damage(attacker, p, rawAmount, isAbility, type);
        } else {
            // Sbire ou monstre : résistances + PV virtuels (PAS de victim.damage() -> évite StackOverflow)
            fr.lolmc.util.VisualEffectUtil.impact(victim.getWorld(),
                    victim.getLocation().add(0, 1, 0), Material.WHITE_STAINED_GLASS, 0.26f, 4L);
            // Mort attribuée à l'attaquant (or/XP gérés par EntityDeathListener)
            MobCombat.damage(attacker, victim, rawAmount, type);
        }
    }

    public static void abilityDamageEntity(Player a, org.bukkit.entity.LivingEntity v, double amount) {
        damageEntity(a, v, amount, true, Type.PHYSICAL);
    }
    public static void abilityDamageMagicEntity(Player a, org.bukkit.entity.LivingEntity v, double amount) {
        damageEntity(a, v, amount, true, Type.MAGICAL);
    }
    public static void trueDamageEntity(Player a, org.bukkit.entity.LivingEntity v, double amount) {
        damageEntity(a, v, amount, true, Type.TRUE);
    }

    public static void damage(Player attacker, Player victim, double rawAmount, boolean isAbility, Type type) {
        if (victim == null) return; // garde : victim ne devrait jamais être null, mais évite un NPE en cascade
        // Aggro sbires + tour : mécanique de laning LoL
        if (attacker != null) {
            var aggro = fr.lolmc.LolPlugin.getInstance().getAggroManager();
            if (aggro != null) aggro.onChampionAttack(attacker, victim);
            // Interrompre la canalisation de TP de la victime
            var ssm = LolPlugin.getInstance().getSummonerSpellManager();
            if (ssm != null && ssm.isChanneling(victim)) ssm.interruptTeleport(victim);
        }
        ChampionManager cm = LolPlugin.getInstance().getChampionManager();
        if (cm == null || !cm.hasChampion(victim)) {
            victim.damage(rawAmount / 25.0); // fallback non-champion
            return;
        }

        // Mort (spectateur en attente de respawn) : aucun dégât
        if (victim.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;

        // Zhonya : stase, aucun dégât (setInvulnerable ne protège que des dégâts vanilla)
        var pmZhonya = LolPlugin.getInstance().getPassiveManager();
        if (pmZhonya != null && pmZhonya.getState(victim).zhonyasActive) return;

        BaseChampion vc = cm.getChampion(victim);
        ChampionStats vs = vc.getStats();
        ChampionStats as = (attacker != null && cm.hasChampion(attacker))
                ? cm.getChampion(attacker).getStats() : null;

        // ── Anathema's Chains: le premier ennemi qui frappe le porteur devient son Némésis (-20% de ses dégâts) ──
        var pmAnathema = LolPlugin.getInstance().getPassiveManager();
        if (attacker != null && pmAnathema != null && pmAnathema.hasAnyItem(victim,"anathema_chains")) {
            var vState = pmAnathema.getState(victim);
            if (vState.anathemaNemesis == null) vState.anathemaNemesis = attacker.getUniqueId();
        }

        // ── Imperial Mandate: un allié (autre que le porteur) qui frappe une cible marquée détonne Coordinated Fire ──
        if (attacker != null && pmAnathema != null) {
            var teamMgr = LolPlugin.getInstance().getTeamManager();
            if (teamMgr != null && !teamMgr.areAllies(attacker, victim) && !pmAnathema.hasAnyItem(attacker,"imperial_mandate")) {
                var victimState2 = pmAnathema.getState(victim);
                if (victimState2.coordinatedFireMarkExpire > System.currentTimeMillis()) {
                    victimState2.coordinatedFireMarkExpire = 0; // marque consommée
                    ChampionStats attackerStats = cm.hasChampion(attacker) ? cm.getChampion(attacker).getStats() : null;
                    if (attackerStats != null) {
                        double markDmg = 90.0 + attackerStats.getFinalAP() * 0.30;
                        vc.getHPSystem().takeDamage(markDmg); // dégât brut, comme le vrai objet (pas repassé par les résistances)
                        attacker.sendActionBar(net.kyori.adventure.text.Component.text(
                                "⚡ Coordinated Fire !", net.kyori.adventure.text.format.NamedTextColor.GOLD));
                    }
                }
            }
        }
        if (isAbility && attacker != null) {
            if (vs.consumeSpellShield()) {
                victim.sendActionBar(net.kyori.adventure.text.Component.text(
                    "Bouclier anti-sort - sort bloque!", net.kyori.adventure.text.format.NamedTextColor.AQUA));
                attacker.sendActionBar(net.kyori.adventure.text.Component.text(
                    "Sort bloque par un bouclier!", net.kyori.adventure.text.format.NamedTextColor.GRAY));
                return;
            }
            if (vs.consumeEonShield()) {
                victim.sendActionBar(net.kyori.adventure.text.Component.text(
                    "Annul - sort bloque!", net.kyori.adventure.text.format.NamedTextColor.AQUA));
                attacker.sendActionBar(net.kyori.adventure.text.Component.text(
                    "Sort bloque par un bouclier!", net.kyori.adventure.text.format.NamedTextColor.GRAY));
                return;
            }
            if (vs.consumeBansheeShield()) {
                victim.sendActionBar(net.kyori.adventure.text.Component.text(
                    "Bouclier de Banshee - sort bloque!", net.kyori.adventure.text.format.NamedTextColor.AQUA));
                attacker.sendActionBar(net.kyori.adventure.text.Component.text(
                    "Sort bloque par un bouclier!", net.kyori.adventure.text.format.NamedTextColor.GRAY));
                return;
            }
        }

        // Zeke's Convergence : +30% dégâts si l'attaquant a le buff (allié proche qui a posé un CC)
        if (as != null) {
            rawAmount *= as.getZekeDamageMultiplier();
        }

        // Rune Cut Down : +8% dégâts (hors vrais dégâts) contre une cible au-dessus de 60% de son HP max
        if (as != null && type != Type.TRUE) {
            var rm = LolPlugin.getInstance().getRuneManager();
            if (rm != null) {
                var atkPage = rm.getPage(attacker.getUniqueId());
                if (atkPage != null && atkPage.has("cut_down") && vc.getHPSystem().getHPRatio() > 0.60) {
                    rawAmount *= 1.08;
                }
            }
        }

        // 1. Résistance (armure/MR avec pénétration de l'attaquant)
        double afterResist;
        if (type == Type.TRUE) {
            afterResist = rawAmount;
        } else if (as == null) {
            // Sbire, monstre, tourelle… : la résistance de la victime s'applique aussi (LoL)
            afterResist = rawAmount * MobCombat.resistMultiplier(
                    type == Type.MAGICAL ? vs.getFinalMagicResist() : vs.getFinalArmor());
        } else if (type == Type.MAGICAL) {
            afterResist = as.calcMagicalDamage(rawAmount, vs);
        } else {
            afterResist = as.calcPhysicalDamage(rawAmount, vs);
        }

        // Effets de potion des sorts : Force (attaquant) et Résistance (victime)
        if (attacker != null) afterResist *= PotionCombat.damageDealtMultiplier(attacker);
        afterResist *= PotionCombat.damageTakenMultiplier(victim);

        // 2. Réductions défensives plates/% de la cible
        double finalDmg = afterResist;
        if (type != Type.TRUE) {
            finalDmg = vs.applyDamageReductions(afterResist, !isAbility);
            // Anathema's Chains : le PORTEUR (victim) prend -20% des dégâts venant de son Némésis désigné
            if (attacker != null) {
                var victimState = pmAnathema != null ? pmAnathema.getState(victim) : null;
                if (victimState != null && attacker.getUniqueId().equals(victimState.anathemaNemesis)) {
                    finalDmg *= 0.80;
                }
            }
        }

        // 2. Boucliers (les vrais dégâts passent à travers ? Non, les boucliers absorbent tout en LoL)
        // Épuisement : l'attaquant épuisé inflige 35 % de dégâts en moins
        if (attacker != null) {
            var ssmEx = LolPlugin.getInstance().getSummonerSpellManager();
            if (ssmEx != null) finalDmg *= ssmEx.outgoingDamageMultiplier(attacker);
        }
        double afterShield = vs.absorbWithShield(finalDmg, type == Type.MAGICAL);

        // 3. HP
        vc.getHPSystem().takeDamage(afterShield);
        // Enregistrer la contribution pour les assists
        if (attacker != null) {
            var rw = fr.lolmc.instance.InstanceHelper.rewardManager(attacker);
            if (rw != null) rw.recordDamage(attacker.getUniqueId(), victim.getUniqueId());
        }
        // Tracker dégâts infligés/subis dans le scoreboard de partie
        var msb = LolPlugin.getInstance().getMatchScoreboard();
        if (msb != null && afterShield > 0) {
            if (attacker != null) msb.addDamageDealt(attacker, afterShield);
            msb.addDamageTaken(victim, afterShield);
        }
        // Death recap : enregistrer la source
        var drm = LolPlugin.getInstance().getDeathRecapManager();
        if (drm != null && afterShield > 0 && attacker != null) {
            String src = attacker.getName();
            String typ = type == fr.lolmc.util.DamageUtil.Type.MAGICAL ? "magic"
                       : type == fr.lolmc.util.DamageUtil.Type.TRUE ? "true" : "physical";
            drm.record(victim.getUniqueId(), src, typ, afterShield);
        }
        // Passif Amumu : désormais géré via Toucher Maudit (applyCurse/onMagicHit)
        // dans Amumu.java — plus de renvoi de dégâts ici.
        // Dragon Elder : exécution si la cible tombe sous 20% HP
        if (attacker != null) {
            // Buff du Dragon Ancestral (avant : potion Force, donnée aussi par Ashe Q, Yi E, Janna E)
            if (fr.lolmc.game.JungleManager.hasElderBuff(attacker) && type != Type.TRUE)
                elderEffects(attacker, victim);
        }
        // Attaquer depuis un bush révèle l'attaquant 2 s (LoL) ; être touché ne révèle pas
        var bushMgr = LolPlugin.getInstance().getBushManager();
        if (bushMgr != null && attacker != null) bushMgr.revealOnAttack(attacker);
        var baseMgr = fr.lolmc.instance.InstanceHelper.baseManager(victim);
        if (baseMgr != null) baseMgr.onDamage(victim);
        var runeMgr = LolPlugin.getInstance().getRuneManager();
        if (runeMgr != null) runeMgr.onDamageTaken(victim, afterShield, type == Type.MAGICAL);
        // Effets de runes (keystones) si l'attaquant est un joueur
        if (attacker != null) {
            var rm = LolPlugin.getInstance().getRuneManager();
            if (rm != null) {
                rm.onDamageToChampion(attacker, victim, isAbility);
                rm.onConquerorHeal(attacker, afterShield);
                rm.onHitEffects(attacker, victim, afterShield, isAbility);
            }
        }

        // 4. Vol de vie / omnivamp pour l'attaquant
        if (attacker != null && cm.hasChampion(attacker)) {
            BaseChampion ac = cm.getChampion(attacker);
            double vamp = isAbility
                ? ac.getStats().getFinalOmnivamp()
                : ac.getStats().getFinalLifeSteal() + ac.getStats().getFinalOmnivamp();
            if (vamp > 0) ac.getHPSystem().heal(afterShield * vamp);
        }

        // 5. Passifs défensifs + DoT
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) {
            pm.onDamageTaken(victim);
            if (isAbility && attacker != null && cm.hasChampion(attacker)) {
                pm.onAbilityDamage(attacker, victim, afterShield, type == Type.MAGICAL);
            }
        }

        // 6. Affichage
        var hud = LolPlugin.getInstance().getHUDManager();
        if (hud != null) {
            hud.updateHUD(victim, vc);
            if (attacker != null && cm.hasChampion(attacker))
                hud.updateHUD(attacker, cm.getChampion(attacker));
        }

        // 7. Vérifier la mort
        if (vc.getHPSystem().isDead() && hud != null) {
            hud.triggerDeath(victim, attacker);
        }
    }

    // ── Aspect du Dragon (Dragon Ancien, wiki LoL) ──
    private static final java.util.Map<java.util.UUID, Long> elderBurnUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<java.util.UUID> elderExecutePending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Brûlure : 75 vrais dégâts sur 3 s jusqu'à 25:00, +15 toutes les 2 min (225 à 45:00).
     * Exécution : une cible sous 20 % des PV max meurt 0,5 s plus tard (avant : immédiat et
     * seulement +10 % des PV max).
     */
    private static void elderEffects(Player attacker, Player victim) {
        var cm = LolPlugin.getInstance().getChampionManager();
        var plugin = LolPlugin.getInstance();
        java.util.UUID vid = victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = elderBurnUntil.get(vid);
        if (until == null || now >= until) {
            elderBurnUntil.put(vid, now + 3000L);
            long mins = fr.lolmc.instance.InstanceHelper.gameManager(attacker).getElapsedSeconds() / 60;
            double total = Math.min(225, 75 + 15 * Math.max(0, (mins - 25) / 2));
            for (int i = 0; i < 3; i++) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (victim.isOnline() && cm.hasChampion(victim)
                            && victim.getGameMode() != org.bukkit.GameMode.SPECTATOR)
                        damage(attacker.isOnline() ? attacker : null, victim, total / 3, true, Type.TRUE);
                }, 5L + i * 20L);
            }
        }
        if (cm.getChampion(victim).getHPSystem().getHPRatio() < 0.20 && elderExecutePending.add(vid)) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                elderExecutePending.remove(vid);
                if (!victim.isOnline() || !cm.hasChampion(victim)
                        || victim.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
                var hp = cm.getChampion(victim).getHPSystem();
                if (hp.getHPRatio() < 0.20)
                    damage(attacker.isOnline() ? attacker : null, victim, hp.getMaxHP(), true, Type.TRUE);
            }, 10L);
        }
    }

    // ── Raccourcis (compat avec l'ancien code) ──

    /** Dégâts déjà calculés, type physique par défaut (compat ancienne signature). */
    public static void damage(Player attacker, Player victim, double amount, boolean isAbility) {
        damage(attacker, victim, amount, isAbility, Type.PHYSICAL);
    }

    public static void trueDamage(Player attacker, Player victim, double amount) {
        damage(attacker, victim, amount, true, Type.TRUE);
    }
}
