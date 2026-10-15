package fr.lolmc.rune;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Gère les pages de runes des joueurs et applique leurs effets pendant la partie, aux valeurs des
 * données du jeu (patch 26.20, perks.cdtb.bin.json) : 1 unité LoL = 1/100 de bloc.
 *
 * Bonus de stats : recalculés chaque seconde (et à chaque changement) par {@link #refresh(Player)}.
 * Chaque bonus est mémorisé par clé et seul l'écart est appliqué : réappliquer une page ne double
 * pas les stats, et les runes qui dépendent du niveau, des PV ou du temps de partie se mettent
 * à jour toutes seules (Concentration absolue, Conditionnement, Tempête menaçante, Légende…).
 */
public class RuneManager {

    // Page de runes par joueur (page active)
    private final Map<UUID, RunePage> playerPages = new HashMap<>();
    // Fichier de sauvegarde des pages
    private final java.io.File runeFile;
    private final org.bukkit.configuration.file.FileConfiguration runeConfig;

    // Cumuls de keystones par joueur (Conquérant, Tempo mortel, Attaque soutenue, Jeu de jambes)
    private final Map<UUID, Integer> keystoneStacks = new HashMap<>();
    private final Map<UUID, Long> keystoneLastProc = new HashMap<>();
    /** Recharges des runes (clé "uuid:rune") : évite que chaque coup déclenche l'effet. */
    private final Map<String, Long> runeCooldowns = new HashMap<>();

    /** Champions éliminés au moins une fois (effets « Chasseur de primes » : Chasseurs, Trésor). */
    private final Map<UUID, Set<UUID>> uniqueTakedowns = new HashMap<>();
    /** Progression Légende (100 = 1 effet). */
    private final Map<UUID, Double> legendProgress = new HashMap<>();
    private final Map<UUID, Integer> overgrowthCount = new HashMap<>();
    private final Map<UUID, Double> manaflowBonus = new HashMap<>();
    private final Map<UUID, Integer> mementos = new HashMap<>();
    private final Map<UUID, Integer> darkHarvestSouls = new HashMap<>();
    private final Map<UUID, Double> graspHP = new HashMap<>();
    private final Map<UUID, Long> graspLast = new HashMap<>(), graspCombatStart = new HashMap<>();
    /** Fins d'effets temporaires (Déluge de lames, Assaut du maraudeur, Premier coup, Après-coup, Inébranlable). */
    private final Map<UUID, Long> hailUntil = new HashMap<>(), phaseRushUntil = new HashMap<>(),
            firstStrikeUntil = new HashMap<>(), aftershockUntil = new HashMap<>(), unflinchingUntil = new HashMap<>();
    /** Après-coup : armure et RM octroyées pendant l'effet. */
    private final Map<UUID, double[]> aftershockResists = new HashMap<>();
    private final Map<UUID, Integer> hailHits = new HashMap<>();
    /** Attaque soutenue : cible des coups consécutifs, et bonus actif jusqu'à la sortie de combat. */
    private final Map<UUID, UUID> pressTarget = new HashMap<>();
    private final Set<UUID> pressActive = new HashSet<>();
    /** Électrocution : sources distinctes (attaque / sort par emplacement) touchées dans la fenêtre de 3 s. */
    private final Map<UUID, Set<String>> electrocuteHits = new HashMap<>();
    /** Assaut du maraudeur : dégâts infligés à une cible dans la fenêtre de 3 s. */
    private final Map<String, double[]> phaseRushDamage = new HashMap<>(); // {dégâts, début}
    /** Toucher de feu mortel : brûlures actives (clé "attaquant:victime"). */
    private final Map<String, org.bukkit.scheduler.BukkitTask> burns = new HashMap<>();
    private final Map<String, Long> burnStart = new HashMap<>();
    // Second souffle : tâche de soin active par joueur (relancée à chaque coup d'un champion)
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> secondWindTasks = new HashMap<>();

    /** Conquérant : dernier gain d'effets par sort (clé "uuid:emplacement"). */
    private final Map<String, Long> conquerorSpellStack = new HashMap<>();
    /** Impact soudain : instant de la dernière ruée / téléportation. */
    private final Map<UUID, Long> dashedAt = new HashMap<>();
    /** Combat contre des champions : début et dernier échange (Premier coup, Saut Hextech, Poigne). */
    private final Map<UUID, Long> champCombatStart = new HashMap<>(), champCombatLast = new HashMap<>();
    /** Comète arcanique : fin de récupération. */
    private final Map<UUID, Long> cometReadyAt = new HashMap<>();
    /** Plaque d'os : victime → {attaquant, coups restants, fin}. */
    private final Map<UUID, Object[]> bonePlating = new HashMap<>();
    /** Gardien : allié protégé → {gardien, fin}. */
    private final Map<UUID, Object[]> guarded = new HashMap<>();
    /** Coup de bouclier : PV du nouveau bouclier, et fin de la fenêtre (2 s après l'expiration du bouclier). */
    private final Map<UUID, Double> shieldBashAmount = new HashMap<>();
    private final Map<UUID, Long> shieldBashUntil = new HashMap<>();
    /** Jeu de jambes : dernière position (énergie gagnée en se déplaçant). */
    private final Map<UUID, Location> lastPos = new HashMap<>();
    /** Vitesse d'approche : cible contrôlée → {lanceur, instant}. */
    private final Map<UUID, Object[]> ccSource = new HashMap<>();
    /** Optimisation glaciale : zones gelées actives, et ennemis refroidis → {propriétaire, fin}. */
    private record GlacialZone(UUID owner, Location origin, Vector dir, long until, double slow) {}
    private final List<GlacialZone> glacialZones = new ArrayList<>();
    private final Map<UUID, Object[]> chilled = new HashMap<>();
    /** Triple tonique : élixirs déjà reçus. */
    private final Map<UUID, Integer> tonicsGiven = new HashMap<>();
    /** Grimoire déchaîné : prochain échange (ms), sorts uniques choisis, sorts récemment choisis. */
    private final Map<UUID, Long> spellbookReadyAt = new HashMap<>();
    private final Map<UUID, Set<String>> spellbookUnique = new HashMap<>();
    private final Map<UUID, Deque<String>> spellbookRecent = new HashMap<>();
    private final Map<UUID, Integer> spellbookSwaps = new HashMap<>();
    /** Marche sur l'eau : état « dans la rivière » au dernier calcul. */
    private final Set<UUID> inRiver = new HashSet<>();

    /** Joueurs dont les runes sont appliquées (appel à applyRuneStats). */
    private final Set<UUID> active = new HashSet<>();
    /** Bonus de stats appliqués (clé "rune:STAT"), et champion sur lequel ils l'ont été. */
    private final Map<UUID, Map<String, Double>> applied = new HashMap<>();
    private final Map<UUID, BaseChampion> appliedChamp = new HashMap<>();

    /** Dégâts infligés par une rune : ne redéclenchent pas les runes (Toucher de feu mortel bouclait). */
    private int procDepth = 0;

    /** Champions dont la force adaptative est en AP par défaut (sans bonus d'objets). */
    private static final Set<String> AP_DEFAULT = Set.of(
            "annie", "veigar", "amumu", "janna", "morgana", "malphite", "leona", "blitzcrank");

    /** Présence d'esprit : mana rendu selon le niveau (RegenAmount, index = niveau). */
    private static final double[] POM_MANA = {6, 6, 6.8, 7.6, 8.4, 9.2, 10, 12, 14, 16, 18, 20,
            23.2, 26.4, 29.6, 32.8, 36, 40, 44};

    private enum Stat { AD, AP, AH, ULT_HASTE, AS, HP, ARMOR, MR, LIFESTEAL, MS, MS_PCT, MS_EFF,
        TENACITY, SLOW_RESIST, HEAL_AMP, LOW_HP_HEAL, MANA }

    /**
     * Vrai (et démarre la recharge) si la rune {@code rune} est disponible pour {@code id}.
     * Public : aussi utilisé par PassiveManager (Goût du Sang).
     */
    public boolean tryRuneCooldown(UUID id, String rune, long cooldownMs) {
        String key = id + ":" + rune;
        long now = System.currentTimeMillis();
        Long last = runeCooldowns.get(key);
        if (last != null && now - last < cooldownMs) return false;
        runeCooldowns.put(key, now);
        return true;
    }

    public RuneManager() {
        this.runeFile = new java.io.File(LolPlugin.getInstance().getDataFolder(), "runes.yml");
        if (!runeFile.exists()) {
            try {
                if (!runeFile.createNewFile()) {
                    LolPlugin.getInstance().getLogger().warning(
                        "Impossible de créer runes.yml (fichier existait déjà entre le check et la création ?)");
                }
            } catch (Exception e) {
                LolPlugin.getInstance().getLogger().warning("Échec de création de runes.yml : " + e.getMessage());
            }
        }
        this.runeConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(runeFile);
        loadAllPages();
        startTicker();
    }

    // ── Gestion des pages ─────────────────────────────────────────

    public void setPage(UUID uuid, RunePage page) {
        page.sanitize();
        playerPages.put(uuid, page);
        savePage(uuid, page);
    }

    /** Sauvegarde la page d'un joueur dans runes.yml. */
    private void savePage(UUID uuid, RunePage page) {
        String base = "pages." + uuid + ".";
        runeConfig.set(base + "primaryPath", page.primaryPath != null ? page.primaryPath.name() : null);
        runeConfig.set(base + "keystone", page.keystone);
        runeConfig.set(base + "primaryRunes", new ArrayList<>(page.primaryRunes));
        runeConfig.set(base + "secondaryPath", page.secondaryPath != null ? page.secondaryPath.name() : null);
        runeConfig.set(base + "secondaryRunes", new ArrayList<>(page.secondaryRunes));
        runeConfig.set(base + "shard1", page.statShard1);
        runeConfig.set(base + "shard2", page.statShard2);
        runeConfig.set(base + "shard3", page.statShard3);
        try { runeConfig.save(runeFile); }
        catch (Exception e) { LolPlugin.getInstance().getLogger().warning("Erreur runes.yml: " + e.getMessage()); }
    }

    /** Charge toutes les pages sauvegardées au démarrage (runes retirées du jeu nettoyées). */
    private void loadAllPages() {
        var section = runeConfig.getConfigurationSection("pages");
        if (section == null) return;
        for (String uuidStr : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                String base = "pages." + uuidStr + ".";
                RunePage page = new RunePage();
                String pp = runeConfig.getString(base + "primaryPath");
                if (pp != null) page.primaryPath = RuneRegistry.Path.valueOf(pp);
                page.keystone = runeConfig.getString(base + "keystone");
                page.primaryRunes.addAll(runeConfig.getStringList(base + "primaryRunes"));
                String sp = runeConfig.getString(base + "secondaryPath");
                if (sp != null) page.secondaryPath = RuneRegistry.Path.valueOf(sp);
                page.secondaryRunes.addAll(runeConfig.getStringList(base + "secondaryRunes"));
                page.statShard1 = runeConfig.getString(base + "shard1", "adaptive");
                page.statShard2 = runeConfig.getString(base + "shard2", "adaptive");
                page.statShard3 = runeConfig.getString(base + "shard3", "health");
                // Pages d'avant le patch : Poro fantôme, Chasseur vorace, fragments armure/RM…
                page.sanitize();
                playerPages.put(uuid, page);
            } catch (Exception ex) {
                LolPlugin.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                        "[RuneManager] page illisible pour " + uuidStr, ex);
            }
        }
    }

    public RunePage getPage(UUID uuid) {
        return playerPages.getOrDefault(uuid, RunePage.defaultPage());
    }

    // ══════════════════════════════════════════════════════════════
    // BONUS DE STATS
    // ══════════════════════════════════════════════════════════════

    /**
     * Active les runes du joueur sur son champion (début de partie, /lola solo, /lola give).
     * Peut être rappelé sans risque : les bonus ne se cumulent pas.
     */
    public void applyRuneStats(Player player) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;
        active.add(player.getUniqueId());
        refresh(player);
        player.sendActionBar(Component.text("✨ Runes appliquées", NamedTextColor.LIGHT_PURPLE));
    }

    /** Recalcule tous les bonus de stats des runes et n'applique que les écarts. */
    public void refresh(Player player) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!active.contains(player.getUniqueId()) || !cm.hasChampion(player)) return;
        BaseChampion champ = cm.getChampion(player);
        UUID id = player.getUniqueId();
        if (appliedChamp.get(id) != champ) {
            // Nouveau champion : ses stats sont neuves, rien n'y est encore appliqué
            appliedChamp.put(id, champ);
            applied.put(id, new HashMap<>());
        }
        Map<String, Double> want = new HashMap<>();
        RunePage page = getPage(id);
        var s = champ.getStats();
        var hp = champ.getHPSystem();
        int lvl = champ.getLevelSystem().getLevel();
        double t = (lvl - 1) / 17.0; // 0 au niveau 1, 1 au niveau 18
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(player);
        long elapsed = gm != null && gm.isGameRunning() ? gm.getElapsedSeconds() : 0;
        long now = System.currentTimeMillis();

        // Coup de bouclier : être prévenu de chaque nouveau bouclier
        s.setShieldListener(page.has("shield_bash") ? amount -> onShieldGained(player, amount) : null);

        // ── Fragments ──
        shard(want, id, champ, "shard1", page.statShard1, t);
        shard(want, id, champ, "shard2", page.statShard2, t);
        shard(want, id, champ, "shard3", page.statShard3, t);

        // ── Keystones à effets cumulés ──
        int ks = keystoneStacks.getOrDefault(id, 0);
        boolean ranged = isRanged(champ);
        if ("conqueror".equals(page.keystone) && ks > 0) {
            double af = ks * (1.8 + 2.2 * t); // 1.8-4 force adaptative par effet
            adaptive(want, id, champ, "conqueror", af * 0.6, af);
        }
        if ("lethal_tempo".equals(page.keystone) && ks > 0) put(want, "lethal_tempo", Stat.AS, ks * (ranged ? 0.04 : 0.06));
        if ("hail_blades".equals(page.keystone) && isActive(hailUntil, id))
            put(want, "hail_blades", Stat.AS, ranged ? 0.60 : 0.90);
        if ("phase_rush".equals(page.keystone) && isActive(phaseRushUntil, id))
            put(want, "phase_rush", Stat.SLOW_RESIST, 0.50);
        if ("grasp_undying".equals(page.keystone)) put(want, "grasp_undying", Stat.HP, graspHP.getOrDefault(id, 0.0));
        if ("aftershock".equals(page.keystone) && isActive(aftershockUntil, id)) {
            double[] r = aftershockResists.getOrDefault(id, new double[2]);
            put(want, "aftershock", Stat.ARMOR, r[0]);
            put(want, "aftershock", Stat.MR, r[1]);
        }

        // ── Runes mineures ──
        int legend = (int) (legendProgress.getOrDefault(id, 0.0) / 100);
        int bounty = uniqueTakedowns.getOrDefault(id, Set.of()).size();
        if (page.has("legend_alacrity")) put(want, "legend_alacrity", Stat.AS, 0.03 + 0.015 * Math.min(10, legend));
        if (page.has("legend_haste")) put(want, "legend_haste", Stat.AH, 1.5 * Math.min(10, legend));
        if (page.has("legend_bloodline")) {
            put(want, "legend_bloodline", Stat.LIFESTEAL, 0.0045 * Math.min(15, legend));
            if (legend >= 15) put(want, "legend_bloodline", Stat.HP, 85);
        }
        if (page.has("ultimate_hunter")) put(want, "ultimate_hunter", Stat.ULT_HASTE, 6 + 5 * bounty);
        // Chasseur acharné : +8 vitesse hors combat par effet (0 au départ)
        if (page.has("relentless_hunter") && !hp.isInCombat() && bounty > 0)
            put(want, "relentless_hunter", Stat.MS, 8 * bounty);
        if (page.has("transcendence")) put(want, "transcendence", Stat.AH, (lvl >= 5 ? 5 : 0) + (lvl >= 8 ? 5 : 0));
        if (page.has("celerity")) {
            put(want, "celerity", Stat.MS_PCT, 0.01);
            put(want, "celerity", Stat.MS_EFF, 0.07); // bonus de vitesse 7 % plus efficaces
        }
        if (page.has("absolute_focus") && hp.getHPRatio() > 0.70)
            adaptive(want, id, champ, "absolute_focus", 1.8 + 16.2 * t, 3 + 27 * t);
        if (page.has("gathering_storm")) {
            int n = (int) (elapsed / 600); // paliers de 10 min : 8, 24, 48, 80, 120, 168… AP
            double ap = 8.0 * n * (n + 1) / 2;
            if (ap > 0) adaptive(want, id, champ, "gathering_storm", ap * 0.6, ap);
        }
        if (page.has("waterwalking") && inRiver.contains(id)) {
            put(want, "waterwalking", Stat.MS, 10);
            double af = 13 + 17 * t; // 13-30 force adaptative
            adaptive(want, id, champ, "waterwalking", af * 0.6, af);
        }
        if (page.has("conditioning") && elapsed >= 720) {
            put(want, "conditioning", Stat.ARMOR, 8 + (s.getFinalArmor() - appliedValue(id, "conditioning", Stat.ARMOR) + 8) * 0.03);
            put(want, "conditioning", Stat.MR, 8 + (s.getFinalMagicResist() - appliedValue(id, "conditioning", Stat.MR) + 8) * 0.03);
        }
        if (page.has("overgrowth")) {
            // +3 PV tous les 8 monstres ou sbires ; à 120, +3.5 % des PV max en plus
            int n = overgrowthCount.getOrDefault(id, 0);
            double bonus = 3 * (n / 8);
            if (n >= 120) bonus += (s.getFinalMaxHP() - appliedValue(id, "overgrowth", Stat.HP)) * 0.035;
            put(want, "overgrowth", Stat.HP, bonus);
        }
        if (page.has("unflinching")) {
            // +10 armure et RM sous contrôle, puis pendant 2 s
            var cc = LolPlugin.getInstance().getCCManager();
            if (cc != null && cc.isImpaired(player)) unflinchingUntil.put(id, now + 2000);
            if (isActive(unflinchingUntil, id)) {
                put(want, "unflinching", Stat.ARMOR, 10);
                put(want, "unflinching", Stat.MR, 10);
            }
        }
        if (page.has("revitalize")) {
            put(want, "revitalize", Stat.HEAL_AMP, 0.05);
            put(want, "revitalize", Stat.LOW_HP_HEAL, 0.10); // sous 40 % des PV
        }
        if (page.has("manaflow_band")) put(want, "manaflow_band", Stat.MANA, manaflowBonus.getOrDefault(id, 0.0));
        if (page.has("jack_of_all_trades")) {
            int kinds = itemStatKinds(player);
            put(want, "jack_of_all_trades", Stat.AH, kinds);
            double force = kinds >= 10 ? 20 : kinds >= 5 ? 8 : 0;
            if (force > 0) adaptive(want, id, champ, "jack_of_all_trades", force * 0.6, force);
        }
        // Chaussures légèrement magiques : +10 vitesse (conservé par les bottes améliorées)
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (page.has("magical_footwear") && pm != null && pm.getState(player).magicalFootwearGiven)
            put(want, "magical_footwear", Stat.MS, 10);

        // ── Appliquer les écarts (y compris les bonus qui ne sont plus voulus → 0) ──
        Map<String, Double> done = applied.get(id);
        Set<String> keys = new HashSet<>(done.keySet());
        keys.addAll(want.keySet());
        for (String key : keys) {
            double prev = done.getOrDefault(key, 0.0), next = want.getOrDefault(key, 0.0);
            if (Math.abs(next - prev) < 1e-9) continue;
            applyStat(champ, Stat.valueOf(key.substring(key.lastIndexOf(':') + 1)), prev, next);
            if (next == 0) done.remove(key); else done.put(key, next);
        }
    }

    private void shard(Map<String, Double> want, UUID id, BaseChampion champ, String key, String shard, double t) {
        switch (shard) {
            case "adaptive"       -> adaptive(want, id, champ, key, 5.4, 9);
            case "attack_speed"   -> put(want, key, Stat.AS, 0.10);
            case "ability_haste"  -> put(want, key, Stat.AH, 8);
            case "move_speed"     -> put(want, key, Stat.MS_PCT, 0.025);
            case "health_scaling" -> put(want, key, Stat.HP, 10 + 170 * t);
            case "health"         -> put(want, key, Stat.HP, 65);
            case "tenacity"       -> { put(want, key, Stat.TENACITY, 0.15); put(want, key, Stat.SLOW_RESIST, 0.15); }
            default -> {}
        }
    }

    private static void put(Map<String, Double> want, String key, Stat stat, double v) {
        want.merge(key + ":" + stat, v, Double::sum);
    }

    /**
     * Bonus adaptatif (LoL) : en AP si le champion a plus d'AP bonus que d'AD bonus (hors bonus
     * adaptatifs des runes eux-mêmes, sinon le choix se figerait), à égalité selon le champion.
     */
    private void adaptive(Map<String, Double> want, UUID id, BaseChampion champ, String key, double ad, double ap) {
        double runeAD = 0, runeAP = 0;
        for (var e : applied.getOrDefault(id, Map.of()).entrySet()) {
            if (e.getKey().endsWith(":AD")) runeAD += e.getValue();
            else if (e.getKey().endsWith(":AP")) runeAP += e.getValue();
        }
        double bonusAD = champ.getStats().getBonusAD() - runeAD;
        double bonusAP = champ.getStats().getBonusAP() - runeAP;
        boolean useAP = bonusAP != bonusAD ? bonusAP > bonusAD : AP_DEFAULT.contains(champ.getId());
        if (useAP) put(want, key, Stat.AP, ap); else put(want, key, Stat.AD, ad);
    }

    private double appliedValue(UUID id, String key, Stat stat) {
        var m = applied.get(id);
        return m != null ? m.getOrDefault(key + ":" + stat, 0.0) : 0.0;
    }

    private void applyStat(BaseChampion champ, Stat stat, double prev, double next) {
        var s = champ.getStats();
        double d = next - prev;
        switch (stat) {
            case AD -> s.addBonusAD(d);
            case AP -> s.addBonusAP(d);
            case AH -> s.addBonusAbilityHaste(d);
            case ULT_HASTE -> s.addUltimateHaste(d);
            case AS -> s.addBonusAttackSpeed(d);
            case HP -> s.addBonusHP(d);
            case ARMOR -> s.addBonusArmor(d);
            case MR -> s.addBonusMR(d);
            case LIFESTEAL -> s.addBonusLifeSteal(d);
            case MS -> s.addBonusMoveSpeed(d);
            case MS_PCT -> s.multiplyMS((1 + next) / (1 + prev));
            case MS_EFF -> s.addMsBonusEfficiency(d);
            case TENACITY -> s.addTenacity(d);
            case SLOW_RESIST -> s.addSlowResist(d);
            case HEAL_AMP -> s.addRuneHealAmp(d);
            case LOW_HP_HEAL -> s.addLowHealthHealAmp(d);
            case MANA -> {
                var res = champ.getResourceSystem();
                if (res != null && res.getType() == ResourceSystem.ResourceType.MANA) res.addMaxResource(d);
            }
        }
    }

    /** Nombre de stats différentes apportées par les objets du joueur (Polyvalence). */
    private int itemStatKinds(Player player) {
        var sl = LolPlugin.getInstance().getShopListener();
        if (sl == null) return 0;
        Set<String> kinds = new HashSet<>();
        for (var item : sl.getOrCreate(player).getEquippedItems())
            if (item != null) item.collectStatKinds(kinds);
        return kinds.size();
    }

    private static boolean isActive(Map<UUID, Long> until, UUID id) {
        Long u = until.get(id);
        return u != null && u > System.currentTimeMillis();
    }

    private static boolean isRanged(BaseChampion champ) {
        return fr.lolmc.game.AutoAttackManager.getAAType(champ) != fr.lolmc.game.AutoAttackManager.AAType.MELEE;
    }

    /** Interpolation linéaire du niveau 1 au niveau 18 (ByCharLevelInterpolation du jeu). */
    private static double lerp(int lvl, double at1, double at18) {
        return at1 + (at18 - at1) * Math.max(0, Math.min(17, lvl - 1)) / 17.0;
    }

    /** Interpolation selon la courbe de croissance des stats (Jeu de jambes). */
    private static double growthLerp(int lvl, double at1, double at18) {
        int n = Math.max(0, Math.min(17, lvl - 1));
        return at1 + (at18 - at1) * n * (0.7025 + 0.0175 * n) / 17.0;
    }

    private void refreshLater(Player p, long ticks) {
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> refresh(p), ticks);
    }

    /** Dégâts infligés par une rune : ne déclenchent pas d'autres effets de runes. */
    private void runeDamage(Player attacker, LivingEntity victim, double amount, DamageUtil.Type type) {
        if (amount <= 0 || victim == null || victim.isDead()) return;
        procDepth++;
        try {
            if (victim instanceof Player p) DamageUtil.damage(attacker, p, amount, true, type);
            else fr.lolmc.util.MobCombat.damage(attacker, victim, amount, type);
        } finally { procDepth--; }
    }

    /** Type adaptatif des dégâts d'une rune (physique si plus d'AD bonus que d'AP). */
    private static DamageUtil.Type adaptiveType(BaseChampion champ) {
        var s = champ.getStats();
        boolean ap = s.getBonusAP() != s.getBonusAD() ? s.getBonusAP() > s.getBonusAD() : AP_DEFAULT.contains(champ.getId());
        return ap ? DamageUtil.Type.MAGICAL : DamageUtil.Type.PHYSICAL;
    }

    /** Bouclier temporaire d'une rune (efficacité des soins et boucliers du lanceur comprise). */
    private void runeShield(Player caster, Player target, double amount, int ticks) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(target) || !cm.hasChampion(caster) || amount <= 0) return;
        var ts = cm.getChampion(target).getStats();
        double amt = amount * (1 + cm.getChampion(caster).getStats().getHealShieldPower());
        ts.addShield(amt);
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
            if (cm.getChampion(target) != null && cm.getChampion(target).getStats() == ts)
                ts.addShield(-Math.min(amt, ts.getShield() - ts.getMagicShield()));
        }, ticks);
    }

    private static boolean enemies(Player a, Player b) {
        var tm = LolPlugin.getInstance().getTeamManager();
        return tm != null && tm.areEnemies(a, b);
    }

    private static boolean allies(Player a, Player b) {
        var tm = LolPlugin.getInstance().getTeamManager();
        return a.equals(b) || (tm != null && tm.areAllies(a, b));
    }

    private static boolean alive(Player p) {
        return p.isOnline() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && LolPlugin.getInstance().getChampionManager().hasChampion(p);
    }

    // ══════════════════════════════════════════════════════════════
    // TÂCHES PÉRIODIQUES
    // ══════════════════════════════════════════════════════════════

    /** Toutes les secondes : bonus dynamiques, Ruban de mana, Sixième sens, Triple tonique. */
    private void startTicker() {
        new org.bukkit.scheduler.BukkitRunnable() {
            long ticks = 0;
            @Override public void run() {
                ticks++;
                for (UUID id : new ArrayList<>(active)) {
                    Player p = org.bukkit.Bukkit.getPlayer(id);
                    if (p == null) continue;
                    refresh(p);
                    if (ticks % 5 == 0) manaflowRegen(p);
                    sixthSense(p);
                    tripleTonic(p);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
        // Tous les 5 ticks : Jeu de jambes, Vitesse d'approche, zones gelées, Coup de bouclier,
        // Inébranlable, Marche sur l'eau
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                for (UUID id : new ArrayList<>(active)) {
                    Player p = org.bukkit.Bukkit.getPlayer(id);
                    if (p == null || !alive(p)) { lastPos.remove(id); continue; }
                    fastTick(p);
                }
                tickGlacialZones();
            }
        }.runTaskTimer(LolPlugin.getInstance(), 5L, 5L);
    }

    private void fastTick(Player p) {
        UUID id = p.getUniqueId();
        RunePage page = getPage(id);
        var cc = LolPlugin.getInstance().getCCManager();
        Location now = p.getLocation();
        Location prev = lastPos.put(id, now.clone());
        Vector moved = prev != null && prev.getWorld().equals(now.getWorld())
                ? now.toVector().subtract(prev.toVector()).setY(0) : new Vector();
        if (moved.lengthSquared() > 25) moved = new Vector(); // téléportation (base, réapparition)

        // Jeu de jambes : 1 effet Énergie tous les 24 unités parcourues (100 = attaque énergisée)
        if ("fleet_footwork".equals(page.keystone) && moved.lengthSquared() > 0) {
            int e = keystoneStacks.getOrDefault(id, 0);
            if (e < 100) keystoneStacks.put(id, (int) Math.min(100, e + moved.length() * 100 / 24));
        }

        // Vitesse d'approche : +7.5 % vers un champion ennemi contrôlé à 1000 unités (15 % si c'est vous)
        if (page.has("approach_velocity") && cc != null && moved.lengthSquared() > 1e-4) {
            double best = 0;
            for (Player e : p.getWorld().getPlayers()) {
                if (!alive(e) || !enemies(p, e) || e.getLocation().distanceSquared(now) > 10 * 10) continue;
                if (!cc.isImpaired(e)) continue;
                Vector to = e.getLocation().toVector().subtract(now.toVector()).setY(0);
                if (to.dot(moved) <= 0) continue; // pas vers lui
                Object[] src = ccSource.get(e.getUniqueId());
                boolean mine = src != null && id.equals(src[0]) && System.currentTimeMillis() - (Long) src[1] < 10_000;
                best = Math.max(best, mine ? 0.15 : 0.075);
            }
            if (best > 0) cc.speedPct(p, best, 6);
        }

        // Coup de bouclier : 2 s pour frapper après l'expiration du bouclier
        if (shieldBashAmount.containsKey(id) && shieldBashUntil.get(id) == Long.MAX_VALUE) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.getChampion(p).getStats().getShield() <= 0) shieldBashUntil.put(id, System.currentTimeMillis() + 2000);
        }

        // Inébranlable : suivre le début et la fin des contrôles
        if (page.has("unflinching") && cc != null) {
            boolean was = isActive(unflinchingUntil, id);
            if (cc.isImpaired(p)) unflinchingUntil.put(id, System.currentTimeMillis() + 2000);
            if (was != isActive(unflinchingUntil, id)) refresh(p);
        }

        // Marche sur l'eau : la rivière est toute la zone couverte d'eau (wiki)
        if (page.has("waterwalking")) {
            boolean water = p.isInWater() || now.getBlock().getType() == Material.WATER
                    || now.clone().subtract(0, 0.2, 0).getBlock().getType() == Material.WATER;
            if (water != inRiver.contains(id)) {
                if (water) inRiver.add(id); else inRiver.remove(id);
                refresh(p);
                if (!water && cc != null) { // les +10 de vitesse s'estompent en 1 s (wiki)
                    var st = LolPlugin.getInstance().getChampionManager().getChampion(p).getStats();
                    cc.speedDecaying(p, 10.0 / Math.max(1, st.getFinalMovementSpeed()), 20);
                }
            }
        } else inRiver.remove(id);
    }

    /** Ruban de mana au maximum : 1% du mana manquant toutes les 5 s. */
    private void manaflowRegen(Player p) {
        if (!getPage(p.getUniqueId()).has("manaflow_band") || manaflowBonus.getOrDefault(p.getUniqueId(), 0.0) < 250) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        var res = cm.getChampion(p).getResourceSystem();
        if (res != null && res.getType() == ResourceSystem.ResourceType.MANA)
            res.addCurrent((res.getMax() - res.getCurrent()) * 0.01);
    }

    /** Sixième sens : repère une balise ennemie à 900 unités (250 s) ; niveau 11 : la révèle 10 s. */
    private void sixthSense(Player p) {
        if (!getPage(p.getUniqueId()).has("sixth_sense")) return;
        var wm = LolPlugin.getInstance().getWardManager();
        var tm = LolPlugin.getInstance().getTeamManager();
        var cm = LolPlugin.getInstance().getChampionManager();
        if (wm == null || tm == null || !cm.hasChampion(p) || !tm.hasTeam(p)) return;
        var myTeam = tm.getTeam(p);
        for (var w : wm.getAllWards()) {
            if (w.team == myTeam || w.entityId == null || wm.isRevealed(w)) continue;
            if (!w.location.getWorld().equals(p.getWorld()) || w.location.distanceSquared(p.getLocation()) > 9 * 9) continue;
            if (!tryRuneCooldown(p.getUniqueId(), "sixth_sense", 250_000L)) return;
            if (cm.getChampion(p).getLevelSystem().getLevel() >= 11)
                wm.revealEnemyWards(p, w.location, 0.5, 10_000L);
            p.sendActionBar(Component.text(String.format("👁 Sixième sens : balise ennemie à %.0f blocs",
                    w.location.distance(p.getLocation())), NamedTextColor.LIGHT_PURPLE));
            return;
        }
    }

    /** Triple tonique : Élixir d'avarice au niveau 3, de force au 6, de compétence au 9. */
    private void tripleTonic(Player p) {
        if (!getPage(p.getUniqueId()).has("triple_tonic")) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        int lvl = cm.getChampion(p).getLevelSystem().getLevel();
        int given = tonicsGiven.getOrDefault(p.getUniqueId(), 0);
        String[] elixirs = {"elixir_avarice", "elixir_force", "elixir_skill"};
        int[] levels = {3, 6, 9};
        var hb = LolPlugin.getInstance().getHotbarManager();
        while (given < 3 && lvl >= levels[given]) {
            hb.addConsumable(p, elixirs[given]);
            p.sendActionBar(Component.text("🧪 Triple tonique : " + switch (given) {
                case 0 -> "Élixir d'avarice"; case 1 -> "Élixir de force"; default -> "Élixir de compétence"; }
                    + " reçu", NamedTextColor.GOLD));
            given++;
        }
        if (given != tonicsGiven.getOrDefault(p.getUniqueId(), 0)) {
            tonicsGiven.put(p.getUniqueId(), given);
            hb.renderPage(p, cm.getChampion(p));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SORTS D'INVOCATEUR, OBJETS, BALISES
    // ══════════════════════════════════════════════════════════════

    /** Durée de recharge d'un sort d'invocateur avec Savoir cosmique / Souvenirs épouvantables. */
    public long summonerCooldownMs(Player player, long baseMs) {
        RunePage page = getPage(player.getUniqueId());
        double haste = 0;
        if (page.has("cosmic_insight")) haste += 18;
        // Pas de reliques de vision dans ce mode : 3 accélération de sort d'invocateur par souvenir (LoL)
        if (page.has("grisly_mementos")) haste += 3 * mementos.getOrDefault(player.getUniqueId(), 0);
        return (long) (baseMs * 100.0 / (100.0 + haste));
    }

    /** Savoir cosmique : +10 accélération d'objet. */
    public double itemHaste(Player p) { return getPage(p.getUniqueId()).has("cosmic_insight") ? 10 : 0; }

    /**
     * Manteau nuageux : après un sort d'invocateur, 15 / 35 / 45 % de vitesse décroissant sur 2 s
     * selon son délai de récupération (≤ 100 s, ≤ 250 s, au-delà ; Téléportation : 45 %).
     */
    public void applyNimbusCloak(Player caster, int spellCooldownSeconds) {
        if (!getPage(caster.getUniqueId()).has("nimbus_cloak")) return;
        double pct = spellCooldownSeconds <= 100 ? 0.15 : spellCooldownSeconds <= 250 ? 0.35 : 0.45;
        LolPlugin.getInstance().getCCManager().speedDecaying(caster, pct, 40);
        caster.sendActionBar(Component.text("☁ Manteau nuageux !", NamedTextColor.AQUA));
    }

    /** Balise de profondeur : durée bonus des balises posées côté ennemi (rivière au niveau 9). */
    public int deepWardBonusSeconds(Player owner, Location loc, boolean trinket) {
        if (!getPage(owner.getUniqueId()).has("deep_ward")) return 0;
        var cm = LolPlugin.getInstance().getChampionManager();
        var tm = LolPlugin.getInstance().getTeamManager();
        var mm = fr.lolmc.instance.InstanceHelper.mapManager(owner);
        if (!cm.hasChampion(owner) || tm == null || mm == null || !tm.hasTeam(owner)) return 0;
        var myTeam = tm.getTeam(owner);
        Location mine = mm.getBaseSpawn(myTeam);
        Location theirs = mm.getBaseSpawn(myTeam == fr.lolmc.team.TeamManager.Team.BLUE
                ? fr.lolmc.team.TeamManager.Team.RED : fr.lolmc.team.TeamManager.Team.BLUE);
        if (mine == null || theirs == null || !mine.getWorld().equals(loc.getWorld())) return 0;
        int lvl = cm.getChampion(owner).getLevelSystem().getLevel();
        double dMine = loc.distance(mine), dTheirs = loc.distance(theirs);
        // Côté ennemi de la carte ; au niveau 9, la rivière (milieu de carte) compte aussi
        boolean deep = dTheirs < dMine || (lvl >= 9 && dTheirs < dMine * 1.15);
        return deep ? (int) Math.round(trinket ? lerp(lvl, 45, 150) : lerp(lvl, 30, 45)) : 0;
    }

    /**
     * Grimoire déchaîné : échange un sort d'invocateur ({@code from} → {@code to}). Premier échange
     * à 6:00, puis 270 s de récupération, -25 s par sort différent choisi (120 s min.). Hors combat
     * depuis 5 s ; impossible de reprendre un des 3 derniers sorts choisis.
     */
    public void spellbookSwap(Player p, String from, String to) {
        UUID id = p.getUniqueId();
        if (!getPage(id).has("unsealed_spellbook")) {
            p.sendMessage(Component.text("❌ Il faut la rune Grimoire déchaîné.", NamedTextColor.RED));
            return;
        }
        var cm = LolPlugin.getInstance().getChampionManager();
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(p);
        var ssm = LolPlugin.getInstance().getSummonerSpellManager();
        if (!cm.hasChampion(p) || gm == null || !gm.isGameRunning() || ssm == null) {
            p.sendMessage(Component.text("❌ Disponible uniquement en partie.", NamedTextColor.RED));
            return;
        }
        long now = System.currentTimeMillis();
        if (gm.getElapsedSeconds() < 360) {
            p.sendMessage(Component.text("❌ Premier échange à 6:00.", NamedTextColor.RED));
            return;
        }
        long ready = spellbookReadyAt.getOrDefault(id, 0L);
        if (now < ready) {
            p.sendMessage(Component.text(String.format("❌ Échange en récupération — %.0f s", (ready - now) / 1000.0), NamedTextColor.RED));
            return;
        }
        long last = Math.max(champCombatLast.getOrDefault(id, 0L), now - cm.getChampion(p).getHPSystem().msSinceLastDamage());
        if (now - last < 5000 || ssm.isChanneling(p)) {
            p.sendMessage(Component.text("❌ Il faut être hors combat depuis 5 s.", NamedTextColor.RED));
            return;
        }
        String target = to.toUpperCase(Locale.ROOT);
        Deque<String> recent = spellbookRecent.computeIfAbsent(id, _ -> new ArrayDeque<>());
        if (recent.contains(target)) {
            p.sendMessage(Component.text("❌ Choisis 3 autres sorts avant de reprendre celui-ci.", NamedTextColor.RED));
            return;
        }
        String err = ssm.swapSpell(p, from, to);
        if (err != null) { p.sendMessage(Component.text("❌ " + err, NamedTextColor.RED)); return; }
        recent.addLast(target);
        spellbookSwaps.merge(id, 1, Integer::sum);
        if ("SMITE".equals(target)) ssm.setSpellbookSmite(p, spellbookSwaps.get(id) >= 3);
        while (recent.size() > 3) recent.removeFirst();
        Set<String> unique = spellbookUnique.computeIfAbsent(id, _ -> new HashSet<>());
        unique.add(target);
        long cd = Math.max(120, 270 - 25L * unique.size()) * 1000L;
        spellbookReadyAt.put(id, now + cd);
        LolPlugin.getInstance().getHotbarManager().renderPage(p, cm.getChampion(p));
        p.sendMessage(Component.text("📖 Grimoire déchaîné : " + from.toUpperCase(Locale.ROOT) + " → " + target
                + String.format(" (prochain échange dans %d s)", cd / 1000), NamedTextColor.LIGHT_PURPLE));
    }

    // ══════════════════════════════════════════════════════════════
    // DÉGÂTS SUBIS
    // ══════════════════════════════════════════════════════════════

    /**
     * Dégâts sur le point d'être subis (après résistances, avant boucliers) : Plaque d'os
     * (30-60 de moins sur les 3 prochains coups du champion), Optimisation glaciale (un ennemi
     * refroidi inflige 15 % de dégâts en moins aux alliés du porteur, pas au porteur).
     */
    public double modifyIncoming(Player victim, Player attacker, double dmg) {
        if (attacker == null || dmg <= 0) return dmg;
        long now = System.currentTimeMillis();
        Object[] bp = bonePlating.get(victim.getUniqueId());
        if (bp != null && attacker.getUniqueId().equals(bp[0]) && (Integer) bp[1] > 0 && now < (Long) bp[2]) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(victim)) dmg = Math.max(0, dmg - lerp(cm.getChampion(victim).getLevelSystem().getLevel(), 30, 60));
            bp[1] = (Integer) bp[1] - 1;
        }
        Object[] ch = chilled.get(attacker.getUniqueId());
        if (ch != null && now < (Long) ch[1]) {
            Player owner = org.bukkit.Bukkit.getPlayer((UUID) ch[0]);
            if (owner != null && !owner.equals(victim) && allies(owner, victim)) dmg *= 0.85;
        }
        return dmg;
    }

    /**
     * Effets de runes au moment où le porteur SUBIT des dégâts ({@code amount} : après résistances,
     * avant boucliers) : Plaque d'os, Second souffle, Gardien, combat contre un champion.
     */
    public void onDamageTaken(Player victim, Player attacker, double amount) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(victim)) return;
        RunePage page = getPage(victim.getUniqueId());
        var champ = cm.getChampion(victim);
        UUID id = victim.getUniqueId();
        long now = System.currentTimeMillis();
        boolean fromChampion = attacker != null && !attacker.equals(victim) && cm.hasChampion(attacker) && enemies(victim, attacker);

        if (fromChampion) {
            noteChampionCombat(victim);
            // Plaque d'os : les 3 prochains coups de ce champion font 30-60 de moins pendant 1.5 s (55 s)
            if (page.has("bone_plating") && tryRuneCooldown(id, "bone_plating", 55_000L))
                bonePlating.put(id, new Object[]{attacker.getUniqueId(), 3, now + 1500});
            // Second souffle : 4 % des PV manquants rendus en 10 s (relancé à chaque coup)
            if (page.has("second_wind")) {
                var existing = secondWindTasks.remove(id);
                if (existing != null) existing.cancel();
                var task = new org.bukkit.scheduler.BukkitRunnable() {
                    int t = 0;
                    @Override public void run() {
                        if (t++ >= 10 || !alive(victim) || cm.getChampion(victim) != champ) {
                            secondWindTasks.remove(id);
                            cancel();
                            return;
                        }
                        var h = champ.getHPSystem();
                        h.heal((h.getMaxHP() - h.getCurrentHP()) * 0.004);
                    }
                }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
                secondWindTasks.put(id, task);
            }
        }
        if (amount > 0) guardian(victim, amount);
        // Les PV ont changé : Concentration absolue suit le seuil de 70 %
        if (page.has("absolute_focus")) refresh(victim);
    }

    /**
     * Gardien : protège les alliés à 350 unités et ceux ciblés par vos compétences (2.5 s). Si l'un
     * d'eux ou vous subissez au moins 50-165 dégâts, vous recevez tous deux 40-150 (+20 % AP,
     * +6 % PV bonus) de bouclier pendant 1.5 s. Récupération : 75-40 s.
     */
    private void guardian(Player victim, double amount) {
        var cm = LolPlugin.getInstance().getChampionManager();
        long now = System.currentTimeMillis();
        for (Player g : victim.getWorld().getPlayers()) {
            if (!"guardian".equals(getPage(g.getUniqueId()).keystone) || !alive(g) || !allies(g, victim)) continue;
            boolean protectedNow = g.equals(victim) || g.getLocation().distanceSquared(victim.getLocation()) <= 3.5 * 3.5;
            Object[] gd = guarded.get(victim.getUniqueId());
            if (!protectedNow && gd != null && g.getUniqueId().equals(gd[0]) && now < (Long) gd[1]) protectedNow = true;
            if (!protectedNow) continue;
            var gc = cm.getChampion(g);
            int lvl = gc.getLevelSystem().getLevel();
            if (amount < lerp(lvl, 50, 165)) continue;
            if (!tryRuneCooldown(g.getUniqueId(), "guardian", (long) (lerp(lvl, 75, 40) * 1000))) continue;
            var gs = gc.getStats();
            double shield = lerp(lvl, 40, 150) + gs.getFinalAP() * 0.20 + gs.getBonusHP() * 0.06;
            runeShield(g, g, shield, 30);
            if (!g.equals(victim)) runeShield(g, victim, shield, 30);
            g.sendActionBar(Component.text("🛡 Gardien !", NamedTextColor.AQUA));
            if (!g.equals(victim)) victim.sendActionBar(Component.text("🛡 Gardien (allié) !", NamedTextColor.AQUA));
            return;
        }
    }

    /** Un allié vient d'être soigné ou protégé par une compétence de {@code caster} : Gardien, Aery. */
    public void onAllyBuffed(Player caster, Player ally) {
        RunePage page = getPage(caster.getUniqueId());
        long now = System.currentTimeMillis();
        if ("guardian".equals(page.keystone)) guarded.put(ally.getUniqueId(), new Object[]{caster.getUniqueId(), now + 2500});
        // Invocation d'Aery : bouclier de 20-100 (+5 % AP, +10 % AD bonus) pendant 2.5 s
        if ("summon_aery".equals(page.keystone) && !aeryAway.contains(caster.getUniqueId())) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (!cm.hasChampion(caster)) return;
            sendAery(caster, ally, true, () -> {
                if (!cm.hasChampion(caster)) return;
                var c = cm.getChampion(caster);
                var s = c.getStats();
                runeShield(caster, ally, lerp(c.getLevelSystem().getLevel(), 20, 100) + s.getFinalAP() * 0.05 + s.getBonusAD() * 0.10, 50);
            });
        }
    }

    /**
     * Aery (données du jeu, Perks_Aery_*) : bond sur un ennemi en 0.45 s (sur un allié en 0.35 s),
     * effet à l'arrivée, puis elle reste 2 s sur la cible et revient (wiki, valeurs des infobulles
     * de Template:Rune data Summon Aery) : vitesse de départ 200 unités/s aux niveaux 1-5, 300 aux
     * niveaux 6-10, 600 dès le niveau 11 ; +10 de vitesse tous les 51 unités parcourues, et +2000
     * tous les 51 unités une fois à moins de 200 unités du porteur (on la « récupère » en s'approchant).
     */
    private void sendAery(Player owner, LivingEntity target, boolean ally, Runnable onArrival) {
        UUID id = owner.getUniqueId();
        aeryAway.add(id);
        int travel = ally ? 7 : 9; // 0.35 s / 0.45 s
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;
            Location pos = owner.getLocation().add(0, 1.5, 0);
            double speed = -1;   // unités/s au retour (fixée au départ du retour)
            double traveled = 0; // unités parcourues depuis le dernier palier de 51
            @Override public void run() {
                t++;
                if (!owner.isOnline() || !active.contains(id)) { aeryAway.remove(id); cancel(); return; }
                if (t <= travel) {
                    // Vol vers la cible
                    if (target.isValid() && target.getWorld().equals(pos.getWorld())) {
                        Location to = target.getLocation().add(0, 1.5, 0);
                        double f = 1.0 / (travel - t + 1);
                        pos.add(to.toVector().subtract(pos.toVector()).multiply(f));
                    }
                    if (t == travel && target.isValid() && !target.isDead()) onArrival.run();
                } else if (t <= travel + 40) {
                    // Reste 2 s sur la cible
                    if (target.isValid() && target.getWorld().equals(pos.getWorld())) pos = target.getLocation().add(0, 1.5, 0);
                } else {
                    // Retour vers le porteur, en accélérant
                    if (!owner.getWorld().equals(pos.getWorld())) { aeryAway.remove(id); cancel(); return; }
                    Location home = owner.getLocation().add(0, 1.5, 0);
                    Vector to = home.toVector().subtract(pos.toVector());
                    double d = to.length() * 100; // unités
                    if (speed < 0) {
                        var cm = LolPlugin.getInstance().getChampionManager();
                        int lvl = cm.hasChampion(owner) ? cm.getChampion(owner).getLevelSystem().getLevel() : 1;
                        speed = lvl >= 11 ? 600 : lvl >= 6 ? 300 : 200;
                    }
                    double step = speed / 20.0; // unités ce tick
                    if (d <= step) { aeryAway.remove(id); cancel(); return; }
                    pos.add(to.multiply(step / d));
                    traveled += step;
                    while (traveled >= 51) { // paliers d'accélération
                        traveled -= 51;
                        speed += d - step <= 200 ? 2000 : 10;
                    }
                }
                pos.getWorld().spawnParticle(Particle.END_ROD, pos, 1, 0.05, 0.05, 0.05, 0);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 1L, 1L);
    }
    private final Set<UUID> aeryAway = new HashSet<>();

    // ══════════════════════════════════════════════════════════════
    // CONTRÔLES DE FOULE
    // ══════════════════════════════════════════════════════════════

    /** {@code victim} vient de subir un contrôle (Inébranlable). */
    public void onCrowdControlled(Player victim) {
        if (!getPage(victim.getUniqueId()).has("unflinching")) return;
        unflinchingUntil.put(victim.getUniqueId(), System.currentTimeMillis() + 2000);
        refresh(victim);
    }

    /**
     * {@code caster} vient de contrôler le champion ennemi {@code target} ({@code immobilize} :
     * étourdi, entravé, projeté… ; sinon ralenti) : Après-coup, Optimisation glaciale,
     * Fontaine de vie, Vitesse d'approche.
     */
    public void onCrowdControl(Player caster, Player target, boolean immobilize) {
        if (caster.equals(target) || !enemies(caster, target)) return;
        ccSource.put(target.getUniqueId(), new Object[]{caster.getUniqueId(), System.currentTimeMillis()});
        RunePage page = getPage(caster.getUniqueId());
        if (immobilize) aftershock(caster);
        if (immobilize && "glacial_augment".equals(page.keystone)) glacialAugment(caster, target);
        if (page.has("font_life")) fontOfLife(caster);
    }

    /**
     * Après-coup : après avoir immobilisé un champion, +45 armure et RM (+75 % des résistances
     * bonus ; 80-150 max) pendant 2.5 s, puis explosion de 25-120 (+8 % des PV bonus) dégâts
     * magiques à 350 unités. Récupération : 20 s.
     */
    public void aftershock(Player caster) {
        if (!"aftershock".equals(getPage(caster.getUniqueId()).keystone)) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(caster) || !tryRuneCooldown(caster.getUniqueId(), "aftershock", 20_000L)) return;
        var c = cm.getChampion(caster);
        var s = c.getStats();
        int lvl = c.getLevelSystem().getLevel();
        double cap = lerp(lvl, 80, 150);
        double armor = Math.min(cap, 45 + (s.getBonusArmor() - appliedValue(caster.getUniqueId(), "aftershock", Stat.ARMOR)) * 0.75);
        double mr = Math.min(cap, 45 + (s.getBonusMagicResistValue() - appliedValue(caster.getUniqueId(), "aftershock", Stat.MR)) * 0.75);
        aftershockResists.put(caster.getUniqueId(), new double[]{armor, mr});
        aftershockUntil.put(caster.getUniqueId(), System.currentTimeMillis() + 2500);
        refresh(caster);
        caster.sendActionBar(Component.text(String.format("🛡 Après-coup ! +%.0f armure / +%.0f RM", armor, mr), NamedTextColor.GOLD));
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
            refresh(caster);
            if (!alive(caster) || cm.getChampion(caster) != c) return;
            double dmg = lerp(c.getLevelSystem().getLevel(), 25, 120) + c.getStats().getBonusHP() * 0.08;
            for (LivingEntity e : fr.lolmc.util.TargetingUtil.enemiesAround(caster, 3.5))
                runeDamage(caster, e, dmg, DamageUtil.Type.MAGICAL);
            fr.lolmc.util.VisualEffectUtil.impactBurst(caster.getWorld(), caster.getLocation().add(0, 1, 0),
                    Material.STONE, 0.3f, 1.0, 8, 6L);
        }, 50L);
    }

    /**
     * Optimisation glaciale : 3 rayons glacés partent du champion immobilisé (vers vous et les
     * autres champions ennemis proches) ; zones de 700 × 80 unités pendant 3 s + la durée de
     * l'immobilisation, qui ralentissent de 20 % (+6 % / 100 AP, +7 % / 100 AD bonus, +90 % par
     * 100 % d'efficacité des soins et boucliers) et réduisent de 15 % les dégâts des ennemis
     * touchés contre vos alliés. Récupération : 25 s.
     */
    private void glacialAugment(Player caster, Player target) {
        var cm = LolPlugin.getInstance().getChampionManager();
        var cc = LolPlugin.getInstance().getCCManager();
        if (!cm.hasChampion(caster) || cc == null || !tryRuneCooldown(caster.getUniqueId(), "glacial_augment", 25_000L)) return;
        var s = cm.getChampion(caster).getStats();
        double slow = (20 + s.getFinalAP() * 0.06 + s.getBonusAD() * 0.07 + s.getHealShieldPower() * 90) / 100.0;
        long until = System.currentTimeMillis() + 3000 + cc.immobileRemainingMs(target.getUniqueId());
        Location origin = target.getLocation();
        List<Vector> dirs = new ArrayList<>();
        Vector toCaster = caster.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if (toCaster.lengthSquared() < 1e-4) toCaster = caster.getLocation().getDirection().setY(0).multiply(-1);
        dirs.add(toCaster.normalize());
        // Autres champions ennemis proches (hors cible), à portée des rayons, du plus proche au plus loin
        List<Player> others = new ArrayList<>();
        for (Player o : origin.getWorld().getPlayers())
            if (!o.equals(target) && alive(o) && enemies(caster, o) && o.getLocation().distanceSquared(origin) <= 7 * 7) others.add(o);
        others.sort(Comparator.comparingDouble(o -> o.getLocation().distanceSquared(origin)));
        for (Player o : others) {
            if (dirs.size() >= 3) break;
            Vector v = o.getLocation().toVector().subtract(origin.toVector()).setY(0);
            if (v.lengthSquared() > 1e-4) dirs.add(v.normalize());
        }
        // Sans autre champion : rayons en éventail de part et d'autre du rayon principal
        double[] spread = {Math.toRadians(45), Math.toRadians(-45)};
        for (int i = 0; dirs.size() < 3; i++) dirs.add(dirs.getFirst().clone().rotateAroundY(spread[i % 2]));
        for (Vector d : dirs) glacialZones.add(new GlacialZone(caster.getUniqueId(), origin.clone(), d, until, slow));
        caster.sendActionBar(Component.text("❄ Optimisation glaciale !", NamedTextColor.AQUA));
    }

    private void tickGlacialZones() {
        if (glacialZones.isEmpty()) return;
        long now = System.currentTimeMillis();
        glacialZones.removeIf(z -> z.until() <= now);
        var cc = LolPlugin.getInstance().getCCManager();
        for (GlacialZone z : glacialZones) {
            Player owner = org.bukkit.Bukkit.getPlayer(z.owner());
            var w = z.origin().getWorld();
            for (double d = 0.5; d <= 7; d += 1.0)
                w.spawnParticle(Particle.SNOWFLAKE, z.origin().clone().add(z.dir().clone().multiply(d)).add(0, 0.2, 0), 1, 0.2, 0.05, 0.2, 0);
            if (owner == null || cc == null) continue;
            for (Player e : w.getPlayers()) {
                if (!alive(e) || !enemies(owner, e)) continue;
                Vector rel = e.getLocation().toVector().subtract(z.origin().toVector()).setY(0);
                double along = rel.dot(z.dir());
                if (along < 0 || along > 7) continue;
                double side = rel.clone().subtract(z.dir().clone().multiply(along)).length();
                if (side > 0.4 + 0.3) continue; // demi-largeur 40 unités + rayon du joueur
                cc.slowPct(e, Math.min(0.99, z.slow()), 10);
                chilled.put(e.getUniqueId(), new Object[]{z.owner(), now + 500});
            }
        }
    }

    /**
     * Fontaine de vie : restreindre les déplacements d'un champion ennemi soigne de 10-50 PV
     * (70 % pour les champions à distance) vous et l'allié le plus blessé à 1000 unités. 20 s.
     */
    private void fontOfLife(Player caster) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(caster) || !tryRuneCooldown(caster.getUniqueId(), "font_life", 20_000L)) return;
        var c = cm.getChampion(caster);
        double heal = lerp(c.getLevelSystem().getLevel(), 10, 50) * (isRanged(c) ? 0.7 : 1.0);
        c.getHPSystem().heal(heal);
        Player worst = null; double ratio = 2;
        for (Player a : caster.getWorld().getPlayers()) {
            if (a.equals(caster) || !alive(a) || !allies(caster, a) || a.getLocation().distanceSquared(caster.getLocation()) > 10 * 10) continue;
            double r = cm.getChampion(a).getHPSystem().getHPRatio();
            if (r < ratio) { ratio = r; worst = a; }
        }
        if (worst != null) cm.getChampion(worst).getHPSystem().heal(heal);
    }

    // ══════════════════════════════════════════════════════════════
    // SORTS, RUÉES, BOUCLIERS
    // ══════════════════════════════════════════════════════════════

    /** Sort lancé (emplacement 1-4) : interrompt le Saut Hextech. */
    public void onAbilityCast(Player caster, int slot) {
        LolPlugin.getInstance().getFlashManager().interruptHexflash(caster); // les sorts interrompent le Saut Hextech
    }

    /** Ruée, bond, saut instantané ou téléportation du joueur (Impact soudain). */
    public void onDash(Player p) {
        if (getPage(p.getUniqueId()).has("sudden_impact")) dashedAt.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /** Nouveau bouclier reçu (Coup de bouclier). */
    private void onShieldGained(Player p, double amount) {
        shieldBashAmount.put(p.getUniqueId(), amount);
        shieldBashUntil.put(p.getUniqueId(), Long.MAX_VALUE); // jusqu'à 2 s après l'expiration
    }

    /** Début et suite d'un combat contre des champions (Premier coup, Saut Hextech, Poigne). */
    private void noteChampionCombat(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = champCombatLast.get(id);
        if (last == null || now - last > 10_000) {
            champCombatStart.put(id, now);
            if (getPage(id).has("hextech_flashtraption")) LolPlugin.getInstance().getFlashManager().onChampionCombat(p);
        }
        champCombatLast.put(id, now);
    }

    // ══════════════════════════════════════════════════════════════
    // DÉGÂTS INFLIGÉS
    // ══════════════════════════════════════════════════════════════

    /** Effets des runes mineures quand {@code attacker} blesse le champion {@code victim}. */
    public void onHitEffects(Player attacker, Player victim, double damage, boolean isAbility) {
        if (procDepth > 0) return;
        RunePage page = getPage(attacker.getUniqueId());
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return;
        var champ = cm.getChampion(attacker);
        var s = champ.getStats();
        UUID id = attacker.getUniqueId();
        int level = champ.getLevelSystem().getLevel();
        long now = System.currentTimeMillis();
        var res = champ.getResourceSystem();
        boolean mana = res != null && res.getType() == ResourceSystem.ResourceType.MANA;
        boolean energy = res != null && res.getType() == ResourceSystem.ResourceType.ENERGY;
        boolean ranged = isRanged(champ);
        var cc = LolPlugin.getInstance().getCCManager();

        // Ruban de mana : +25 mana max par compétence touchant un champion (15 s), 250 max
        if (page.has("manaflow_band") && isAbility && mana
                && manaflowBonus.getOrDefault(id, 0.0) < 250
                && tryRuneCooldown(id, "manaflow_band", 15_000L)) {
            manaflowBonus.merge(id, 25.0, Double::sum);
            refresh(attacker);
        }
        // Présence d'esprit : 6-44 mana selon le niveau (80 % à distance) ou 6 énergie (8 s)
        if (page.has("presence_mind") && (mana || energy) && tryRuneCooldown(id, "presence_mind", 8_000L))
            res.addCurrent(energy ? 6 : POM_MANA[Math.min(18, level)] * (ranged ? 0.8 : 1.0));
        // Brûlure : la prochaine compétence inflige 20-40 dégâts magiques après 1 s (10 s)
        if (page.has("scorch") && isAbility && tryRuneCooldown(id, "scorch", 10_000L)) {
            double dmg = lerp(level, 20, 40);
            org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
                if (alive(attacker) && alive(victim)) runeDamage(attacker, victim, dmg, DamageUtil.Type.MAGICAL);
            }, 20L);
        }
        // Goût du sang : 16-40 (+10 % AD bonus, +5 % AP) PV en blessant un champion (20 s)
        if (page.has("taste_blood") && tryRuneCooldown(id, "taste_blood", 20_000L))
            champ.getHPSystem().heal(lerp(level, 16, 40) + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05);
        // Coup bas : 10-45 dégâts bruts sur un champion aux actions ou déplacements restreints (4 s)
        if (page.has("cheap_shot") && cc != null && cc.isImpaired(victim) && tryRuneCooldown(id, "cheap_shot", 4_000L))
            runeDamage(attacker, victim, lerp(level, 10, 45), DamageUtil.Type.TRUE);
        // Impact soudain : 4 s après une ruée ou une téléportation, 20-80 dégâts bruts (10 s)
        Long dashed = dashedAt.get(id);
        if (page.has("sudden_impact") && dashed != null && now - dashed <= 4000 && tryRuneCooldown(id, "sudden_impact", 10_000L)) {
            dashedAt.remove(id);
            runeDamage(attacker, victim, lerp(level, 20, 80), DamageUtil.Type.TRUE);
        }
        // Coup de bouclier : attaque de base avec un nouveau bouclier : 5-30 (+2.5 % PV bonus,
        // +15 % du bouclier) dégâts adaptatifs
        Long bashUntil = shieldBashUntil.get(id);
        if (page.has("shield_bash") && !isAbility && bashUntil != null && now < bashUntil) {
            double dmg = lerp(level, 5, 30) + s.getBonusHP() * 0.025 + shieldBashAmount.getOrDefault(id, 0.0) * 0.15;
            shieldBashUntil.remove(id);
            shieldBashAmount.remove(id);
            runeDamage(attacker, victim, dmg, adaptiveType(champ));
            attacker.sendActionBar(Component.text("🛡 Coup de bouclier !", NamedTextColor.GREEN));
        }
        // Premier coup : 50 % (35 % à distance) des dégâts supplémentaires convertis en PO
        if (isActive(firstStrikeUntil, id)) {
            int gold = (int) Math.round(damage * (0.07 / 1.07) * (ranged ? 0.35 : 0.50));
            if (gold > 0) LolPlugin.getInstance().getGoldManager().addGold(id, gold);
        }
        // Comète arcanique en récupération : une compétence qui touche un champion la réduit de 20 %
        if ("arcane_comet".equals(page.keystone) && isAbility) {
            Long ready = cometReadyAt.get(id);
            if (ready != null && ready > now && !cometJustFired) cometReadyAt.put(id, now + (long) ((ready - now) * 0.80));
        }
        cometJustFired = false;
        // Assaut du maraudeur : 25 % des PV max d'un champion en 3 s → +48 % de vitesse (75 % à
        // distance) et 50 % de résistance aux ralentissements pendant 4 s. Récupération : 20-10 s
        if ("phase_rush".equals(page.keystone)) {
            String key = id + ":" + victim.getUniqueId();
            double[] acc = phaseRushDamage.get(key);
            if (acc == null || now - acc[1] > 3000) phaseRushDamage.put(key, acc = new double[]{0, now});
            acc[0] += damage;
            if (cm.hasChampion(victim) && acc[0] >= cm.getChampion(victim).getHPSystem().getMaxHP() * 0.25
                    && tryRuneCooldown(id, "phase_rush", (long) (lerp(level, 20, 10) * 1000))) {
                phaseRushDamage.remove(key);
                phaseRushUntil.put(id, now + 4000);
                if (cc != null) cc.speedPct(attacker, 0.48 * (ranged ? 0.75 : 1.0), 80);
                refresh(attacker);
                refreshLater(attacker, 81L);
                attacker.sendActionBar(Component.text("💨 Assaut du maraudeur !", NamedTextColor.AQUA));
            }
        }
        noteChampionCombat(attacker);
    }

    /** Vrai juste après la chute d'une comète (sa propre touche ne réduit pas sa récupération). */
    private boolean cometJustFired = false;

    // ── Effets de combat (keystones) ──────────────────────────────

    /**
     * Appelé quand le joueur attaque ou touche un champion avec un sort.
     * Gère les keystones (Conquérant, Électrocution, Attaque soutenue...).
     */
    public void onDamageToChampion(Player attacker, Player victim, boolean isAbility) {
        if (procDepth > 0) return;
        RunePage page = getPage(attacker.getUniqueId());
        if (page.keystone == null) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return;
        var champ = cm.getChampion(attacker);
        var s = champ.getStats();
        int level = champ.getLevelSystem().getLevel();
        UUID id = attacker.getUniqueId();
        long now = System.currentTimeMillis();
        boolean ranged = isRanged(champ);
        int slot = isAbility ? sourceSlot(champ) : 0; // sort qui inflige les dégâts

        switch (page.keystone) {
            case "conqueror" -> {
                // 2 effets par coup (1 pour une attaque à distance), 12 max, 5 s ; un même sort
                // ne donne des effets qu'une fois toutes les 4 s
                if (isAbility && slot > 0) {
                    String key = id + ":" + slot;
                    Long last = conquerorSpellStack.get(key);
                    if (last != null && now - last < 4000) return;
                    conquerorSpellStack.put(key, now);
                }
                int gain = !isAbility && ranged ? 1 : 2;
                keystoneStacks.merge(id, gain, (a, b) -> Math.min(12, a + b));
                keystoneLastProc.put(id, now);
                refresh(attacker);
                scheduleStackDecay(attacker, 5000L);
            }
            case "lethal_tempo" -> {
                if (isAbility) return; // seules les attaques de base comptent
                int before = keystoneStacks.getOrDefault(id, 0);
                int stacks = Math.min(6, before + 1);
                keystoneStacks.put(id, stacks);
                keystoneLastProc.put(id, now);
                refresh(attacker);
                scheduleStackDecay(attacker, 6000L);
                // Au maximum : 9-30 (6-24 à distance) dégâts adaptatifs, +1 % par 1 % de vitesse d'attaque bonus
                if (stacks >= 6) {
                    double dmg = (ranged ? lerp(level, 6, 24) : lerp(level, 9, 30)) * (1 + s.getBonusAttackSpeedRatio());
                    runeDamage(attacker, victim, dmg, adaptiveType(champ));
                }
            }
            case "electrocute" -> {
                // 3 attaques ou compétences DIFFÉRENTES en 3 s : 70-240 (+10 % AD bonus, +5 % AP), 20 s
                Long first = keystoneLastProc.get(id);
                if (first == null || now - first > 3000L) {
                    electrocuteHits.put(id, new HashSet<>());
                    keystoneLastProc.put(id, now);
                }
                var hits = electrocuteHits.computeIfAbsent(id, _ -> new HashSet<>());
                hits.add(isAbility ? "ability:" + slot : "attack");
                if (hits.size() >= 3 && tryRuneCooldown(id, "electrocute", 20_000L)) {
                    hits.clear();
                    double dmg = lerp(level, 70, 240) + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05;
                    runeDamage(attacker, victim, dmg, adaptiveType(champ));
                    attacker.sendActionBar(Component.text("⚡ Électrocution !", NamedTextColor.YELLOW));
                }
            }
            case "press_attack" -> {
                if (isAbility) return;
                // 3 attaques consécutives sur le MÊME champion (4 s entre deux) : 40-160 adaptatifs
                Long last = keystoneLastProc.get(id);
                if (!victim.getUniqueId().equals(pressTarget.get(id)) || last == null || now - last > 4000) {
                    pressTarget.put(id, victim.getUniqueId());
                    keystoneStacks.put(id, 0);
                }
                keystoneLastProc.put(id, now);
                int stacks = keystoneStacks.merge(id, 1, Integer::sum);
                if (stacks >= 3) {
                    keystoneStacks.put(id, 0);
                    runeDamage(attacker, victim, lerp(level, 40, 160), adaptiveType(champ));
                    pressActive.add(id); // +8 % de dégâts aux champions jusqu'à la sortie de combat
                    attacker.sendActionBar(Component.text("⚔ Attaque soutenue !", NamedTextColor.GOLD));
                }
            }
            case "dark_harvest" -> {
                // Champion sous 50 % : 30 (+11 par âme, +10 % AD bonus, +5 % AP) ; 35 s, 1 s après une élimination
                if (!cm.hasChampion(victim)) return;
                var hp = cm.getChampion(victim).getHPSystem();
                if (hp.getHPRatio() < 0.5 && tryRuneCooldown(id, "dark_harvest", 35_000L)) {
                    int souls = darkHarvestSouls.getOrDefault(id, 0);
                    double dmg = 30 + 11 * souls + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05;
                    runeDamage(attacker, victim, dmg, adaptiveType(champ));
                    darkHarvestSouls.put(id, souls + 1);
                    attacker.sendActionBar(Component.text("🌑 Moisson noire ! (" + (souls + 1) + " âmes)", NamedTextColor.DARK_PURPLE));
                }
            }
            case "hail_blades" -> {
                if (isAbility) return;
                // +90 % (60 % à distance) de vitesse d'attaque pour 3 attaques, 3 s max entre deux ;
                // 2-20 (+12 % AD bonus, +10 % AP) dégâts bruts à l'impact. Récupération : 10 s
                if (!isActive(hailUntil, id)) {
                    if (!tryRuneCooldown(id, "hail_blades", 10_000L)) return;
                    hailUntil.put(id, now + 3000);
                    hailHits.put(id, 0);
                    refresh(attacker);
                    refreshLater(attacker, 61L);
                    attacker.sendActionBar(Component.text("⚔ Déluge de lames !", NamedTextColor.RED));
                }
                runeDamage(attacker, victim, lerp(level, 2, 20) + s.getBonusAD() * 0.12 + s.getFinalAP() * 0.10,
                        DamageUtil.Type.TRUE);
                if (hailHits.merge(id, 1, Integer::sum) >= 3) {
                    hailUntil.remove(id);
                    refresh(attacker);
                } else {
                    hailUntil.put(id, now + 3000);
                    refreshLater(attacker, 61L);
                }
            }
            case "arcane_comet" -> {
                // Compétence : 15-100 (+5 % AP, +10 % AD bonus) adaptatifs, +100 % max selon la
                // distance (750 unités). Récupération : 20-8 s
                if (!isAbility || cometReadyAt.getOrDefault(id, 0L) > now) return;
                cometReadyAt.put(id, now + (long) (lerp(level, 20, 8) * 1000));
                double dist = attacker.getWorld().equals(victim.getWorld())
                        ? attacker.getLocation().distance(victim.getLocation()) : 0;
                double dmg = (lerp(level, 15, 100) + s.getFinalAP() * 0.05 + s.getBonusAD() * 0.10)
                        * (1 + Math.min(1.0, dist / 7.5));
                cometJustFired = true;
                runeDamage(attacker, victim, dmg, adaptiveType(champ));
                attacker.sendActionBar(Component.text("☄ Comète arcanique !", NamedTextColor.LIGHT_PURPLE));
            }
            case "summon_aery" -> {
                // Attaque ou compétence : 10-50 (+5 % AP, +10 % AD bonus) adaptatifs à l'arrivée d'Aery
                if (aeryAway.contains(id)) return;
                sendAery(attacker, victim, false, () -> {
                    if (!cm.hasChampion(attacker) || !alive(victim)) return;
                    var c = cm.getChampion(attacker);
                    var st = c.getStats();
                    double dmg = lerp(c.getLevelSystem().getLevel(), 10, 50) + st.getFinalAP() * 0.05 + st.getBonusAD() * 0.10;
                    runeDamage(attacker, victim, dmg, adaptiveType(c));
                });
            }
            case "deathfire_touch" -> {
                if (isAbility) burn(attacker, victim);
            }
            case "grasp_undying" -> {
                // Toutes les 4 s en combat, l'attaque suivante : 3.5 % PV max magiques, soin 1.3 %,
                // +5 PV max (à distance : 40 % d'efficacité, +2 PV)
                if (isAbility) return;
                Long lastCombat = champCombatLast.get(id);
                if (lastCombat == null || now - lastCombat > 5000) graspCombatStart.put(id, now);
                long since = Math.max(graspCombatStart.getOrDefault(id, now), graspLast.getOrDefault(id, 0L));
                if (now - since < 4000) return;
                graspLast.put(id, now);
                double r = ranged ? 0.4 : 1.0;
                double maxHP = champ.getHPSystem().getMaxHP();
                runeDamage(attacker, victim, maxHP * 0.035 * r, DamageUtil.Type.MAGICAL);
                champ.getHPSystem().heal(maxHP * 0.013 * r);
                graspHP.merge(id, ranged ? 2.0 : 5.0, Double::sum);
                refresh(attacker);
                attacker.sendActionBar(Component.text("🌿 Poigne de l'immortel !", NamedTextColor.GREEN));
            }
            case "fleet_footwork" -> {
                // Énergie : 6 par attaque (et 1 par 24 unités parcourues) ; à 100, l'attaque soigne
                // 15-160 (+10 % AD bonus, +5 % AP ; 60 % à distance) et donne +20 % de vitesse 1 s (75 % à distance)
                if (isAbility) return;
                int e = keystoneStacks.getOrDefault(id, 0);
                if (e >= 100) {
                    keystoneStacks.put(id, 0);
                    double heal = growthLerp(level, 15, 160) + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05;
                    champ.getHPSystem().heal(heal * (ranged ? 0.6 : 1.0));
                    var cc = LolPlugin.getInstance().getCCManager();
                    if (cc != null) cc.speedPct(attacker, 0.20 * (ranged ? 0.75 : 1.0), 20);
                    attacker.sendActionBar(Component.text("🏃 Jeu de jambes ! Soin + vitesse", NamedTextColor.GOLD));
                } else {
                    keystoneStacks.put(id, Math.min(100, e + 6));
                }
            }
            case "first_strike" -> {
                // Toucher un champion dans les 0.25 s qui suivent le début du combat : +10 PO, puis
                // +7 % de dégâts pendant 3 s. Récupération : 25-15 s
                Long last = champCombatLast.get(id);
                long start = last == null || now - last > 10_000 ? now : champCombatStart.getOrDefault(id, now);
                if (now - start <= 250 && tryRuneCooldown(id, "first_strike", (long) (lerp(level, 25, 15) * 1000))) {
                    firstStrikeUntil.put(id, now + 3000);
                    LolPlugin.getInstance().getGoldManager().addGold(id, 10);
                    attacker.sendActionBar(Component.text("💰 Premier coup !", NamedTextColor.GOLD));
                }
            }
            default -> {}
        }
    }

    /** Planifie l'expiration des charges de Conquérant / Tempo mortel après {@code ms} sans coup. */
    private void scheduleStackDecay(Player attacker, long ms) {
        final UUID id = attacker.getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
            Long last = keystoneLastProc.get(id);
            if (last == null || System.currentTimeMillis() - last < ms - 50) return; // rafraîchi entre-temps
            keystoneStacks.put(id, 0);
            refresh(attacker);
        }, ms / 50L + 1L);
    }

    /**
     * Toucher de feu mortel : 3-12 (+2.5 % AP, +7 % AD bonus) dégâts magiques par seconde (2 fois
     * par seconde) pendant 4 s ; après 3 s de brûlure continue, +75 %.
     */
    private void burn(Player attacker, Player victim) {
        String key = attacker.getUniqueId() + ":" + victim.getUniqueId();
        var cm = LolPlugin.getInstance().getChampionManager();
        long now = System.currentTimeMillis();
        var existing = burns.get(key);
        final long start = existing != null && !existing.isCancelled() && burnStart.containsKey(key) ? burnStart.get(key) : now;
        if (existing != null) existing.cancel();
        burnStart.put(key, start);
        burns.put(key, new org.bukkit.scheduler.BukkitRunnable() {
            int left = 8;
            @Override public void run() {
                if (left-- <= 0 || !alive(attacker) || !alive(victim)) {
                    burns.remove(key);
                    burnStart.remove(key);
                    cancel();
                    return;
                }
                var c = cm.getChampion(attacker);
                double dps = lerp(c.getLevelSystem().getLevel(), 3, 12)
                        + c.getStats().getFinalAP() * 0.025 + c.getStats().getBonusAD() * 0.07;
                if (System.currentTimeMillis() - start >= 3000) dps *= 1.75;
                runeDamage(attacker, victim, dps / 2, DamageUtil.Type.MAGICAL);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 10L, 10L));
    }

    /** Conquérant : à 12 effets, soigne de 8 % (5 % à distance) des dégâts infligés aux champions. */
    public void onConquerorHeal(Player attacker, double damageDealt) {
        if (procDepth > 0) return;
        if (!"conqueror".equals(getPage(attacker.getUniqueId()).keystone)) return;
        if (keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) >= 12) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(attacker)) {
                var c = cm.getChampion(attacker);
                c.getHPSystem().heal(damageDealt * (isRanged(c) ? 0.05 : 0.08));
            }
        }
    }

    /** Multiplicateur de dégâts des runes contre un champion (appelé par DamageUtil). */
    public double getDamageMultiplier(Player attacker, Player victim) {
        return getDamageMultiplier(attacker, victim, false);
    }

    // ── Source des dégâts (Arcaniste axiomatique) ──
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** Ultimes dont tous les dégâts visent une cible unique (les autres sont des dégâts de zone). */
    private static final Set<String> SINGLE_TARGET_ULTS = Set.of("veigar", "zed", "darius", "garen", "warwick");
    private static int forcedUltimate = 0, forcedUltimateSingle = 0, singleTargetDepth = 0;

    /** Dégâts infligés hors des classes de sort mais issus de l'ultime (attaques de Tibbers). */
    public static void beginUltimateSource(boolean singleTarget) {
        forcedUltimate++;
        if (singleTarget) forcedUltimateSingle++;
    }
    public static void endUltimateSource() {
        if (forcedUltimate > 0) forcedUltimate--;
        if (forcedUltimateSingle > forcedUltimate) forcedUltimateSingle = forcedUltimate;
    }
    /** Coup à cible unique d'un ultime de zone (Flèche de cristal : cible principale ; Furie du dragon : cible frappée). */
    public static void beginSingleTarget() { singleTargetDepth++; }
    public static void endSingleTarget() { if (singleTargetDepth > 0) singleTargetDepth--; }

    /**
     * Emplacement (1-4) du sort de {@code champ} qui inflige les dégâts en cours, ou 0 : la classe du
     * sort (ou une classe anonyme qu'elle contient) est recherchée dans la pile d'appels.
     */
    private static int sourceSlot(BaseChampion champ) {
        if (forcedUltimate > 0) return 4;
        return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .mapToInt(k -> slotOfClass(champ, k)).filter(i -> i > 0).findFirst().orElse(0));
    }

    private static int slotOfClass(BaseChampion champ, Class<?> k) {
        for (Class<?> c = k; c != null; c = c.getEnclosingClass())
            for (int i = 1; i <= 4; i++) {
                var ab = champ.getAbility(i);
                if (ab != null && ab.getClass() == c) return i;
            }
        return 0;
    }

    /** Arcaniste axiomatique : +12 % aux dégâts à cible unique de l'ultime, +8 % aux dégâts de zone. */
    private static double axiomAmp(BaseChampion champ) {
        boolean single = forcedUltimate > 0 ? forcedUltimateSingle > 0
                : singleTargetDepth > 0 || SINGLE_TARGET_ULTS.contains(champ.getId());
        return single ? 1.12 : 1.08;
    }

    /** Multiplicateur des soins et boucliers de l'ultime de {@code caster} ({@code area} : effet de zone). */
    public double ultimateHealShieldMultiplier(Player caster, boolean area) {
        if (!getPage(caster.getUniqueId()).has("axiom_arcanist")) return 1.0;
        return area ? 1.08 : 1.12;
    }

    /**
     * Multiplicateur de dégâts des runes contre un champion : Coup de grâce, Abattage, Baroud
     * d'honneur, Attaque soutenue, Premier coup, Arcaniste axiomatique (ultime).
     * {@code isAbility} : dégâts d'un champion (pas d'un objet).
     */
    public double getDamageMultiplier(Player attacker, Player victim, boolean isAbility) {
        RunePage page = getPage(attacker.getUniqueId());
        double mult = 1.0;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(victim) || !cm.hasChampion(attacker)) return mult;
        double victimRatio = cm.getChampion(victim).getHPSystem().getHPRatio();
        var ahp = cm.getChampion(attacker).getHPSystem();

        if (page.has("coup_grace") && victimRatio < 0.40) mult *= 1.08;
        if (page.has("cut_down") && victimRatio > 0.60) mult *= 1.08;
        if (page.has("last_stand")) {
            double r = ahp.getHPRatio();
            // 5 % à 60 % de PV, jusqu'à 11 % à 30 %
            if (r < 0.60) mult *= 1.0 + 0.05 + 0.06 * Math.min(1.0, (0.60 - r) / 0.30);
        }
        UUID id = attacker.getUniqueId();
        if (pressActive.contains(id)) {
            if (ahp.isInCombat()) mult *= 1.08;
            else pressActive.remove(id);
        }
        if (isActive(firstStrikeUntil, id)) mult *= 1.07;
        // Arcaniste axiomatique : dégâts de l'ultime +12 % (dégâts de zone : +8 %)
        if (page.has("axiom_arcanist") && isAbility && procDepth == 0) {
            var ac = cm.getChampion(attacker);
            if (sourceSlot(ac) == 4) mult *= axiomAmp(ac);
        }
        return mult;
    }

    // ══════════════════════════════════════════════════════════════
    // ÉLIMINATIONS
    // ══════════════════════════════════════════════════════════════

    /** Participation à l'élimination de {@code victim} (assistance). */
    public void onTakedown(Player player, Player victim) { onTakedown(player, victim, false); }

    /** Participation à l'élimination de {@code victim} ; {@code killer} : dernier coup. */
    public void onTakedown(Player player, Player victim, boolean killer) {
        RunePage page = getPage(player.getUniqueId());
        UUID id = player.getUniqueId();
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;
        var champ = cm.getChampion(player);
        var hp = champ.getHPSystem();
        var gold = LolPlugin.getInstance().getGoldManager();

        if (page.has("triumph")) {
            // 5 % des PV manquants + 2.5 % des PV max, +20 PO
            hp.heal((hp.getMaxHP() - hp.getCurrentHP()) * 0.05 + hp.getMaxHP() * 0.025);
            gold.addGold(id, 20);
        }
        // Présence d'esprit : 15 % du mana ou de l'énergie max, après 1 s
        if (page.has("presence_mind")) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
                if (cm.getChampion(player) != champ) return;
                var res = champ.getResourceSystem();
                if (res != null && (res.getType() == ResourceSystem.ResourceType.MANA
                        || res.getType() == ResourceSystem.ResourceType.ENERGY))
                    res.addCurrent(res.getMax() * 0.15);
            }, 20L);
        }
        // Chaussures magiques : bottes 45 s plus tôt
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (page.has("magical_footwear") && pm != null) {
            var st = pm.getState(player);
            st.magicalFootwearThresholdMs = Math.max(0, st.magicalFootwearThresholdMs - 45_000L);
        }
        // Moisson noire : récupération ramenée à 1 s
        if ("dark_harvest".equals(page.keystone)) {
            String key = id + ":dark_harvest";
            Long last = runeCooldowns.get(key);
            long now = System.currentTimeMillis();
            if (last != null && now - last < 34_000L) runeCooldowns.put(key, now - 34_000L);
        }
        // Effets « Chasseur de primes » : premier takedown sur chaque champion ennemi
        var set = uniqueTakedowns.computeIfAbsent(id, _ -> new HashSet<>());
        int before = set.size();
        boolean newBounty = victim != null && !victim.equals(player) && set.add(victim.getUniqueId());
        if (newBounty && page.has("treasure_hunter")) {
            int bonus = Math.min(130, 50 + 20 * before);
            gold.addGold(id, bonus);
            player.sendActionBar(Component.text("💰 Chasseur de trésors : +" + bonus + " PO", NamedTextColor.GOLD));
        }
        if (page.has("grisly_mementos")) mementos.merge(id, 1, (a, b) -> Math.min(18, a + b));
        legendProgress.merge(id, 100.0, Double::sum);
        // Arcaniste axiomatique : -7 % du délai actuel de l'ultime
        var ult = champ.getAbility(4);
        if (page.has("axiom_arcanist") && ult != null && ult.isOnCooldown(player))
            ult.reduceCooldown(player, ult.getRemainingCooldown(player) * 0.07);
        // Transcendance (niveau 11) : -20 % du délai restant des compétences de base
        if (page.has("transcendence") && champ.getLevelSystem().getLevel() >= 11) {
            for (int i = 1; i <= 3; i++) {
                var ab = champ.getAbility(i);
                if (ab != null && ab.isOnCooldown(player)) ab.reduceCooldown(player, ab.getRemainingCooldown(player) * 0.20);
            }
        }
        // Absorption vitale : seulement en tuant
        if (killer && page.has("absorb_life")) absorbLife(player);
        refresh(player);
    }

    /**
     * Mort d'un sbire ou d'un monstre : Légende (progression du tueur), Absorption vitale,
     * Surcroissance (champions proches). Appelé par EntityDeathListener.
     */
    public void onUnitDeath(LivingEntity dead, Player killer, boolean monster, boolean epic) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (killer != null && cm.hasChampion(killer)) {
            // Progression Légende (LoL) : sbire 4 %, grand monstre 25 %, monstre épique 100 %
            legendProgress.merge(killer.getUniqueId(), epic ? 100.0 : monster ? 25.0 : 4.0, Double::sum);
            if (getPage(killer.getUniqueId()).has("absorb_life")) absorbLife(killer);
        }
        // Surcroissance : sbires ennemis ou monstres morts à moins de 1400 unités
        var tm = LolPlugin.getInstance().getTeamManager();
        var minionTeam = monster ? null : fr.lolmc.game.MinionManager.getMinionTeam(dead);
        for (Player p : dead.getWorld().getPlayers()) {
            if (!getPage(p.getUniqueId()).has("overgrowth") || !cm.hasChampion(p)) continue;
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (!monster && (tm == null || minionTeam == null || tm.getTeam(p) == minionTeam)) continue;
            if (p.getLocation().distanceSquared(dead.getLocation()) > 14 * 14) continue;
            overgrowthCount.merge(p.getUniqueId(), 1, Integer::sum);
        }
    }

    /**
     * Absorption vitale : tuer une cible rend 1-23 PV (1 au niveau 1, +0.25 par niveau, +1 par
     * niveau dès le 6, +2 par niveau dès le 11).
     */
    private void absorbLife(Player p) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        var c = cm.getChampion(p);
        int lvl = Math.min(18, c.getLevelSystem().getLevel());
        double heal = lvl < 6 ? 1 + 0.25 * (lvl - 1) : lvl < 11 ? 2 + (lvl - 5) : 7 + 2 * (lvl - 10);
        c.getHPSystem().heal(heal);
    }

    /** Remise immédiate : 7.5 % du prix des objets légendaires rendus. */
    public void onLegendaryPurchase(Player player, int price) {
        if (!getPage(player.getUniqueId()).has("cash_back")) return;
        int back = (int) Math.round(price * 0.075);
        if (back <= 0) return;
        LolPlugin.getInstance().getGoldManager().refundGold(player.getUniqueId(), back);
        player.sendMessage(Component.text("💰 Remise immédiate : +" + back + " PO", NamedTextColor.GOLD));
    }

    public void cleanup(UUID uuid) {
        var cm = LolPlugin.getInstance().getChampionManager();
        var p = org.bukkit.Bukkit.getPlayer(uuid);
        if (p != null && cm.hasChampion(p)) cm.getChampion(p).getStats().setShieldListener(null);
        active.remove(uuid);
        applied.remove(uuid);
        appliedChamp.remove(uuid);
        keystoneStacks.remove(uuid);
        keystoneLastProc.remove(uuid);
        String prefix = uuid + ":";
        runeCooldowns.keySet().removeIf(k -> k.startsWith(prefix));
        conquerorSpellStack.keySet().removeIf(k -> k.startsWith(prefix));
        uniqueTakedowns.remove(uuid);
        legendProgress.remove(uuid);
        overgrowthCount.remove(uuid);
        manaflowBonus.remove(uuid);
        mementos.remove(uuid);
        darkHarvestSouls.remove(uuid);
        graspHP.remove(uuid); graspLast.remove(uuid); graspCombatStart.remove(uuid);
        hailUntil.remove(uuid); hailHits.remove(uuid);
        phaseRushUntil.remove(uuid); firstStrikeUntil.remove(uuid);
        aftershockUntil.remove(uuid); aftershockResists.remove(uuid); unflinchingUntil.remove(uuid);
        pressTarget.remove(uuid); pressActive.remove(uuid);
        electrocuteHits.remove(uuid);
        dashedAt.remove(uuid);
        champCombatStart.remove(uuid); champCombatLast.remove(uuid);
        cometReadyAt.remove(uuid); aeryAway.remove(uuid);
        bonePlating.remove(uuid); guarded.remove(uuid); chilled.remove(uuid); ccSource.remove(uuid);
        shieldBashAmount.remove(uuid); shieldBashUntil.remove(uuid);
        lastPos.remove(uuid); tonicsGiven.remove(uuid); inRiver.remove(uuid);
        spellbookReadyAt.remove(uuid); spellbookUnique.remove(uuid); spellbookRecent.remove(uuid); spellbookSwaps.remove(uuid);
        glacialZones.removeIf(z -> z.owner().equals(uuid));
        phaseRushDamage.keySet().removeIf(k -> k.startsWith(prefix));
        burns.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(prefix)) return false;
            e.getValue().cancel();
            return true;
        });
        burnStart.keySet().removeIf(k -> k.startsWith(prefix));
        var sw = secondWindTasks.remove(uuid);
        if (sw != null) sw.cancel();
    }

    /**
     * Applique les données de rune reçues depuis le lobby via BridgeManager.
     * @param data Map contenant "keystone", "minors", "spell1", "spell2"
     */
    public void applyKeystoneFromBridge(Player player,
                                        String keystone,
                                        Map<String, String> data) {
        RunePage page = getPage(player.getUniqueId());
        page.keystone = keystone;
        // Le lobby n'envoie pas les voies : les déduire de la keystone et des runes secondaires
        var ks = RuneRegistry.get(keystone);
        if (ks != null) page.primaryPath = ks.path();
        String minorsStr = data.getOrDefault("minors", "");
        if (!minorsStr.isEmpty()) {
            String[] parts = minorsStr.split(",");
            page.primaryRunes.clear();
            page.secondaryRunes.clear();
            for (int i = 0; i < Math.min(parts.length, 5); i++) {
                String rune = parts[i].trim();
                if (rune.isEmpty()) continue;
                if (i < 3) page.primaryRunes.add(rune);
                else page.secondaryRunes.add(rune);
            }
            for (String r : page.secondaryRunes) {
                var sr = RuneRegistry.get(r);
                if (sr != null) { page.secondaryPath = sr.path(); break; }
            }
        }
        page.sanitize();
        playerPages.put(player.getUniqueId(), page);
        applyRuneStats(player); // sans risque : les bonus ne se cumulent plus
        player.sendMessage(Component.text(
            "✔ Runes appliquées depuis le lobby!", NamedTextColor.GREEN));
    }
}
