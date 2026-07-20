package dev.pointofpressure.minecom;

import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.TaskSchedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Single ordered per-tick dispatch for every server-wide gameplay system.
 *
 * <p>Before P1 each subsystem scheduled its own independent
 * {@code MinecraftServer.getSchedulerManager().buildTask(..).repeat(tick(1))}
 * task (~23 of them). Minestom runs those in an unspecified order, so the
 * relative sequencing between, say, redstone and hoppers was an accident of
 * scheduler internals. This class collapses them into ONE repeating scheduler
 * task that runs each registered system in an explicit, documented order and
 * times each with {@link System#nanoTime()} for instant per-system
 * observability (see {@link #timingsReport()} / the {@code /tickprofile}
 * command). The task still runs at the same point in the server tick that the
 * individual tasks did (it is scheduled on the same global scheduler manager),
 * so nothing changes relative to Minestom's own instance/entity ticking — only
 * the order <em>among</em> these systems is now fixed and deterministic.
 *
 * <p><b>Phase order mirrors vanilla's {@code ServerLevel.tick} /
 * {@code MinecraftServer.tickServer} sequence</b> (decompiled 26.2) so the
 * consolidation also removes a class of update-order divergence. Systems are
 * bucketed into these phases and, within a phase, run in registration
 * (Bootstrap wiring) order via a stable sort:
 *
 * <pre>
 *   vanilla anchor                     phase constant        minecom systems
 *   ---------------------------------  --------------------  --------------------------------
 *   worldBorder.tick()                 WORLD_BORDER          (none yet)
 *   advanceWeatherCycle / tickTime     WEATHER_TIME          (none yet — WeatherCycle is own cadence)
 *   tickBlocksAndFluids scheduled      SCHEDULED_TICKS       (none yet — Fluids/Farming own cadence)
 *   randomTick                         RANDOM_TICKS          RandomTicks, FireSpread
 *   runBlockEvents                     BLOCK_EVENTS          (none yet)
 *   neighbour-update propagation       REDSTONE              Redstone
 *   guardEntityTick (entity.tick)      ENTITIES              Leashing, Minecarts, Boats, Breath,
 *                                                            VillagerConversion, Archaeology(brush)
 *   blockEntityTickers                 BLOCK_ENTITIES        Hoppers, Furnaces, Beacons, Conduits,
 *                                                            Beehives, Campfires, Jukebox, Brewing,
 *                                                            CreakingHearts, TrialChambers
 *   NaturalSpawner (in tickChunk)      MOB_SPAWNING          natural spawner, PhantomSpawning,
 *                                                            ClassicSpawners
 *   post-tick bookkeeping              POST                  Archaeology(resets)
 * </pre>
 *
 * <p>Each phase body is wrapped so a throw in one system is logged and the
 * remaining systems still tick this tick — matching the isolation the old
 * N-independent-tasks arrangement had (one failing task never froze the
 * others). This is a deliberate, documented choice (AUDIT.md), not a silent
 * catch: the exception is logged at ERROR.
 *
 * <p>Per-entity ({@code mob.scheduler()}) and per-instance
 * ({@code instance.scheduler()}) tick tasks are intentionally NOT folded in —
 * they live and die with their entity/instance and tick at a different point
 * in the server tick; consolidating them would change timing semantics.
 */
public final class TickPipeline {
    private TickPipeline() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(TickPipeline.class);

    // Vanilla-anchored phase order (see class javadoc). Spaced by 100 so future
    // systems can slot between without renumbering.
    public static final int WORLD_BORDER = 100;
    public static final int WEATHER_TIME = 200;
    public static final int SCHEDULED_TICKS = 300;
    public static final int RANDOM_TICKS = 400;
    public static final int BLOCK_EVENTS = 500;
    public static final int REDSTONE = 600;
    public static final int ENTITIES = 700;
    public static final int BLOCK_ENTITIES = 800;
    public static final int MOB_SPAWNING = 900;
    public static final int POST = 1000;

    private record System(int phase, int seq, String name, Runnable action) {}

    // thread: mutated only under REGISTERED's monitor; the sorted snapshot is
    // published to `active` (volatile) so the tick thread reads it lock-free.
    // Registration is usually all-before-start (Bootstrap wiring), but some
    // subsystems register lazily after start() — e.g. VillagerConversion, which
    // flat-world playtests start at scenario time (see PlayTest.scenario
    // VillagerConversion) — so post-start registration is supported: the system
    // is inserted in phase order and picked up on the next tick.
    private static final List<System> REGISTERED = new ArrayList<>();
    private static volatile System[] active = new System[0];

    // thread: written on the tick thread each tick, read on any thread by the
    // /tickprofile command and the bench harness — hence concurrent.
    private static final ConcurrentMap<String, Long> LAST_NANOS = new ConcurrentHashMap<>();
    private static volatile long lastTotalNanos = 0L;

    private static int registerSeq = 0;
    private static volatile boolean started = false;

    /**
     * Register a system to run once per tick in {@code phase} (one of the phase
     * constants). Within a phase, systems run in registration order. Safe to
     * call before or after {@link #start()}; a post-start registration takes
     * effect on the next tick.
     */
    public static void register(int phase, String name, Runnable action) {
        synchronized (REGISTERED) {
            REGISTERED.add(new System(phase, registerSeq++, name, action));
            var arr = REGISTERED.toArray(new System[0]);
            Arrays.sort(arr, (a, b) -> a.phase != b.phase ? Integer.compare(a.phase, b.phase)
                    : Integer.compare(a.seq, b.seq));
            active = arr;
        }
    }

    /** Schedule the single per-tick task that dispatches all registered systems. */
    public static void start() {
        if (started) return;
        started = true;
        MinecraftServer.getSchedulerManager().buildTask(TickPipeline::tickAll)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    private static void tickAll() {
        System[] snapshot = active;
        long total = 0L;
        for (System s : snapshot) {
            long t0 = java.lang.System.nanoTime();
            try {
                s.action.run();
            } catch (Throwable t) {
                LOGGER.error("Tick system '{}' threw; other systems continue", s.name(), t);
            }
            long dt = java.lang.System.nanoTime() - t0;
            LAST_NANOS.put(s.name(), dt);
            total += dt;
        }
        lastTotalNanos = total;
    }

    /** Last-tick nanotime for a registered system, or -1 if unknown. */
    public static long lastNanos(String name) {
        Long v = LAST_NANOS.get(name);
        return v == null ? -1L : v;
    }

    /** Total nanotime spent in all pipeline systems on the last tick. */
    public static long lastTotalNanos() {
        return lastTotalNanos;
    }

    /** Ordered, human-readable per-system timing table for the last tick. */
    public static String timingsReport() {
        var sb = new StringBuilder("TickPipeline last-tick timings (systems in run order):\n");
        for (System s : active) {
            long ns = lastNanos(s.name());
            sb.append(String.format("  %-24s %8.3f ms%n", s.name, ns / 1_000_000.0));
        }
        sb.append(String.format("  %-24s %8.3f ms%n", "TOTAL", lastTotalNanos / 1_000_000.0));
        return sb.toString();
    }
}
