package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère l'aggro des sbires et des tours quand un champion attaque
 * un champion ennemi (mécanique fondamentale du laning LoL).
 *
 * Règles LoL :
 *  - Attaquer un champion ennemi → les sbires ennemis dans ~9 blocs
 *    switchent immédiatement sur l'attaquant pendant ~3s.
 *  - Attaquer un champion ennemi sous sa tour → la tour retarget
 *    IMMÉDIATEMENT l'attaquant (aggro de tour).
 */
public class AggroManager {

    private static final double MINION_AGGRO_RANGE = 9.0;
    private static final long   MINION_AGGRO_MS    = 3000L;
    public  static final long   TURRET_AGGRO_MS    = 3000L;

    // UUID attaquant → fin de l'aggro tour (la tour le priorise)
    private final Map<UUID, Long> turretAggro = new ConcurrentHashMap<>();
    // UUID attaquant → dernier champion qu'il a blessé (la tourelle vérifie qu'il est allié et proche)
    private final Map<UUID, UUID> lastVictim = new ConcurrentHashMap<>();

    /**
     * Appelé à CHAQUE attaque champion → champion (depuis DamageUtil).
     * Déclenche l'aggro des sbires et de la tour ennemis.
     */
    public void onChampionAttack(Player attacker, Player victim) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var victimTeam = tm.getTeam(victim);
        if (victimTeam == null) return;

        // ── Aggro des sbires ennemis proches de la victime ──
        var bush = LolPlugin.getInstance().getBushManager();
        for (var entity : victim.getNearbyEntities(MINION_AGGRO_RANGE, 5, MINION_AGGRO_RANGE)) {
            if (!(entity instanceof org.bukkit.entity.Mob mob)) continue;
            if (!MinionManager.isMinion(mob)) continue;
            if (MinionManager.getMinionTeam(mob) != victimTeam) continue;
            // Un sbire ne peut pas viser un attaquant qu'il ne voit pas (bush sans vision)
            if (bush != null && bush.hidesFromMinion(mob, victimTeam, attacker)) continue;
            // Le sbire allié de la victime cible l'attaquant
            mob.setTarget(attacker);
            // Marquer l'aggro pour que la boucle IA ne l'écrase pas pendant 3s
            mob.getPersistentDataContainer().set(
                aggroKey(), org.bukkit.persistence.PersistentDataType.LONG,
                System.currentTimeMillis() + MINION_AGGRO_MS);
        }

        // ── Aggro de tour : l'attaquant devient prioritaire 3s ──
        turretAggro.put(attacker.getUniqueId(), System.currentTimeMillis() + TURRET_AGGRO_MS);
        lastVictim.put(attacker.getUniqueId(), victim.getUniqueId());
    }

    /** Champion blessé récemment (3 s) par ce joueur, ou null (appel à l'aide des tourelles). */
    public Player getRecentChampionVictim(Player attacker) {
        if (!hasTurretAggro(attacker)) return null;
        UUID v = lastVictim.get(attacker.getUniqueId());
        Player victim = v != null ? org.bukkit.Bukkit.getPlayer(v) : null;
        return victim != null && victim.getGameMode() != org.bukkit.GameMode.SPECTATOR ? victim : null;
    }

    /** True si ce joueur a l'aggro de tour actif (à prioriser). */
    public boolean hasTurretAggro(Player player) {
        Long until = turretAggro.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    /** True si ce sbire a une aggro forcée en cours (ne pas retarget). */
    public static boolean hasMinionAggro(org.bukkit.entity.Mob mob) {
        Long until = mob.getPersistentDataContainer().get(
            aggroKey(), org.bukkit.persistence.PersistentDataType.LONG);
        return until != null && until > System.currentTimeMillis();
    }

    private static org.bukkit.NamespacedKey aggroKey() {
        return new org.bukkit.NamespacedKey(LolPlugin.getInstance(), "minion_aggro");
    }

    public void cleanup(UUID uuid) { turretAggro.remove(uuid); lastVictim.remove(uuid); }
    public void reset() { turretAggro.clear(); lastVictim.clear(); }
}
