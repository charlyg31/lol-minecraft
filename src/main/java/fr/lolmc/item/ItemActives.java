package fr.lolmc.item;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.TargetingUtil;
import fr.lolmc.util.VisualEffectUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import static fr.lolmc.item.ItemEffects.*;

/**
 * Actifs des objets (clic droit dans la barre d'action), aux valeurs du jeu (patch 26.20). Les
 * actifs d'objets retirés de LoL (Galeforce, Everfrost, Goredrinker…) ne sont plus gérés.
 */
public final class ItemActives {

    private ItemActives() {}

    /** Utilise l'actif de {@code itemId}. */
    public static void activate(Player p, String itemId) {
        BaseChampion c = champ(p);
        if (c == null) return;
        Fx f = fx(p);
        var st = c.getStats();
        int l = lvl(p);
        var cc = pl().getCCManager();
        switch (itemId) {
            // ── Stase : Sablier de Zhonya (120 s), Protège-bras du savant (une fois) ──
            case "zhonyas_hourglass", "seeker_s_armguard" -> {
                boolean seeker = itemId.equals("seeker_s_armguard");
                if (!seeker && !use(p, f, "zhonya", 120)) return;
                stasis(p, 50);
                if (seeker) transform(p, "seeker_s_armguard", "shattered_armguard");
            }
            // ── Ceinture de mercure / Cimeterre mercuriel (90 s) : purge, +50 % de vitesse 2 s (Cimeterre) ──
            case "quicksilver_sash", "mercurial_scimitar" -> {
                if (!use(p, f, "qss", 90)) return;
                cc.cleanse(p);
                if (itemId.equals("mercurial_scimitar")) cc.speedPct(p, 0.50, 40);
                p.sendActionBar(Component.text("✨ Mercure : contrôles retirés", NamedTextColor.WHITE));
            }
            // ── Tiamat (75 % AD), Hydre vorace (80 % AD, vol de vie), Hydre profane (80 % AD), rayon 450, 10 s ──
            case "tiamat", "ravenous_hydra", "profane_hydra" -> {
                if (!use(p, f, "hydra", 10)) return;
                double ratio = itemId.equals("tiamat") ? 0.75 : 0.80;
                double total = 0;
                for (var e : TargetingUtil.enemiesAround(p, 4.5)) {
                    double d = st.getFinalAD() * ratio;
                    itemDamage(p, e, d, DamageUtil.Type.PHYSICAL, true);
                    total += d;
                }
                if (itemId.equals("ravenous_hydra")) c.getHPSystem().heal(total * st.getFinalLifeSteal());
                VisualEffectUtil.impactBurst(p.getWorld(), p.getLocation().add(0, 1, 0), Material.RED_STAINED_GLASS, 0.28f, 2.0, 8, 6L);
            }
            // ── Hydre titanesque : renforce le prochain Fendoir (10 s) ──
            case "titanic_hydra" -> {
                if (!use(p, f, "hydra", 10)) return;
                f.cd.put("titanicEmpowered", Long.MAX_VALUE);
                pl().getAutoAttackManager().resetAttackTimer(p);
                p.sendActionBar(Component.text("🪓 Croissant titanesque : prochaine attaque renforcée", NamedTextColor.RED));
            }
            // ── Estropieur (15 s) : 80 % AD autour, -35 % 3 s, +35 % de vitesse par champion touché (décroît en 3 s) ──
            case "stridebreaker" -> {
                if (!use(p, f, "stridebreaker", 15)) return;
                int champs = 0;
                for (var e : TargetingUtil.enemiesAround(p, 4.5)) {
                    itemDamage(p, e, st.getFinalAD() * 0.80, DamageUtil.Type.PHYSICAL, false);
                    cc.slowDecaying(e, 0.35, 60);
                    if (isChampion(e)) champs++;
                }
                if (champs > 0) cc.speedDecaying(p, 0.35 * champs, 60);
                VisualEffectUtil.groundRing(p.getWorld(), p.getLocation().add(0, 0.3, 0), 4.5, Material.YELLOW_STAINED_GLASS, 18, 0.3f, 0.1f, 8L);
            }
            // ── Lame spectre de Youmuu (45 s) : +20 % de vitesse (15 % à distance), fantomatique 6 s (4 s à distance) ──
            case "youmuus_ghostblade" -> {
                if (!use(p, f, "youmuu", 45)) return;
                boolean rng = ranged(p);
                cc.speedPct(p, rng ? 0.15 : 0.20, rng ? 80 : 120);
            }
            // ── Pistolame Hextech (60 s) : 175-253 (+30 % AP) magiques au champion ciblé, -25 % 1.5 s ──
            case "hextech_gunblade" -> {
                var t = TargetingUtil.getTargetedEnemy(p, 7.0);
                if (t == null || !isChampion(t)) { p.sendActionBar(Component.text("Aucun champion visé.", NamedTextColor.GRAY)); return; }
                if (!use(p, f, "gunblade", 60)) return;
                itemDamage(p, t, interp(175, 253, l) + st.getFinalAP() * 0.30, DamageUtil.Type.MAGICAL, false);
                cc.slowPct(t, 0.25, 30);
            }
            // ── Ceinture-roquette Hextech (50 s) : ruée, projectiles de 135 + 15 % AP ──
            case "rocketbelt" -> {
                if (!use(p, f, "rocketbelt", 50)) return;
                Location dst = TargetingUtil.getAimedGroundLocation(p, 2.75);
                Location from = p.getLocation();
                org.bukkit.util.Vector dir = dst.toVector().subtract(from.toVector()).setY(0);
                if (dir.lengthSquared() < 1e-6) dir = from.getDirection().setY(0);
                dir.normalize();
                Location safe = dst.clone(); safe.setYaw(from.getYaw()); safe.setPitch(from.getPitch());
                p.teleport(safe);
                double d = 135 + st.getFinalAP() * 0.15;
                for (var e : TargetingUtil.enemiesInCone(p, 7.5, 40)) itemDamage(p, e, d, DamageUtil.Type.MAGICAL, false);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
            }
            // ── Présage de Randuin (90 s) : -70 % aux ennemis proches pendant 2 s ──
            case "randuin_s_omen" -> {
                if (!use(p, f, "randuin", 90)) return;
                for (var e : TargetingUtil.enemiesAround(p, 5.0)) cc.slowPct(e, 0.70, 40);
                VisualEffectUtil.groundRing(p.getWorld(), p.getLocation().add(0, 0.3, 0), 5.0, Material.GRAY_STAINED_GLASS, 18, 0.3f, 0.1f, 8L);
            }
            // ── Chant de guerre de Shurelya (75 s) : +30 % de vitesse 4 s aux alliés à 1000 unités ──
            case "shurelyas" -> {
                if (!use(p, f, "shurelya", 75)) return;
                for (Player a : allies(p, 10.0)) cc.speedPct(a, 0.30, 80);
            }
            // ── Médaillon de l'Iron Solari (90 s) : bouclier 290 (+7/niveau dès 9) aux alliés à 850, 2.5 s ──
            case "locket" -> {
                if (!use(p, f, "locket", 90)) return;
                double shield = bp(290, 9, 7, l);
                for (Player a : allies(p, 8.5)) {
                    // Effet réduit à 25 % sur un allié protégé par un autre Médaillon dans les 20 s
                    double s = marked(a.getUniqueId(), "locketRecent") ? shield * 0.25 : shield;
                    mark(a.getUniqueId(), "locketRecent", 20);
                    tempShield(a, s, 50, false);
                    if (!a.equals(p)) onAllyHealShield(p, a, 0, shield);
                }
            }
            // ── Bénédiction de Mikael (120 s) : purge un allié et lui rend 100-250 PV ──
            case "mikaels" -> {
                Player target = null; double best = Double.MAX_VALUE;
                for (Player a : allies(p, 6.5)) {
                    if (a.equals(p)) continue;
                    double ratio = champ(a).getHPSystem().getHPRatio();
                    if (ratio < best) { best = ratio; target = a; }
                }
                if (target == null) { p.sendActionBar(Component.text("Aucun allié à portée.", NamedTextColor.GRAY)); return; }
                if (!use(p, f, "mikael", 120)) return;
                cc.cleanse(target);
                double heal = interp(100, 250, l);
                champ(target).getHPSystem().heal(heal * (1 + st.getHealShieldPower()));
                onAllyHealShield(p, target, heal, 0);
            }
            // ── Rédemption (90 s) : après 2.5 s, rend 150-350 PV aux alliés et 10 % des PV max en dégâts bruts aux champions ennemis ──
            case "redemption" -> {
                if (!use(p, f, "redemption", 90)) return;
                Location at = TargetingUtil.getAimedGroundLocation(p, 30.0);
                double heal = interp(150, 350, l) * (1 + st.getHealShieldPower());
                VisualEffectUtil.groundRing(at.getWorld(), at, 5.5, Material.WHITE_STAINED_GLASS, 20, 0.35f, 0.1f, 50L);
                later(50, () -> {
                    for (Player o : at.getWorld().getPlayers()) {
                        if (!isChampion(o) || o.getLocation().distanceSquared(at) > 5.5 * 5.5) continue;
                        if (o.equals(p) || pl().getTeamManager().areAllies(p, o)) {
                            // Effet réduit de moitié sur un allié soigné par une autre Rédemption dans les 8 s
                            double h = marked(o.getUniqueId(), "redemptionRecent") ? heal * 0.5 : heal;
                            mark(o.getUniqueId(), "redemptionRecent", 8);
                            champ(o).getHPSystem().heal(h);
                            if (!o.equals(p)) onAllyHealShield(p, o, heal, 0);
                        } else if (pl().getTeamManager().areEnemies(p, o)) {
                            itemDamage(p, o, champ(o).getHPSystem().getMaxHP() * 0.10, DamageUtil.Type.TRUE, false);
                        }
                    }
                });
            }
            // ── Vœu du chevalier : désigne l'allié visé (ou le plus proche) comme digne (60 s) ──
            case "knight_s_vow" -> {
                Player target = null; double best = 12.5 * 12.5;
                for (Player a : allies(p, 12.5)) {
                    if (a.equals(p)) continue;
                    double d = a.getLocation().distanceSquared(p.getLocation());
                    if (d < best) { best = d; target = a; }
                }
                if (target == null) { p.sendActionBar(Component.text("Aucun allié à portée.", NamedTextColor.GRAY)); return; }
                if (!use(p, f, "knightvow", 60)) return;
                f.knightPartner = target.getUniqueId();
                p.sendActionBar(Component.text("⚔ Serment : " + target.getName(), NamedTextColor.GOLD));
            }
            // ── Actualisateur (60 s) : mana renforcé 8 s (coûts ×2, dégâts/soins/boucliers +15 % + 0.5 % du mana bonus, -30 % de recharge des sorts de base) ──
            case "actualizer" -> {
                if (!use(p, f, "actualizer", 60)) return;
                mark(p.getUniqueId(), "actualizer", 8);
                p.sendActionBar(Component.text("💧 Renforts de mana (8 s)", NamedTextColor.AQUA));
            }
            // ── Objets de support : balise camouflée (charges rechargées à la boutique) ──
            case "bloodsong", "bounty_worlds", "celestial_opposition", "dream_maker", "runic_compass",
                 "solstice_sleigh", "zaz_zak_s_realmspike", "world_atlas" -> {
                int max = itemId.equals("runic_compass") || itemId.equals("world_atlas") ? 3 : 4;
                if (pl().getShopListener().canShopHere(p)) f.cd.put("supportWards", (long) max);
                long charges = f.cd.getOrDefault("supportWards", (long) max);
                if (charges <= 0) { p.sendActionBar(Component.text("Plus de balise (rechargées à la boutique).", NamedTextColor.GRAY)); return; }
                var wm = pl().getWardManager();
                if (wm == null) return;
                f.cd.put("supportWards", charges - 1);
                wm.placeWard(p, p.getLocation(), 90);
                p.sendActionBar(Component.text("👁 Balise posée (" + (charges - 1) + " restante(s))", NamedTextColor.YELLOW));
            }
            default -> { }
        }
        var hud = pl().getHUDManager();
        if (hud != null) hud.updateHUD(p, c);
    }

