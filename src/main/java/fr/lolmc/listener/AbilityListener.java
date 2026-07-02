package fr.lolmc.listener;
import fr.lolmc.util.Compat;

import fr.lolmc.LolPlugin;
import fr.lolmc.util.WorldContext;

import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.item.HotbarManager;
import fr.lolmc.item.PassiveManager;
import fr.lolmc.manager.ChampionManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import fr.lolmc.team.TeamManager.Team;

public class AbilityListener implements Listener {

    private final ChampionManager manager;

    public AbilityListener(ChampionManager manager) {
        this.manager = manager;
        // Affichage portée toutes les 2 ticks (uniquement page 1, slots sorts)
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
                    if (!manager.hasChampion(p)) continue;
                    if (LolPlugin.getInstance().getHotbarManager().getPage(p) != 1) continue;
                    manager.getChampion(p).displayRangeIfHoldingAbility(p);
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 2L);

        // Tâche d'affichage des cooldowns sur les items de sort (chaque 5 ticks)
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : WorldContext.getGamePlayers()) {
                    if (!manager.hasChampion(p)) continue;
                    if (LolPlugin.getInstance().getHotbarManager().getPage(p) != 1) continue;
                    updateCooldownDisplay(p, manager.getChampion(p));
                }
            }
        }.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);
    }

    /**
     * Affiche le cooldown restant sur les items de sort (slots 1-4).
     * Le nombre = secondes restantes (via la quantité de l'item).
     */
    // Mémorise le dernier nombre de secondes affiché par slot, pour ne rafraîchir
    // l'item QUE lorsque la valeur change (évite le clignotement).
    private final java.util.Map<java.util.UUID, int[]> cdShown = new java.util.HashMap<>();

    private void updateCooldownDisplay(Player player, BaseChampion champ) {
        int[] shown = cdShown.computeIfAbsent(player.getUniqueId(), k -> new int[]{-1, -1, -1, -1, -1});
        for (int slot = 1; slot <= 4; slot++) {
            var ability = champ.getAbility(slot);
            if (ability == null) continue;
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !HotbarManager.isLolItem(item)) continue;

            double remaining = ability.getRemainingCooldown(player);
            int secs = remaining > 0 ? (int) Math.ceil(remaining) : 0;

            // Ne rien faire si l'affichage est déjà à jour (anti-clignotement)
            if (shown[slot] == secs) continue;
            fr.lolmc.util.DebugLogger.log("CooldownDisplay", "slot=" + slot
                + " sort=" + ability.getName() + " remaining=" + String.format("%.1f", remaining)
                + "s -> affiche " + secs + "s (item type=" + HotbarManager.getType(item) + ")");
            shown[slot] = secs;

            if (secs > 0) {
                // Quantité = secondes restantes (chiffre rouge visible sur l'item)
                item.setAmount(Math.max(1, Math.min(64, secs)));
                var meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(net.kyori.adventure.text.Component.text(
                        "⏳ " + ability.getName() + " (" + secs + "s)",
                        net.kyori.adventure.text.format.NamedTextColor.RED)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                    item.setItemMeta(meta);
                }
            } else {
                // Prêt : quantité 1, nom rétabli par le refresh normal
                item.setAmount(1);
                champ.refreshSlot(player, slot);
            }
        }
    }

    private HotbarManager hotbar() { return LolPlugin.getInstance().getHotbarManager(); }

    // Anti-rebond : évite le double déclenchement clic gauche (animation + attaque entité)
    // Active les messages de diagnostic en jeu (à désactiver en prod)
    public static boolean DEBUG = false;
    private final java.util.Map<java.util.UUID, Long> lastCastTime = new java.util.HashMap<>();
    private boolean canCast(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastCastTime.get(p.getUniqueId());
        if (last != null && (now - last) < 200) return false;
        lastCastTime.put(p.getUniqueId(), now);
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    // SYSTÈME D'INPUT LoL
    //   • CLIC GAUCHE  (air ou ennemi) → lance le sort du slot tenu (1-4)
    //                                     slot 0 = auto-attaque
    //   • CLIC DROIT   sur un sort (1-4) → améliore le sort
    //   • CLIC DROIT   sur Flash/actif/recall/page → action de l'item
    // ══════════════════════════════════════════════════════════════

    // ── CLIC DROIT sur un joueur (rien : on cible au clic gauche) ──
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player caster = e.getPlayer();
        if (!manager.hasChampion(caster)) return;

        // Clic droit sur un ArmorStand tagué "lol_ward" → tentative de destruction
        if (e.getRightClicked() instanceof org.bukkit.entity.ArmorStand as
                && as.getScoreboardTags().contains("lol_ward")) {
            e.setCancelled(true);
            LolPlugin.getInstance().getWardManager().destroyWardByEntity(as.getUniqueId(), caster);
            return;
        }

        // Autres clics droits sur entité : annuler l'interaction vanilla.
        e.setCancelled(true);
    }

    // ── Oracle Lens / Farsight : révèle les wards ennemies proches ──
    // Appelé depuis handleSlotAction quand l'item consommable est oracle_lens/farsight.
    public void revealNearbyWards(Player player) {
        int count = LolPlugin.getInstance().getWardManager()
                .revealEnemyWards(player, player.getLocation(), 10.0, 10_000L);
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                count > 0 ? "🔍 " + count + " ward(s) ennemie(s) révélée(s)! Clic droit pour détruire."
                          : "🔍 Aucune ward ennemie dans le rayon.",
                net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    // ── CLIC GAUCHE FIABLE via PlayerAnimationEvent ──
    // LEFT_CLICK_AIR n'est pas toujours envoyé par le client ; l'animation
    // de balancement du bras (arm swing) l'est à chaque clic gauche.
    @EventHandler
    public void onArmSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        Player caster = e.getPlayer();
        // Log AVANT le filtre hasChampion : confirme que l'event se déclenche
        fr.lolmc.util.DebugLogger.log("ArmSwing-RAW", caster.getName()
            + " a déclenché PlayerAnimationEvent (hasChampion="
            + manager.hasChampion(caster) + ")");
        if (!manager.hasChampion(caster)) return;
        if (!fr.lolmc.util.WorldContext.isInGameWorld(caster)) return;

        // Ignorer l'animation de la main secondaire (Paper envoie ARM_SWING
        // ET OFF_ARM_SWING pour un seul clic → double déclenchement des sorts)
        if (e.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;

        fr.lolmc.util.DebugLogger.log("ArmSwing", caster.getName()
            + " animation=" + e.getAnimationType());

        int slot = caster.getInventory().getHeldItemSlot();
        ItemStack held = caster.getInventory().getItem(slot);
        String t = HotbarManager.getType(held);
        fr.lolmc.util.DebugLogger.log("ArmSwing", "slot=" + slot + " type=" + t
            + " item=" + (held != null ? held.getType() : "null"));
        if (!"ability".equals(t)) return;
        if (!canCast(caster)) return; // anti-double-déclenchement

        // ── Contrôle de Tibbers (Annie) : clic gauche avec le R en main = envoyer Tibbers ──
        var tibbersEnt = fr.lolmc.champion.impl.mid.Annie.getActiveTibbers(caster);
        if (tibbersEnt instanceof org.bukkit.entity.PolarBear tib
                && "ability".equals(t)
                && "r_annie".equals(HotbarManager.getId(held))) {
            fr.lolmc.champion.impl.mid.Annie.onTibbersControl(caster, tib);
            return;
        }

        if (!"ability".equals(t)) return;

        onLeftClickCast(caster, slot, held, null);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player caster = e.getPlayer();
        if (!manager.hasChampion(caster)) return;
        if (!fr.lolmc.util.WorldContext.isInGameWorld(caster)) return;

        Action a = e.getAction();
        int slot = caster.getInventory().getHeldItemSlot();
        ItemStack held = caster.getInventory().getItem(slot);
        fr.lolmc.util.DebugLogger.log("Interact", caster.getName() + " action=" + a
            + " slot=" + slot + " type=" + HotbarManager.getType(held)
            + " isLol=" + HotbarManager.isLolItem(held));
        if (!HotbarManager.isLolItem(held)) return;

        // (Le clic gauche est géré par onArmSwing, plus fiable.)
        // ── CLIC DROIT → améliorer le sort ou activer un item ──
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            String type = HotbarManager.getType(held);

            // Clic droit sur un sort (slot 1-4) = améliorer
            if ("ability".equals(type) && slot >= 1 && slot <= 4) {
                tryLevelUpAbility(caster, slot);
                return;
            }
            // Recall
            boolean isRecall = HotbarManager.isRecallItem(held);
            fr.lolmc.util.DebugLogger.log("Interact", "  -> isRecallItem=" + isRecall + " slot=" + slot);
            if (isRecall) {
                LolPlugin.getInstance().getBaseManager().startRecall(caster);
                return;
            }
            // Flash / actif / consommable / bouton page
            handleSlotAction(caster, slot, held, null);
        }
    }

    /** Gère le clic gauche : auto-attaque (slot 0) ou lancement de sort (1-4). */
    private void onLeftClickCast(Player caster, int slot, ItemStack held, Player target) {
        String type = HotbarManager.getType(held);
        if (!"ability".equals(type)) return;
        fr.lolmc.util.DebugLogger.log("LeftClickCast", "slot=" + slot + " caster=" + caster.getName());

        if (slot == 0) {
            // Slot 0 = auto-attaque : cherche la cible visée
            Player aimed = target != null ? target : getTargetedPlayer(caster);
            if (aimed != null) {
                LolPlugin.getInstance().getAutoAttackManager().tryAutoAttack(caster, aimed);
            } else {
                // Aucun joueur visé — chercher une structure ennemie dans la portée AA
                tryAttackStructure(caster);
            }
        } else if (slot >= 1 && slot <= 4) {
            // Lancer le sort, avec la cible visée s'il y en a une
            Player aimed = target != null ? target : getTargetedPlayer(caster);
            manager.getChampion(caster).tryUseAbility(caster, slot, aimed);
        }
    }

    /**
     * Cherche la structure ennemie la plus proche dans la portée AA du joueur
     * et l'attaque si elle est visée (angle ≤ 45°).
     */
    private void tryAttackStructure(Player caster) {
        if (!manager.hasChampion(caster)) return;
        var champ = manager.getChampion(caster);
        double range = champ.getAutoAttackRange();
        var tm = LolPlugin.getInstance().getTeamManager();
        var mm = LolPlugin.getInstance().getMapManager();
        if (mm == null) return;

        GameStructure closest = null;
        double closestDist = Double.MAX_VALUE;
        var eye = caster.getEyeLocation();
        var dir = eye.getDirection();

        for (var structure : mm.getStructures()) {
            if (structure.isDestroyed()) continue;
            if (structure.getTeam() == tm.getTeam(caster)) continue; // structure alliée
            var center = structure.getCenter().clone().add(0.5, 1, 0.5);
            if (!center.getWorld().equals(caster.getWorld())) continue;
            double dist = caster.getLocation().distance(center);
            if (dist > range) continue;
            // Vérifier que le joueur regarde vers la structure (angle ≤ 45°)
            var toStruct = center.toVector().subtract(eye.toVector()).normalize();
            double dot = dir.dot(toStruct);
            if (dot < 0.7) continue; // cos(45°) ≈ 0.7
            if (dist < closestDist) { closestDist = dist; closest = structure; }
        }

        if (closest == null) return;

        // Vérifier la cadence AA (même cooldown que les AA sur champions)
        var aam = LolPlugin.getInstance().getAutoAttackManager();
        if (!aam.canAutoAttack(caster)) return;
        aam.triggerCooldown(caster);

        // Infliger les dégâts AA à la structure
        final var target = closest;
        var sdl = LolPlugin.getInstance().getStructureDamageListener();
        if (sdl != null) sdl.applyAutoAttackDamage(caster, target);
    }

    /** Trouve le joueur visé par le caster (raycast simple, portée 30 blocs). */
    private Player getTargetedPlayer(Player caster) {
        var eye = caster.getEyeLocation();
        var dir = eye.getDirection();
        Player closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Player other : caster.getWorld().getPlayers()) {
            if (other.equals(caster)) continue;
            var toTarget = other.getLocation().toVector().subtract(eye.toVector());
            double dist = toTarget.length();
            if (dist > 30) continue;
            // Angle entre le regard et la cible
            double dot = toTarget.normalize().dot(dir);
            if (dot > 0.96 && dist < closestDist) { // ~16° de tolérance
                closest = other;
                closestDist = dist;
            }
        }
        return closest;
    }

    /**
     * Améliore un sort et notifie le joueur.
     */
    private void tryLevelUpAbility(Player caster, int slot) {
        BaseChampion champ = manager.getChampion(caster);
        if (champ.levelUpAbility(caster, slot)) {
            int rank = champ.getLevelSystem().getAbilityRank(slot);
            String name = champ.getAbility(slot).getName();
            caster.sendActionBar(net.kyori.adventure.text.Component.text(
                "✨ " + name + " amélioré au rang " + rank + "!",
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
            caster.playSound(caster.getLocation(),
                org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
            // Rafraîchir le clignotement
            updateAbilityGlow(caster, champ);
        } else {
            caster.sendActionBar(net.kyori.adventure.text.Component.text(
                "❌ Impossible d'améliorer (pas de point ou rang max / niveau requis)",
                net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    /**
     * Fait clignoter les sorts améliorables (ajoute l'enchantement glow).
     */
    public void updateAbilityGlow(Player player, BaseChampion champ) {
        if (hotbar().getPage(player) != 1) return;
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null) continue;
            boolean canUp = champ.getLevelSystem().canLevelUp(slot);
            var meta = item.getItemMeta();
            if (meta == null) continue;
            if (canUp) {
                meta.addEnchant(Compat.glowEnchant(), 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            } else {
                meta.removeEnchant(Compat.glowEnchant());
            }
            item.setItemMeta(meta);
        }
    }

    /**
     * Route l'action selon le type PDC de l'item tenu.
     */
    private void handleSlotAction(Player caster, int slot, ItemStack held, Player target) {
        String type = HotbarManager.getType(held);
        if (type == null) return;
        BaseChampion champ = manager.getChampion(caster);

        switch (type) {
            case "ability" -> {
                // slot 0 = AA (géré par clic gauche), 1-4 = sorts
                if (slot >= 1 && slot <= 4) {
                    champ.tryUseAbility(caster, slot, target);
                }
            }
            case "flash" -> LolPlugin.getInstance().getFlashManager().useFlash(caster);
            case "item" -> {
                String itemId = HotbarManager.getId(held);
                PassiveManager pm = LolPlugin.getInstance().getPassiveManager();
                if (pm != null && itemId != null) pm.activateItem(caster, itemId);
            }
            case "consumable" -> {
                String consId = HotbarManager.getId(held);
                LolPlugin.getInstance().getShopListener().useConsumablePublic(caster, consId);
                hotbar().removeConsumable(caster, consId);
                hotbar().renderPage(caster, champ);
            }
            case "recall" -> LolPlugin.getInstance().getBaseManager().startRecall(caster);
            case "page" -> hotbar().switchPage(caster, champ);
        }
    }

    // ── Dégâts des sbires sur les joueurs ──
    @EventHandler
    public void onMinionAttackPlayer(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof org.bukkit.entity.LivingEntity damagerLe)) return;
        if (!fr.lolmc.game.MinionManager.isMinion(damagerLe)) return;
        e.setCancelled(true);
        var tm = LolPlugin.getInstance().getTeamManager();
        fr.lolmc.team.TeamManager.Team minionTeam =
            fr.lolmc.game.MinionManager.getMinionTeam(damagerLe);
        fr.lolmc.team.TeamManager.Team victimTeam = tm.getTeam(victim);
        if (minionTeam == null || minionTeam == victimTeam) return;
        // Dégâts du sbire selon son type
        String typeTag = fr.lolmc.game.MinionManager.getMinionTypeTag(damagerLe);
        // Dégâts bruts LoL (avant réduction d'armure)
        double rawDmg = switch (typeTag != null ? typeTag : "melee") {
            case "cannon" -> 100.0;
            case "super"  -> 190.0;
            case "caster" -> 39.0;
            default       -> 68.0; // melee
        };
        // Appliquer via le système LoL (avec réduction d'armure)
        if (manager.hasChampion(victim)) {
            var champ = manager.getChampion(victim);
            double armor = champ.getStats().getFinalArmor();
            // Formule LoL : dégâts × 100/(100+armure)
            double dmgReduced = rawDmg * (100.0 / (100.0 + Math.max(0, armor)));
            champ.getHPSystem().takeDamage(dmgReduced);
            var hud = LolPlugin.getInstance().getHUDManager();
            if (hud != null) hud.updateHUD(victim, champ);
        }
    }

    // ── Clic gauche sur entité → AA (slot 0) ──
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player caster)) return;
        if (!manager.hasChampion(caster)) return;

        // ANTI-SPAM ÉPÉE : un joueur-champion ne fait JAMAIS de dégât de mêlée vanilla.
        // Tous les dégâts passent par le système LoL (AA cadencée ou sorts).
        // On annule donc systématiquement le dégât vanilla ici.
        e.setCancelled(true);

        var entity = e.getEntity();
        var tm = LolPlugin.getInstance().getTeamManager();
        Team myTeam = tm.getTeam(caster);

        // ── Cible = SBIRE ou MONSTRE de jungle ──
        if (entity instanceof org.bukkit.entity.LivingEntity le
                && (fr.lolmc.game.MinionManager.isMinion(le)
                    || fr.lolmc.game.JungleManager.isJungleMonster(le))) {
            // Bloquer les dégâts sur ses PROPRES sbires
            if (fr.lolmc.game.MinionManager.isMinion(le)) {
                Team minionTeam = fr.lolmc.game.MinionManager.getMinionTeam(le);
                if (minionTeam != null && minionTeam == myTeam) {
                    return;
                }
            }
            // AA LoL uniquement si on tient le slot 0 (l'auto-attaque), avec cadence respectée
            int slotM = caster.getInventory().getHeldItemSlot();
            ItemStack heldM = caster.getInventory().getItem(slotM);
            if ("ability".equals(HotbarManager.getType(heldM)) && slotM == 0) {
                LolPlugin.getInstance().getAutoAttackManager().tryAutoAttackEntity(caster, le);
            }
            return;
        }

        // ── Cible = JOUEUR ──
        if (!(entity instanceof Player target)) return;
        // Bloquer les dégâts sur les alliés (déjà annulé vanilla en début de méthode)
        Team targetTeam = tm.getTeam(target);
        if (targetTeam != null && targetTeam == myTeam) {
            return;
        }

        int slot = caster.getInventory().getHeldItemSlot();
        ItemStack held = caster.getInventory().getItem(slot);
        if (!"ability".equals(HotbarManager.getType(held))) return;

        if (slot == 0) {
            LolPlugin.getInstance().getAutoAttackManager().tryAutoAttack(caster, target);
        } else if (slot >= 1 && slot <= 4) {
            // Le débounce est déjà appliqué en amont (onArmSwing / onLeftClick)
            manager.getChampion(caster).tryUseAbility(caster, slot, target);
        }
    }

    // ── Refresh tooltip au changement de slot (sorts uniquement) ──
    @EventHandler
    public void onSlotChange(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasChampion(p)) return;
        if (hotbar().getPage(p) != 1) return;
        int s = e.getNewSlot();
        if (s >= 1 && s <= 4) manager.getChampion(p).refreshSlot(p, s);
    }

    // ── Bloquer toute manipulation de la hotbar LoL ──
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!manager.hasChampion(p)) return;
        // Bloquer si l'item cliqué OU le curseur est un item LoL
        ItemStack clicked = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        if (HotbarManager.isLolItem(clicked) || HotbarManager.isLolItem(cursor)) {
            e.setCancelled(true);
        }
        // Bloquer les slots hotbar 0-8 systématiquement
        if (e.getSlot() >= 0 && e.getSlot() <= 8 && e.getClickedInventory() == p.getInventory()) {
            e.setCancelled(true);
        }
        // Slot main secondaire : raw slot 40 (vue inventaire standard) OU 45 (vue craft
        // ouverte avec E) selon le contexte. On bloque par le type de slot, plus fiable
        // que le numéro brut, pour empêcher tout dépôt/retrait en offhand pendant la partie.
        boolean offhandSlot = e.getRawSlot() == 40 || e.getRawSlot() == 45
                || e.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.QUICKBAR;
        // Le slot offhand est rapporté par Paper avec un SlotType dédié dans la vue craft.
        if (e.getClickedInventory() instanceof org.bukkit.inventory.PlayerInventory
                && e.getSlot() == 40) {
            offhandSlot = true;
        }
        if (offhandSlot && fr.lolmc.util.WorldContext.isInGameWorld(p)) {
            e.setCancelled(true);
        }
        // Toute action d'échange offhand (touche F / clic offhand) est bloquée en jeu,
        // qu'un item LoL soit impliqué ou non : c'est ce qui cassait l'agencement.
        if (e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                && fr.lolmc.util.WorldContext.isInGameWorld(p)) {
            e.setCancelled(true);
        }
        // Shift-clic : déplace un item vers un autre inventaire/slot → casse la barre si LoL
        var ct = e.getClick();
        if (ct.isShiftClick() && (HotbarManager.isLolItem(clicked) || HotbarManager.isLolItem(cursor))) {
            e.setCancelled(true);
        }
        // Double-clic (ramasse tous les items identiques) sur un item LoL
        if (ct == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK && HotbarManager.isLolItem(cursor)) {
            e.setCancelled(true);
        }
        // Échange par touche numérique (1-9) vers/depuis un slot hotbar
        if (ct == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
            int hb = e.getHotbarButton();
            if (hb >= 0 && hb <= 8) {
                ItemStack hbItem = p.getInventory().getItem(hb);
                if (HotbarManager.isLolItem(hbItem) || HotbarManager.isLolItem(clicked)) {
                    e.setCancelled(true);
                }
            }
        }
    }

    // ── Bloquer le glisser-déposer (drag) d'items LoL ──
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!manager.hasChampion(p)) return;
        // Si on glisse un item LoL, ou si l'un des slots ciblés touche la hotbar/offhand
        if (HotbarManager.isLolItem(e.getOldCursor())) { e.setCancelled(true); return; }
        for (int raw : e.getRawSlots()) {
            if (raw == 40 || raw == 45 || (raw >= 0 && raw <= 8)) {
                if (fr.lolmc.util.WorldContext.isInGameWorld(p)) { e.setCancelled(true); return; }
            }
        }
    }

    // ── Empêcher l'échange main principale ↔ main secondaire (touche F) ──
    @EventHandler
    public void onSwapHands(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!manager.hasChampion(p)) return;
        // Tout item LoL (ou n'importe quel item de la hotbar gérée) ne doit jamais
        // basculer en main secondaire : ça déplace les sorts/items et casse la barre.
        if (HotbarManager.isLolItem(e.getMainHandItem())
                || HotbarManager.isLolItem(e.getOffHandItem())) {
            e.setCancelled(true);
            return;
        }
        // Par sécurité en partie : bloquer tout swap offhand dans le monde de jeu.
        if (fr.lolmc.util.WorldContext.isInGameWorld(p)) {
            e.setCancelled(true);
        }
    }

    // ── Empêcher de dropper les items LoL ──
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (HotbarManager.isLolItem(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    // ── Nettoyage mémoire à la déconnexion ──
    @EventHandler
    public void onJoinBridge(org.bukkit.event.player.PlayerJoinEvent e) {
        var bridge = LolPlugin.getInstance().getBridgeManager();
        if (bridge != null && bridge.isEnabled()) bridge.onPlayerJoin(e.getPlayer());
        // Restaurer l'état si reconnexion en pleine partie
        var gm = LolPlugin.getInstance().getGameManager();
        if (gm != null && gm.isRunning())
            org.bukkit.Bukkit.getScheduler().runTaskLater(LolPlugin.getInstance(),
                () -> gm.onPlayerRejoin(e.getPlayer()), 40L);
    }

    /**
     * Démarre la prévisualisation quand le joueur tient un sort (slot 1-4).
     * Arrête quand il change de slot.
     */
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent e) {
        Player player = e.getPlayer();
        if (!manager.hasChampion(player)) return;
        if (!fr.lolmc.util.WorldContext.isInGameWorld(player)) return;
        var preview = LolPlugin.getInstance().getAbilityPreview();
        if (preview == null) return;
        int newSlot = e.getNewSlot();
        // Toujours arrêter la preview de l'ancien slot
        preview.stop(player);
        // Démarrer si c'est un slot de sort (1-4)
        if (newSlot >= 1 && newSlot <= 4) {
            preview.start(player, newSlot);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // Si le joueur est en pleine partie : on GARDE son état (champion, équipe, objets)
        // pour qu'il puisse revenir. On ne fait qu'un nettoyage léger des caches transitoires.
        var gm = LolPlugin.getInstance().getGameManager();
        if (gm.isRunning() && gm.isParticipant(p.getUniqueId())) {
            lightCleanup(p);
        } else {
            cleanupPlayer(p);
        }
    }

    @EventHandler
    public void onKick(PlayerKickEvent e) {
        cleanupPlayer(e.getPlayer());
    }

    /** Nettoyage léger (déconnexion en cours de partie) : garde champion/équipe/objets. */
    private void lightCleanup(Player p) {
        LolPlugin.getInstance().getFlashManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getWardManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getBushManager().cleanup(p.getUniqueId());
    }

    private void cleanupPlayer(Player p) {
        manager.removeChampion(p);
        fr.lolmc.util.ChampionStateReset.resetPlayer(p.getUniqueId());
        var pm = LolPlugin.getInstance().getPassiveManager();
        if (pm != null) pm.cleanup(p.getUniqueId());
        LolPlugin.getInstance().getCCManager().clear(p.getUniqueId());
        LolPlugin.getInstance().getHotbarManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getShopListener().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getFlashManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getWardManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getTeamManager().removePlayer(p.getUniqueId());
        LolPlugin.getInstance().getPartyManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getMatchmakingManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getBushManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getRoleQueueManager().cleanup(p.getUniqueId());
        LolPlugin.getInstance().getRuneManager().cleanup(p.getUniqueId());
    }

    // ══════════════════════════════════════════════════════════════
    // BLOCAGE DES COMPORTEMENTS VANILLA DES ITEMS LoL
    //   Empêche : téléportation Ender Pearl, canne à pêche, tir d'arc,
    //   consommation, pose de blocs — tant que le joueur a un champion.
    // ══════════════════════════════════════════════════════════════

    /** Bloque la téléportation par Ender Pearl (utilisée comme icône Flash/Recall). */
    @EventHandler
    public void onPearlTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && manager.hasChampion(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    /** Bloque le lancement de projectiles vanilla (perles, cannes à pêche, arcs) pour les champions. */
    @EventHandler
    public void onProjectile(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && manager.hasChampion(p)) {
            e.setCancelled(true);
        }
    }

    /** Bloque la pose de blocs (certains items LoL sont des blocs). */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (manager.hasChampion(e.getPlayer())) {
            ItemStack held = e.getItemInHand();
            if (HotbarManager.isLolItem(held)) e.setCancelled(true);
        }
    }

    /** Bloque la consommation vanilla d'items LoL (potions, etc. gérées par le plugin). */
    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        if (manager.hasChampion(e.getPlayer())
                && HotbarManager.isLolItem(e.getItem())) {
            e.setCancelled(true);
        }
    }

    /** Empêche de lâcher les items LoL au sol (touche Q). */
    @EventHandler
    public void onDropItem(PlayerDropItemEvent e) {
        if (manager.hasChampion(e.getPlayer())
                && HotbarManager.isLolItem(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

}
