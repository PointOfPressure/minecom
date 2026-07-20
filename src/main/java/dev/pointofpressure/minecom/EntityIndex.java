package dev.pointofpressure.minecom;

import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.Instance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-chunk spatial entity index for "entities near X" queries.
 *
 * <p>Thin minecom-owned facade over Minestom's {@link EntityTracker} — the
 * engine's own per-chunk entity buckets, maintained on every entity
 * spawn/move/remove. Before P1, ~40 gameplay sites answered "which entities are
 * near this point?" with {@code instance.getEntities()}, an O(all-entities)
 * scan of the whole instance. Routing them through this facade turns each into
 * an O(local) chunk lookup while (a) keeping a single seam to swap if Minestom
 * changes (MASTERPLAN §5.3) and (b) leaving the caller's exact predicate
 * untouched.
 *
 * <p><b>Same-result contract.</b> Every method here returns a <em>superset</em>
 * of the entities the replaced full scan iterated: {@link #near} visits every
 * entity in chunks within {@code range} blocks of {@code center}, and
 * {@link #inChunk} visits every entity in the chunk covering {@code pos}. So an
 * entity within {@code range} blocks (or in the same block column) is always
 * present, and applying the caller's identical distance/box/type predicate to
 * the returned candidates yields exactly the set the scan produced. Verified by
 * {@code PlayTest.scenarioEntityIndexParity}.
 *
 * <p>Results carry no duplicates: each entity lives in exactly one chunk
 * bucket, so it is visited at most once.
 */
public final class EntityIndex {
    private EntityIndex() {}

    /**
     * Every entity in chunks within {@code range} blocks (horizontally) of
     * {@code center}. Candidate set — the caller re-applies its exact
     * distance/box test. Chunk radius is {@code ceil(range/16)+1}, one wider
     * than strictly needed so an entity near a chunk edge is never missed.
     */
    public static List<Entity> near(Instance instance, Point center, double range) {
        var out = new ArrayList<Entity>();
        int chunkRange = (int) Math.ceil(range / 16.0) + 1;
        instance.getEntityTracker().nearbyEntitiesByChunkRange(
                center, chunkRange, EntityTracker.Target.ENTITIES, out::add);
        return out;
    }

    /**
     * Every entity in the single chunk that covers {@code pos}. For exact-cell
     * / single-column queries (an entity occupying a given block is always in
     * that block's chunk).
     */
    public static List<Entity> inChunk(Instance instance, Point pos) {
        return new ArrayList<>(instance.getEntityTracker()
                .chunkEntities(pos, EntityTracker.Target.ENTITIES));
    }

    /**
     * Deduplicated union of the chunks covering each of {@code points} — for
     * queries testing membership against a set of nearby cells (e.g. piston
     * push destinations).
     */
    public static Set<Entity> inChunksOf(Instance instance, Collection<? extends Point> points) {
        var out = new HashSet<Entity>();
        var seenChunks = new HashSet<Long>();
        var tracker = instance.getEntityTracker();
        for (Point p : points) {
            long key = ((long) (p.blockX() >> 4) << 32) | (p.blockZ() >> 4 & 0xffffffffL);
            if (seenChunks.add(key)) {
                out.addAll(tracker.chunkEntities(p, EntityTracker.Target.ENTITIES));
            }
        }
        return out;
    }
}
