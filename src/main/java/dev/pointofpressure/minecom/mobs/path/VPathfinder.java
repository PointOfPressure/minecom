package dev.pointofpressure.minecom.mobs.path;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vanilla ground pathfinding: exact ports of PathFinder (A* with the 1.5x
 * heuristic fudge), BinaryHeap, Node math and WalkNodeEvaluator (path types,
 * maluses, jump/fall handling, diagonal rules, danger neighbors). Routes match
 * vanilla; steering between nodes is handled by the caller.
 */
public final class VPathfinder {

    // ================================================================== path types

    public enum PathType {
        BLOCKED(-1.0F), OPEN(0.0F), WALKABLE(0.0F), WALKABLE_DOOR(0.0F), TRAPDOOR(0.0F),
        POWDER_SNOW(-1.0F), ON_TOP_OF_POWDER_SNOW(0.0F), FENCE(-1.0F), LAVA(-1.0F),
        WATER(8.0F), WATER_BORDER(8.0F), RAIL(0.0F), UNPASSABLE_RAIL(-1.0F),
        FIRE_IN_NEIGHBOR(8.0F), FIRE(16.0F), DAMAGING_IN_NEIGHBOR(8.0F), DAMAGING(-1.0F),
        DOOR_OPEN(0.0F), DOOR_WOOD_CLOSED(-1.0F), DOOR_IRON_CLOSED(-1.0F), BREACH(4.0F),
        LEAVES(-1.0F), STICKY_HONEY(8.0F), COCOA(0.0F), DAMAGE_CAUTIOUS(0.0F),
        ON_TOP_OF_TRAPDOOR(0.0F), BIG_MOBS_CLOSE_TO_DANGER(4.0F);

        public final float malus;

        PathType(float malus) {
            this.malus = malus;
        }
    }

    // ================================================================== node & heap

    static class Node {
        final int x, y, z;
        final int hash;
        int heapIdx = -1;
        float g, h, f;
        float walkedDistance;
        float costMalus;
        boolean closed;
        Node cameFrom;
        PathType type = PathType.BLOCKED;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.hash = y & 0xFF | (x & 32767) << 8 | (z & 32767) << 24
                    | (x < 0 ? Integer.MIN_VALUE : 0) | (z < 0 ? 32768 : 0);
        }

        boolean inOpenSet() {
            return heapIdx >= 0;
        }

        float distanceTo(Node to) {
            float xd = to.x - x, yd = to.y - y, zd = to.z - z;
            return (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        }

        float distanceManhattan(Node to) {
            return Math.abs(to.x - x) + Math.abs(to.y - y) + Math.abs(to.z - z);
        }
    }

    static final class Target extends Node {
        float bestHeuristic = Float.MAX_VALUE;
        Node bestNode;
        boolean reached;

        Target(Node node) {
            super(node.x, node.y, node.z);
        }

        void updateBest(float heuristic, Node node) {
            if (heuristic < bestHeuristic) {
                bestHeuristic = heuristic;
                bestNode = node;
            }
        }
    }

    static final class BinaryHeap {
        private Node[] heap = new Node[128];
        private int size;

        void insert(Node node) {
            if (size == heap.length) {
                Node[] bigger = new Node[size << 1];
                System.arraycopy(heap, 0, bigger, 0, size);
                heap = bigger;
            }
            heap[size] = node;
            node.heapIdx = size;
            upHeap(size++);
        }

