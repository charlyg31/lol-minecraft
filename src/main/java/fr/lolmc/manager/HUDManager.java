package fr.lolmc.manager;
import fr.lolmc.util.Compat;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.HPSystem;
import fr.lolmc.stats.ResourceSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import fr.lolmc.champion.impl.mid.Yasuo;

public class HUDManager {

    private final ChampionManager championManager;

    public HUDManager(ChampionManager championManager) {
        this.championManager = championManager;
        startTasks();
    }

    private void startTasks() {
        // ── Affichage ActionBar + sync HP Minecraft — toutes les 2 ticks ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
                    if (!WorldContext.isInGameWorld(p)) continue;
                    if (!championManager.hasChampion(p)) continue;
                    BaseChampion champ = championManager.getChampion(p);
                    updateHUD(p, champ);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);

        // ── Regen HP + Ressource — toutes les 100 ticks (5s) ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
                    if (!WorldContext.isInGameWorld(p)) continue;
                    if (!championManager.hasChampion(p)) continue;
                    BaseChampion champ = championManager.getChampion(p);
                    HPSystem hp = champ.getHPSystem();
                    ResourceSystem res = champ.getResourceSystem();

                    hp.tickRegen();
                    res.tickRegen();
                    syncMinecraftHP(p, hp);
                    // Ticks passifs dependants du champion
                    // // tickChampionPassives(p, champ);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 100L, 100L);

