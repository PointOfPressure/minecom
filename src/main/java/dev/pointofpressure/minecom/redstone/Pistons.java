package dev.pointofpressure.minecom.redstone;

import dev.pointofpressure.minecom.blocks.BlockRules;
import dev.pointofpressure.minecom.blocks.Fluids;
import dev.pointofpressure.minecom.data.LootTables;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pistons with vanilla rules, including full slime/honey block chains. The
 * structure resolver is a faithful port of decompiled
 * net.minecraft.world.level.block.piston.PistonStructureResolver (26.1.2):
 * back-pull of sticky-connected blocks behind a line, recursive perpendicular
 * branching off slime/honey, in-place list reorder when a branch's forward
 * walk collides with an already-claimed cell, honey and slime never sticking
 * to each other, and the 12-block limit counted across the whole structure.
 * Push rules ported from PistonBaseBlock.isPushable: obsidian family
 * immovable, glazed terracotta push-only (pushes, never pulled or dragged),
 * no-collision blocks destroy-on-push, hardness &lt; 0 immovable, block
 * entities immovable, world-height bounds. Movement is applied instantly
 * (no moving-piston block entity / animation — the project's usual
 * client-visual simplification) and quasi-connectivity stays approximated
 * as "power at the block above", re-checked only on block updates.
 */
final class Pistons {
    private Pistons() {}

    private static final int MAX_PUSH_DEPTH = 12;
    // project-wide overworld build limits (same constants as worldgen/vanilla/VJigsaw)
    private static final int MIN_Y = -64, MAX_Y = 319;

    private static final Vec[] DIRECTIONS = {
            new Vec(0, -1, 0), new Vec(0, 1, 0),
            new Vec(0, 0, -1), new Vec(0, 0, 1),
            new Vec(-1, 0, 0), new Vec(1, 0, 0),
    };

    private enum Reaction { NORMAL, DESTROY, BLOCK, PUSH_ONLY }

    static void evaluate(Instance instance, Point pos, Block piston) {
        boolean extended = "true".equals(piston.getProperty("extended"));
        boolean powered = Redstone.activated(pos) || Redstone.activated(pos.add(0, 1, 0)); // QC
        if (powered && !extended) extend(instance, pos, piston);
        else if (!powered && extended) retract(instance, pos, piston);
    }

    /** PushReaction approximation: no data-driven source exists in Minestom's registry. */
    private static Reaction reaction(Block block) {
        String key = block.key().value();
        if (key.endsWith("_glazed_terracotta")) return Reaction.PUSH_ONLY;
        if (key.equals("piston_head") || key.equals("moving_piston")) return Reaction.BLOCK;
        if (block.isLiquid()) return Reaction.DESTROY; // fluids vanish, no drops
        if (!block.isSolid() && !block.isAir()) return Reaction.DESTROY; // plants, torches, rails...
        return Reaction.NORMAL;
    }

    private static boolean isSticky(Block block) {
        String key = block.key().value();
        return key.equals("slime_block") || key.equals("honey_block");
    }

    /** Honey never sticks to slime (both orders); otherwise either being sticky suffices. */
    private static boolean canStick(Block a, Block b) {
        String ka = a.key().value(), kb = b.key().value();
        if (ka.equals("honey_block") && kb.equals("slime_block")) return false;
        if (ka.equals("slime_block") && kb.equals("honey_block")) return false;
        return isSticky(a) || isSticky(b);
    }

    /**
     * Port of PistonBaseBlock.isPushable. {@code direction} is the movement
     * direction being tested, {@code connection} the direction this line
     * connects from (push-only blocks move only when pushed head-on).
     */
    private static boolean isPushable(Instance instance, Point pos, Block state,
                                      Vec direction, boolean allowDestroyable, Vec connection) {
        if (pos.blockY() < MIN_Y || pos.blockY() > MAX_Y) return false;
        if (state.isAir()) return true;
        String key = state.key().value();
        if (key.equals("obsidian") || key.equals("crying_obsidian")
                || key.equals("respawn_anchor") || key.equals("reinforced_deepslate")) return false;
        if (direction.y() < 0 && pos.blockY() == MIN_Y) return false;
        if (direction.y() > 0 && pos.blockY() == MAX_Y) return false;
        if (key.equals("piston") || key.equals("sticky_piston")) {
            if ("true".equals(state.getProperty("extended"))) return false;
        } else {
            if (state.registry().hardness() < 0) return false; // bedrock etc.
            switch (reaction(state)) {
                case BLOCK: return false;
                case DESTROY: return allowDestroyable;
                case PUSH_ONLY: return sameVec(direction, connection);
                case NORMAL: break;
            }
        }
        return !state.registry().isBlockEntity();
    }

    private static boolean sameVec(Point a, Point b) {
        return a.blockX() == b.blockX() && a.blockY() == b.blockY() && a.blockZ() == b.blockZ();
    }

