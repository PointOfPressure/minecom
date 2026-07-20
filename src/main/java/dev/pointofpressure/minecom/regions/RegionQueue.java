package dev.pointofpressure.minecom.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * A region's inbox: the message-queue interface of the P2 multi-core design
 * (docs/MULTICORE.md §2). Other regions {@link #enqueue} messages here from
 * their own threads; the owning region {@link #drain}s and applies them at the
 * start of its next tick boundary, in the source order they arrived.
 *
 * <p>The interface exists as of M1; nothing enqueues to it yet — gameplay
 * subsystems start producing messages in M3 (MULTICORE.md §6). Use
 * {@link #concurrent()} for the standard multi-producer / single-consumer
 * implementation.
 *
 * <p>Threading contract: {@link #enqueue} is safe from any region thread;
 * {@link #drain} runs only on the owning region's tick thread. This matches
 * CONVENTIONS §6's "share via concurrent structures, mutate on the tick
 * thread" discipline — enqueue is the shared write, drain is the tick-thread
 * mutate.
 */
public interface RegionQueue {

    /** Post a message for the owning region. Safe to call from any thread. */
    void enqueue(RegionMessage message);

    /**
     * Remove and hand every currently-queued message to {@code apply}, in
     * arrival order, on the owning region's tick thread. Messages enqueued
     * during a drain are held for the next drain (MULTICORE.md §2.7 — a
     * message never applies in the same boundary it was produced in).
     *
     * @return the number of messages applied
     */
    int drain(Consumer<RegionMessage> apply);

    /** Number of messages currently queued. */
    int size();

    /** Whether the queue holds no messages. */
    boolean isEmpty();

    /** The standard multi-producer / single-consumer inbox. */
    static RegionQueue concurrent() {
        return new Concurrent();
    }

    /**
     * Backed by a {@link ConcurrentLinkedQueue}: lock-free multi-producer
     * enqueue, single-consumer drain on the tick thread. Draining snapshots the
     * messages present at drain-start so a concurrent enqueue lands in the next
     * boundary, not this one.
     */
    final class Concurrent implements RegionQueue {
        private final ConcurrentLinkedQueue<RegionMessage> queue = new ConcurrentLinkedQueue<>();

        private Concurrent() {}

        @Override
        public void enqueue(RegionMessage message) {
            queue.add(message);
        }

        @Override
        public int drain(Consumer<RegionMessage> apply) {
            // Bound the drain to the messages present at drain-start, so any
            // message enqueued mid-drain defers to the next boundary
            // (MULTICORE.md §2.7 — a message never applies in the boundary it
            // was produced in).
            int bound = queue.size();
            List<RegionMessage> batch = new ArrayList<>(bound);
            for (int i = 0; i < bound; i++) {
                RegionMessage m = queue.poll();
                if (m == null) break;
                batch.add(m);
            }
            for (RegionMessage m : batch) apply.accept(m);
            return batch.size();
        }

        @Override
        public int size() {
            return queue.size();
        }

        @Override
        public boolean isEmpty() {
            return queue.isEmpty();
        }
    }
}
