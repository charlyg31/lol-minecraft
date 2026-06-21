package fr.lolmc.util;

import fr.lolmc.LolPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Système de log de débug écrit dans un fichier (debug.log) du dossier du plugin.
 *
 * Activable/désactivable en jeu avec /lol debug on|off.
 * L'état est sauvegardé dans la config pour persister après redémarrage.
 *
 * Usage : DebugLogger.log("AbilityListener", "armswing déclenché slot=2");
 */
public final class DebugLogger {

    private static boolean enabled = false;
    private static File logFile;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    private DebugLogger() {}

    /** Initialise le logger (appelé au démarrage du plugin). */
    public static void init() {
        LolPlugin plugin = LolPlugin.getInstance();
        logFile = new File(plugin.getDataFolder(), "debug.log");
        // Restaurer l'état depuis la config
        enabled = plugin.getConfig().getBoolean("debug.enabled", false);
        if (enabled) {
            log("DebugLogger", "=== Démarrage du plugin (debug actif) ===");
        }
    }

    /** Active ou désactive le débug, et sauvegarde l'état. */
    public static void setEnabled(boolean value) {
        enabled = value;
        LolPlugin plugin = LolPlugin.getInstance();
        plugin.getConfig().set("debug.enabled", value);
        plugin.saveConfig();
        if (value) {
            log("DebugLogger", "=== DÉBUG ACTIVÉ ===");
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Écrit une ligne dans le fichier de log (si le débug est actif). */
    public static void log(String category, String message) {
        if (!enabled || logFile == null) return;
        String line = "[" + TIME.format(new Date()) + "] [" + category + "] " + message;
        try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
            out.println(line);
        } catch (Exception e) {
            // En dernier recours, logguer dans la console serveur
            LolPlugin.getInstance().getLogger().warning("debug.log écriture échouée: " + e.getMessage());
        }
    }

    /** Vide le fichier de log. */
    public static void clear() {
        if (logFile == null) return;
        try (PrintWriter out = new PrintWriter(new FileWriter(logFile, false))) {
            out.print("");
        } catch (Exception ignored) {}
    }

    /** Renvoie le chemin du fichier de log pour l'afficher au joueur. */
    public static String getPath() {
        return logFile != null ? logFile.getAbsolutePath() : "(non initialisé)";
    }
}