    /** Integer-exact offset so list contains/indexOf comparisons are reliable. */
    private static Vec rel(Point p, Vec dir, int steps) {
        return new Vec(p.blockX() + (int) dir.x() * steps,
                p.blockY() + (int) dir.y() * steps,
                p.blockZ() + (int) dir.z() * steps);
    }

    private static void extend(Instance instance, Point pos, Block piston) {
        Vec dir = Redstone.facingVec(piston.getProperty("facing"));
        Resolver resolver = new Resolver(instance, pos, dir, true);
        if (!resolver.resolve()) return;
        apply(instance, pos, dir, resolver, true);

        Point headPos = rel(pos, dir, 1);
        boolean sticky = piston.key().value().equals("sticky_piston");
        instance.setBlock(headPos, Block.PISTON_HEAD
                .withProperty("facing", piston.getProperty("facing"))
                .withProperty("type", sticky ? "sticky" : "normal"));
        instance.setBlock(pos, piston.withProperty("extended", "true"));
        Redstone.neighborsChanged(pos);
        Redstone.neighborsChanged(headPos);
        BlockRules.scheduleFallCheck(instance, pos.add(0, 1, 0));
    }

    private static void retract(Instance instance, Point pos, Block piston) {
        Vec dir = Redstone.facingVec(piston.getProperty("facing"));
        Point headPos = rel(pos, dir, 1);
        if (instance.getBlock(headPos).key().value().equals("piston_head")) {
            instance.setBlock(headPos, Block.AIR);
        }
        boolean sticky = piston.key().value().equals("sticky_piston");
        if (sticky) {
            Point pulled = rel(pos, dir, 2);
            Block target = instance.getBlock(pulled);
            String tk = target.key().value();
            boolean pistonBase = tk.equals("piston") || tk.equals("sticky_piston");
            // vanilla triggerEvent gate: only NORMAL-pushable targets (or piston bases) get pulled
            if (!target.isAir()
                    && isPushable(instance, pulled, target, dir.mul(-1), false, dir)
                    && (reaction(target) == Reaction.NORMAL || pistonBase)) {
                Resolver resolver = new Resolver(instance, pos, dir, false);
                if (resolver.resolve()) apply(instance, pos, dir, resolver, false);
            }
        }
        instance.setBlock(pos, piston.withProperty("extended", "false"));
        Redstone.neighborsChanged(pos);
        Redstone.neighborsChanged(headPos);
        Fluids.notifyAround(headPos);
    }

    /** Destroys toDestroy, moves toPush one step, airs vacated cells, fires updates. */
    private static void apply(Instance instance, Point pistonPos, Vec facing, Resolver r, boolean extending) {
        Vec pushDir = r.pushDirection;
        Map<Point, Block> snapshot = new HashMap<>();
        for (Point p : r.toPush) snapshot.put(p, instance.getBlock(p));

        for (int i = r.toDestroy.size() - 1; i >= 0; i--) {
            Point p = r.toDestroy.get(i);
            Block b = instance.getBlock(p);
            if (!b.isLiquid()) {
                for (ItemStack drop : LootTables.blockDrops(b, ItemStack.AIR)) {
                    BlockRules.dropAt(instance, p, drop);
                }
            }
            instance.setBlock(p, Block.AIR);
        }

        Set<Point> vacated = new HashSet<>(r.toPush);
        Set<Point> destinations = new HashSet<>();
        for (int i = r.toPush.size() - 1; i >= 0; i--) {
            Point from = r.toPush.get(i);
            Point to = rel(from, pushDir, 1);
            instance.setBlock(to, snapshot.get(from));
            destinations.add(to);
            vacated.remove(to);
        }
        if (extending) vacated.remove(rel(pistonPos, facing, 1)); // head goes here
        for (Point p : vacated) instance.setBlock(p, Block.AIR);

        for (Point p : r.toDestroy) {
            Redstone.neighborsChanged(p);
            Fluids.notifyAround(p);
        }
        for (Point from : r.toPush) {
            Point to = rel(from, pushDir, 1);
            Redstone.neighborsChanged(from);
            Redstone.neighborsChanged(to);
            Fluids.notifyAround(from);
            Fluids.notifyAround(to);
            BlockRules.scheduleFallCheck(instance, to.add(0, 1, 0));
        }
        for (Point p : vacated) BlockRules.scheduleFallCheck(instance, p.add(0, 1, 0));

        // the head cell sweeps entities too, even when nothing was pushed
        if (extending) destinations.add(rel(pistonPos, facing, 1));
        pushEntities(instance, destinations, pushDir);
    }

    private static void pushEntities(Instance instance, Set<Point> cells, Vec dir) {
        for (var entity : instance.getEntities()) {
            var ep = entity.getPosition();
            for (Point cell : cells) {
                if (ep.blockX() == cell.blockX() && ep.blockZ() == cell.blockZ()
                        && Math.abs(ep.y() - cell.blockY()) < 1.2) {
                    entity.teleport(ep.add(dir.x(), Math.max(0, dir.y()), dir.z()));
                    break;
                }
            }
        }
    }

