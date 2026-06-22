package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * Valeurs d'équilibrage externalisées dans champions.yml (rechargeable à chaud
 * via /lol reload, sans recompiler).
 *
 * Principe de sécurité : chaque getter prend une valeur par défaut (celle codée
 * en dur dans le champion). Si le YAML ne définit pas la clé, c'est la valeur du
 * code qui s'applique → aucun risque de casser un champion si le fichier est
 * incomplet ou absent.
 *
 * Les clés sont l'identifiant du sort : "q_garen", "r_veigar", etc.
 *   q_garen:
 *     cooldown: [8, 7, 6, 5, 4]
 *     cost: 0
 *     base: [30, 55, 80, 105, 130]
 *     ratio_ad: 0.5
 */
public final class Balance {

    private static FileConfiguration cfg;

    private Balance() {}

    /** Charge (ou recharge) champions.yml depuis le dossier du plugin. */
    public static void load() {
        LolPlugin plugin = LolPlugin.getInstance();
        File file = new File(plugin.getDataFolder(), "champions.yml");
        if (!file.exists()) {
            cfg = new YamlConfiguration(); // fichier absent → tout en valeurs par défaut
            return;
        }
        cfg = YamlConfiguration.loadConfiguration(file);
    }

    private static FileConfiguration cfg() {
        if (cfg == null) load();
        return cfg;
    }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < l.size(); i++) a[i] = l.get(i);
        return a;
    }

    /** Tableau de cooldown par rang d'un sort, ou null si non défini (→ valeur du code). */
    public static double[] cd(String abilityId) {
        List<Double> l = cfg().getDoubleList(abilityId + ".cooldown");
        return (l == null || l.isEmpty()) ? null : toArray(l);
    }

    /** Coût en ressource d'un sort, ou la valeur par défaut du code si non défini. */
    public static double cost(String abilityId, double def) {
        String path = abilityId + ".cost";
        return cfg().contains(path) ? cfg().getDouble(path) : def;
    }

    /** Tableau de dégâts de base d'un sort, ou le défaut du code si non défini. */
    public static double[] base(String abilityId, double[] def) {
        List<Double> l = cfg().getDoubleList(abilityId + ".base");
        return (l == null || l.isEmpty()) ? def : toArray(l);
    }

    /** Ratio nommé d'un sort (ad, ap, hp...), ou le défaut du code si non défini. */
    public static double ratio(String abilityId, String key, double def) {
        String path = abilityId + ".ratio_" + key;
        return cfg().contains(path) ? cfg().getDouble(path) : def;
    }
}
