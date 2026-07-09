package dev.pointofpressure.minecom.worldgen.vanilla;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /** Place all ten spikes into the End instance (chunks must be loaded first). */
    public static void placeAll(Instance end, long worldSeed) {
        for (Spike s : spikes(worldSeed)) place(end, s);
    }

    private static void place(Instance end, Spike s) {
        // obsidian pillar: filled circle of the given radius, rising from the island base to the tip
        int base = 40;
        for (int yy = base; yy <= s.height; yy++) {
            for (int dx = -s.radius; dx <= s.radius; dx++) {
                for (int dz = -s.radius; dz <= s.radius; dz++) {
                    if (dx * dx + dz * dz <= s.radius * s.radius) {
                        end.setBlock(s.x + dx, yy, s.z + dz, Block.OBSIDIAN);
                    }
                }
            }
        }
        end.setBlock(s.x, s.height, s.z, Block.BEDROCK);   // capstone (end-crystal pedestal)

        // the end crystal perched on the pillar (heals the dragon in the full fight)
        var crystal = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.END_CRYSTAL);
        crystal.setInstance(end, new net.minestom.server.coordinate.Pos(s.x + 0.5, s.height + 1, s.z + 0.5));

        if (s.guarded) {
            // iron-bar cage around the tip (EndCrystal cage)
            for (int yy = s.height + 1; yy <= s.height + 4; yy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean onEdge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                        boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                        if (yy == s.height + 4 || (onEdge && !corner)) {
                            end.setBlock(s.x + dx, yy, s.z + dz, Block.IRON_BARS);
                        }
                    }
                }
            }
        }
    }
}
