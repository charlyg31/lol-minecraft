package fr.lolmc.champion.impl.adc;

import fr.lolmc.LolPlugin;
import fr.lolmc.ability.base.BaseAbility;
import fr.lolmc.ability.base.BasicAttackAbility;
import fr.lolmc.stats.ResourceSystem;
import fr.lolmc.champion.base.BaseChampion;
import fr.lolmc.stats.ChampionStats;
import fr.lolmc.util.DamageUtil;
import fr.lolmc.util.TargetingUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public final class Sivir extends BaseChampion implements fr.lolmc.champion.base.StatefulChampion {
    public Sivir() {
        super("sivir", "Sivir", ChampionRole.ADC,
            new ChampionStats(600,60,0,30,33,0.625,0,335,5,3.25));
        getStats().setGrowthStats(104,2.5,4,1.1,0.016,0.55);
        setLolAttackRange(500); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(3.25, ResourceSystem.ResourceType.MANA, 340, 45, 6, 0.8);
    }

    private static final java.util.Map<java.util.UUID, Boolean> ricochetActive = new java.util.concurrent.ConcurrentHashMap<>();
    // Ultime actif : chaque attaque réduit les recharges des sorts de 0.5 s (wiki)
    private static final java.util.Map<java.util.UUID, Long> huntUntil = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void resetState(java.util.UUID id) { ricochetActive.remove(id); }
    @Override public void resetAllState()               { ricochetActive.clear(); huntUntil.clear(); }
    public static void setRicochet(java.util.UUID id, boolean v) { ricochetActive.put(id, v); }

    static class AA extends BasicAttackAbility {
        AA(){super("sivir",Material.CROSSBOW,5.5f,DamageType.PHYSICAL);}
        @Override protected void onHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt, double dmg) {
            // À la Chasse : chaque attaque réduit les recharges de Q, W et E de 0.5 s
            if (huntUntil.getOrDefault(c.getUniqueId(), 0L) > System.currentTimeMillis()) {
                var cmH = LolPlugin.getInstance().getChampionManager();
                if (cmH.hasChampion(c)) for (int i = 1; i <= 3; i++) {
                    var a = cmH.getChampion(c).getAbility(i);
                    if (a != null) a.reduceCooldown(c, 0.5);
                }
            }
            if (!ricochetActive.getOrDefault(c.getUniqueId(), false)) return;
            // Ricochets (wiki) : jusqu'à 8 rebonds, 40-50 % AD chacun
            var cmW = LolPlugin.getInstance().getChampionManager();
            int wr = cmW.hasChampion(c) ? Math.max(1, cmW.getChampion(c).getAbility(2).getLevel()) : 1;
            double bouncePct = new double[]{0.40,0.425,0.45,0.475,0.50}[Math.min(wr,5)-1];
            int bounces = 0;
            for (var e : fr.lolmc.util.TargetingUtil.entitiesInRadius(c, tgt.getLocation(), 5.0)) {
                if (e.equals(tgt) || bounces >= 8) continue; // jusqu'à 8 rebonds (LoL)
                fr.lolmc.util.DamageUtil.damageEntity(c, e, s.getFinalAD() * bouncePct, false, fr.lolmc.util.DamageUtil.Type.PHYSICAL);
                fr.lolmc.util.VisualEffectUtil.impact(e.getWorld(),
                        e.getLocation().add(0,1,0), Material.WHITE_STAINED_GLASS, 0.22f, 3L);
                bounces++;
            }
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_sivir","Lame Boomerang",Material.IRON_AXE,AbilitySlot.Q,
            new double[]{14,13,12,11,10},25,0,DamageType.PHYSICAL);
            resourceCost = 70;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : lame lancée en ligne (1250 unités) qui revient : 60-160 (+70 % AD bonus
            // +60 % AP) à l'aller et au retour. Avant : sort ciblé à 6.5 blocs.
            double[] base=fr.lolmc.util.Balance.base("q_sivir",new double[]{60,85,110,135,160});
            double dmg=base[getLevel()-1]+s.getBonusAD()*0.70+s.getFinalAP()*0.60;
            var hits=TargetingUtil.skillshot(c, 12.5, 0.9, true);
            for(var e : hits) DamageUtil.abilityDamageEntity(c, e, dmg);
            new BukkitRunnable(){@Override public void run(){
                for(var e : hits){
                    if(e.isDead()) continue;
                    DamageUtil.abilityDamageEntity(c, e, dmg);
                    fr.lolmc.util.VisualEffectUtil.impact(c.getWorld(), e.getLocation(), Material.WHITE_STAINED_GLASS, 0.25f, 4L);
                }
            }}.runTaskLater(LolPlugin.getInstance(),15L);
            c.getWorld().playSound(c.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.3f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_sivir",new double[]{60,85,110,135,160});double d=base[getLevel()-1]+s.getBonusAD()*0.70+s.getFinalAP()*0.60;
            return String.format("Lame en ligne: %.0f dégâts à l'aller et au retour (boomerang).",d);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_sivir","Ricochets",Material.MUSIC_DISC_13,AbilitySlot.W,
            new double[]{7,7,7,7,7},0,0,DamageType.PHYSICAL);
            resourceCost = 22;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // W Ricochet : active le rebond des AA pendant 6s
            // Wiki : 4 s, +20-40 % de vitesse d'attaque
            setRicochet(c.getUniqueId(), true);
            double asBonus=new double[]{0.20,0.25,0.30,0.35,0.40}[getLevel()-1];
            s.addBonusAttackSpeed(asBonus);
            c.sendActionBar(Component.text("🎯 Ricochets 4s!",NamedTextColor.YELLOW));
            new BukkitRunnable(){@Override public void run(){ setRicochet(c.getUniqueId(), false); s.addBonusAttackSpeed(-asBonus); }}.runTaskLater(LolPlugin.getInstance(),80L);
                }
        @Override public String getDynamicDescription(ChampionStats s){return "4s: +vitesse d'attaque, les attaques rebondissent jusqu'à 8 fois (40-50% AD).";}
    }

    static class E extends BaseAbility {
        E(){super("e_sivir","Bouclier Maléfique",Material.SHIELD,AbilitySlot.E,
            new double[]{22,20,18,16,14},0,0,DamageType.TRUE);
            resourceCost = 0;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Bouclier anti-sort : bloque le prochain sort ennemi pendant 1,5 s
            s.grantSpellShield(1500L);
            c.sendActionBar(Component.text("🛡 Bouclier Maléfique!",NamedTextColor.GREEN));
        }
        @Override public String getDynamicDescription(ChampionStats s){return "Bloque un sort ennemi. Annule le CC.";}
    }

    static class R extends BaseAbility {
        R(){super("r_sivir","Appel des Flèches",Material.GOLDEN_AXE,AbilitySlot.R,
            new double[]{120,90,60},0,0,DamageType.TRUE);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : 8/10/12 s, +20/25/30 % de vitesse pour Sivir et ses alliés proches ; chaque
            // attaque réduit les recharges de 0.5 s (avant : 4 s et une remise à zéro du W)
            int r=Math.min(getLevel()-1,2);
            int ticks=new int[]{160,200,240}[r];
            huntUntil.put(c.getUniqueId(), System.currentTimeMillis()+ticks*50L);
            double msPct=new double[]{0.20,0.25,0.30}[r];
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(c, msPct, ticks);
            var tm0=LolPlugin.getInstance().getTeamManager();
            for(var ally : c.getWorld().getPlayers()){
                if(ally.equals(c)) continue;
                if(tm0!=null && tm0.areAllies(c, ally) && ally.getLocation().distance(c.getLocation())<=10)
                    fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(ally, msPct, ticks);
            }
            c.sendActionBar(Component.text("⚡ À la Chasse! Les attaques réduisent les recharges",NamedTextColor.GOLD));
            c.getWorld().playSound(c.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
        }
        @Override public String getDynamicDescription(ChampionStats s){return "8-12s: +20-30% vitesse (alliés compris), chaque attaque réduit les recharges de 0.5s.";}
    }
}
