package fr.lolmc.item;
import fr.lolmc.util.DamageUtil;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.listener.ShopListener;
import fr.lolmc.manager.ChampionManager;
import fr.lolmc.manager.HUDManager;
import fr.lolmc.stats.ChampionStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Point d'entrée des effets « passifs » hors objets (runes, quête de support, passifs périodiques
 * des champions) et façade vers les effets d'objets ({@link ItemEffects}, {@link ItemTicker},
 * {@link ItemActives}). Avant, ce fichier contenait aussi tous les passifs d'objets, avec des
 * valeurs d'anciens patchs et des effets en double (vol de vie, omnivampirisme, soin de Morgana).
 */
public class PassiveManager {

    private final ChampionManager championManager;
    private final HUDManager hudManager;
    private final ShopListener shopListener;

    // État par joueur (runes, quête de support, stase)
    private final Map<UUID, ItemState> states = new HashMap<>();

    // Tâches BukkitTask stockées pour annulation propre
    private final List<org.bukkit.scheduler.BukkitTask> tasks = new ArrayList<>();

    public PassiveManager(ChampionManager cm, HUDManager hud, ShopListener sl) {
        this.championManager = cm;
        this.hudManager = hud;
        this.shopListener = sl;
        // NE PAS démarrer les tâches ici — appelé par GameManager.startGame()
    }

    private org.bukkit.scheduler.BukkitTask auraTask;

    /** Démarre les tâches runtime (appelé par GameManager.startGame). */
    public void startTasks() {
        // ── Passifs périodiques des champions (chaque seconde) ──
        if (auraTask != null) auraTask.cancel(); // sinon une tâche de plus s'empile à chaque partie
        auraTask = new BukkitRunnable() { @Override public void run() {
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (!championManager.hasChampion(p)) continue;
                var ch = championManager.getChampion(p);
                switch (ch.getId()) {
                    case "warwick" -> fr.lolmc.champion.impl.jungle.Warwick.tickWarwickPassive(p);
                    case "malphite" -> fr.lolmc.champion.impl.top.Malphite.tickGraniteShield(p, ch);
                    case "garen" -> fr.lolmc.champion.impl.top.Garen.tickGarenPassive(ch);
                    case "janna" -> fr.lolmc.champion.impl.support.Janna.tickJannaPassive(p);
                    case "blitzcrank" -> fr.lolmc.champion.impl.support.Blitzcrank.tickStaticShield(p, ch);
                    case "yasuo" -> fr.lolmc.champion.impl.mid.Yasuo.tickWindShield(p, ch);
                    default -> { }
                }
            }
        }}.runTaskTimer(LolPlugin.getInstance(), 0L, 20L);

