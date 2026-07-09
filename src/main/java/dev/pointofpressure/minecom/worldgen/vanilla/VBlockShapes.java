package dev.pointofpressure.minecom.worldgen.vanilla;

import net.minestom.server.instance.block.Block;

/**
 * Approximates vanilla {@code BlockState.isCollisionShapeFullBlock() && !hasDynamicShape()},
 * used only to split template blocks into the "full" vs "other" ordering buckets that decide
 * structure-processor RNG order. Uses Minestom's collision-shape bounding box as the signal.
 */
final class VBlockShapes {
    private VBlockShapes() {}

    static boolean isFullCube(String name, Block state) {
        var reg = state.registry();
        if (reg == null) return false;
        var shape = reg.collisionShape();
        if (shape == null) return false;
        var s = shape.relativeStart();
        var e = shape.relativeEnd();
        return s.x() == 0 && s.y() == 0 && s.z() == 0 && e.x() == 1 && e.y() == 1 && e.z() == 1;
    }
}