        void clear() {
            size = 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        Node pop() {
            Node popped = heap[0];
            heap[0] = heap[--size];
            heap[size] = null;
            if (size > 0) downHeap(0);
            popped.heapIdx = -1;
            return popped;
        }

        void changeCost(Node node, float newCost) {
            float oldCost = node.f;
            node.f = newCost;
            if (newCost < oldCost) upHeap(node.heapIdx);
            else downHeap(node.heapIdx);
        }

        private void upHeap(int idx) {
            Node node = heap[idx];
            float cost = node.f;
            while (idx > 0) {
                int parentIdx = idx - 1 >> 1;
                Node parent = heap[parentIdx];
                if (!(cost < parent.f)) break;
                heap[idx] = parent;
                parent.heapIdx = idx;
                idx = parentIdx;
            }
            heap[idx] = node;
            node.heapIdx = idx;
        }

        private void downHeap(int idx) {
            Node node = heap[idx];
            float cost = node.f;
            while (true) {
                int child1 = 1 + (idx << 1);
                int child2 = child1 + 1;
                if (child1 >= size) break;
                Node c1 = heap[child1];
                float f1 = c1.f;
                Node c2;
                float f2;
                if (child2 >= size) {
                    c2 = null;
                    f2 = Float.POSITIVE_INFINITY;
                } else {
                    c2 = heap[child2];
                    f2 = c2.f;
                }
                if (f1 < f2) {
                    if (!(f1 < cost)) break;
                    heap[idx] = c1;
                    c1.heapIdx = idx;
                    idx = child1;
                } else {
                    if (!(f2 < cost)) break;
                    heap[idx] = c2;
                    c2.heapIdx = idx;
                    idx = child2;
                }
            }
            heap[idx] = node;
            node.heapIdx = idx;
        }
    }

    // ================================================================== mob parameters

    /** Pathing profile: vanilla Mob defaults, with per-mob malus overrides. */
    public static final class MobProfile {
        public final float width, height;
        public final float maxUpStep = 0.6F;
        public final int maxFallDistance = 3;
        public final boolean canPassDoors = true;
        public final boolean canOpenDoors = false;
        public final boolean canFloat;
        public final Map<PathType, Float> malusOverrides = new HashMap<>();

        public MobProfile(float width, float height, boolean canFloat) {
            this.width = width;
            this.height = height;
            this.canFloat = canFloat;
        }

        float malus(PathType type) {
            Float override = malusOverrides.get(type);
            return override != null ? override : type.malus;
        }
    }

    // ================================================================== evaluator

    private final Instance instance;
    private final MobProfile mob;
    private final double mobX, mobY, mobZ;
    private final boolean onGround;
    private final int entityWidth, entityHeight, entityDepth;
    private final Map<Integer, Node> nodes = new HashMap<>();
    private final Map<Long, PathType> pathTypeCache = new HashMap<>();
    private final Node[] neighbors = new Node[32];
    private final Node[] reusableNeighbors = new Node[4];

    private VPathfinder(Instance instance, MobProfile mob, Point position, boolean onGround) {
        this.instance = instance;
        this.mob = mob;
        this.mobX = position.x();
        this.mobY = position.y();
        this.mobZ = position.z();
        this.onGround = onGround;
        this.entityWidth = floor(mob.width + 1.0F);
        this.entityHeight = floor(mob.height + 1.0F);
        this.entityDepth = floor(mob.width + 1.0F);
    }

    /** Compute a vanilla path from the mob to the target; null if no route at all. */
    public static List<Point> findPath(Instance instance, LivingEntity entity, MobProfile profile,
                                       Point target, float maxPathLength) {
        VPathfinder finder = new VPathfinder(instance, profile, entity.getPosition(), entity.isOnGround());
        return finder.find(target, maxPathLength);
    }

