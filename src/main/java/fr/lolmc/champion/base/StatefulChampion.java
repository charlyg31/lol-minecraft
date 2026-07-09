package fr.lolmc.champion.base;

import java.util.UUID;

/**
 * Interface marquant un champion qui possède un état statique persistant
 * entre les tours (maps UUID → données).
 * Toute classe implémentant cette interface DOIT être enregistrée dans
 * {@link fr.lolmc.util.ChampionStateReset} — le compilateur garantit que
 * les deux méthodes existent, ce qui élimine les oublis silencieux.
 * Usage :
 * <pre>
 *   public class Veigar extends BaseChampion implements StatefulChampion {
 *       private static final Map<UUID, Integer> stacks = new ConcurrentHashMap<>();
 *       {@literal @}Override public void resetState(UUID id) { stacks.remove(id); }
 *       {@literal @}Override public void resetAllState()     { stacks.clear(); }
 *   }
 * </pre>
 */
public interface StatefulChampion {
    /** Réinitialise l'état d'un seul joueur (déconnexion, changement de champion). */
    void resetState(UUID id);
    /** Réinitialise tout l'état (fin de partie). */
    void resetAllState();
}
