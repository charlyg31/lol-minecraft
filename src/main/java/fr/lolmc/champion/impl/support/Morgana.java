package fr.lolmc.champion.impl.support;

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

public final class Morgana extends BaseChampion {
    public Morgana() {
        super("morgana", "Morgana", ChampionRole.SUPPORT,
                new ChampionStats(630,56,0,25,30,0.625,0,335,4.5,5.5));
        getStats().setGrowthStats(104,3.5,4.2,1.3,0.0153,0.4);
        setLolAttackRange(450); // stats LoL (wiki)
    }
    @Override protected void registerAbilities() {
        setAbility(0,new AA()); setAbility(1,new Q());
        setAbility(2,new W()); setAbility(3,new E()); setAbility(4,new R());
        initSystems(5.5, ResourceSystem.ResourceType.MANA, 340, 60, 11, 0.4);
    }

    // Passif Siphon d'Âme (wiki) : les dégâts des SORTS sur les champions, gros sbires et monstres
    // soignent Morgana de 18 % (avant : 20 % de ses attaques de base)
    static class AA extends BasicAttackAbility {
        AA(){super("morgana",Material.PURPLE_DYE,5.5f,DamageType.MAGICAL);}
    }

    static void siphon(Player c, org.bukkit.entity.LivingEntity tgt, double dmg) {
        String mt = fr.lolmc.game.MinionManager.getMinionTypeTag(tgt);
        if (tgt instanceof Player || fr.lolmc.game.JungleManager.isJungleMonster(tgt)
                || "cannon".equals(mt) || "super".equals(mt)) {
            var cm = LolPlugin.getInstance().getChampionManager();
            if (cm.hasChampion(c)) cm.getChampion(c).getHPSystem().heal(dmg * 0.18);
        }
    }

    static class Q extends BaseAbility {
        Q(){super("q_morgana","Filet des Ombres",Material.LEAD,AbilitySlot.Q,
                new double[]{10,10,10,10,10},20,0,DamageType.MAGICAL);
            resourceCost = 50; resourceCosts = new double[]{50,55,60,65,70};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : skillshot de 1300 unités, enracine 2-3 s (avant : sort ciblé à 6.5 blocs, 2 s)
            // Projectile réel : 1300 unités à 1200/s
            TargetingUtil.launchSkillshot(c, c.getEyeLocation(), c.getEyeLocation().getDirection(), 13.0, 0.7, false, 12.0, // missile : 1300 unités (données du jeu)
                    new org.bukkit.inventory.ItemStack(Material.PURPLE_STAINED_GLASS), 0.8f, Material.PURPLE_STAINED_GLASS,
                    tgt -> bindingHit(c, s, tgt),
                    hits -> { if(hits.isEmpty()) c.sendActionBar(Component.text("🕸 Entrave manquée!",NamedTextColor.GRAY)); });
        }

        private void bindingHit(Player c, ChampionStats s, org.bukkit.entity.LivingEntity tgt){
            double[] base=fr.lolmc.util.Balance.base("q_morgana",new double[]{80,135,190,245,300});double dmg=base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_morgana","ap",0.9);
            DamageUtil.abilityDamageMagicEntity(c, tgt, dmg);
            siphon(c, tgt, dmg);
            double[] rootSec={2,2.25,2.5,2.75,3};
            fr.lolmc.LolPlugin.getInstance().getCCManager().root(tgt, (int)(rootSec[getLevel()-1]*20));
            if(tgt instanceof Player _tp)_tp.sendActionBar(Component.text("🕸 Enraciné — Morgana Q!",NamedTextColor.DARK_PURPLE));
            fr.lolmc.util.VisualEffectUtil.impactBurst(c.getWorld(),
                    tgt.getLocation(), Material.PURPLE_STAINED_GLASS, 0.28f, 0.5, 8, 6L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] base=fr.lolmc.util.Balance.base("q_morgana",new double[]{80,135,190,245,300});double[] rs={2,2.25,2.5,2.75,3};
            return String.format("Skillshot: %.0f dégâts (+90%%AP) + enracine %.2fs.",base[getLevel()-1]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("q_morgana","ap",0.9),rs[getLevel()-1]);
        }
    }

