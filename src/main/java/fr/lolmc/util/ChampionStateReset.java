package fr.lolmc.util;

import fr.lolmc.champion.impl.top.Nasus;
import fr.lolmc.champion.impl.top.Darius;
import fr.lolmc.champion.impl.top.Malphite;
import fr.lolmc.champion.impl.mid.Annie;
import fr.lolmc.champion.impl.mid.Veigar;
import fr.lolmc.champion.impl.mid.Yasuo;
import fr.lolmc.champion.impl.mid.Zed;
import fr.lolmc.champion.impl.jungle.Amumu;
import fr.lolmc.champion.impl.jungle.LeeSin;
import fr.lolmc.champion.impl.jungle.MasterYi;
import fr.lolmc.champion.impl.support.Blitzcrank;
import fr.lolmc.champion.impl.adc.Jinx;
import fr.lolmc.champion.impl.adc.MissFortune;
import fr.lolmc.champion.impl.adc.Sivir;

import java.util.UUID;

/**
 * Point central de réinitialisation des états statiques des champions.
 * Appelé à la fin de chaque partie (resetAll) et à la déconnexion/
 * changement de champion d'un joueur (resetPlayer).
 */
public final class ChampionStateReset {

    private ChampionStateReset() {}

    /** Réinitialise l'état d'un seul joueur (déconnexion, changement de champion). */
    public static void resetPlayer(UUID id) {
        Nasus.resetState(id);
        Darius.resetState(id);
        Malphite.resetState(id);
        Annie.resetState(id);
        Veigar.resetState(id);
        Yasuo.resetState(id);
        Zed.resetState(id);
        Amumu.resetState(id);
        LeeSin.resetState(id);
        MasterYi.resetState(id);
        Blitzcrank.resetState(id);
        Jinx.resetState(id);
        MissFortune.resetState(id);
        Sivir.resetState(id);
    }

    /** Réinitialise tout (fin de partie). */
    public static void resetAll() {
        Nasus.resetAllState();
        Darius.resetAllState();
        Malphite.resetAllState();
        Annie.resetAllState();
        Veigar.resetAllState();
        Yasuo.resetAllState();
        Zed.resetAllState();
        Amumu.resetAllState();
        LeeSin.resetAllState();
        MasterYi.resetAllState();
        Blitzcrank.resetAllState();
        Jinx.resetAllState();
        MissFortune.resetAllState();
        Sivir.resetAllState();
    }
}