    private Block block(int x, int y, int z) {
        if (y < instance.getCachedDimensionType().minY()
                || y >= instance.getCachedDimensionType().minY() + instance.getCachedDimensionType().height()) {
            return Block.AIR;
        }
        var chunk = instance.getChunkAt(x, z);
        if (chunk == null) return Block.AIR;
        return instance.getBlock(x, y, z);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ------------------------------------------------------------------ path type of a single block

    private PathType pathTypeFromState(int x, int y, int z) {
        Block b = block(x, y, z);
        if (b.isAir()) return PathType.OPEN;
        String name = b.name();
        if (name.endsWith("_trapdoor") || name.equals("minecraft:lily_pad") || name.equals("minecraft:big_dripleaf"))
            return PathType.TRAPDOOR;
        if (name.equals("minecraft:powder_snow")) return PathType.POWDER_SNOW;
        if (name.equals("minecraft:cactus") || name.equals("minecraft:sweet_berry_bush")) return PathType.DAMAGING;
        if (name.equals("minecraft:honey_block")) return PathType.STICKY_HONEY;
        if (name.equals("minecraft:cocoa")) return PathType.COCOA;
        if (name.equals("minecraft:wither_rose") || name.equals("minecraft:pointed_dripstone"))
            return PathType.DAMAGE_CAUTIOUS;
        if (name.equals("minecraft:lava")) return PathType.LAVA;
        if (name.equals("minecraft:fire") || name.equals("minecraft:soul_fire")
                || name.equals("minecraft:magma_block")
                || (name.endsWith("campfire") && "true".equals(b.getProperty("lit"))))
            return PathType.FIRE;
        if (name.endsWith("_door")) {
            boolean open = "true".equals(b.getProperty("open"));
            if (open) return PathType.DOOR_OPEN;
            return name.equals("minecraft:iron_door") ? PathType.DOOR_IRON_CLOSED : PathType.DOOR_WOOD_CLOSED;
        }
        if (name.endsWith("rail")) return PathType.RAIL;
        if (name.endsWith("_leaves")) return PathType.LEAVES;
        if (name.endsWith("_fence") || name.endsWith("_wall")
                || (name.endsWith("_fence_gate") && !"true".equals(b.getProperty("open"))))
            return PathType.FENCE;
        if (name.equals("minecraft:water")) return PathType.WATER;
        // isPathfindable(LAND): no collision shape
        if (b.isSolid()) return PathType.BLOCKED;
        return PathType.OPEN;
    }

    private PathType pathTypeStatic(int x, int y, int z) {
        PathType type = pathTypeFromState(x, y, z);
        if (type == PathType.OPEN && y >= MIN_WORLD_Y + 1) {
            return switch (pathTypeFromState(x, y - 1, z)) {
                case OPEN, WATER, LAVA, WALKABLE -> PathType.OPEN;
                case FIRE -> PathType.FIRE;
                case DAMAGING -> PathType.DAMAGING;
                case STICKY_HONEY -> PathType.STICKY_HONEY;
                case POWDER_SNOW -> PathType.ON_TOP_OF_POWDER_SNOW;
                case DAMAGE_CAUTIOUS -> PathType.DAMAGE_CAUTIOUS;
                case TRAPDOOR -> PathType.ON_TOP_OF_TRAPDOOR;
                default -> checkNeighbourBlocks(x, y, z, PathType.WALKABLE);
            };
        }
        return type;
    }

    private static final int MIN_WORLD_Y = -64;

    private PathType checkNeighbourBlocks(int x, int y, int z, PathType fallback) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        PathType type = pathTypeFromState(x + dx, y + dy, z + dz);
                        if (type == PathType.DAMAGING) return PathType.DAMAGING_IN_NEIGHBOR;
                        if (type == PathType.FIRE || type == PathType.LAVA) return PathType.FIRE_IN_NEIGHBOR;
                        if (type == PathType.WATER) return PathType.WATER_BORDER;
                        if (type == PathType.DAMAGE_CAUTIOUS) return PathType.DAMAGE_CAUTIOUS;
                    }
                }
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------ path type over the mob BB

