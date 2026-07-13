package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bubble columns, ported from decompiled BubbleColumnBlock +
 * Entity.onInsideBubbleColumn/onAboveBubbleColumn + AbstractBoat (26.1.2):
 * soul sand under source water grows a push-up column, magma a drag-down one
 * (DRAG property), re-derived on 5gt scheduled checks (CHECK_PERIOD) that
 * convert the whole contiguous source-water run at once and revert cells to
 * water when their driver goes away. Entities intersecting a column get the
 * vanilla per-tick velocity ramps (inside: vy±0.03/0.06 toward -0.3/0.7;
 * open-surface cell: ±0.03/0.1 toward -0.9/1.8, Minestom velocity is
 * blocks/second so ×20); boats instead arm AbstractBoat's 60gt timer — a
 * drag column then ejects riders and lets the hull sink, a push column
 * launches it (2.7 with a player aboard, 0.6 empty). Simplifications
 * (AUDIT.md): column cells aren't water sources for the flow engine
 * (vanilla's getFluidState is), columns are event-driven and session-scoped
 * (worldgen soul sand under oceans doesn't self-start — Redstone-registry
 * pattern), the per-entity clamp applies once per tick from the highest
 * intersected cell rather than per swept block, and the whirlpool/upward
 * particles + ambient sounds are skipped (client visuals).
 */
public final class BubbleColumns {
    private BubbleColumns() {}

    private static final int CHECK_PERIOD = 5; // BubbleColumnBlock.CHECK_PERIOD
    private static final int BOAT_BUBBLE_TIME = 60; // AbstractBoat.BUBBLE_TIME

    private static Instance instance;
    // thread: all three touched only from the instance tick task + event thread via schedule()
    private static final Map<Long, Integer> DUE = new ConcurrentHashMap<>();
    private static final Set<Long> COLUMNS = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Integer> BOAT_TIMER = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        events.addListener(PlayerBlockPlaceEvent.class, e -> notifyChanged(e.getBlockPosition()));
        events.addListener(PlayerBlockBreakEvent.class, e -> notifyChanged(e.getBlockPosition()));
        overworld.scheduler().buildTask(BubbleColumns::tick).repeat(TaskSchedule.tick(1)).schedule();
    }

    /**
     * A block changed here — schedule the vanilla updateShape rechecks (the cell
     * above a new driver, the cell itself, and the cell above for decay
     * cascades). Public for tests and direct-set callers (world.setBlock skips
     * the place event).
     */
    public static void notifyChanged(Point pos) {
        schedule(pos.blockX(), pos.blockY(), pos.blockZ());
        schedule(pos.blockX(), pos.blockY() + 1, pos.blockZ());
    }

    private static void schedule(int x, int y, int z) {
        DUE.putIfAbsent(pack(x, y, z), CHECK_PERIOD);
    }

    private static void tick() {
        if (instance == null) return;
        // scheduled column updates
        var due = DUE.entrySet().iterator();
        Map<Long, Boolean> run = new HashMap<>();
        while (due.hasNext()) {
            var e = due.next();
            if (e.getValue() <= 1) {
                run.put(e.getKey(), Boolean.TRUE);
                due.remove();
            } else {
                e.setValue(e.getValue() - 1);
            }
        }
        for (long key : run.keySet()) {
            updateColumn(unpackX(key), unpackY(key), unpackZ(key));
        }
        applyEntityEffects();
    }

    // ------------------------------------------------------------------ column maintenance

    /** BubbleColumnBlock.updateColumn: derive the cell from its below-neighbor, then walk up. */
    private static void updateColumn(int x, int y, int z) {
        Block occupy = instance.getBlock(x, y, z);
        if (!canOccupy(occupy)) {
            COLUMNS.remove(pack(x, y, z));
            return;
        }
        Block below = instance.getBlock(x, y - 1, z);
        Block columnState = columnState(below, occupy);
        int cy = y;
        while (true) {
            Block current = instance.getBlock(x, cy, z);
            if (!canOccupy(current)) break;
            if (!current.compare(columnState)
                    || !propsEqual(current, columnState)) {
                instance.setBlock(x, cy, z, columnState);
                // a reverted cell is live water again — let the flow engine reconsider it
                if (!isColumn(columnState)) Fluids.notifyAround(new Vec(x, cy, z));
            } else if (!isColumn(columnState)) {
                break; // reverting: stop once we hit plain water that already matches
            }
            long key = pack(x, cy, z);
            if (isColumn(columnState)) COLUMNS.add(key); else COLUMNS.remove(key);
            cy++;
        }
    }

    /** BubbleColumnBlock.getColumnState, keyed off the block below the cell. */
    private static Block columnState(Block below, Block occupy) {
        if (isColumn(below)) return below;
        if (below.compare(Block.SOUL_SAND)) {
            return Block.BUBBLE_COLUMN.withProperty("drag", "false");
        }
        if (below.key().value().equals("magma_block")) {
            return Block.BUBBLE_COLUMN.withProperty("drag", "true");
        }
        return Block.WATER; // no driver: the whole run above reverts to water
    }

    /** BubbleColumnBlock.canOccupy: an existing column cell or source water. */
    private static boolean canOccupy(Block block) {
        if (isColumn(block)) return true;
        return block.compare(Block.WATER) && "0".equals(block.getProperty("level"));
    }

    private static boolean isColumn(Block block) {
        return block.key().value().equals("bubble_column");
    }

    private static boolean propsEqual(Block a, Block b) {
        return !isColumn(a) || !isColumn(b)
                || String.valueOf(a.getProperty("drag")).equals(String.valueOf(b.getProperty("drag")));
    }

    // ------------------------------------------------------------------ entity effects

    /**
     * Entity.onInsideBubbleColumn / onAboveBubbleColumn velocity ramps, applied
     * once per entity per tick from the highest column cell its box intersects;
     * boats divert into the AbstractBoat 60gt timer instead.
     */
    private static void applyEntityEffects() {
        if (COLUMNS.isEmpty()) {
            BOAT_TIMER.clear();
            return;
        }
        // entityId -> highest intersected cell (packed); recomputed every tick
        Map<Integer, long[]> hits = new HashMap<>();
        for (long key : COLUMNS) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            Block cell = instance.getBlock(x, y, z);
            if (!isColumn(cell)) {
                COLUMNS.remove(key);
                continue;
            }
            boolean drag = "true".equals(cell.getProperty("drag"));
            for (Entity en : instance.getNearbyEntities(new Vec(x + 0.5, y + 0.5, z + 0.5), 2.5)) {
                if (en instanceof Player p && p.getGameMode() == net.minestom.server.entity.GameMode.SPECTATOR) continue;
                Pos ep = en.getPosition();
                double half = en.getBoundingBox().width() / 2;
                if (ep.x() + half <= x || ep.x() - half >= x + 1) continue;
                if (ep.z() + half <= z || ep.z() - half >= z + 1) continue;
                if (ep.y() + en.getBoundingBox().height() <= y || ep.y() >= y + 1) continue;
                long[] best = hits.get(en.getEntityId());
                if (best == null || unpackY(best[0]) < y) {
                    hits.put(en.getEntityId(), new long[]{key, drag ? 1 : 0});
                }
            }
        }
        Set<Integer> boatsSeen = new java.util.HashSet<>();
        for (var hit : hits.entrySet()) {
            long key = hit.getValue()[0];
            boolean drag = hit.getValue()[1] == 1;
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            Entity en = instance.getEntityById(hit.getKey());
            if (en == null) continue;
            Block above = instance.getBlock(x, y + 1, z);
            boolean open = !above.isSolid() && !above.isLiquid() && !isColumn(above);
            if (Boats.isBoat(en.getEntityType())) {
                boatsSeen.add(en.getEntityId());
                tickBoat(en, drag);
                continue;
            }
            Vec v = en.getVelocity();
            double vy = v.y() / 20.0; // Minestom velocity is blocks/second
            if (open) {
                vy = drag ? Math.max(-0.9, vy - 0.03) : Math.min(1.8, vy + 0.1);
            } else {
                vy = drag ? Math.max(-0.3, vy - 0.03) : Math.min(0.7, vy + 0.06);
            }
            en.setVelocity(new Vec(v.x(), vy * 20.0, v.z()));
        }
        BOAT_TIMER.keySet().retainAll(boatsSeen); // drifting off the column resets the timer
    }

    /** AbstractBoat.onAboveBubbleColumn + tickBubbleColumn: the one-shot 60gt impulse. */
    private static void tickBoat(Entity boat, boolean drag) {
        int left = BOAT_TIMER.merge(boat.getEntityId(), BOAT_BUBBLE_TIME, (old, arm) -> old - 1);
        if (left > 0) return;
        BOAT_TIMER.remove(boat.getEntityId());
        if (drag) {
            java.util.List.copyOf(boat.getPassengers()).forEach(boat::removePassenger);
            Vec v = boat.getVelocity();
            boat.setVelocity(new Vec(v.x(), v.y() - 0.7 * 20.0, v.z()));
        } else {
            boolean playerAboard = boat.getPassengers().stream().anyMatch(p -> p instanceof Player);
            Vec v = boat.getVelocity();
            // the hop teleport keeps the launch observable — Boats' surface easing
            // would otherwise re-zero the vertical velocity on the very next tick
            Pos bp = boat.getPosition();
            boat.teleport(new Pos(bp.x(), bp.y() + 1.2, bp.z(), bp.yaw(), bp.pitch()));
            boat.setVelocity(new Vec(v.x(), (playerAboard ? 2.7 : 0.6) * 20.0, v.z()));
        }
    }

    // ------------------------------------------------------------------ packing

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    private static int unpackX(long key) {
        return (int) (key >> 38);
    }

    private static int unpackZ(long key) {
        return (int) (key << 26 >> 38);
    }

    private static int unpackY(long key) {
        return (int) (key << 52 >> 52);
    }
}