        ItemTicker.start();
        if (!tasks.isEmpty()) return;
        // ── Quête de support et runes (chaque seconde) ──
        tasks.add(new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
                    if (!championManager.hasChampion(p)) continue;
                    processPeriodicPassives(p, championManager.getChampion(p), getState(p));
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 20L));
    }

    /** Arrête et libère toutes les tâches runtime. */
    public void stopTasks() {
        if (auraTask != null) { auraTask.cancel(); auraTask = null; }
        tasks.forEach(org.bukkit.scheduler.BukkitTask::cancel);
        tasks.clear();
        ItemTicker.stop();
    }

    public ItemState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), _ -> new ItemState());
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: SORT LANCÉ
    // ════════════════════════════════════════════════════════
    public void onAbilityCast(Player caster, int slot) {
        if (!championManager.hasChampion(caster)) return;
        BaseChampion champ = championManager.getChampion(caster);
        // Annie : Pyromanie (stun tous les 4 sorts)
        if ("annie".equals(champ.getId())) {
            fr.lolmc.champion.impl.mid.Annie.onSpellCast(caster.getUniqueId(), slot);
        }
        // ── Rune Impact soudain : +12 létalité / +9 pénétration magique 5 s (une instance) ──
        var rm = LolPlugin.getInstance().getRuneManager();
        ItemState st = getState(caster);
        if (rm != null && rm.getPage(caster.getUniqueId()).has("sudden_impact")
                && System.currentTimeMillis() >= st.suddenImpactUntil) {
            st.suddenImpactUntil = System.currentTimeMillis() + 5000L;
            champ.getStats().addBonusLethality(12);
            champ.getStats().addBonusFlatMagicPen(9);
            new BukkitRunnable() { @Override public void run() {
                if (championManager.hasChampion(caster)) {
                    championManager.getChampion(caster).getStats().addBonusLethality(-12);
                    championManager.getChampion(caster).getStats().addBonusFlatMagicPen(-9);
                }
            }}.runTaskLater(LolPlugin.getInstance(), 100L);
        }
    }

    // ════════════════════════════════════════════════════════
    // QUÊTE DE SUPPORT (wiki, patch 26.19-26.20)
    // Atlas : 3 or / 10 s, Richesses partagées 18 or ; 400 or gagnés → Boussole runique
    // Boussole : 5 or / 10 s, 21 or ; 800 or gagnés → Trésor des mondes, améliorable gratuitement
    // ════════════════════════════════════════════════════════

    /** Objet de la ligne de support porté par le joueur, ou null. */
    private LolItem supportItem(Player p) {
        for (LolItem it : shopListener.getOrCreate(p).getEquippedItems())
            if (it != null && it.getTier() == LolItem.Tier.SUPPORT_QUEST) return it;
        return null;
    }

    /** Allié le plus proche à moins de 1050 unités, ou null. */
    private Player nearestAlly(Player p) {
        Player best = null; double bd = 10.5 * 10.5;
        for (Player a : p.getWorld().getPlayers()) {
            if (a.equals(p) || !championManager.hasChampion(a) || a.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || !LolPlugin.getInstance().getTeamManager().areAllies(p, a)) continue;
            double d = a.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = a; }
        }
        return best;
    }

    /** Consomme une charge de Richesses partagées (près d'un allié) : 18 ou 21 or. */
    private boolean useSharedRiches(Player p, boolean oncePerHit) {
        LolItem it = supportItem(p);
        if (it == null) return false;
        ItemState st = getState(p);
        long now = System.currentTimeMillis();
        if (st.sharedRichesCharges <= 0 || (oncePerHit && now - st.lastSharedRiches < 250)) return false;
        if (nearestAlly(p) == null) return false;
        st.sharedRichesCharges--;
        st.lastSharedRiches = now;
        int gold = "world_atlas".equals(it.getId()) ? 18 : 21;
        LolPlugin.getInstance().getGoldManager().addGold(p.getUniqueId(), gold);
        st.supportQuestGold += gold;
        p.sendActionBar(Component.text("💰 Richesses partagées +" + gold + " or", NamedTextColor.GOLD));
        return true;
    }

    /**
     * Dernier coup sur un sbire avec une charge de Richesses partagées près d'un allié : le
     * support gagne 18/21 or et l'allié le plus proche reçoit l'or du sbire. Renvoie cet allié.
     */
    public Player sharedRichesMinion(Player killer) {
        if (supportItem(killer) == null) return null;
        Player ally = nearestAlly(killer);
        return ally != null && useSharedRiches(killer, false) ? ally : null;
    }

    /** Or passif, charges et transformations de la quête (appelé chaque seconde). */
    private void tickSupportQuest(Player p, BaseChampion champ, ItemState st) {
        LolItem it = supportItem(p);
        if (it == null) return;
        if (st.sharedRichesCharges < 3 && ++st.sharedRichesTimer >= 20) { st.sharedRichesCharges++; st.sharedRichesTimer = 0; }
        if (++st.supportGoldTimer >= 10) {
            st.supportGoldTimer = 0;
            int g = (int) Math.round(it.getGoldPer10());
            if (g > 0) { LolPlugin.getInstance().getGoldManager().addGold(p.getUniqueId(), g); st.supportQuestGold += g; }
        }
        String next = switch (it.getId()) {
            case "world_atlas" -> st.supportQuestGold >= 400 ? "runic_compass" : null;
            case "runic_compass" -> st.supportQuestGold >= 800 ? "bounty_worlds" : null;
            default -> null;
        };
        if (next == null) return;
        LolItem upgrade = ItemRegistry.get(next);
        var inv = shopListener.getOrCreate(p);
        if (upgrade == null) return;
        for (int i = 0; i < 6; i++) {
            if (inv.getItem(i) != it) continue;
            inv.removeItemNoRefund(champ, i);
            inv.equipItem(p, champ, upgrade);
            var hb = LolPlugin.getInstance().getHotbarManager();
            hb.removeItem(p, it.getId());
            hb.addItem(p, upgrade.getId());
            hb.renderPage(p, champ);
            st.supportQuestGold = 0;
            p.sendMessage(Component.text("🧭 Quête de support : " + it.getDisplayName() + " → "
                    + upgrade.getDisplayName() + " !", NamedTextColor.GOLD));
            break;
        }
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: ATTAQUE DE BASE SUR UN CHAMPION (runes, Lumière du soleil)
    // ════════════════════════════════════════════════════════
    public void onAutoAttack(Player attacker, Player victim) {
        if (!championManager.hasChampion(attacker) || !championManager.hasChampion(victim)) return;
        useSharedRiches(attacker, true); // attaque sur un champion ennemi
        ChampionStats as = championManager.getChampion(attacker).getStats();

        // ── Rune Coup de bouclier : avec un bouclier actif, l'attaque inflige des dégâts adaptatifs ──
        var rmShieldBash = LolPlugin.getInstance().getRuneManager();
        if (rmShieldBash != null && as.getShield() > 0
                && rmShieldBash.getPage(attacker.getUniqueId()).has("shield_bash")) {
            boolean ad = as.getFinalAD() > as.getFinalAP();
            ItemEffects.itemDamage(attacker, victim, (ad ? as.getFinalAD() : as.getFinalAP()) * 0.10,
                    ad ? DamageUtil.Type.PHYSICAL : DamageUtil.Type.MAGICAL, true);
            attacker.sendActionBar(Component.text("🛡 Coup de Bouclier !", NamedTextColor.GREEN));
        }

        // ── Leona, Lumière du Soleil : un allié frappe une cible marquée ──
        if (fr.lolmc.champion.impl.support.Leona.consumeMark(attacker, victim)) {
            int lvl2 = championManager.getChampion(attacker).getLevelSystem().getLevel();
            double sunDmg = 32 + (151 - 32) * (lvl2 - 1) / 17.0; // 32-151 selon le niveau (wiki)
            ItemEffects.itemDamage(attacker, victim, sunDmg, DamageUtil.Type.MAGICAL, false);
            attacker.sendActionBar(Component.text("☀ Lumière du Soleil! +" + (int) sunDmg, NamedTextColor.YELLOW));
        }
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: DÉGÂTS DE SORT SUR UN CHAMPION (runes)
    // ════════════════════════════════════════════════════════
    public void onAbilityDamage(Player caster, Player victim, double rawDamage, boolean isMagical) {
        if (!championManager.hasChampion(caster) || !championManager.hasChampion(victim)) return;
        useSharedRiches(caster, true); // une charge par sort au plus
        var rm = LolPlugin.getInstance().getRuneManager();
        if (rm == null) return;
        var page = rm.getPage(caster.getUniqueId());
        int lvl = championManager.getChampion(caster).getLevelSystem().getLevel();
        // ── Goût du sang : soin sur dégâts à un champion (20 s) ──
        if (page.has("taste_blood") && rm.tryRuneCooldown(caster.getUniqueId(), "taste_blood", 20_000L))
            championManager.getChampion(caster).getHPSystem().heal(16 + lvl * 1.5);
        // ── Coup bas : 10 + niveau dégâts bruts sur une cible contrôlée ──
        if (page.has("cheap_shot")) {
            var cc = LolPlugin.getInstance().getCCManager();
            if (cc != null && (cc.isStunned(victim.getUniqueId()) || cc.isRooted(victim.getUniqueId())
                    || cc.isSilenced(victim.getUniqueId())))
                DamageUtil.trueDamage(caster, victim, 10 + lvl * 1.0);
        }
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: DÉGÂTS REÇUS (rune Gardien)
    // ════════════════════════════════════════════════════════
    public void onDamageTaken(Player victim) {
        if (!championManager.hasChampion(victim)) return;
        BaseChampion champ = championManager.getChampion(victim);
        ChampionStats stats = champ.getStats();
        ItemState state = getState(victim);

        // ── Rune Gardien : le porteur et l'allié le plus proche reçoivent un bouclier (45 s) ──
        var guardianPage = LolPlugin.getInstance().getRuneManager() != null
                ? LolPlugin.getInstance().getRuneManager().getPage(victim.getUniqueId()) : null;
        if (guardianPage != null && "guardian".equals(guardianPage.keystone)
                && !state.isOnCooldown(state.guardianLastUse, 45000L)) {
            Player nearestAlly = null;
            double nearestDist = Double.MAX_VALUE;
            for (Player p : WorldContext.getGamePlayers()) {
                if (p.equals(victim) || !championManager.hasChampion(p)
                        || !LolPlugin.getInstance().getTeamManager().areAllies(victim, p)) continue;
                if (!p.getWorld().equals(victim.getWorld())) continue;
                double d = p.getLocation().distanceSquared(victim.getLocation());
                if (d < nearestDist) { nearestDist = d; nearestAlly = p; }
            }
            if (nearestAlly != null && nearestDist <= 8.0*8.0) {
                state.guardianLastUse = System.currentTimeMillis();
                double guardShield = 45.0 + stats.getFinalAP() * 0.125 + stats.getBonusHP() * 0.08;
                stats.addShield(guardShield);
                championManager.getChampion(nearestAlly).getStats().addShield(guardShield);
                victim.sendActionBar(Component.text("🛡 Gardien !", NamedTextColor.AQUA));
                nearestAlly.sendActionBar(Component.text("🛡 Gardien (allié) !", NamedTextColor.AQUA));
                final double gShield = guardShield;
                final Player guardAlly = nearestAlly;
                new BukkitRunnable() {
                    @Override public void run() {
                        stats.addShield(-Math.min(gShield, stats.getShield()));
                        if (championManager.hasChampion(guardAlly)) {
                            var as = championManager.getChampion(guardAlly).getStats();
                            as.addShield(-Math.min(gShield, as.getShield()));
                        }
                    }
                }.runTaskLater(LolPlugin.getInstance(), 40L);
            }
        }
        hudManager.updateHUD(victim, champ);
    }

    // ════════════════════════════════════════════════════════
    // ÉVÉNEMENT: KILL (runes)
    // ════════════════════════════════════════════════════════
    public void onKill(Player killer) {
        var rm = LolPlugin.getInstance().getRuneManager();
        if (rm == null) return;
        var page = rm.getPage(killer.getUniqueId());
        // ── Présence d'esprit : 15 % de la ressource max sur takedown (patch 26) ──
        if (page.has("presence_mind") && championManager.hasChampion(killer)) {
            var res = championManager.getChampion(killer).getResourceSystem();
            res.addCurrent(res.getMax() * 0.15);
            killer.sendActionBar(Component.text("✨ Présence d'Esprit", NamedTextColor.AQUA));
        }
        // ── Chaussures magiques : -45 s sur le délai des bottes gratuites ──
        if (page.has("magical_footwear")) {
            var footState = getState(killer);
            footState.magicalFootwearThresholdMs = Math.max(0, footState.magicalFootwearThresholdMs - 45_000L);
        }
    }

    // ════════════════════════════════════════════════════════
    // ACTIFS D'OBJETS (clic droit sur l'objet)
    // ════════════════════════════════════════════════════════
    public void activateItem(Player player, String itemId) {
        if (!championManager.hasChampion(player)) return;
        fr.lolmc.instance.InstanceHelper.baseManager(player).onAction(player); // actif d'objet
        ItemActives.activate(player, itemId);
    }

    // ════════════════════════════════════════════════════════
    // TÂCHE PÉRIODIQUE : quête de support et runes
    // ════════════════════════════════════════════════════════
    private void processPeriodicPassives(Player p, BaseChampion champ, ItemState state) {
        ChampionStats stats = champ.getStats();
        tickSupportQuest(p, champ, state);

        var rmGhostPoro = LolPlugin.getInstance().getRuneManager();
        if (rmGhostPoro == null) return;
        var page = rmGhostPoro.getPage(p.getUniqueId());

        // ── Rune Marche sur l'eau : +25 vitesse de déplacement dans l'eau (rivière) ──
        if (page.has("waterwalking")) {
            boolean inWater = p.isInWater();
            if (inWater != state.wasInWaterLastTick) {
                stats.addBonusMoveSpeed(inWater ? 25 : -25);
                state.wasInWaterLastTick = inWater;
            }
        } else if (state.wasInWaterLastTick) {
            stats.addBonusMoveSpeed(-25);
            state.wasInWaterLastTick = false;
        }

        // ── Rune Chaussures magiques : bottes gratuites après un délai (12 min - 45 s par takedown) ──
        if (!state.magicalFootwearGiven && page.has("magical_footwear")) {
            var gm3 = fr.lolmc.instance.InstanceHelper.gameManager(p);
            if (gm3 != null && gm3.getElapsedSeconds() * 1000L >= state.magicalFootwearThresholdMs) {
                var invMgr = shopListener.getOrCreate(p);
                if (!invMgr.hasBoots()) {
                    var bootsItem = ItemRegistry.get("boots_speed");
                    if (bootsItem != null && invMgr.equipItem(p, champ, bootsItem)) {
                        state.magicalFootwearGiven = true;
                        p.sendActionBar(Component.text("👢 Chaussures Magiques reçues !", NamedTextColor.AQUA));
                    }
                } else {
                    state.magicalFootwearGiven = true; // déjà des bottes, la rune n'a plus d'effet
                }
            }
        }

        // ── Rune Livraison de biscuits : 3 biscuits aux minutes 2, 4 et 6 ──
        if (state.biscuitsDelivered < 3 && page.has("biscuit_delivery")) {
            var gm4 = fr.lolmc.instance.InstanceHelper.gameManager(p);
            if (gm4 != null) {
                int dueBiscuits = (int) Math.min(3, gm4.getElapsedSeconds() / 120);
                if (dueBiscuits > state.biscuitsDelivered) {
                    var hb = LolPlugin.getInstance().getHotbarManager();
                    for (int i = state.biscuitsDelivered; i < dueBiscuits; i++) hb.addConsumable(p, "biscuit");
                    state.biscuitsDelivered = dueBiscuits;
                    p.sendActionBar(Component.text("🍪 Biscuit livré !", NamedTextColor.GOLD));
                }
            }
        }

        // ── Rune Vitesse d'approche : +7.5 % de vitesse près d'un ennemi ralenti ou immobilisé ──
        if (page.has("approach_velocity")) {
            var cc2 = LolPlugin.getInstance().getCCManager();
            var tm2 = LolPlugin.getInstance().getTeamManager();
            boolean nearImpaired = false;
            for (var e : p.getNearbyEntities(10, 10, 10)) {
                if (e instanceof Player other && tm2 != null && tm2.areEnemies(p, other)
                        && cc2 != null && (cc2.isSlowed(other) || cc2.isRooted(other.getUniqueId()) || cc2.isStunned(other.getUniqueId()))) {
                    nearImpaired = true;
                    break;
                }
            }
            if (nearImpaired != state.approachVelocityActive) {
                if (nearImpaired) {
                    state.approachVelocityBonus = stats.getFinalMovementSpeed() * 0.075;
                    stats.addBonusMoveSpeed(state.approachVelocityBonus);
                } else {
                    stats.addBonusMoveSpeed(-state.approachVelocityBonus);
                    state.approachVelocityBonus = 0;
                }
                state.approachVelocityActive = nearImpaired;
            }
        }

        // ── Rune Triple tonique : élixirs gratuits aux niveaux 3 (Avarice) et 6 (Force) ──
        if (state.tripleTonicsGiven < 2 && page.has("triple_tonic")) {
            int lvl2 = champ.getLevelSystem().getLevel();
            if (state.tripleTonicsGiven == 0 && lvl2 >= 3) {
                state.tripleTonicsGiven = 1;
                stats.addBonusAD(5);
                p.sendActionBar(Component.text("🧪 Élixir d'Avarice (niveau 3) !", NamedTextColor.GOLD));
            } else if (state.tripleTonicsGiven == 1 && lvl2 >= 6) {
                state.tripleTonicsGiven = 2;
                if (stats.getFinalAP() > stats.getFinalAD()) stats.addBonusAP(25); else stats.addBonusAD(15);
                p.sendActionBar(Component.text("🧪 Élixir de Force (niveau 6) !", NamedTextColor.GOLD));
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // UTILITAIRES
    // ════════════════════════════════════════════════════════

    public boolean hasAnyItem(Player player, String... ids) {
        var inv = shopListener.getOrCreate(player);
        for (LolItem item : inv.getEquippedItems()) {
            if (item == null) continue;
            for (String id : ids) if (item.getId().equals(id)) return true;
        }
        return false;
    }

    /** Retourne true si le joueur est sous Hémorragie (réduction des soins). */
    public boolean hasGrievousWounds(Player target) {
        return championManager.hasChampion(target) && championManager.getChampion(target).getStats().hasGrievousWounds();
    }

    /** Appelé quand un joueur tue un monstre de jungle : compagnon de jungle. */
    public void onJungleKill(Player killer, org.bukkit.entity.LivingEntity monster) {
        ItemEffects.onJungleKill(killer, monster);
    }

    /** Nettoie l'état d'un joueur (déconnexion / fin de partie). */
    public void cleanup(UUID uuid) {
        states.remove(uuid);
        ItemEffects.reset(uuid);
        ItemTicker.reset(uuid);
    }

    /** Nettoie tous les états (fin de partie / onDisable). */
    public void cleanupAll() {
        states.clear();
        ItemEffects.resetAll();
    }
}
