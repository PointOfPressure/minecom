package dev.pointofpressure.minecom.bench;

import dev.pointofpressure.minecom.Bootstrap;
import dev.pointofpressure.minecom.mobs.Mobs;
import dev.pointofpressure.minecom.redstone.Redstone;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-side scenario setup for the P0 benchmark harness (MASTERPLAN §4,
 * scripts/bench/). Entirely gated on {@code MINECOM_BENCH_SCENARIO} — unset
 * (the production/playtest default), every method here is a no-op, so this
 * never touches a non-bench run's behavior.
 *
 * rust-mc-bot has no "spread across a region" or "build a structure"
 * capability (scripts/bench/rust-mc-bot/VENDOR.md) — scattering bots and
 * populating a redstone/mob-farm world happens here instead, directly
 * against the same APIs Bootstrap/Commands use, not through a console
 * command reader (minecom has none — see HANDOFF for why that wasn't added
 * just for this).
 */
public final class BenchSetup {
    private BenchSetup() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchSetup.class);

    /** Flat, terrain-independent build layer for redstone/mob-farm rigs. */
    private static final int RIG_Y = 100;

    public static void register(GlobalEventHandler events, InstanceContainer overworld) {
        String scenario = System.getenv("MINECOM_BENCH_SCENARIO");
        if (scenario == null) return;

        switch (scenario) {
            case "spread" -> registerSpread(events, overworld);
            case "redstone" -> stampRedstoneClocks(overworld, envInt("MINECOM_BENCH_REDSTONE_COUNT", 400));
            case "mobfarm" -> stampMobFarm(overworld, envInt("MINECOM_BENCH_MOB_COUNT", 150),
                    System.getenv().getOrDefault("MINECOM_BENCH_MOB_KIND", "zombie"));
            case "spawn" -> { /* default connect-at-spawn behavior needs no setup */ }
            default -> LOGGER.warn("Unknown MINECOM_BENCH_SCENARIO={}, ignoring", scenario);
        }
    }

    /** Scatters each newly-joined player across a pregenerated square (scenario b). */
    private static void registerSpread(GlobalEventHandler events, InstanceContainer overworld) {
        int radiusChunks = envInt("MINECOM_BENCH_SPREAD_RADIUS", 50);
        var gen = Bootstrap.vanillaGen();
        if (gen == null) {
            LOGGER.warn("MINECOM_BENCH_SCENARIO=spread but no vanilla generator is active (flat world?) — skipping");
            return;
        }
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) return;
            var rng = ThreadLocalRandom.current();
            int x = rng.nextInt(-radiusChunks, radiusChunks) * 16 + 8;
            int z = rng.nextInt(-radiusChunks, radiusChunks) * 16 + 8;
            int[] top = gen.topBlock(x, z);
            event.getPlayer().teleport(new Pos(x + 0.5, top[0] + 1, z + 0.5));
        });
    }

    /**
     * A grid of double-observer clocks (two observers facing each other —
     * the minimal self-starting vanilla redstone clock, well-known in the
     * community as a lag-test rig) on a flat platform at y={@value RIG_Y}.
     * Each pair pulses forever once kicked with one neighborsChanged call;
     * no lamps/wiring needed for tick load, so this stays deliberately
     * coarse (MASTERPLAN §4 P0 note — a per-contraption-type breakdown is
     * P1 territory, not this one).
     */
    private static void stampRedstoneClocks(InstanceContainer overworld, int count) {
        int perRow = (int) Math.ceil(Math.sqrt(count));
        int spacing = 3;
        int half = perRow * spacing / 2;

        int minCx = (-half - 8) >> 4, maxCx = (half + 8) >> 4;
        int minCz = (-half - 8) >> 4, maxCz = (half + 8) >> 4;
        CompletableFuture<?>[] loads = new CompletableFuture<?>[(maxCx - minCx + 1) * (maxCz - minCz + 1)];
        int li = 0;
        for (int cx = minCx; cx <= maxCx; cx++)
            for (int cz = minCz; cz <= maxCz; cz++)
                loads[li++] = overworld.loadChunk(cx, cz);
        CompletableFuture.allOf(loads).join();

        int placed = 0;
        for (int row = 0; row < perRow && placed < count; row++) {
            for (int col = 0; col < perRow && placed < count; col++) {
                int x = -half + row * spacing;
                int z = -half + col * spacing;
                Pos a = new Pos(x, RIG_Y, z);
                Pos b = new Pos(x + 1, RIG_Y, z);
                overworld.setBlock(a, Block.OBSERVER.withProperty("facing", "east"));
                overworld.setBlock(b, Block.OBSERVER.withProperty("facing", "west"));
                Redstone.neighborsChanged(a);
                placed++;
            }
        }
        LOGGER.info("Bench redstone rig: {} double-observer clocks stamped at y={}", placed, RIG_Y);
    }

    /** A walled pen at ground level near spawn, filled with N persistent hostiles. */
    private static void stampMobFarm(InstanceContainer overworld, int count, String kind) {
        var gen = Bootstrap.vanillaGen();
        int cx = 32, cz = 0; // clear of spawn-finding's own search radius
        int surfaceY = gen != null ? gen.topBlock(cx, cz)[0] + 1 : Bootstrap.FLAT_SURFACE + 1;
        int size = (int) Math.ceil(Math.sqrt(count)) + 4;
        int half = size / 2;

        int minChunkX = (cx - half) >> 4, maxChunkX = (cx + half) >> 4;
        int minChunkZ = (cz - half) >> 4, maxChunkZ = (cz + half) >> 4;
        java.util.List<CompletableFuture<?>> loads = new java.util.ArrayList<>();
        for (int lcx = minChunkX; lcx <= maxChunkX; lcx++)
            for (int lcz = minChunkZ; lcz <= maxChunkZ; lcz++)
                loads.add(overworld.loadChunk(lcx, lcz));
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).join();

        // walls + a roof: an unroofed pen lets sunlight in and the hostiles burn to
        // ash instead of sustaining AI/pathfinding load — real vanilla mob farms are
        // enclosed for the same reason, so a roof is the accurate shape here, not a
        // simplification.
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                boolean wall = Math.abs(x) == half || Math.abs(z) == half;
                for (int y = 0; y < 4; y++) {
                    if (wall) overworld.setBlock(new Pos(cx + x, surfaceY + y, cz + z), Block.COBBLESTONE);
                }
                overworld.setBlock(new Pos(cx + x, surfaceY + 4, cz + z), Block.COBBLESTONE);
            }
        }

        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int x = cx + rng.nextInt(-half + 1, half);
            int z = cz + rng.nextInt(-half + 1, half);
            Mobs.spawn(kind, overworld, new Pos(x + 0.5, surfaceY, z + 0.5));
        }
        LOGGER.info("Bench mob farm: {} {}s penned at ({}, {}, {})", count, kind, cx, surfaceY, cz);
    }

    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        return v != null ? Integer.parseInt(v) : def;
    }
}
