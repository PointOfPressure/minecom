package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;

/**
 * Vanilla OreVeinifier: giant copper (granite filler, y 0..50) and iron (tuff
 * filler, y -60..-8) veins driven by the vein_toggle/vein_ridged/vein_gap
 * router functions (cell-interpolated) and the "minecraft:ore" positional
 * random. Applies wherever the aquifer says solid.
 */
public final class VVeins {
    private final VDensity.DF veinToggle;
    private final VDensity.DF veinRidged;
    private final VDensity.DF veinGap;
    private final XRandom.Positional oreRandom;

    public VVeins(VDensity.Builder builder, JsonObject noiseRouter) {
        this.veinToggle = builder.build(noiseRouter.get("vein_toggle"));
        this.veinRidged = builder.build(noiseRouter.get("vein_ridged"));
        this.veinGap = builder.build(noiseRouter.get("vein_gap"));
        this.oreRandom = builder.seedRandom().fromHashOf("minecraft:ore").forkPositional();
    }

    /** The vein block at a solid position, or null for the default block (stone). */
    public Block veinAt(int x, int y, int z) {
        double toggle = veinToggle.compute(x, y, z);
        boolean copper = toggle > 0.0;
        int minY = copper ? 0 : -60;
        int maxY = copper ? 50 : -8;
        double veininessRidged = Math.abs(toggle);
        int distanceFromTop = maxY - y;
        int distanceFromBottom = y - minY;
        if (distanceFromBottom < 0 || distanceFromTop < 0) return null;
        int distanceFromEdge = Math.min(distanceFromTop, distanceFromBottom);
        double edgeRoundoff = clampedMap(distanceFromEdge, 0.0, 20.0, -0.2, 0.0);
        if (veininessRidged + edgeRoundoff < 0.4F) return null;
        XRandom random = oreRandom.at(x, y, z);
        if (random.nextFloat() > 0.7F) return null;
        if (veinRidged.compute(x, y, z) >= 0.0) return null;
        double richness = clampedMap(veininessRidged, 0.4F, 0.6F, 0.1F, 0.3F);
        if (random.nextFloat() < richness && veinGap.compute(x, y, z) > -0.3F) {
            return random.nextFloat() < 0.02F
                    ? (copper ? Block.RAW_COPPER_BLOCK : Block.RAW_IRON_BLOCK)
                    : (copper ? Block.COPPER_ORE : Block.DEEPSLATE_IRON_ORE);
        }
        return copper ? Block.GRANITE : Block.TUFF;
    }

    private static double clampedMap(double value, double fromMin, double fromMax, double toMin, double toMax) {
        double delta = (value - fromMin) / (fromMax - fromMin);
        if (delta < 0.0) return toMin;
        return delta > 1.0 ? toMax : toMin + delta * (toMax - toMin);
    }
}