    static class W extends BaseAbility {
        W(){super("w_morgana","Emprisonnement Torturé",Material.NETHERRACK,AbilitySlot.W,
                new double[]{12,12,12,12,12},20,4,DamageType.MAGICAL);
            resourceCost = 70; resourceCosts = new double[]{70,80,90,100,110};}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Zone au sol visée (avant : il fallait viser un ennemi)
            Location loc=TargetingUtil.getAimedGroundLocation(c, 9.0);
            new BukkitRunnable(){
                int tick=0;
                @Override public void run(){
                    if(tick>=100){cancel();return;}
                    // Wiki : 9-35 (+10 % AP) par 0.5 s, jusqu'à ×2 selon les PV manquants ; rayon 280
                    double[] tickMin={9,15.5,22,28.5,35};
                    double baseTick=tickMin[getLevel()-1]+s.getFinalAP()*0.10;
                    for(var __t : TargetingUtil.entitiesInRadius(c, loc, 3.5)){
                        // PV manquants de toute cible (avant : champions seulement)
                        double missingPct=1.0-TargetingUtil.getRealHealth(__t)/Math.max(1, TargetingUtil.getRealMaxHealth(__t));
                        double dmg=baseTick*(1.0+missingPct); // jusqu'à x2 sur cible basse vie
                        if(fr.lolmc.game.JungleManager.isJungleMonster(__t)) dmg*=1.70; // 170 % contre les monstres (données du jeu)
                        TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                        siphon(c, __t, dmg);
                    }
                    fr.lolmc.util.VisualEffectUtil.impactBurst(loc.getWorld(),
                            loc, Material.PURPLE_STAINED_GLASS, 0.22f, 2.0, 5, 6L);
                    tick+=10; // tick toutes les 0.5s (LoL)
                }
            }.runTaskTimer(LolPlugin.getInstance(),0L,10L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] tickMin={9,15.5,22,28.5,35};
            return String.format("Zone: %.0f dégâts magiques/0.5s (5s), +0-100%% selon PV manquants.",tickMin[getLevel()-1]+s.getFinalAP()*0.10);
        }
    }

    static class E extends BaseAbility {
        E(){super("e_morgana","Bouclier Noir",Material.BLACK_STAINED_GLASS,AbilitySlot.E,
                new double[]{26,23.5,21,18.5,16},0,0,DamageType.TRUE);
            resourceCost = 80;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Bouclier Noir : protège un allié (ou soi), absorbe les dégâts magiques + immunité CC tant qu'il tient
            Player dest = (t!=null && LolPlugin.getInstance().getTeamManager().areAllies(c,t)) ? t : c; // allié visé, sinon soi
            double[] shieldBase = {100,155,210,265,320}; // wiki
            double shield = shieldBase[getLevel()-1] + s.getFinalAP()*0.70;
            var cm = LolPlugin.getInstance().getChampionManager();
            // Bouclier anti-dégâts magiques (absorbe d'abord la magie), 5 s
            if (cm.hasChampion(dest)) {
                var destStats = cm.getChampion(dest).getStats();
                destStats.addMagicShield(shield);
                fr.lolmc.item.ItemEffects.onAllyHealShield(c, dest, 0, shield); // objets de soin/bouclier
                // Immunité aux contrôles tant que le bouclier tient (avant : annoncée, non appliquée)
                LolPlugin.getInstance().getCCManager().grantSpellShieldImmunity(dest, 5000L);
                new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){ destStats.addMagicShield(-shield); }}
                        .runTaskLater(LolPlugin.getInstance(), 100L);
            }
            dest.sendActionBar(Component.text("🛡 Bouclier Noir! ("+(int)shield+")",NamedTextColor.DARK_GRAY));
            c.getWorld().playSound(c.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 0.6f);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] shieldBase = {100,155,210,265,320};
            return String.format("Bouclier %.0f (+70%%AP) anti-dégâts magiques + immunité CC sur un allié.",shieldBase[getLevel()-1]+s.getFinalAP()*0.70);
        }
    }

    static class R extends BaseAbility {
        /** Distance de rupture des chaînes (1050 unités + rayons). */
        static final double BREAK = 11.8;
        R(){super("r_morgana","Chaînes de la Corruption",Material.IRON_NUGGET,AbilitySlot.R,
                new double[]{120,110,100},20,5,DamageType.MAGICAL);
            resourceCost = 100;}
        @Override public void cast(Player c,ChampionStats s,Player t){
            // Wiki : enchaîne les champions ennemis proches (625) : 200/275/350 (+80 % AP) tout de
            // suite, ralentit 20 % ; après 3 s, ceux encore enchaînés reprennent les dégâts et sont
            // étourdis 1.5/1.75/2 s. Avant : dégâts + étourdissement immédiats.
            int r=Math.min(getLevel()-1,2);
            double dmg=new double[]{200,275,350}[r]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_morgana","ap",0.8);
            var chained=new java.util.ArrayList<org.bukkit.entity.LivingEntity>();
            for(var __t : TargetingUtil.enemiesAround(c, 6.9)){
                if(!(__t instanceof Player)) continue;
                TargetingUtil.dealDamage(c, __t, dmg, TargetingUtil.DmgType.MAGICAL);
                siphon(c, __t, dmg);
                fr.lolmc.LolPlugin.getInstance().getCCManager().slowPct(__t, 0.20, 60); // 20 % pendant la chaîne (wiki)
                chained.add(__t);
                ((Player)__t).sendActionBar(Component.text("⛓ Chaînes de l'Âme! Éloigne-toi (3s)",NamedTextColor.DARK_PURPLE));
            }
            if(chained.isEmpty()){c.sendActionBar(Component.text("⛓ Aucun champion ennemi proche",NamedTextColor.GRAY));cancelCast();return;}
            fr.lolmc.LolPlugin.getInstance().getCCManager().speedPct(c, new double[]{0.20,0.40,0.60}[r], 60); // wiki
            int stunTicks=new int[]{30,35,40}[r];
            fr.lolmc.util.VisualEffectUtil.groundRing(c.getWorld(),
                    c.getLocation(), 6.9, Material.PURPLE_STAINED_GLASS, 32, 0.6f, 0.1f, 12L); // rayon réel (625)
            // Chaînes visibles entre Morgana et chaque champion enchaîné (rupture au-delà de 1050)
            new BukkitRunnable(){ int n=0;
                @Override public void run(){
                    if(n++>=12 || !c.isOnline()){cancel();return;}
                    for(var e : chained){
                        if(e.isDead() || !e.getWorld().equals(c.getWorld()) || e.getLocation().distance(c.getLocation())>BREAK) continue;
                        var from=c.getLocation().add(0,1,0); var to=e.getLocation().add(0,1,0);
                        fr.lolmc.util.VisualEffectUtil.skillshotLine(from, to.toVector().subtract(from.toVector()).normalize(),
                                from.distance(to), Material.PURPLE_STAINED_GLASS, 0.08f, 6L);
                    }
                }}.runTaskTimer(LolPlugin.getInstance(), 0L, 5L);
            new BukkitRunnable(){@Override public void run(){
                if(!c.isOnline()) return;
                var cc=LolPlugin.getInstance().getCCManager();
                for(var e : chained){
                    if(e.isDead() || !e.getWorld().equals(c.getWorld())
                            || e.getLocation().distance(c.getLocation())>BREAK) continue; // chaîne rompue (1050, wiki ; avant : 625)
                    TargetingUtil.dealDamage(c, e, dmg, TargetingUtil.DmgType.MAGICAL);
                    siphon(c, e, dmg);
                    if(cc!=null) cc.stun(e, stunTicks);
                    if(e instanceof Player __p) __p.sendActionBar(Component.text("⛓ STUN Morgana R!",NamedTextColor.DARK_PURPLE));
                }
            }}.runTaskLater(LolPlugin.getInstance(), 60L);
        }
        @Override public String getDynamicDescription(ChampionStats s){
            double[] rBase={200,275,350};
            return String.format("Enchaîne les champions proches: %.0f dégâts, puis après 3s encore %.0f + stun s'ils sont restés.",
                    rBase[Math.min(getLevel()-1,2)]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_morgana","ap",0.8),
                    rBase[Math.min(getLevel()-1,2)]+s.getFinalAP()*fr.lolmc.util.Balance.ratio("r_morgana","ap",0.8));
        }
    }
}