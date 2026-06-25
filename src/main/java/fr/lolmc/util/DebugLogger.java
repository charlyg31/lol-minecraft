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
 * Le PrintWriter est maintenu ouvert tant que le debug est actif pour éviter
 * un open/close disque à chaque appel (impact performance critique en combat).
 *
 * Usage : DebugLogger.log("AbilityListener", "armswing déclenché slot=2");
 */
public final class DebugLogger {

    private static boolean enabled = false;
    private static File logFile;
    private static PrintWriter writer;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    private DebugLogger() {}

    /** Initialise le logger (appelé au démarrage du plugin). */
    public static void init() {
        LolPlugin plugin = LolPlugin.getInstance();
        logFile = new File(plugin.getDataFolder(), "debug.log");
        enabled = plugin.getConfig().getBoolean("debug.enabled", false);
        if (enabled) {
            openWriter();
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
            openWriter();
            log("DebugLogger", "=== DÉBUG ACTIVÉ ===");
        } else {
            closeWriter();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Écrit une ligne dans le fichier de log (si le débug est actif). */
    public static void log(String category, String message) {
        if (!enabled || logFile == null) return;
        if (writer == null) openWriter();
        String line = "[" + TIME.format(new Date()) + "] [" + category + "] " + message;
        writer.println(line);
        writer.flush();
    }

    /** Vide le fichier de log. */
    public static void clear() {
        closeWriter();
        if (logFile == null) return;
        try (PrintWriter out = new PrintWriter(new FileWriter(logFile, false))) {
            out.print("");
        } catch (Exception ignored) {}
        if (enabled) openWriter();
    }

    /** Ferme le writer proprement (appelé au onDisable). */
    public static void close() {
        closeWriter();
    }

    /** Renvoie le chemin du fichier de log pour l'afficher au joueur. */
    public static String getPath() {
        return logFile != null ? logFile.getAbsolutePath() : "(non initialisé)";
    }

    private static void openWriter() {
        closeWriter();
        try {
            writer = new PrintWriter(new FileWriter(logFile, true));
        } catch (Exception e) {
            LolPlugin.getInstance().getLogger().warning("debug.log ouverture échouée: " + e.getMessage());
        }
    }

    private static void closeWriter() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }
}
