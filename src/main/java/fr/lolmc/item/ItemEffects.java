package fr.lolmc.item;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.game.AutoAttackManager;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.MobCombat;
import fr.lolmc.util.TargetingUtil;
import fr.lolmc.util.VisualEffectUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Effets des objets (passifs), alignés sur les données du jeu (CommunityDragon, patch 26.20).
 *
 * <p>Points d'entrée :
 * <ul>
 *   <li>{@link #onAttackHit} : effets à l'impact des attaques de base, sur champions, sbires et monstres ;</li>
 *   <li>{@link #afterDamage} : effets liés aux dégâts infligés et subis (sorts, attaques, objets) ;</li>
 *   <li>{@link #outgoingMultiplier}, {@link #incoming}, {@link #beforeHealthLoss}, {@link #deferDamage}
 *       et {@link #tryRevive} : modificateurs appliqués dans la chaîne de dégâts ;</li>
 *   <li>{@link #onCast}, {@link #onCrowdControl}, {@link #onAllyHealShield}, {@link #onChampionDeath},
 *       {@link #onMinionKill} : événements de jeu.</li>
 * </ul>
 * Les effets périodiques et les statistiques dynamiques sont dans {@link ItemTicker}, les actifs dans
 * {@link ItemActives}. Avant, ces passifs reprenaient des valeurs d'anciens patchs, ne touchaient que
 * les champions, retiraient les PV sans passer par la chaîne de dégâts, et une douzaine d'entre eux
 * testaient des identifiants d'objets qui n'existaient plus.
 */
public final class ItemEffects {

    private ItemEffects() {}

    // ══════════════════════════════════════════════════════════════
    // OUTILS
    // ══════════════════════════════════════════════════════════════

    static LolPlugin pl() { return LolPlugin.getInstance(); }

    static boolean has(Player p, String... ids) {
        var pm = pl().getPassiveManager();
        return pm != null && p != null && pm.hasAnyItem(p, ids);
    }

    static BaseChampion champ(Player p) {
        var cm = pl().getChampionManager();
        return p != null && cm.hasChampion(p) ? cm.getChampion(p) : null;
    }

    static boolean isChampion(LivingEntity e) {
        return e instanceof Player p && pl().getChampionManager().hasChampion(p)
                && p.getGameMode() != org.bukkit.GameMode.SPECTATOR;
    }

    static boolean ranged(Player p) {
        BaseChampion c = champ(p);
        return c != null && AutoAttackManager.getAAType(c) != AutoAttackManager.AAType.MELEE;
    }

    static int lvl(Player p) {
        BaseChampion c = champ(p);
        return c != null ? c.getLevelSystem().getLevel() : 1;
    }

    /** Valeur par paliers de niveau du jeu : {@code base} au niveau 1, +{@code per} par niveau dès {@code from}. */
    static double bp(double base, int from, double per, int lvl) {
        return base + per * Math.max(0, lvl - from + 1);
    }

    /** Interpolation linéaire du niveau 1 au niveau 18. */
    static double interp(double a, double b, int lvl) {
        return a + (b - a) * Math.max(0, Math.min(17, lvl - 1)) / 17.0;
    }

    static boolean isEpicMonster(LivingEntity e) {
        var t = fr.lolmc.game.JungleManager.getMonsterType(e);
        return t != null && t.isEpic();
    }

    static boolean isMonster(LivingEntity e) {
        return fr.lolmc.game.JungleManager.isJungleMonster(e);
    }

    static double maxHp(LivingEntity e) { return TargetingUtil.getRealMaxHealth(e); }
    static double curHp(LivingEntity e) { return TargetingUtil.getRealHealth(e); }

    static void later(long ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(pl(), r, ticks); }

    /** Mana bonus : mana des objets + mana gagné par Flux de mana. */
    static double bonusMana(Player p) {
        double m = 0;
        for (LolItem it : pl().getShopListener().getOrCreate(p).getEquippedItems()) if (it != null) m += it.getBonusMana();
        for (var e : fx(p).cd.entrySet()) if (e.getKey().startsWith("manaflow:")) m += e.getValue();
        return m;
    }

    /** Actualisateur : bonus de dégâts, soins et boucliers du mana renforcé (15 % + 0.5 % du mana bonus). */
    static double actualizerBonus(Player p) {
        return marked(p.getUniqueId(), "actualizer") ? (15 + bonusMana(p) * 0.005) / 100.0 : 0;
    }

    static boolean ready(Map<String, Long> cds, String key) {
        return cds.getOrDefault(key, 0L) <= System.currentTimeMillis();
    }

    static void cooldown(Map<String, Long> cds, String key, double seconds) {
        cds.put(key, System.currentTimeMillis() + (long) (seconds * 1000));
    }

    /** Bouclier temporaire (retiré à la fin s'il reste). */
    static void tempShield(Player p, double amount, int ticks, boolean magicOnly) {
        BaseChampion c = champ(p);
        if (c == null || amount <= 0) return;
        double amt = amount * (1 + c.getStats().getHealShieldPower());
        if (magicOnly) c.getStats().addMagicShield(amt); else c.getStats().addShield(amt);
        later(ticks, () -> {
            BaseChampion c2 = champ(p);
            if (c2 == null) return;
            if (magicOnly) c2.getStats().addMagicShield(-Math.min(amt, c2.getStats().getMagicShield()));
            else c2.getStats().addShield(-Math.min(amt, c2.getStats().getShield() - c2.getStats().getMagicShield()));
        });
    }

    // ── Dégâts infligés par un objet (sans relancer les effets d'objets ni les boucliers anti-sorts) ──

    private static int itemDepth = 0;

    /** Vrai pendant l'application de dégâts d'objet (pas d'effets d'objets en cascade). */
    public static boolean inItemDamage() { return itemDepth > 0; }

    /**
     * Dégâts d'un objet. {@code onHit} : dégâts à l'impact d'une attaque (comptent comme dégâts
     * d'attaque : vol de vie, réductions des attaques) ; sinon, comme dégâts de sort sans pouvoir
     * être bloqués par un bouclier anti-sorts.
     */
    public static void itemDamage(Player src, LivingEntity t, double amount, DamageUtil.Type type, boolean onHit) {
        if (amount <= 0 || t == null || t.isDead() || !t.isValid()) return;
        itemDepth++;
        try {
            if (t instanceof Player p) DamageUtil.damage(src, p, amount, !onHit, type);
            else MobCombat.damage(src, t, amount, type);
        } finally {
            itemDepth--;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ÉTAT PAR JOUEUR
    // ══════════════════════════════════════════════════════════════

    static final class Fx {
        final Map<String, Long> cd = new HashMap<>();
        // Lame enchantée
        long spellbladeUntil;
        // Combat contre des champions (Tourment, Déguisement hanté, Créateur de failles, Jak'Sho…)
        long champCombatStart, lastChampCombat;
        // Attaques
        int krakenCount, ragebladeStacks, ragebladeCount, hullbreakerCount;
        long ragebladeUntil;
        boolean terminusLight;
        int terminusLightStacks, terminusDarkStacks;
        long terminusLightUntil, terminusDarkUntil;
        double energize; Location lastLoc;
        double yunTalCrit; long yunTalAsUntil;
        long eclipseLastHit; UUID eclipseLastTarget;
        double deadManMomentum;
        int fiendAttacks; long fiendUntil;
        final Map<UUID, int[]> botrkHits = new HashMap<>();
        final Map<UUID, Long> botrkHitTime = new HashMap<>();
        final Map<UUID, Integer> heartsteelTicks = new HashMap<>();
        final java.util.Set<UUID> heartsteelReady = new java.util.HashSet<>();
        final Map<UUID, Long> sunderedCd = new HashMap<>();
        long umbralLastVisible; long umbralReadyUntil;
        // Sorts
        int lastCastSlot; long lastCastTime; int castId;
        final Map<String, Integer> manaflowCharges = new HashMap<>();
        final Map<String, Long> manaflowNextCharge = new HashMap<>();
        final Map<Integer, Long> manaflowCastLock = new HashMap<>();
        int shojinStacks; long shojinUntil; int shojinLastCast = -1;
        int muramanaLastCast = -1, crimsonLastCast = -1, cosmicStacks;
        long cosmicUntil;
        final Map<UUID, double[]> stormsurgeWindow = new HashMap<>();
        // Défense
        long kaenicLastMagic; boolean kaenicShieldUp;
        int fonStacks; long fonLastStack, fonActiveUntil;
        double ddPending; int ddTicksLeft;
        long celestialUntil;
        double doranShieldRegenLeft; long doranRingUntil;
        long immolateUntil;
        // Éliminations
        int glory; int hubrisStatues; long hubrisUntil; long hexopticsUntil; long sabotageUntil;
        int carnage; int cullKills; boolean cullDone;
        // Soins / boucliers sur les alliés
        long ardentUntil, flowingWaterUntil; double heliaCharges;
        UUID knightPartner;
        long fanfareUntil;
        int rodStacks; long rodNext; int lastLevel;
        // Compagnon de jungle
        int companion; double scorchStacks; boolean scorchReady; boolean wasInBush; double mossShield;
        double ichorShield; long ichorAt;
    }

    static final Map<UUID, Fx> FX = new ConcurrentHashMap<>();

    static Fx fx(Player p) { return FX.computeIfAbsent(p.getUniqueId(), _ -> new Fx()); }

    public static void reset(UUID id) { FX.remove(id); DAMAGED_BY.remove(id); LAST_ABILITY_HIT.remove(id); }

    /** Compétences qui immobilisent (étourdissement, enracinement, projection, peur, suppression) : Mandat impérial. */
    static final java.util.Set<String> IMMOBILIZING = java.util.Set.of(
            "q_amumu", "r_amumu", "r_malphite", "q_leona", "e_leona", "r_leona", "q_morgana", "r_morgana",
            "q_blitzcrank", "e_blitzcrank", "q_janna", "r_janna", "r_ashe", "e_veigar", "e_jinx",
            "r_leesin", "e_warwick", "r_warwick", "q_yasuo", "r_yasuo");

    /** Coups portés à une balise par une attaque : Glaive d'ombre (Black-out), 3 (2 à distance), sinon 1. */
    public static int wardHitDamage(Player attacker) {
        if (!has(attacker, "umbral_glaive")) return 1;
        return ranged(attacker) ? 2 : 3;
    }

    /** Dernier sort ayant touché chaque cible : cible → {lanceur, instant}. Sert à attribuer les contrôles. */
    static final Map<UUID, Object[]> LAST_ABILITY_HIT = new ConcurrentHashMap<>();

    /** Contrôle posé sans sort récent : cible → {immobilise, instant}, attribué au prochain sort qui la touche. */
    static final Map<UUID, Object[]> PENDING_CC = new ConcurrentHashMap<>();

    /** Contrôle posé avant les dégâts du sort (ex. Q d'Annie) : en attente d'attribution (0.25 s). */
    public static void pendingCrowdControl(LivingEntity target, boolean immobilize) {
        Object[] prev = PENDING_CC.get(target.getUniqueId());
        boolean imm = immobilize || (prev != null && (Boolean) prev[0]);
        PENDING_CC.put(target.getUniqueId(), new Object[]{imm, System.currentTimeMillis()});
    }

    /** Dégâts reçus des monstres non épiques avec un compagnon de jungle : -50 %. */
    public static double monsterDamageTakenMultiplier(Player victim, boolean epicMonster) {
        return !epicMonster && has(victim, "smite_stalker", "pickaxe_jungle", "blue_smite") ? 0.5 : 1.0;
    }

    /** Lanceur du sort qui vient de toucher {@code target} (moins de 0.25 s), ou null. */
    public static Player recentAbilitySource(LivingEntity target) {
        Object[] e = LAST_ABILITY_HIT.get(target.getUniqueId());
        if (e == null || System.currentTimeMillis() - (Long) e[1] > 250) return null;
        return Bukkit.getPlayer((UUID) e[0]);
    }

    /** Thread serveur : vrai pendant l'application d'une attaque de base critique (Présage de Randuin). */
    public static boolean currentAttackCrit = false;

    public static void resetAll() { FX.clear(); DAMAGED_BY.clear(); ARMOR_SHRED.clear(); BURNS.clear(); MARKS.clear(); }

    /** Dernier champion de chaque attaquant : victime → (attaquant → instant). Fenêtre des éliminations. */
    static final Map<UUID, Map<UUID, Long>> DAMAGED_BY = new ConcurrentHashMap<>();

    /** Couperet noir : cible → {cumuls, armure retirée, fin}. */
    static final Map<UUID, double[]> ARMOR_SHRED = new ConcurrentHashMap<>();

    /** Brûlures d'objets en cours : clé « cible:objet » → fin. */
    static final Map<String, Long> BURNS = new ConcurrentHashMap<>();

    /** Marques sur une cible (Mandat impérial, Concentration lointaine, Bulles de Rêve éveillé…). */
    static final Map<String, Long> MARKS = new ConcurrentHashMap<>();

    static boolean marked(UUID target, String key) { return MARKS.getOrDefault(target + ":" + key, 0L) > System.currentTimeMillis(); }
    static void mark(UUID target, String key, double seconds) { MARKS.put(target + ":" + key, System.currentTimeMillis() + (long) (seconds * 1000)); }

    static void enterChampionCombat(Player p) {
        Fx f = fx(p);
        long now = System.currentTimeMillis();
        if (now - f.lastChampCombat > 3000) f.champCombatStart = now;
        f.lastChampCombat = now;
    }

    /** Secondes passées en combat contre des champions (0 hors combat). */
    static double champCombatSeconds(Player p) {
        Fx f = fx(p);
        long now = System.currentTimeMillis();
        return now - f.lastChampCombat > 3000 ? 0 : (now - f.champCombatStart) / 1000.0;
    }

    // ══════════════════════════════════════════════════════════════
    // MODIFICATEURS DE DÉGÂTS
    // ══════════════════════════════════════════════════════════════

    /** Multiplicateur des dégâts infligés par {@code attacker} à {@code victim} (avant résistances). */
    public static double outgoingMultiplier(Player attacker, LivingEntity victim, DamageUtil.Type type, boolean isAbility) {
        if (attacker == null || victim == null || champ(attacker) == null) return 1.0;
        double m = 1.0;
        boolean champVictim = isChampion(victim);
        var st = champ(attacker).getStats();
        if (champVictim) {
            double secs = champCombatSeconds(attacker);
            // Tourment de Liandry / Déguisement hanté : +2 % par seconde de combat, max 6 %
            if (has(attacker, "liandry_anguish", "haunting_guise")) m *= 1 + Math.min(0.06, 0.02 * Math.floor(secs));
            // Créateur de failles : +2 % par seconde, max 8 %
            if (has(attacker, "riftmaker")) m *= 1 + Math.min(0.08, 0.02 * Math.floor(secs));
            // Salutations de Dominik : jusqu'à +15 % selon les PV bonus de la cible (max à 1500)
            if (has(attacker, "lord_dominiks") && victim instanceof Player vp && champ(vp) != null)
                m *= 1 + 0.15 * Math.min(1, champ(vp).getStats().getBonusHP() / 1500.0);
            // Mandat impérial : cible immobilisée marquée, +7 % de dégâts subis
            if (marked(victim.getUniqueId(), "mandate")) m *= 1.07;
            // Concentration lointaine : cible touchée de loin, +10 % de dégâts du porteur
            if (marked(victim.getUniqueId(), "horizon:" + attacker.getUniqueId())) m *= 1.10;
            // Mélodie du sang : la cible frappée par la Lame enchantée subit +8 % (5 %) de dégâts 4 s
            if (marked(victim.getUniqueId(), "bloodsong:m")) m *= 1.08;
            else if (marked(victim.getUniqueId(), "bloodsong:r")) m *= 1.05;
        }
        // Actualisateur : mana renforcé
        m *= 1 + actualizerBonus(attacker);
        // Compagnon de jungle : +10 % de dégâts (hors bruts) contre les monstres non épiques
        if (type != DamageUtil.Type.TRUE && isMonster(victim) && !isEpicMonster(victim)
                && has(attacker, "smite_stalker", "pickaxe_jungle", "blue_smite")) m *= 1.10;
        // Voie immortelle : +4 % de dégâts au-dessus de 50 % des PV
        if (has(attacker, "immortal_path") && champ(attacker).getHPSystem().getHPRatio() > 0.5) m *= 1.04;
        // Flamme-ombre : dégâts magiques et bruts +20 % sous 40 % des PV de la cible
        if (has(attacker, "shadowflame") && type != DamageUtil.Type.PHYSICAL
                && curHp(victim) < maxHp(victim) * 0.40) m *= 1.20;
        // Lance de Shojin : +3 % (1.5 % à distance) de dégâts de compétence par cumul
        if (isAbility && !inItemDamage() && has(attacker, "spear_of_shojin")) {
            Fx f = fx(attacker);
            if (f.shojinUntil > System.currentTimeMillis())
                m *= 1 + f.shojinStacks * (ranged(attacker) ? 0.015 : 0.03);
        }
        return m;
    }

    /** Masque abyssal : la victime est-elle près d'un porteur ennemi ? (+12 % de dégâts magiques) */
    private static boolean nearAbyssal(Player victim) {
        var tm = pl().getTeamManager();
        for (Player p : victim.getWorld().getPlayers())
            if (tm.areEnemies(p, victim) && p.getLocation().distanceSquared(victim.getLocation()) <= 7 * 7
                    && has(p, "abyssal_mask") && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) return true;
        return false;
    }

    /**
     * Dégâts subis par un champion après réductions : Masque abyssal, Mailles du gardien, Bénédiction
     * de la montagne, Présage de Randuin (critiques), Vœu du chevalier (redirection).
     */
    public static double incoming(Player victim, Player attacker, double dmg, DamageUtil.Type type, boolean isAttack, boolean crit) {
        BaseChampion vc = champ(victim);
        if (vc == null || dmg <= 0) return dmg;
        if (type == DamageUtil.Type.MAGICAL && nearAbyssal(victim)) dmg *= 1.12;
        if (isAttack && !inItemDamage()) {
            // Mailles du gardien : bloque 15 + 0.5 % des PV max (max 20 % du coup)
            if (has(victim, "warden_mail")) dmg -= Math.min(dmg * 0.20, 15 + vc.getHPSystem().getMaxHP() * 0.005);
            // Présage de Randuin : les critiques infligent 30 % de dégâts en moins
            if (crit && has(victim, "randuin_s_omen")) dmg *= 0.70;
        }
        // Opposition céleste : -35 % (25 % à distance) des dégâts de champions pendant 2 s
        if (attacker != null && fx(victim).celestialUntil > System.currentTimeMillis())
            dmg *= 1 - (ranged(victim) ? 0.25 : 0.35);
        // Rêve éveillé : la prochaine source de dégâts est réduite (bulle reçue d'un allié)
        if (attacker != null && MARKS.remove(victim.getUniqueId() + ":dreamDR") != null)
            dmg = Math.max(0, dmg - bp(50, 7, 12, lvl(victim)));
        // Vœu du chevalier : 14 % des dégâts subis par l'allié digne vont au porteur proche
        if (attacker != null) {
            for (Player knight : victim.getWorld().getPlayers()) {
                if (knight.equals(victim) || !has(knight, "knight_s_vow")) continue;
                if (!victim.getUniqueId().equals(fx(knight).knightPartner)) continue;
                if (knight.getLocation().distanceSquared(victim.getLocation()) > 12.5 * 12.5) continue;
                if (champ(knight) == null || champ(knight).getHPSystem().getHPRatio() < 0.30) continue;
                double redirected = dmg * 0.14;
                dmg -= redirected;
                itemDamage(null, knight, redirected, DamageUtil.Type.TRUE, false);
                break;
            }
        }
        return Math.max(0, dmg);
    }

    /**
     * Lien vital (Gage de Sterak, Arc-bouclier, Gueule de Malmortius, Hexterminateur, Étreinte du
     * séraphin, Harnais protoplasmique) : si les dégâts vont faire passer sous 30 % des PV, le bouclier
     * est accordé AVANT le coup (données du jeu).
     */
    public static void beforeHealthLoss(Player victim, double dmg, DamageUtil.Type type) {
        BaseChampion vc = champ(victim);
        if (vc == null || dmg <= 0) return;
        var hp = vc.getHPSystem();
        var st = vc.getStats();
        double after = hp.getCurrentHP() + st.getShield() - dmg;
        if (after >= hp.getMaxHP() * 0.30) return;
        Fx f = fx(victim);
        int l = lvl(victim);
        boolean rng = ranged(victim);
        if (has(victim, "steraks_gage") && ready(f.cd, "sterak")) {
            cooldown(f.cd, "sterak", 90);
            double shield = st.getBonusHP() * 0.60;
            if (rng) shield *= 0.6;
            // Se désagrège sur 4.5 s : retiré par quarts
            st.addShield(shield);
            for (int i = 1; i <= 4; i++) {
                final double part = shield / 4;
                later((long) (i * 22.5), () -> { var c = champ(victim); if (c != null) c.getStats().addShield(-Math.min(part, c.getStats().getShield())); });
            }
            victim.sendActionBar(Component.text("🛡 Gage de Sterak : +" + (int) shield, NamedTextColor.GREEN));
        } else if (has(victim, "immortal_shieldbow") && ready(f.cd, "shieldbow")) {
            cooldown(f.cd, "shieldbow", 90);
            double shield = bp(400, 9, 30, l) * (rng ? 0.8 : 1.0);
            tempShield(victim, shield, 60, false);
            victim.sendActionBar(Component.text("🏹 Lien vital : +" + (int) shield, NamedTextColor.GREEN));
        } else if (has(victim, "seraphs_embrace") && ready(f.cd, "seraph")) {
            cooldown(f.cd, "seraph", 90);
            double shield = vc.getResourceSystem().getCurrent() * 0.18;
            tempShield(victim, shield, 60, false);
            victim.sendActionBar(Component.text("✨ Étreinte du séraphin : +" + (int) shield, NamedTextColor.AQUA));
        } else if (has(victim, "protoplasm_harness") && ready(f.cd, "protoplasm")) {
            cooldown(f.cd, "protoplasm", 90);
            double maxGain = interp(100, 300, l);
            double regen = interp(100, 400, l) + st.getBonusArmor() * 1.75 + st.getBonusMagicResistValue() * 1.75;
            hp.addBonusHP(maxGain);
            later(100, () -> { var c = champ(victim); if (c != null) c.getHPSystem().addBonusHP(-maxGain); });
            for (int i = 1; i <= 5; i++) later(i * 20L, () -> { var c = champ(victim); if (c != null) c.getHPSystem().heal(regen / 5); });
            pl().getCCManager().speedPct(victim, 0.10, 100);
            mark(victim.getUniqueId(), "protoplasm", 5); // taille +15 %
            st.addTenacity(0.25);
            later(100, () -> st.addTenacity(-0.25));
            victim.sendActionBar(Component.text("🧬 Harnais protoplasmique !", NamedTextColor.GREEN));
        }
        if (type == DamageUtil.Type.MAGICAL) {
            if (has(victim, "maw") && ready(f.cd, "maw")) {
                cooldown(f.cd, "maw", 90);
                double shield = (200 + st.getBonusAD() * 1.5) * (rng ? 0.75 : 1.0);
                tempShield(victim, shield, 60, true);
                st.addBonusOmnivamp(0.10);
                later(100, () -> st.addBonusOmnivamp(-0.10));
                victim.sendActionBar(Component.text("⚔ Gueule de Malmortius : +" + (int) shield, NamedTextColor.RED));
            } else if (has(victim, "hexdrinker") && ready(f.cd, "hexdrinker")) {
                cooldown(f.cd, "hexdrinker", 90);
                double shield = interp(110, 280, l) * (rng ? 0.75 : 1.0);
                tempShield(victim, shield, 50, true);
            }
        }
    }

    /**
     * Danse de la mort : 30 % (10 % à distance) des dégâts subis sont différés et infligés en
     * dégâts bruts sur 3 s. Renvoie les dégâts subis immédiatement.
     */
    public static double deferDamage(Player victim, double dmg) {
        if (dmg <= 0 || !has(victim, "death_s_dance") || inItemDamage()) return dmg;
        double part = dmg * (ranged(victim) ? 0.10 : 0.30);
        Fx f = fx(victim);
        f.ddPending += part;
        f.ddTicksLeft = 6; // 6 × 0.5 s
        return dmg - part;
    }

    /** Ange gardien : un coup fatal laisse 1 PV, stase 4 s, puis 50 % des PV de base et 100 % du mana. */
    public static boolean tryRevive(Player victim) {
        BaseChampion vc = champ(victim);
        if (vc == null || !has(victim, "guardian_angel")) return false;
        Fx f = fx(victim);
        if (!ready(f.cd, "ga")) return false;
        cooldown(f.cd, "ga", 300);
        var hp = vc.getHPSystem();
        hp.setCurrentHP(1);
        f.ddPending = 0;
        fr.lolmc.util.Untargetable.apply(victim, 80);
        pl().getCCManager().stun(victim, 80);
        victim.showTitle(net.kyori.adventure.title.Title.title(Component.text("☠"),
                Component.text("Ange gardien — renaissance dans 4 s"),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(250),
                        java.time.Duration.ofMillis(3500), java.time.Duration.ofMillis(250))));
        later(80, () -> {
            var c = champ(victim);
            if (c == null || !victim.isOnline()) return;
            // 50 % des PV de base : PV max sans les PV bonus
            double baseHp = c.getHPSystem().getMaxHP() - c.getStats().getBonusHP();
            c.getHPSystem().setCurrentHP(Math.max(1, baseHp * 0.50));
            if (c.getResourceSystem().getType() == fr.lolmc.stats.ResourceSystem.ResourceType.MANA)
                c.getResourceSystem().fill();
            victim.sendActionBar(Component.text("✅ Renaissance !", NamedTextColor.GOLD));
        });
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    // ATTAQUES DE BASE
    // ══════════════════════════════════════════════════════════════

    /**
     * Modifie les dégâts d'une attaque avant son départ : Ciel éventré et Chasseur de monstres
     * (critiques forcés à 80 %), Lunettes Hextech C44 (distance), cumuls de Naufrageur.
     * {@code crit} peut être modifié (tableau d'un élément).
     */
    public static double modifyAttack(Player a, LivingEntity t, double dmg, boolean[] crit) {
        BaseChampion ac = champ(a);
        if (ac == null) return dmg;
        var st = ac.getStats();
        Fx f = fx(a);
        long now = System.currentTimeMillis();
        double ad = st.getFinalAD();
        // Chasseur de monstres : 3 attaques après l'ultime, critiques à 80 % ; déjà critique → +15 % bruts
        if (f.fiendAttacks > 0 && f.fiendUntil > now && has(a, "fiendhunter_bolts")) {
            f.fiendAttacks--;
            if (crit[0]) itemDamage(a, t, dmg * 0.15, DamageUtil.Type.TRUE, true);
            else { crit[0] = true; dmg = ad * st.getFinalCritDamage() * 0.8; }
            if (f.fiendAttacks == 0) st.addBonusAttackSpeed(-0.5);
        }
        // Ciel éventré : la 1re attaque sur un champion (10 s par cible) est un critique à 80 %
        if (isChampion(t) && has(a, "sundered_sky") && f.sunderedCd.getOrDefault(t.getUniqueId(), 0L) <= now) {
            f.sunderedCd.put(t.getUniqueId(), now + 10_000L);
            if (!crit[0]) { crit[0] = true; dmg = ad * st.getFinalCritDamage() * 0.8; }
            var hp = ac.getHPSystem();
            double heal = (st.getBaseAD() * 0.9 + (hp.getMaxHP() - hp.getCurrentHP()) * 0.04) * (ranged(a) ? 0.5 : 1.0);
            hp.heal(heal);
        }
        // Lunettes Hextech C44 : jusqu'à +10 % selon la distance (max à 500 unités)
        if (has(a, "hexoptics_c44"))
            dmg *= 1 + 0.10 * Math.min(1, a.getLocation().distance(t.getLocation()) / 5.0);
        return dmg;
    }

    /** Portée d'attaque bonus (blocs) : Canon ultrarapide énergisé, Lunettes Hextech C44 après une élimination. */
    public static double bonusAttackRange(Player a) {
        if (champ(a) == null) return 0;
        Fx f = fx(a);
        double bonus = 0;
        if (has(a, "rapid_firecannon") && f.energize >= 100)
            bonus += Math.min(1.5, champ(a).getAutoAttackRange() * 0.35);
        if (has(a, "hexoptics_c44") && f.hexopticsUntil > System.currentTimeMillis()) bonus += 1.0;
        return bonus;
    }

    /** Effets à l'impact d'une attaque de base (champions, sbires, monstres). */
    public static void onAttackHit(Player a, LivingEntity t, double dmg, boolean crit) {
        onAttackHit(a, t, dmg, crit, true);
    }

    private static void onAttackHit(Player a, LivingEntity t, double dmg, boolean crit, boolean primary) {
        BaseChampion ac = champ(a);
        if (ac == null || t == null || t.isDead()) return;
        var st = ac.getStats();
        Fx f = fx(a);
        long now = System.currentTimeMillis();
        boolean champT = isChampion(t);
        boolean rng = ranged(a);
        int l = lvl(a);
        if (champT) enterChampionCombat(a);
        if (primary) f.immolateUntil = now + 3000; // Immolation : infliger des dégâts, sbires compris

        // Lame enragée de Guinsoo : au maximum de cumuls, une attaque sur trois applique deux fois les effets
        int times = 1;
        if (primary && has(a, "rageblade")) {
            if (f.ragebladeUntil < now && f.ragebladeStacks > 0) { st.addBonusAttackSpeed(-0.08 * f.ragebladeStacks); f.ragebladeStacks = 0; }
            if (f.ragebladeStacks < 4) { f.ragebladeStacks++; st.addBonusAttackSpeed(0.08); }
            f.ragebladeUntil = now + 4000;
            final int stacksNow = f.ragebladeStacks;
            later(81, () -> {
                Fx f2 = fx(a);
                if (f2.ragebladeUntil <= System.currentTimeMillis() && f2.ragebladeStacks > 0) {
                    st.addBonusAttackSpeed(-0.08 * f2.ragebladeStacks);
                    f2.ragebladeStacks = 0;
                }
            });
            if (stacksNow >= 4 && ++f.ragebladeCount % 3 == 0) times = 2;
        }
        // Aube et crépuscule : sa Lame enchantée applique les effets à l'impact une fois de plus
        boolean duskProc = primary && f.spellbladeUntil > now && has(a, "dusk_and_dawn") && ready(f.cd, "spellblade");
        if (duskProc) times++;

        for (int i = 0; i < times; i++) onHitEffects(a, t, dmg, champT, rng, l);

        if (!primary) return;

        // ── Lame enchantée (une seule à la fois, 1.5 s de récupération) ──
        if (f.spellbladeUntil > now && ready(f.cd, "spellblade")) spellblade(a, t, champT, rng);

        // ── Énergisé (Poignard de Statikk, Canon ultrarapide, Lame tempête, Cyclosabre voltaïque) ──
        if (has(a, "statikk_shiv", "rapid_firecannon", "stormrazor", "voltaic_cyclosword")) {
            f.energize = Math.min(100, f.energize + 6 + (has(a, "statikk_shiv") ? 9 : 0));
            if (f.energize >= 100) { f.energize = 0; energized(a, t, champT, rng); }
        }

        // ── Effets propres à l'attaque principale ──
        // Tueur de krakens : une attaque sur trois, 150 (+5/niveau dès 9) ×0.8 à distance, jusqu'à +75 % selon les PV manquants
        if (has(a, "kraken_slayer") && ++f.krakenCount >= 3) {
            f.krakenCount = 0;
            double base = bp(150, 9, 5, l) * (rng ? 0.8 : 1.0);
            double missing = 1 - curHp(t) / Math.max(1, maxHp(t));
            itemDamage(a, t, base * (1 + 0.75 * missing), DamageUtil.Type.PHYSICAL, true);
            t.getWorld().strikeLightningEffect(t.getLocation());
        }
        // Brise-coques : une attaque sur cinq contre un champion ou un monstre épique
        if (has(a, "hullbreaker") && (champT || isEpicMonster(t)) && ++f.hullbreakerCount >= 5) {
            f.hullbreakerCount = 0;
            double d = (st.getBaseAD() * 1.2 + st.getFinalMaxHP() * 0.05) * (rng ? 0.7 : 1.0);
            itemDamage(a, t, d, DamageUtil.Type.PHYSICAL, true);
        }
        // Plaque du mort : l'élan accumulé (max 100) est consommé : (100 % AD de base + 40) × élan
        if (has(a, "dead_man_s_plate") && f.deadManMomentum > 0) {
            double d = (st.getBaseAD() + 40) * f.deadManMomentum / 100.0;
            f.deadManMomentum = 0;
            itemDamage(a, t, d, DamageUtil.Type.PHYSICAL, true);
        }
        // Cœuracier : cible préparée (3 s près d'elle), 70 + 6 % des PV max, +10 % des dégâts en PV max
        if (champT && has(a, "heartsteel") && f.heartsteelReady.remove(t.getUniqueId())) {
            cooldown(f.cd, "heartsteel:" + t.getUniqueId(), 30);
            double d = 70 + st.getFinalMaxHP() * 0.06;
            itemDamage(a, t, d, DamageUtil.Type.PHYSICAL, true);
            ac.getHPSystem().addBonusHP(d * 0.10);
            fx(a).cd.merge("heartsteelHP", (long) Math.round(d * 0.10), Long::sum);
        }
        // Glaive d'ombre : après 1 s hors de vue, 50 + 1.5 × létalité en dégâts bruts (4 s pour attaquer)
        if (champT && has(a, "umbral_glaive") && f.umbralReadyUntil > now && ready(f.cd, "umbral")) {
            f.umbralReadyUntil = 0;
            cooldown(f.cd, "umbral", 90);
            itemDamage(a, t, 50 + st.getFinalLethality() * 1.5, DamageUtil.Type.TRUE, true);
        }
        // Flèches des Yun Tal : +0.4 % (0.2 % à distance) de chances de critique par attaque, max 25 %
        if (has(a, "yun_tal_wildarrows")) {
            double gain = Math.min(0.25 - f.yunTalCrit, rng ? 0.002 : 0.004);
            if (gain > 0) { f.yunTalCrit += gain; st.addBonusCritChance(gain); }
            if (champT && ready(f.cd, "yuntal")) {
                cooldown(f.cd, "yuntal", 30);
                st.addBonusAttackSpeed(0.30);
                later(120, () -> st.addBonusAttackSpeed(-0.30));
            } else {
                Long c = f.cd.get("yuntal");
                if (c != null) f.cd.put("yuntal", c - (crit ? 2000 : 1000));
            }
        }
        // Tremblelame navori : chaque attaque réduit de 15 % la recharge restante des sorts de base
        if (has(a, "navori_flickerblade")) for (int i = 1; i <= 3; i++) {
            var ab = ac.getAbility(i);
            if (ab != null && ab.isOnCooldown(a)) ab.reduceCooldown(a, ab.getRemainingCooldown(a) * 0.15);
        }
        // Fronde de l'éclaireur : chaque attaque réduit sa recharge de 1 s
        if (has(a, "scout_s_slingshot")) { Long c = f.cd.get("slingshot"); if (c != null) f.cd.put("slingshot", c - 1000); }
        // Phage / Force de la trinité : vitesse après une attaque
        if (has(a, "trinity_force")) { st.addBonusMoveSpeed(20); later(40, () -> st.addBonusMoveSpeed(-20)); }
        else if (has(a, "phage")) { double ms = rng ? 10 : 20; st.addBonusMoveSpeed(ms); later(40, () -> st.addBonusMoveSpeed(-ms)); }
        // Lame du roi déchu : 3 attaques sur un champion en 6 s → ralenti 30 % (15 % à distance) 1 s (15 s)
        if (champT && has(a, "botrk")) {
            UUID id = t.getUniqueId();
            int n = f.botrkHitTime.getOrDefault(id, 0L) > now - 6000 ? f.botrkHits.computeIfAbsent(id, _ -> new int[1])[0] + 1 : 1;
            f.botrkHits.put(id, new int[]{n});
            f.botrkHitTime.put(id, now);
            if (n >= 3 && ready(f.cd, "botrkSlow")) {
                f.botrkHits.remove(id);
                cooldown(f.cd, "botrkSlow", 15);
                pl().getCCManager().slowPct(t, rng ? 0.15 : 0.30, 20);
            }
        }
        // Encensoir ardent (buff reçu) : +20 dégâts magiques à l'impact
        if (marked(a.getUniqueId(), "ardent")) itemDamage(a, t, 20, DamageUtil.Type.MAGICAL, true);
        // Rêve éveillé (bulle reçue) : la prochaine attaque inflige des dégâts magiques bonus
        if (MARKS.remove(a.getUniqueId() + ":dreamDmg") != null) itemDamage(a, t, bp(40, 7, 10, l), DamageUtil.Type.MAGICAL, true);
        // Ouragan de Runaan : 2 projectiles sur des ennemis proches (60 % AD, effets à l'impact)
        if (has(a, "runaan_s_hurricane") && rng) {
            int n = 0;
            for (var e : TargetingUtil.entitiesInRadius(a, t.getLocation(), ac.getAutoAttackRange() + 0.65)) {
                if (n >= 2 || e.equals(t)) continue;
                n++;
                itemDamage(a, e, st.getFinalAD() * 0.6, DamageUtil.Type.PHYSICAL, true);
                onAttackHit(a, e, st.getFinalAD() * 0.6, false, false);
                VisualEffectUtil.impact(a.getWorld(), e.getLocation().add(0, 1, 0), Material.WHITE_STAINED_GLASS, 0.3f, 3L);
            }
        }
        // Fendoir (Tiamat, hydres, Estropieur) : 40 % AD (20 % à distance) aux ennemis proches de la cible
        if (has(a, "tiamat", "ravenous_hydra", "profane_hydra", "stridebreaker")) {
            double d = st.getFinalAD() * (rng ? 0.2 : 0.4);
            int n = 0;
            for (var e : TargetingUtil.entitiesInRadius(a, t.getLocation(), 3.5)) {
                if (e.equals(t) || n++ >= 10) continue;
                itemDamage(a, e, d, DamageUtil.Type.PHYSICAL, true);
            }
        }
        // Hydre titanesque : 1 % des PV max à la cible, 3 % aux ennemis derrière elle (×0.5 à distance)
        if (has(a, "titanic_hydra")) {
            double mult = rng ? 0.5 : 1.0;
            boolean empowered = f.cd.remove("titanicEmpowered") != null;
            double maxHpA = st.getFinalMaxHP();
            itemDamage(a, t, maxHpA * (empowered ? 0.05 : 0.01) * mult, DamageUtil.Type.PHYSICAL, true);
            org.bukkit.util.Vector axis = t.getLocation().toVector().subtract(a.getLocation().toVector()).setY(0);
            int n = 0;
            for (var e : TargetingUtil.entitiesInRadius(a, t.getLocation(), 3.5)) {
                if (e.equals(t) || n >= 10) continue;
                org.bukkit.util.Vector to = e.getLocation().toVector().subtract(t.getLocation().toVector()).setY(0);
                if (axis.lengthSquared() > 1e-6 && to.lengthSquared() > 1e-6 && axis.angle(to) > Math.toRadians(60)) continue;
                n++;
                itemDamage(a, e, maxHpA * (empowered ? 0.12 : 0.03) * mult, DamageUtil.Type.PHYSICAL, true);
            }
        }
        scorchclaw(a, t);
        // Abatteur : +3 PV par attaque ; Arc de Doran : +3 PV
        if (has(a, "cull")) ac.getHPSystem().heal(3);
        if (has(a, "doran_s_bow")) ac.getHPSystem().heal(3);
    }

    /** Effets « à l'impact » proprement dits (doublés par Guinsoo et Aube et crépuscule). */
    private static void onHitEffects(Player a, LivingEntity t, double dmg, boolean champT, boolean rng, int l) {
        BaseChampion ac = champ(a);
        var st = ac.getStats();
        Fx f = fx(a);
        long now = System.currentTimeMillis();
        // Arc courbe : 15 physiques
        if (has(a, "recurve_bow")) itemDamage(a, t, 15, DamageUtil.Type.PHYSICAL, true);
        // Coup de main (Anneau, Bouclier, Casque de Doran, Larme) : +5 contre les sbires
        if (fr.lolmc.game.MinionManager.isMinion(t) && has(a, "doran_s_ring", "doran_s_shield", "doran_s_helm", "tear_of_goddess"))
            itemDamage(a, t, 5, DamageUtil.Type.PHYSICAL, true);
        // Lame du roi déchu : 9 % (6 % à distance) des PV actuels, max 100 contre les monstres
        if (has(a, "botrk")) {
            double d = curHp(t) * (rng ? 0.06 : 0.09);
            if (isMonster(t)) d = Math.min(100, d);
            itemDamage(a, t, d, DamageUtil.Type.PHYSICAL, true);
        }
        // Dent de Nashor : 15 + 15 % AP magiques
        if (has(a, "nashors_tooth")) itemDamage(a, t, 15 + st.getFinalAP() * 0.15, DamageUtil.Type.MAGICAL, true);
        // Au bout du rouleau : 45 magiques
        if (has(a, "wits_end")) itemDamage(a, t, 45, DamageUtil.Type.MAGICAL, true);
        // Lame enragée : 30 magiques
        if (has(a, "rageblade")) itemDamage(a, t, 30, DamageUtil.Type.MAGICAL, true);
        // Muramana : 1.2 % du mana max contre les champions
        if (champT && has(a, "muramana")) itemDamage(a, t, ac.getResourceSystem().getMax() * 0.012, DamageUtil.Type.PHYSICAL, true);
        // Terminus : 30 + 10 % AD bonus + 10 % AP magiques ; alternance lumière / ténèbres contre les champions
        if (has(a, "terminus")) {
            itemDamage(a, t, 30 + st.getBonusAD() * 0.10 + st.getFinalAP() * 0.10, DamageUtil.Type.MAGICAL, true);
            if (champT) {
                f.terminusLight = !f.terminusLight;
                if (f.terminusLight) { f.terminusLightStacks = Math.min(3, (f.terminusLightUntil > now ? f.terminusLightStacks : 0) + 1); f.terminusLightUntil = now + 5000; }
                else { f.terminusDarkStacks = Math.min(3, (f.terminusDarkUntil > now ? f.terminusDarkStacks : 0) + 1); f.terminusDarkUntil = now + 5000; }
            }
        }
        // Encensoir ardent et Rêve éveillé : gérés sur l'attaque principale
    }

    /** Lame enchantée : Brillance, Force de la trinité, Fléau de liche, Gantelet givrant, Faux spectrale, Mélodie du sang, Aube et crépuscule. */
    private static void spellblade(Player a, LivingEntity t, boolean champT, boolean rng) {
        BaseChampion ac = champ(a);
        var st = ac.getStats();
        Fx f = fx(a);
        double base = st.getBaseAD();
        boolean done = true;
        if (has(a, "trinity_force")) itemDamage(a, t, base * 2.0, DamageUtil.Type.PHYSICAL, true);
        else if (has(a, "lich_bane")) itemDamage(a, t, base * 0.75 + st.getFinalAP() * 0.45, DamageUtil.Type.MAGICAL, true);
        else if (has(a, "dusk_and_dawn")) {
            itemDamage(a, t, base * 0.75 + st.getFinalAP() * 0.10, DamageUtil.Type.MAGICAL, true);
            ac.getHPSystem().heal(st.getFinalAP() * 0.10 + st.getBonusHP() * 0.03);
        } else if (has(a, "iceborn_gauntlet")) {
            itemDamage(a, t, base * 1.5, DamageUtil.Type.PHYSICAL, true);
            // Champ de givre 2 s (rayon 300) : ralentit de 25 % (12.5 % à distance)
            final Location field = t.getLocation().clone();
            double slow = rng ? 0.125 : 0.25;
            for (int i = 0; i < 4; i++) later(i * 10L, () -> {
                for (var e : TargetingUtil.entitiesInRadius(a, field, 3.0)) pl().getCCManager().slowPct(e, slow, 12);
                VisualEffectUtil.groundRing(field.getWorld(), field, 3.0, Material.LIGHT_BLUE_STAINED_GLASS, 10, 0.25f, 0.05f, 10L);
            });
        } else if (has(a, "essence_reaver")) {
            double d = base * 1.25 + st.getFinalCritChance() * 50;
            itemDamage(a, t, d, DamageUtil.Type.PHYSICAL, true);
            if (ac.getResourceSystem().getType() == fr.lolmc.stats.ResourceSystem.ResourceType.MANA)
                ac.getResourceSystem().addCurrent(d * 0.5);
        } else if (has(a, "bloodsong")) {
            itemDamage(a, t, base, DamageUtil.Type.PHYSICAL, true);
            if (champT) mark(t.getUniqueId(), "bloodsong:" + (rng ? "r" : "m"), 4);
        } else if (has(a, "sheen")) itemDamage(a, t, base, DamageUtil.Type.PHYSICAL, true);
        else done = false;
        if (done) {
            f.spellbladeUntil = 0;
            cooldown(f.cd, "spellblade", 1.5);
            VisualEffectUtil.impact(a.getWorld(), t.getLocation().add(0, 1, 0), Material.LIGHT_BLUE_STAINED_GLASS, 0.4f, 4L);
        }
    }

    /** Attaque énergisée : Statikk (chaîne d'éclairs), Canon ultrarapide (+40), Lame tempête (+100, vitesse), Cyclosabre (% PV actuels). */
    private static void energized(Player a, LivingEntity t, boolean champT, boolean rng) {
        BaseChampion ac = champ(a);
        var st = ac.getStats();
        if (has(a, "statikk_shiv")) {
            int bounces = 4;
            var hit = new java.util.HashSet<UUID>();
            LivingEntity cur = t;
            for (int i = 0; i < bounces && cur != null; i++) {
                hit.add(cur.getUniqueId());
                itemDamage(a, cur, isChampion(cur) ? 60 : 90, DamageUtil.Type.MAGICAL, false);
                cur.getWorld().strikeLightningEffect(cur.getLocation());
                LivingEntity next = null; double best = 25;
                for (var e : TargetingUtil.entitiesInRadius(a, cur.getLocation(), 5.0)) {
                    if (hit.contains(e.getUniqueId())) continue;
                    double d = e.getLocation().distanceSquared(cur.getLocation());
                    if (d < best) { best = d; next = e; }
                }
                cur = next;
            }
        }
        if (has(a, "rapid_firecannon")) itemDamage(a, t, 40, DamageUtil.Type.MAGICAL, true);
        if (has(a, "stormrazor")) {
            itemDamage(a, t, 100, DamageUtil.Type.MAGICAL, true);
            pl().getCCManager().speedPct(a, 0.45, 30);
        }
        if (has(a, "voltaic_cyclosword")) {
            double d = curHp(t) * (rng ? 0.07 : 0.09);
            if (!champT) d = Math.min(200, d);
            itemDamage(a, t, d, DamageUtil.Type.PHYSICAL, true);
            double leth = rng ? 12 : 15;
            st.addBonusLethality(leth);
            later(80, () -> st.addBonusLethality(-leth));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SORTS
    // ══════════════════════════════════════════════════════════════

    /** Un sort vient d'être lancé ({@code manaCost} : mana dépensé). */
    public static void onCast(Player p, int slot, double manaCost) {
        BaseChampion c = champ(p);
        if (c == null) return;
        Fx f = fx(p);
        long now = System.currentTimeMillis();
        f.lastCastSlot = slot;
        f.lastCastTime = now;
        f.castId++;
        var st = c.getStats();
        // Lame enchantée : la prochaine attaque dans les 10 s est renforcée
        if (has(p, "sheen", "trinity_force", "lich_bane", "iceborn_gauntlet", "essence_reaver", "bloodsong", "dusk_and_dawn")
                && ready(f.cd, "spellblade")) {
            boolean fresh = f.spellbladeUntil <= now;
            f.spellbladeUntil = now + 10_000;
            // Fléau de liche : +50 % de vitesse d'attaque tant que la Lame enchantée est prête
            if (fresh && has(p, "lich_bane")) {
                st.addBonusAttackSpeed(0.5);
                later(1, () -> new BukkitRunnable() {
                    @Override public void run() {
                        if (fx(p).spellbladeUntil > System.currentTimeMillis() && p.isOnline()) return;
                        st.addBonusAttackSpeed(-0.5); cancel();
                    }
                }.runTaskTimer(pl(), 2L, 2L));
            }
        }
        // Actualisateur : coûts doublés, -30 % de recharge des sorts de base
        if (marked(p.getUniqueId(), "actualizer")) {
            if (manaCost > 0) { c.getResourceSystem().consume(Math.min(manaCost, c.getResourceSystem().getCurrent())); manaCost *= 2; }
            var ab = slot >= 1 && slot <= 3 ? c.getAbility(slot) : null;
            if (ab != null && ab.isOnCooldown(p)) ab.reduceCooldown(p, ab.getRemainingCooldown(p) * 0.30);
        }
        // Mandat impérial (Contrôle) : +20 d'accélération pour les compétences qui immobilisent.
        // La recharge vient d'être lancée : on la ramène à celle qu'elle aurait avec 20 d'accélération de plus.
        var castAb = c.getAbility(slot);
        if (castAb != null && IMMOBILIZING.contains(castAb.getId()) && has(p, "imperial_mandate") && castAb.isOnCooldown(p)) {
            double h = Math.max(0, st.getFinalAbilityHaste());
            castAb.reduceCooldown(p, castAb.getRemainingCooldown(p) * (1 - (100 + h) / (120 + h)));
        }
        // Catalyseur de l'éternité / Bâton séculaire : 25 % du mana dépensé rendu en PV (max 20 par sort)
        if (manaCost > 0 && has(p, "catalyst", "rod_of_ages") && ready(f.cd, "eternity")) {
            cooldown(f.cd, "eternity", 1);
            c.getHPSystem().heal(Math.min(20, manaCost * 0.25));
        }
        if (slot == 4) {
            // Hexplaque expérimentale : +50 % (25 %) de vitesse d'attaque et +20 % (10 %) de vitesse 8 s
            if (has(p, "experimental_hexplate") && ready(f.cd, "hexplate")) {
                cooldown(f.cd, "hexplate", 30);
                boolean rng = ranged(p);
                double as = rng ? 0.25 : 0.50;
                st.addBonusAttackSpeed(as);
                pl().getCCManager().speedPct(p, rng ? 0.10 : 0.20, 160);
                later(160, () -> st.addBonusAttackSpeed(-as));
            }
            // Chasseur de monstres : 3 attaques renforcées pendant 8 s
            if (has(p, "fiendhunter_bolts") && ready(f.cd, "fiendhunter")) {
                cooldown(f.cd, "fiendhunter", 45);
                if (f.fiendAttacks <= 0) st.addBonusAttackSpeed(0.5);
                f.fiendAttacks = 3;
                f.fiendUntil = now + 8000;
                later(160, () -> {
                    Fx f2 = fx(p);
                    if (f2.fiendAttacks > 0 && f2.fiendUntil <= System.currentTimeMillis()) { f2.fiendAttacks = 0; st.addBonusAttackSpeed(-0.5); }
                });
            }
            // Convergence de Zeke : tempête 5 s autour du porteur (30 dégâts/s, -30 % de vitesse)
            if (has(p, "zekess_convergence") && ready(f.cd, "zeke")) {
                cooldown(f.cd, "zeke", 45);
                for (int i = 0; i < 5; i++) later(i * 20L, () -> {
                    if (!p.isOnline()) return;
                    for (var e : TargetingUtil.enemiesAround(p, 3.5)) {
                        if (!isChampion(e)) continue;
                        itemDamage(p, e, 30, DamageUtil.Type.MAGICAL, false);
                        pl().getCCManager().slowPct(e, 0.30, 22);
                    }
                    VisualEffectUtil.groundRing(p.getWorld(), p.getLocation().add(0, 0.3, 0), 3.5,
                            Material.LIGHT_BLUE_STAINED_GLASS, 14, 0.3f, 0.1f, 20L);
                });
            }
        }
    }

    /**
     * Effets des dégâts (sorts, attaques, objets) une fois appliqués, côté attaquant et côté victime.
     * {@code isAbility} : dégâts de sort (les dégâts d'objets arrivent avec {@link #inItemDamage()}).
     */
    public static void afterDamage(Player a, LivingEntity v, double dmg, DamageUtil.Type type, boolean isAbility) {
        long now = System.currentTimeMillis();
        boolean item = inItemDamage();
        boolean champV = isChampion(v);
        if (a != null && champ(a) != null) {
            if (champV) {
                DAMAGED_BY.computeIfAbsent(v.getUniqueId(), _ -> new ConcurrentHashMap<>()).put(a.getUniqueId(), now);
                enterChampionCombat(a);
            }
            if (isAbility && !item) {
                LAST_ABILITY_HIT.put(v.getUniqueId(), new Object[]{a.getUniqueId(), now});
                Object[] pending = PENDING_CC.remove(v.getUniqueId());
                if (pending != null && now - (Long) pending[1] <= 250) onCrowdControl(a, v, (Boolean) pending[0]);
            }
            if (!item) attackerEffects(a, v, dmg, type, isAbility, champV, now);
        }
        if (v instanceof Player vp && champ(vp) != null && !item) victimEffects(vp, a, dmg, type, isAbility, now);
    }

    private static void attackerEffects(Player a, LivingEntity v, double dmg, DamageUtil.Type type, boolean isAbility,
                                        boolean champV, long now) {
        BaseChampion ac = champ(a);
        var st = ac.getStats();
        Fx f = fx(a);
        boolean rng = ranged(a);
        int l = lvl(a);
        var cc = pl().getCCManager();
        // Immolation (Égide solaire, Rayonnement du vide, Cendres de Bami) : 3 s après avoir infligé des dégâts
        f.immolateUntil = now + 3000;
        // ── Dégâts physiques ──
        if (type == DamageUtil.Type.PHYSICAL) {
            // Couperet noir : -6 % d'armure par cumul (5 max, 6 s) ; +20 (10) vitesse 2 s
            if (has(a, "black_cleaver")) {
                if (champV) shredArmor(v);
                double ms = rng ? 10 : 20;
                if (ready(f.cd, "cleaverMs")) { cooldown(f.cd, "cleaverMs", 2); st.addBonusMoveSpeed(ms); later(40, () -> st.addBonusMoveSpeed(-ms)); }
            }
            // Hémorragie 40 % 3 s : Marque du bourreau, Épée chimico-punk, Rappel mortel
            if (champV && has(a, "executioner_s_calling", "chempunk_chainsword", "mortal_reminder"))
                champ((Player) v).getStats().applyGrievousWounds(0.40, 3000L);
        }
        // ── Dégâts magiques ──
        if (type == DamageUtil.Type.MAGICAL && champV) {
            if (has(a, "oblivion_orb", "morellonomicon")) champ((Player) v).getStats().applyGrievousWounds(0.40, 3000L);
            // Malédiction du sanguinaire : -7.5 % de RM par cumul (4 max, 6 s), sorts et passifs
            if (isAbility && has(a, "bloodletter_s_curse") && ready(f.cd, "bloodletter")) {
                cooldown(f.cd, "bloodletter", 0.3);
                champ((Player) v).getStats().applyVileDecayStack(6000L);
            }
        }
        if (champV) {
            chargeHelia(a, dmg);
            // Percepteur : exécute les champions sous 5 % de leurs PV
            if (has(a, "the_collector") && curHp(v) > 0 && curHp(v) < maxHp(v) * 0.05)
                itemDamage(a, v, curHp(v) + 1, DamageUtil.Type.TRUE, false);
            // Élixir de sorcellerie : +25 dégâts bruts (5 s de récupération contre les champions)
            var cons = pl().getConsumableManager();
            if (cons != null && "sorcery".equals(cons.getActiveElixir(a)) && ready(f.cd, "elixirSorcery")) {
                cooldown(f.cd, "elixirSorcery", 5);
                itemDamage(a, v, 25, DamageUtil.Type.TRUE, false);
            }
            // Vœu du chevalier : le porteur proche récupère 12 % des dégâts infligés par son allié digne
            for (Player knight : a.getWorld().getPlayers()) {
                if (knight.equals(a) || !a.getUniqueId().equals(fx(knight).knightPartner) || !has(knight, "knight_s_vow")) continue;
                if (knight.getLocation().distanceSquared(a.getLocation()) <= 12.5 * 12.5 && champ(knight) != null)
                    champ(knight).getHPSystem().heal(dmg * 0.12);
            }
            // Crochet de serpent : boucliers réduits de 50 % 3 s ; réduit de 50 % les boucliers actuels à la 1re application
            if (has(a, "serpent_s_fang")) {
                var vs = champ((Player) v).getStats();
                if (!vs.hasSerpentVenom()) vs.reduceShield(0.50);
                vs.applySerpentVenom(0.50, 3000L);
            }
            // Éclipse : 2 attaques ou sorts distincts en 2 s → 8 % des PV max (×0.625 à distance) et bouclier (6 s)
            if (has(a, "eclipse") && ready(f.cd, "eclipse")) {
                if (v.getUniqueId().equals(f.eclipseLastTarget) && now - f.eclipseLastHit <= 2000 && now - f.eclipseLastHit > 50) {
                    cooldown(f.cd, "eclipse", 6);
                    f.eclipseLastTarget = null;
                    itemDamage(a, v, maxHp(v) * 0.08 * (rng ? 0.625 : 1.0), DamageUtil.Type.PHYSICAL, false);
                    tempShield(a, (150 + st.getBonusAD() * 0.40) * (rng ? 0.5 : 1.0), 40, false);
                } else { f.eclipseLastTarget = v.getUniqueId(); f.eclipseLastHit = now; }
            }
            // Fronde de l'éclaireur (40 magiques) / Alternateur Hextech (65 magiques) : 40 s
            if (has(a, "hextech_alternator") && ready(f.cd, "alternator")) { cooldown(f.cd, "alternator", 40); itemDamage(a, v, 65, DamageUtil.Type.MAGICAL, false); }
            else if (has(a, "scout_s_slingshot") && ready(f.cd, "slingshot")) { cooldown(f.cd, "slingshot", 40); itemDamage(a, v, 40, DamageUtil.Type.MAGICAL, false); }
            // Anneau de Doran : régénération de mana doublée 5 s après avoir blessé un champion
            if (has(a, "doran_s_ring")) f.doranRingUntil = now + 5000;
            // Volonté cosmique : dégâts magiques ou bruts → +20 vitesse 4 s (3 cumuls)
            if (type != DamageUtil.Type.PHYSICAL && has(a, "cosmic_drive")) {
                if (f.cosmicUntil < now) f.cosmicStacks = 0;
                if (f.cosmicStacks < 3) { f.cosmicStacks++; st.addBonusMoveSpeed(20); later(80, () -> { st.addBonusMoveSpeed(-20); Fx f2 = fx(a); f2.cosmicStacks = Math.max(0, f2.cosmicStacks - 1); }); }
                f.cosmicUntil = now + 4000;
            }
            // Onde orageuse : 25 % des PV max infligés en 2.5 s → Rafale 2 s plus tard (125 + 10 % AP), 30 s
            if (has(a, "stormsurge") && ready(f.cd, "stormsurge")) {
                double[] w = f.stormsurgeWindow.computeIfAbsent(v.getUniqueId(), _ -> new double[2]);
                if (now - w[1] > 2500) { w[0] = 0; w[1] = now; }
                w[0] += dmg;
                if (w[0] >= maxHp(v) * 0.25) {
                    cooldown(f.cd, "stormsurge", 30);
                    f.stormsurgeWindow.remove(v.getUniqueId());
                    double sq = 125 + st.getFinalAP() * 0.10;
                    pl().getCCManager().speedPct(a, 0.25, 30);
                    later(40, () -> {
                        if (!v.isDead() && v.isValid()) itemDamage(a, v, sq, DamageUtil.Type.MAGICAL, false);
                        else for (var e : TargetingUtil.entitiesInRadius(a, v.getLocation(), 5.0)) itemDamage(a, e, sq, DamageUtil.Type.MAGICAL, false);
                        v.getWorld().strikeLightningEffect(v.getLocation());
                    });
                }
            }
        }
        // ── Sorts uniquement ──
        if (!isAbility) {
            manaflow(a, v, champV, false);
            return;
        }
        manaflow(a, v, champV, true);
        scorchclaw(a, v);
        int castId = f.castId;
        boolean ult = f.lastCastSlot == 4 && now - f.lastCastTime < 4000;
        // Sceptre de Rylai : -30 % 1 s ; Rancune de Serylda : -30 % 1 s sous 50 % des PV
        if (has(a, "rylai_s_crystal_scepter")) cc.slowPct(v, 0.30, 20);
        else if (has(a, "serylda_s_grudge") && curHp(v) < maxHp(v) * 0.5) cc.slowPct(v, 0.30, 20);
        // Lance de Shojin : +1 cumul par lancer (4 max, 6 s)
        if (has(a, "spear_of_shojin") && f.shojinLastCast != castId) {
            f.shojinLastCast = castId;
            if (f.shojinUntil < now) f.shojinStacks = 0;
            f.shojinStacks = Math.min(4, f.shojinStacks + 1);
            f.shojinUntil = now + 6000;
        }
        // Tourment de Liandry : 2 % des PV max par seconde pendant 3 s (max 40 par tick contre les monstres)
        if (has(a, "liandry_anguish")) burn(a, v, "liandry", 3, () -> {
            double d = maxHp(v) * 0.02 / 2;
            return isMonster(v) ? Math.min(40, d) : d;
        });
        // Torche noire : 20 + 2 % AP par seconde (40 + 2 % AP contre les monstres) pendant 3 s
        if (has(a, "blackfire_torch")) burn(a, v, "blackfire", 3, () -> (isMonster(v) ? 40 : 20) / 2.0 + st.getFinalAP() * 0.02 / 2);
        // Cendres fatidiques : 5 par seconde pendant 3 s (+5/s contre les monstres)
        else if (has(a, "fated_ashes")) burn(a, v, "fatedAshes", 3, () -> isMonster(v) ? 5.0 : 2.5);
        // Écho de Luden : 75 + 5 % AP à la cible et à 5 ennemis proches ; échos restants sur la cible à 20 %
        if (has(a, "ludens_echo") && ready(f.cd, "luden")) {
            cooldown(f.cd, "luden", 12);
            double d = 75 + st.getFinalAP() * 0.05;
            itemDamage(a, v, d, DamageUtil.Type.MAGICAL, false);
            int used = 1;
            for (var e : TargetingUtil.entitiesInRadius(a, v.getLocation(), 6.5)) {
                if (used >= 6 || e.equals(v)) continue;
                used++;
                itemDamage(a, e, d, DamageUtil.Type.MAGICAL, false);
            }
            if (used < 6) itemDamage(a, v, d * 0.20 * (6 - used), DamageUtil.Type.MAGICAL, false);
            VisualEffectUtil.impact(v.getWorld(), v.getLocation().add(0, 1.5, 0), Material.AMETHYST_BLOCK, 0.25f, 6L);
        }
        if (champV) {
            // Muramana : 4 % (3 % à distance) du mana max, une fois par lancer
            if (has(a, "muramana") && f.muramanaLastCast != castId) {
                f.muramanaLastCast = castId;
                itemDamage(a, v, ac.getResourceSystem().getMax() * (rng ? 0.03 : 0.04), DamageUtil.Type.PHYSICAL, false);
            }
            // Lucidité pourpre : +10 % (8 %) de vitesse 4 s, une fois par sort
            if (has(a, "crimson_lucidity") && f.crimsonLastCast != castId) { f.crimsonLastCast = castId; cc.speedPct(a, rng ? 0.08 : 0.10, 80); }
            // Pique dimensionnelle de Zaz'Zak : 10 + 15 % AP + 3 % des PV max (10 s)
            if (has(a, "zaz_zak_s_realmspike") && ready(f.cd, "zazzak")) {
                cooldown(f.cd, "zazzak", 10);
                itemDamage(a, v, 10 + st.getFinalAP() * 0.15 + maxHp(v) * 0.03, DamageUtil.Type.MAGICAL, false);
            }
            // Briseur de bastion : 50 + 1.5 × létalité en dégâts bruts (×0.5 à distance), 20 s
            if (has(a, "bastionbreaker") && ready(f.cd, "bastion")) {
                cooldown(f.cd, "bastion", 20);
                itemDamage(a, v, (50 + st.getFinalLethality() * 1.5) * (rng ? 0.5 : 1.0), DamageUtil.Type.TRUE, false);
            }
            // Malfaisance : l'ultime brûle le sol 3 s (60 + 5 % AP par seconde, -10 RM)
            if (ult && has(a, "malignance") && ready(f.cd, "malignance")) {
                cooldown(f.cd, "malignance", 3);
                final Location ground = v.getLocation().clone();
                for (int i = 0; i < 12; i++) later(i * 5L, () -> {
                    for (var e : TargetingUtil.entitiesInRadius(a, ground, 2.5)) {
                        itemDamage(a, e, (60 + st.getFinalAP() * 0.05) * 0.25, DamageUtil.Type.MAGICAL, false);
                        if (isChampion(e) && !marked(e.getUniqueId(), "malignanceMR")) {
                            mark(e.getUniqueId(), "malignanceMR", 1);
                            var es = champ((Player) e).getStats();
                            es.addBonusMR(-10);
                            later(20, () -> es.addBonusMR(10));
                        }
                    }
                    VisualEffectUtil.groundRing(ground.getWorld(), ground, 2.5, Material.PURPLE_STAINED_GLASS, 10, 0.25f, 0.05f, 5L);
                });
            }
            // Concentration lointaine : sort touché à 600 unités ou plus → révèle 6 s, +10 % de dégâts du porteur
            if (has(a, "horizon_focus") && a.getLocation().distance(v.getLocation()) >= 6.0 && ready(f.cd, "horizon")) {
                cooldown(f.cd, "horizon", 30);
                mark(v.getUniqueId(), "horizon:" + a.getUniqueId(), 6);
                ((Player) v).addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, 120, 0, false, false));
            }
        }
    }

    private static void victimEffects(Player v, Player a, double dmg, DamageUtil.Type type, boolean isAbility, long now) {
        BaseChampion vc = champ(v);
        var st = vc.getStats();
        Fx f = fx(v);
        boolean fromChamp = a != null && champ(a) != null;
        f.immolateUntil = now + 3000;
        if (fromChamp) enterChampionCombat(v);
        // Rookern kaénique : le bouclier se recharge après 15 s sans dégâts magiques
        if (type == DamageUtil.Type.MAGICAL) { f.kaenicLastMagic = now; f.kaenicShieldUp = st.getMagicShield() > 0 && f.kaenicShieldUp; }
        if (!fromChamp) return;
        boolean rng = ranged(v);
        int l = lvl(v);
        // Bouclier de Doran : régénère jusqu'à 40 PV (30 à distance) en 8 s après des dégâts de champion
        if (has(v, "doran_s_shield")) f.doranShieldRegenLeft = rng ? 30 : 40;
        // Avancée blindée / Broyeuses à chaînes : bouclier 90 (+10/niv dès 9) + 8 % des PV bonus, 5 s (15 s)
        if (type == DamageUtil.Type.PHYSICAL && has(v, "armored_advance") && ready(f.cd, "armoredAdvance")) {
            cooldown(f.cd, "armoredAdvance", 15);
            tempShield(v, bp(90, 9, 10, l) + st.getBonusHP() * 0.08, 100, false);
        }
        if (type == DamageUtil.Type.MAGICAL && has(v, "chainlaced_crushers") && ready(f.cd, "crushers")) {
            cooldown(f.cd, "crushers", 15);
            tempShield(v, bp(90, 9, 10, l) + st.getBonusHP() * 0.08, 100, true);
        }
        // Force de la nature : 8 cumuls de dégâts magiques de champions (1 par seconde) → +70 RM et +6 % de vitesse
        if (type == DamageUtil.Type.MAGICAL && has(v, "force_of_nature") && now - f.fonLastStack >= 1000) {
            f.fonLastStack = now;
            f.fonStacks = Math.min(8, f.fonStacks + 1);
        }
        // Opposition céleste : 2 s de réduction des dégâts de champions, puis ralentissement de 50 % (18 s)
        if (has(v, "celestial_opposition") && ready(f.cd, "celestial")) {
            cooldown(f.cd, "celestial", 18);
            f.celestialUntil = now + 2000;
            later(40, () -> { for (var e : TargetingUtil.enemiesAround(v, 5.0)) pl().getCCManager().slowPct(e, 0.50, 30); });
        }
        // Épines (Armure roncière, Cotte épineuse) : touché par une attaque → dégâts magiques et 40 % d'Hémorragie
        if (!isAbility && has(v, "thornmail", "bramble_vest")) {
            double thorns = has(v, "thornmail") ? 20 + st.getBonusArmor() * 0.10 : 10;
            itemDamage(v, a, thorns, DamageUtil.Type.MAGICAL, false);
            champ(a).getStats().applyGrievousWounds(0.40, 3000L);
        }
        // Catalyseur / Bâton séculaire : 10 % des dégâts de champion rendus en mana
        if (has(v, "catalyst", "rod_of_ages") && vc.getResourceSystem().getType() == fr.lolmc.stats.ResourceSystem.ResourceType.MANA)
            vc.getResourceSystem().addCurrent(dmg * 0.10);
        // Rêve éveillé reçu : les prochains dégâts sont réduits (géré dans incoming)
    }

    /** Couperet noir : un cumul de plus (5 max) sur la cible, 6 % de son armure chacun. */
    private static void shredArmor(LivingEntity v) {
        if (!(v instanceof Player vp) || champ(vp) == null) return;
        var vs = champ(vp).getStats();
        double[] s = ARMOR_SHRED.computeIfAbsent(v.getUniqueId(), _ -> new double[3]);
        long now = System.currentTimeMillis();
        if (s[2] < now) { vs.addBonusArmor(s[1]); s[0] = 0; s[1] = 0; }
        s[0] = Math.min(5, s[0] + 1);
        double want = (vs.getFinalArmor() + s[1]) * 0.06 * s[0];
        vs.addBonusArmor(-(want - s[1]));
        s[1] = want;
        s[2] = now + 6000;
    }

    /** Brûlure d'objet : {@code perTick} dégâts magiques toutes les 0.5 s pendant {@code seconds} (rafraîchie). */
    private static void burn(Player a, LivingEntity v, String key, int seconds, java.util.function.Supplier<Double> perTick) {
        String k = v.getUniqueId() + ":" + key;
        boolean running = BURNS.getOrDefault(k, 0L) > System.currentTimeMillis();
        BURNS.put(k, System.currentTimeMillis() + seconds * 1000L);
        if (running) return;
        new BukkitRunnable() {
            @Override public void run() {
                if (BURNS.getOrDefault(k, 0L) <= System.currentTimeMillis() || v.isDead() || !v.isValid() || !a.isOnline()) {
                    BURNS.remove(k); cancel(); return;
                }
                itemDamage(a, v, perTick.get(), DamageUtil.Type.MAGICAL, false);
            }
        }.runTaskTimer(pl(), 10L, 10L);
    }

    /**
     * Flux de mana (Larme, Manamune, Bâton de l'archange, Approche de l'hiver, Diadème des murmures) :
     * toucher une cible gagne du mana max (doublé contre les champions), charges rechargées en 8 s,
     * une fois par sort (6.5 s) ; à +360, l'objet se transforme.
     */
    private static void manaflow(Player a, LivingEntity v, boolean champV, boolean isAbility) {
        BaseChampion ac = champ(a);
        if (ac.getResourceSystem().getType() != fr.lolmc.stats.ResourceSystem.ResourceType.MANA) return;
        String[][] items = {
                {"tear_of_goddess", "3", "4", "false", null},
                {"manamune", "3", "4", "true", "muramana"},
                {"archangel_s_staff", "5", "5", "false", "seraphs_embrace"},
                {"winter_s_approach", "3", "4", "true", "fimbulwinter"},
                {"whispering_circlet", "4", "5", "false", null}};
        Fx f = fx(a);
        long now = System.currentTimeMillis();
        for (String[] it : items) {
            if (!has(a, it[0])) continue;
            if (!isAbility && !Boolean.parseBoolean(it[3])) continue;
            if (isAbility) {
                Long lock = f.manaflowCastLock.get(f.castId);
                if (lock != null && lock > now) continue;
                f.manaflowCastLock.put(f.castId, now + 6500);
            }
            int charges = f.manaflowCharges.getOrDefault(it[0], Integer.parseInt(it[2]));
            if (charges <= 0) continue;
            f.manaflowCharges.put(it[0], charges - 1);
            f.manaflowNextCharge.putIfAbsent(it[0], now + 8000);
            int gainedKey = (int) (long) f.cd.getOrDefault("manaflow:" + it[0], 0L);
            if (gainedKey >= 360) continue;
            int gain = Math.min(360 - gainedKey, Integer.parseInt(it[1]) * (champV ? 2 : 1));
            ac.getResourceSystem().addMaxResource(gain);
            f.cd.put("manaflow:" + it[0], (long) (gainedKey + gain));
            if (gainedKey + gain >= 360 && it[4] != null) transform(a, it[0], it[4]);
        }
    }

    /** Transforme un objet du joueur en un autre (Manamune → Muramana, Archange → Séraphin…). */
    static void transform(Player p, String from, String to) {
        LolItem upgrade = ItemRegistry.get(to);
        LolItem old = ItemRegistry.get(from);
        BaseChampion c = champ(p);
        if (upgrade == null || old == null || c == null) return;
        var inv = pl().getShopListener().getOrCreate(p);
        for (int i = 0; i < 6; i++) {
            if (inv.getItem(i) != old) continue;
            inv.removeItemNoRefund(c, i);
            inv.equipItem(p, c, upgrade);
            var hb = pl().getHotbarManager();
            hb.removeItem(p, from);
            hb.addItem(p, to);
            hb.renderPage(p, c);
            p.sendMessage(Component.text("✨ " + old.getDisplayName() + " → " + upgrade.getDisplayName() + " !", NamedTextColor.AQUA));
            break;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CONTRÔLES, SOINS ET BOUCLIERS SUR LES ALLIÉS
    // ══════════════════════════════════════════════════════════════

    /**
     * Un champion ennemi vient d'être contrôlé par {@code caster} ({@code immobilize} : étourdi,
     * enraciné, projeté…, sinon ralenti) : Mandat impérial, Fimbulvetr, Flûtes de Bandle, Traîneau du solstice.
     */
    public static void onCrowdControl(Player caster, LivingEntity target, boolean immobilize) {
        if (caster == null || champ(caster) == null || !isChampion(target)) return;
        Fx f = fx(caster);
        int l = lvl(caster);
        var st = champ(caster).getStats();
        if (immobilize && has(caster, "imperial_mandate")) {
            mark(target.getUniqueId(), "mandate", 4);
            VisualEffectUtil.impact(target.getWorld(), target.getLocation().add(0, 2.3, 0), Material.PURPLE_STAINED_GLASS, 0.2f, 8L);
        }
        if (has(caster, "fimbulwinter") && (immobilize || !ranged(caster)) && ready(f.cd, "fimbul")) {
            cooldown(f.cd, "fimbul", 8);
            double shield = 100 + champ(caster).getResourceSystem().getCurrent() * 0.045;
            if (TargetingUtil.enemiesAround(caster, 8).stream().filter(ItemEffects::isChampion).count() > 1) shield *= 1.8;
            tempShield(caster, shield, 60, false);
        }
        if (has(caster, "bandlepipes")) {
            f.fanfareUntil = System.currentTimeMillis() + (ranged(caster) ? 4000 : 8000);
        }
        if (has(caster, "solstice_sleigh") && ready(f.cd, "sleigh")) {
            Player ally = nearestAlly(caster, 15);
            if (ally != null) {
                cooldown(f.cd, "sleigh", 30);
                double heal = bp(50, 7, 15, l) + st.getBonusHP() * 0.07;
                for (Player p : new Player[]{caster, ally}) {
                    var c = champ(p);
                    if (c != null) c.getHPSystem().heal(heal);
                    pl().getCCManager().speedDecaying(p, 0.20, 50);
                }
            }
        }
        if (immobilize && has((Player) target, "force_of_nature")) {
            Fx tf = fx((Player) target);
            tf.fonStacks = Math.min(8, tf.fonStacks + 2);
        }
    }

    static Player nearestAlly(Player p, double radius) {
        Player best = null; double bd = radius * radius;
        var tm = pl().getTeamManager();
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || !isChampion(o) || !tm.areAllies(p, o)) continue;
            double d = o.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    /**
     * {@code caster} vient de soigner ({@code heal}) ou de protéger ({@code shield}) l'allié {@code ally} :
     * Encensoir ardent, Bâton des flots, Régénérateur de pierre de lune, Échos d'Helia, Rêve éveillé.
     */
    public static void onAllyHealShield(Player caster, Player ally, double heal, double shield) {
        if (caster == null || ally == null || caster.equals(ally) || champ(caster) == null || champ(ally) == null) return;
        Fx f = fx(caster);
        // Encensoir ardent : +25 % de vitesse d'attaque et +20 magiques à l'impact pendant 6 s, pour les deux
        if (has(caster, "ardent_censer")) for (Player p : new Player[]{caster, ally}) {
            if (!marked(p.getUniqueId(), "ardent")) {
                var ps = champ(p).getStats();
                ps.addBonusAttackSpeed(0.25);
                later(120, () -> ps.addBonusAttackSpeed(-0.25));
            }
            mark(p.getUniqueId(), "ardent", 6);
        }
        // Bâton des flots : +40 puissance et +15 accélération pendant 6 s, pour les deux
        if (has(caster, "staff_of_flowing_water")) for (Player p : new Player[]{caster, ally}) {
            if (marked(p.getUniqueId(), "flowingWater")) { mark(p.getUniqueId(), "flowingWater", 6); continue; }
            mark(p.getUniqueId(), "flowingWater", 6);
            var ps = champ(p).getStats();
            ps.addBonusAP(40); ps.addBonusAbilityHaste(15);
            later(120, () -> { ps.addBonusAP(-40); ps.addBonusAbilityHaste(-15); });
        }
        // Régénérateur de pierre de lune : 30 % du soin / 35 % du bouclier sur un autre allié
        if (has(caster, "moonstone_renewer")) {
            Player other = null; double bd = 8 * 8;
            for (Player o : ally.getWorld().getPlayers()) {
                if (o.equals(ally) || o.equals(caster) || !isChampion(o) || !pl().getTeamManager().areAllies(caster, o)) continue;
                double d = o.getLocation().distanceSquared(ally.getLocation());
                if (d < bd) { bd = d; other = o; }
            }
            if (other != null) {
                if (heal > 0) champ(other).getHPSystem().heal(heal * 0.30);
                if (shield > 0) tempShield(other, shield * 0.35, 40, false);
            }
        }
        // Échos d'Helia : les charges (30 % des dégâts infligés aux champions) soignent l'allié
        if (has(caster, "echoes_of_helia") && f.heliaCharges >= 10) {
            champ(ally).getHPSystem().heal(f.heliaCharges);
            f.heliaCharges = 0;
        }
        // Rêve éveillé : bulles 3 s sur l'allié (prochaine attaque renforcée, prochains dégâts réduits)
        if (has(caster, "dream_maker") && ready(f.cd, "dream")) {
            cooldown(f.cd, "dream", 8);
            mark(ally.getUniqueId(), "dreamDmg", 3);
            mark(ally.getUniqueId(), "dreamDR", 3);
        }
    }

    /**
     * Soif-de-sang (Bouclier d'ichor) : les soins du vol de vie en excès des PV max deviennent un
     * bouclier, jusqu'à 165 (+15 par niveau dès 9) ; il disparaît après 25 s sans être rechargé.
     */
    public static void onLifesteal(Player p, double heal) {
        BaseChampion c = champ(p);
        if (c == null || heal <= 0 || !has(p, "bloodthirster")) return;
        var hp = c.getHPSystem();
        double excess = heal * c.getStats().getHealMultiplier() - (hp.getMaxHP() - hp.getCurrentHP());
        if (excess <= 0) return;
        Fx f = fx(p);
        double cap = bp(165, 9, 15, lvl(p));
        f.ichorShield = Math.min(f.ichorShield, c.getStats().getShield());
        double add = Math.min(excess, cap - f.ichorShield);
        if (add <= 0) return;
        c.getStats().addShield(add);
        f.ichorShield += add;
        long at = f.ichorAt = System.currentTimeMillis();
        later(500, () -> {
            Fx f2 = fx(p);
            var c2 = champ(p);
            if (f2.ichorAt != at || c2 == null) return;
            c2.getStats().addShield(-Math.min(f2.ichorShield, c2.getStats().getShield()));
            f2.ichorShield = 0;
        });
    }

    /** Échos d'Helia : 30 % des dégâts infligés aux champions deviennent des charges (max 80). */
    public static void chargeHelia(Player a, double raw) {
        if (a == null || !has(a, "echoes_of_helia")) return;
        Fx f = fx(a);
        f.heliaCharges = Math.min(80, f.heliaCharges + raw * 0.30);
    }

    // ══════════════════════════════════════════════════════════════
    // ÉLIMINATIONS
    // ══════════════════════════════════════════════════════════════

    /**
     * Mort d'un champion : objets des participants (dégâts infligés dans les 3 s) et du tueur, et
     * pertes de la victime (Gloire, Danse de la mort).
     */
    public static void onChampionDeath(Player victim, Player killer, java.util.Collection<Player> assistants) {
        long now = System.currentTimeMillis();
        var map = DAMAGED_BY.remove(victim.getUniqueId());
        var takedown3s = new java.util.HashSet<Player>();
        if (map != null) for (var e : map.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && now - e.getValue() <= 3000) takedown3s.add(p);
        }
        var participants = new java.util.LinkedHashSet<Player>();
        if (killer != null) participants.add(killer);
        if (assistants != null) participants.addAll(assistants);
        for (Player p : participants) {
            if (champ(p) == null) continue;
            boolean kill = p.equals(killer);
            Fx f = fx(p);
            var st = champ(p).getStats();
            // Sceau noir (2 / 1, max 10, +4 puissance) et Voleur d'âmes de Mejai (4 / 2, max 25, +5)
            if (has(p, "mejai_s_soulstealer")) f.glory = Math.min(25, f.glory + (kill ? 4 : 2));
            else if (has(p, "dark_seal")) f.glory = Math.min(10, f.glory + (kill ? 2 : 1));
            // Jambières gloutonnes / Voie immortelle : +0.6 % d'omnivampirisme par élimination (10 max)
            if (has(p, "gluttonous_greaves", "immortal_path") && f.carnage < 10) { f.carnage++; st.addBonusOmnivamp(0.006); }
            // Compagnon de jungle : une élimination de champion le fait grandir
            if (has(p, "smite_stalker", "pickaxe_jungle", "blue_smite")) growCompanion(p);
            // Percepteur : 25 PO bonus par élimination
            if (kill && has(p, "the_collector")) pl().getGoldManager().addGold(p.getUniqueId(), 25);
        }
        for (Player p : takedown3s) {
            if (champ(p) == null) continue;
            Fx f = fx(p);
            var c = champ(p);
            var st = c.getStats();
            // Arc axiomatique : la recharge de l'ultime baisse de 10 % (+0.25 % par point de létalité)
            if (has(p, "axiom_arc")) {
                var ult = c.getAbility(4);
                if (ult != null && ult.isOnCooldown(p))
                    ult.reduceCooldown(p, ult.getCurrentCooldown(st) * (0.10 + 0.0025 * st.getFinalLethality()));
            }
            // Orgueil : +12 AD (+3 par statue) pendant 90 s
            if (has(p, "hubris")) { f.hubrisStatues++; f.hubrisUntil = now + 90_000; }
            // Lunettes Hextech C44 : +100 de portée 8 s
            if (has(p, "hexoptics_c44")) f.hexopticsUntil = now + 8000;
            // Faim insatiable : +15 % d'omnivampirisme 8 s
            if (has(p, "unending_fury") && !marked(p.getUniqueId(), "feast")) {
                mark(p.getUniqueId(), "feast", 8);
                st.addBonusOmnivamp(0.15);
                later(160, () -> st.addBonusOmnivamp(-0.15));
            }
            // Fleur de crypte : nova sur le corps, soigne les alliés de 100 + 20 % AP (60 s)
            if (has(p, "cryptbloom") && ready(f.cd, "cryptbloom")) {
                cooldown(f.cd, "cryptbloom", 60);
                Location body = victim.getLocation();
                for (Player o : body.getWorld().getPlayers())
                    if (isChampion(o) && (o.equals(p) || pl().getTeamManager().areAllies(p, o)) && o.getLocation().distanceSquared(body) <= 6 * 6)
                        champ(o).getHPSystem().heal(100 + st.getFinalAP() * 0.20);
                VisualEffectUtil.impactBurst(body.getWorld(), body.clone().add(0, 1, 0), Material.LIME_STAINED_GLASS, 0.3f, 2.0, 10, 10L);
            }
            // Danse de la mort : purge les dégâts différés et soigne 75 % de l'AD bonus en 2 s
            if (has(p, "death_s_dance")) {
                f.ddPending = 0; f.ddTicksLeft = 0;
                double heal = st.getBonusAD() * 0.75;
                for (int i = 1; i <= 4; i++) later(i * 10L, () -> { var c2 = champ(p); if (c2 != null) c2.getHPSystem().heal(heal / 4); });
                pl().getCCManager().speedDecaying(p, 0.40, 40);
            }
            // Briseur de bastion : Sabotage 90 s
            if (has(p, "bastionbreaker")) f.sabotageUntil = now + 90_000;
            // Rayonnement du vide : Désolation, explosion autour de la victime (4 × l'immolation)
            if (has(p, "hollow_radiance")) {
                double d = (15 + st.getBonusHP() * 0.01) * 4;
                for (var e : TargetingUtil.entitiesInRadius(p, victim.getLocation(), 5.0)) itemDamage(p, e, d, DamageUtil.Type.MAGICAL, false);
            }
        }
        // Victime : perte de Gloire, dégâts différés effacés
        if (champ(victim) != null) {
            Fx vf = fx(victim);
            if (has(victim, "mejai_s_soulstealer")) vf.glory = Math.max(0, vf.glory - 10);
            else if (has(victim, "dark_seal")) vf.glory = Math.max(0, vf.glory - 5);
            vf.ddPending = 0; vf.ddTicksLeft = 0;
        }
    }

    /** Sbire tué par {@code killer} : Abatteur (1 PO, 350 PO à 100 sbires). */
    public static void onMinionKill(Player killer) {
        if (killer == null || !has(killer, "cull")) return;
        Fx f = fx(killer);
        if (f.cullDone) return;
        f.cullKills++;
        pl().getGoldManager().addGold(killer.getUniqueId(), 1);
        if (f.cullKills >= 100) {
            f.cullDone = true;
            pl().getGoldManager().addGold(killer.getUniqueId(), 350);
            killer.sendActionBar(Component.text("💰 Abatteur : +350 PO !", NamedTextColor.GOLD));
        }
    }

    /** Briseur de bastion (Sabotage) : la prochaine attaque sur une tourelle ou un monstre épique, 300 + 25 × létalité bruts en 3 s. */
    public static double consumeSabotage(Player a) {
        if (a == null || champ(a) == null || !has(a, "bastionbreaker")) return 0;
        Fx f = fx(a);
        if (f.sabotageUntil <= System.currentTimeMillis()) return 0;
        f.sabotageUntil = 0;
        return (300 + champ(a).getStats().getFinalLethality() * 25) * (ranged(a) ? 0.8 : 1.0);
    }

    /** Brise-coques contre les bâtiments : multiplicateur de la 5e attaque (300 % AD de base + 10 % PV max). */
    public static double hullbreakerStructureBonus(Player a) {
        if (a == null || champ(a) == null || !has(a, "hullbreaker")) return 0;
        Fx f = fx(a);
        if (++f.hullbreakerCount < 5) return 0;
        f.hullbreakerCount = 0;
        var st = champ(a).getStats();
        return (st.getBaseAD() * 3 + st.getFinalMaxHP() * 0.10) * (ranged(a) ? 0.7 : 1.0);
    }

    static void sound(Player p, Sound s) { p.getWorld().playSound(p.getLocation(), s, 1f, 1f); }

    // ══════════════════════════════════════════════════════════════
    // COMPAGNONS DE JUNGLE (Bébé Saute-nuages, Ixamandre, Chardent)
    // ══════════════════════════════════════════════════════════════

    /** Cumuls du compagnon de jungle (35 max) ; le Châtiment est déchaîné à 20. */
    public static int companionStacks(Player p) { return p == null ? 0 : fx(p).companion; }

    private static void growCompanion(Player p) {
        Fx f = fx(p);
        if (f.companion >= 35) return;
        f.companion++;
        if (f.companion == 20) p.sendActionBar(Component.text("🐾 Compagnon : Châtiment déchaîné !", NamedTextColor.GOLD));
        if (f.companion == 35) p.sendActionBar(Component.text("🐾 Compagnon adulte : quête de jungle terminée !", NamedTextColor.GOLD));
    }

    /**
     * Monstre de jungle tué : le compagnon grandit (1 cumul par monstre, champion ou épique) ; une fois
     * adulte, tuer un grand monstre déclenche son effet.
     */
    public static void onJungleKill(Player killer, LivingEntity monster) {
        if (killer == null || champ(killer) == null || !has(killer, "smite_stalker", "pickaxe_jungle", "blue_smite")) return;
        growCompanion(killer);
        Fx f = fx(killer);
        if (monster == null) return;
        var type = fr.lolmc.game.JungleManager.getMonsterType(monster);
        boolean large = type != null && (type.groupCount == 1 || monster.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey(pl(), "camp_large"), org.bukkit.persistence.PersistentDataType.BYTE));
        if (!large) return;
        // Tout compagnon (wiki) : un grand monstre rend des PV (0 à 250 selon le niveau, plus avec les PV
        // manquants) et du mana (19 à 87, 15 énergie), et rapporte 80 XP (+150 pour le premier)
        BaseChampion kc = champ(killer);
        int l = lvl(killer);
        later(6, () -> {
            var hp = kc.getHPSystem();
            double missing = 1 - hp.getHPRatio();
            hp.heal(interp(0, 250, l) * (0.5 + 0.5 * missing));
            var res = kc.getResourceSystem();
            if (res.getType() == fr.lolmc.stats.ResourceSystem.ResourceType.MANA) res.addCurrent(interp(19, 87, l));
            else if (res.getType() == fr.lolmc.stats.ResourceSystem.ResourceType.ENERGY) res.addCurrent(15);
        });
        double xp = 80 + (f.cd.putIfAbsent("largeMonsterDone", 1L) == null ? 150 : 0); // +150 pour le premier
        fr.lolmc.instance.InstanceHelper.rewardManager(killer).grantProximityXP(killer, xp);
        if (f.companion < 35) return;
        // Saute-nuages : +45 % de vitesse qui décroît en 1.5 s ; Ixamandre : bouclier rechargé ; Chardent : prêt
        if (has(killer, "smite_stalker")) pl().getCCManager().speedDecaying(killer, 0.45, 30);
        if (has(killer, "pickaxe_jungle")) mossShield(killer);
        if (has(killer, "blue_smite")) f.scorchReady = true;
    }

    /** Ixamandre adulte : bouclier de 200 (+20 par niveau dès 11), rechargé hors combat. */
    static void mossShield(Player p) {
        Fx f = fx(p);
        var st = champ(p).getStats();
        double want = bp(200, 11, 20, lvl(p));
        double add = Math.max(0, want - Math.min(f.mossShield, st.getShield()));
        if (add <= 0) return;
        st.addShield(add);
        f.mossShield = want;
    }

    /**
     * Chardent adulte (wiki) : la prochaine attaque ou compétence contre un champion consomme les
     * 100 cumuls : la cible et les ennemis à 250 unités brûlent (5 % de leurs PV max en dégâts bruts
     * sur 4 s) et sont ralentis de 30 % (décroît en 2 s).
     */
    private static void scorchclaw(Player a, LivingEntity t) {
        Fx f = fx(a);
        if (!f.scorchReady || !has(a, "blue_smite") || !isChampion(t)) return;
        f.scorchReady = false;
        f.scorchStacks = 0;
        var hit = new java.util.ArrayList<LivingEntity>();
        hit.add(t);
        for (var e : TargetingUtil.entitiesInRadius(a, t.getLocation(), 2.5)) if (!e.equals(t)) hit.add(e);
        for (var e : hit) {
            double perSec = maxHp(e) * 0.0125;
            for (int i = 1; i <= 4; i++) later(i * 20L, () -> { if (!e.isDead()) itemDamage(a, e, perSec, DamageUtil.Type.TRUE, false); });
            pl().getCCManager().slowDecaying(e, 0.30, 40);
        }
    }
}
