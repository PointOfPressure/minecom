package dev.pointofpressure.minecom.worldgen.vanilla;

import java.util.List;

/**
 * Bit-exact port of Beardifier: the density-function-level terrain carve applied around rigid
 * structure pieces and jigsaw junctions, driven by each structure's {@code terrain_adaptation}
 * (beard_box/beard_thin/encapsulate/bury). Evaluated as part of the "beardifier" node in the
 * final_density graph, so structures physically reshape the terrain around them (not just place
 * blocks on top of it) — e.g. ancient_city's large carved caverns.
 */
public final class VBeardifier {
    public enum Adjustment { NONE, BURY, BEARD_THIN, BEARD_BOX, ENCAPSULATE }

    /** A rigid piece's bounding box {minX,minY,minZ,maxX,maxY,maxZ}. */
    public record Rigid(int[] bb, Adjustment adjustment, int groundLevelDelta) {}

    public record Junction(int sourceX, int sourceGroundY, int sourceZ) {}

    private static final int BEARD_KERNEL_RADIUS = 12;
    private static final int BEARD_KERNEL_SIZE = 24;
    private static final float[] BEARD_KERNEL = new float[13824];

    static {
        for (int zi = 0; zi < BEARD_KERNEL_SIZE; zi++) {
            for (int xi = 0; xi < BEARD_KERNEL_SIZE; xi++) {
                for (int yi = 0; yi < BEARD_KERNEL_SIZE; yi++) {
                    BEARD_KERNEL[zi * 24 * 24 + xi * 24 + yi] =
                            (float) computeBeardContribution(xi - BEARD_KERNEL_RADIUS, (yi - BEARD_KERNEL_RADIUS) + 0.5, zi - BEARD_KERNEL_RADIUS);
                }
            }
        }
    }

    public static final VBeardifier EMPTY = new VBeardifier(List.of(), List.of());

    private final List<Rigid> pieces;
    private final List<Junction> junctions;

    public VBeardifier(List<Rigid> pieces, List<Junction> junctions) {
        this.pieces = pieces;
        this.junctions = junctions;
    }

    public double compute(int blockX, int blockY, int blockZ) {
        if (pieces.isEmpty() && junctions.isEmpty()) return 0.0;
        double noiseValue = 0.0;
        for (Rigid rigid : pieces) {
            int[] box = rigid.bb;
            int groundLevelDelta = rigid.groundLevelDelta;
            int dx = Math.max(0, Math.max(box[0] - blockX, blockX - box[3]));
            int dz = Math.max(0, Math.max(box[2] - blockZ, blockZ - box[5]));
            int groundY = box[1] + groundLevelDelta;
            int dyToGround = blockY - groundY;

            int dy = switch (rigid.adjustment) {
                case NONE -> 0;
                case BURY, BEARD_THIN -> dyToGround;
                case BEARD_BOX -> Math.max(0, Math.max(groundY - blockY, blockY - box[4]));
                case ENCAPSULATE -> Math.max(0, Math.max(box[1] - blockY, blockY - box[4]));
            };

            noiseValue += switch (rigid.adjustment) {
                case NONE -> 0.0;
                case BURY -> getBuryContribution(dx, dy / 2.0, dz);
                case BEARD_THIN, BEARD_BOX -> getBeardContribution(dx, dy, dz, dyToGround) * 0.8;
                case ENCAPSULATE -> getBuryContribution(dx / 2.0, dy / 2.0, dz / 2.0) * 0.8;
            };
        }
        for (Junction j : junctions) {
            int dx = blockX - j.sourceX;
            int dy = blockY - j.sourceGroundY;
            int dz = blockZ - j.sourceZ;
            noiseValue += getBeardContribution(dx, dy, dz, dy) * 0.4;
        }
        return noiseValue;
    }

    private static double getBuryContribution(double dx, double dy, double dz) {
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return VDensity.clampedMap(distance, 0.0, 6.0, 1.0, 0.0);
    }

    private static double getBeardContribution(int dx, int dy, int dz, int yToGround) {
        int xi = dx + BEARD_KERNEL_RADIUS, yi = dy + BEARD_KERNEL_RADIUS, zi = dz + BEARD_KERNEL_RADIUS;
        if (isInKernelRange(xi) && isInKernelRange(yi) && isInKernelRange(zi)) {
            double dyWithOffset = yToGround + 0.5;
            double distanceSqr = dx * dx + dyWithOffset * dyWithOffset + (double) dz * dz;
            double value = -dyWithOffset * fastInvSqrt(distanceSqr / 2.0) / 2.0;
            return value * BEARD_KERNEL[zi * 24 * 24 + xi * 24 + yi];
        }
        return 0.0;
    }

    private static boolean isInKernelRange(int xi) {
        return xi >= 0 && xi < BEARD_KERNEL_SIZE;
    }

    private static double computeBeardContribution(int dx, double dy, int dz) {
        double distanceSqr = dx * dx + dy * dy + (double) dz * dz;
        return Math.pow(Math.E, -distanceSqr / 16.0);
    }

    /** Mth.fastInvSqrt: the quake-style bit-hack inverse sqrt vanilla uses here (not plain 1/sqrt — deliberately imprecise, affects boundary blocks). */
    private static double fastInvSqrt(double x) {
        double xhalf = 0.5 * x;
        long i = Double.doubleToRawLongBits(x);
        i = 6910469410427058090L - (i >> 1);
        x = Double.longBitsToDouble(i);
        return x * (1.5 - xhalf * x * x);
    }
}
