package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.LolUnits;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les sorts d'invocateur de LoL (hors Flash, géré par FlashManager).
 *
 * Délais : Data Dragon 16.20.1. Valeurs : wiki officiel LoL (wiki.leagueoflegends.com, patch 26),
 * du niveau 1 au niveau 18 :
 *   - Embrasement : 70-475 dégâts bruts sur 5 s + 40 % de blessures graves — 180 s
 *   - Soins : 80-318 PV au lanceur et à un allié (900 u.), +30 % vitesse 1 s — 240 s
 *   - Barrière : bouclier de 100-460 pendant 2,5 s — 180 s
 *   - Fatigue : ralentit de 40 % et réduit les dégâts de 35 % pendant 3 s — 240 s
 *   - Téléportation : canalisation de 3 s vers une tourelle, un sbire ou une balise alliés — 300 s ;
 *     déchaînée à 10 min : 330-240 s et +50 % vitesse 3 s à l'arrivée
 *   - Châtiment : 600 dégâts bruts, 2 charges (1 au départ, recharge de 90 s dès 0:48) — 15 s
 *   - Purge : retire les contrôles et les effets de sorts d'invocateur, +75 % ténacité 3 s — 240 s
 *   - Fantôme : +24-48 % vitesse de déplacement pendant 10 s — 240 s
 */
public class SummonerSpellManager {

    public enum Spell {
        IGNITE("Embrasement", 180, "70-475 dégâts bruts en 5 s et 40% de blessures graves."),
        HEAL("Soins", 240, "Rend 80-318 PV à vous et à un allié, +30% de vitesse 1 s."),
        BARRIER("Barrière", 180, "Bouclier de 100-460 pendant 2,5 s."),
        EXHAUST("Fatigue", 240, "Ralentit un ennemi de 40% et réduit ses dégâts de 35% pendant 3 s."),
        TELEPORT("Téléportation", 300, "Après 3 s, vous rejoignez une tourelle, un sbire ou une balise alliés."),
        SMITE("Châtiment", 15, "600 dégâts bruts à un monstre ou un sbire. 2 charges (recharge 90 s)."),
        CLEANSE("Purge", 240, "Retire les contrôles et +75% de ténacité pendant 3 s."),
        GHOST("Fantôme", 240, "+24-48% de vitesse de déplacement pendant 10 s.");

        public final String displayName;
        public final int cooldownSeconds;
        public final String description;
        Spell(String displayName, int cd, String description) {
            this.displayName = displayName; this.cooldownSeconds = cd; this.description = description;
        }
    }

    /** Châtiment : 2 charges, chacune rechargée en 90 s (le délai de 15 s sépare deux lancers). */
    private static final int SMITE_CHARGES = 2;
    private static final long SMITE_RECHARGE_MS = 90_000L;
    /** Soins reçus de nouveau dans les 35 s : moitié moins efficaces. */
    private static final long HEAL_REDUCED_MS = 35_000L;

    // Cooldowns par joueur et par sort : "uuid:SPELL" → timestamp de fin
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<java.util.UUID, Spell[]> playerSpells = new java.util.HashMap<>();
    /** Fins de recharge des charges de Châtiment utilisées, par joueur. */
    private final Map<UUID, java.util.List<Long>> smiteRecharges = new HashMap<>();
    /** Dernier Soins reçu, par joueur. */
    private final Map<UUID, Long> healedAt = new HashMap<>();
    /** Embrasements en cours sur chaque cible (annulés par Purge). */
    private final Map<UUID, java.util.List<org.bukkit.scheduler.BukkitTask>> ignites = new HashMap<>();

    public Spell[] getSpells(Player player) { return playerSpells.get(player.getUniqueId()); }

    public boolean isOnCooldown(Player player, Spell spell) {
        return getRemainingCooldown(player, spell) > 0;
    }

