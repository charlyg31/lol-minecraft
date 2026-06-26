package fr.lolmc.bungee;

import net.md_5.bungee.api.plugin.Plugin;

/**
 * LolMC-Bungee — Plugin proxy BungeeCord.
 *
 * Rôle unique : intercepter les changements de serveur et transmettre
 * le serveur d'origine de chaque joueur au plugin lobby.
 *
 * Installation :
 *   → Mettre LolMC-Bungee.jar dans /plugins/ du proxy BungeeCord
 *   → Configurer bungee.yml (nom du serveur lobby)
 *   → Rien à configurer côté Spigot/Paper
 */
public class LolBungeePlugin extends Plugin {

    private static LolBungeePlugin instance;
    private OriginTracker originTracker;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig(); // crée bungee.yml si absent

        this.originTracker = new OriginTracker(this);

        // Enregistrer le listener de changement de serveur
        getProxy().getPluginManager().registerListener(this, new ServerSwitchListener(this, originTracker));

        // Enregistrer le canal de communication avec le lobby
        getProxy().registerChannel(OriginTracker.CHANNEL);

        getLogger().info("LolMC-Bungee activé — suivi du serveur d'origine actif.");
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(OriginTracker.CHANNEL);
        getLogger().info("LolMC-Bungee désactivé.");
    }

    public static LolBungeePlugin getInstance() { return instance; }
    public OriginTracker getOriginTracker()     { return originTracker; }
}
