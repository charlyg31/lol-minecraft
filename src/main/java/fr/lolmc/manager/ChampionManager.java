package fr.lolmc.manager;

import fr.lolmc.LolPlugin;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.champion.impl.adc.*;
import fr.lolmc.champion.impl.jungle.*;
import fr.lolmc.champion.impl.mid.*;
import fr.lolmc.champion.impl.support.*;
import fr.lolmc.champion.impl.top.*;
import org.bukkit.entity.Player;

import java.util.*;

public class ChampionManager {

    // Champion actif par joueur
    private final Map<UUID, BaseChampion> activeChampions = new HashMap<>();

    // Registre de tous les champions disponibles
    private final Map<String, BaseChampion> registry = new LinkedHashMap<>();

    public ChampionManager() {
        registerAll();
    }

    private void registerAll() {
        // TOP
        register(new Garen());
        register(new Malphite());
        register(new Darius());
        register(new Nasus());
        // JUNGLE
        register(new Warwick());
        register(new Amumu());
        register(new MasterYi());
        register(new LeeSin());
        // MID
        register(new Annie());
        register(new Veigar());
        register(new Zed());
        register(new Yasuo());
        // SUPPORT
        register(new Morgana());
        register(new Leona());
        register(new Blitzcrank());
        register(new Janna());
        // ADC
        register(new Ashe());
        register(new Sivir());
        register(new Jinx());
        register(new MissFortune());
    }

    private void register(BaseChampion champion) {
        registry.put(champion.getId(), champion);
    }

    // ── Assigner un champion à un joueur ──
    public void assignChampion(Player player, String championId) {
        BaseChampion champ = registry.get(championId.toLowerCase());
        if (champ == null) {
            player.sendMessage("§cChampion introuvable : " + championId);
            return;
        }
        // Créer une nouvelle instance pour ce joueur
        BaseChampion instance = createInstance(championId);
        if (instance == null) return;

        activeChampions.put(player.getUniqueId(), instance);
        // Vider l'inventaire puis poser la hotbar complète (sorts + Flash + actifs + bouton page 2)
        player.getInventory().clear();
        LolPlugin.getInstance().getHotbarManager().initPlayer(player, instance);
        player.sendMessage("§a✓ Champion assigné : §e" + instance.getDisplayName());
    }

    public BaseChampion getChampion(Player player) {
        return activeChampions.get(player.getUniqueId());
    }

    public void removeChampion(Player player) {
        activeChampions.remove(player.getUniqueId());
    }

    public boolean hasChampion(Player player) {
        return activeChampions.containsKey(player.getUniqueId());
    }

    public BaseChampion getPrototype(String id) { return registry.get(id.toLowerCase()); }

    public Collection<BaseChampion> getAllChampions() {
        return registry.values();
    }

    public List<BaseChampion> getChampionsByRole(BaseChampion.ChampionRole role) {
        List<BaseChampion> result = new ArrayList<>();
        for (BaseChampion c : registry.values()) {
            if (c.getRole() == role) result.add(c);
        }
        return result;
    }

    /** Créer une nouvelle instance fraîche du champion */
    private BaseChampion createInstance(String id) {
        return switch (id) {
            case "garen"       -> new Garen();
            case "malphite"    -> new Malphite();
            case "darius"      -> new Darius();
            case "nasus"       -> new Nasus();
            case "warwick"     -> new Warwick();
            case "amumu"       -> new Amumu();
            case "masteryi"    -> new MasterYi();
            case "leesin"      -> new LeeSin();
            case "annie"       -> new Annie();
            case "veigar"      -> new Veigar();
            case "zed"         -> new Zed();
            case "yasuo"       -> new Yasuo();
            case "morgana"     -> new Morgana();
            case "leona"       -> new Leona();
            case "blitzcrank"  -> new Blitzcrank();
            case "janna"       -> new Janna();
            case "ashe"        -> new Ashe();
            case "sivir"       -> new Sivir();
            case "jinx"        -> new Jinx();
            case "missfortune" -> new MissFortune();
            default -> null;
        };
    }
}
