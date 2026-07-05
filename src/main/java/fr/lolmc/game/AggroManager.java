package fr.lolmc.game;

import fr.lolmc.LolPlugin;
import org.bukkit.entity.LivingEntity;
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

    /**
     * Appelé à CHAQUE attaque champion → champion (depuis DamageUtil).
     * Déclenche l'aggro des sbires et de la tour ennemis.
     */
    public void onChampionAttack(Player attacker, Player victim) {
        var tm = LolPlugin.getInstance().getTeamManager();
        var victimTeam = tm.getTeam(victim);
        if (victimTeam == null) return;

        // ── Aggro des sbires ennemis proches de la victime ──
        for (var entity : victim.getNearbyEntities(MINION_AGGRO_RANGE, 5, MINION_AGGRO_RANGE)) {
            if (!(entity instanceof org.bukkit.entity.Mob mob)) continue;
            if (!MinionManager.isMinion(mob)) continue;
            if (MinionManager.getMinionTeam(mob) != victimTeam) continue;
            // Le sbire allié de la victime cible l'attaquant
            mob.setTarget(attacker);
            // Marquer l'aggro pour que la boucle IA ne l'écrase pas pendant 3s
            mob.getPersistentDataContainer().set(
                aggroKey(), org.bukkit.persistence.PersistentDataType.LONG,
                System.currentTimeMillis() + MINION_AGGRO_MS);
        }

        // ── Aggro de tour : l'attaquant devient prioritaire 3s ──
        turretAggro.put(attacker.getUniqueId(), System.currentTimeMillis() + TURRET_AGGRO_MS);
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

    public void cleanup(UUID uuid) { turretAggro.remove(uuid); }
    public void reset() { turretAggro.clear(); }
}
