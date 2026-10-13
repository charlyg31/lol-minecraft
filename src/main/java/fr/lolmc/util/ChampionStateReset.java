package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.champion.base.StatefulChampion;

import java.util.UUID;

/**
 * Point central de réinitialisation des états statiques des champions.
 * Appelé à la fin de chaque partie (resetAll) et à la déconnexion/
 * changement de champion d'un joueur (resetPlayer).
 * <p>
 * Itère sur tous les champions enregistrés dans {@link fr.lolmc.manager.ChampionManager}
 * et appelle resetState/resetAllState sur ceux qui implémentent
 * {@link StatefulChampion} — aucune liste à maintenir à la main,
 * donc aucun oubli possible pour un futur champion stateful.
 */
public final class ChampionStateReset {

    private ChampionStateReset() {}

    /** Réinitialise l'état d'un seul joueur (déconnexion, changement de champion). */
    public static void resetPlayer(UUID id) {
        for (BaseChampion champ : LolPlugin.getInstance().getChampionManager().getAllChampions()) {
            if (champ instanceof StatefulChampion sc) sc.resetState(id);
        }
    }

    /** Réinitialise tout (fin de partie). */
    public static void resetAll() {
        for (BaseChampion champ : LolPlugin.getInstance().getChampionManager().getAllChampions()) {
            if (champ instanceof StatefulChampion sc) sc.resetAllState();
        }
    }
}
