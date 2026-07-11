package fr.lolmc.rune;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RuneRegistry;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les pages de runes des joueurs et applique leurs effets pendant la partie.
 *
 * Les runes les plus impactantes (keystones, runes de combat) sont implémentées
 * avec leurs effets. Beaucoup de runes mineures appliquent des bonus passifs
 * directement aux stats à l'application de la page.
 */
public class RuneManager {

    // Page de runes par joueur (page active)
    private final Map<UUID, RunePage> playerPages = new HashMap<>();
    // Fichier de sauvegarde des pages
    private final java.io.File runeFile;
    private org.bukkit.configuration.file.FileConfiguration runeConfig;

    // Stacks de keystones par joueur (Conqueror, Electrocute, etc.)
    private final Map<UUID, Integer> keystoneStacks = new HashMap<>();
    private final Map<UUID, Long> keystoneLastProc = new HashMap<>();
    // Gathering Storm : bonus adaptatif croissant avec le temps de partie
    private final Map<UUID, Double> gatheringStormBonus = new HashMap<>();
    // Relentless Hunter : stacks de kills uniques (un stack par champion différent tué)
    private final Map<UUID, java.util.Set<UUID>> relentlessKills = new HashMap<>();

    public RuneManager() {
        this.runeFile = new java.io.File(LolPlugin.getInstance().getDataFolder(), "runes.yml");
        if (!runeFile.exists()) {
            try { runeFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.runeConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(runeFile);
        loadAllPages();
        startGatheringStormTask();
    }

    /** Tâche Tempête Montante : toutes les 10 minutes, +8 force adaptative (+8 AD ou +14 AP). */
    private void startGatheringStormTask() {
        new org.bukkit.scheduler.BukkitRunnable() {
            int minutes = 0;
            @Override public void run() {
                minutes += 10;
                double bonus = switch (minutes) {
                    case 10 -> 8;
                    case 20 -> 24;
                    case 30 -> 48;
                    case 40 -> 80;
                    case 50 -> 120;
                    default -> minutes > 50 ? 120 + (minutes - 50) * 8 : 0;
                };
                var cm = LolPlugin.getInstance().getChampionManager();
                for (var entry : playerPages.entrySet()) {
                    if (!entry.getValue().has("gathering_storm")) continue;
                    var p = org.bukkit.Bukkit.getPlayer(entry.getKey());
                    if (p == null || !cm.hasChampion(p)) continue;
                    double prev = gatheringStormBonus.getOrDefault(entry.getKey(), 0.0);
                    double delta = bonus - prev;
                    if (delta > 0) {
                        gatheringStormBonus.put(entry.getKey(), bonus);
                        cm.getChampion(p).getStats().addBonusAD(delta);
                        p.sendActionBar(Component.text(
                            String.format("⛈ Tempête Montante: +%.0f AD (%dmin)", bonus, minutes),
                            NamedTextColor.AQUA));
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 12000L, 12000L); // toutes les 10 minutes
    }

    // ── Gestion des pages ─────────────────────────────────────────

    public void setPage(UUID uuid, RunePage page) {
        playerPages.put(uuid, page);
        savePage(uuid, page);
    }

    /** Sauvegarde la page d'un joueur dans runes.yml. */
    private void savePage(UUID uuid, RunePage page) {
        String base = "pages." + uuid + ".";
        runeConfig.set(base + "primaryPath", page.primaryPath != null ? page.primaryPath.name() : null);
        runeConfig.set(base + "keystone", page.keystone);
        runeConfig.set(base + "primaryRunes", new java.util.ArrayList<>(page.primaryRunes));
        runeConfig.set(base + "secondaryPath", page.secondaryPath != null ? page.secondaryPath.name() : null);
        runeConfig.set(base + "secondaryRunes", new java.util.ArrayList<>(page.secondaryRunes));
        runeConfig.set(base + "shard1", page.statShard1);
        runeConfig.set(base + "shard2", page.statShard2);
        runeConfig.set(base + "shard3", page.statShard3);
        try { runeConfig.save(runeFile); }
        catch (Exception e) { LolPlugin.getInstance().getLogger().warning("Erreur runes.yml: " + e.getMessage()); }
    }

    /** Charge toutes les pages sauvegardées au démarrage. */
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
                playerPages.put(uuid, page);
            } catch (Exception ignored) {}
        }
    }

    public RunePage getPage(UUID uuid) {
        return playerPages.getOrDefault(uuid, RunePage.defaultPage());
    }

    /**
     * Applique les bonus passifs des runes (stats) au champion.
     * Appelé au début de la partie ou à l'assignation du champion.
     */
    public void applyRuneStats(Player player) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(player)) return;
        BaseChampion champ = cm.getChampion(player);
        RunePage page = getPage(player.getUniqueId());

        // Fragments de stats (toujours actifs)
        applyStatShard(champ, page.statShard1);
        applyStatShard(champ, page.statShard2);
        applyStatShard(champ, page.statShard3);

        // Runes mineures à effet passif sur les stats
        for (String runeId : page.allActiveRunes()) {
            applyPassiveRune(champ, runeId);
        }

        player.sendActionBar(Component.text("✨ Runes appliquées", NamedTextColor.LIGHT_PURPLE));
    }

