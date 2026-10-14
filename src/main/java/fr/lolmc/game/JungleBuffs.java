package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Buffs de la jungle aux valeurs du wiki officiel (patch 26) : Crête de braises (rouge),
 * Crête d'intuition (bleu), effets cumulés de Pourfendeur de dragons, âmes du dragon et Main du
 * Baron. Un seul minuteur (1 s) gère les effets dans la durée ; le reste passe par des crochets
 * (attaque de base, dégâts infligés, ultime, mort, rappel).
 */
public final class JungleBuffs {

    private JungleBuffs() {}

    private static final Map<UUID, Long> redUntil = new HashMap<>(), blueUntil = new HashMap<>(), baronUntil = new HashMap<>();
    /** Bonus appliqués par buff (pour les retirer exactement). */
    private static final Map<UUID, double[]> blueApplied = new HashMap<>(), baronApplied = new HashMap<>();
    private static final Map<UUID, BaseChampion> buffChamp = new HashMap<>();
    /** Effets cumulés de dragon par joueur (élément → nombre). */
    private static final Map<UUID, Map<String, Integer>> drakeStacks = new HashMap<>();
    private static final Map<UUID, String> soul = new HashMap<>();
    private static final Map<String, Long> cooldowns = new HashMap<>();
    /** Bouclier de l'âme de la Montagne actuellement accordé. */
    private static final Map<UUID, Double> mountainShield = new HashMap<>();
    /** Âme Océanique : {PV par seconde, mana par seconde, fin}. */
    private static final Map<UUID, double[]> oceanRegen = new HashMap<>();
    private static boolean ticking = false;

    // ══════════════════════════════════════════════════════════════
    // OCTROI
    // ══════════════════════════════════════════════════════════════

    private static int level(Player p) {
        var cm = LolPlugin.getInstance().getChampionManager();
        return cm.hasChampion(p) ? cm.getChampion(p).getLevelSystem().getLevel() : 1;
    }

    private static boolean ranged(BaseChampion c) { return c.getAutoAttackRange() > 4.0; }

    /** Crête de braises : 120 s. */
    public static void grantRed(Player p) {
        redUntil.put(p.getUniqueId(), System.currentTimeMillis() + 120_000L);
        ensureTicker();
        p.sendActionBar(Component.text("🔴 Crête de braises (2 min) : brûlure + ralentissement sur tes attaques",
                NamedTextColor.RED));
    }

