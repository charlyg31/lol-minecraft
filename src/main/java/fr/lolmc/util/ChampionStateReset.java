package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.champion.base.StatefulChampion;

import java.util.UUID;

/**
 * Point central de rÃ©initialisation des Ã©tats statiques des champions.
 * AppelÃ© Ã  la fin de chaque partie (resetAll) et Ã  la dÃ©connexion/
 * changement de champion d'un joueur (resetPlayer).
 * <p>
 * ItÃ¨re sur tous les champions enregistrÃ©s dans {@link fr.lolmc.manager.ChampionManager}
 * et appelle resetState/resetAllState sur ceux qui implÃ©mentent
 * {@link StatefulChampion} â aucune liste Ã  maintenir Ã  la main,
 * donc aucun oubli possible pour un futur champion stateful.
 */
public final class ChampionStateReset {

    private ChampionStateReset() {}

    /** RÃ©initialise l'Ã©tat d'un seul joueur (dÃ©connexion, changement de champion). */
    public static void resetPlayer(UUID id) {
        for (BaseChampion champ : LolPlugin.getInstance().getChampionManager().getAllChampions()) {
            if (champ instanceof StatefulChampion sc) sc.resetState(id);
        }
    }

    /** RÃ©initialise tout (fin de partie). */
    public static void resetAll() {
        for (BaseChampion champ : LolPlugin.getInstance().getChampionManager().getAllChampions()) {
            if (champ instanceof StatefulChampion sc) sc.resetAllState();
        }
    }
}