    private void applyStatShard(BaseChampion champ, String shard) {
        switch (shard) {
            case "adaptive" -> champ.getStats().addBonusAD(5.4);      // +5.4 AD (ou 9 AP adaptatif)
            case "attack_speed" -> champ.getStats().multiplyAS(1.10); // +10% AS
            case "ability_haste" -> champ.getStats().addBonusAbilityHaste(8);
            case "armor" -> champ.getStats().addBonusArmor(6);
            case "mr" -> champ.getStats().addBonusMR(8);
            case "health" -> champ.getStats().addBonusHP(65);
            default -> {}
        }
    }

    /** Applique les runes à effet passif permanent sur les stats. */
    private void applyPassiveRune(BaseChampion champ, String runeId) {
        var s = champ.getStats();
        switch (runeId) {
            // ── PRÉCISION ──
            case "legend_alacrity" -> s.multiplyAS(1.10);        // +10% vitesse d'attaque
            case "legend_haste" -> s.addBonusAbilityHaste(10);   // +hâte
            case "legend_bloodline" -> s.addBonusOmnivamp(0.06); // +6% omnivamp
            // ── DOMINATION ──
            case "eyeball_collection" -> s.addBonusAD(6);        // force adaptative par takedowns (simplifié: flat)
            case "ravenous_hunter" -> { /* omnivamp géré on-damage */ }
            case "ultimate_hunter" -> s.addBonusAbilityHaste(6); // hâte de l'ultime
            case "relentless_hunter" -> s.addBonusMoveSpeed(5); // +5 MS de base, croît avec kills
            // ── SORCELLERIE ──
            case "transcendence" -> s.addBonusAbilityHaste(5);
            case "absolute_focus" -> s.addBonusAD(3);
            case "gathering_storm" -> { /* bonus appliqué dans tickGatheringStorm() */ }
            case "manaflow_band" -> s.addBonusHP(250); // simule le bonus de mana converti
            case "scorch" -> { /* dégâts à implémenter dans onAbilityDamage */ }
            // ── DÉTERMINATION ──
            case "conditioning" -> { s.addBonusArmor(9); s.addBonusMR(9); } // +résistances
            case "overgrowth" -> s.addBonusHP(100); // bonus de base
            case "second_wind" -> { /* géré dans onDamageTaken */ }
            case "bone_plating" -> { /* géré dans onDamageTaken via state */ }
            case "font_life" -> { /* soin alliés déclenché dans CCManager.stun/root */ }
            // ── INSPIRATION ──
            case "cosmic_insight" -> s.addBonusAbilityHaste(18); // +hâte invocateur/objets (approx)
            case "absolute_focus_alt" -> {}
            default -> {}
        }
    }

    /**
     * Effets de runes au moment où le porteur SUBIT des dégâts.
     * (Second Souffle, Plaques Osseuses, Réplique...)
     */
    public void onDamageTaken(Player victim, double amount) {
        RunePage page = getPage(victim.getUniqueId());
        // Bone Plating : réduit les dégâts des 3 prochaines attaques ennemies de 30-60
        if (page.has("bone_plating")) {
            var cm3 = LolPlugin.getInstance().getChampionManager();
            if (cm3.hasChampion(victim)) {
                int lvl3 = cm3.getChampion(victim).getLevelSystem().getLevel();
                // Simulé par une réduction de 30 + 2*lvl dégâts absorbés via bouclier temporaire
                double bpShield = Math.min(amount, 30 + lvl3 * 2.0);
                cm3.getChampion(victim).getStats().addShield(bpShield);
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override public void run() {
                        if (cm3.hasChampion(victim)) cm3.getChampion(victim).getStats().clearShields();
                    }
                }.runTaskLater(LolPlugin.getInstance(), 20L);
            }
        }

