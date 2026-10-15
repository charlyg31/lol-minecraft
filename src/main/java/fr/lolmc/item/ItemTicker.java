package fr.lolmc.item;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.TargetingUtil;
import fr.lolmc.util.VisualEffectUtil;
import fr.lolmc.util.WorldContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static fr.lolmc.item.ItemEffects.*;

/**
 * Effets périodiques des objets (toutes les 0.5 s) et statistiques dérivées d'autres statistiques
 * (Effroi, Tyrannie, Infusion du Néant, Gloire…). Chaque bonus dynamique est recalculé et ajusté
 * par différence : il disparaît de lui-même quand l'objet est vendu (avant, plusieurs bonus restaient
 * acquis après la revente).
 */
public final class ItemTicker {

    private ItemTicker() {}

    private static BukkitTask task;
    private static long tickCount = 0;

    /** Bonus dynamiques appliqués : joueur → (clé → montant). */
    private static final Map<UUID, Map<String, Double>> DYN = new ConcurrentHashMap<>();
    /** Sillage de l'Élixir de fer : porteur → points {position, instant}. */
    private static final Map<UUID, java.util.ArrayDeque<Object[]>> TRAILS = new ConcurrentHashMap<>();
    /** Cœur gelé : porteur → (ennemi → multiplicateur appliqué). */
    private static final Map<UUID, Map<UUID, Double>> FROZEN = new ConcurrentHashMap<>();

    public static void start() {
        if (task != null) task.cancel();
        task = new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(pl(), 10L, 10L);
    }

    public static void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    /** Fixe un bonus dynamique : seule la différence avec la valeur déjà appliquée est ajoutée. */
    private static void dyn(Player p, String key, double want, BiConsumer<ChampionStats, Double> add, ChampionStats st) {
        var m = DYN.computeIfAbsent(p.getUniqueId(), _ -> new HashMap<>());
        double prev = m.getOrDefault(key, 0.0);
        if (Math.abs(want - prev) < 1e-6) return;
        add.accept(st, want - prev);
        if (want == 0) m.remove(key); else m.put(key, want);
    }

    private static double dynValue(Player p, String key) {
        var m = DYN.get(p.getUniqueId());
        return m == null ? 0 : m.getOrDefault(key, 0.0);
    }

    public static void reset(UUID id) { DYN.remove(id); FROZEN.remove(id); }

    /** Taille bonus donnée par les objets (agrandit l'immolation). */
    static double itemScale(Player p) {
        var attr = p.getAttribute(fr.lolmc.util.Compat.scale());
        if (attr == null) return 0;
        var mod = attr.getModifier(new org.bukkit.NamespacedKey(pl(), "lol_item_scale"));
        return mod != null ? mod.getAmount() : 0;
    }

