package dev.pointofpressure.minecom.regions;

import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Instance;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A region — the unit of runtime ownership in the P2 multi-core design
 * (docs/MULTICORE.md §1). It owns a contiguous set of chunks and everything
 * anchored to them (blocks, block entities, entities, scheduled ticks), and it
 * ticks on exactly one thread via its {@link RegionExecutor}. Within a region
 * there is zero concurrency — the same single-threaded world the codebase
 * assumes today, just one of N.
 *
 * <p>In M1 there is a single region owning the whole instance (region=world),
 * so {@link #owns} is always true and {@link #runTick} has an empty body:
 * gameplay still ticks through the existing subsystem scheduler tasks, exactly
 * as today. M2 moves entity/block ticking into {@link #runTick} and gives
 * {@link #owns} a real chunk-set predicate (MULTICORE.md §6).
 */
public final class Region {

    private final String id;
    private final Instance instance;
    private final RegionExecutor executor;
    private final RegionQueue inbox;
    private final AtomicLong ticks = new AtomicLong();

    Region(String id, Instance instance, RegionExecutor executor, RegionQueue inbox) {
        this.id = id;
        this.instance = instance;
        this.executor = executor;
        this.inbox = inbox;
    }

    public String id() {
        return id;
    }

    public Instance instance() {
        return instance;
    }

    public RegionExecutor executor() {
        return executor;
    }

    /** This region's inbox — where other regions post messages (M3+). */
    public RegionQueue inbox() {
        return inbox;
    }

    /** How many tick boundaries this region has run. */
    public long tickCount() {
        return ticks.get();
    }

    /**
     * Whether this region owns {@code point}. In M1 (region=world) a region
     * owns everything in its instance, so this is always true; M2 replaces it
     * with an owns-this-chunk test derived from the region grid.
     */
    public boolean owns(Point point) {
        return true;
    }

    /**
     * Run one tick boundary for this region: apply inbound messages, then tick
     * owned state. Called by {@link Regions#tickBarrier()} through
     * {@link #executor}. In M1 the message layer has no producers and gameplay
     * ticks elsewhere, so this only drains an (always-empty) inbox and advances
     * the tick counter — provably zero behavior change (MULTICORE.md §5.2).
     */
    void runTick() {
        executor.execute(() -> {
            // 1. Apply cross-region messages at the boundary, before own ticking
            //    (MULTICORE.md §2.0). No producers until M3, so this is a no-op.
            inbox.drain(Region::applyMessage);
            // 2. Tick owned entities/blocks/scheduled updates. Populated in M2;
            //    empty in M1, where gameplay still runs on the existing
            //    subsystem scheduler tasks.
            ticks.incrementAndGet();
        });
    }

    /**
     * Apply a single inbound message to this region's state. M3 dispatches on
     * the concrete message type (EntityHandoff, ItemTransfer, …). Unreachable
     * in M1 — nothing enqueues — so it fails loudly if the message layer is
     * exercised before M3 wires real handlers.
     */
    private static void applyMessage(RegionMessage message) {
        throw new IllegalStateException(
                "region message layer has no handlers before M3: " + message);
    }
}