        // Second Wind : soin 6 + 4% PV manquants pendant 10s après avoir pris des dégâts
        if (page.has("second_wind")) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(victim)) {
                var hp = cm.getChampion(victim).getHPSystem();
                double heal = 6 + (hp.getMaxHP() - hp.getCurrentHP()) * 0.04;
                new org.bukkit.scheduler.BukkitRunnable() { int t = 0;
                    @Override public void run() {
                        if (t++ >= 10 || !victim.isOnline()) { cancel(); return; }
                        if (cm.hasChampion(victim)) cm.getChampion(victim).getHPSystem().heal(heal/10.0);
                    }
                }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
            }
        }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(victim)) return;
        var champ = cm.getChampion(victim);

        if (page.has("second_wind")) {
            // Soigne 6% PV manquants sur 10s après avoir subi des dégâts (simplifié: soin direct léger)
            new org.bukkit.scheduler.BukkitRunnable() {
                int ticks = 0;
                @Override public void run() {
                    if (ticks >= 5 || !victim.isOnline()) { cancel(); return; }
                    double missing = champ.getHPSystem().getMaxHP() - champ.getHPSystem().getCurrentHP();
                    champ.getHPSystem().heal(missing * 0.012);
                    ticks++;
                }
            }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
        }
        if (page.has("bone_plating")) {
            // Les 3 prochaines sources de dégâts sont réduites (marqueur simplifié: petit bouclier)
            champ.getStats().addFlatDamageReduction(8);
        }
    }

    /**
     * Effets on-hit additionnels (vol de vie, mana, dégâts de sort).
     */
    public void onHitEffects(Player attacker, Player victim, double damage, boolean isAbility) {
        RunePage page = getPage(attacker.getUniqueId());
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(attacker)) return;
        var champ = cm.getChampion(attacker);

        if (page.has("legend_bloodline")) {
            champ.getHPSystem().heal(damage * 0.04); // ~4% vol de vie
        }
        if (page.has("ravenous_hunter")) {
            champ.getHPSystem().heal(damage * 0.03); // omnivampirisme
        }
        if (page.has("manaflow_band") && isAbility) {
            champ.getResourceSystem().fill(); // restaure un peu de ressource (simplifié)
        }
        if (page.has("scorch") && isAbility) {
            // Premier sort touchant inflige des dégâts bonus (CD 10s géré via stacks)
            long now = System.currentTimeMillis();
            Long last = keystoneLastProc.get(attacker.getUniqueId());
            if (last == null || (now - last) > 10000) {
                keystoneLastProc.put(attacker.getUniqueId(), now);
                int level = champ.getLevelSystem().getLevel();
                DamageUtil.damage(attacker, victim, 15 + level * 1.5, true, DamageUtil.Type.MAGICAL);
            }
        }
        if (page.has("cheap_shot")) {
            // Dégâts vrais bonus si la cible est sous contrôle (ralentie/immobilisée)
            if (victim.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                DamageUtil.damage(attacker, victim, 10, false, DamageUtil.Type.TRUE);
            }
        }
        if (page.has("taste_blood")) {
            champ.getHPSystem().heal(8); // petit soin en touchant un champion
        }
    }

    // ── Effets de combat (keystones) ──────────────────────────────

    /**
     * Appelé quand le joueur attaque ou touche un champion avec un sort.
     * Gère les keystones à stacks (Conqueror, Electrocute, Press the Attack...).
     */
    public void onDamageToChampion(Player attacker, Player victim, boolean isAbility) {
        RunePage page = getPage(attacker.getUniqueId());
        if (page.keystone == null) return;
        long now = System.currentTimeMillis();

        switch (page.keystone) {
            case "conqueror" -> {
                // Stacks de force adaptative, max 12
                int stacks = Math.min(12, keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) + 1);
                keystoneStacks.put(attacker.getUniqueId(), stacks);
                keystoneLastProc.put(attacker.getUniqueId(), now);
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(attacker)) {
                    cm.getChampion(attacker).getStats().addBonusAD(0.8); // +AD par stack
                }
            }
            case "electrocute" -> {
                // 3 procs distincts en 3s → dégâts
                int stacks = keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) + 1;
                keystoneStacks.put(attacker.getUniqueId(), stacks);
                if (stacks >= 3) {
                    keystoneStacks.put(attacker.getUniqueId(), 0);
                    int level = LolPlugin.getInstance().getChampionManager()
                            .getChampion(attacker).getLevelSystem().getLevel();
                    double dmg = 30 + level * 10; // dégâts adaptatifs croissants
                    DamageUtil.damage(attacker, victim, dmg, true, DamageUtil.Type.MAGICAL);
                    attacker.sendActionBar(Component.text("⚡ Électrocution!", NamedTextColor.YELLOW));
                }
            }
            case "press_attack" -> {
                int stacks = keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) + 1;
                keystoneStacks.put(attacker.getUniqueId(), stacks);
                if (stacks >= 3) {
                    keystoneStacks.put(attacker.getUniqueId(), 0);
                    int level = LolPlugin.getInstance().getChampionManager()
                            .getChampion(attacker).getLevelSystem().getLevel();
                    DamageUtil.damage(attacker, victim, 40 + level * 8, false, DamageUtil.Type.PHYSICAL);
                    // Expose la cible (+8% dégâts subis) — marqueur via effet
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 120, 0, false, false));
                }
            }
            case "dark_harvest" -> {
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(victim)) {
                    var hp = cm.getChampion(victim).getHPSystem();
                    if (hp.getCurrentHP() / hp.getMaxHP() < 0.5) {
                        DamageUtil.damage(attacker, victim, 40, true, DamageUtil.Type.MAGICAL);
                        attacker.sendActionBar(Component.text("🌑 Moisson Noire!", NamedTextColor.DARK_PURPLE));
                    }
                }
            }
            case "arcane_comet" -> {
                if (isAbility) {
                    int level = LolPlugin.getInstance().getChampionManager()
                            .getChampion(attacker).getLevelSystem().getLevel();
                    DamageUtil.damage(attacker, victim, 30 + level * 5, true, DamageUtil.Type.MAGICAL);
                }
            }
            case "phase_rush" -> {
                int stacks = keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) + 1;
                keystoneStacks.put(attacker.getUniqueId(), stacks);
                if (stacks >= 3) {
                    keystoneStacks.put(attacker.getUniqueId(), 0);
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 75, 2, false, true));
                    attacker.sendActionBar(Component.text("💨 Ruée de Phase! 75% vitesse 3.75s", NamedTextColor.AQUA));
                }
            }
            case "grasp_undying" -> {
                // 4 AA/sorts sur champion → AA renforcée (3.5% HP max) + 5 HP max permanent
                int stacks = keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) + 1;
                keystoneStacks.put(attacker.getUniqueId(), stacks);
                if (stacks >= 4) {
                    keystoneStacks.put(attacker.getUniqueId(), 0);
                    var cm = LolPlugin.getInstance().getChampionManager();
                    if (cm.hasChampion(attacker)) {
                        var champ = cm.getChampion(attacker);
                        double graspDmg = champ.getStats().getFinalMaxHP() * 0.035;
                        DamageUtil.damage(attacker, victim, graspDmg, true, DamageUtil.Type.MAGICAL);
                        champ.getHPSystem().heal(graspDmg);
                        champ.getStats().addBonusHP(5); // +5 HP max permanent
                        attacker.sendActionBar(Component.text("🌿 Emprise! +5 HP permanent", NamedTextColor.GREEN));
                    }
                }
            }
            case "fleet_footwork" -> {
                // 100 énergie accumulée par AA/mouvement → AA bonus soin + vitesse
                int stacks = Math.min(100, keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) + 20);
                keystoneStacks.put(attacker.getUniqueId(), stacks);
                if (stacks >= 100) {
                    keystoneStacks.put(attacker.getUniqueId(), 0);
                    var cm = LolPlugin.getInstance().getChampionManager();
                    if (cm.hasChampion(attacker)) {
                        int lvl = cm.getChampion(attacker).getLevelSystem().getLevel();
                        double heal = 10 + lvl * 4;
                        cm.getChampion(attacker).getHPSystem().heal(heal);
                        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 30, 0, false, true));
                        attacker.sendActionBar(Component.text("🏃 Jeu de Jambes! Soin + vitesse", NamedTextColor.GOLD));
                    }
                }
            }
            case "lethal_tempo" -> {
                // Stacks de vitesse d'attaque sur champion (max 6), dure 6s
                int stacks = Math.min(6, keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) + 1);
                keystoneStacks.put(attacker.getUniqueId(), stacks);
                keystoneLastProc.put(attacker.getUniqueId(), System.currentTimeMillis());
                // +13% AS par stack (total +78% AS à 6 stacks)
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(attacker)) {
                    cm.getChampion(attacker).getStats().addBonusAttackSpeed(13);
                    new org.bukkit.scheduler.BukkitRunnable() {
                        @Override public void run() {
                            if (cm.hasChampion(attacker))
                                cm.getChampion(attacker).getStats().addBonusAttackSpeed(-13);
                        }
                    }.runTaskLater(LolPlugin.getInstance(), 120L); // 6s
                }
            }
            case "hail_blades" -> {
                // 3 attaques rapides sur champion (ignore AS normale pendant 2s)
                Long last = keystoneLastProc.get(attacker.getUniqueId());
                if (last == null || System.currentTimeMillis() - last > 8000L) {
                    keystoneLastProc.put(attacker.getUniqueId(), System.currentTimeMillis());
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 40, 2, false, true));
                    attacker.sendActionBar(Component.text("⚔ Grêle de Lames! AS max 2s", NamedTextColor.RED));
                }
            }
            case "summon_aery" -> {
                // Dégâts à un ennemi ou bouclier sur un allié
                if (isAbility) {
                    var cm = LolPlugin.getInstance().getChampionManager();
                    if (cm.hasChampion(attacker)) {
                        int lvl = cm.getChampion(attacker).getLevelSystem().getLevel();
                        double dmg = 8 + lvl * 4 + cm.getChampion(attacker).getStats().getFinalAD() * 0.10
                                      + cm.getChampion(attacker).getStats().getFinalAP() * 0.25;
                        DamageUtil.damage(attacker, victim, dmg, true, DamageUtil.Type.MAGICAL);
                        attacker.sendActionBar(Component.text("🎐 Aery envoyée!", NamedTextColor.YELLOW));
                    }
                }
            }
            case "first_strike" -> {
                // Premier hit hors combat → dégâts bonus + or
                Long last = keystoneLastProc.get(attacker.getUniqueId());
                var acm = LolPlugin.getInstance().getChampionManager();
                if (last == null || System.currentTimeMillis() - last > 5000L) {
                    if (acm.hasChampion(attacker) && !acm.getChampion(attacker).getHPSystem().isInCombat()) {
                        keystoneLastProc.put(attacker.getUniqueId(), System.currentTimeMillis());
                        double bonusDmg = acm.getChampion(attacker).getStats().getFinalAD() * 0.12
                                        + acm.getChampion(attacker).getStats().getFinalAP() * 0.20;
                        DamageUtil.damage(attacker, victim, bonusDmg, true, DamageUtil.Type.MAGICAL);
                        // +or proportionnel aux dégâts
                        int gold = (int)(bonusDmg * 0.35);
                        LolPlugin.getInstance().getGoldManager().addGold(attacker.getUniqueId(), gold);
                        attacker.sendActionBar(Component.text(
                            String.format("💰 Premier Coup! +%.0f dégâts +%d or", bonusDmg, gold), NamedTextColor.GOLD));
                    }
                }
            }
            case "predator" -> {
                // Actif : +60% vitesse 10s, à l'attaque = dégâts de charge
                // (Implémenté comme passif ON, non actif pour simplifier)
                Long last = keystoneLastProc.get(attacker.getUniqueId());
                if (last == null || System.currentTimeMillis() - last > 150_000L) { // CD 150s
                    keystoneLastProc.put(attacker.getUniqueId(), System.currentTimeMillis());
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 3, false, true));
                    int lvl = LolPlugin.getInstance().getChampionManager().hasChampion(attacker)
                        ? LolPlugin.getInstance().getChampionManager().getChampion(attacker).getLevelSystem().getLevel() : 1;
                    DamageUtil.damage(attacker, victim, 40 + lvl * 20, true, DamageUtil.Type.MAGICAL);
                    attacker.sendActionBar(Component.text("🏃 PREDATEUR! Frappe de charge!", NamedTextColor.RED));
                }
            }
            case "glacial_augment" -> {
                // AA ralentit 30% + crée des zones gelées
                if (!isAbility) {
                    LolPlugin.getInstance().getCCManager().slow(victim, 30, 40);
                    fr.lolmc.util.VisualEffectUtil.impactBurst(victim.getWorld(),
                            victim.getLocation().add(0,1,0), Material.LIGHT_BLUE_STAINED_GLASS, 0.24f, 0.5, 6, 6L);
                    attacker.sendActionBar(Component.text("❄ Augment Glacial!", NamedTextColor.AQUA));
                }
            }
            default -> {}
        }
    }

    /** Conqueror : soigne quand on a 12 stacks et qu'on inflige des dégâts. */
    public void onConquerorHeal(Player attacker, double damageDealt) {
        if (!"conqueror".equals(getPage(attacker.getUniqueId()).keystone)) return;
        if (keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) >= 12) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(attacker)) {
                cm.getChampion(attacker).getHPSystem().heal(damageDealt * 0.08);
            }
        }
    }

    /** Coup de Grâce / Cut Down : modificateurs de dégâts selon les PV. */
    public double getDamageMultiplier(Player attacker, Player victim) {
        RunePage page = getPage(attacker.getUniqueId());
        double mult = 1.0;
        var cm = LolPlugin.getInstance().getChampionManager();

        if (page.has("coup_grace") && cm.hasChampion(victim)) {
            var hp = cm.getChampion(victim).getHPSystem();
            if (hp.getCurrentHP() / hp.getMaxHP() < 0.40) mult *= 1.08; // +8% sous 40% PV
        }
        if (page.has("last_stand") && cm.hasChampion(attacker)) {
            var hp = cm.getChampion(attacker).getHPSystem();
            if (hp.getCurrentHP() / hp.getMaxHP() < 0.60) mult *= 1.11; // +11% si bas PV
        }
        return mult;
    }

    /** Triumph : soin + or sur takedown. */
    public void onTakedown(Player player) {
        RunePage page = getPage(player.getUniqueId());
        if (page.has("triumph")) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(player)) {
                var hp = cm.getChampion(player).getHPSystem();
                hp.heal((hp.getMaxHP() - hp.getCurrentHP()) * 0.12); // 12% PV manquants
            }
            LolPlugin.getInstance().getGoldManager().addGold(player.getUniqueId(), 25);
        }
        if (page.has("absorb_life")) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(player)) {
                cm.getChampion(player).getHPSystem().heal(cm.getChampion(player).getHPSystem().getMaxHP() * 0.05);
            }
        }
        // Relentless Hunter : +5 MS hors combat par champion unique tué (max +45 à 8 stacks)
        if (page.has("relentless_hunter")) {
            var kills = relentlessKills.computeIfAbsent(player.getUniqueId(), k -> new java.util.HashSet<>());
            // Ajouter la victime si on peut l'identifier
            var cm2 = LolPlugin.getInstance().getChampionManager();
            // On cherche la cible dans les entités proches (approximation)
            for (var e : player.getNearbyEntities(20,20,20)) {
                if (e instanceof Player vp && !vp.equals(player) && cm2.hasChampion(vp)) {
                    kills.add(vp.getUniqueId());
                }
            }
            int stacks = Math.min(8, kills.size());
            int msBonus = 5 + stacks * 5; // +5 base +5/stack unique
            if (cm2.hasChampion(player)) {
                // Recalculer depuis 0 (reset puis re-apply pour éviter accumulation)
                var stats = cm2.getChampion(player).getStats();
                int prev = relentlessKills.getOrDefault(player.getUniqueId(), new java.util.HashSet<>()).size();
                if (stacks > prev) stats.addBonusMoveSpeed(5); // +5 par stack gagné
            }
        }
    }

    public void cleanup(UUID uuid) {
        keystoneStacks.remove(uuid);
        keystoneLastProc.remove(uuid);
        gatheringStormBonus.remove(uuid);
        relentlessKills.remove(uuid);
    }


    /**
     * Applique les données de rune reçues depuis le lobby via BridgeManager.
     * @param data Map contenant "keystone", "minors", "spell1", "spell2"
     */
    public void applyKeystoneFromBridge(org.bukkit.entity.Player player,
                                         String keystone,
                                         java.util.Map<String, String> data) {
        RunePage page = getPage(player.getUniqueId());
        page.keystone = keystone;
        String minorsStr = data.getOrDefault("minors", "");
        if (!minorsStr.isEmpty()) {
            String[] parts = minorsStr.split(",");
            for (int i = 0; i < Math.min(parts.length, 6); i++) {
//                 if (i < page.minors.length) page.minors[i] = parts[i].trim();
            }
        }
        playerPages.put(player.getUniqueId(), page);
        applyRuneStats(player);
        player.sendMessage(net.kyori.adventure.text.Component.text(
            "✔ Runes appliquées depuis le lobby!", net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }


}