    private static void tick() {
        tickCount++;
        long now = System.currentTimeMillis();
        for (Player p : WorldContext.getGamePlayers()) {
            BaseChampion c = champ(p);
            if (c == null) continue;
            try { tickPlayer(p, c, now); }
            catch (RuntimeException ex) { pl().getLogger().warning("Effets d'objets (" + p.getName() + ") : " + ex); }
        }
        // Couperet noir : cumuls expirés
        for (var it = ARMOR_SHRED.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue()[2] > now) continue;
            Player v = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (v != null && champ(v) != null) champ(v).getStats().addBonusArmor(e.getValue()[1]);
            it.remove();
        }
        MARKS.entrySet().removeIf(e -> e.getValue() < now - 60_000);
    }

    private static void tickPlayer(Player p, BaseChampion c, long now) {
        Fx f = fx(p);
        ChampionStats st = c.getStats();
        var hp = c.getHPSystem();
        var res = c.getResourceSystem();
        boolean mana = res.getType() == ResourceSystem.ResourceType.MANA;
        boolean rng = ranged(p);
        int l = lvl(p);
        boolean dead = p.getGameMode() == org.bukkit.GameMode.SPECTATOR;
        boolean everySecond = tickCount % 2 == 0;

        // ── Déplacement : Énergisé (1 cumul par 24 unités, ~4 par bloc) et élan de la Plaque du mort ──
        org.bukkit.Location loc = p.getLocation();
        double moved = f.lastLoc != null && f.lastLoc.getWorld().equals(loc.getWorld())
                ? Math.hypot(loc.getX() - f.lastLoc.getX(), loc.getZ() - f.lastLoc.getZ()) : 0;
        f.lastLoc = loc.clone();
        if (!dead && has(p, "statikk_shiv", "rapid_firecannon", "stormrazor", "voltaic_cyclosword"))
            f.energize = Math.min(100, f.energize + moved * 100 / 24.0);
        if (has(p, "dead_man_s_plate")) {
            if (moved > 0.2) f.deadManMomentum = Math.min(100, f.deadManMomentum + 14); // 7 cumuls par 0.25 s
        } else f.deadManMomentum = 0;
        dyn(p, "deadManMS", f.deadManMomentum / 100.0 * 20, ChampionStats::addBonusMoveSpeed, st);

        // ── Danse de la mort : dégâts différés infligés en 3 s ──
        if (f.ddTicksLeft > 0 && f.ddPending > 0 && !dead) {
            double part = f.ddPending / f.ddTicksLeft;
            f.ddPending -= part;
            f.ddTicksLeft--;
            itemDamage(null, p, part, DamageUtil.Type.TRUE, false);
        }

        // ── Régénérations ──
        if (!dead && everySecond) {
            // Bouclier de Doran : 4 PV / 5 s, et 40 PV (30 à distance) en 8 s après des dégâts de champion
            if (has(p, "doran_s_shield")) {
                hp.heal(0.8);
                if (f.doranShieldRegenLeft > 0) {
                    double part = (rng ? 30 : 40) / 8.0;
                    hp.heal(Math.min(part, f.doranShieldRegenLeft));
                    f.doranShieldRegenLeft -= part;
                }
            }
            // Anneau de Doran : 1 mana/s (2 après avoir blessé un champion) ; sans mana, 45 % en PV
            if (has(p, "doran_s_ring")) {
                double m = f.doranRingUntil > now ? 2 : 1;
                if (mana) res.addCurrent(m); else hp.heal(m * 0.45);
            }
            // Armure de Warmog : 2000 PV bonus et 8 s sans dégâts → 1.5 % des PV max par 0.5 s
        }
        if (!dead && has(p, "warmogs_armor") && st.getBonusHP() >= 2000 && hp.msSinceLastDamage() >= 8000)
            hp.heal(hp.getMaxHP() * 0.015);

        // ── Immolation (Égide solaire, Rayonnement du vide, Cendres de Bami) : 1 fois par seconde ──
        if (!dead && everySecond && f.immolateUntil > now) {
            double dmg; double minionMod; double monsterMod;
            if (has(p, "sunfire_aegis")) { dmg = 20 + st.getBonusHP() * 0.015; minionMod = 0.5; monsterMod = 0.8; }
            else if (has(p, "hollow_radiance")) { dmg = 15 + st.getBonusHP() * 0.01; minionMod = 0.25; monsterMod = 0.25; }
            else if (has(p, "bamis_cinder")) { dmg = 15; minionMod = 0.5; monsterMod = 1.0; }
            else dmg = minionMod = monsterMod = 0;
            double radius = 3.25 * (1 + itemScale(p));
            if (dmg > 0) for (var e : TargetingUtil.enemiesAround(p, radius)) {
                boolean minion = fr.lolmc.game.MinionManager.isMinion(e);
                double d = minion ? dmg * (1 + minionMod) : isMonster(e) ? dmg * (1 + monsterMod) : dmg;
                // Égide solaire : exécute les sbires qu'un tick de plus tuerait
                if (minion && has(p, "sunfire_aegis") && curHp(e) <= d) d = curHp(e) + 1;
                itemDamage(p, e, d, DamageUtil.Type.MAGICAL, false);
            }
        }

        // ── Désespoir infini : toutes les 4 s en combat de champions, 3 % des PV bonus, soin 250 % ──
        if (!dead && has(p, "unending_despair") && champCombatSeconds(p) > 0 && ready(f.cd, "despair")) {
            cooldown(f.cd, "despair", 4);
            double d = st.getBonusHP() * 0.03;
            double total = 0;
            for (var e : TargetingUtil.enemiesAround(p, 6.5)) if (isChampion(e)) { itemDamage(p, e, d, DamageUtil.Type.MAGICAL, false); total += d; }
            if (total > 0) hp.heal(total * 2.5);
        }

        // ── Rookern kaénique : bouclier anti-magie de 15 % des PV max après 15 s sans dégâts magiques ──
        if (has(p, "kaenic_rookern") && !f.kaenicShieldUp && now - f.kaenicLastMagic >= 15_000 && !dead) {
            f.kaenicShieldUp = true;
            double add = hp.getMaxHP() * 0.15 - st.getMagicShield();
            if (add > 0) st.addMagicShield(add);
        }

        // ── Spell shields : Voile de la banshee, Manteau de la nuit (40 s), Barrière verdoyante (60 s) ──
        boolean bansheeItem = has(p, "banshee_s_veil", "verdant_barrier");
        if (bansheeItem) {
            if (st.isBansheeShieldReady()) f.cd.remove("bansheePop");
            else {
                f.cd.putIfAbsent("bansheePop", now);
                double cd = has(p, "banshee_s_veil") ? 40 : 60;
                if (now - f.cd.get("bansheePop") >= cd * 1000) st.setBansheeShieldReady(true);
            }
        } else if (st.isBansheeShieldReady()) st.setBansheeShieldReady(false);
        if (has(p, "edge_of_night")) {
            if (st.isEonShieldReady()) f.cd.remove("eonPop");
            else {
                f.cd.putIfAbsent("eonPop", now);
                if (now - f.cd.get("eonPop") >= 40_000) st.setEonShieldReady(true);
            }
        } else if (st.isEonShieldReady()) st.setEonShieldReady(false);

        // ── Cœuracier : un champion ennemi à 700 unités pendant 3 s prépare la prochaine attaque ──
        if (has(p, "heartsteel") && !dead) {
            var near = new java.util.HashSet<UUID>();
            for (var e : TargetingUtil.enemiesAround(p, 7.0)) {
                if (!isChampion(e)) continue;
                UUID id = e.getUniqueId();
                near.add(id);
                if (!ready(f.cd, "heartsteel:" + id) || f.heartsteelReady.contains(id)) continue;
                int n = f.heartsteelTicks.merge(id, 1, Integer::sum);
                if (n >= 6) { f.heartsteelReady.add(id); f.heartsteelTicks.remove(id); }
            }
            f.heartsteelTicks.keySet().retainAll(near);
        }
        // PV gagnés par le Cœuracier (retirés si l'objet est vendu)
        double heartHp = f.cd.getOrDefault("heartsteelHP", 0L);
        if (heartHp > 0 && !has(p, "heartsteel")) { hp.addBonusHP(-heartHp); f.cd.remove("heartsteelHP"); }

        // ── Glaive d'ombre : 1 s hors de vue des ennemis → prochaine attaque prête 4 s ──
        if (has(p, "umbral_glaive")) {
            boolean seen = false;
            for (var e : TargetingUtil.enemiesAround(p, 13.5)) if (isChampion(e) && ((Player) e).canSee(p)) { seen = true; break; }
            if (seen) f.umbralLastVisible = now;
            else if (now - f.umbralLastVisible >= 1000 && f.umbralReadyUntil < now) f.umbralReadyUntil = now + 4000;
        }

        // ── Flux de mana : recharge des charges (8 s) ──
        for (var e : f.manaflowNextCharge.entrySet().toArray(new Map.Entry[0])) {
            @SuppressWarnings("unchecked") var en = (Map.Entry<String, Long>) e;
            if (en.getValue() > now) continue;
            String item = en.getKey();
            int max = switch (item) { case "archangel_s_staff", "whispering_circlet" -> 5; default -> 4; };
            int ch = f.manaflowCharges.getOrDefault(item, max) + 1;
            f.manaflowCharges.put(item, Math.min(max, ch));
            if (ch < max) f.manaflowNextCharge.put(item, now + 8000); else f.manaflowNextCharge.remove(item);
        }

        // ── Bâton séculaire : +10 PV, +30 mana, +3 puissance par minute (10 fois) ──
        if (has(p, "rod_of_ages")) {
            if (f.rodNext == 0) f.rodNext = now + 60_000;
            if (now >= f.rodNext && f.rodStacks < 10) {
                f.rodStacks++; f.rodNext = now + 60_000;
                if (mana) res.addMaxResource(30);
                // Au maximum d'effets cumulés, le porteur gagne un niveau
                if (f.rodStacks == 10) {
                    var ls = c.getLevelSystem();
                    double need = ls.getXPToNext() - ls.getCurrentXP();
                    if (need > 0) fr.lolmc.instance.InstanceHelper.rewardManager(p).grantProximityXP(p, need);
                }
            }
        } else if (f.rodStacks > 0) { if (mana) res.addMaxResource(-30.0 * f.rodStacks); f.rodStacks = 0; f.rodNext = 0; }
        dyn(p, "rodHP", f.rodStacks * 10.0, (s, v) -> hp.addBonusHP(v), st);
        dyn(p, "rodAP", f.rodStacks * 3.0, ChampionStats::addBonusAP, st);

        // ── Chapitre perdu : 20 % du mana max rendus en 3 s à chaque niveau ──
        if (f.lastLevel == 0) f.lastLevel = l;
        if (l > f.lastLevel) {
            if (mana && has(p, "lost_chapter")) for (int i = 1; i <= 6; i++) later(i * 10L, () -> res.addCurrent(res.getMax() * 0.20 / 6));
            f.lastLevel = l;
        }

        // ── Brise-coques : sbires de siège et super-sbires alliés proches, +70 (+6/niv dès 9) armure et RM ──
        if (has(p, "hullbreaker") && !dead) {
            double bonus = interp(70, 130, l) * (rng ? 0.5 : 1.0);
            var team = pl().getTeamManager().getTeam(p);
            for (var e : p.getNearbyEntities(10.5, 6, 10.5)) {
                if (!(e instanceof org.bukkit.entity.LivingEntity le) || !fr.lolmc.game.MinionManager.isMinion(le)) continue;
                String t = fr.lolmc.game.MinionManager.getMinionTypeTag(le);
                if (fr.lolmc.game.MinionManager.getMinionTeam(le) == team && ("cannon".equals(t) || "super".equals(t)))
                    fr.lolmc.util.MobCombat.setBoardingBonus(le, bonus);
            }
        }
        // ── Élixirs : sorcellerie (+15 % de régénération de mana), fer (sillage +15 % de vitesse aux alliés) ──
        var cons = pl().getConsumableManager();
        String elixir = cons != null ? cons.getActiveElixir(p) : null;
        dyn(p, "sorceryRegen", mana && "sorcery".equals(elixir) ? res.getBaseRegenPer5s() * 0.15 : 0, (s, v) -> res.addRegen(v), st);
        // Élixir de fer : en se déplaçant, le porteur laisse brièvement un sillage au sol ; les alliés
        // dedans gagnent 15 % de vitesse. La durée et la largeur du sillage ne sont publiées nulle part
        // (wiki, données du jeu : logique de script interne) : 1.5 s et 1 bloc de part et d'autre ici.
        var trail = TRAILS.computeIfAbsent(p.getUniqueId(), _ -> new java.util.ArrayDeque<>());
        if ("iron".equals(elixir) && !dead && moved > 0.1) trail.addLast(new Object[]{loc.clone(), now});
        while (!trail.isEmpty() && now - (Long) trail.peekFirst()[1] > 1500) trail.pollFirst();
        if (!trail.isEmpty())
            for (Player o : p.getWorld().getPlayers()) {
                if (o.equals(p) || !isChampion(o) || !pl().getTeamManager().areAllies(p, o)) continue;
                for (Object[] pt : trail)
                    if (((org.bukkit.Location) pt[0]).getWorld().equals(o.getWorld())
                            && ((org.bukkit.Location) pt[0]).distanceSquared(o.getLocation()) <= 1.0) {
                        pl().getCCManager().speedPct(o, 0.15, 12); break;
                    }
            }

        // ── Glaive d'ombre (Black-out) : révèle les balises ennemies proches ──
        var wmB = pl().getWardManager();
        if (has(p, "umbral_glaive") && !dead && wmB != null) {
            if (f.blackoutUntil < now && ready(f.cd, "blackout") && wmB.isSpottedByEnemyStealthWard(p)) {
                f.blackoutUntil = now + 8000;
                cooldown(f.cd, "blackout", 90);
            }
            if (f.blackoutUntil > now) {
                wmB.revealEnemyWards(p, p.getLocation(), 4.0, 2000L);
                wmB.disableEnemyWards(p, p.getLocation(), 4.0, 2000L); // la neutralisation persiste 2 s
            }
        }

        // ── Taille : Cœuracier (+3 % par tranche de 1000 PV max, max 30 %), Harnais protoplasmique (+15 %), Élixir de fer ──
        double size = 0;
        if (has(p, "heartsteel")) size += Math.min(0.30, 0.03 * Math.floor(hp.getMaxHP() / 1000));
        if (marked(p.getUniqueId(), "protoplasm")) size += 0.15;
        if ("iron".equals(elixir)) size += 0.15;
        var scaleAttr = p.getAttribute(fr.lolmc.util.Compat.scale());
        if (scaleAttr != null) {
            var key = new org.bukkit.NamespacedKey(pl(), "lol_item_scale");
            var cur = scaleAttr.getModifier(key);
            if (cur == null || Math.abs(cur.getAmount() - size) > 1e-4) {
                if (cur != null) scaleAttr.removeModifier(key);
                if (size > 0) scaleAttr.addTransientModifier(new org.bukkit.attribute.AttributeModifier(key, size,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR));
            }
        }

        // ── Volonté cosmique : +20 vitesse pendant 4 s ──
        dyn(p, "cosmicMS", has(p, "cosmic_drive") && f.cosmicUntil > now ? 20 : 0, ChampionStats::addBonusMoveSpeed, st);
        // ── Opposition céleste : fin de « Béni » → ralentit de 50 % 1.5 s à 500 unités, puis 18 s de recharge ──
        if (f.blessedActive && f.blessedUntil <= now) {
            f.blessedActive = false;
            cooldown(f.cd, "celestial", 18);
            for (var e : TargetingUtil.enemiesAround(p, 5.0)) pl().getCCManager().slowPct(e, 0.50, 30);
        }
        // ── Torche noire : +4 % de puissance par champion, monstre épique ou grand monstre en feu ──
        int burning = has(p, "blackfire_torch") ? blackfireTargets(p) : 0;
        dyn(p, "blackfireAP", (st.getFinalAP() - dynValue(p, "blackfireAP")) * 0.04 * burning, ChampionStats::addBonusAP, st);

        // ── Compagnons : friandise bonus stockée toutes les 60 s (90 s une fois adulte) ──
        if (has(p, "smite_stalker", "pickaxe_jungle", "blue_smite")) {
            if (f.nextBonusTreat == 0) f.nextBonusTreat = now + 60_000;
            if (now >= f.nextBonusTreat) { f.bonusTreats++; f.nextBonusTreat = now + (f.companion >= 35 ? 90_000 : 60_000); }
        } else { f.bonusTreats = 0; f.nextBonusTreat = 0; }

        // ── Compagnons de jungle adultes ──
        if (f.companion >= 35 && !dead) {
            // Saute-nuages : +30 % de vitesse dans un buisson (45 % après un grand monstre tué dedans),
            // qui décroît en 1.5 s une fois sorti
            var bush = pl().getBushManager();
            boolean inBush = bush != null && bush.isInBush(p);
            if (has(p, "smite_stalker")) {
                double pct = f.gaitInBush ? 0.45 : 0.30;
                if (inBush) pl().getCCManager().speedPct(p, pct, 12);
                else if (f.wasInBush) { pl().getCCManager().speedDecaying(p, pct, 30); f.gaitInBush = false; }
            }
            f.wasInBush = inBush;
            // Ixamandre : bouclier rechargé après 10 s hors combat
            if (has(p, "pickaxe_jungle") && hp.msSinceLastDamage() >= 10_000) mossShield(p);
            // Chardent : +3 cumuls par demi-seconde, prêt à 100
            if (has(p, "blue_smite") && !f.scorchReady && (f.scorchStacks += 3) >= 100) f.scorchReady = true;
        }
        if (!has(p, "smite_stalker", "pickaxe_jungle", "blue_smite")) { f.companion = 0; f.scorchReady = false; }

        // ══ Statistiques dynamiques ══
        double bonusMana = 0, itemManaRegen = 0;
        for (LolItem it : pl().getShopListener().getOrCreate(p).getEquippedItems())
            if (it != null) { bonusMana += it.getBonusMana(); itemManaRegen += it.getManaRegenPct(); }
        for (var e : f.cd.entrySet()) if (e.getKey().startsWith("manaflow:")) bonusMana += e.getValue();
        bonusMana += f.rodStacks * 30;

        // Effroi : Manamune / Muramana (2 % du mana max en AD), Archange (1 % du mana bonus en AP),
        // Séraphin (2 %), Approche de l'hiver / Fimbulvetr (15 % du mana bonus en PV)
        dyn(p, "aweAD", mana && has(p, "manamune", "muramana") ? res.getMax() * 0.02 : 0, ChampionStats::addBonusAD, st);
        dyn(p, "aweAP", mana && has(p, "seraphs_embrace") ? bonusMana * 0.02 : mana && has(p, "archangel_s_staff") ? bonusMana * 0.01 : 0,
                ChampionStats::addBonusAP, st);
        dyn(p, "aweHP", mana && has(p, "winter_s_approach", "fimbulwinter") ? bonusMana * 0.15 : 0, (s, v) -> hp.addBonusHP(v), st);
        // Gage de Sterak : +50 % de l'AD de base
        dyn(p, "sterakAD", has(p, "steraks_gage") ? st.getBaseAD() * 0.5 : 0, ChampionStats::addBonusAD, st);
        // Armure sanguine : 2.5 % des PV bonus en AD, et jusqu'à +12 % d'AD selon les PV manquants (max à 70 %)
        double overlordFlat = has(p, "overlord_s_bloodmail") ? (st.getBonusHP() - dynValue(p, "warmogHP")) * 0.025 : 0;
        dyn(p, "overlordAD", overlordFlat, ChampionStats::addBonusAD, st);
        double missing = 1 - hp.getHPRatio();
        double overlordPct = has(p, "overlord_s_bloodmail") ? 0.12 * Math.min(1, missing / 0.70) : 0;
        dyn(p, "overlordADPct", (st.getFinalAD() - dynValue(p, "overlordADPct")) * overlordPct, ChampionStats::addBonusAD, st);
        // Créateur de failles : 2 % des PV bonus en AP ; omnivampirisme au maximum de cumuls
        dyn(p, "riftAP", has(p, "riftmaker") ? st.getBonusHP() * 0.02 : 0, ChampionStats::addBonusAP, st);
        dyn(p, "riftVamp", has(p, "riftmaker") && champCombatSeconds(p) >= 4 ? (rng ? 0.06 : 0.10) : 0, ChampionStats::addBonusOmnivamp, st);
        // Armure de Warmog : +12 % des PV des objets
        double itemHP = 0;
        for (LolItem it : pl().getShopListener().getOrCreate(p).getEquippedItems()) if (it != null) itemHP += it.getBonusHP();
        dyn(p, "warmogHP", has(p, "warmogs_armor") ? itemHP * 0.12 : 0, (s, v) -> hp.addBonusHP(v), st);
        // Cœur de l'aube : +10 AP et +2 % d'efficacité des soins par 100 % de régénération de mana de base
        dyn(p, "dawnAP", has(p, "dawncore") ? itemManaRegen * 10 : 0, ChampionStats::addBonusAP, st);
        dyn(p, "dawnHSP", has(p, "dawncore") ? itemManaRegen * 0.02 : 0, ChampionStats::addHealShieldPower, st);
        // Diadème des murmures : efficacité des soins selon le mana bonus
        dyn(p, "circletHSP", has(p, "whispering_circlet") ? bonusMana * 0.005 / 100.0 : 0, ChampionStats::addHealShieldPower, st);
        // Faim insatiable : accélération de 5 + 13 % (10 % à distance) de l'AD bonus
        dyn(p, "furyAH", has(p, "unending_fury") ? 5 + (st.getBonusAD()) * (rng ? 0.10 : 0.13) : 0, ChampionStats::addBonusAbilityHaste, st);
        // Lance de Shojin : +25 accélération de base
        dyn(p, "shojinAH", has(p, "spear_of_shojin") ? 25 : 0, ChampionStats::addBonusAbilityHaste, st);
        // Accélération d'ultime : Malfaisance 20, Hexplaque 30, Chasseur de monstres 30, Zeke 15
        double ultHaste = (has(p, "malignance") ? 20 : 0) + (has(p, "experimental_hexplate") ? 30 : 0)
                + (has(p, "fiendhunter_bolts") ? 30 : 0) + (has(p, "zekess_convergence") ? 15 : 0);
        dyn(p, "ultHaste", ultHaste, ChampionStats::addUltimateHaste, st);
        // Célérité martiale : force adaptative de 5 % de la vitesse de déplacement
        double adaptive = has(p, "swiftmarch") ? st.getFinalMovementSpeed() * 0.05 : 0;
        boolean apChamp = st.getBonusAP() > st.getBonusAD();
        dyn(p, "swiftAD", apChamp ? 0 : adaptive * 0.6, ChampionStats::addBonusAD, st);
        dyn(p, "swiftAP", apChamp ? adaptive : 0, ChampionStats::addBonusAP, st);
        // Gloire : Sceau noir (+4 AP), Voleur d'âmes (+5 AP, +10 % de vitesse à 10 cumuls)
        boolean mejai = has(p, "mejai_s_soulstealer");
        if (!mejai && !has(p, "dark_seal")) f.glory = 0;
        dyn(p, "gloryAP", f.glory * (mejai ? 5.0 : 4.0), ChampionStats::addBonusAP, st);
        if (mejai && f.glory >= 10) pl().getCCManager().speedPct(p, 0.10, 12);
        // Orgueil : +12 AD (+3 par statue) pendant 90 s
        if (!has(p, "hubris")) f.hubrisStatues = 0;
        dyn(p, "hubrisAD", f.hubrisUntil > now && f.hubrisStatues > 0 ? 12 + 3 * f.hubrisStatues : 0, ChampionStats::addBonusAD, st);
        // Terminus : lumière (+6 armure et RM par cumul), ténèbres (+10 % de pénétrations par cumul)
        int light = has(p, "terminus") && f.terminusLightUntil > now ? f.terminusLightStacks : 0;
        int dark = has(p, "terminus") && f.terminusDarkUntil > now ? f.terminusDarkStacks : 0;
        dyn(p, "termArmor", light * 6.0, ChampionStats::addBonusArmor, st);
        dyn(p, "termMR", light * 6.0, ChampionStats::addBonusMR, st);
        dyn(p, "termAPen", dark * 0.10, ChampionStats::addBonusArmorPenPercent, st);
        dyn(p, "termMPen", dark * 0.10, ChampionStats::addBonusMagicPenPercent, st);
        // Jak'Sho : après 5 s de combat contre des champions, +30 % d'armure et de RM bonus
        boolean jak = has(p, "jaksho") && champCombatSeconds(p) >= 5;
        dyn(p, "jakArmor", jak ? (st.getBonusArmor() - dynValue(p, "jakArmor")) * 0.30 : 0, ChampionStats::addBonusArmor, st);
        dyn(p, "jakMR", jak ? (st.getBonusMagicResistValue() - dynValue(p, "jakMR")) * 0.30 : 0, ChampionStats::addBonusMR, st);
        // Force de la nature : 8 cumuls (perdus 7 s sans nouveau cumul) → +70 RM, +6 % de vitesse
        if (now > f.fonUntil) f.fonStacks = 0;
        boolean fon = has(p, "force_of_nature") && f.fonStacks >= 8;
        dyn(p, "fonMR", fon ? 70 : 0, ChampionStats::addBonusMR, st);
        if (fon) pl().getCCManager().speedPct(p, 0.06, 12);
        // Lame spectre de Youmuu : +20 (10 à distance) de vitesse hors combat
        dyn(p, "youmuuMS", has(p, "youmuus_ghostblade") && !hp.isInCombat() ? (rng ? 10 : 20) : 0, ChampionStats::addBonusMoveSpeed, st);
        // Flûtes de Bandle : Fanfare, +20 vitesse ; aura de vitesse d'attaque pour les alliés
        dyn(p, "fanfareMS", has(p, "bandlepipes") && f.fanfareUntil > now ? 20 : 0, ChampionStats::addBonusMoveSpeed, st);
        double fanfareAS = 0;
        for (Player o : p.getWorld().getPlayers()) {
            if (champ(o) == null || !(o.equals(p) || pl().getTeamManager().areAllies(p, o))) continue;
            if (fx(o).fanfareUntil > now && has(o, "bandlepipes") && o.getLocation().distanceSquared(p.getLocation()) <= 9 * 9) {
                fanfareAS = ranged(o) ? 0.20 : 0.30; break;
            }
        }
        dyn(p, "fanfareAS", fanfareAS, ChampionStats::addBonusAttackSpeed, st);
        // Visage spirituel (+25 %) et Voie immortelle (+12 % sous 50 % des PV) : soins et boucliers reçus
        double healAmp = (has(p, "spirit_visage") ? 0.25 : 0) + (has(p, "immortal_path") && hp.getHPRatio() < 0.5 ? 0.12 : 0);
        st.setHealAmpBonus(healAmp);
        // Jambières gloutonnes / Voie immortelle vendues : retirer l'omnivampirisme des cumuls
        if (f.carnage > 0 && !has(p, "gluttonous_greaves", "immortal_path")) { st.addBonusOmnivamp(-0.006 * f.carnage); f.carnage = 0; }

        // ── Cœur gelé : -20 % de vitesse d'attaque aux champions ennemis à 700 unités ──
        var affected = FROZEN.computeIfAbsent(p.getUniqueId(), _ -> new HashMap<>());
        var nowNear = new java.util.HashSet<UUID>();
        if (has(p, "frozen_heart") && !dead)
            for (var e : TargetingUtil.enemiesAround(p, 7.0)) if (isChampion(e)) nowNear.add(e.getUniqueId());
        for (var it = affected.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (nowNear.contains(e.getKey())) continue;
            Player v = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (v != null && champ(v) != null) champ(v).getStats().multiplyAS(1 / 0.8);
            it.remove();
        }
        for (UUID id : nowNear) {
            if (affected.containsKey(id)) continue;
            Player v = org.bukkit.Bukkit.getPlayer(id);
            if (v == null || champ(v) == null) continue;
            champ(v).getStats().multiplyAS(0.8);
            affected.put(id, 0.8);
        }
        if (light + dark > 0 && everySecond) VisualEffectUtil.impact(p.getWorld(), p.getLocation().add(0, 2.2, 0),
                light > 0 ? Material.WHITE_STAINED_GLASS : Material.BLACK_STAINED_GLASS, 0.12f, 10L);
    }
}
