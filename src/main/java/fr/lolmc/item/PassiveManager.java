package fr.lolmc.item;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.TargetingUtil;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.listener.ShopListener;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.stats.HPSystem;
import fr.lolmc.stats.ResourceSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
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

    // Tâches BukkitTask stockées pour annulation propre
    private final java.util.List<org.bukkit.scheduler.BukkitTask> tasks = new java.util.ArrayList<>();

    public PassiveManager(ChampionManager cm, HUDManager hud, ShopListener sl) {
        this.championManager = cm;
        this.hudManager = hud;
        this.shopListener = sl;
        // NE PAS démarrer les tâches ici — appelé par GameManager.startGame()
    }

    /** Démarre les tâches runtime (appelé par GameManager.startGame). */
    public void startTasks() {
        // ── Auras passives (vérifiées toutes les secondes) ──
        new BukkitRunnable() { @Override public void run() {
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (!championManager.hasChampion(p)) continue;
                var ch = championManager.getChampion(p);
                var st = ch.getStats();
                // Frozen Heart / Warden Mail / Randuin's Omen : -15-20% AS aux attaquants proches
                if (hasAnyItem(p,"frozen_heart","warden_mail","randuins_omen")) {
                    for (var e : p.getNearbyEntities(8,8,8)) {
                        if (e instanceof Player ep && LolPlugin.getInstance().getTeamManager().areEnemies(p,ep))
                            ep.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,25,0,false,false));
                    }
                }
                // Hullbreaker : seul → +20 armure/MR
                if (hasAnyItem(p,"hullbreaker")) {
                    boolean hasAlly=false;
                    for (var e : p.getNearbyEntities(12,12,12)) if (e instanceof Player ep && !LolPlugin.getInstance().getTeamManager().areEnemies(p,ep)) { hasAlly=true; break; }
                    if (!hasAlly && !getState(p).hullbreakerActive) { getState(p).hullbreakerActive=true; st.addBonusArmor(20); st.addBonusMR(20); }
                    else if (hasAlly && getState(p).hullbreakerActive) { getState(p).hullbreakerActive=false; st.addBonusArmor(-20); st.addBonusMR(-20); }
                }
                // Swiftmarch / Tunneler : +MS hors combat
                if (hasAnyItem(p,"swiftmarch") && !ch.getHPSystem().isInCombat() && !getState(p).swiftmarchActive) {
                    getState(p).swiftmarchActive=true; st.addBonusMoveSpeed(5);
                } else if (!hasAnyItem(p,"swiftmarch") && getState(p).swiftmarchActive) {
                    getState(p).swiftmarchActive=false; st.addBonusMoveSpeed(-5);
                }
                // Vigilant Wardstone : +12 AH + 12% AS aux alliés proches
                if (hasAnyItem(p,"vigilant_wardstone")) {
                    for (var e : p.getNearbyEntities(8,8,8)) if (e instanceof Player ap && !LolPlugin.getInstance().getTeamManager().areEnemies(p,ap) && championManager.hasChampion(ap))
                        ap.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,25,0,false,false));
                }
                // Warwick : Instinct de Chasse (révèle+traque les ennemis <50% HP)
                if ("warwick".equals(ch.getId())) {
                    fr.lolmc.champion.impl.jungle.Warwick.tickWarwickPassive(p);
                }
                // Rod of Ages : stacks croissants (simplifié, +20HP/20mana au fil du temps)
                if (hasAnyItem(p,"rod_of_ages") && getState(p).rodStacks<10) {
                    if (System.currentTimeMillis()-getState(p).lastRodStack > 60000) { // 1 stack/minute
                        getState(p).lastRodStack=System.currentTimeMillis(); getState(p).rodStacks++;
                        st.addBonusHP(20); ch.getHPSystem().heal(20);
                        if (ch.getResourceSystem()!=null) ch.getResourceSystem().addCurrent(20);
                    }
                }
                // Ardent Censer / Staff of Flowing Water : buff aux alliés soignés (géré dans soin)
                // Tear of the Goddess : stacks sur AA/sorts (géré via compteur)
            }
        }}.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);

        if (!tasks.isEmpty()) return;
        doStartTasks();
    }

    /** Arrête et libère toutes les tâches runtime. */
    public void stopTasks() {
        tasks.forEach(org.bukkit.scheduler.BukkitTask::cancel);
        tasks.clear();
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
        // ── Sudden Impact : +12 létalité/+9 pén. magique pendant 5s (une seule instance active) ──
        var runePageSI = LolPlugin.getInstance().getRuneManager();
        long __siExpire = getState(caster).antihealTargets.getOrDefault(
                new java.util.UUID(0L, caster.getUniqueId().getLeastSignificantBits()), 0L);
        boolean __siActive = System.currentTimeMillis() < __siExpire;
        if (!__siActive && runePageSI != null && runePageSI.getPage(caster.getUniqueId()).has("sudden_impact")) {
            // Marquer actif (5s) via une clé dédiée dans antihealTargets
            getState(caster).antihealTargets.put(
                    new java.util.UUID(0L, caster.getUniqueId().getLeastSignificantBits()),
                    System.currentTimeMillis() + 5000L);
            champ.getStats().addBonusLethality(12);
            champ.getStats().addBonusFlatMagicPen(9);
            new org.bukkit.scheduler.BukkitRunnable() { @Override public void run() {
                if (championManager.hasChampion(caster)) {
                    championManager.getChampion(caster).getStats().addBonusLethality(-12);
                    championManager.getChampion(caster).getStats().addBonusFlatMagicPen(-9);
                }
            }}.runTaskLater(LolPlugin.getInstance(), 100L);
        }

        // ── Cosmic Drive / Crimson Lucidity : +MS après un sort ──
        if (hasAnyItem(caster,"cosmic_drive","cosmic_drive2")) {
            champ.getStats().addBonusMoveSpeed(20);
            new BukkitRunnable(){@Override public void run(){ champ.getStats().addBonusMoveSpeed(-20); }}.runTaskLater(LolPlugin.getInstance(),40L);
        }
        if (hasAnyItem(caster,"crimson_lucidity")) {
            champ.getStats().addBonusMoveSpeed(8);
            new BukkitRunnable(){@Override public void run(){ champ.getStats().addBonusMoveSpeed(-8); }}.runTaskLater(LolPlugin.getInstance(),40L);
        }
        // ── Opportunity : prochain dégât +15% hors combat ──
        if (hasAnyItem(caster,"opportunity")) {
            getState(caster).opportunityReady = true;
        }
        // ── Experimental Hexplate : après ultime → +30% AS + 15% MS 7s ──
        if (slot == 4 && hasAnyItem(caster,"experimental_hexplate")) {
            champ.getStats().addBonusMoveSpeed(15); champ.getStats().multiplyAS(1.30);
            new BukkitRunnable(){@Override public void run(){ champ.getStats().addBonusMoveSpeed(-15); champ.getStats().multiplyAS(1.0/1.30); }}.runTaskLater(LolPlugin.getInstance(),140L);
        }
        // ── Radiant Virtue : ultime → soigne alliés 6% HP max + MS 8s ──
        if (slot == 4 && hasAnyItem(caster,"radiant_virtue")) {
            for (Player ally : caster.getWorld().getPlayers()) {
                if (ally.equals(caster)) continue;
                if (!LolPlugin.getInstance().getTeamManager().areEnemies(caster,ally)) {
                    if (championManager.hasChampion(ally)) {
                        var ah=championManager.getChampion(ally).getHPSystem();
                        ah.heal(ah.getMaxHP()*0.06);
                        championManager.getChampion(ally).getStats().addBonusMoveSpeed(10);
                        final Player fa=ally; new BukkitRunnable(){@Override public void run(){ if(championManager.hasChampion(fa)) championManager.getChampion(fa).getStats().addBonusMoveSpeed(-10); }}.runTaskLater(LolPlugin.getInstance(),160L);
                    }
                }
            }
        }
        // ── Malignance : dégâts d'ultime → -20% MR cible 3s ──
        // (géré dans onAbilityDamage si slot==4)
        // ── Emblem of All-In / Dusk and Dawn : prochain AA crit ou double on-hit ──
        if (slot == 4 && hasAnyItem(caster,"emblem_allin","fiendhunter_bolts")) {
            getState(caster).nextAACrit = true;
        }
        if (hasAnyItem(caster,"dusk_and_dawn")) {
            getState(caster).duskDawnReady = true;
        }

        // ── Cosmic Drive / Crimson Lucidity : +MS après sort ──
        if (hasAnyItem(caster,"cosmic_drive","cosmic_drive2")) {
            champ.getStats().addBonusMoveSpeed(20); new BukkitRunnable(){@Override public void run(){ champ.getStats().addBonusMoveSpeed(-20); }}.runTaskLater(LolPlugin.getInstance(),40L);
        }
        if (hasAnyItem(caster,"crimson_lucidity")) {
            champ.getStats().addBonusMoveSpeed(8); new BukkitRunnable(){@Override public void run(){ champ.getStats().addBonusMoveSpeed(-8); }}.runTaskLater(LolPlugin.getInstance(),40L);
        }
        // ── Iceborn Gauntlet : zone ralentissante après sort ──
        if (hasAnyItem(caster,"iceborn_gauntlet")) {
            final Location iLoc=caster.getLocation().clone();
            new BukkitRunnable(){@Override public void run(){
                for(var t:TargetingUtil.entitiesInRadius(caster,iLoc,3.5)){ if(t instanceof Player tp) tp.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,1,false,false)); }
                fr.lolmc.util.VisualEffectUtil.impactBurst(iLoc.getWorld(), iLoc.add(0,0.1,0),
                        Material.LIGHT_BLUE_STAINED_GLASS, 0.25f, 1.5, 6, 10L);
            }}.runTaskLater(LolPlugin.getInstance(),2L);
        }
        // ── Experimental Hexplate : après ultime → +30% AS + 15% MS 7s ──
        if (slot==4 && hasAnyItem(caster,"experimental_hexplate")) {
            champ.getStats().addBonusMoveSpeed(15); champ.getStats().multiplyAS(1.30);
            new BukkitRunnable(){@Override public void run(){ champ.getStats().addBonusMoveSpeed(-15); champ.getStats().multiplyAS(1.0/1.30); }}.runTaskLater(LolPlugin.getInstance(),140L);
        }
        // ── Radiant Virtue : ultime → soigne alliés 6% HP max + MS 8s ──
        if (slot==4 && hasAnyItem(caster,"radiant_virtue")) {
            for(Player ally:caster.getWorld().getPlayers()){ if(ally.equals(caster)) continue;
                if(!LolPlugin.getInstance().getTeamManager().areEnemies(caster,ally) && championManager.hasChampion(ally)){
                    var ah=championManager.getChampion(ally).getHPSystem(); ah.heal(ah.getMaxHP()*0.06);
                    championManager.getChampion(ally).getStats().addBonusMoveSpeed(10);
                    final Player fa=ally; new BukkitRunnable(){@Override public void run(){ if(championManager.hasChampion(fa)) championManager.getChampion(fa).getStats().addBonusMoveSpeed(-10); }}.runTaskLater(LolPlugin.getInstance(),160L);
                }
            }
        }
        // ── Dusk and Dawn : prochain AA double on-hit ──
        if (hasAnyItem(caster,"dusk_and_dawn")) getState(caster).duskDawnReady=true;
        // ── Emblem All-In / Fiendhunter Bolts : après ultime, prochain AA = crit ──
        if (slot==4 && hasAnyItem(caster,"emblem_allin","fiendhunter_bolts")) getState(caster).nextAACrit=true;
        // ── Malignance : ultime marque la cible pour -20% MR ──
        if (slot==4 && hasAnyItem(caster,"malignance")) getState(caster).malignanceReady=true;

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
                fr.lolmc.util.VisualEffectUtil.impact(attacker.getWorld(),
                        victim.getLocation().add(0,1,0), Material.LIGHT_BLUE_STAINED_GLASS, 0.4f, 4L);
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
                        fr.lolmc.util.VisualEffectUtil.impact(attacker.getWorld(),
                                extraTarget.getLocation().add(0,1,0), Material.WHITE_STAINED_GLASS, 0.3f, 3L);
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

        // ── Black Cleaver : stacks armure (max 6, expiration 6s) ──
        // La réduction est appliquée lors du calcul dans DamageUtil (stacks × 6% armure)
        if (hasAnyItem(attacker,"black_cleaver","black_cleaver2")) {
            int stacks = Math.min(6, state.blackCleaverStacks.getOrDefault(vid, 0) + 1);
            state.blackCleaverStacks.put(vid, stacks);
            // Décrémenter 1 stack après 6s
            final java.util.UUID fvid = vid;
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() {
                    state.blackCleaverStacks.merge(fvid, -1, (a, b) -> Math.max(0, a + b));
                }
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

        // ── Leona Lumière du Soleil : allié frappe cible marquée ──
        if (fr.lolmc.champion.impl.support.Leona.consumeMark(victim)) {
            var cm2 = LolPlugin.getInstance().getChampionManager();
            int lvl2 = cm2.hasChampion(attacker) ? cm2.getChampion(attacker).getLevelSystem().getLevel() : 1;
            double sunDmg = 35 + lvl2 * 2.6; // 35-80 selon le niveau
            fr.lolmc.util.TargetingUtil.dealDamage(attacker, victim, sunDmg, fr.lolmc.util.TargetingUtil.DmgType.MAGICAL);
            attacker.sendActionBar(net.kyori.adventure.text.Component.text(
                    "☀ Lumière du Soleil! +" + (int)sunDmg, net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        }

        // ── Frostfire Gauntlet: zone de glace ralentissante sur AA ──
        if (hasAnyItem(attacker,"frostfire_gauntlet","frostfire")) {
            fr.lolmc.util.VisualEffectUtil.impactBurst(victim.getWorld(),
                    victim.getLocation().add(0,0.5,0), Material.LIGHT_BLUE_STAINED_GLASS, 0.25f, 1.0, 5, 8L);
            LolPlugin.getInstance().getCCManager().slow(victim, 15, 40);
        }

        // ── Titanic Hydra: AoE AA (filtre équipe + hasChampion) ──
        if (hasAnyItem(attacker,"titanic_hydra")) {
            double titanicDmg = as.calcPhysicalDamage(5 + vhp.getMaxHP() * 0.01, vs);
            var __tmTH = LolPlugin.getInstance().getTeamManager();
            attacker.getWorld().getNearbyEntities(victim.getLocation(), 3, 2, 3).stream()
                    .filter(e -> e instanceof Player pe
                            && !pe.equals(victim) && !pe.equals(attacker)
                            && championManager.hasChampion(pe)
                            && __tmTH.areEnemies(attacker, pe))
                    .forEach(e -> {
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
                fr.lolmc.util.VisualEffectUtil.impactBurst(victim.getWorld(),
                        victim.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.22f, 0.5, 4, 5L);
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

        // ── Tiamat / Ravenous Hydra / Profane Hydra / Ironspike Whip : AoE AA ──
        if (hasAnyItem(attacker,"tiamat","ravenous_hydra","profane_hydra","ironspike_whip")) {
            for (var t : fr.lolmc.util.TargetingUtil.enemiesAround(attacker, 3.0)) {
                if (t.equals(victim)) continue;
                fr.lolmc.util.TargetingUtil.dealDamage(attacker, t, aaDmg*0.40, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
            }
        }
        // ── Umbral Glaive : détecte/détruit wards ──
        if (hasAnyItem(attacker,"umbral_glaive"))
            LolPlugin.getInstance().getWardManager().destroyEnemyWards(attacker, victim.getLocation(), 4.0);
        // ── Eclipse : 2 AA → bouclier + 15% MS ──
        if (hasAnyItem(attacker,"eclipse")) {
            int stk = getState(attacker).eclipseStacks + 1;
            getState(attacker).eclipseStacks = stk;
            if (stk >= 2) {
                getState(attacker).eclipseStacks = 0;
                double sh = 60 + as.getFinalAD()*0.10;
                ac.getStats().addShield(sh); ac.getStats().addBonusMoveSpeed(15);
                final double fsh=sh; new BukkitRunnable(){@Override public void run(){ ac.getStats().addShield(-fsh); ac.getStats().addBonusMoveSpeed(-15); }}.runTaskLater(LolPlugin.getInstance(),40L);
            }
        }
        // ── Terminus : Light/Dark alternés (+Armor ou +MR) ──
        if (hasAnyItem(attacker,"terminus")) {
            boolean light = (getState(attacker).terminusLight = !getState(attacker).terminusLight);
            if (light) { as.addBonusArmor(8); new BukkitRunnable(){@Override public void run(){ as.addBonusArmor(-8); }}.runTaskLater(LolPlugin.getInstance(),60L); }
            else        { as.addBonusMR(8);    new BukkitRunnable(){@Override public void run(){ as.addBonusMR(-8);    }}.runTaskLater(LolPlugin.getInstance(),60L); }
        }
        // ── Sundered Sky : AA crit → soigne 10% HP manquants ──
        if (hasAnyItem(attacker,"sundered_sky"))
            ahp.heal((ahp.getMaxHP()-ahp.getCurrentHP())*0.10);
        // ── Stormrazor : 1ère AA → slow 99% 0.5s (CD 18s) ──
        if (hasAnyItem(attacker,"stormrazor")) {
            long now=System.currentTimeMillis(); ItemState stsr=getState(attacker);
            if (now-stsr.lastStormrazor > 18000) {
                stsr.lastStormrazor = now;
                if (victim instanceof Player vp) vp.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,10,5,false,false));
            }
        }
        // ── Phage : +15 MS 2s ──
        if (hasAnyItem(attacker,"phage")) {
            as.addBonusMoveSpeed(15);
            new BukkitRunnable(){@Override public void run(){ as.addBonusMoveSpeed(-15); }}.runTaskLater(LolPlugin.getInstance(),40L);
        }
        // ── Rageknife : toutes les 2 AA → on-hit ×2 ──
        if (hasAnyItem(attacker,"rageknife")) {
            int rk = (getState(attacker).rageknifeCount+1) % 2;
            getState(attacker).rageknifeCount = rk;
            if (rk == 0) fr.lolmc.util.TargetingUtil.dealDamage(attacker, victim, aaDmg*0.20, fr.lolmc.util.TargetingUtil.DmgType.MAGICAL);
        }
        // ── Phantom Dancer : +7% MS ──
        if (hasAnyItem(attacker,"phantom_dancer","phantom_dancer2")) {
            as.addBonusMoveSpeed(7);
            new BukkitRunnable(){@Override public void run(){ as.addBonusMoveSpeed(-7); }}.runTaskLater(LolPlugin.getInstance(),40L);
        }
        // ── Bloodthirster : vol de vie excédentaire → bouclier ──
        if (hasAnyItem(attacker,"bloodthirster")) {
            double overHeal = Math.max(0, ahp.getCurrentHP() + aaDmg*0.18 - ahp.getMaxHP());
            if (overHeal > 0) { double sh=Math.min(overHeal,50+as.getFinalAD()*0.40); as.addShield(sh); new BukkitRunnable(){@Override public void run(){ as.addShield(-sh); }}.runTaskLater(LolPlugin.getInstance(),60L); }
        }
        // ── Dusk and Dawn : après sort, prochain AA double on-hit ──
        if (getState(attacker).duskDawnReady) {
            getState(attacker).duskDawnReady = false;
            fr.lolmc.util.TargetingUtil.dealDamage(attacker, victim, aaDmg*0.50, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
        }
        // ── Emblem All-In / Fiendhunter : prochain AA crit garanti ──
        if (getState(attacker).nextAACrit) {
            getState(attacker).nextAACrit = false;
            fr.lolmc.util.TargetingUtil.dealDamage(attacker, victim, aaDmg*0.75, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
        }
        // ── Protoplasm Harness : 6 stacks → AoE ──
        if (hasAnyItem(attacker,"protoplasm_harness")) {
            int ph = (getState(attacker).protoStacks+1) % 6;
            getState(attacker).protoStacks = ph;
            if (ph == 0) fr.lolmc.util.TargetingUtil.dealDamageAll(attacker, fr.lolmc.util.TargetingUtil.enemiesAround(attacker,3.5), aaDmg*0.30, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);
        }
        // ── Yun Tal Wildarrows : crits → DoT 60%AD physique 3s ──
        if (hasAnyItem(attacker,"yun_tal_wildarrows") && getState(attacker).lastHitCrit)
            applyDoT(attacker, victim, as.getFinalAD()*0.20, 3, "yuntal");
        // ── Noonquiver : tir bonus 50 dégâts physiques ──
        if (hasAnyItem(attacker,"noonquiver"))
            fr.lolmc.util.TargetingUtil.dealDamage(attacker, victim, 50, fr.lolmc.util.TargetingUtil.DmgType.PHYSICAL);

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
        if (!championManager.hasChampion(attacker)) return;
        // ── Scorchclaw Smite : AA ralentissent les monstres de jungle ──
        if (hasAnyItem(attacker, "blue_smite")) {
            victim.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, 40, 1, false, false));
        }
        // ── Gustwalker Smite : bonus vitesse après tuer un monstre (géré dans onJungleKill) ──
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

        // ── Morgana Siphon de l'Âme : soin sur sorts (20% dégâts) ──
        { var __cm = LolPlugin.getInstance().getChampionManager();
            if (__cm.hasChampion(caster) && "morgana".equals(__cm.getChampion(caster).getId())) {
                boolean isChampOrMonster = (victim instanceof Player)
                        || fr.lolmc.game.JungleManager.isJungleMonster(victim);
                if (isChampOrMonster) {
                    __cm.getChampion(caster).getHPSystem().heal(rawDamage * 0.20);
                }
            }}

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

        // ── Atakhan Vorace : résurrection unique (avant GA) ──
        if (state.atakhanRevive && hp.isDead() && !state.gaActive) {
            state.atakhanRevive = false;
            state.gaActive = true;
            hp.setCurrentHP(1);
            victim.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.text("🌺"),
                    net.kyori.adventure.text.Component.text("Pétales Sanglants — Résurrection 4s..."),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(250),
                            java.time.Duration.ofMillis(3000),
                            java.time.Duration.ofMillis(500))));
            new BukkitRunnable() {
                @Override public void run() {
                    if (!victim.isOnline()) { state.gaActive = false; return; }
                    hp.setCurrentHP(hp.getMaxHP() * 0.60);
                    state.gaActive = false;
                    fr.lolmc.util.VisualEffectUtil.impactBurst(victim.getWorld(),
                        victim.getLocation().add(0,1,0), Material.GOLD_BLOCK, 0.3f, 0.6, 10, 15L);
                }
            }.runTaskLater(LolPlugin.getInstance(), 80L);
            return;
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
                killer.sendActionBar(Component.text("🔷 Axiom Arc: ultime rechargé à 50%!", NamedTextColor.AQUA));
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

        // ── Dark Seal : +5 AP par kill (max 10 stacks) ──
        if (hasAnyItem(killer,"dark_seal")) {
            getState(killer).darkSealStacks = Math.min(10, getState(killer).darkSealStacks+1);
            stats.addBonusAP(5);
        }
        // ── Mejai's Soulstealer : +5 AP par kill (max 25 stacks) ──
        if (hasAnyItem(killer,"mejais_soulstealer")) {
            int stk = Math.min(25, getState(killer).mejaisStacks+1);
            getState(killer).mejaisStacks = stk;
            stats.addBonusAP(5);
        }
        // ── Stormsurge / Stormsurge NH : kill → foudre sur voisins ──
        if (hasAnyItem(killer,"stormsurge","stormsurge_nh")) {
            double ldmg = 300 + stats.getFinalAP()*0.25;
            for (var t : TargetingUtil.enemiesAround(killer, 6.0)) TargetingUtil.dealDamage(killer,t,ldmg,TargetingUtil.DmgType.MAGICAL);
            fr.lolmc.util.VisualEffectUtil.impactBurst(killer.getWorld(),
                    killer.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.3f, 2.0, 8, 8L);
        }
        // ── Cryptbloom : kill/assist → zone qui soigne alliés ──
        if (hasAnyItem(killer,"cryptbloom")) {
            for (Player ally : killer.getWorld().getPlayers()) {
                if (!LolPlugin.getInstance().getTeamManager().areEnemies(killer,ally) && championManager.hasChampion(ally) && ally.getLocation().distance(killer.getLocation())<6.0)
                    championManager.getChampion(ally).getHPSystem().heal(championManager.getChampion(ally).getHPSystem().getMaxHP()*0.08);
            }
        }
        // ── The Collector : exécute <5% HP (déjà en fin de vie, on accorde du gold) ──
        if (hasAnyItem(killer,"the_collector")) {
            LolPlugin.getInstance().getGoldManager().addGold(killer.getUniqueId(), 25);
        }
        // ── Unending Fury : kill/assist → +15% omnivamp 3s ──
        if (hasAnyItem(killer,"unending_fury")) {
            stats.addBonusOmnivamp(0.15);
            new BukkitRunnable(){@Override public void run(){ stats.addBonusOmnivamp(-0.15); }}.runTaskLater(LolPlugin.getInstance(),60L);
        }
        // ── Cull : +1 or/minion tué (ici kill champion, bonus extra) ──
        if (hasAnyItem(killer,"cull") && getState(killer).cullStacks < 100) {
            getState(killer).cullStacks += 5;
            LolPlugin.getInstance().getGoldManager().addGold(killer.getUniqueId(), 5);
        }
        // ── Dead Man's Plate reset stacks ──
        if (hasAnyItem(killer,"dead_mans_plate")) getState(killer).deadManStacks = 0;

        // ── Dark Seal : +5 AP par kill (max 10 stacks) ──
        if (hasAnyItem(killer,"dark_seal")) { getState(killer).darkSealStacks=Math.min(10,getState(killer).darkSealStacks+1); stats.addBonusAP(5); }
        // ── Mejai's Soulstealer : +5 AP par kill (max 25 stacks) ──
        if (hasAnyItem(killer,"mejais_soulstealer")) { getState(killer).mejaisStacks=Math.min(25,getState(killer).mejaisStacks+1); stats.addBonusAP(5); }
        // ── Stormsurge / Stormsurge NH : kill → foudre AoE ──
        if (hasAnyItem(killer,"stormsurge","stormsurge_nh")) {
            double ld=300+stats.getFinalAP()*0.25;
            for(var t:TargetingUtil.enemiesAround(killer,6.0)) TargetingUtil.dealDamage(killer,t,ld,TargetingUtil.DmgType.MAGICAL);
            fr.lolmc.util.VisualEffectUtil.impactBurst(killer.getWorld(),
                    killer.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.3f, 2.0, 8, 8L);
        }
        // ── Cryptbloom : kill → zone soin alliés 8% HP ──
        if (hasAnyItem(killer,"cryptbloom")) {
            for(Player ally:killer.getWorld().getPlayers()){ if(!LolPlugin.getInstance().getTeamManager().areEnemies(killer,ally) && championManager.hasChampion(ally) && ally.getLocation().distance(killer.getLocation())<6.0)
                championManager.getChampion(ally).getHPSystem().heal(championManager.getChampion(ally).getHPSystem().getMaxHP()*0.08); }
        }
        // ── The Collector : +25 or bonus ──
        if (hasAnyItem(killer,"the_collector")) LolPlugin.getInstance().getGoldManager().addGold(killer.getUniqueId(),25);
        // ── Unending Fury : +15% omnivamp 3s ──
        if (hasAnyItem(killer,"unending_fury")) {
            stats.addBonusOmnivamp(0.15); new BukkitRunnable(){@Override public void run(){ stats.addBonusOmnivamp(-0.15); }}.runTaskLater(LolPlugin.getInstance(),60L);
        }
        // ── Cull : +5 or (max 100 stacks) ──
        if (hasAnyItem(killer,"cull") && getState(killer).cullStacks<100) { getState(killer).cullStacks+=5; LolPlugin.getInstance().getGoldManager().addGold(killer.getUniqueId(),5); }
        // ── Or partagé (World Atlas, Bounty, etc.) : +or aux alliés proches ──
        if (hasAnyItem(killer,"world_atlas","bounty_worlds","celestial_opposition","solstice_sleigh","spectral_sickle")) {
            for(Player ally:killer.getWorld().getPlayers()){
                if(!ally.equals(killer) && !LolPlugin.getInstance().getTeamManager().areEnemies(killer,ally) && ally.getLocation().distance(killer.getLocation())<10.0)
                    LolPlugin.getInstance().getGoldManager().addGold(ally.getUniqueId(),50);
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
            // Actifs nouveaux
            case "everfrost" -> {
                if (state.isOnCooldown(state.lastEverfrost, 40000L)) { sendCDMessage(player,"Everfrost",state.lastEverfrost,40000L); break; }
                state.lastEverfrost = System.currentTimeMillis();
                for (var t : TargetingUtil.entitiesInRadius(player, player.getLocation(), 5.0)) {
                    TargetingUtil.dealDamage(player, t, 100+champ.getStats().getFinalAP()*0.30, TargetingUtil.DmgType.MAGICAL);
                    var cc=LolPlugin.getInstance().getCCManager(); if(cc!=null) cc.root(t,40);
                }
                fr.lolmc.util.VisualEffectUtil.impactBurst(player.getWorld(),
                        player.getLocation().add(0,1,0), Material.LIGHT_BLUE_STAINED_GLASS, 0.28f, 2.0, 7, 8L);
                player.sendActionBar(Component.text("❄ Everfrost! Enracine ennemis proches.",NamedTextColor.AQUA));
            }
            case "gargoyle_stoneplate" -> {
                if (state.isOnCooldown(state.lastGargoyle, 90000L)) { sendCDMessage(player,"Gargoyle",state.lastGargoyle,90000L); break; }
                state.lastGargoyle = System.currentTimeMillis();
                double bonusHP = champ.getHPSystem().getMaxHP();
                champ.getHPSystem().addBonusHP(bonusHP);
                player.sendActionBar(Component.text("🗿 Gargoyle: +100% HP temporaire 4s!",NamedTextColor.GRAY));
                new BukkitRunnable(){@Override public void run(){ champ.getHPSystem().addBonusHP(-bonusHP); }}.runTaskLater(LolPlugin.getInstance(),80L);
            }
            case "goredrinker" -> {
                if (state.isOnCooldown(state.lastGoredrinker, 60000L)) { sendCDMessage(player,"Goredrinker",state.lastGoredrinker,60000L); break; }
                state.lastGoredrinker = System.currentTimeMillis();
                double totalDmg = 0;
                for (var t : TargetingUtil.entitiesInRadius(player, player.getLocation(), 4.5)) {
                    double d = 50 + champ.getStats().getFinalAD()*0.40;
                    TargetingUtil.dealDamage(player, t, d, TargetingUtil.DmgType.PHYSICAL);
                    totalDmg+=d;
                }
                champ.getHPSystem().heal(totalDmg*0.15 + champ.getHPSystem().getMaxHP()*0.10);
                fr.lolmc.util.VisualEffectUtil.impactBurst(player.getWorld(),
                        player.getLocation().add(0,1,0), Material.RED_STAINED_GLASS, 0.28f, 1.5, 6, 8L);
                player.sendActionBar(Component.text("🩸 Goredrinker! AoE + soin.",NamedTextColor.RED));
            }
            case "prowlers_claw" -> {
                if (state.isOnCooldown(state.lastProwler, 90000L)) { sendCDMessage(player,"Prowler's Claw",state.lastProwler,90000L); break; }
                state.lastProwler = System.currentTimeMillis();
                var target = TargetingUtil.getTargetedEnemy(player, 8.0);
                if (target == null) { player.sendActionBar(Component.text("Aucune cible.",NamedTextColor.GRAY)); break; }
                player.teleportAsync(target.getLocation().add(target.getLocation().getDirection().normalize().multiply(-1.5)));
                TargetingUtil.dealDamage(player, target, 65+champ.getStats().getFinalAD()*0.15+champ.getStats().getFinalAP()*0.15, TargetingUtil.DmgType.PHYSICAL);
                player.sendActionBar(Component.text("🐾 Prowler's Claw! +15% dégâts 3s.",NamedTextColor.DARK_RED));
                state.prowlerBonusDmg = true;
                new BukkitRunnable(){@Override public void run(){ state.prowlerBonusDmg=false; }}.runTaskLater(LolPlugin.getInstance(),60L);
            }
            case "stridebreaker" -> {
                if (state.isOnCooldown(state.lastStridebreaker, 60000L)) { sendCDMessage(player,"Stridebreaker",state.lastStridebreaker,60000L); break; }
                state.lastStridebreaker = System.currentTimeMillis();
                for (var t : TargetingUtil.entitiesInRadius(player, player.getLocation(), 4.0)) {
                    TargetingUtil.dealDamage(player, t, 100+champ.getStats().getFinalAD()*0.50, TargetingUtil.DmgType.PHYSICAL);
                    if (t instanceof Player tp) tp.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,20,3,false,false));
                }
                player.sendActionBar(Component.text("⚡ Stridebreaker! AoE slow.",NamedTextColor.YELLOW));
            }
            case "mercurial_scimitar","quicksilver_sash","silvermere_dawn" -> {
                if (state.isOnCooldown(state.lastQSS, 90000L)) { sendCDMessage(player,"QSS",state.lastQSS,90000L); break; }
                state.lastQSS = System.currentTimeMillis();
                var cc=LolPlugin.getInstance().getCCManager();
                if(cc!=null) cc.cleanse(player);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
                if (itemId.equals("silvermere_dawn")) { player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,60,0,false,false)); }
                player.sendActionBar(Component.text("✨ Cleanse! Tous CC retirés.",NamedTextColor.WHITE));
            }
            case "stopwatch" -> {
                if (state.stopwatchUsed) { player.sendActionBar(Component.text("Stopwatch déjà utilisée.",NamedTextColor.GRAY)); break; }
                state.stopwatchUsed = true;
                champ.getStats().addShield(99999);
                player.sendActionBar(Component.text("⏱ Stopwatch! Invulnérabilité 2.5s.",NamedTextColor.GOLD));
                new BukkitRunnable(){@Override public void run(){ champ.getStats().addShield(-99999); }}.runTaskLater(LolPlugin.getInstance(),50L);
            }
            case "twin_shadows" -> {
                if (state.isOnCooldown(state.lastTwinShadows, 120000L)) { sendCDMessage(player,"Twin Shadows",state.lastTwinShadows,120000L); break; }
                state.lastTwinShadows = System.currentTimeMillis();
                var target2 = TargetingUtil.getNearestEnemy(player, 12.0);
                if (target2 != null) {
                    if (target2 instanceof Player tp) tp.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,false,false));
                    TargetingUtil.dealDamage(player, target2, 50+champ.getStats().getFinalAP()*0.20, TargetingUtil.DmgType.MAGICAL);
                }
                player.sendActionBar(Component.text("👥 Twin Shadows! Fantômes ralentissants.",NamedTextColor.DARK_PURPLE));
            }
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
        fr.lolmc.util.VisualEffectUtil.impactBurst(player.getWorld(),
                player.getLocation(), Material.GOLD_BLOCK, 0.25f, 0.5, 6, 10L);
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
        ChampionStats stats = champ.getStats();
        double projDmg = stats.calcPhysicalDamage(200 + stats.getFinalAD() * 0.60, null);
        // Animation dash : traînée de particules
        Location dashStart = player.getLocation().clone();
        Location dashDest = player.getTargetBlockExact(10) != null
                ? player.getTargetBlockExact(10).getLocation()
                : player.getLocation().add(player.getLocation().getDirection().multiply(10));
        Location dashSafe = safeLocation(dashStart, dashDest);
        // Traînée avant téléportation
        double dashDist = dashStart.distance(dashSafe);
        int dashSteps = Math.max(3, (int)(dashDist / 0.4));
        org.bukkit.util.Vector dashStep = dashSafe.toVector()
                .subtract(dashStart.toVector()).normalize().multiply(dashDist / dashSteps);
        org.bukkit.Location cur = dashStart.clone();
        for (int si = 0; si < dashSteps; si++) {
            cur.add(dashStep);
            fr.lolmc.util.VisualEffectUtil.impact(player.getWorld(), cur.clone(), Material.WHITE_STAINED_GLASS, 0.18f, 3L);
        }
        player.teleport(dashSafe);
        player.getWorld().playSound(dashSafe, Sound.ENTITY_PHANTOM_FLAP, 1f, 1.5f);
        // 3 projectiles animés vers les 3 ennemis les plus proches
        final var enemies = player.getWorld().getNearbyEntities(player.getLocation(), 8, 2, 8).stream()
                .filter(e -> e instanceof Player ep && !ep.equals(player)
                        && championManager.hasChampion((Player)e)
                        && LolPlugin.getInstance().getTeamManager().areEnemies(player, (Player)e))
                .limit(3).toList();
        for (var enemy : enemies) {
            final var fe = enemy;
            Location projStart = player.getLocation().clone().add(0,1,0);
            Location projEnd = fe.getLocation().clone().add(0,1,0);
            double pd = projStart.distance(projEnd);
            int ps = Math.max(3,(int)(pd/0.5));
            org.bukkit.util.Vector pv = projEnd.toVector().subtract(projStart.toVector()).normalize().multiply(pd/ps);
            new org.bukkit.scheduler.BukkitRunnable(){
                int pi=0; Location pc = projStart.clone();
                @Override public void run(){
                    if(pi>=ps){cancel();
                        if(championManager.hasChampion((Player)fe))
                            championManager.getChampion((Player)fe).getHPSystem().takeDamage(projDmg);
                        fr.lolmc.util.VisualEffectUtil.impact(fe.getLocation().getWorld(),
                                fe.getLocation().add(0,1,0), Material.WHITE_STAINED_GLASS, 0.3f, 4L);
                        fe.getLocation().getWorld().playSound(fe.getLocation(), Sound.ENTITY_ARROW_HIT, 0.8f, 1.3f);
                        return;}
                    pc.add(pv);
                    fr.lolmc.util.VisualEffectUtil.impact(pc.getWorld(), pc.clone(), Material.WHITE_STAINED_GLASS, 0.15f, 2L);
                    pi++;
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,1L);
        }
        player.sendActionBar(Component.text("⚡ Galeforce! " + enemies.size() + " projectiles", NamedTextColor.YELLOW));
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
        player.sendActionBar(Component.text("💨 Shurelya's! +60% vitesse 4s alliés", NamedTextColor.AQUA));
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
                fr.lolmc.util.VisualEffectUtil.groundRing(healLoc.getWorld(), healLoc, 3.0,
                        Material.WHITE_STAINED_GLASS, 20, 0.35f, 0.1f, 25L);
                healLoc.getWorld().getNearbyEntities(healLoc, 5, 2, 5).stream()
                        .filter(e -> e instanceof Player)
                        .forEach(e -> {
                            Player ally = (Player) e;
                            if (championManager.hasChampion(ally)) {
                                double heal = 250 + champ.getStats().getFinalAP() * 0.04;
                                championManager.getChampion(ally).getHPSystem().heal(heal);
                                hudManager.updateHUD(ally, championManager.getChampion(ally));
                                fr.lolmc.util.VisualEffectUtil.impact(ally.getWorld(),
                                        ally.getLocation().add(0,2,0), Material.PINK_STAINED_GLASS, 0.25f, 6L);
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
                        fr.lolmc.util.VisualEffectUtil.impact(player.getWorld(),
                                e.getLocation().add(0,1,0), Material.ORANGE_STAINED_GLASS, 0.4f, 5L);
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

    private void doStartTasks() {
        // ── Tâche principale 1s ──
        tasks.add(new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    BaseChampion champ = championManager.getChampion(p);
                    ItemState state = getState(p);
                    processPeriodicPassives(p, champ, state);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L));

        // ── Sunfire / Bami's Cinder: dégâts AoE 2 ticks ──
        tasks.add(new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
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
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L));

        // ── Dead Man's Plate: stacks mouvement hors combat ──
        tasks.add(new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
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
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L));

        // ── Force of Nature: stacks MR après dégâts magiques ──
        // Géré dans onAbilityDamage

        // ── Manamune: recalculer AD bonus ──
        tasks.add(new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
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
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 60L));

        // ── Abyssal Mask: aura -15% MR ennemis proches ──
        tasks.add(new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
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
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 10L));
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
                fr.lolmc.util.VisualEffectUtil.impact(victim.getWorld(),
                        victim.getLocation().add(0,1,0), Material.ORANGE_STAINED_GLASS, 0.2f, 4L);
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

    /** Retourne true si le joueur est sous Grievous Wounds (anti-heal -50%). */
    public boolean hasGrievousWounds(Player target) {
        var st = getState(target);
        return st.antihealTargets.containsKey(target.getUniqueId())
            && st.antihealTargets.get(target.getUniqueId()) > System.currentTimeMillis();
    }

    /** Accorde une résurrection unique (Atakhan Vorace — Pétales Sanglants). */
    public void grantAtakhanRevive(Player player) {
        getState(player).atakhanRevive = true;
    }

    public boolean isReviving(java.util.UUID uuid) {
        return states.containsKey(uuid) && states.get(uuid).gaActive;
    }


    /**
     * Appelé quand un joueur tue un monstre de jungle.
     * Gère les passifs des items de jungle.
     */
    public void onJungleKill(Player killer) {
        if (!championManager.hasChampion(killer)) return;
        var champ = championManager.getChampion(killer);
        // ── Gustwalker Smite : +20% vitesse de déplacement pendant 2s après un kill ──
        if (hasAnyItem(killer, "smite_stalker")) {
            champ.getStats().addBonusMoveSpeed(20);
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() { champ.getStats().addBonusMoveSpeed(-20); }
            }.runTaskLater(LolPlugin.getInstance(), 40L);
            killer.sendActionBar(net.kyori.adventure.text.Component.text(
                "🌿 Gustwalker: +20 MS!", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        }
        // ── Mosstomper Smite : bouclier 65-170 après un kill de jungle (CD 12s) ──
        if (hasAnyItem(killer, "pickaxe_jungle")) {
            ItemState state = getState(killer);
            long now = System.currentTimeMillis();
            if (state.lastMosstomperShield == 0 || now - state.lastMosstomperShield > 12000) {
                state.lastMosstomperShield = now;
                int lvl = champ.getLevelSystem().getLevel();
                double shield = 65 + lvl * 7.0; // ~65-170 selon niveau
                champ.getStats().addShield(shield);
                final double fsh = shield;
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override public void run() { champ.getStats().addShield(-fsh); }
                }.runTaskLater(LolPlugin.getInstance(), 60L); // 3s
                killer.sendActionBar(net.kyori.adventure.text.Component.text(
                    String.format("🌿 Mosstomper: Bouclier %.0f!", shield),
                    net.kyori.adventure.text.format.NamedTextColor.GREEN));
            }
        }
    }

    /** Nettoie l'état d'un joueur (déconnexion / fin de partie). */
    public void cleanup(java.util.UUID uuid) {
        states.remove(uuid);
    }

    /** Nettoie tous les états (fin de partie / onDisable). */
    public void cleanupAll() {
        states.clear();
    }
}