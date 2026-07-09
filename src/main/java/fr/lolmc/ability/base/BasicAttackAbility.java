package fr.lolmc.ability.base;

import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.TargetingUtil;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Attaque de base réutilisable.
 * Évite de redupliquer le même code d'AA dans chaque champion : ciblage
 * (cible visée si aucune fournie), dégâts = AD final, type physique/magique.
 * Les champions ayant un passif d'attaque (ralentissement, soin, dégâts
 * bonus...) surchargent simplement {@link #onHit}.
 */
public class BasicAttackAbility extends BaseAbility {

    private final double aaRange;
    private final TargetingUtil.DmgType dmgType;

    public BasicAttackAbility(String championId, Material icon, double range, DamageType type) {
        super("aa_" + championId, "Attaque de base", icon, AbilitySlot.AA,
                new double[]{0.5}, range, 0, type);
        this.aaRange = range;
        this.dmgType = (type == DamageType.MAGICAL)
                ? TargetingUtil.DmgType.MAGICAL : TargetingUtil.DmgType.PHYSICAL;
        this.resourceCost = 0;
    }

    @Override
    public void cast(Player caster, ChampionStats stats, Player target) {
        LivingEntity tgt = (target != null) ? target : TargetingUtil.getTargetedEnemy(caster, aaRange);
        if (tgt == null) return;
        double dmg = stats.getFinalAD();
        TargetingUtil.dealDamage(caster, tgt, dmg, dmgType);
        onHit(caster, stats, tgt, dmg);
    }

    /** Passif d'attaque de base (ralentissement, soin, dégâts bonus...). Vide par défaut. */
    protected void onHit(Player attacker, ChampionStats stats, LivingEntity target, double damageDealt) { }

    @Override
    public String getDynamicDescription(ChampionStats stats) {
        return String.format("Attaque de base : %.0f dégâts.", stats.getFinalAD());
    }
}