    private PathType getPathTypeOfMob(int x, int y, int z) {
        var types = java.util.EnumSet.noneOf(PathType.class);
        int mx = floor(mobX), my = floor(mobY), mz = floor(mobZ);
        for (int dx = 0; dx < entityWidth; dx++) {
            for (int dy = 0; dy < entityHeight; dy++) {
                for (int dz = 0; dz < entityDepth; dz++) {
                    PathType type = pathTypeStatic(x + dx, y + dy, z + dz);
                    if (type == PathType.DOOR_WOOD_CLOSED && mob.canOpenDoors && mob.canPassDoors) {
                        type = PathType.WALKABLE_DOOR;
                    }
                    if (type == PathType.DOOR_OPEN && !mob.canPassDoors) {
                        type = PathType.BLOCKED;
                    }
                    if (type == PathType.RAIL
                            && pathTypeStatic(mx, my, mz) != PathType.RAIL
                            && pathTypeStatic(mx, my - 1, mz) != PathType.RAIL) {
                        type = PathType.UNPASSABLE_RAIL;
                    }
                    types.add(type);
                }
            }
        }
        if (types.size() == 1) return types.iterator().next();
        if (types.contains(PathType.FENCE)) return PathType.FENCE;
        if (types.contains(PathType.UNPASSABLE_RAIL)) return PathType.UNPASSABLE_RAIL;
        PathType highest = PathType.BLOCKED;
        float highestMalus = mob.malus(highest);
        for (PathType type : types) {
            float malus = mob.malus(type);
            if (malus < 0.0F) return type;
            if (malus >= highestMalus) {
                highestMalus = malus;
                highest = type;
            }
        }
        PathType current = pathTypeStatic(x, y, z);
        if (entityWidth > 1) {
            boolean cheaper = mob.malus(current) < highestMalus;
            boolean cap = cheaper && mob.malus(PathType.BIG_MOBS_CLOSE_TO_DANGER) < highestMalus;
            return cap ? PathType.BIG_MOBS_CLOSE_TO_DANGER : highest;
        }
        return current == PathType.OPEN && highest != PathType.OPEN && highestMalus == 0.0F
                ? PathType.OPEN : highest;
    }

    private PathType cachedPathType(int x, int y, int z) {
        long key = ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
        return pathTypeCache.computeIfAbsent(key, k -> getPathTypeOfMob(x, y, z));
    }

    // ------------------------------------------------------------------ nodes

    private Node getNode(int x, int y, int z) {
        return nodes.computeIfAbsent(new Node(x, y, z).hash, k -> new Node(x, y, z));
    }

    private double getFloorLevel(int x, int y, int z) {
        if (mob.canFloat && block(x, y, z) == Block.WATER) return y + 0.5;
        Block below = block(x, y - 1, z);
        if (below.isAir() || !below.isSolid()) return y - 1;
        return y - 1 + collisionTop(below);
    }

    /** Real collision-shape max Y (registry-driven, not a hardcoded per-block-name list — covers
     * every partial-height block generically: slabs, carpets, farmland, soul sand, scaffolding,
     * etc.). Verified against real vanilla heights for slabs/carpets/farmland/dirt_path — all
     * match exactly. Snow layers are a confirmed EXCEPTION: Minestom's registered collision shape
     * for minecraft:snow doesn't match real vanilla's layers*0.125 formula (e.g. layers=3 reports
     * 0.25 instead of 0.375), so that one case is still hardcoded rather than trusting the registry. */
    private static double collisionTop(Block block) {
        if (block.name().equals("minecraft:snow")) {
            return Integer.parseInt(block.getProperty("layers")) * 0.125;
        }
        var reg = block.registry();
        if (reg == null) return 1.0;
        var shape = reg.collisionShape();
        if (shape == null) return 1.0;
        return shape.relativeEnd().y();
    }

    // ------------------------------------------------------------------ A* search

