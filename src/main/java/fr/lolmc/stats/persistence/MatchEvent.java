package fr.lolmc.stats.persistence;

/**
 * Un événement de la frise d'une partie (une ligne dans match_events).
 *
 * @param t      secondes écoulées depuis le début de la partie
 * @param type   KILL, TURRET, INHIBITOR, NEXUS, DRAGON, ELDER, BARON, HERALD, VOIDGRUB
 * @param team   équipe de l'auteur ("BLUE", "RED" ou "")
 * @param actor  pseudo de l'auteur (vide si aucun, ex. exécution)
 * @param target victime, structure ou monstre
 */
public record MatchEvent(int t, String type, String team, String actor, String target) {

    public String toJson() {
        return "{\"t\":" + t + ",\"type\":\"" + Json.esc(type) + "\",\"team\":\"" + Json.esc(team)
                + "\",\"actor\":\"" + Json.esc(actor) + "\",\"target\":\"" + Json.esc(target) + "\"}";
    }
}
