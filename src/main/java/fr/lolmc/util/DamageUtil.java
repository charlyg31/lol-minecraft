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
        // Intouchable (Frappe Alpha, Marque de Mort) : aucun dégât
        if (Untargetable.is(victim)) return;
        // Cavalier du Héraut en charge : invulnérable
        if (fr.lolmc.game.HeraldRodeo.isRiding(victim.getUniqueId())) return;

        BaseChampion vc = cm.getChampion(victim);
        ChampionStats vs = vc.getStats();
        ChampionStats as = (attacker != null && cm.hasChampion(attacker))
                ? cm.getChampion(attacker).getStats() : null;

        boolean itemDmg = fr.lolmc.item.ItemEffects.inItemDamage();
        // Boucliers anti-sorts : bloquent les sorts, pas les dégâts d'objets (Lame enchantée, brûlures…)
        if (isAbility && attacker != null && !itemDmg) {
            if (vs.consumeSpellShield()) {
                vc.onSpellShieldBlock(victim);
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

        // Objets : Tourment de Liandry, Dominik, Flamme-ombre, Mandat impérial, Shojin, Actualisateur…
        if (attacker != null) rawAmount *= fr.lolmc.item.ItemEffects.outgoingMultiplier(attacker, victim, type, isAbility);

        // Runes : Coup de grâce, Abattage, Baroud d'honneur, Attaque soutenue, Premier coup
        // (avant : seul Abattage était appliqué, getDamageMultiplier n'était jamais appelé)
        if (as != null) {
            var rm = LolPlugin.getInstance().getRuneManager();
            if (rm != null) rawAmount *= rm.getDamageMultiplier(attacker, victim, !itemDmg);
        }

        if (attacker != null && !itemDmg) fr.lolmc.item.ItemEffects.notePreMitigation(attacker, rawAmount); // Échos d'Helia
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
            if (type == Type.PHYSICAL) finalDmg = vs.reducePhysical(finalDmg); // Caprice d'Amumu
            // Malédiction d'Amumu : +10 % des dégâts magiques en dégâts bruts, quelle que soit la source
            // (avant : seulement pour les sorts d'Amumu, en un second coup)
            if (type == Type.MAGICAL && fr.lolmc.champion.impl.jungle.Amumu.isCursed(victim.getUniqueId()))
                finalDmg += finalDmg * 0.10;
        }

        // 2. Boucliers (les vrais dégâts passent à travers ? Non, les boucliers absorbent tout en LoL)
        // Épuisement : l'attaquant épuisé inflige 35 % de dégâts en moins
        if (attacker != null) {
            var ssmEx = LolPlugin.getInstance().getSummonerSpellManager();
            if (ssmEx != null) finalDmg *= ssmEx.outgoingDamageMultiplier(attacker);
            finalDmg *= fr.lolmc.game.JungleBuffs.outgoingMultiplier(attacker); // âme Chimtech
        }
        finalDmg *= fr.lolmc.game.JungleBuffs.incomingMultiplier(victim); // âme Chimtech
        // Objets de la victime : Masque abyssal, Mailles du gardien, Randuin, Opposition céleste, Vœu du chevalier
        finalDmg = fr.lolmc.item.ItemEffects.incoming(victim, attacker, finalDmg, type, !isAbility,
                fr.lolmc.item.ItemEffects.currentAttackCrit);
        // Runes : Plaque d'os (dégâts réduits) et Optimisation glaciale (ennemi refroidi contre vos alliés)
        var rmIn = LolPlugin.getInstance().getRuneManager();
        if (rmIn != null) finalDmg = rmIn.modifyIncoming(victim, attacker, finalDmg);
        // Lien vital : le bouclier arrive AVANT le coup qui ferait passer sous 30 % des PV
        fr.lolmc.item.ItemEffects.beforeHealthLoss(victim, finalDmg, type);
        double afterShield = vs.absorbWithShield(finalDmg, type == Type.MAGICAL);
        // Danse de la mort : une partie des dégâts est différée
        afterShield = fr.lolmc.item.ItemEffects.deferDamage(victim, afterShield);

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
        // Passif Amumu : Toucher Maudit, appliqué plus haut aux dégâts magiques
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
        if (runeMgr != null) runeMgr.onDamageTaken(victim, attacker, finalDmg);
        // Effets de runes (keystones) si l'attaquant est un joueur
        if (attacker != null) {
            var rm = LolPlugin.getInstance().getRuneManager();
            if (rm != null) {
                // Les dégâts d'objets ne comptent pas comme des coups supplémentaires pour les runes
                if (!itemDmg) rm.onDamageToChampion(attacker, victim, isAbility);
                rm.onConquerorHeal(attacker, afterShield);
                if (!itemDmg) rm.onHitEffects(attacker, victim, afterShield, isAbility);
            }
            fr.lolmc.game.JungleBuffs.onDamageDealt(attacker, victim); // âmes Infernale, Océanique, Hextech
        }

        // 4. Vol de vie / omnivamp pour l'attaquant
        if (attacker != null && cm.hasChampion(attacker)) {
            BaseChampion ac = cm.getChampion(attacker);
            double vamp = isAbility
                ? ac.getStats().getFinalOmnivamp()
                : ac.getStats().getFinalLifeSteal() + ac.getStats().getFinalOmnivamp();
            if (vamp > 0) {
                if (!isAbility) fr.lolmc.item.ItemEffects.onLifesteal(attacker, afterShield * vamp); // Soif-de-sang
                ac.getHPSystem().heal(afterShield * vamp);
            }
        }

        // 5. Effets d'objets (attaquant et victime), puis runes
        fr.lolmc.item.ItemEffects.afterDamage(attacker, victim, afterShield, type, isAbility);
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) {
            pm.onDamageTaken(victim);
            if (isAbility && !itemDmg && attacker != null && cm.hasChampion(attacker)) {
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
            // Ange gardien : renaissance au lieu de la mort
            if (!fr.lolmc.item.ItemEffects.tryRevive(victim)) hud.triggerDeath(victim, attacker);
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
        // Données du jeu : brûlure de 3 s, réappliquée au plus toutes les 2 s par cible ;
        // 25 → 75 dégâts par seconde de 25 à 45 min de jeu (progression continue)
        if (until == null || now >= until) {
            elderBurnUntil.put(vid, now + 2000L);
            double mins = fr.lolmc.instance.InstanceHelper.gameManager(attacker).getElapsedSeconds() / 60.0;
            double total = 3 * (25 + 50 * Math.max(0, Math.min(1, (mins - 25) / 20.0)));
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