    /** Stase (Zhonya) : intouchable, invulnérable et immobile pendant {@code ticks}. */
    static void stasis(Player p, int ticks) {
        var pm = pl().getPassiveManager();
        if (pm != null) pm.getState(p).zhonyasActive = true;
        fr.lolmc.util.Untargetable.apply(p, ticks);
        pl().getCCManager().stun(p, ticks);
        p.setInvulnerable(true);
        VisualEffectUtil.impactBurst(p.getWorld(), p.getLocation(), Material.GOLD_BLOCK, 0.25f, 0.5, 6, 10L);
        p.sendActionBar(Component.text("⏱ Stase", NamedTextColor.GOLD));
        later(ticks, () -> {
            if (pm != null) pm.getState(p).zhonyasActive = false;
            p.setInvulnerable(false);
        });
    }

    /** Vrai si l'actif est disponible (et le met en recharge), sinon affiche le temps restant. */
    private static boolean use(Player p, Fx f, String key, double seconds) {
        long left = f.cd.getOrDefault("active:" + key, 0L) - System.currentTimeMillis();
        if (left > 0) {
            p.sendActionBar(Component.text(String.format("⏱ En recharge — %.1f s", left / 1000.0), NamedTextColor.RED));
            return false;
        }
        // Savoir cosmique : +10 accélération d'objet
        var rm = pl().getRuneManager();
        double haste = rm != null ? rm.itemHaste(p) : 0;
        f.cd.put("active:" + key, System.currentTimeMillis() + (long) (seconds * 1000 * 100 / (100 + haste)));
        return true;
    }

    /** Le porteur et ses alliés champions dans le rayon. */
    private static java.util.List<Player> allies(Player p, double radius) {
        var out = new java.util.ArrayList<Player>();
        var tm = pl().getTeamManager();
        for (Player o : p.getWorld().getPlayers())
            if (isChampion(o) && (o.equals(p) || tm.areAllies(p, o)) && o.getLocation().distanceSquared(p.getLocation()) <= radius * radius)
                out.add(o);
        return out;
    }
}
