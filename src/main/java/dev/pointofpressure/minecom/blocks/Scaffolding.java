package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * ScaffoldingBlock.getDistance: DISTANCE (0-7) is 0 directly on a sturdy block, otherwise
 * inherited from the scaffolding below (or min(horizontal neighbor distance)+1 if none is
 * below). Reaching distance 7 destroys the block — but only after a 1-tick grace: if it was
 * ALREADY at 7 on the previous recompute, it falls as a real gravity entity
 * (BlockRules.startFalling); if it just became unsupported this tick, it's destroyed and
 * drops directly with no falling animation. A distance change cascades to every neighbor
 * that might depend on it (below/horizontal/above), mirroring real vanilla's per-block
 * scheduled-tick propagation with an explicit BFS instead of relying on a tick-delay chain.
 */
public final class Scaffolding {
    private Scaffolding() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (!e.getBlock().key().value().equals("scaffolding")) {
                // deferred: the placed block isn't actually written into the world until after
                // this listener returns (this project's established PlayerBlockPlaceEvent/
                // PlayerBlockBreakEvent ordering), so a neighboring scaffolding's "is there
                // now a sturdy block below me" check needs to run after that write lands
                deferCascadeNeighbors(e.getInstance(), e.getBlockPosition());
                return;
            }
            // ScaffoldingBlock.getStateForPlacement: read/modify via e.getBlock()/e.setBlock()
            // rather than instance.getBlock() for THIS position specifically, since it isn't
            // in the world yet either — but neighbor reads (below/horizontal) are unaffected,
            // so its own initial distance/bottom can be computed synchronously right here.
            Instance instance = e.getInstance();
            Point pos = e.getBlockPosition();
            int distance = getDistance(instance, pos);
            boolean bottom = distance > 0 && !instance.getBlock(pos.sub(0, 1, 0)).key().value().equals("scaffolding");
            e.setBlock(e.getBlock().withProperty("distance", String.valueOf(distance))
                    .withProperty("bottom", String.valueOf(bottom)));
            deferCascadeNeighbors(instance, pos);
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> deferCascadeNeighbors(e.getInstance(), e.getBlockPosition()));
    }

    /**
     * Real vanilla drives this off its own level.scheduleTick(pos, this, 1) — a genuine
     * 1-tick delay, not just an ordering workaround — so deferring here matches the real
     * mechanic as well as sidestepping the place/break-not-yet-applied ordering issue.
     */
    private static void deferCascadeNeighbors(Instance instance, Point pos) {
        instance.scheduler().buildTask(() -> cascadeNeighbors(instance, pos))
                .delay(net.minestom.server.timer.TaskSchedule.tick(1)).schedule();
    }

    private static void cascadeNeighbors(Instance instance, Point pos) {
        for (Vec d : NEIGHBORS) recomputeCascade(instance, pos.add(d));
    }

    private static final Vec[] NEIGHBORS = {
            new Vec(0, -1, 0), new Vec(0, 1, 0),
            new Vec(1, 0, 0), new Vec(-1, 0, 0), new Vec(0, 0, 1), new Vec(0, 0, -1),
    };

    /** BFS recompute: apply this position, then enqueue neighbors only if its state actually changed. */
    /**
     * Worklist algorithm, not a single-pass BFS: an interdependent chain (e.g. A supports B
     * supports C) can need a position revisited after a LATER neighbor's change, so a position
     * is only barred from re-queueing while it's still pending, not permanently once processed
     * — the same fixed-point-relaxation shape as the existing redstone_wire propagation.
     */
    private static void recomputeCascade(Instance instance, Point start) {
        Set<Long> queued = new HashSet<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        queue.add(start);
        queued.add(pack(start));
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 4096) {
            Point pos = queue.poll();
            queued.remove(pack(pos));
            if (!instance.getBlock(pos).key().value().equals("scaffolding")) continue;
            if (recomputeOne(instance, pos)) {
                for (Vec d : NEIGHBORS) {
                    Point np = pos.add(d);
                    if (queued.add(pack(np))) queue.add(np);
                }
            }
        }
    }

    /** Returns true if this position's DISTANCE/BOTTOM actually changed (so neighbors need rechecking too). */
    private static boolean recomputeOne(Instance instance, Point pos) {
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("scaffolding")) return false;

        int oldDistance = Integer.parseInt(block.getProperty("distance"));
        int distance = getDistance(instance, pos);

        if (distance >= 7) {
            if (oldDistance >= 7) {
                instance.setBlock(pos, Block.AIR);
                BlockRules.startFalling(instance, pos, block);
            } else {
                instance.setBlock(pos, Block.AIR);
                BlockRules.dropAt(instance, pos, net.minestom.server.item.ItemStack.of(Material.SCAFFOLDING));
            }
            return true;
        }

        boolean bottom = distance > 0 && !instance.getBlock(pos.sub(0, 1, 0)).key().value().equals("scaffolding");
        boolean changed = distance != oldDistance || bottom != "true".equals(block.getProperty("bottom"));
        if (changed) {
            instance.setBlock(pos, block.withProperty("distance", String.valueOf(distance))
                    .withProperty("bottom", String.valueOf(bottom)));
        }
        return changed;
    }

    /** ScaffoldingBlock.getDistance, ported directly. */
    private static int getDistance(Instance instance, Point pos) {
        Point below = pos.sub(0, 1, 0);
        Block belowBlock = instance.getBlock(below);
        int distance = 7;
        if (belowBlock.key().value().equals("scaffolding")) {
            distance = Integer.parseInt(belowBlock.getProperty("distance"));
        } else if (belowBlock.isSolid()) {
            return 0;
        }

        for (int[] h : HORIZ) {
            Point np = pos.add(h[0], 0, h[1]);
            Block n = instance.getBlock(np);
            if (n.key().value().equals("scaffolding")) {
                distance = Math.min(distance, Integer.parseInt(n.getProperty("distance")) + 1);
                if (distance == 1) break;
            }
        }
        return distance;
    }

    private static final int[][] HORIZ = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static long pack(Point p) {
        return ((long) p.blockX() << 42) ^ ((long) p.blockY() << 21) ^ (long) (p.blockZ() & 0x1FFFFF);
    }
}
