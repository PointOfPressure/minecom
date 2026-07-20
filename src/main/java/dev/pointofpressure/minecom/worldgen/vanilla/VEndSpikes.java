package dev.pointofpressure.minecom.worldgen.vanilla;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The ten obsidian spikes ringing the central End island (EndSpikeFeature). Their
 * ring positions are fixed (radius 42, 10 evenly-spaced angles); which spike gets
 * which size/height, and whether it is iron-caged, comes from a world-seeded shuffle
 * of [0,10) — matching EndSpikeFeature.SpikeCacheLoader.
 */
public final class VEndSpikes {

    private static final int SPIKE_DISTANCE = 42;

    public record Spike(int x, int z, int radius, int height, boolean guarded) {}

    private VEndSpikes() {}

    /** EndSpikeFeature.getSpikesForLevel + SpikeCacheLoader.load. */
    public static List<Spike> spikes(long worldSeed) {
        long key = new VSurface.LegacyRandom(worldSeed).nextLong() & 65535L;
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(key);
        // Util.toShuffledList(IntStream.range(0,10), random)
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < 10; i++) sizes.add(i);
        for (int i = sizes.size(); i > 1; i--) Collections.swap(sizes, i - 1, random.nextInt(i));

        List<Spike> result = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int x = (int) Math.floor(SPIKE_DISTANCE * Math.cos(2.0 * (-Math.PI + (Math.PI / 10) * i)));
            int z = (int) Math.floor(SPIKE_DISTANCE * Math.sin(2.0 * (-Math.PI + (Math.PI / 10) * i)));
            int size = sizes.get(i);
            int radius = 2 + size / 3;
            int height = 76 + size * 3;
            boolean guarded = size == 1 || size == 2;
            result.add(new Spike(x, z, radius, height, guarded));
        }
        return result;
    }

    /** The End's world floor: {@code level.getMinY()} for the_end == 0 (EndSpikeFeature scans from here). */
    private static final int MIN_Y = 0;

    /** Sink for placed blocks — lets {@link #placeBlocks} be verified without a live Instance. */
    @FunctionalInterface
    public interface BlockSink { void set(int x, int y, int z, Block block); }

    /** Place all ten spikes into the End instance (chunks must be loaded first). */
    public static void placeAll(Instance end, long worldSeed) {
        for (Spike s : spikes(worldSeed)) {
            placeBlocks(s, end::setBlock);
            // the end crystal perched on the pillar (heals the dragon in the full fight);
            // an entity, not a block — only spawned when we have a live instance.
            var crystal = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.END_CRYSTAL);
            crystal.setInstance(end, new net.minestom.server.coordinate.Pos(s.x + 0.5, s.height + 1, s.z + 0.5));
        }
    }

    /**
     * Blocks placed by one spike, ported bit-for-bit from {@code EndSpikeFeature.placeSpike}
     * (26.2). The obsidian pillar fills the {@code radius*radius + 1} circle from the world
     * floor up to {@code y < height}; every other cell of the {@code (2r+1)²} column up to
     * {@code height + 10} that sits above {@code y = 65} is carved to air (this is what removes
     * the End-stone island around and above each spike). Guarded spikes get an iron-bar cage at
     * {@code y = height..height+3} with vanilla's exact N/S/E/W connection flags. Finally the
     * crystal pedestal: bedrock at {@code (x, height, z)} ({@code crystalPos.below()}) and fire
     * at {@code (x, height+1, z)} ({@code crystalPos}).
     */
    public static void placeBlocks(Spike s, BlockSink sink) {
        int radius = s.radius;
        for (int yy = MIN_Y; yy <= s.height + 10; yy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // BlockPos.distToLowCornerSqr(centerX, pos.y, centerZ): the y term is
                    // pos.y - pos.y == 0, so this is the horizontal squared distance.
                    if (dx * dx + dz * dz <= radius * radius + 1 && yy < s.height) {
                        sink.set(s.x + dx, yy, s.z + dz, Block.OBSIDIAN);
                    } else if (yy > 65) {
                        sink.set(s.x + dx, yy, s.z + dz, Block.AIR);
                    }
                }
            }
        }

        if (s.guarded) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 0; dy <= 3; dy++) {
                        boolean isXSide = Math.abs(dx) == 2;
                        boolean isZSide = Math.abs(dz) == 2;
                        boolean top = dy == 3;
                        if (isXSide || isZSide || top) {
                            boolean xEdge = dx == -2 || dx == 2 || top;
                            boolean zEdge = dz == -2 || dz == 2 || top;
                            Block bars = Block.IRON_BARS.withProperties(Map.of(
                                    "north", String.valueOf(xEdge && dz != -2),
                                    "south", String.valueOf(xEdge && dz != 2),
                                    "west", String.valueOf(zEdge && dx != -2),
                                    "east", String.valueOf(zEdge && dx != 2)));
                            sink.set(s.x + dx, s.height + dy, s.z + dz, bars);
                        }
                    }
                }
            }
        }

        sink.set(s.x, s.height, s.z, Block.BEDROCK);       // crystalPos.below()
        sink.set(s.x, s.height + 1, s.z, Block.FIRE);      // crystalPos (FireBlock.getState -> plain fire)
    }
}
