package fr.lolmc.rune;

import fr.lolmc.LolPlugin;
import fr.lolmc.rune.RuneRegistry;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    public RuneManager() {
        this.runeFile = new java.io.File(LolPlugin.getInstance().getDataFolder(), "runes.yml");
        if (!runeFile.exists()) {
            try { runeFile.createNewFile(); } catch (Exception ignored) {}
        }
        this.runeConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(runeFile);
        loadAllPages();
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
            case "legend_bloodline" -> { /* vol de vie géré on-hit */ }
            // ── DOMINATION ──
            case "eyeball_collection" -> s.addBonusAD(6);        // force adaptative par takedowns (simplifié: flat)
            case "ravenous_hunter" -> { /* omnivamp géré on-damage */ }
            case "ultimate_hunter" -> s.addBonusAbilityHaste(6); // hâte de l'ultime
            case "relentless_hunter" -> { /* vitesse hors combat, géré dynamiquement */ }
            // ── SORCELLERIE ──
            case "transcendence" -> s.addBonusAbilityHaste(5);
            case "absolute_focus" -> s.addBonusAD(3);
            case "gathering_storm" -> { /* croissance avec le temps, géré dynamiquement */ }
            case "manaflow_band" -> { /* mana géré on-hit */ }
            case "scorch" -> { /* dégâts géré on-ability */ }
            // ── DÉTERMINATION ──
            case "conditioning" -> { /* +résistances après 12min, géré dynamiquement */ }
            case "overgrowth" -> { /* PV par monstres morts, géré dynamiquement */ }
            case "second_wind" -> { /* soin après dégâts, géré on-damage-taken */ }
            case "bone_plating" -> { /* réduction dégâts, géré on-damage-taken */ }
            case "font_life" -> { /* soin de zone */ }
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
            case "phase_rush", "press_attack_alt" -> {
                int stacks = keystoneStacks.getOrDefault(attacker.getUniqueId(), 0) + 1;
                keystoneStacks.put(attacker.getUniqueId(), stacks);
                if (stacks >= 3) {
                    keystoneStacks.put(attacker.getUniqueId(), 0);
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 2, false, true));
                    attacker.sendActionBar(Component.text("💨 Ruée de Phase!", NamedTextColor.AQUA));
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
    }

    public void cleanup(UUID uuid) {
        keystoneStacks.remove(uuid);
        keystoneLastProc.remove(uuid);
    }
}