    public double getRemainingCooldown(Player player, Spell spell) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId() + ":" + spell.name());
        long end = until != null ? until : 0;
        if (spell == Spell.SMITE && smiteCharges(player.getUniqueId(), now) == 0)
            end = Math.max(end, smiteRecharges.get(player.getUniqueId()).getFirst());
        return Math.max(0, (end - now) / 1000.0);
    }

    /** Charges de Châtiment disponibles (les recharges terminées sont retirées). */
    private int smiteCharges(UUID id, long now) {
        var l = smiteRecharges.get(id);
        if (l == null) return SMITE_CHARGES;
        l.removeIf(t -> t <= now);
        return SMITE_CHARGES - l.size();
    }

    /** Annule le cooldown d'un sort (remboursement TP interrompu). */
    public void resetCooldown(Player player, Spell spell) {
        cooldowns.remove(player.getUniqueId() + ":" + spell.name());
    }

    private void triggerCooldown(Player player, Spell spell) {
        var rm = LolPlugin.getInstance().getRuneManager();
        long now = System.currentTimeMillis();
        if (spell == Spell.SMITE) {
            // Une charge consommée : rechargée 90 s après la fin de la charge précédente
            var l = smiteRecharges.computeIfAbsent(player.getUniqueId(), _ -> new java.util.ArrayList<>());
            long from = l.isEmpty() ? now : Math.max(now, l.getLast());
            l.add(from + SMITE_RECHARGE_MS);
        }
        long base = spell == Spell.TELEPORT && isUnleashedTeleport(player)
                ? (long) (byLevel(player, 330, 240) * 1000) : spell.cooldownSeconds * 1000L;
        long cd = rm != null ? rm.summonerCooldownMs(player, base) : base;
        cooldowns.put(player.getUniqueId() + ":" + spell.name(), now + cd);
        // Icône de la barre d'action remise en couleur à la fin de la recharge
        long ready = (long) (getRemainingCooldown(player, spell) * 1000);
        org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(), () -> {
            var champ = LolPlugin.getInstance().getChampionManager().getChampion(player);
            if (player.isOnline() && champ != null && !isOnCooldown(player, spell))
                LolPlugin.getInstance().getHotbarManager().renderPage(player, champ);
        }, ready / 50L + 1L);
    }

    /** Téléportation déchaînée : à partir de 10 min de partie (330-240 s de délai). */
    private static boolean isUnleashedTeleport(Player p) {
        var gm = fr.lolmc.instance.InstanceHelper.gameManager(p);
        return gm != null && gm.isGameRunning() && gm.getElapsedSeconds() >= 600;
    }

    /**
     * Lance un sort d'invocateur. La cible est optionnelle (selon le sort).
     */
    public boolean cast(Player caster, Spell spell, Player target) {
        if (caster.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false; // mort
        if (isOnCooldown(caster, spell)) {
            caster.sendActionBar(Component.text(
                    "⏳ " + spell.displayName + " en recharge ("
                    + (int) Math.ceil(getRemainingCooldown(caster, spell)) + "s)", NamedTextColor.RED));
            return false;
        }
        // Étourdi ou projeté : aucun sort d'invocateur, sauf Purge (LoL)
        var cc = LolPlugin.getInstance().getCCManager();
        if (spell != Spell.CLEANSE && cc != null
                && (cc.isStunned(caster.getUniqueId()) || cc.isAirborne(caster.getUniqueId()))) {
            caster.sendActionBar(Component.text("❌ Impossible sous contrôle", NamedTextColor.RED));
            return false;
        }

        boolean success = switch (spell) {
            case IGNITE -> castIgnite(caster, target);
            case HEAL -> castHeal(caster, target);
            case BARRIER -> castBarrier(caster);
            case EXHAUST -> castExhaust(caster, target);
            case TELEPORT -> castTeleport(caster);
            case SMITE -> castSmite(caster);
            case CLEANSE -> castCleanse(caster);
            case GHOST -> castGhost(caster);
        };

        if (success) {
            triggerCooldown(caster, spell);
            fr.lolmc.instance.InstanceHelper.baseManager(caster).onAction(caster);
            var rm = LolPlugin.getInstance().getRuneManager();
            if (rm != null) rm.applyNimbusCloak(caster);
        }
        return success;
    }

    private static int level(Player p) {
        var cm = LolPlugin.getInstance().getChampionManager();
        return cm.hasChampion(p) ? cm.getChampion(p).getLevelSystem().getLevel() : 1;
    }

    /** Valeur selon le niveau du lanceur, de {@code at1} (niveau 1) à {@code at18} (niveau 18). */
    private static double byLevel(Player p, double at1, double at18) {
        return at1 + (at18 - at1) * (level(p) - 1) / 17.0;
    }

    // ── Embrasement : 70-410 dégâts bruts sur 5 s, 40 % de blessures graves ──

    private boolean castIgnite(Player caster, Player target) {
        if (target == null) {
            caster.sendActionBar(Component.text("🔥 Embrasement nécessite une cible", NamedTextColor.GRAY));
            return false;
        }
        // Portée d'Embrasement : 600 unités = 6 blocs
        if (caster.getLocation().distance(target.getLocation()) > LolUnits.toBlocks(600)) {
            caster.sendActionBar(Component.text("🔥 Cible hors de portée", NamedTextColor.GRAY));
            return false;
        }
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(target)) return false;

        // 70 au niveau 1, +20 par niveau jusqu'au 5, puis +25 : 475 au niveau 18 (patch 25.09)
        int lvl = level(caster);
        double perTick = (lvl <= 5 ? 50 + 20 * lvl : 150 + 25 * (lvl - 5)) / 5.0;
        // Blessures graves : 40 % (avant : 60 %)
        cm.getChampion(target).getStats().applyGrievousWounds(0.40, 5000L);
        var task = new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 5 || !target.isOnline() || target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    cancel();
                    return;
                }
                DamageUtil.damage(caster, target, perTick, true, DamageUtil.Type.TRUE);
                fr.lolmc.util.VisualEffectUtil.impact(target.getWorld(),
                        target.getLocation().add(0,1,0), Material.ORANGE_STAINED_GLASS, 0.22f, 4L);
                ticks++;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 20L, 20L);
        var list = ignites.computeIfAbsent(target.getUniqueId(), _ -> new java.util.ArrayList<>());
        list.removeIf(org.bukkit.scheduler.BukkitTask::isCancelled);
        list.add(task);

        caster.sendActionBar(Component.text("🔥 Embrasement sur " + target.getName() + "!", NamedTextColor.GOLD));
        return true;
    }

    // ── Soins : 80-318 PV + 30 % de vitesse au lanceur et à un allié ──

    private boolean castHeal(Player caster, Player target) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(caster)) return false;
        double amount = byLevel(caster, 80, 318);
        healWithSpell(caster, amount);
        // Allié : celui visé s'il est allié, sinon le plus blessé à portée (900 unités)
        Player ally = null;
        var tm = LolPlugin.getInstance().getTeamManager();
        if (target != null && tm.areAllies(caster, target) && !target.equals(caster)) ally = target;
        if (ally == null) {
            double lowest = 1.0, range = LolUnits.toBlocks(900);
            for (Player p : caster.getWorld().getPlayers()) {
                if (p.equals(caster) || !cm.hasChampion(p) || !tm.areAllies(caster, p)) continue;
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                if (p.getLocation().distance(caster.getLocation()) > range) continue;
                double r = cm.getChampion(p).getHPSystem().getHPRatio();
                if (r < lowest) { lowest = r; ally = p; }
            }
        }
        if (ally != null) {
            healWithSpell(ally, amount);
            ally.sendActionBar(Component.text("💚 Soins de " + caster.getName(), NamedTextColor.GREEN));
        }
        caster.sendActionBar(Component.text("💚 Soins ! +" + (int) amount + " PV", NamedTextColor.GREEN));
        return true;
    }

    private void healWithSpell(Player p, double amount) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(p)) return;
        BaseChampion champ = cm.getChampion(p);
        long now = System.currentTimeMillis();
        Long last = healedAt.put(p.getUniqueId(), now);
        if (last != null && now - last < HEAL_REDUCED_MS) amount *= 0.5; // déjà soigné récemment
        champ.getHPSystem().heal(amount);
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null) cc.speedDecaying(p, 0.30, 20); // +30 % décroissant sur 1 s
        fr.lolmc.util.VisualEffectUtil.impactBurst(p.getWorld(),
                p.getLocation().add(0,1,0), Material.PINK_STAINED_GLASS, 0.25f, 0.5, 6, 6L);
        var hud = LolPlugin.getInstance().getHUDManager();
        if (hud != null) hud.updateHUD(p, champ);
    }

    // ── Barrière : bouclier de 100-460 pendant 2,5 s ──

    private boolean castBarrier(Player caster) {
        var cm = LolPlugin.getInstance().getChampionManager();
        if (!cm.hasChampion(caster)) return false;
        BaseChampion champ = cm.getChampion(caster);
        double shield = byLevel(caster, 100, 100 + 21.176 * 17); // 100 → 502 au niveau 20
        champ.getStats().addShield(shield);
        fr.lolmc.util.VisualEffectUtil.impactBurst(caster.getWorld(),
                caster.getLocation().add(0,1,0), Material.YELLOW_STAINED_GLASS, 0.3f, 0.6, 8, 8L);
        caster.sendActionBar(Component.text("🛡 Barrière ! +" + (int) shield, NamedTextColor.YELLOW));
        // Le bouclier disparaît après 2,5 s — seulement celui-ci, pas les autres boucliers
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() { champ.getStats().addShield(-shield); }
        }.runTaskLater(LolPlugin.getInstance(), 50L);
        return true;
    }

    // ── Fatigue : ralentit de 40 % et réduit les dégâts infligés de 35 % pendant 3 s ──

    private boolean castExhaust(Player caster, Player target) {
        if (target == null) {
            caster.sendActionBar(Component.text("Fatigue nécessite une cible", NamedTextColor.GRAY));
            return false;
        }
        // Portée : 650 unités = 6,5 blocs
        if (caster.getLocation().distance(target.getLocation()) > LolUnits.toBlocks(650)) {
            caster.sendActionBar(Component.text("Cible hors de portée", NamedTextColor.GRAY));
            return false;
        }
        // Ralentissement en % (avant : potion Lenteur III, et Faiblesse vanilla sans effet LoL)
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null) cc.slowPct(target, 0.40, 60);
        exhaust(target, 3000L);
        target.sendActionBar(Component.text("😵 Fatigué par " + caster.getName() + "!", NamedTextColor.GRAY));
        caster.sendActionBar(Component.text("Fatigue lancée !", NamedTextColor.AQUA));
        return true;
    }

    // ── Téléportation : vers une tourelle, un sbire ou une balise alliés ──

    // UUID → tâche de TP en cours (canalisation interruptible)
    private final java.util.Map<java.util.UUID, org.bukkit.scheduler.BukkitTask> channeling
        = new java.util.concurrent.ConcurrentHashMap<>();
    /** BlockDisplay de la canalisation de TP en cours, par joueur. */
    private final java.util.Map<java.util.UUID, org.bukkit.entity.BlockDisplay> channelDisplays
        = new java.util.concurrent.ConcurrentHashMap<>();

    /** Interrompt la canalisation de TP (appelé quand le caster prend des dégâts/CC). */
    public void interruptTeleport(Player caster) {
        var task = channeling.remove(caster.getUniqueId());
        removeChannelDisplay(caster);
        if (task != null) {
            task.cancel();
            caster.sendActionBar(net.kyori.adventure.text.Component.text(
                "❌ Téléportation interrompue!", net.kyori.adventure.text.format.NamedTextColor.RED));
            caster.getWorld().playSound(caster.getLocation(),
                org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH, 1f, 0.8f);
            // LoL : une canalisation interrompue par un ennemi ne rembourse pas le délai
            // (avant : délai entièrement rendu, TP relançable aussitôt)
        }
    }

    /** Crée le BlockDisplay public (visible par tous) de canalisation de TP. */
    private org.bukkit.entity.BlockDisplay spawnChannelBlock(Location loc) {
        return loc.getWorld().spawn(loc, org.bukkit.entity.BlockDisplay.class, disp -> {
            disp.setBlock(Material.PURPLE_STAINED_GLASS.createBlockData());
            disp.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setInterpolationDuration(2);
            disp.setInterpolationDelay(0);
            float size = 0.22f;
            disp.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(-size / 2f, -size / 2f, -size / 2f),
                    new org.joml.Quaternionf(),
                    new org.joml.Vector3f(size, size, size),
                    new org.joml.Quaternionf()));
        });
    }

    private void removeChannelDisplay(Player caster) {
        var d = channelDisplays.remove(caster.getUniqueId());
        if (d != null && !d.isDead()) d.remove();
    }

    public boolean isChanneling(Player caster) {
        return channeling.containsKey(caster.getUniqueId());
    }

    /**
     * Canalise 3 s avec particules, puis exécute l'action si non interrompu.
     * Interruptible via interruptTeleport().
     */
    private void channelTeleport(Player caster, Runnable onComplete) {
        // Annuler une canalisation précédente
        var old = channeling.remove(caster.getUniqueId());
        if (old != null) old.cancel();

        var task = new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!caster.isOnline()) { channeling.remove(caster.getUniqueId()); removeChannelDisplay(caster); cancel(); return; }
                if (ticks >= 60) { // 3 s écoulées → TP
                    channeling.remove(caster.getUniqueId());
                    removeChannelDisplay(caster);
                    cancel();
                    onComplete.run();
                    return;
                }
                // Cercle de canalisation violet autour du caster (point qui monte en spirale)
                double progress = ticks / 60.0;
                double angle = ticks * 0.3;
                var loc = caster.getLocation();
                var display = channelDisplays.computeIfAbsent(caster.getUniqueId(),
                        k -> spawnChannelBlock(loc));
                display.teleport(loc.clone().add(Math.cos(angle) * 1.2, 0.1 + progress * 2.0, Math.sin(angle) * 1.2));
                ticks += 2;
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);
        channeling.put(caster.getUniqueId(), task);
    }

    private void startTeleport(Player caster, Location dest, String label) {
        caster.sendActionBar(Component.text("🌀 Téléportation → " + label + " dans 3s...", NamedTextColor.AQUA));
        channelTeleport(caster, () -> {
            if (!caster.isOnline()) return;
            caster.teleport(dest);
            caster.playSound(dest, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            // LoL : le délai démarre à l'arrivée, pas au lancement
            triggerCooldown(caster, Spell.TELEPORT);
            // Téléportation déchaînée (10 min) : +50 % de vitesse pendant 3 s à l'arrivée
            var cc = LolPlugin.getInstance().getCCManager();
            if (isUnleashedTeleport(caster) && cc != null) cc.speedPct(caster, 0.50, 60);
        });
    }

    private boolean castTeleport(Player caster) {
        // LoL : TP vers une tourelle, un sbire ou une balise alliés, visés au curseur.
        // (Avant : sans cible visée, retour gratuit à la base — un deuxième Rappel instantané.)
        var tm = LolPlugin.getInstance().getTeamManager();
        var team = tm.getTeam(caster);
        if (team == null) return false;
        var eye = caster.getEyeLocation();
        var dir = eye.getDirection().normalize();

        Location best = null;
        String label = null;
        double bestDot = 0.85;

        // 1. Tourelles alliées debout (les inhibiteurs et le Nexus ne sont pas des cibles de TP)
        var mapMgr = fr.lolmc.instance.InstanceHelper.mapManager(caster);
        if (mapMgr != null) {
            for (var s : mapMgr.getStructuresForTeam(team)) {
                if (s.isDestroyed() || s.getType() != fr.lolmc.game.GameStructure.Type.TURRET) continue;
                var toS = s.getCenter().toVector().subtract(eye.toVector());
                double dist = toS.length();
                if (dist > 500 || dist < 1) continue;
                double dot = toS.normalize().dot(dir);
                if (dot > bestDot) { bestDot = dot; best = s.getCenter(); label = "tourelle"; }
            }
        }
        // 2. Sbires alliés (portée 50 blocs)
        for (var e : caster.getNearbyEntities(50, 50, 50)) {
            if (!(e instanceof LivingEntity le) || !MinionManager.isMinion(le)) continue;
            if (MinionManager.getMinionTeam(le) != team) continue;
            var toE = e.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            double dot = toE.normalize().dot(dir);
            if (dot > bestDot) { bestDot = dot; best = le.getLocation().add(0, 0.1, 0); label = "sbire allié"; }
        }
        // 3. Balises alliées
        var wm = LolPlugin.getInstance().getWardManager();
        if (wm != null) {
            for (var w : wm.getAllWards()) {
                if (w.team != team || w.entityId == null || !w.location.getWorld().equals(caster.getWorld())) continue;
                var toW = w.location.toVector().subtract(eye.toVector());
                double dist = toW.length();
                if (dist > 500 || dist < 1) continue;
                double dot = toW.normalize().dot(dir);
                if (dot > bestDot) { bestDot = dot; best = w.location.clone(); label = "balise alliée"; }
            }
        }
        if (best == null) {
            caster.sendActionBar(Component.text("🌀 Vise une tourelle, un sbire ou une balise alliés",
                    NamedTextColor.GRAY));
            return false;
        }
        startTeleport(caster, best, label);
        return true;
    }

    // ── Châtiment : 600 dégâts bruts à un monstre ou à un sbire ennemi ──

    private boolean castSmite(Player caster) {
        double range = LolUnits.toBlocks(500);
        var inv = LolPlugin.getInstance().getShopListener().getOrCreate(caster);
        // Objets de jungle (ids du registre : Mosstomper = pickaxe_jungle, Gustwalker = smite_stalker,
        // Scorchclaw = blue_smite) : Châtiment déchaîné, utilisable sur les champions
        boolean hasGustwalker = inv.hasItem("smite_stalker");
        boolean unleashed = inv.hasItem("pickaxe_jungle") || hasGustwalker || inv.hasItem("blue_smite");

        // Cible : l'unité visée, sinon le monstre puis le sbire ennemi le plus proche.
        // (Avant : un champion ennemi à portée passait AVANT le monstre, même en visant le dragon.)
        LivingEntity target = fr.lolmc.util.TargetingUtil.getTargetedEnemy(caster, range);
        if (target instanceof Player && !unleashed) target = null;
        if (target == null) target = nearest(caster, range, true);
        if (target == null) target = nearest(caster, range, false);
        if (target == null) {
            caster.sendActionBar(Component.text("⚔ Aucune cible à châtier", NamedTextColor.GRAY));
            return false;
        }

        if (target instanceof Player enemy) {
            // Châtiment déchaîné sur un champion : 40 dégâts bruts + 20 % de ralentissement 2 s (avant : 600)
            double dmg = 40;
            DamageUtil.damage(caster, enemy, dmg, true, DamageUtil.Type.TRUE);
            var cc = LolPlugin.getInstance().getCCManager();
            if (cc != null) cc.slowPct(enemy, 0.20, 40);
            caster.sendActionBar(Component.text("⚔ Châtiment → " + enemy.getName() + " (" + (int) dmg + ")",
                    NamedTextColor.RED));
            fr.lolmc.util.VisualEffectUtil.impactBurst(caster.getWorld(),
                    enemy.getLocation().add(0,1,0), Material.RED_STAINED_GLASS, 0.3f, 0.4, 8, 6L);
            return true;
        }

        double smiteDamage = 600;
        // Gustwalker : +20 vitesse 2 s après un châtiment
        if (hasGustwalker) {
            var sc = LolPlugin.getInstance().getChampionManager().getChampion(caster);
            if (sc != null) {
                sc.getStats().addBonusMoveSpeed(20);
                new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){
                    sc.getStats().addBonusMoveSpeed(-20);
                }}.runTaskLater(LolPlugin.getInstance(), 40L);
            }
        }
        // HP réels (virtuels si Baron/Elder/canon, sinon vanilla)
        double cur = fr.lolmc.util.VirtualHP.has(target)
            ? fr.lolmc.util.VirtualHP.getCurrent(target) : target.getHealth();
        // Dégâts directs (un target.damage() vanilla serait annulé par AbilityListener)
        if (smiteDamage >= cur) {
            if (fr.lolmc.util.VirtualHP.has(target))
                fr.lolmc.util.VirtualHP.damage(target, cur + 100, caster);
            else fr.lolmc.util.VirtualHP.kill(target, caster);
            caster.sendActionBar(Component.text("⚔ Châtiment — EXÉCUTÉ!", NamedTextColor.GOLD));
        } else {
            if (fr.lolmc.util.VirtualHP.has(target))
                fr.lolmc.util.VirtualHP.damage(target, smiteDamage, caster);
            else {
                double left = target.getHealth() - smiteDamage;
                target.setHealth(left);
                fr.lolmc.util.HealthBar.update(target, left, fr.lolmc.util.Compat.maxHealthOf(target));
            }
            caster.sendActionBar(Component.text("⚔ Châtiment! (" + (int) smiteDamage + ")", NamedTextColor.GOLD));
        }
        fr.lolmc.util.VisualEffectUtil.impactBurst(target.getWorld(),
                target.getLocation().add(0,1,0), Material.RED_STAINED_GLASS, 0.3f, 0.4, 8, 6L);
        return true;
    }

    /** Monstre de jungle (ou sbire ennemi) le plus proche à portée de Châtiment. */
    private LivingEntity nearest(Player caster, double range, boolean monster) {
        var team = LolPlugin.getInstance().getTeamManager().getTeam(caster);
        LivingEntity best = null;
        double closest = Double.MAX_VALUE;
        for (var e : caster.getNearbyEntities(range, range, range)) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            boolean ok = monster ? JungleManager.isJungleMonster(le)
                    : MinionManager.isMinion(le) && MinionManager.getMinionTeam(le) != team;
            if (!ok) continue;
            double d = le.getLocation().distance(caster.getLocation());
            if (d <= range && d < closest) { closest = d; best = le; }
        }
        return best;
    }

    // ── Purge : retire les contrôles et les effets de sorts d'invocateur, +75 % ténacité 3 s ──

    private boolean castCleanse(Player caster) {
        // Avant : retirait seulement les potions (Lenteur, Faiblesse, Cécité), pas les vrais
        // étourdissements / immobilisations / silences du CCManager
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null) {
            cc.cleanse(caster);
            cc.clearSlows(caster.getUniqueId());
        }
        exhaustedUntil.remove(caster.getUniqueId());
        var burning = ignites.remove(caster.getUniqueId());
        if (burning != null) burning.forEach(org.bukkit.scheduler.BukkitTask::cancel);
        var cm = LolPlugin.getInstance().getChampionManager();
        if (cm.hasChampion(caster)) {
            var stats = cm.getChampion(caster).getStats();
            stats.addTenacity(0.75);
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() { stats.addTenacity(-0.75); }
            }.runTaskLater(LolPlugin.getInstance(), 60L);
        }
        fr.lolmc.util.VisualEffectUtil.impactBurst(caster.getWorld(),
                caster.getLocation().add(0,1,0), Material.LIGHT_BLUE_STAINED_GLASS, 0.3f, 0.5, 8, 8L);
        caster.sendActionBar(Component.text("✨ Purge !", NamedTextColor.AQUA));
        return true;
    }

    // ── Fantôme : +24-48 % de vitesse de déplacement pendant 10 s ──

    private boolean castGhost(Player caster) {
        // Avant : potion Vitesse II, qui s'ajoutait au système de vitesse LoL sans en tenir compte
        double pct = byLevel(caster, 0.24, 0.48);
        var cc = LolPlugin.getInstance().getCCManager();
        if (cc != null) cc.speedPct(caster, pct, 200);
        fr.lolmc.util.VisualEffectUtil.impactBurst(caster.getWorld(),
                caster.getLocation(), Material.WHITE_STAINED_GLASS, 0.3f, 0.5, 6, 8L);
        caster.sendActionBar(Component.text("👻 Fantôme ! +" + Math.round(pct * 100) + "% vitesse (10s)",
                NamedTextColor.WHITE));
        return true;
    }

    public void cleanup(UUID uuid) {
        cooldowns.entrySet().removeIf(e -> e.getKey().startsWith(uuid.toString()));
        exhaustedUntil.remove(uuid);
        flashChosen.remove(uuid);
        smiteRecharges.remove(uuid);
        healedAt.remove(uuid);
        var burning = ignites.remove(uuid);
        if (burning != null) burning.forEach(org.bukkit.scheduler.BukkitTask::cancel);
    }


    /**
     * Définit les sorts d'invocateur d'un joueur (appelé par BridgeManager).
     */
    public void setSpells(org.bukkit.entity.Player player, String spell1, String spell2) {
        // FLASH est géré par FlashManager (toujours disponible, hors de cet enum) : seuls les
        // autres sorts choisis sont retenus. Avant, Flash était remplacé par un sort arbitraire.
        java.util.List<Spell> chosen = new java.util.ArrayList<>(2);
        flashChosen.put(player.getUniqueId(),
                "FLASH".equalsIgnoreCase(spell1) || "FLASH".equalsIgnoreCase(spell2));
        for (String name : new String[]{spell1, spell2}) {
            Spell sp = parseSpell(name);
            if (sp != null && !chosen.contains(sp)) chosen.add(sp);
        }
        playerSpells.put(player.getUniqueId(), chosen.toArray(new Spell[0]));
        // Châtiment : 1 charge au départ ; la recharge démarre à 0:48 (2e charge à 2:18, patch 26.01)
        smiteRecharges.remove(player.getUniqueId());
        if (chosen.contains(Spell.SMITE))
            smiteRecharges.put(player.getUniqueId(), new java.util.ArrayList<>(
                    java.util.List.of(System.currentTimeMillis() + 48_000L + SMITE_RECHARGE_MS)));
    }

    /** Le joueur a-t-il pris Flash ? (vrai par défaut si aucun choix n'a été fait) */
    private final Map<UUID, Boolean> flashChosen = new java.util.concurrent.ConcurrentHashMap<>();
    public boolean hasFlash(Player player) { return flashChosen.getOrDefault(player.getUniqueId(), true); }

    // ── Fatigue : -35 % de dégâts infligés ──
    private final Map<UUID, Long> exhaustedUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /** Fatigue {@code target} : -35 % de dégâts infligés pendant {@code durationMs}. */
    public void exhaust(Player target, long durationMs) {
        exhaustedUntil.merge(target.getUniqueId(), System.currentTimeMillis() + durationMs, Math::max);
    }

    /** Multiplicateur des dégâts infligés par {@code attacker} (0,65 s'il est épuisé, sinon 1). */
    public double outgoingDamageMultiplier(Player attacker) {
        Long until = exhaustedUntil.get(attacker.getUniqueId());
        if (until == null) return 1.0;
        if (System.currentTimeMillis() >= until) { exhaustedUntil.remove(attacker.getUniqueId()); return 1.0; }
        return 0.65;
    }

    private static Spell parseSpell(String name) {
        if (name == null) return null;
        try { return Spell.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
