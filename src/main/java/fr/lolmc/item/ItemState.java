package fr.lolmc.item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stocke tous les stacks et états liés aux items pour chaque joueur.
 * Un seul objet par joueur, géré par PassiveManager.
 */
public class ItemState {

    // ── Spellblade (Trinity, Lich Bane, Sheen, Divine Sunderer, Essence Reaver) ──
    public boolean spellbladePrimed = false;    // true après avoir lancé un sort
    public long spellbladeTime = 0;             // timestamp du prime (expire 10s)

    // ── Stacks on-hit ──
    public int krakenStacks = 0;                // 0→3, 3ème AA = vrais dégâts
    public int voltaicStacks = 0;               // 0→100, 100 = éclair
    public int sunfireStacks = 0;               // 0→6, +dmg par stack

    // ── Stacks par cible (armure, MR) ──
    // Map<targetUUID, stacks>
    public final Map<UUID, Integer> blackCleaverStacks = new HashMap<>();
    public final Map<UUID, Integer> forceOfNatureStacks = new HashMap<>();  // stacks MR
    public final Map<UUID, Integer> jakShoStacks = new HashMap<>();         // stacks résistances

    // ── Stacks permanents (Heartsteel, Manamune) ──
    public int heartsteelHP = 0;                // HP max permanent gagnés
    public int manaMuneStacks = 0;              // stacks pour Manamune
    public double bonusADFromMana = 0;          // AD bonus Manamune calculé

    // ── Cooldowns d'actifs (timestamp dernière utilisation) ──
    public long lastZhonyas = 0;                // 120s
    public long lastGaleforce = 0;              // 90s
    public long lastShurelyas = 0;              // 120s
    public long lastRedemption = 0;             // 120s
    public long lastLocket = 0;                 // 90s
    public long lastMikaels = 0;                // 90s
    public long lastBotrkActive = 0;            // 60s
    public long lastHextechRocket = 0;          // 90s
    public long lastDeadManPlate = 0;
    public long lastSerpentsFang = 0;

    // ── États spéciaux ──
    public boolean zhonyasActive = false;       // invulnérabilité en cours
    public boolean sterakActive = false;        // bouclier actif
    public boolean gaActive = false;            // Guardian Angel en cours
    public long sterakCooldown = 0;             // 45s
    public long gaCooldown = 0;                 // 300s
    public long warmogTime = 0;                 // dernier tick regen Warmog's

    // ── Antiheal actif (sur cible) ──
    // Map<targetUUID, expireTimestamp>
    public final Map<UUID, Long> antihealTargets = new HashMap<>();

    // ── DoT actifs (Liandry's, Demonic) ──
    public final Map<UUID, Long> liandryDotActive = new HashMap<>();   // Map<target, expireTime>
    public final Map<UUID, Long> demonicDotActive = new HashMap<>();

    // ── Navori (CD réduit par crits) ──
    public boolean lastHitCrit = false;

    // ── Spear of Shojin (AA reduce CD) ──
    public int shojinAaCount = 0;              // 0→3 AA après sort

    // ── Dead Man's Plate (stacks mouvement) ──
    public int deadManStacks = 0;              // 0→100

    // ── Statikk Shiv (stacks éclair) ──
    public int statikkStacks = 0;              // 0→3

    // ── Runaan's Hurricane (multi-cibles) ──
    // Pas besoin de state, géré à chaque AA

    // ── Spirit Visage ──
    public boolean hasSpiritVisage = false;    // +30% soins

    // ── Abyssal Mask ──
    public boolean hasAbyssalMask = false;     // -15% MR ennemis proches

    // ── Hubris (+AD sur kill) ──
    public long hubrisExpire = 0;
    public double hubrisAD = 0;

    // ── Axiom Arc (réduction CD ultime sur kill) ──
    public boolean hasAxiomArc = false;

    // ── Ardent Censer (buff allié après soin) ──
    public long ardentCenserBuff = 0;

    // ── Frozen Mallet (slow on-hit) ──
    public boolean hasFrozenMallet = false;

    // ── Chempunk / Mortal Reminder (antiheal) ──
    public boolean hasAntihealItem = false;

    // ── Guinsoo's Rageblade ──
    public boolean hasRageblade = false;       // crits → double on-hit

    // ── Omnivamp ──
    public double pendingOmnivampHeal = 0;     // accumulé en combat

    // ── Items de jungle ──
    public long lastMosstomperShield = 0;      // CD 12s (Mosstomper Smite)

    // ── Nouveaux items passifs ──
    public int eclipseStacks = 0;
    public boolean terminusLight = true;
    public long lastStormrazor = 0;
    public int rageknifeCount = 0;
    public int protoStacks = 0;
    public boolean duskDawnReady = false;
    public boolean nextAACrit = false;
    public int riftmakerStacks = 0;
    public final java.util.Map<java.util.UUID, Integer> bloodletterStacks = new java.util.HashMap<>();
    public final java.util.Map<java.util.UUID, Integer> riteStacks = new java.util.HashMap<>();
    public long lastLudensProc = 0;
    public boolean bansheeActive = false;
    public long lastBansheeBreak = 0;
    public boolean crownActive = false;
    public boolean shieldbowActive = false;
    public long lastShieldbowProc = 0;
    public boolean seraphActive = false;
    public boolean mantle12Active = false;
    public java.util.UUID anathemaTarget = null;
    public int darkSealStacks = 0;
    public int mejaisStacks = 0;
    public int cullStacks = 0;
    public boolean opportunityReady = false;

    // ── Helpers ──
    public boolean isSpellbladeReady() {
        return spellbladePrimed && (System.currentTimeMillis() - spellbladeTime) < 10000L;
    }

    public void primeSpellblade() {
        spellbladePrimed = true;
        spellbladeTime = System.currentTimeMillis();
    }

    public void consumeSpellblade() {
        spellbladePrimed = false;
    }

    public boolean isOnCooldown(long lastUse, long cooldownMs) {
        return (System.currentTimeMillis() - lastUse) < cooldownMs;
    }

    public void reset() {
        spellbladePrimed = false;
        krakenStacks = 0;
        voltaicStacks = 0;
        sunfireStacks = 0;
        blackCleaverStacks.clear();
        statikkStacks = 0;
        deadManStacks = 0;
        shojinAaCount = 0;
        antihealTargets.clear();
        liandryDotActive.clear();
        demonicDotActive.clear();
    }
}
