package fr.lolmc.util;

import fr.lolmc.LolPlugin;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Construit un "modèle" custom pour un mob en assemblant plusieurs
 * BlockDisplay positionnés (offset / échelle / rotation), à la manière
 * d'un mini-BlockBench fait main.
 *
 * Chaque partie est un bloc flottant accroché au mob (passager) qui le suit.
 * Combiné avec MobAppearance.makeInvisible sur le mob de base, on ne voit
 * que le modèle custom — exactement la technique du datapack PvE.
 *
 * Repère : X = gauche/droite, Y = haut, Z = avant/arrière (le mob regarde +Z).
 * Les offsets et échelles sont en blocs. À ajuster visuellement en jeu.
 */
public final class MobModel {

    /** Une partie du modèle : un bloc avec position, taille et rotation Y. */
    private static final class Part {
        final Material block;
        final float ox, oy, oz;      // position (centre du bloc) relative au mob
        final float sx, sy, sz;      // échelle
        final float yawDeg;          // rotation autour de l'axe Y
        Part(Material b, float ox, float oy, float oz, float sx, float sy, float sz, float yawDeg) {
            this.block = b; this.ox = ox; this.oy = oy; this.oz = oz;
            this.sx = sx; this.sy = sy; this.sz = sz; this.yawDeg = yawDeg;
        }
    }

    private final List<Part> parts = new ArrayList<>();

    /** Ajoute un cube (même échelle sur les 3 axes). */
    public MobModel cube(Material b, float ox, float oy, float oz, float s) {
        parts.add(new Part(b, ox, oy, oz, s, s, s, 0f));
        return this;
    }

    /** Ajoute une boîte (échelle différente par axe). */
    public MobModel box(Material b, float ox, float oy, float oz, float sx, float sy, float sz) {
        parts.add(new Part(b, ox, oy, oz, sx, sy, sz, 0f));
        return this;
    }

    /** Ajoute une boîte avec une rotation Y (ailes, membres inclinés...). */
    public MobModel box(Material b, float ox, float oy, float oz, float sx, float sy, float sz, float yawDeg) {
        parts.add(new Part(b, ox, oy, oz, sx, sy, sz, yawDeg));
        return this;
    }

    /** Multiplie toutes les dimensions du modèle (pour les petits monstres de groupe). */
    public MobModel scaleAll(float factor) {
        List<Part> scaled = new ArrayList<>();
        for (Part p : parts) {
            scaled.add(new Part(p.block,
                    p.ox * factor, p.oy * factor, p.oz * factor,
                    p.sx * factor, p.sy * factor, p.sz * factor, p.yawDeg));
        }
        parts.clear();
        parts.addAll(scaled);
        return this;
    }

    /**
     * Fait apparaître le modèle accroché au mob. Chaque partie est un
     * BlockDisplay passager qui suit le mob. Retourne les UUID des parties
     * (à mémoriser pour le nettoyage à la mort / au reset).
     */
    public List<UUID> spawnOn(LivingEntity base) {
        List<UUID> ids = new ArrayList<>();
        for (Part p : parts) {
            try {
                BlockDisplay d = base.getWorld().spawn(base.getLocation(), BlockDisplay.class, disp -> {
                    disp.setBlock(p.block.createBlockData());
                    Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(p.yawDeg));
                    // La translation place le COIN du bloc : on recentre en X/Z.
                    Vector3f tr = new Vector3f(p.ox - p.sx / 2f, p.oy, p.oz - p.sz / 2f);
                    disp.setTransformation(new Transformation(
                            tr, rot, new Vector3f(p.sx, p.sy, p.sz), new Quaternionf()));
                    disp.setBrightness(new Display.Brightness(15, 15)); // toujours bien visible
                    disp.setPersistent(true);
                    disp.getPersistentDataContainer().set(
                            MobAppearance.decoKey(), PersistentDataType.BYTE, (byte) 1);
                });
                base.addPassenger(d);
                ids.add(d.getUniqueId());
            } catch (Exception e) {
                LolPlugin.getInstance().getLogger().warning("MobModel part echec: " + e.getMessage());
            }
        }
        return ids;
    }
}