    private List<Point> find(Point targetPoint, float maxPathLength) {
        // getStart
        int startY = floor(mobY);
        if (mob.canFloat && block(floor(mobX), startY, floor(mobZ)) == Block.WATER) {
            while (block(floor(mobX), startY, floor(mobZ)) == Block.WATER) startY++;
            startY--;
        } else if (onGround) {
            startY = floor(mobY + 0.5);
        } else {
            int y = floor(mobY + 1.0);
            while (y > MIN_WORLD_Y) {
                startY = y;
                Block below = block(floor(mobX), y - 1, floor(mobZ));
                if (!below.isAir() && below.isSolid()) break;
                y--;
            }
        }
        Node from = getNode(floor(mobX), startY, floor(mobZ));
        from.type = cachedPathType(from.x, from.y, from.z);
        from.costMalus = mob.malus(from.type);

        Target target = new Target(getNode(floor(targetPoint.x()), floor(targetPoint.y()), floor(targetPoint.z())));
        BinaryHeap openSet = new BinaryHeap();
        from.g = 0.0F;
        from.h = from.distanceTo(target);
        target.updateBest(from.h, from);
        from.f = from.h;
        openSet.insert(from);
        // vanilla: maxVisitedNodes = (int)(followRange * 16), multiplier 1; maxPathLength == followRange
        int maxVisited = (int) (maxPathLength * 16.0F);
        int count = 0;
        boolean reached = false;

        while (!openSet.isEmpty()) {
            if (++count >= maxVisited) break;
            Node current = openSet.pop();
            current.closed = true;
            if (current.distanceManhattan(target) <= 0) {
                target.reached = true;
                target.updateBest(0, current);
                reached = true;
                break;
            }
            if (current.distanceTo(from) >= maxPathLength) continue;

            int neighborCount = getNeighbors(current);
            for (int i = 0; i < neighborCount; i++) {
                Node neighbor = neighbors[i];
                float distance = current.distanceTo(neighbor);
                neighbor.walkedDistance = current.walkedDistance + distance;
                float tentative = current.g + distance + neighbor.costMalus;
                if (neighbor.walkedDistance < maxPathLength && (!neighbor.inOpenSet() || tentative < neighbor.g)) {
                    neighbor.cameFrom = current;
                    neighbor.g = tentative;
                    float h = neighbor.distanceTo(target);
                    target.updateBest(h, neighbor);
                    neighbor.h = h * 1.5F;
                    if (neighbor.inOpenSet()) {
                        openSet.changeCost(neighbor, neighbor.g + neighbor.h);
                    } else {
                        neighbor.f = neighbor.g + neighbor.h;
                        openSet.insert(neighbor);
                    }
                }
            }
        }

        Node best = target.bestNode;
        if (best == null) return null;
        List<Point> points = new ArrayList<>();
        Node node = best;
        while (node != null) {
            points.add(0, new Vec(node.x + 0.5, node.y, node.z + 0.5));
            node = node.cameFrom;
        }
        return points;
    }

    // ------------------------------------------------------------------ neighbors (WalkNodeEvaluator)

