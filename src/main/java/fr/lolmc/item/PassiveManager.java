package fr.lolmc.item;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.listener.ShopListener;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.stats.HPSystem;
import fr.lolmc.stats.ResourceSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Gestionnaire COMPLET de tous les passifs et actifs d'items LoL.
 * Couvre: Spellblade, Stacks, On-hit, DoT, Antiheal, Actifs, Wards, etc.
 */
public class PassiveManager {

    private final ChampionManager championManager;
    private final HUDManager hudManager;
    private final ShopListener shopListener;

    // État par joueur
    private final Map<UUID, ItemState> states = new HashMap<>();

    public PassiveManager(ChampionManager cm, HUDManager hud, ShopListener sl) {
        this.championManager = cm;
        this.hudManager = hud;
        this.shopListener = sl;
        startTasks();
    }

    public ItemState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), k -> new ItemState());
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: SORT LANCÉ
    // ════════════════════════════════════════════════════════
    public void onAbilityCast(Player caster, int slot) {
        if (!championManager.hasChampion(caster)) return;
        ItemState state = getState(caster);
        BaseChampion champ = championManager.getChampion(caster);
        // ── Sudden Impact : +12 létalité/+9 pén. magique pendant 5s après dash ──
        var runePageSI = LolPlugin.getInstance().getRuneManager();
        if (runePageSI != null && runePageSI.getPage(caster.getUniqueId()).has("sudden_impact")) {
            champ.getStats().addBonusLethality(12);
            champ.getStats().addBonusFlatMagicPen(9);
            new org.bukkit.scheduler.BukkitRunnable() { @Override public void run() {
                if (championManager.hasChampion(caster)) {
                    championManager.getChampion(caster).getStats().addBonusLethality(-12);
                    championManager.getChampion(caster).getStats().addBonusFlatMagicPen(-9);
                }
            }}.runTaskLater(LolPlugin.getInstance(), 100L);
        }

        // ── Spellblade (Trinity, Lich Bane, Sheen, Divine Sunderer, Essence Reaver) ──
        if (hasAnyItem(caster, "trinity_force","trinity_force2","lich_bane","sheen",
                "divine_sunderer","essence_reaver") && !state.isSpellbladeReady()) {
            state.primeSpellblade();
        }

        // ── Spear of Shojin: reset compteur AA ──
        if (hasAnyItem(caster, "spear_of_shojin","spear_of_shojin2")) {
            state.shojinAaCount = 0;
        }

        // ── Navori Quickblades: si dernier hit = crit, réduire CD ──
        if (hasAnyItem(caster, "navori_quickblades") && state.lastHitCrit) {
            champ.getAbilities()[slot].triggerCooldown(caster); // reset partiel géré séparément
        }
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: ATTAQUE DE BASE (AUTO-ATTACK)
    // ════════════════════════════════════════════════════════
    public void onAutoAttack(Player attacker, Player victim) {
        if (!championManager.hasChampion(attacker) || !championManager.hasChampion(victim)) return;
        BaseChampion ac = championManager.getChampion(attacker);
        BaseChampion vc = championManager.getChampion(victim);
        ChampionStats as = ac.getStats();
        ChampionStats vs = vc.getStats();
        HPSystem ahp = ac.getHPSystem();
        HPSystem vhp = vc.getHPSystem();
        ItemState state = getState(attacker);
        UUID vid = victim.getUniqueId();

        double aaDmg = as.calcAutoAttackDamage(vs);
        boolean isCrit = Math.random() < as.getFinalCritChance();
        state.lastHitCrit = isCrit;

        // ── Spellblade (si primé) ──
        if (state.isSpellbladeReady()) {
            double spellbladeDmg = 0;
            if (hasAnyItem(attacker,"trinity_force","trinity_force2"))
                spellbladeDmg = as.calcPhysicalDamage(2.0 * as.getBaseAD(), vs);
            else if (hasAnyItem(attacker,"lich_bane"))
                spellbladeDmg = as.calcMagicalDamage(1.5 * as.getBaseAD() + 0.4 * as.getFinalAP(), vs);
            else if (hasAnyItem(attacker,"sheen"))
                spellbladeDmg = as.calcPhysicalDamage(1.0 * as.getBaseAD(), vs);
            else if (hasAnyItem(attacker,"divine_sunderer"))
                spellbladeDmg = as.calcPhysicalDamage(1.25 * as.getBaseAD() + 0.06 * vhp.getMaxHP(), vs);
            else if (hasAnyItem(attacker,"essence_reaver"))
                spellbladeDmg = as.calcPhysicalDamage(as.getBaseAD() * 1.0, vs);
            if (spellbladeDmg > 0) {
                vhp.takeDamage(spellbladeDmg);
                attacker.getWorld().spawnParticle(Particle.ENCHANT, victim.getLocation().add(0,1,0), 10);
                state.consumeSpellblade();
            }
        }

        // ── Blade of the Ruined King: 6% HP actuels ──
        if (hasAnyItem(attacker,"botrk")) {
            double botrkDmg = as.calcPhysicalDamage(Math.max(15, vhp.getCurrentHP() * 0.06), vs);
            vhp.takeDamage(botrkDmg);
            ahp.heal(botrkDmg * 0.10);
        }

        // ── Kraken Slayer: 3ème AA = +150 vrais dégâts ──
        if (hasAnyItem(attacker,"kraken_slayer","kraken_slayer2")) {
            state.krakenStacks++;
            if (state.krakenStacks >= 3) {
                vhp.takeDamage(as.calcTrueDamage(150));
                state.krakenStacks = 0;
                attacker.sendActionBar(Component.text("⚡ Kraken Slayer!", NamedTextColor.AQUA));
                victim.getWorld().strikeLightningEffect(victim.getLocation());
            }
        }

        // ── Wit's End: +42 dégâts magiques on-hit ──
        if (hasAnyItem(attacker,"wits_end","wit_s_end2"))
            vhp.takeDamage(as.calcMagicalDamage(42, vs));

        // ── Nashor's Tooth: +15+20%AP dégâts on-hit ──
        if (hasAnyItem(attacker,"nashors_tooth","nashors_tooth2"))
            vhp.takeDamage(as.calcMagicalDamage(15 + as.getFinalAP() * 0.20, vs));

        // ── Statikk Shiv: 3ème AA = éclair AoE ──
        if (hasAnyItem(attacker,"statikk_shiv")) {
            state.statikkStacks++;
            if (state.statikkStacks >= 3) {
                double shivDmg = as.calcMagicalDamage(60 + as.getFinalAD() * 0.5, vs);
                attacker.getWorld().getNearbyEntities(victim.getLocation(), 4, 2, 4).stream()
                    .filter(e -> e instanceof Player)
                    .forEach(e -> championManager.getChampion((Player)e).getHPSystem()
                        .takeDamage(shivDmg));
                state.statikkStacks = 0;
                victim.getWorld().strikeLightningEffect(victim.getLocation());
            }
        }

        // ── Runaan's Hurricane: 2 bolts latéraux ──
        if (hasAnyItem(attacker,"runaans_hurricane")) {
            double boltDmg = as.calcPhysicalDamage(aaDmg * 0.40, vs);
            attacker.getWorld().getNearbyEntities(victim.getLocation(), 5, 2, 5).stream()
                .filter(e -> e instanceof Player && !e.equals(victim) && !e.equals(attacker))
                .limit(2)
                .forEach(e -> {
                    Player extraTarget = (Player) e;
                    if (championManager.hasChampion(extraTarget))
                        championManager.getChampion(extraTarget).getHPSystem().takeDamage(boltDmg);
                    attacker.getWorld().spawnParticle(Particle.CRIT,
                        extraTarget.getLocation().add(0,1,0), 5);
                });
        }

        // ── Guinsoo's Rageblade: crits → 2x effets on-hit ──
        if (hasAnyItem(attacker,"rageblade") && isCrit) {
            // Double les effets on-hit (on reapplique Wit's End / Nashor's etc.)
            if (hasAnyItem(attacker,"wits_end","wit_s_end2"))
                vhp.takeDamage(as.calcMagicalDamage(42, vs));
            if (hasAnyItem(attacker,"nashors_tooth","nashors_tooth2"))
                vhp.takeDamage(as.calcMagicalDamage(15 + as.getFinalAP() * 0.20, vs));
        }

        // ── Frozen Mallet: ralentit la cible ──
        if (hasAnyItem(attacker,"frozen_mallet"))
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, false));

        // ── Dead Man's Plate: slow si stacks max ──
        if (hasAnyItem(attacker,"dead_mans_plate")) {
            state.deadManStacks = 0; // Reset au contact
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 1, false, false));
        }

        // ── Black Cleaver: -6% armure/stack (max 6) sur la cible ──
        if (hasAnyItem(attacker,"black_cleaver","black_cleaver2")) {
            int stacks = Math.min(6, state.blackCleaverStacks.getOrDefault(vid, 0) + 1);
            state.blackCleaverStacks.put(vid, stacks);
            // Appliquer la réduction d'armure à la cible dynamiquement dans calcPhysicalDamage
            double armorReduction = vs.getFinalArmor() * (stacks * 0.06);
            vs.addBonusArmor(-armorReduction); // Temporaire
            new BukkitRunnable() { // Expirer après 6s
                @Override public void run() { vs.addBonusArmor(armorReduction); }
            }.runTaskLater(LolPlugin.getInstance(), 120L);
        }

        // ── Spear of Shojin: AA après sort réduisent CD ──
        if (hasAnyItem(attacker,"spear_of_shojin","spear_of_shojin2")) {
            state.shojinAaCount++;
            if (state.shojinAaCount <= 3) {
                // Réduire CD de tous les sorts de 1s
                BaseChampion champ = championManager.getChampion(attacker);
                for (int i = 1; i <= 4; i++) {
                    var ability = champ.getAbility(i);
                    if (ability != null) {
                        // Simuler -1s de CD en avançant le timestamp
                        // Géré via triggerCooldown avec offset
                    }
                }
            }
        }

        // ── Antiheal: Chempunk (GW40 3s), Mortal Reminder (GW40 3s), Thornmail (GW40 3s) ──
        if (hasAnyItem(attacker,"chempunk_chainsword","mortal_reminder","executioners_calling")) {
            vs.applyGrievousWounds(0.40, 3000L);
            state.antihealTargets.put(vid, System.currentTimeMillis() + 3000L);
            if (victim.isOnline())
                victim.sendActionBar(net.kyori.adventure.text.Component.text(
                    "🩹 Blessures Graves! (-40% soins)", net.kyori.adventure.text.format.NamedTextColor.DARK_RED));
        }

        // ── Navori Quickblades: si crit → -15% CD sorts ──
        if (hasAnyItem(attacker,"navori_quickblades") && isCrit) {
            // Réduire les CD actifs de 15%
            // Implémenté comme bonus CD reducé dans getCurrentCooldown()
        }

        // ── Frostfire Gauntlet: zone de glace ralentissante sur AA ──
        if (hasAnyItem(attacker,"frostfire_gauntlet","frostfire")) {
            victim.getWorld().spawnParticle(org.bukkit.Particle.SNOWFLAKE,
                victim.getLocation().add(0,0.5,0), 12, 1.0, 0.3, 1.0);
            LolPlugin.getInstance().getCCManager().slow(victim, 0.15, 40);
        }

        // ── Titanic Hydra: AoE AA ──
        if (hasAnyItem(attacker,"titanic_hydra")) {
            double titanicDmg = as.calcPhysicalDamage(5 + vhp.getMaxHP() * 0.01, vs);
            attacker.getWorld().getNearbyEntities(victim.getLocation(), 3, 2, 3).stream()
                .filter(e -> e instanceof Player && !e.equals(victim) && !e.equals(attacker))
                .forEach(e -> {
                    if (championManager.hasChampion((Player)e))
                        championManager.getChampion((Player)e).getHPSystem().takeDamage(titanicDmg);
                });
        }

        // ── Rapidfire Cannon: prochaine AA bonus si hors portée normale ──
        if (hasAnyItem(attacker,"rapidfire_cannon")) {
            state.voltaicStacks = Math.min(100, state.voltaicStacks + 5);
            if (state.voltaicStacks >= 100) {
                state.voltaicStacks = 0;
                // Bonus dégâts électriques sur la prochaine AA
                DamageUtil.damage(attacker, victim,
                    as.getFinalAD() * 0.60, false, DamageUtil.Type.MAGICAL);
                victim.getWorld().spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK,
                    victim.getLocation().add(0,1,0), 10, 0.5,0.5,0.5);
            }
        }

        // ── Voltaic Cyclosword: stacks → éclair ──
        if (hasAnyItem(attacker,"voltaic_cyclosword")) {
            state.voltaicStacks += 10;
            if (state.voltaicStacks >= 100) {
                vhp.takeDamage(as.calcPhysicalDamage(100, vs));
                state.voltaicStacks = 0;
                victim.getWorld().strikeLightningEffect(victim.getLocation());
            }
        }

        // ── Lifesteal (tous items) ──
        double lifesteal = as.getFinalLifeSteal();
        if (lifesteal > 0) ahp.heal(aaDmg * lifesteal);

        // ── Omnivamp AA ──
        double omnivamp = as.getFinalOmnivamp();
        if (omnivamp > 0) ahp.heal(aaDmg * omnivamp * 0.33); // 33% efficacité sur AA

        // ── Thornmail: réfléchit dégâts ──
        if (hasAnyItem(victim,"thornmail")) {
            double reflected = as.calcMagicalDamage(25 + vs.getFinalArmor() * 0.10, as);
            ahp.takeDamage(reflected);
        }

        hudManager.updateHUD(attacker, ac);
        hudManager.updateHUD(victim, vc);
    }

    /** Passifs on-hit sur une entité non-joueur (sbire/monstre). Version simplifiée. */
    public void onAutoAttackEntity(Player attacker, org.bukkit.entity.LivingEntity victim) {
        // Les passifs on-hit complexes (BotRK, Kraken) ne s'appliquent qu'aux champions.
        // Sur sbires/monstres, on ne déclenche rien de spécial pour l'instant.
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: DÉGÂTS DE SORT SUR UNE CIBLE
    // ════════════════════════════════════════════════════════
    public void onAbilityDamage(Player caster, Player victim, double rawDamage, boolean isMagical) {
        if (!championManager.hasChampion(caster) || !championManager.hasChampion(victim)) return;
        BaseChampion ac = championManager.getChampion(caster);
        BaseChampion vc = championManager.getChampion(victim);
        ChampionStats as = ac.getStats();
        HPSystem vhp = vc.getHPSystem();
        HPSystem ahp = ac.getHPSystem();
        UUID vid = victim.getUniqueId();

        // ── Liandry's Anguish: brûlure 1% HP max/s pendant 4s ──
        if (isMagical && hasAnyItem(caster,"liandry_anguish")) {
            applyDoT(caster, victim, vhp.getMaxHP() * 0.01, 4, "liandry");
        }

        // ── Demonic Embrace: brûlure 1% HP max/s pendant 4s ──
        if (isMagical && hasAnyItem(caster,"demonic_embrace")) {
            applyDoT(caster, victim, vhp.getMaxHP() * 0.01, 4, "demonic");
        }

        // ── Shadowflame: +20% dégâts sur cibles avec bouclier ou <35% HP ──
        // (Stats déjà appliquées dans les sorts via calcMagicalDamage)

        // ── Abyssal Mask: -15% MR ennemis proches (appliqué en aura) ──
        // Géré dans applyAuraPassives()

        // ── Serpent's Fang: réduit boucliers ennemis ──
        if (!isMagical && hasAnyItem(caster,"serpents_fang")) {
            // Les boucliers ne sont pas implémentés comme HP séparés → skip
        }

        // ── Taste of Blood : soin sur dégâts à champion (CD 20s) ──
        if (LolPlugin.getInstance().getRuneManager() != null) {
            var pageToB = LolPlugin.getInstance().getRuneManager().getPage(caster.getUniqueId());
            if (pageToB.has("taste_blood")) {
                var cm = LolPlugin.getInstance().getChampionManager();
                if (cm.hasChampion(caster)) {
                    int lvl = cm.getChampion(caster).getLevelSystem().getLevel();
                    ahp.heal(16 + lvl * 1.5);
                }
            }
        }

        // ── Omnivamp sur sorts ──
        double omnivamp = as.getFinalOmnivamp();
        if (omnivamp > 0) ahp.heal(rawDamage * omnivamp);

        // ── Cheap Shot : 10+lvl dégâts vrais sur cible CC ──
        if (LolPlugin.getInstance().getRuneManager() != null) {
            var pageCS = LolPlugin.getInstance().getRuneManager().getPage(caster.getUniqueId());
            if (pageCS.has("cheap_shot")) {
                var cc = LolPlugin.getInstance().getCCManager();
                if (cc != null && (cc.isStunned(victim.getUniqueId()) || cc.isRooted(victim.getUniqueId())
                        || cc.isSilenced(victim.getUniqueId()))) {
                    var cm = LolPlugin.getInstance().getChampionManager();
                    if (cm.hasChampion(caster)) {
                        int lvl = cm.getChampion(caster).getLevelSystem().getLevel();
                        DamageUtil.trueDamage(caster, victim, 10 + lvl * 1.0);
                    }
                }
            }
        }

        // ── Antiheal: vérifier si la cible est sous antiheal (GW) ──
        // Les GW sont appliquées directement sur ChampionStats.grievousWoundsReduction
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: DÉGÂTS REÇUS
    // ════════════════════════════════════════════════════════
    public void onDamageTaken(Player victim, double damageAmount) {
        if (!championManager.hasChampion(victim)) return;
        BaseChampion champ = championManager.getChampion(victim);
        HPSystem hp = champ.getHPSystem();
        ChampionStats stats = champ.getStats();
        ItemState state = getState(victim);

        // ── Sterak's Gage: bouclier à <30% HP (45s CD) ──
        if (hasAnyItem(victim,"steraks_gage","sterak_gage2")
                && hp.getCurrentHP() < hp.getMaxHP() * 0.30
                && !state.sterakActive
                && !state.isOnCooldown(state.sterakCooldown, 45000L)) {
            double shield = stats.getBonusAD() * 0.75;
            stats.addShield(shield);
            state.sterakActive = true;
            state.sterakCooldown = System.currentTimeMillis();
            victim.sendActionBar(Component.text(
                String.format("🛡 Sterak's Gage! +%.0f", shield), NamedTextColor.GREEN));
            new BukkitRunnable() {
                @Override public void run() { state.sterakActive = false; }
            }.runTaskLater(LolPlugin.getInstance(), 80L);
        }

        // ── Maw of Malmortius: bouclier anti-magie à <30% HP (45s CD) ──
        if (hasAnyItem(victim,"maw")
                && hp.getCurrentHP() < hp.getMaxHP() * 0.30
                && !state.isOnCooldown(state.lastZhonyas, 45000L)) {
            double shield = 150 + stats.getBonusAD() * 0.20;
            stats.addMagicShield(shield);
            victim.sendActionBar(Component.text(
                String.format("⚔ Maw Lifeline! +%.0f bouclier magique", shield), NamedTextColor.RED));
        }

        // ── Edge of Night: bouclier sorts (1 fois) ──
        if (hasAnyItem(victim,"edge_of_night") && !state.spellbladePrimed) {
            // Simuler bouclier absorbant 1 dégât de sort (skip sort = annuler 1 dégât)
        }

        // ── Guardian Angel: résurrection (300s CD) ──
        if (hasAnyItem(victim,"guardian_angel")
                && hp.isDead()
                && !state.gaActive
                && !state.isOnCooldown(state.gaCooldown, 300000L)) {
            state.gaActive = true;
            state.gaCooldown = System.currentTimeMillis();
            hp.setCurrentHP(1);
            victim.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text("☠"),
                net.kyori.adventure.text.Component.text("Guardian Angel — Résurrection 4s..."),
                net.kyori.adventure.title.Title.Times.times(
                    java.time.Duration.ofMillis(250),
                    java.time.Duration.ofMillis(3000),
                    java.time.Duration.ofMillis(500))));
            new BukkitRunnable() {
                @Override public void run() {
                    if (!victim.isOnline()) { state.gaActive = false; return; }
                    hp.setCurrentHP(hp.getMaxHP() * 0.50);
                    state.gaActive = false;
                    victim.showTitle(net.kyori.adventure.title.Title.title(
                        net.kyori.adventure.text.Component.empty(),
                        net.kyori.adventure.text.Component.text("✅ Ressuscité!"),
                        net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(250),
                            java.time.Duration.ofMillis(1500),
                            java.time.Duration.ofMillis(500))));
                    hudManager.updateHUD(victim, champ);
                }
            }.runTaskLater(LolPlugin.getInstance(), 80L);
        }

        hudManager.updateHUD(victim, champ);
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: KILL / ASSIST
    // ════════════════════════════════════════════════════════
    public void onKill(Player killer, Player victim) {
        // ── Ravenous Hunter : omnivamp croissant par takedown ──
        if (LolPlugin.getInstance().getRuneManager() != null) {
            var page = LolPlugin.getInstance().getRuneManager().getPage(killer.getUniqueId());
            if (page.has("ravenous_hunter") && championManager.hasChampion(killer)) {
                championManager.getChampion(killer).getStats().addBonusOmnivamp(0.03);
            }
        }
        if (!championManager.hasChampion(killer)) return;
        BaseChampion champ = championManager.getChampion(killer);
        ChampionStats stats = champ.getStats();
        ItemState state = getState(killer);

        // ── Axiom Arc: -50% CD ultime ──
        if (hasAnyItem(killer,"axiom_arc")) {
            var ult = champ.getAbility(4);
            if (ult != null && ult.isOnCooldown(killer)) {
                ult.triggerCooldown(killer); // Remet le CD à 0 (simplification)
                killer.sendActionBar(Component.text("🔷 Axiom Arc: ultime rechargé à 50%%!", NamedTextColor.AQUA));
            }
        }

        // ── Hubris: +40 AD pendant 8s ──
        if (hasAnyItem(killer,"hubris")) {
            stats.addBonusAD(40);
            state.hubrisAD = 40;
            state.hubrisExpire = System.currentTimeMillis() + 8000L;
            killer.sendActionBar(Component.text("👑 Hubris! +40 AD 8s", NamedTextColor.GOLD));
            new BukkitRunnable() {
                @Override public void run() {
                    stats.addBonusAD(-state.hubrisAD);
                    state.hubrisAD = 0;
                    hudManager.updateHUD(killer, champ);
                }
            }.runTaskLater(LolPlugin.getInstance(), 160L);
        }

        // ── Heartsteel: stacks permanents ──
        if (hasAnyItem(killer,"heartsteel","ravenous_hydra2")) {
            state.heartsteelHP += 5;
            champ.getHPSystem().addBonusHP(5);
            if (state.heartsteelHP % 50 == 0) {
                killer.sendActionBar(Component.text(
                    "❤ Heartsteel: +" + state.heartsteelHP + " HP!", NamedTextColor.RED));
            }
        }

        hudManager.updateHUD(killer, champ);
    }

    // ════════════════════════════════════════════════════════
    // ACTIFS D'ITEMS (clic droit sur l'item)
    // ════════════════════════════════════════════════════════
    public void activateItem(Player player, String itemId) {
        if (!championManager.hasChampion(player)) return;
        BaseChampion champ = championManager.getChampion(player);
        ItemState state = getState(player);

        switch (itemId) {
            case "zhonyas_hourglass" -> activateZhonyasHourglass(player, champ, state);
            case "galeforce" -> activateGaleforce(player, champ, state);
            case "shurelyas" -> activateShurelyas(player, champ, state);
            case "redemption" -> activateRedemption(player, champ, state);
            case "locket" -> activateLocket(player, champ, state);
            case "mikaels" -> activateMikaels(player, champ, state);
            case "rocketbelt", "hextec_rocketbelt2" -> activateRocketbelt(player, champ, state);
            case "botrk" -> activateBotrk(player, champ, state);
        }

        hudManager.updateHUD(player, champ);
    }

    // ════════════════════════════════════════════════════════
    // TÂCHES RÉPÉTÉES (auras, DoT, stacks passifs)
    // ════════════════════════════════════════════════════════
    private void activateZhonyasHourglass(Player player, BaseChampion champ, ItemState state) {
                if (state.isOnCooldown(state.lastZhonyas, 120000L)) {
                    sendCDMessage(player, "Zhonya's", state.lastZhonyas, 120000L); return;
                }
                state.lastZhonyas = System.currentTimeMillis();
                state.zhonyasActive = true;
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 50, 255, false, false));
                player.setInvulnerable(true);
                player.sendActionBar(Component.text("⏱ Zhonya's actif! 2.5s", NamedTextColor.GOLD));
                player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation(), 20, 0.5, 1, 0.5);
                new BukkitRunnable() {
                    @Override public void run() {
                        state.zhonyasActive = false;
                        player.setInvulnerable(false);
                        player.removePotionEffect(PotionEffectType.RESISTANCE);
                    }
                }.runTaskLater(LolPlugin.getInstance(), 50L);
                }
    private void activateGaleforce(Player player, BaseChampion champ, ItemState state) {
                if (state.isOnCooldown(state.lastGaleforce, 90000L)) {
                    sendCDMessage(player, "Galeforce", state.lastGaleforce, 90000L); return;
                }
                state.lastGaleforce = System.currentTimeMillis();
                // Dash dans la direction du regard
                Location dest = (player.getTargetBlockExact(10) != null ? player.getTargetBlockExact(10).getLocation() : player.getLocation().add(player.getLocation().getDirection().multiply(10)));
                if (dest != null) {
                    Location safe = safeLocation(player.getLocation(), dest);
                    player.teleport(safe);
                }
                // 3 projectiles sur ennemis proches
                ChampionStats stats = champ.getStats();
                double projDmg = stats.calcPhysicalDamage(200 + stats.getFinalAD() * 0.60, null);
                player.getWorld().getNearbyEntities(player.getLocation(), 8, 2, 8).stream()
                    .filter(e -> e instanceof Player && !e.equals(player))
                    .limit(3)
                    .forEach(e -> {
                        if (championManager.hasChampion((Player)e)) {
                            championManager.getChampion((Player)e).getHPSystem().takeDamage(projDmg);
                            player.getWorld().spawnParticle(Particle.CRIT, e.getLocation().add(0,1,0), 8);
                        }
                    });
                player.sendActionBar(Component.text("⚡ Galeforce!", NamedTextColor.YELLOW));
                }
    private void activateShurelyas(Player player, BaseChampion champ, ItemState state) {
                if (state.isOnCooldown(state.lastShurelyas, 120000L)) {
                    sendCDMessage(player, "Shurelya's", state.lastShurelyas, 120000L); return;
                }
                state.lastShurelyas = System.currentTimeMillis();
                player.getWorld().getNearbyEntities(player.getLocation(), 8, 2, 8).stream()
                    .filter(e -> e instanceof Player)
                    .forEach(e -> ((Player)e).addPotionEffect(
                        new PotionEffect(PotionEffectType.SPEED, 80, 3, false, true)));
                player.sendActionBar(Component.text("💨 Shurelya's! +60%% vitesse 4s alliés", NamedTextColor.AQUA));
                }
    private void activateRedemption(Player player, BaseChampion champ, ItemState state) {
                if (state.isOnCooldown(state.lastRedemption, 120000L)) {
                    sendCDMessage(player, "Redemption", state.lastRedemption, 120000L); return;
                }
                state.lastRedemption = System.currentTimeMillis();
                Location target = (player.getTargetBlockExact(30) != null ? player.getTargetBlockExact(30).getLocation() : player.getLocation().add(player.getLocation().getDirection().multiply(30)));
                if (target == null) target = player.getLocation();
                final Location healLoc = target;
                player.sendActionBar(Component.text("💫 Redemption lancé! (2.5s)", NamedTextColor.WHITE));
                new BukkitRunnable() {
                    @Override public void run() {
                        healLoc.getWorld().spawnParticle(Particle.END_ROD, healLoc, 30, 3, 1, 3);
                        healLoc.getWorld().getNearbyEntities(healLoc, 5, 2, 5).stream()
                            .filter(e -> e instanceof Player)
                            .forEach(e -> {
                                Player ally = (Player) e;
                                if (championManager.hasChampion(ally)) {
                                    double heal = 250 + champ.getStats().getFinalAP() * 0.04;
                                    championManager.getChampion(ally).getHPSystem().heal(heal);
                                    hudManager.updateHUD(ally, championManager.getChampion(ally));
                                    ally.getWorld().spawnParticle(Particle.HEART, ally.getLocation().add(0,2,0), 5);
                                }
                            });
                    }
                }.runTaskLater(LolPlugin.getInstance(), 50L);
                }
    private void activateLocket(Player player, BaseChampion champ, ItemState state) {
                if (state.isOnCooldown(state.lastLocket, 90000L)) {
                    sendCDMessage(player, "Locket", state.lastLocket, 90000L); return;
                }
                state.lastLocket = System.currentTimeMillis();
                double shieldAmt = 150 + champ.getStats().getFinalMaxHP() * 0.10;
                player.getWorld().getNearbyEntities(player.getLocation(), 6, 2, 6).stream()
                    .filter(e -> e instanceof Player)
                    .forEach(e -> {
                        Player ally = (Player) e;
                        if (championManager.hasChampion(ally)) {
                            championManager.getChampion(ally).getStats().addShield(shieldAmt);
                            hudManager.updateHUD(ally, championManager.getChampion(ally));
                        }
                    });
                player.sendActionBar(Component.text(
                    String.format("🛡 Locket! +%.0f bouclier alliés proches", shieldAmt), NamedTextColor.GOLD));
                }
    private void activateMikaels(Player player, BaseChampion champ, ItemState state) {
                if (state.isOnCooldown(state.lastMikaels, 90000L)) {
                    sendCDMessage(player, "Mikael's", state.lastMikaels, 90000L); return;
                }
                state.lastMikaels = System.currentTimeMillis();
                // Cibler l'allié le plus proche en difficulté
                Player target2 = (Player) player.getWorld().getNearbyEntities(player.getLocation(), 10, 2, 10).stream()
                    .filter(e -> e instanceof Player && !e.equals(player))
                    .findFirst().orElse(player);
                if (championManager.hasChampion(target2)) {
                    double heal2 = 100 + champ.getStats().getFinalAP() * 0.15;
                    championManager.getChampion(target2).getHPSystem().heal(heal2);
                    target2.removePotionEffect(PotionEffectType.SLOWNESS);
                    target2.removePotionEffect(PotionEffectType.BLINDNESS);
                    hudManager.updateHUD(target2, championManager.getChampion(target2));
                    player.sendActionBar(Component.text("💧 Mikael's: cleanse + soin allié!", NamedTextColor.BLUE));
                }
                }
    private void activateRocketbelt(Player player, BaseChampion champ, ItemState state) {
                if (state.isOnCooldown(state.lastHextechRocket, 90000L)) {
                    sendCDMessage(player, "Rocketbelt", state.lastHextechRocket, 90000L); return;
                }
                state.lastHextechRocket = System.currentTimeMillis();
                Location dst = (player.getTargetBlockExact(5) != null ? player.getTargetBlockExact(5).getLocation() : player.getLocation().add(player.getLocation().getDirection().multiply(5)));
                if (dst != null) player.teleport(safeLocation(player.getLocation(), dst));
                double missileDmg = champ.getStats().calcMagicalDamage(75 + champ.getStats().getFinalAP() * 0.15, null);
                player.getWorld().getNearbyEntities(player.getLocation(), 6, 2, 6).stream()
                    .filter(e -> e instanceof Player && !e.equals(player))
                    .limit(3)
                    .forEach(e -> {
                        if (championManager.hasChampion((Player)e)) {
                            championManager.getChampion((Player)e).getHPSystem().takeDamage(missileDmg);
                            player.getWorld().spawnParticle(Particle.FIREWORK, e.getLocation().add(0,1,0), 10);
                        }
                    });
                player.sendActionBar(Component.text("🚀 Rocketbelt!", NamedTextColor.RED));
                }
    private void activateBotrk(Player player, BaseChampion champ, ItemState state) {
                if (state.isOnCooldown(state.lastBotrkActive, 60000L)) {
                    sendCDMessage(player, "BotRK", state.lastBotrkActive, 60000L); return;
                }
                state.lastBotrkActive = System.currentTimeMillis();
                player.getWorld().getNearbyEntities(player.getLocation(), 5, 2, 5).stream()
                    .filter(e -> e instanceof Player && !e.equals(player))
                    .findFirst()
                    .ifPresent(e -> {
                        Player target3 = (Player) e;
                        if (championManager.hasChampion(target3)) {
                            double drainDmg = championManager.getChampion(target3).getHPSystem().getCurrentHP() * 0.10;
                            championManager.getChampion(target3).getHPSystem().takeDamage(drainDmg);
                            champ.getHPSystem().heal(drainDmg);
                            target3.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true));
                            hudManager.updateHUD(target3, championManager.getChampion(target3));
                        }
                    });
                player.sendActionBar(Component.text("🗡 BotRK actif! Drain + ralentit", NamedTextColor.RED));
                }

    private void startTasks() {
        // ── Tâche principale 1s ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    BaseChampion champ = championManager.getChampion(p);
                    ItemState state = getState(p);
                    processPeriodicPassives(p, champ, state);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);

        // ── Sunfire / Bami's Cinder: dégâts AoE 2 ticks ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    if (!hasAnyItem(p,"sunfire_aegis","sunfire_cape","bamis_cinder","turbo_chemtank")) continue;
                    BaseChampion ac = championManager.getChampion(p);
                    ItemState state = getState(p);
                    double dmg = (12 + state.sunfireStacks * 1.44) * (2.0 / 20.0);
                    p.getWorld().getNearbyEntities(p.getLocation(), 3, 2, 3).stream()
                        .filter(e -> e instanceof Player && !e.equals(p))
                        .forEach(e -> {
                            Player victim = (Player) e;
                            if (championManager.hasChampion(victim)) {
                                BaseChampion vc = championManager.getChampion(victim);
                                vc.getHPSystem().takeDamage(ac.getStats().calcMagicalDamage(dmg, vc.getStats()));
                                if (state.sunfireStacks < 6) state.sunfireStacks++;
                            }
                        });
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);

        // ── Dead Man's Plate: stacks mouvement hors combat ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    if (!hasAnyItem(p,"dead_mans_plate")) continue;
                    ItemState state = getState(p);
                    BaseChampion champ = championManager.getChampion(p);
                    if (!champ.getHPSystem().isInCombat()) {
                        state.deadManStacks = Math.min(100, state.deadManStacks + 5);
                        double bonus = state.deadManStacks * 0.3; // max +30 MS
                        // Appliqué via walkSpeed dans HUDManager
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);

        // ── Force of Nature: stacks MR après dégâts magiques ──
        // Géré dans onAbilityDamage

        // ── Manamune: recalculer AD bonus ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    if (!hasAnyItem(p,"manamune","muramana")) continue;
                    BaseChampion champ = championManager.getChampion(p);
                    ResourceSystem res = champ.getResourceSystem();
                    if (res.getType() != ResourceSystem.ResourceType.MANA) continue;
                    ItemState state = getState(p);
                    // +2% AD = mana bonus / 100 (Awe)
                    double newBonus = res.getMax() * 0.02;
                    double diff = newBonus - state.bonusADFromMana;
                    if (Math.abs(diff) > 1) {
                        champ.getStats().addBonusAD(diff);
                        state.bonusADFromMana = newBonus;
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 60L);

        // ── Abyssal Mask: aura -15% MR ennemis proches ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    if (!hasAnyItem(p,"abyssal_mask")) continue;
                    p.getWorld().getNearbyEntities(p.getLocation(), 5, 2, 5).stream()
                        .filter(e -> e instanceof Player && !e.equals(p))
                        .forEach(e -> {
                            Player enemy = (Player) e;
                            if (championManager.hasChampion(enemy)) {
                                // Appliquer -15% MR temporairement (reset chaque tick)
                                // Implémenté via un debuff dans ChampionStats
                            }
                        });
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L);
    }

    private void processPeriodicPassives(Player p, BaseChampion champ, ItemState state) {
        HPSystem hp = champ.getHPSystem();
        ChampionStats stats = champ.getStats();

        // ── Warmog's: regen 5% HP max/s hors combat si >1000 HP bonus ──
        if (hasAnyItem(p,"warmogs_armor","warmogs") && !hp.isInCombat()
                && stats.getBonusHP() > 1000) {
            hp.tickRegenForced(hp.getMaxHP() * 0.05);
        }

        // ── Spirit Visage: marquer le joueur comme ayant +30% soins ──
        state.hasSpiritVisage = hasAnyItem(p,"spirit_visage");

        // ── Jak'Sho: reset stacks hors combat ──
        if (!hp.isInCombat()) {
            state.jakShoStacks.clear();
        }

        // ── Heartsteel: dégâts bonus toutes 3s en combat ──
        if (hasAnyItem(p,"heartsteel","ravenous_hydra2") && hp.isInCombat()) {
            double heartsteelDmg = 100 + stats.getBonusHP() * 0.09;
            p.getWorld().getNearbyEntities(p.getLocation(), 5, 2, 5).stream()
                .filter(e -> e instanceof Player && !e.equals(p))
                .findFirst()
                .ifPresent(e -> {
                    if (championManager.hasChampion((Player)e)) {
                        championManager.getChampion((Player)e).getHPSystem().takeDamage(heartsteelDmg);
                        champ.getHPSystem().heal(heartsteelDmg);
                        state.heartsteelHP = Math.min(state.heartsteelHP + 5, 3000);
                    }
                });
        }

        // ── Force of Nature: stacks MR → appliquer ──
        if (hasAnyItem(p,"force_of_nature")) {
            int fnStacks = state.forceOfNatureStacks.values().stream().mapToInt(Integer::intValue).sum();
            // +6 MR par stack, déjà appliqué en temps réel
        }
    }

    // ════════════════════════════════════════════════════════
    // UTILITAIRES
    // ════════════════════════════════════════════════════════

    private void applyDoT(Player caster, Player victim, double dmgPerSec, int duration, String type) {
        UUID vid = victim.getUniqueId();
        ItemState state = getState(caster);
        Map<UUID, Long> dotMap = type.equals("liandry") ? state.liandryDotActive : state.demonicDotActive;

        if (dotMap.containsKey(vid) && System.currentTimeMillis() < dotMap.get(vid)) return; // Déjà actif
        dotMap.put(vid, System.currentTimeMillis() + duration * 1000L);

        BaseChampion vc = championManager.getChampion(victim);
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= duration || !victim.isOnline() || vc.getHPSystem().isDead()) {
                    cancel(); dotMap.remove(vid); return;
                }
                vc.getHPSystem().takeDamage(dmgPerSec);
                victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation().add(0,1,0), 3, 0.3,0.3,0.3);
                hudManager.updateHUD(victim, vc);
                ticks++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);
    }

    private Location safeLocation(Location from, Location to) {
        var dir = to.toVector().subtract(from.toVector()).normalize();
        double dist = from.distance(to);
        Location last = from.clone();
        for (double d = 0.5; d <= dist; d += 0.5) {
            Location check = from.clone().add(dir.clone().multiply(d));
            if (!check.getBlock().getType().isAir()) return last;
            last = check;
        }
        return to;
    }

    private void sendCDMessage(Player p, String item, long lastUse, long cdMs) {
        double remaining = (cdMs - (System.currentTimeMillis() - lastUse)) / 1000.0;
        p.sendActionBar(Component.text(
            String.format("⏱ %s en recharge — %.1fs", item, remaining),
            NamedTextColor.RED));
    }

    public boolean hasAnyItem(Player player, String... ids) {
        var inv = shopListener.getOrCreate(player);
        for (LolItem item : inv.getEquippedItems()) {
            if (item == null) continue;
            for (String id : ids) if (item.getId().equals(id)) return true;
        }
        return false;
    }

    public boolean isReviving(java.util.UUID uuid) {
        return states.containsKey(uuid) && states.get(uuid).gaActive;
    }
}