        // ── Flow Yasuo : +flow si le joueur bouge — toutes les 4 ticks ──
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
                    if (!WorldContext.isInGameWorld(p)) continue;
                    if (!championManager.hasChampion(p)) continue;
                    BaseChampion champ = championManager.getChampion(p);
                    ResourceSystem res = champ.getResourceSystem();
                    if (res.getType() == ResourceSystem.ResourceType.FLOW) {
                        // Si le joueur se déplace
                        if (p.getVelocity().lengthSquared() > 0.01) {
                            res.addFlow(0.5); // +0.5 flow par 4 ticks de mouvement
                        }
                    }
                    if (res.getType() == ResourceSystem.ResourceType.ENERGY) {
                        // Énergie: regen rapide (50/5s = 2/tick environ)
                        res.setCurrent(Math.min(res.getMax(), res.getCurrent() + 0.4));
                    }
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 4L);
    }

    // ── Mise à jour de l'affichage ────────────────────────────────

    public void updateHUD(Player player, BaseChampion champ) {
        HPSystem hp = champ.getHPSystem();
        ResourceSystem res = champ.getResourceSystem();

        // ── 1. Sync HP vers Minecraft ──
        syncMinecraftHP(player, hp);

        // ── 2. Barre EXP = ressource ──
        updateExpBar(player, res);

        // ── 3. ActionBar = HP + Ressource ──
        updateActionBar(player, champ, hp, res);

        // ── 4. Nourriture désactivée (toujours à 20) ──
        player.setFoodLevel(20);
        player.setSaturation(20f);

        // ── 5. Vitesse de mouvement LoL → Minecraft ──
        applyMovementSpeed(player, champ.getStats());

        // ── 6. Vitesse d'attaque → cooldown AA slot 0 ──
        applyAttackSpeed(champ);
    }

    /**
     * Convertit la vitesse de mouvement LoL en vitesse Minecraft.
     * LoL: vitesse base ~325-360, max ~500+
     * Minecraft walkSpeed: 0.2 = normal (100%%), range 0.0..1.0
     * Mapping: LoL 330 = MC 0.2, LoL 500 = MC 0.35
     */
    private void applyMovementSpeed(Player player, fr.lolmc.stats.ChampionStats stats) {
        double lolMS = stats.getFinalMovementSpeed();
        // Formule: mc_speed = (lolMS / 330.0) * 0.2
        float mcSpeed = (float) Math.min(1.0, Math.max(0.05, (lolMS / 330.0) * 0.2));
        if (Math.abs(player.getWalkSpeed() - mcSpeed) > 0.001f) {
            player.setWalkSpeed(mcSpeed);
        }
    }

    /**
     * Applique la vitesse d'attaque au cooldown du sort AA (slot 0).
     * LoL: AS 0.5-2.5 attaques/sec → cooldown AA = 1.0/AS secondes
     */
    private void applyAttackSpeed(fr.lolmc.champion.base.BaseChampion champ) {
        double as = champ.getStats().getFinalAttackSpeed();
        as = Math.max(0.2, Math.min(2.5, as));
        double newCooldown = 1.0 / as;
        var aa = champ.getAbility(0);
        if (aa != null) {
            // Modifier le cooldown de base de l'AA dynamiquement
            aa.setDynamicCooldown(newCooldown);
        }
    }

    private void syncMinecraftHP(Player player, HPSystem hp) {
        // Mettre maxHealth à 40 (fixe)
        var attr = player.getAttribute(Compat.maxHealth());
        if (attr != null && attr.getValue() != 40.0) {
            attr.setBaseValue(40.0);
        }
        // Calculer HP Minecraft proportionnel
        double mcHP = hp.toMinecraftHealth();
        if (Math.abs(player.getHealth() - mcHP) > 0.1) {
            player.setHealth(Math.max(0.1, Math.min(40.0, mcHP)));
        }
    }

    private void updateExpBar(Player player, ResourceSystem res) {
        if (!res.hasResource()) {
            // Pas de ressource → barre vide et niveau 0
            player.setExp(0f);
            player.setLevel(0);
            return;
        }
        // Niveau = valeur entière de la ressource
        player.setLevel((int) res.getCurrent());
        // Remplissage = ratio
        player.setExp(res.getRatio());
    }

    private void updateActionBar(Player player, BaseChampion champ,
                                  HPSystem hp, ResourceSystem res) {
        // ── HP bar en texte ──
        int hpCur = (int) hp.getCurrentHP();
        int hpMax = (int) hp.getMaxHP();

        // Construire une mini barre de vie (20 segments)
        int filled = (int) Math.round(hp.getHPRatio() * 20);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 20; i++) bar.append(i < filled ? "❤" : "♡");

        // Couleur de la barre selon HP
        NamedTextColor hpColor;
        float ratio = hp.getHPRatio();
        if (ratio > 0.5f)      hpColor = NamedTextColor.GREEN;
        else if (ratio > 0.25f) hpColor = NamedTextColor.YELLOW;
        else                    hpColor = NamedTextColor.RED;

        // ── Ressource ──
        Component resourceComp;
        if (res.hasResource()) {
            int resCur = (int) res.getCurrent();
            int resMax = (int) res.getMax();
            String symbol = res.getSymbol();
            resourceComp = Component.text(
                String.format("  %s %d/%d", symbol, resCur, resMax),
                res.getColor()
            ).decoration(TextDecoration.ITALIC, false);
        } else {
            resourceComp = Component.empty();
        }

        // ── Niveau du champion + indicateur de point de compétence ──
        int champLevel = champ.getLevelSystem().getLevel();
        boolean hasPoint = champ.getLevelSystem().hasSkillPoint();
        Component levelComp = Component.text(
            String.format("⭐%d ", champLevel),
            NamedTextColor.AQUA
        ).decoration(TextDecoration.ITALIC, false);
        if (hasPoint) {
            levelComp = levelComp.append(Component.text("[+] ", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        }

        Component actionBar = levelComp.append(Component.text(
            String.format("❤ %d/%d  %s", hpCur, hpMax, bar),
            hpColor
        ).decoration(TextDecoration.ITALIC, false))
         .append(resourceComp);

        player.sendActionBar(actionBar);
    }

    // ── Méthodes publiques utilisées par les sorts ────────────────

    /**
     * Inflige des dégâts LoL au joueur cible.
     * Met à jour HPSystem + HP Minecraft.
     */
    public void dealDamage(Player victim, double amount, ChampionManager mgr) {
        if (!mgr.hasChampion(victim)) return;
        BaseChampion champ = mgr.getChampion(victim);
        HPSystem hp = champ.getHPSystem();
        hp.takeDamage(amount);
        syncMinecraftHP(victim, hp);
        if (hp.isDead()) onDeath(victim);
    }

    /**
     * Soigne le joueur.
     */
    public void healPlayer(Player player, double amount) {
        if (!championManager.hasChampion(player)) return;
        HPSystem hp = championManager.getChampion(player).getHPSystem();
        hp.heal(amount);
        syncMinecraftHP(player, hp);
    }

    private void onDeath(Player player) {
        // TODO : logique de mort LoL (respawn timer, etc.)
        player.sendMessage(Component.text("☠ Tu es mort!", NamedTextColor.DARK_RED));
    }

    /**
     * Initialise le HUD pour un joueur qui vient de choisir un champion.
     */
    public void initPlayer(Player player, BaseChampion champ) {
        // HP max Minecraft = 40
        var attr = player.getAttribute(Compat.maxHealth());
        if (attr != null) attr.setBaseValue(40.0);
        player.setHealth(40.0);

        // HP LoL = max
        champ.getHPSystem().setCurrentHP(champ.getHPSystem().getMaxHP());

        // Ressource = max
        champ.getResourceSystem().fill();

        // Nourriture désactivée
        player.setFoodLevel(20);
        player.setSaturation(20f);

        // Désactiver regen Minecraft naturelle (gérée par nous)

        // Première update
        updateHUD(player, champ);
    }


    private void updatePlayerHealthBar(Player player,
            fr.lolmc.champion.base.BaseChampion champ) {
        double cur = champ.getHPSystem().getCurrentHP();
        double max = champ.getHPSystem().getMaxHP();
        String label = champ.getId().substring(0,1).toUpperCase()
                     + champ.getId().substring(1);
        // Mettre à jour le nametag du joueur pour les autres
        fr.lolmc.util.HealthBar.apply(player, cur, max, label);
    }
}