    /** Crête d'intuition : 120 s, 10/15/20 accélération de compétence + régénération de mana. */
    public static void grantBlue(Player p) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        removeBlue(p.getUniqueId());
        var champ = cm.getChampion(p);
        int lvl = level(p);
        double ah = lvl >= 11 ? 20 : lvl >= 6 ? 15 : 10;
        champ.getStats().addBonusAbilityHaste(ah);
        blueApplied.put(p.getUniqueId(), new double[]{ah});
        buffChamp.put(p.getUniqueId(), champ);
        blueUntil.put(p.getUniqueId(), System.currentTimeMillis() + 120_000L);
        ensureTicker();
        p.sendActionBar(Component.text("🔵 Crête d'intuition (2 min) : +" + (int) ah + " accélération, régénération de mana",
                NamedTextColor.BLUE));
    }

    private static void removeBlue(UUID id) {
        double[] a = blueApplied.remove(id);
        var champ = buffChamp.get(id);
        if (a != null && champ != null) champ.getStats().addBonusAbilityHaste(-a[0]);
        blueUntil.remove(id);
    }

    /** Main du Baron : 180 s, 12-48 AD et 20-80 AP selon la minute de la prise (fixés). */
    public static void grantBaron(Player p) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        removeBaron(p.getUniqueId());
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(p);
        double minutes = gm != null ? gm.getElapsedSeconds() / 60.0 : 20;
        double t = Math.max(0, Math.min(1, (minutes - 20) / 20.0)); // 20 min → 40 min
        double ad = 12 + 36 * t, ap = 20 + 60 * t;
        var champ = cm.getChampion(p);
        champ.getStats().addBonusAD(ad);
        champ.getStats().addBonusAP(ap);
        baronApplied.put(p.getUniqueId(), new double[]{ad, ap});
        buffChamp.put(p.getUniqueId(), champ);
        baronUntil.put(p.getUniqueId(), System.currentTimeMillis() + 180_000L);
        ensureTicker();
        p.sendActionBar(Component.text(String.format("🪱 Main du Baron (3 min) : +%.0f AD +%.0f AP, rappel accéléré", ad, ap),
                NamedTextColor.LIGHT_PURPLE));
    }

    private static void removeBaron(UUID id) {
        double[] a = baronApplied.remove(id);
        var champ = buffChamp.get(id);
        if (a != null && champ != null) { champ.getStats().addBonusAD(-a[0]); champ.getStats().addBonusAP(-a[1]); }
        baronUntil.remove(id);
    }

    public static boolean hasBaron(Player p) { return isActive(baronUntil, p.getUniqueId()); }

    /**
     * Un effet Pourfendeur de dragons (élément sans « drake_ »). Valeurs par effet (wiki) :
     * Infernal +3 % AD/AP, Montagne +5 % armure/RM, Océan 2 % des PV manquants / 5 s, Nuage +5 %
     * résistance aux ralentissements et vitesse hors combat, Chimtech +6 % ténacité et puissance
     * des soins et boucliers, Hextech +5 accélération et +5 % vitesse d'attaque.
     */
    public static void addDrakeStack(Player p, String element) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        var s = cm.getChampion(p).getStats();
        int n = drakeStacks.computeIfAbsent(p.getUniqueId(), _ -> new HashMap<>()).merge(element, 1, Integer::sum);
        switch (element) {
            case "infernal" -> { double f = (1 + 0.03 * n) / (1 + 0.03 * (n - 1)); s.multiplyAD(f); s.multiplyAP(f); }
            case "mountain" -> s.multiplyArmorMR((1 + 0.05 * n) / (1 + 0.05 * (n - 1)));
            case "cloud" -> s.addSlowResist(0.05);
            case "chemtech" -> { s.addTenacity(0.06); s.addHealShieldPower(0.06); }
            case "hextech" -> { s.addBonusAbilityHaste(5); s.addBonusAttackSpeed(0.05); }
            default -> {} // océan : régénération (minuteur)
        }
        ensureTicker();
    }

    public static void setSoul(Player p, String element) {
        soul.put(p.getUniqueId(), element);
        ensureTicker();
        if ("cloud".equals(element)) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(p)) cm.getChampion(p).getStats().multiplyMS(1.15); // +15 % permanent
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CROCHETS
    // ══════════════════════════════════════════════════════════════

    /** Attaque de base : brûlure et ralentissement de la Crête de braises (hors structures). */
    public static void onBasicAttack(Player attacker, LivingEntity target) {
        if (!isActive(redUntil, attacker.getUniqueId())) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return;
        int lvl = level(attacker);
        double perHit = (15 + 45 * (lvl - 1) / 17.0) / 3.0; // 15-60 bruts en 3 fois sur 2 s
        DamageUtil.damageEntity(attacker, target, perHit, false, DamageUtil.Type.TRUE);
        for (int i = 1; i <= 2; i++) {
            Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
                if (target.isValid() && !target.isDead() && attacker.isOnline())
                    DamageUtil.damageEntity(attacker, target, perHit, false, DamageUtil.Type.TRUE);
            }, 20L * i);
        }
        boolean r = ranged(cm.getChampion(attacker));
        int tier = lvl >= 11 ? 2 : lvl >= 6 ? 1 : 0;
        double slow = r ? new double[]{0.05, 0.075, 0.125}[tier] : new double[]{0.10, 0.15, 0.25}[tier];
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null) cc.slowPct(target, slow, 60);
    }

    /** Un champion inflige des dégâts à un champion ennemi : âmes Infernale, Océanique, Hextech. */
    public static void onDamageDealt(Player attacker, Player victim) {
        String s = soul.get(attacker.getUniqueId());
        if (s == null) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return;
        var champ = cm.getChampion(attacker);
        var st = champ.getStats();
        double bonusHP = st.getBonusHP();
        UUID id = attacker.getUniqueId();
        switch (s) {
            case "infernal" -> {
                if (!cooldown(id + ":soul", 3000)) return;
                double dmg = 100 + 0.225 * st.getBonusAD() + 0.135 * st.getFinalAP() + 0.0275 * bonusHP;
                var type = st.getBonusAD() > st.getFinalAP() ? DamageUtil.Type.PHYSICAL : DamageUtil.Type.MAGICAL;
                for (var e : fr.lolmc.util.TargetingUtil.entitiesInRadius(attacker, victim.getLocation(), 3.0))
                    DamageUtil.damageEntity(null, e, dmg, false, type);
                fr.lolmc.util.VisualEffectUtil.impactBurst(victim.getWorld(), victim.getLocation().add(0, 1, 0),
                        org.bukkit.Material.ORANGE_STAINED_GLASS, 0.3f, 1.5, 8, 6L);
            }
            case "ocean" -> oceanTrigger(attacker, 1.0);
            case "hextech" -> {
                if (!cooldown(id + ":soul", 8000)) return;
                double dmg = 25 + (52.94 - 25) * (level(attacker) - 1) / 17.0;
                double slow = ranged(champ) ? 0.35 : 0.45;
                var cc = LolPlugin.getInstance().getCCManager();
                int chained = 0;
                for (var e : fr.lolmc.util.TargetingUtil.entitiesInRadius(attacker, victim.getLocation(), 5.0)) {
                    if (chained++ >= 4) break; // la cible + 3 rebonds
                    DamageUtil.damageEntity(null, e, dmg, false, DamageUtil.Type.TRUE);
                    if (cc != null) cc.slowDecaying(e, slow, 30);
                }
            }
            default -> {}
        }
    }

    /**
     * Âme Océanique (wiki) : infliger des dégâts rend 150 (+26 % AD bonus +17 % AP +7 % PV bonus) PV
     * et 100 (+3.5 % du mana max) mana sur 4 s ; contre les sbires et monstres, 30 % d'efficacité.
     * Pas de délai : chaque nouveau dégât relance les 4 s (sans cumuler deux régénérations).
     */
    private static void oceanTrigger(Player p, double eff) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        var champ = cm.getChampion(p);
        var st = champ.getStats();
        double heal = (150 + 0.26 * st.getBonusAD() + 0.17 * st.getFinalAP() + 0.07 * st.getBonusHP()) * eff / 4;
        var res = champ.getResourceSystem();
        double mana = res != null && res.getType() == fr.lolmc.stats.ResourceSystem.ResourceType.MANA
                ? (100 + 0.035 * res.getMax()) * eff / 4 : 0;
        double[] cur = oceanRegen.get(p.getUniqueId());
        long until = System.currentTimeMillis() + 4000L;
        if (cur != null && cur[2] > System.currentTimeMillis()) {
            cur[0] = Math.max(cur[0], heal); cur[1] = Math.max(cur[1], mana); cur[2] = until;
        } else {
            oceanRegen.put(p.getUniqueId(), new double[]{heal, mana, until});
        }
    }

    /** Dégâts infligés à un sbire ou un monstre (âme Océanique à 30 %). */
    public static void onDamageDealtUnit(Player attacker) {
        if ("ocean".equals(soul.get(attacker.getUniqueId()))) oceanTrigger(attacker, 0.30);
    }

    /** Nombre total d'effets Pourfendeur de dragons du joueur. */
    public static int drakeStackTotal(Player p) {
        var st = drakeStacks.get(p.getUniqueId());
        if (st == null) return 0;
        int n = 0;
        for (int v : st.values()) n += v;
        return n;
    }

    /**
     * Rancune ancestrale (wiki) : un dragon élémentaire subit 15 % de dégâts en moins par effet
     * Pourfendeur de dragons de l'attaquant (60 % au maximum).
     */
    public static double dragonDamageMultiplier(LivingEntity target, Player attacker) {
        if (attacker == null) return 1.0;
        var type = JungleManager.getMonsterType(target);
        if (type == null || !type.isDragon() || type == JungleManager.MonsterType.DRAGON_ELDER) return 1.0;
        return 1.0 - Math.min(0.60, 0.15 * drakeStackTotal(attacker));
    }

    // ── Main du Baron : sbires renforcés ──

    /** Champion de la même équipe avec la Main du Baron à moins de 12 blocs (aura). */
    public static boolean isEmpowered(LivingEntity minion) {
        if (baronUntil.isEmpty() || !MinionManager.isMinion(minion)) return false;
        var team = MinionManager.getMinionTeam(minion);
        var tm = LolPlugin.getInstance().getTeamManager();
        for (Player p : minion.getWorld().getPlayers()) {
            if (!hasBaron(p) || tm.getTeam(p) != team || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getLocation().distanceSquared(minion.getLocation()) <= 12 * 12) return true;
        }
        return false;
    }

    /**
     * Dégâts reçus par un sbire renforcé (wiki) : -50 → -70 % (selon la minute) contre les champions,
     * -85 % contre les sbires pour les sbires de mêlée.
     */
    public static double minionDamageMultiplier(LivingEntity target, Player attacker) {
        if (!isEmpowered(target)) return 1.0;
        String t = MinionManager.getMinionTypeTag(target);
        if (attacker == null) return "melee".equals(t) ? 0.15 : 1.0;
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(attacker);
        double minutes = gm != null ? gm.getElapsedSeconds() / 60.0 : 20;
        double reduction = 0.50 + 0.20 * Math.max(0, Math.min(1, (minutes - 20) / 20.0));
        return ("melee".equals(t) || "caster".equals(t)) ? 1.0 - reduction : 1.0;
    }

    /** Bonus d'un sbire à distance renforcé : {AD, portée en blocs} (mage +20 / +1, canon +50 / +7.5). */
    public static double[] rangedMinionBonus(LivingEntity minion) {
        if (!isEmpowered(minion)) return new double[]{0, 0};
        return "cannon".equals(MinionManager.getMinionTypeTag(minion)) ? new double[]{50, 7.5} : new double[]{20, 1.0};
    }

    /** Âme Chimtech : sous 50 % de PV, +13 % de dégâts infligés. */
    public static double outgoingMultiplier(Player attacker) {
        return "chemtech".equals(soul.get(attacker.getUniqueId())) && hpRatio(attacker) < 0.5 ? 1.13 : 1.0;
    }

    /** Âme Chimtech : sous 50 % de PV, -13 % de dégâts subis. */
    public static double incomingMultiplier(Player victim) {
        return "chemtech".equals(soul.get(victim.getUniqueId())) && hpRatio(victim) < 0.5 ? 0.87 : 1.0;
    }

    /** Âme des Nuages : +60 % de vitesse pendant 6 s après l'ultime (30 s). */
    public static void onUltimate(Player p) {
        if (!"cloud".equals(soul.get(p.getUniqueId())) || !cooldown(p.getUniqueId() + ":cloudult", 30000)) return;
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null) cc.speedPct(p, 0.60, 120);
    }

    /** Un champion tue un porteur de crête : la crête passe au tueur, durée pleine (wiki). */
    public static void transferOnKill(Player victim, Player killer) {
        if (killer == null || killer.equals(victim)) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(killer)) return;
        if (isActive(redUntil, victim.getUniqueId())) grantRed(killer);
        if (isActive(blueUntil, victim.getUniqueId())) grantBlue(killer);
    }

    /** Mort : Main du Baron et crêtes perdues (le Pourfendeur de dragons et l'âme restent). */
    public static void onDeath(Player p) {
        UUID id = p.getUniqueId();
        removeBaron(id);
        removeBlue(id);
        redUntil.remove(id);
        removeMountainShield(p);
    }

    /** Fin de partie / déconnexion : tout est retiré de la mémoire (les stats sont neuves ensuite). */
    public static void cleanup(UUID id) {
        redUntil.remove(id); blueUntil.remove(id); baronUntil.remove(id);
        blueApplied.remove(id); baronApplied.remove(id); buffChamp.remove(id);
        drakeStacks.remove(id); soul.remove(id); mountainShield.remove(id); oceanRegen.remove(id);
        cooldowns.keySet().removeIf(k -> k.startsWith(id.toString()));
    }

    // ══════════════════════════════════════════════════════════════
    // MINUTEUR (1 s)
    // ══════════════════════════════════════════════════════════════

    private static void ensureTicker() {
        if (ticking) return;
        ticking = true;
        Bukkit.getScheduler().runTaskTimer(LolPlugin.getInstance(), JungleBuffs::tick, 20L, 20L);
    }

    private static void tick() {
        var cm = LolPlugin.getInstance().getChampionManager();
        var cc = LolPlugin.getInstance().getCCManager();
        long now = System.currentTimeMillis();
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        ids.addAll(redUntil.keySet()); ids.addAll(blueUntil.keySet()); ids.addAll(baronUntil.keySet());
        ids.addAll(drakeStacks.keySet()); ids.addAll(soul.keySet()); ids.addAll(oceanRegen.keySet());
        for (UUID id : ids) {
            if (blueUntil.containsKey(id) && blueUntil.get(id) <= now) removeBlue(id);
            if (baronUntil.containsKey(id) && baronUntil.get(id) <= now) removeBaron(id);
            if (redUntil.containsKey(id) && redUntil.get(id) <= now) redUntil.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p == null || !cm.hasChampion(p) || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            var champ = cm.getChampion(p);
            var hp = champ.getHPSystem();
            int lvl = champ.getLevelSystem().getLevel();
            // Crête de braises : régénération hors combat 0.5/1/1.5/3 % des PV max / 5 s
            if (isActive(redUntil, id) && !hp.isInCombat()) {
                double pct = lvl >= 16 ? 0.03 : lvl >= 11 ? 0.015 : lvl >= 6 ? 0.01 : 0.005;
                hp.heal(hp.getMaxHP() * pct / 5);
            }
            // Crête d'intuition : 1 % du mana max + 5 par seconde
            if (isActive(blueUntil, id) && champ.getResourceSystem() != null) {
                var res = champ.getResourceSystem();
                res.addCurrent(res.getMax() * 0.01 + 5);
            }
            double[] oc = oceanRegen.get(id);
            if (oc != null) {
                if (oc[2] <= now) oceanRegen.remove(id);
                else {
                    hp.heal(oc[0]);
                    if (oc[1] > 0 && champ.getResourceSystem() != null) champ.getResourceSystem().addCurrent(oc[1]);
                }
            }
            var st = drakeStacks.get(id);
            // Volonté océanique : 2 % des PV manquants / 5 s par effet
            int ocean = st != null ? st.getOrDefault("ocean", 0) : 0;
            if (ocean > 0) hp.heal((hp.getMaxHP() - hp.getCurrentHP()) * 0.02 * ocean / 5);
            // Grâce des nuages : +5 % de vitesse hors combat par effet
            int cloud = st != null ? st.getOrDefault("cloud", 0) : 0;
            if (cloud > 0 && !hp.isInCombat() && cc != null) cc.speedPct(p, 0.05 * cloud, 25);
            // Âme de la Montagne : bouclier après 5 s sans dégâts
            if ("mountain".equals(soul.get(id)) && hp.msSinceLastDamage() >= 5000 && !mountainShield.containsKey(id)) {
                var s = champ.getStats();
                double shield = 220 + 0.16 * s.getBonusAD() + 0.12 * s.getFinalAP() + 0.12 * s.getBonusHP();
                s.addShield(shield);
                mountainShield.put(id, shield);
            } else if (mountainShield.containsKey(id) && hp.msSinceLastDamage() < 1000) {
                // Touché : le bouclier se recharge après 5 s sans dégâts (le reste absorbé est perdu)
                removeMountainShield(p);
            }
        }
    }

    private static void removeMountainShield(Player p) {
        Double sh = mountainShield.remove(p.getUniqueId());
        var cm = LolPlugin.getInstance().getChampionManager();
        if (sh == null || !cm.hasChampion(p)) return;
        var s = cm.getChampion(p).getStats();
        s.addShield(-Math.min(sh, s.getShield()));
    }

    // ══════════════════════════════════════════════════════════════

    private static boolean isActive(Map<UUID, Long> until, UUID id) {
        Long u = until.get(id);
        return u != null && u > System.currentTimeMillis();
    }

    private static boolean cooldown(String key, long ms) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(key);
        if (last != null && now - last < ms) return false;
        cooldowns.put(key, now);
        return true;
    }

    private static double hpRatio(Player p) {
        var cm = LolPlugin.getInstance().getChampionManager();
        return cm.hasChampion(p) ? cm.getChampion(p).getHPSystem().getHPRatio() : 1.0;
    }
}
