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
    public int forceOfNatureStacks = 0;         // stacks MR (max 6, +6 MR chacun)
    public UUID anathemaNemesis = null;         // Anathema's Chains : premier ennemi frappé (-20% dégâts de lui)
    public long coordinatedFireMarkExpire = 0;  // Imperial Mandate : expiration de la marque Coordinated Fire (0 = pas de marque)
    public final Map<UUID, Integer> jakShoStacks = new HashMap<>();         // stacks résistances

    // ── Stacks permanents (Heartsteel, Manamune) ──
    public int heartsteelHP = 0;                // HP max permanent gagnés
    public int manaMuneStacks = 0;              // stacks pour Manamune
    public double bonusADFromMana = 0;          // AD bonus Manamune calculé
    public double bonusHPFromMana = 0;          // HP bonus Winter's Approach calculé (Awe)
    public double bonusADFromMaxHP = 0;         // AD bonus Atma's Reckoning calculé
    public double bonusADFromBonusHP = 0;       // AD bonus Overlord's Bloodmail calculé

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
    public long shieldbowCooldown = 0;          // Immortal Shieldbow : Lifeline, 90s
    public long queendomCooldown = 0;           // Crown of the Shattered Queen : Queendom, pas de vrai CD (retiré en combat) mais on évite le spam
    public boolean queendomActive = false;      // bouclier Queendom actif
    public long ludensLastUse = 0;              // Luden's Companion : Surge, 12s
    public long aftershockLastUse = 0;          // Rune Aftershock : 20s
    public long guardianLastUse = 0;            // Rune Guardian : 45s
    public long nullifyingOrbLastUse = 0;       // Rune Nullifying Orb : 60s
    public long ghostPoroLastUse = 0;           // Rune Ghost Poro : 90s (pose en bush)
    public long magicalFootwearThresholdMs = 720_000L; // Rune Magical Footwear : 12min, -45s/takedown
    public boolean magicalFootwearGiven = false;
    public int biscuitsDelivered = 0; // Rune Biscuit Delivery : compteur (0 à 3, aux minutes 2/4/6)
    public int tripleTonicsGiven = 0; // Rune Triple Tonic : compteur (0 à 2, aux niveaux 3 et 6 ; le 3e élixir niv.9 non implémenté, cf. skill point)
    public int dematerializerCharges = 3; // Rune Minion Dematerializer : 3 charges
    public double dematerializerBonus = 0; // +6% par sbire absorbé (max 3, simplifié : universel plutôt que par type)
    public boolean wasInWaterLastTick = false;  // Rune Waterwalking : évite de réappliquer le bonus chaque tick
    public boolean approachVelocityActive = false; // Rune Approach Velocity : évite de réappliquer le bonus chaque tick
    public double approachVelocityBonus = 0;    // Montant exact appliqué (pour un retrait symétrique correct)
    public final Map<String, Integer> demolishStacks = new java.util.HashMap<>(); // Rune Demolish : stacks par structure (id)
    public final Map<String, Long> demolishCooldown = new java.util.HashMap<>();  // Rune Demolish : 45s par structure
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
    public boolean malignanceReady = false;
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
    public boolean prowlerBonusDmg = false;
    public boolean stopwatchUsed = false;
    public boolean atakhanRevive = false;   // Atakhan Vorace : résurrection 1x
    public boolean hullbreakerActive = false;
    public boolean swiftmarchActive = false;
    public int rodStacks = 0;
    public long lastRodStack = 0;
    // Cooldowns nouveaux actifs
    public long lastEverfrost = 0;
    public long lastGargoyle = 0;
    public long lastGoredrinker = 0;
    public long lastProwler = 0;
    public long lastStridebreaker = 0;
    public long lastQSS = 0;
    public long lastTwinShadows = 0;
    public long lastEvenshroud = 0;

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
