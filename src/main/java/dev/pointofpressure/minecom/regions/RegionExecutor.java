package dev.pointofpressure.minecom.regions;

/**
 * The single-threaded executor a region ticks on — the ownership-thread
 * abstraction of the P2 multi-core design (docs/MULTICORE.md §1.1). Every
 * region owns exactly one executor, and all of that region's state is mutated
 * only from work run through it: this is the "single-threaded island" the
 * whole design protects.
 *
 * <p>M1 ships only {@link #sameThread()}, which runs work inline on the caller
 * (the Minestom tick thread). That is the region=world degenerate case: one
 * region, ticking on the server's own tick thread, with zero threading change
 * versus today (MULTICORE.md §5.2). M2 adds a worker-pool-backed executor
 * behind this same interface — the A/B switch the migration path is built on
 * (introducing that real executor is the CONVENTIONS §6 ExecutorService that
 * HANDOFF.md's P2 entry pre-authorizes).
 */
public interface RegionExecutor {

    /** Run {@code task} on this region's owning thread. */
    void execute(Runnable task);

    /**
     * Whether the calling thread is this executor's owning thread. Region code
     * asserts this on hot paths in debug builds to catch an accidental
     * cross-thread mutation early. Always true for {@link #sameThread()}.
     */
    boolean isOwningThread();

    /** Inline executor: runs work on the caller. The region=world executor. */
    static RegionExecutor sameThread() {
        return new SameThread();
    }

    /**
     * Runs every task synchronously on the calling thread. For region=world
     * (M1) the caller is always the server tick thread, so a region tick runs
     * exactly where and when it runs today.
     */
    final class SameThread implements RegionExecutor {
        private SameThread() {}

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public boolean isOwningThread() {
            return true;
        }
    }
}