    /**
     * Line-by-line port of PistonStructureResolver (decompiled 26.1.2).
     * Per-run state object, same shape as vanilla's.
     */
    private static final class Resolver {
        private final Instance instance;
        private final Point pistonPos;
        private final boolean extending;
        private final Point startPos;
        private final Vec pushDirection;
        private final Vec pistonDirection;
        private final List<Point> toPush = new ArrayList<>();
        private final List<Point> toDestroy = new ArrayList<>();

        Resolver(Instance instance, Point pistonPos, Vec facing, boolean extending) {
            this.instance = instance;
            this.pistonPos = new Vec(pistonPos.blockX(), pistonPos.blockY(), pistonPos.blockZ());
            this.pistonDirection = facing;
            this.extending = extending;
            if (extending) {
                this.pushDirection = facing;
                this.startPos = rel(pistonPos, facing, 1);
            } else {
                this.pushDirection = facing.mul(-1);
                this.startPos = rel(pistonPos, facing, 2);
            }
        }

        boolean resolve() {
            toPush.clear();
            toDestroy.clear();
            Block state = instance.getBlock(startPos);
            if (!isPushable(instance, startPos, state, pushDirection, false, pistonDirection)) {
                if (extending && reaction(state) == Reaction.DESTROY) {
                    toDestroy.add(startPos);
                    return true;
                }
                return false;
            }
            if (!addBlockLine(startPos, pushDirection)) return false;
            for (int i = 0; i < toPush.size(); i++) {
                Point pos = toPush.get(i);
                if (isSticky(instance.getBlock(pos)) && !addBranchingBlocks(pos)) return false;
            }
            return true;
        }

        private boolean addBlockLine(Point start, Vec direction) {
            Block state = instance.getBlock(start);
            if (state.isAir()) return true;
            if (!isPushable(instance, start, state, pushDirection, false, direction)) return true;
            if (sameVec(start, pistonPos)) return true;
            if (toPush.contains(rel(start, pushDirection, 0))) return true;

            int blockCount = 1;
            if (blockCount + toPush.size() > MAX_PUSH_DEPTH) return false;
            // walk backward through sticky-connected blocks behind the line start
            while (isSticky(state)) {
                Point pos = rel(start, pushDirection, -blockCount);
                Block previous = state;
                state = instance.getBlock(pos);
                if (state.isAir()
                        || !canStick(previous, state)
                        || !isPushable(instance, pos, state, pushDirection, false, pushDirection.mul(-1))
                        || sameVec(pos, pistonPos)) {
                    break;
                }
                if (++blockCount + toPush.size() > MAX_PUSH_DEPTH) return false;
            }

            int blocksAdded = 0;
            for (int i = blockCount - 1; i >= 0; i--) {
                toPush.add(rel(start, pushDirection, -i));
                blocksAdded++;
            }

            int i = 1;
            while (true) {
                Point next = rel(start, pushDirection, i);
                int collisionIndex = toPush.indexOf(next);
                if (collisionIndex > -1) {
                    reorderListAtCollision(blocksAdded, collisionIndex);
                    for (int j = 0; j <= collisionIndex + blocksAdded; j++) {
                        Point p = toPush.get(j);
                        if (isSticky(instance.getBlock(p)) && !addBranchingBlocks(p)) return false;
                    }
                    return true;
                }
                Block nextState = instance.getBlock(next);
                if (nextState.isAir()) return true;
                if (!isPushable(instance, next, nextState, pushDirection, true, pushDirection)
                        || sameVec(next, pistonPos)) {
                    return false;
                }
                if (reaction(nextState) == Reaction.DESTROY) {
                    toDestroy.add(next);
                    return true;
                }
                if (toPush.size() >= MAX_PUSH_DEPTH) return false;
                toPush.add(next);
                blocksAdded++;
                i++;
            }
        }

        private void reorderListAtCollision(int blocksAdded, int collisionIndex) {
            List<Point> head = new ArrayList<>(toPush.subList(0, collisionIndex));
            List<Point> lastLineAdded = new ArrayList<>(toPush.subList(toPush.size() - blocksAdded, toPush.size()));
            List<Point> collisionToLine = new ArrayList<>(toPush.subList(collisionIndex, toPush.size() - blocksAdded));
            toPush.clear();
            toPush.addAll(head);
            toPush.addAll(lastLineAdded);
            toPush.addAll(collisionToLine);
        }

        private boolean addBranchingBlocks(Point from) {
            Block fromState = instance.getBlock(from);
            for (Vec direction : DIRECTIONS) {
                // vanilla: skip directions on the push axis
                if (direction.x() * pushDirection.x() + direction.y() * pushDirection.y()
                        + direction.z() * pushDirection.z() != 0) continue;
                Point neighbourPos = rel(from, direction, 1);
                Block neighbourState = instance.getBlock(neighbourPos);
                if (canStick(neighbourState, fromState) && !addBlockLine(neighbourPos, direction)) {
                    return false;
                }
            }
            return true;
        }
    }
}