    private static final int[][] HORIZONTALS = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}}; // south, west, north, east

    private int getNeighbors(Node pos) {
        int p = 0;
        int jumpSize = 0;
        PathType above = cachedPathType(pos.x, pos.y + 1, pos.z);
        PathType current = cachedPathType(pos.x, pos.y, pos.z);
        if (mob.malus(above) >= 0.0F && current != PathType.STICKY_HONEY) {
            jumpSize = floor(Math.max(1.0F, mob.maxUpStep));
        }
        double floorLevel = getFloorLevel(pos.x, pos.y, pos.z);

        for (int d = 0; d < 4; d++) {
            Node node = findAcceptedNode(pos.x + HORIZONTALS[d][0], pos.y, pos.z + HORIZONTALS[d][1],
                    jumpSize, floorLevel, d, current);
            reusableNeighbors[d] = node;
            if (node != null && !node.closed && (node.costMalus >= 0.0F || pos.costMalus < 0.0F)) {
                neighbors[p++] = node;
            }
        }

        for (int d = 0; d < 4; d++) {
            int cw = (d + 3) % 4; // getClockWise: south->west, west->north, north->east, east->south
            Node ew = reusableNeighbors[d];
            Node ns = reusableNeighbors[cw];
            if (isDiagonalValid(pos, ew, ns)) {
                Node diagonal = findAcceptedNode(pos.x + HORIZONTALS[d][0] + HORIZONTALS[cw][0], pos.y,
                        pos.z + HORIZONTALS[d][1] + HORIZONTALS[cw][1], jumpSize, floorLevel, d, current);
                if (diagonal != null && !diagonal.closed
                        && diagonal.type != PathType.WALKABLE_DOOR && diagonal.costMalus >= 0.0F) {
                    neighbors[p++] = diagonal;
                }
            }
        }
        return p;
    }

    private boolean isDiagonalValid(Node pos, Node ew, Node ns) {
        if (ns == null || ew == null || ns.y > pos.y || ew.y > pos.y) return false;
        if (ew.type == PathType.WALKABLE_DOOR || ns.type == PathType.WALKABLE_DOOR) return false;
        if (mob.width > 1.0F && (ew.costMalus > 0.0F || ns.costMalus > 0.0F)) return false;
        boolean canPassBetweenPosts = ns.type == PathType.FENCE && ew.type == PathType.FENCE && mob.width < 0.5;
        return (ns.y < pos.y || ns.costMalus >= 0.0F || canPassBetweenPosts)
                && (ew.y < pos.y || ew.costMalus >= 0.0F || canPassBetweenPosts);
    }

    private Node findAcceptedNode(int x, int y, int z, int jumpSize, double nodeHeight,
                                  int direction, PathType currentType) {
        Node best = null;
        double floorLevel = getFloorLevel(x, y, z);
        if (floorLevel - nodeHeight > Math.max(1.125, mob.maxUpStep)) return null;
        PathType type = cachedPathType(x, y, z);
        float cost = mob.malus(type);
        if (cost >= 0.0F) {
            best = getNode(x, y, z);
            best.type = type;
            best.costMalus = Math.max(best.costMalus, cost);
        }
        if (type == PathType.WALKABLE) return best;

        if ((best == null || best.costMalus < 0.0F) && jumpSize > 0
                && type != PathType.FENCE && type != PathType.UNPASSABLE_RAIL
                && type != PathType.TRAPDOOR && type != PathType.POWDER_SNOW) {
            best = findAcceptedNode(x, y + 1, z, jumpSize - 1, nodeHeight, direction, currentType);
        } else if (type == PathType.WATER && !mob.canFloat) {
            int yy = y - 1;
            while (yy > MIN_WORLD_Y) {
                PathType localType = cachedPathType(x, yy, z);
                if (localType != PathType.WATER) return best;
                best = getNode(x, yy, z);
                best.type = localType;
                best.costMalus = Math.max(best.costMalus, mob.malus(localType));
                yy--;
            }
        } else if (type == PathType.OPEN) {
            for (int yy = y - 1; yy >= MIN_WORLD_Y; yy--) {
                if (y - yy > mob.maxFallDistance) {
                    return blockedNode(x, yy, z);
                }
                PathType localType = cachedPathType(x, yy, z);
                float localCost = mob.malus(localType);
                if (localType != PathType.OPEN) {
                    if (localCost >= 0.0F) {
                        Node node = getNode(x, yy, z);
                        node.type = localType;
                        node.costMalus = Math.max(node.costMalus, localCost);
                        return node;
                    }
                    return blockedNode(x, yy, z);
                }
            }
            return blockedNode(x, y, z);
        } else if (type == PathType.FENCE || type == PathType.DOOR_WOOD_CLOSED || type == PathType.DOOR_IRON_CLOSED) {
            if (best == null) {
                Node node = getNode(x, y, z);
                node.closed = true;
                node.type = type;
                node.costMalus = type.malus;
                return node;
            }
        }
        return best;
    }

    private Node blockedNode(int x, int y, int z) {
        Node node = getNode(x, y, z);
        node.type = PathType.BLOCKED;
        node.costMalus = -1.0F;
        return node;
    }
}
