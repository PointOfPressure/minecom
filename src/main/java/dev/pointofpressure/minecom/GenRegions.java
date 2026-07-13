package dev.pointofpressure.minecom;

import dev.pointofpressure.minecom.data.VanillaData;
import dev.pointofpressure.minecom.worldgen.vanilla.VanillaGen;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.anvil.AnvilLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Headless worldgen exporter for the region-diff harness
 * (scripts/worldgen_region_diff.py, MASTERPLAN §2 item 1).
 *
 * Generates the square of overworld chunks [center-radius, center+radius) on
 * both axes for the given seed and saves them as Anvil regions under
 * ./world/region (Minestom AnvilLoader layout), then exits. The harness runs
 * this in a disposable work dir and compares the output block-for-block
 * against a real vanilla server's regions.
 *
 * Usage: java -jar minecom.jar --genregions <seed> <radius> [centerCx centerCz]
 *
 * No margin chunks are needed: VanillaGen.decoratedData re-runs the 8
 * neighbours' decorations and clips their spill into each generated chunk, so
 * every chunk is self-contained regardless of what else is generated.
 */
public final class GenRegions {
    private GenRegions() {}

    public static int run(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: --genregions <seed> <radius> [centerCx centerCz]");
            return 2;
        }
        long seed = Long.parseLong(args[1]);
        int radius = Integer.parseInt(args[2]);
        int centerCx = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int centerCz = args.length > 4 ? Integer.parseInt(args[4]) : 0;

        MinecraftServer server = MinecraftServer.init();
        VanillaData.load();
        InstanceContainer overworld = MinecraftServer.getInstanceManager()
                .createInstanceContainer(new AnvilLoader("world"));
        overworld.setGenerator(new VanillaGen(seed));
        // ephemeral port, same trick as PlayTest: never collides, port is unused
        server.start("127.0.0.1", 0);

        int side = radius * 2;
        int total = side * side;
        long start = System.currentTimeMillis();
        List<CompletableFuture<?>> batch = new ArrayList<>();
        int done = 0;
        for (int cx = centerCx - radius; cx < centerCx + radius; cx++) {
            for (int cz = centerCz - radius; cz < centerCz + radius; cz++) {
                batch.add(overworld.loadChunk(cx, cz));
                if (batch.size() == 64) {
                    done += join(batch);
                    System.out.printf("genregions: %d/%d chunks, %dms%n",
                            done, total, System.currentTimeMillis() - start);
                }
            }
        }
        done += join(batch);
        overworld.saveChunksToStorage().join();
        System.out.printf("genregions done: %d chunks, seed %d, center (%d,%d), radius %d, %dms%n",
                done, seed, centerCx, centerCz, radius, System.currentTimeMillis() - start);
        return 0;
    }

    private static int join(List<CompletableFuture<?>> batch) {
        int n = batch.size();
        CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).join();
        batch.clear();
        return n;
    }
}
