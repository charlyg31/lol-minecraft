package fr.lolmc.util;

import fr.lolmc.champion.impl.top.Nasus;
import fr.lolmc.champion.impl.mid.Annie;
import fr.lolmc.champion.impl.mid.Veigar;
import fr.lolmc.champion.impl.mid.Yasuo;
import fr.lolmc.champion.impl.mid.Zed;
import fr.lolmc.champion.impl.jungle.LeeSin;
import fr.lolmc.champion.impl.adc.MissFortune;

import java.util.UUID;

/**
 * Point central de réinitialisation des états statiques des champions
 * (stacks, ombres, cibles marquées...). Évite que ces données par joueur
 * persistent entre les parties ou après une déconnexion (fuites mémoire +
 * bugs d'état comme les stacks de Nasus/Veigar qui resteraient acquis).
 *
 * Quand un champion ajoute une collection statique par joueur, lui ajouter
 * resetState(UUID)/resetAllState() et l'appeler ici.
 */
public final class ChampionStateReset {

    private ChampionStateReset() {}

    /** Réinitialise l'état d'un seul joueur (déconnexion, changement de champion). */
    public static void resetPlayer(UUID id) {
        Nasus.resetState(id);
        Annie.resetState(id);
        Veigar.resetState(id);
        Yasuo.resetState(id);
        Zed.resetState(id);
        LeeSin.resetState(id);
        MissFortune.resetState(id);
    }

    /** Réinitialise tout (fin de partie). */
    public static void resetAll() {
        Nasus.resetAllState();
        Annie.resetAllState();
        Veigar.resetAllState();
        Yasuo.resetAllState();
        Zed.resetAllState();
        LeeSin.resetAllState();
        MissFortune.resetAllState();
    }
}
