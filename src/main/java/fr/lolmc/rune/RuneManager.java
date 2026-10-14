package fr.lolmc.rune;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Gère les pages de runes des joueurs et applique leurs effets pendant la partie (patch 26.20).
 *
 * Bonus de stats : recalculés chaque seconde (et à chaque changement) par {@link #refresh(Player)}.
 * Chaque bonus est mémorisé par clé et seul l'écart est appliqué : réappliquer une page ne double
 * plus les stats, et les runes qui dépendent du niveau, des PV ou du temps de partie se mettent
 * à jour toutes seules (Concentration absolue, Conditionnement, Tempête menaçante, Légende…).
 */
public class RuneManager {

    // Page de runes par joueur (page active)
    private final Map<UUID, RunePage> playerPages = new HashMap<>();
    // Fichier de sauvegarde des pages
    private final java.io.File runeFile;
    private final org.bukkit.configuration.file.FileConfiguration runeConfig;

    // Stacks de keystones par joueur (Conquérant, Électrocution, etc.)
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
    private final Map<UUID, Long> graspLast = new HashMap<>();
    /** Fins d'effets temporaires (Déluge de lames, Assaut du maraudeur, Premier coup). */
    private final Map<UUID, Long> hailUntil = new HashMap<>(), phaseRushUntil = new HashMap<>(), firstStrikeUntil = new HashMap<>();
    private final Map<UUID, Integer> hailHits = new HashMap<>();
    /** Attaque soutenue : cible des coups consécutifs, et bonus actif jusqu'à la sortie de combat. */
    private final Map<UUID, UUID> pressTarget = new HashMap<>();
    private final Set<UUID> pressActive = new HashSet<>();
    /** Électrocution : sources distinctes (attaque / sort) touchées dans la fenêtre de 3 s. */
    private final Map<UUID, Set<String>> electrocuteHits = new HashMap<>();
    /** Assaut du maraudeur : dégâts infligés à une cible dans la fenêtre de 4 s. */
    private final Map<String, double[]> phaseRushDamage = new HashMap<>(); // {dégâts, début}
    /** Toucher de feu mortel : brûlures actives (clé "attaquant:victime"). */
    private final Map<String, org.bukkit.scheduler.BukkitTask> burns = new HashMap<>();
    // Second Souffle : tâche de soin active par joueur (annulée avant chaque relance, pas de double-cumul)
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> secondWindTasks = new HashMap<>();

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

    private enum Stat { AD, AP, AH, ULT_HASTE, AS, HP, ARMOR, MR, LIFESTEAL, MS, MS_PCT, TENACITY, SLOW_RESIST, HEAL_AMP, MANA }

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

    /** Toutes les secondes : bonus dynamiques, Ruban de mana, Sixième sens. */
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
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
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

        // ── Fragments ──
        shard(want, id, champ, "shard1", page.statShard1, t);
        shard(want, id, champ, "shard2", page.statShard2, t);
        shard(want, id, champ, "shard3", page.statShard3, t);

        // ── Keystones à effets cumulés ──
        int ks = keystoneStacks.getOrDefault(id, 0);
        if ("conqueror".equals(page.keystone) && ks > 0)
            adaptive(want, id, champ, "conqueror", ks * (1.08 + 1.16 * t), ks * (1.8 + 1.93 * t));
        if ("lethal_tempo".equals(page.keystone) && ks > 0) put(want, "lethal_tempo", Stat.AS, ks * 0.08);
        if ("hail_blades".equals(page.keystone) && isActive(hailUntil, id))
            put(want, "hail_blades", Stat.AS, isRanged(champ) ? 0.60 : 0.90);
        if ("phase_rush".equals(page.keystone) && isActive(phaseRushUntil, id)) {
            put(want, "phase_rush", Stat.MS_PCT, (isRanged(champ) ? 0.30 : 0.40) + 0.20 * t);
            put(want, "phase_rush", Stat.SLOW_RESIST, 0.75);
        }
        if ("grasp_undying".equals(page.keystone)) put(want, "grasp_undying", Stat.HP, graspHP.getOrDefault(id, 0.0));

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
        if (page.has("relentless_hunter") && !hp.isInCombat())
            put(want, "relentless_hunter", Stat.MS, 10 + 8 * bounty);
        if (page.has("transcendence")) put(want, "transcendence", Stat.AH, (lvl >= 5 ? 5 : 0) + (lvl >= 8 ? 5 : 0));
        if (page.has("celerity")) put(want, "celerity", Stat.MS_PCT, 0.01);
        if (page.has("absolute_focus") && hp.getHPRatio() > 0.70)
            adaptive(want, id, champ, "absolute_focus", 1.8 + 16.2 * t, 3 + 27 * t);
        if (page.has("gathering_storm")) {
            int n = (int) (elapsed / 600); // paliers de 10 min : 8, 24, 48, 80, 120, 168… AP
            double ap = 8.0 * n * (n + 1) / 2;
            if (ap > 0) adaptive(want, id, champ, "gathering_storm", ap * 0.6, ap);
        }
        if (page.has("conditioning") && elapsed >= 720) {
            put(want, "conditioning", Stat.ARMOR, 8 + (s.getFinalArmor() - appliedValue(id, "conditioning", Stat.ARMOR) + 8) * 0.03);
            put(want, "conditioning", Stat.MR, 8 + (s.getFinalMagicResist() - appliedValue(id, "conditioning", Stat.MR) + 8) * 0.03);
        }
        if (page.has("overgrowth")) {
            int n = overgrowthCount.getOrDefault(id, 0);
            double bonus = 3 * Math.min(n, 120) / 8;
            if (n >= 120) bonus += (s.getFinalMaxHP() - appliedValue(id, "overgrowth", Stat.HP)) * 0.035;
            put(want, "overgrowth", Stat.HP, bonus);
        }
        if (page.has("unflinching")) {
            put(want, "unflinching", Stat.TENACITY, 0.10);
            put(want, "unflinching", Stat.SLOW_RESIST, 0.10);
        }
        if (page.has("revitalize")) put(want, "revitalize", Stat.HEAL_AMP, 0.05);
        if (page.has("manaflow_band")) put(want, "manaflow_band", Stat.MANA, manaflowBonus.getOrDefault(id, 0.0));
        if (page.has("jack_of_all_trades")) {
            int kinds = Math.min(10, itemStatKinds(player));
            put(want, "jack_of_all_trades", Stat.AH, kinds);
            double force = kinds >= 10 ? 20 : kinds >= 5 ? 8 : 0;
            if (force > 0) adaptive(want, id, champ, "jack_of_all_trades", force * 0.6, force);
        }

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
            case TENACITY -> s.addTenacity(d);
            case SLOW_RESIST -> s.addSlowResist(d);
            case HEAL_AMP -> s.addRuneHealAmp(d);
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

    private static boolean isRanged(BaseChampion champ) { return champ.getAutoAttackRange() > 4.0; }

    private static double lerp(int lvl, double at1, double at18) { return at1 + (at18 - at1) * (lvl - 1) / 17.0; }

    private void refreshLater(Player p, long ticks) {
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> refresh(p), ticks);
    }

    /** Dégâts infligés par une rune : ne déclenchent pas d'autres effets de runes. */
    private void runeDamage(Player attacker, Player victim, double amount, DamageUtil.Type type) {
        procDepth++;
        try { DamageUtil.damage(attacker, victim, amount, true, type); }
        finally { procDepth--; }
    }

    /** Type adaptatif des dégâts d'une rune (physique si plus d'AD bonus que d'AP). */
    private static DamageUtil.Type adaptiveType(BaseChampion champ) {
        var s = champ.getStats();
        return s.getBonusAD() > s.getBonusAP() ? DamageUtil.Type.PHYSICAL : DamageUtil.Type.MAGICAL;
    }

    // ══════════════════════════════════════════════════════════════
    // ACCÉLÉRATION DE SORT D'INVOCATEUR, RUBAN DE MANA, SIXIÈME SENS
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

    /** Ruban de mana au maximum : 1% du mana manquant toutes les 5 s. */
    private void manaflowRegen(Player p) {
        if (!getPage(p.getUniqueId()).has("manaflow_band") || manaflowBonus.getOrDefault(p.getUniqueId(), 0.0) < 250) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        var res = cm.getChampion(p).getResourceSystem();
        if (res != null && res.getType() == ResourceSystem.ResourceType.MANA)
            res.addCurrent((res.getMax() - res.getCurrent()) * 0.01);
    }

    /** Sixième sens : repère une balise ennemie proche (250 s) ; niveau 11 : la révèle 10 s. */
    private void sixthSense(Player p) {
        if (!getPage(p.getUniqueId()).has("sixth_sense")) return;
        var wm = LolPlugin.getInstance().getWardManager();
        var tm = LolPlugin.getInstance().getTeamManager();
        var cm = LolPlugin.getInstance().getChampionManager();
        if (wm == null || tm == null || !cm.hasChampion(p) || !tm.hasTeam(p)) return;
        var myTeam = tm.getTeam(p);
        for (var w : wm.getAllWards()) {
            if (w.team == myTeam || w.entityId == null || wm.isRevealed(w)) continue;
            if (!w.location.getWorld().equals(p.getWorld()) || w.location.distanceSquared(p.getLocation()) > 10 * 10) continue;
            if (!tryRuneCooldown(p.getUniqueId(), "sixth_sense", 250_000L)) return;
            if (cm.getChampion(p).getLevelSystem().getLevel() >= 11)
                wm.revealEnemyWards(p, w.location, 0.5, 10_000L);
            p.sendActionBar(Component.text(String.format("👁 Sixième sens : balise ennemie à %.0f blocs",
                    w.location.distance(p.getLocation())), NamedTextColor.LIGHT_PURPLE));
            return;
        }
    }

    /** Balise de profondeur : durée bonus des balises posées côté ennemi (rivière au niveau 9). */
    public int deepWardBonusSeconds(Player owner, Location loc) {
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
        return deep ? (int) Math.round(lerp(lvl, 30, 45)) : 0;
    }

    // ══════════════════════════════════════════════════════════════
    // DÉGÂTS SUBIS
    // ══════════════════════════════════════════════════════════════

    /**
     * Effets de runes au moment où le porteur SUBIT des dégâts.
     * (Second Souffle, Plaque d'os)
     */
    public void onDamageTaken(Player victim, double amount, boolean isMagical) {
        RunePage page = getPage(victim.getUniqueId());
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(victim)) return;
        var champ = cm.getChampion(victim);

        // Plaque d'os : les 3 prochaines attaques ennemies infligent 30-60 dégâts de moins (55 s)
        if (page.has("bone_plating") && tryRuneCooldown(victim.getUniqueId(), "bone_plating", 55_000L)) {
            int lvl = champ.getLevelSystem().getLevel();
            // Simulé par un bouclier de 3 × (30-60 selon le niveau) pendant 1,5 s
            double bpShield = 3 * lerp(lvl, 30, 60);
            var bpStats = champ.getStats();
            bpStats.addShield(bpShield);
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() { bpStats.addShield(-bpShield); } // seulement ce bouclier
            }.runTaskLater(LolPlugin.getInstance(), 30L);
        }

        // Second Souffle : soin 3 + 4% PV manquants sur 10s après avoir pris des dégâts d'un
        // champion ennemi. Un nouveau dégât relance le soin (pas deux tâches simultanées).
        if (page.has("second_wind")) {
            var existing = secondWindTasks.remove(victim.getUniqueId());
            if (existing != null) existing.cancel();
            double heal = 3 + (champ.getHPSystem().getMaxHP() - champ.getHPSystem().getCurrentHP()) * 0.04;
            var task = new org.bukkit.scheduler.BukkitRunnable() {
                int t = 0;
                @Override public void run() {
                    if (t++ >= 10 || !victim.isOnline() || !cm.hasChampion(victim)) {
                        secondWindTasks.remove(victim.getUniqueId());
                        cancel();
                        return;
                    }
                    cm.getChampion(victim).getHPSystem().heal(heal / 10.0);
                }
            }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
            secondWindTasks.put(victim.getUniqueId(), task);
        }
        // Les PV ont changé : Concentration absolue suit le seuil de 70 %
        if (page.has("absolute_focus")) refresh(victim);
    }

    /**
     * Manteau nuageux : après un sort d'invocateur, +25% vitesse de mouvement pendant 2s.
     * (Le vrai taux varie selon le délai du sort ; simplifié à un taux médian unique.)
     */
    public void applyNimbusCloak(Player caster) {
        var page = getPage(caster.getUniqueId());
        if (!page.has("nimbus_cloak")) return;
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(caster)) return;
        var stats = cm.getChampion(caster).getStats();
        double bonusMs = stats.getFinalMovementSpeed() * 0.25;
        stats.addBonusMoveSpeed(bonusMs);
        caster.sendActionBar(Component.text("☁ Manteau nuageux !", NamedTextColor.AQUA));
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (cm.hasChampion(caster)) stats.addBonusMoveSpeed(-bonusMs);
            }
        }.runTaskLater(LolPlugin.getInstance(), 40L);
    }

    // ══════════════════════════════════════════════════════════════
    // DÉGÂTS INFLIGÉS
    // ══════════════════════════════════════════════════════════════

    public void onHitEffects(Player attacker, Player victim, double damage, boolean isAbility) {
        if (procDepth > 0) return;
        RunePage page = getPage(attacker.getUniqueId());
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return;
        var champ = cm.getChampion(attacker);
        int level = champ.getLevelSystem().getLevel();
        var res = champ.getResourceSystem();
        boolean mana = res != null && res.getType() == ResourceSystem.ResourceType.MANA;

        // Ruban de mana : +25 mana max par compétence touchant un champion (15 s), 250 max
        // (avant : +250 PV d'office, et 5 % de la ressource rendus)
        if (page.has("manaflow_band") && isAbility && mana
                && manaflowBonus.getOrDefault(attacker.getUniqueId(), 0.0) < 250
                && tryRuneCooldown(attacker.getUniqueId(), "manaflow_band", 15_000L)) {
            manaflowBonus.merge(attacker.getUniqueId(), 25.0, Double::sum);
            refresh(attacker);
        }
        // Présence d'esprit : blesser un champion rend 6-50 mana (8 s)
        if (page.has("presence_mind") && mana && tryRuneCooldown(attacker.getUniqueId(), "presence_mind", 8_000L))
            res.addCurrent(lerp(level, 6, 50) * (isRanged(champ) ? 0.8 : 1.0));
        // Brûlure : toutes les 10 s, la prochaine compétence inflige 20-40 dégâts magiques
        if (page.has("scorch") && isAbility && tryRuneCooldown(attacker.getUniqueId(), "scorch", 10_000L))
            runeDamage(attacker, victim, lerp(level, 20, 40), DamageUtil.Type.MAGICAL);
        // Premier coup : 50 % (35 % à distance) des dégâts bonus convertis en PO
        if (isActive(firstStrikeUntil, attacker.getUniqueId())) {
            int gold = (int) Math.round(damage * (0.07 / 1.07) * (isRanged(champ) ? 0.35 : 0.50));
            if (gold > 0) LolPlugin.getInstance().getGoldManager().addGold(attacker.getUniqueId(), gold);
        }
        // Assaut du maraudeur : 25 % des PV max d'une cible en 4 s
        if ("phase_rush".equals(page.keystone)) {
            String key = attacker.getUniqueId() + ":" + victim.getUniqueId();
            long now = System.currentTimeMillis();
            double[] acc = phaseRushDamage.get(key);
            if (acc == null || now - acc[1] > 4000) phaseRushDamage.put(key, acc = new double[]{0, now});
            acc[0] += damage;
            if (cm.hasChampion(victim) && acc[0] >= cm.getChampion(victim).getHPSystem().getMaxHP() * 0.25
                    && tryRuneCooldown(attacker.getUniqueId(), "phase_rush", 15_000L)) {
                phaseRushDamage.remove(key);
                phaseRushUntil.put(attacker.getUniqueId(), now + 3000);
                refresh(attacker);
                refreshLater(attacker, 61L);
                attacker.sendActionBar(Component.text("💨 Assaut du maraudeur !", NamedTextColor.AQUA));
            }
        }
        // Coup bas et Goût du sang : gérés dans PassiveManager.onAbilityDamage
    }

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

        switch (page.keystone) {
            case "conqueror" -> {
                // 2 effets par attaque à distance… simplifié : 1 effet par coup, 12 max, 5 s
                keystoneStacks.merge(id, 1, (a, b) -> Math.min(12, a + b));
                keystoneLastProc.put(id, now);
                refresh(attacker);
                scheduleStackDecay(attacker, 5000L);
            }
            case "lethal_tempo" -> {
                if (isAbility) return; // seules les attaques de base comptent
                keystoneStacks.merge(id, 1, (a, b) -> Math.min(6, a + b));
                keystoneLastProc.put(id, now);
                refresh(attacker);
                scheduleStackDecay(attacker, 6000L);
            }
            case "electrocute" -> {
                // 3 attaques ou compétences DIFFÉRENTES en 3 s (avant : 3 coups quelconques)
                Long first = keystoneLastProc.get(id);
                if (first == null || now - first > 3000L) {
                    electrocuteHits.put(id, new HashSet<>());
                    keystoneLastProc.put(id, now);
                }
                var hits = electrocuteHits.computeIfAbsent(id, _ -> new HashSet<>());
                hits.add(isAbility ? "ability:" + hits.size() : "attack");
                if (hits.size() >= 3 && tryRuneCooldown(id, "electrocute", (long) lerp(level, 25_000, 20_000))) {
                    hits.clear();
                    double dmg = lerp(level, 30, 220) + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05;
                    runeDamage(attacker, victim, dmg, adaptiveType(champ));
                    attacker.sendActionBar(Component.text("⚡ Électrocution !", NamedTextColor.YELLOW));
                }
            }
            case "press_attack" -> {
                if (isAbility) return;
                // 3 attaques consécutives sur le MÊME champion (4 s entre deux)
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
                if (!cm.hasChampion(victim)) return;
                var hp = cm.getChampion(victim).getHPSystem();
                if (hp.getHPRatio() < 0.5 && tryRuneCooldown(id, "dark_harvest", 35_000L)) {
                    int souls = darkHarvestSouls.merge(id, 1, Integer::sum) - 1;
                    double dmg = lerp(level, 20, 60) + 5 * souls + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05;
                    runeDamage(attacker, victim, dmg, adaptiveType(champ));
                    attacker.sendActionBar(Component.text("🌑 Moisson noire ! (" + (souls + 1) + " âmes)", NamedTextColor.DARK_PURPLE));
                }
            }
            case "hail_blades" -> {
                if (isAbility) return;
                // 3 attaques avec beaucoup de vitesse d'attaque + dégâts bruts (10 s de récupération)
                // (avant : effet de minage HASTE, sans effet sur les attaques)
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
                    hailUntil.put(id, now + 3000); // 3 s max entre deux attaques
                }
            }
            case "arcane_comet" -> {
                if (isAbility && tryRuneCooldown(id, "arcane_comet", (long) lerp(level, 20_000, 8_000))) {
                    runeDamage(attacker, victim, lerp(level, 30, 130) + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05,
                            DamageUtil.Type.MAGICAL);
                    attacker.sendActionBar(Component.text("☄ Comète arcanique !", NamedTextColor.LIGHT_PURPLE));
                }
            }
            case "summon_aery" -> {
                // Dégâts à un ennemi (Aery doit revenir : ~3 s)
                if (tryRuneCooldown(id, "summon_aery", 3_000L)) {
                    double dmg = lerp(level, 10, 50) + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05;
                    runeDamage(attacker, victim, dmg, DamageUtil.Type.MAGICAL);
                }
            }
            case "deathfire_touch" -> {
                if (isAbility) burn(attacker, victim);
            }
            case "grasp_undying" -> {
                // Toutes les 4 s en combat, l'attaque suivante : 3,5 % PV max, soin 1,3 %, +5 PV max
                // (60 % à distance). Avant : toutes les 4 attaques, soin égal aux dégâts.
                if (isAbility) return;
                Long last = graspLast.get(id);
                if (last != null && now - last < 4000) return;
                graspLast.put(id, now);
                double r = isRanged(champ) ? 0.6 : 1.0;
                double maxHP = champ.getHPSystem().getMaxHP();
                runeDamage(attacker, victim, maxHP * 0.035 * r, DamageUtil.Type.MAGICAL);
                champ.getHPSystem().heal(maxHP * 0.013 * r);
                graspHP.merge(id, 5 * r, Double::sum);
                refresh(attacker);
                attacker.sendActionBar(Component.text("🌿 Poigne de l'immortel !", NamedTextColor.GREEN));
            }
            case "fleet_footwork" -> {
                // 100 énergie accumulée par AA → soin + vitesse
                if (isAbility) return;
                int stacks = keystoneStacks.merge(id, 20, (a, b) -> Math.min(100, a + b));
                if (stacks == 100) {
                    keystoneStacks.put(id, 0);
                    champ.getHPSystem().heal(lerp(level, 10, 100) + s.getBonusAD() * 0.10 + s.getFinalAP() * 0.05);
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 0, false, true));
                    attacker.sendActionBar(Component.text("🏃 Jeu de jambes ! Soin + vitesse", NamedTextColor.GOLD));
                }
            }
            case "first_strike" -> {
                // En engageant le combat : +10 PO, puis +7 % de dégâts pendant 3 s (25-15 s)
                if (!champ.getHPSystem().isInCombat()
                        && tryRuneCooldown(id, "first_strike", (long) lerp(level, 25_000, 15_000))) {
                    firstStrikeUntil.put(id, now + 3000);
                    LolPlugin.getInstance().getGoldManager().addGold(id, 10);
                    attacker.sendActionBar(Component.text("💰 Premier coup !", NamedTextColor.GOLD));
                }
            }
            case "glacial_augment" -> {
                // Ralentit la cible (7 s de récupération par cible ; avant : à chaque attaque)
                if (!isAbility && tryRuneCooldown(id, "glacial:" + victim.getUniqueId(), 7_000L)) {
                    LolPlugin.getInstance().getCCManager().slow(victim, 40, 1);
                    fr.lolmc.util.VisualEffectUtil.impactBurst(victim.getWorld(),
                            victim.getLocation().add(0, 1, 0), Material.LIGHT_BLUE_STAINED_GLASS, 0.24f, 0.5, 6, 6L);
                    attacker.sendActionBar(Component.text("❄ Optimisation glaciale !", NamedTextColor.AQUA));
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

    /** Toucher de feu mortel : brûlure de 4 s, +75 % après 3 s de brûlure continue. */
    private void burn(Player attacker, Player victim) {
        String key = attacker.getUniqueId() + ":" + victim.getUniqueId();
        var cm = LolPlugin.getInstance().getChampionManager();
        long now = System.currentTimeMillis();
        var existing = burns.get(key);
        final long start = existing != null && !existing.isCancelled() && burnStart.containsKey(key) ? burnStart.get(key) : now;
        if (existing != null) existing.cancel();
        burnStart.put(key, start);
        burns.put(key, new org.bukkit.scheduler.BukkitRunnable() {
            int left = 4;
            @Override public void run() {
                if (left-- <= 0 || !attacker.isOnline() || !victim.isOnline()
                        || !cm.hasChampion(attacker) || !cm.hasChampion(victim)
                        || victim.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    burns.remove(key);
                    burnStart.remove(key);
                    cancel();
                    return;
                }
                var c = cm.getChampion(attacker);
                double dps = lerp(c.getLevelSystem().getLevel(), 3, 12)
                        + c.getStats().getFinalAP() * 0.025 + c.getStats().getBonusAD() * 0.07;
                if (System.currentTimeMillis() - start >= 3000) dps *= 1.75;
                runeDamage(attacker, victim, dps, DamageUtil.Type.MAGICAL);
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L));
    }
    private final Map<String, Long> burnStart = new HashMap<>();

    /** Conquérant : soigne quand on a 12 stacks et qu'on inflige des dégâts. */
    public void onConquerorHeal(Player attacker, double damageDealt) {
        if (procDepth > 0) return;
        if (!"conqueror".equals(getPage(attacker.getUniqueId()).keystone)) return;
        if (keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) >= 12) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(attacker)) {
                cm.getChampion(attacker).getHPSystem().heal(damageDealt * 0.08);
            }
        }
    }

    /** Multiplicateur de dégâts des runes contre un champion (appelé par DamageUtil). */
    public double getDamageMultiplier(Player attacker, Player victim) {
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
            // 5 % à 60 % de PV, jusqu'à 11 % à 30 % (avant : 11 % fixes, et jamais appliqué)
            if (r < 0.60) mult *= 1.0 + 0.05 + 0.06 * Math.min(1.0, (0.60 - r) / 0.30);
        }
        UUID id = attacker.getUniqueId();
        if (pressActive.contains(id)) {
            if (ahp.isInCombat()) mult *= 1.08;
            else pressActive.remove(id);
        }
        if (isActive(firstStrikeUntil, id)) mult *= 1.07;
        return mult;
    }

    // ══════════════════════════════════════════════════════════════
    // ÉLIMINATIONS
    // ══════════════════════════════════════════════════════════════

    /** Takedown (kill ou assist) sur {@code victim} (null si inconnue). */
    public void onTakedown(Player player, Player victim) {
        RunePage page = getPage(player.getUniqueId());
        UUID id = player.getUniqueId();
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;
        var champ = cm.getChampion(player);
        var hp = champ.getHPSystem();
        var gold = LolPlugin.getInstance().getGoldManager();

        if (page.has("triumph")) {
            // 5 % des PV manquants + 2,5 % des PV max, +20 PO (avant : 12 % manquants, +25 PO)
            hp.heal((hp.getMaxHP() - hp.getCurrentHP()) * 0.05 + hp.getMaxHP() * 0.025);
            gold.addGold(id, 20);
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
        if (page.has("absorb_life")) absorbLife(player);
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
        // Surcroissance : sbires ennemis ou monstres morts à moins de 14 blocs
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

    /** Absorption vitale : tuer une cible rend 18-35 PV (selon le niveau). */
    private void absorbLife(Player p) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        var c = cm.getChampion(p);
        c.getHPSystem().heal(lerp(c.getLevelSystem().getLevel(), 18, 35));
    }

    /** Remise immédiate : 7,5 % du prix des objets légendaires rendus. */
    public void onLegendaryPurchase(Player player, int price) {
        if (!getPage(player.getUniqueId()).has("cash_back")) return;
        int back = (int) Math.round(price * 0.075);
        if (back <= 0) return;
        LolPlugin.getInstance().getGoldManager().refundGold(player.getUniqueId(), back);
        player.sendMessage(Component.text("💰 Remise immédiate : +" + back + " PO", NamedTextColor.GOLD));
    }

    public void cleanup(UUID uuid) {
        active.remove(uuid);
        applied.remove(uuid);
        appliedChamp.remove(uuid);
        keystoneStacks.remove(uuid);
        keystoneLastProc.remove(uuid);
        runeCooldowns.keySet().removeIf(k -> k.startsWith(uuid.toString()));
        uniqueTakedowns.remove(uuid);
        legendProgress.remove(uuid);
        overgrowthCount.remove(uuid);
        manaflowBonus.remove(uuid);
        mementos.remove(uuid);
        darkHarvestSouls.remove(uuid);
        graspHP.remove(uuid);
        graspLast.remove(uuid);
        hailUntil.remove(uuid); hailHits.remove(uuid);
        phaseRushUntil.remove(uuid); firstStrikeUntil.remove(uuid);
        pressTarget.remove(uuid); pressActive.remove(uuid);
        electrocuteHits.remove(uuid);
        String prefix = uuid + ":";
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
