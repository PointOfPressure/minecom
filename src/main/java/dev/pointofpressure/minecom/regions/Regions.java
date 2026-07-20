package dev.pointofpressure.minecom.regions;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The region scheduler — the entry point of the P2 multi-core design
 * (docs/MULTICORE.md). It owns the set of {@link Region}s and installs the tick
 * barrier that drives them.
 *
 * <p>M1 ships the region=world migration path (MULTICORE.md §5.2, §6): each
 * registered instance gets exactly one region owning all of it, ticking on the
 * server tick thread via {@link RegionExecutor#sameThread()}. The barrier fires
 * every tick, but with one all-owning region on the tick thread and no message
 * producers yet, it is behavior-transparent — the entire existing suite must
 * pass with it active, which is M1's gate. M2 subdivides into a static grid on
 * a worker pool; M3 gives the {@link RegionQueue} its first producers.
 *
 * <p>The mode is selected by {@code MINECOM_REGIONS} (default {@code world}).
 * Only {@code world} is implemented in M1; any other value is rejected so a
 * premature multi-region config can't silently run before M2 exists.
 */
public final class Regions {
    private Regions() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(Regions.class);

    /** Region id -> region. Concurrent per CONVENTIONS §6 (read off-thread in M2+). */
    private static final Map<String, Region> REGIONS = new ConcurrentHashMap<>();
    /** Instance -> its single region (M1 region=world). M2 makes this a grid. */
    private static final Map<Instance, Region> BY_INSTANCE = new ConcurrentHashMap<>();

    private static volatile boolean barrierInstalled;

    /** Runtime mode. M1 supports only WORLD (region=world). */
    public enum Mode { WORLD }

    /** The configured mode, read once from {@code MINECOM_REGIONS}. */
    public static Mode mode() {
        String raw = System.getenv().getOrDefault("MINECOM_REGIONS", "world").trim().toLowerCase();
        if (raw.equals("world")) return Mode.WORLD;
        throw new IllegalStateException("MINECOM_REGIONS=" + raw
                + " is not supported before M2 — only 'world' (region=world) exists in M1");
    }

    /**
     * Register {@code instance} with the scheduler and install the tick barrier
     * on first call. In M1 this creates one region owning all of the instance.
     * Idempotent per instance.
     *
     * @return the region owning the instance
     */
    public static Region register(Instance instance) {
        mode(); // validate config early; throws on an unsupported mode
        Region existing = BY_INSTANCE.get(instance);
        if (existing != null) return existing;

        String id = "world:" + instance.getUuid();
        Region region = new Region(id, instance, RegionExecutor.sameThread(), RegionQueue.concurrent());
        REGIONS.put(id, region);
        BY_INSTANCE.put(instance, region);
        installBarrier();
        LOGGER.info("Region scheduler: registered region {} (mode={}, region=world)", id, mode());
        return region;
    }

    /** Install the once-per-tick barrier that drives every region. */
    private static synchronized void installBarrier() {
        if (barrierInstalled) return;
        barrierInstalled = true;
        MinecraftServer.getSchedulerManager().buildTask(Regions::tickBarrier)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    /**
     * One tick boundary for the whole scheduler: run each region's tick through
     * its executor. In M1 all regions use the same-thread executor, so this runs
     * inline on the tick thread — indistinguishable from not existing. M2 fans
     * the region ticks out across the worker pool and joins on this barrier.
     */
    public static void tickBarrier() {
        for (Region region : REGIONS.values()) {
            region.runTick();
        }
    }

    /**
     * The region owning {@code point} in {@code instance}. In M1 that is the
     * instance's single region (region=world). Returns null if the instance was
     * never registered.
     */
    public static Region regionOf(Instance instance, Point point) {
        Region region = BY_INSTANCE.get(instance);
        return (region != null && region.owns(point)) ? region : null;
    }

    /** The single region owning {@code instance}, or null if unregistered. */
    public static Region of(Instance instance) {
        return BY_INSTANCE.get(instance);
    }

    /** All registered regions (one per instance in M1). */
    public static List<Region> all() {
        return List.copyOf(REGIONS.values());
    }

    /** Whether the tick barrier is live. */
    public static boolean active() {
        return barrierInstalled;
    }
}
