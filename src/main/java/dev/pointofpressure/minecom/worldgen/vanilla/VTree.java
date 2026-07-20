package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vanilla TreeFeature: trunk placers (straight, fancy, giant, dark_oak, cherry,
 * forking, mega_jungle, bending), foliage placers (blob, fancy, spruce, pine,
 * mega_pine, dark_oak, cherry, acacia, random_spread, bush, jungle), two/three-
 * layer feature sizes, and the beehive + alter_ground decorators — with
 * vanilla's exact random-draw order, including the HashSet-iteration-then-sort
 * ordering of decorator position lists (our Pos record reproduces
 * BlockPos.hashCode so java.util.HashSet iterates identically).
 *
 * <p>Root placers (mangrove) are not modeled — TreeFeature.doPlace's
 * config.rootPlacer branch (getTrunkOrigin draw + placeRoots) is absent, so any
 * config with a {@code root_placer} is skipped (see docs/HANDOFF.md).
 */
final class VTree {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private static final Set<String> TRUNK_OK = Set.of(
            "straight_trunk_placer", "fancy_trunk_placer", "giant_trunk_placer",
            "dark_oak_trunk_placer", "cherry_trunk_placer", "forking_trunk_placer",
            "mega_jungle_trunk_placer", "bending_trunk_placer");
    private static final Set<String> FOLIAGE_OK = Set.of(
            "blob_foliage_placer", "fancy_foliage_placer", "spruce_foliage_placer",
            "pine_foliage_placer", "mega_pine_foliage_placer", "dark_oak_foliage_placer",
            "cherry_foliage_placer", "acacia_foliage_placer", "random_spread_foliage_placer",
            "bush_foliage_placer", "jungle_foliage_placer");

    private final VFeature host;

    VTree(VFeature host) {
        this.host = host;
    }

    /** BlockPos-equivalent with vanilla's exact hashCode for HashSet ordering. */
    record Pos(int x, int y, int z) {
        @Override
        public int hashCode() {
            return (y + z * 31) * 31 + x;
        }

        Pos above(int n) {
            return new Pos(x, y + n, z);
        }

        Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    // ================================================================== helpers

    private boolean validTreePos(VFeature.Canvas canvas, int x, int y, int z) {
        Block b = canvas.get(x, y, z);
        if (b == null) return true;
        return host.tag("minecraft:replaceable_by_trees").contains(b.name());
    }

    private boolean isFree(VFeature.Canvas canvas, int x, int y, int z) {
        if (validTreePos(canvas, x, y, z)) return true;
        Block b = canvas.get(x, y, z);
        return b != null && host.tag("minecraft:logs").contains(b.name());
    }

    // ================================================================== main driver

    /** TreeFeature.place: returns true if the tree generated. */
    boolean place(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random, int ox, int oy, int oz) {
        JsonObject trunkPlacer = config.getAsJsonObject("trunk_placer");
        JsonObject foliagePlacer = config.getAsJsonObject("foliage_placer");
        String trunkType = path(trunkPlacer.get("type").getAsString());
        String foliageType = path(foliagePlacer.get("type").getAsString());
        // Root placers (mangrove) are unmodeled — the getTrunkOrigin draw and
        // placeRoots branch of TreeFeature.doPlace are absent, so skipping keeps
        // RNG aligned for any later tree in the count-loop.
        if (config.has("root_placer")) return false;
        if (!TRUNK_OK.contains(trunkType)) return false;
        if (!FOLIAGE_OK.contains(foliageType)) return false;

        Block trunkState = VSurface.parseBlockState(
                config.getAsJsonObject("trunk_provider").getAsJsonObject("state"));
        Block foliageState = VSurface.parseBlockState(
                config.getAsJsonObject("foliage_provider").getAsJsonObject("state"));
        boolean ignoreVines = config.has("ignore_vines") && config.get("ignore_vines").getAsBoolean();

        // draw order: tree height, foliage height, leaf radius
        int baseHeight = trunkPlacer.get("base_height").getAsInt();
        int randA = trunkPlacer.get("height_rand_a").getAsInt();
        int randB = trunkPlacer.get("height_rand_b").getAsInt();
        int treeHeight = baseHeight + random.nextInt(randA + 1) + random.nextInt(randB + 1);
        int foliageHeight = switch (foliageType) {
            case "spruce_foliage_placer" ->
                    Math.max(4, treeHeight - VFeature.sampleInt(foliagePlacer.get("trunk_height"), random));
            case "pine_foliage_placer" -> VFeature.sampleInt(foliagePlacer.get("height"), random);
            case "mega_pine_foliage_placer" -> VFeature.sampleInt(foliagePlacer.get("crown_height"), random);
            case "dark_oak_foliage_placer" -> 4; // DarkOakFoliagePlacer.foliageHeight: constant 4
            case "acacia_foliage_placer" -> 0;   // AcaciaFoliagePlacer.foliageHeight: constant 0
            case "cherry_foliage_placer" -> VFeature.sampleInt(foliagePlacer.get("height"), random);
            case "random_spread_foliage_placer" ->
                    VFeature.sampleInt(foliagePlacer.get("foliage_height"), random);
            default -> foliagePlacer.get("height").getAsInt(); // blob/fancy/bush/jungle: constant, no draw
        };
        int trunkHeight = treeHeight - foliageHeight;
        int leafRadius = VFeature.sampleInt(foliagePlacer.get("radius"), random);
        if (foliageType.equals("pine_foliage_placer")) {
            leafRadius += random.nextInt(Math.max(trunkHeight + 1, 1));
        }

        int minY = oy;
        int maxY = oy + treeHeight + 1;
        if (minY < MIN_Y + 1 || maxY > MIN_Y + HEIGHT) return false;

        JsonObject minimumSize = config.getAsJsonObject("minimum_size");
        int clippedHeight = maxFreeTreeHeight(canvas, treeHeight, ox, oy, oz, minimumSize, ignoreVines);
        Integer minClipped = minimumSize.has("min_clipped_height")
                ? minimumSize.get("min_clipped_height").getAsInt() : null;
        if (clippedHeight < treeHeight && (minClipped == null || clippedHeight < minClipped)) return false;

        Set<Pos> trunks = new LinkedHashSet<>();
        Set<Pos> foliage = new LinkedHashSet<>();
        List<int[]> attachments; // {x, y, z, radiusOffset}

        attachments = switch (trunkType) {
            case "straight_trunk_placer" ->
                    straightTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
            case "giant_trunk_placer" ->
                    giantTrunk(canvas, config, clippedHeight, ox, oy, oz, trunkState, trunks);
            case "fancy_trunk_placer" ->
                    fancyTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
            case "dark_oak_trunk_placer" ->
                    darkOakTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
            case "cherry_trunk_placer" ->
                    cherryTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
            case "forking_trunk_placer" ->
                    forkingTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
            case "mega_jungle_trunk_placer" ->
                    megaJungleTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
            case "bending_trunk_placer" ->
                    bendingTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
            default -> throw new IllegalStateException("unreachable trunk type " + trunkType);
        };

        for (int[] att : attachments) {
            createFoliage(canvas, foliagePlacer, foliageType, random, treeHeight,
                    att, foliageHeight, leafRadius, foliageState, foliage);
        }

        if (trunks.isEmpty() && foliage.isEmpty()) return false;

        // decorators — writes recorded like vanilla's decorationSetter, so
        // updateLeaves can pre-mark them visited
        Set<Pos> decorations = new LinkedHashSet<>();
        JsonArray decorators = config.getAsJsonArray("decorators");
        if (decorators != null && !decorators.isEmpty()) {
            List<Pos> logsSorted = hashOrderedSorted(trunks);
            List<Pos> leavesSorted = hashOrderedSorted(foliage);
            for (JsonElement d : decorators) {
                JsonObject dec = d.getAsJsonObject();
                switch (path(dec.get("type").getAsString())) {
                    case "beehive" -> beehive(canvas, dec, random, logsSorted, leavesSorted, decorations);
                    case "alter_ground" -> alterGround(canvas, dec, random, logsSorted, decorations);
                    case "place_on_ground" -> placeOnGround(canvas, dec, random, logsSorted, decorations);
                    default -> { }
                }
            }
        }
        updateLeaves(canvas, trunks, foliage, decorations);
        return true;
    }

    /**
     * TreeFeature.updateLeaves (26.2 decompile): after placing, vanilla rewrites
     * every reachable leaf's distance property via a bucketed BFS from the logs.
     * Any block in #prevents_nearby_leaf_decay (= #logs) is distance 0 — including
     * a NEIGHBORING tree's logs inside the bounding box; a leaf's distance is
     * min(its current distance value, parent+1), capped below 7. Decoration
     * positions are pre-marked visited so they are never traversed or rewritten.
     * The bounding box is vanilla's encapsulatingPositions over logs + foliage +
     * decorations (no root placers are modeled, so no root positions). The
     * trailing StructureTemplate.updateShapeAtEdge pass is not modeled (the
     * canvas has no neighbor shape updates). This is what turns the serialized
     * foliage_provider distance=7 into the real 1..6 gradient near trunks.
     */
    private void updateLeaves(VFeature.Canvas canvas, Set<Pos> logs, Set<Pos> foliage, Set<Pos> decorations) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Set<Pos> set : List.of(logs, foliage, decorations)) {
            for (Pos p : set) {
                minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
                minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
            }
        }
        if (minX > maxX) return;

        List<Set<Pos>> toCheck = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) toCheck.add(new java.util.HashSet<>());
        Set<Pos> visited = new java.util.HashSet<>();
        for (Pos p : decorations) {
            if (inside(p, minX, minY, minZ, maxX, maxY, maxZ)) visited.add(p);
        }
        // vanilla's trunks set is a HashSet built by incremental insert; the BFS pops
        // via its iterator, so bucket order matters — rebuild the same table shape
        // (Pos.hashCode == BlockPos.hashCode) before seeding bucket 0
        java.util.HashSet<Pos> seed = new java.util.HashSet<>();
        for (Pos p : logs) seed.add(p);
        toCheck.get(0).addAll(seed);
        Set<String> preventsDecay = host.tag("minecraft:prevents_nearby_leaf_decay");

        int distance = 0;
        while (distance < 7) {
            Set<Pos> bucket = toCheck.get(distance);
            if (bucket.isEmpty()) {
                distance++;
                continue;
            }
            Iterator<Pos> it = bucket.iterator();
            Pos pos = it.next();
            it.remove();
            if (!inside(pos, minX, minY, minZ, maxX, maxY, maxZ)) continue;
            if (distance != 0) {
                Block state = canvas.get(pos.x, pos.y, pos.z);
                if (state != null) {
                    canvas.set(pos.x, pos.y, pos.z,
                            state.withProperty("distance", String.valueOf(distance)));
                }
            }
            visited.add(pos);
            for (int[] d : NEIGHBORS) {
                Pos n = pos.offset(d[0], d[1], d[2]);
                if (!inside(n, minX, minY, minZ, maxX, maxY, maxZ) || visited.contains(n)) continue;
                Block neighborState = canvas.get(n.x, n.y, n.z);
                Integer at = distanceAt(neighborState, preventsDecay);
                if (at == null) continue;
                int newDistance = Math.min(at, distance + 1);
                if (newDistance < 7) {
                    toCheck.get(newDistance).add(n);
                    distance = Math.min(distance, newDistance);
                }
            }
        }
    }

    private static final int[][] NEIGHBORS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    private static boolean inside(Pos p, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY && p.z >= minZ && p.z <= maxZ;
    }

    /** LeavesBlock.getOptionalDistanceAt: 0 for #prevents_nearby_leaf_decay, else the distance property. */
    private static Integer distanceAt(Block state, Set<String> preventsDecay) {
        if (state == null) return null;
        if (preventsDecay.contains(state.name())) return 0;
        String value = state.getProperty("distance");
        return value == null ? null : Integer.valueOf(value);
    }

    /** vanilla builds a HashSet then sorts by Y (stable) — reproduce both steps. */
    private static List<Pos> hashOrderedSorted(Set<Pos> insertionOrdered) {
        // vanilla accumulates into a HashSet: reproduce its iteration order, then stable-sort
        java.util.HashSet<Pos> vanillaSet = new java.util.HashSet<>();
        for (Pos p : insertionOrdered) vanillaSet.add(p);
        List<Pos> result = new ArrayList<>(vanillaSet);
        result.sort(Comparator.comparingInt(p -> p.y));
        return result;
    }

    private int maxFreeTreeHeight(VFeature.Canvas canvas, int maxTreeHeight, int x, int y, int z,
                                  JsonObject minimumSize, boolean ignoreVines) {
        String sizeType = path(minimumSize.get("type").getAsString());
        for (int yo = 0; yo <= maxTreeHeight + 1; yo++) {
            int r = sizeAtHeight(sizeType, minimumSize, maxTreeHeight, yo);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int px = x + dx, py = y + yo, pz = z + dz;
                    boolean free = isFree(canvas, px, py, pz);
                    if (!free || !ignoreVines && isVine(canvas, px, py, pz)) {
                        return yo - 2;
                    }
                }
            }
        }
        return maxTreeHeight;
    }

    private boolean isVine(VFeature.Canvas canvas, int x, int y, int z) {
        Block b = canvas.get(x, y, z);
        return b != null && b.name().equals("minecraft:vine");
    }

    private static int sizeAtHeight(String type, JsonObject size, int treeHeight, int yo) {
        int limit = size.has("limit") ? size.get("limit").getAsInt() : 1;
        if (type.equals("three_layers_feature_size")) {
            int upperLimit = size.has("upper_limit") ? size.get("upper_limit").getAsInt() : 1;
            int lowerSize = size.has("lower_size") ? size.get("lower_size").getAsInt() : 0;
            int middleSize = size.has("middle_size") ? size.get("middle_size").getAsInt() : 1;
            int upperSize = size.has("upper_size") ? size.get("upper_size").getAsInt() : 1;
            if (yo < limit) return lowerSize;
            return yo >= treeHeight - upperLimit ? upperSize : middleSize;
        }
        int lowerSize = size.has("lower_size") ? size.get("lower_size").getAsInt() : 0;
        int upperSize = size.has("upper_size") ? size.get("upper_size").getAsInt() : 1;
        return yo < limit ? lowerSize : upperSize;
    }

    // ================================================================== trunk placers

    private void placeBelowTrunkBlock(VFeature.Canvas canvas, JsonObject config, int x, int y, int z) {
        JsonObject provider = config.getAsJsonObject("below_trunk_provider");
        if (provider == null) return;
        // rule_based provider: rules test predicates, no random draws for our configs
        Block state = ruleState(provider, canvas, x, y, z);
        if (state != null) canvas.set(x, y, z, state);
    }

    private Block ruleState(JsonObject provider, VFeature.Canvas canvas, int x, int y, int z) {
        String type = path(provider.get("type").getAsString());
        return switch (type) {
            case "simple_state_provider" -> VSurface.parseBlockState(provider.getAsJsonObject("state"));
            case "rule_based_state_provider" -> {
                for (JsonElement e : provider.getAsJsonArray("rules")) {
                    JsonObject rule = e.getAsJsonObject();
                    if (host.testPredicate(rule.getAsJsonObject("if_true"), canvas, x, y, z)) {
                        yield ruleState(rule.getAsJsonObject("then"), canvas, x, y, z);
                    }
                }
                if (provider.has("fallback")) yield ruleState(provider.getAsJsonObject("fallback"), canvas, x, y, z);
                yield null;
            }
            default -> null;
        };
    }

    private boolean placeLog(VFeature.Canvas canvas, int x, int y, int z, Block state, Set<Pos> trunks) {
        if (!validTreePos(canvas, x, y, z)) return false;
        canvas.set(x, y, z, state);
        trunks.add(new Pos(x, y, z));
        return true;
    }

    private List<int[]> straightTrunk(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random,
                                      int treeHeight, int x, int y, int z, Block trunkState, Set<Pos> trunks) {
        placeBelowTrunkBlock(canvas, config, x, y - 1, z);
        for (int yo = 0; yo < treeHeight; yo++) {
            placeLog(canvas, x, y + yo, z, trunkState, trunks);
        }
        return List.of(new int[]{x, y + treeHeight, z, 0});
    }

    private List<int[]> fancyTrunk(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random,
                                   int treeHeight, int ox, int oy, int oz, Block trunkState, Set<Pos> trunks) {
        int height = treeHeight + 2;
        int trunkHeight = VNoise.floor(height * 0.618);
        placeBelowTrunkBlock(canvas, config, ox, oy - 1, oz);
        int clustersPerY = Math.min(1, VNoise.floor(1.382 + Math.pow(1.0 * height / 13.0, 2.0)));
        int trunkTop = oy + trunkHeight;
        List<int[]> foliageCoords = new ArrayList<>(); // {x,y,z, branchBase}
        foliageCoords.add(new int[]{ox, oy + (height - 5), oz, trunkTop});

        for (int relativeY = height - 5; relativeY >= 0; relativeY--) {
            float treeShape = fancyTreeShape(height, relativeY);
            if (treeShape < 0.0F) continue;
            for (int i = 0; i < clustersPerY; i++) {
                double radius = 1.0 * treeShape * (random.nextFloat() + 0.328);
                double angle = random.nextFloat() * 2.0F * Math.PI;
                double xd = radius * Math.sin(angle) + 0.5;
                double zd = radius * Math.cos(angle) + 0.5;
                int sx = ox + VNoise.floor(xd), sy = oy + relativeY - 1, sz = oz + VNoise.floor(zd);
                if (makeLimb(canvas, random, sx, sy, sz, sx, sy + 5, sz, false, trunkState, trunks)) {
                    int dx = ox - sx;
                    int dz = oz - sz;
                    double branchHeight = sy - Math.sqrt(dx * dx + dz * dz) * 0.381;
                    int branchTop = branchHeight > trunkTop ? trunkTop : (int) branchHeight;
                    if (makeLimb(canvas, random, ox, branchTop, oz, sx, sy, sz, false, trunkState, trunks)) {
                        foliageCoords.add(new int[]{sx, sy, sz, branchTop});
                    }
                }
            }
        }

        makeLimb(canvas, random, ox, oy, oz, ox, oy + trunkHeight, oz, true, trunkState, trunks);
        // makeBranches
        for (int[] coord : foliageCoords) {
            int branchBase = coord[3];
            if ((ox != coord[0] || oz != coord[2] || branchBase != coord[1])
                    && branchBase - oy >= height * 0.2) {
                makeLimb(canvas, random, ox, branchBase, oz, coord[0], coord[1], coord[2], true, trunkState, trunks);
            }
        }
        List<int[]> attachments = new ArrayList<>();
        for (int[] coord : foliageCoords) {
            if (coord[3] - oy >= height * 0.2) {
                attachments.add(new int[]{coord[0], coord[1], coord[2], 0});
            }
        }
        return attachments;
    }

    /** GiantTrunkPlacer: 2x2 log column, single foliage attachment with doubleTrunk. */
    private List<int[]> giantTrunk(VFeature.Canvas canvas, JsonObject config, int treeHeight,
                                   int ox, int oy, int oz, Block trunkState, Set<Pos> trunks) {
        placeBelowTrunkBlock(canvas, config, ox, oy - 1, oz);
        placeBelowTrunkBlock(canvas, config, ox + 1, oy - 1, oz);
        placeBelowTrunkBlock(canvas, config, ox, oy - 1, oz + 1);
        placeBelowTrunkBlock(canvas, config, ox + 1, oy - 1, oz + 1);
        for (int hh = 0; hh < treeHeight; hh++) {
            placeLog(canvas, ox, oy + hh, oz, trunkState, trunks);
            if (hh < treeHeight - 1) {
                placeLog(canvas, ox + 1, oy + hh, oz, trunkState, trunks);
                placeLog(canvas, ox + 1, oy + hh, oz + 1, trunkState, trunks);
                placeLog(canvas, ox, oy + hh, oz + 1, trunkState, trunks);
            }
        }
        return List.of(new int[]{ox, oy + treeHeight, oz, 0, 1}); // 5th elem = doubleTrunk
    }

    /** Plane.HORIZONTAL faces {NORTH, EAST, SOUTH, WEST} as (stepX, stepZ). */
    private static final int[][] HDIR = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** getRandomDirection axis for HDIR index: N/S -> z, E/W -> x. */
    private static String hAxis(int dirIdx) {
        return dirIdx % 2 == 0 ? "z" : "x";
    }

    /** Direction.getAxisDirection() == POSITIVE for EAST(1) and SOUTH(2). */
    private static boolean hAxisPositive(int dirIdx) {
        return dirIdx == 1 || dirIdx == 2;
    }

    private Block logWithAxis(Block trunkState, String axis) {
        return trunkState.properties().containsKey("axis") ? trunkState.withProperty("axis", axis) : trunkState;
    }

    /** TreeFeature.isAirOrLeaves: air or a #minecraft:leaves block. */
    private boolean isAirOrLeaves(VFeature.Canvas canvas, int x, int y, int z) {
        Block b = canvas.get(x, y, z);
        return b == null || host.tag("minecraft:leaves").contains(b.name());
    }

    /** DarkOakTrunkPlacer (26.2): 2x2 leaning column + four random branches. */
    private List<int[]> darkOakTrunk(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random,
                                     int treeHeight, int ox, int oy, int oz, Block trunkState, Set<Pos> trunks) {
        List<int[]> attachments = new ArrayList<>();
        placeBelowTrunkBlock(canvas, config, ox, oy - 1, oz);
        placeBelowTrunkBlock(canvas, config, ox + 1, oy - 1, oz);      // east
        placeBelowTrunkBlock(canvas, config, ox, oy - 1, oz + 1);      // south
        placeBelowTrunkBlock(canvas, config, ox + 1, oy - 1, oz + 1);  // south.east
        int leanIdx = random.nextInt(4);
        int leanDx = HDIR[leanIdx][0], leanDz = HDIR[leanIdx][1];
        int leanHeight = treeHeight - random.nextInt(4);
        int leanSteps = 2 - random.nextInt(3);
        int tx = ox, tz = oz;
        int ey = oy + treeHeight - 1;
        for (int dy = 0; dy < treeHeight; dy++) {
            if (dy >= leanHeight && leanSteps > 0) {
                tx += leanDx;
                tz += leanDz;
                leanSteps--;
            }
            int yy = oy + dy;
            if (isAirOrLeaves(canvas, tx, yy, tz)) {
                placeLog(canvas, tx, yy, tz, trunkState, trunks);
                placeLog(canvas, tx + 1, yy, tz, trunkState, trunks);
                placeLog(canvas, tx, yy, tz + 1, trunkState, trunks);
                placeLog(canvas, tx + 1, yy, tz + 1, trunkState, trunks);
            }
        }
        attachments.add(new int[]{tx, ey, tz, 0, 1}); // doubleTrunk
        for (int oxx = -1; oxx <= 2; oxx++) {
            for (int ozz = -1; ozz <= 2; ozz++) {
                if ((oxx < 0 || oxx > 1 || ozz < 0 || ozz > 1) && random.nextInt(3) <= 0) {
                    int length = random.nextInt(3) + 2;
                    for (int by = 0; by < length; by++) {
                        placeLog(canvas, ox + oxx, ey - by - 1, oz + ozz, trunkState, trunks);
                    }
                    attachments.add(new int[]{ox + oxx, ey, oz + ozz, 0, 0});
                }
            }
        }
        return attachments;
    }

    /** ForkingTrunkPlacer (26.2): leaning main stem + one optional side fork. */
    private List<int[]> forkingTrunk(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random,
                                     int treeHeight, int ox, int oy, int oz, Block trunkState, Set<Pos> trunks) {
        placeBelowTrunkBlock(canvas, config, ox, oy - 1, oz);
        List<int[]> attachments = new ArrayList<>();
        int leanIdx = random.nextInt(4);
        int leanDx = HDIR[leanIdx][0], leanDz = HDIR[leanIdx][1];
        int leanHeight = treeHeight - random.nextInt(4) - 1;
        int leanSteps = 3 - random.nextInt(3);
        int tx = ox, tz = oz;
        int ey = 0;
        boolean hasEy = false;
        for (int yo = 0; yo < treeHeight; yo++) {
            int yy = oy + yo;
            if (yo >= leanHeight && leanSteps > 0) {
                tx += leanDx;
                tz += leanDz;
                leanSteps--;
            }
            if (placeLog(canvas, tx, yy, tz, trunkState, trunks)) {
                ey = yy + 1;
                hasEy = true;
            }
        }
        if (hasEy) attachments.add(new int[]{tx, ey, tz, 1, 0});
        tx = ox;
        tz = oz;
        int branchIdx = random.nextInt(4);
        if (branchIdx != leanIdx) {
            int branchDx = HDIR[branchIdx][0], branchDz = HDIR[branchIdx][1];
            int branchPos = leanHeight - random.nextInt(2) - 1;
            int branchSteps = 1 + random.nextInt(3);
            hasEy = false;
            for (int yo = branchPos; yo < treeHeight && branchSteps > 0; branchSteps--) {
                if (yo >= 1) {
                    int yy = oy + yo;
                    tx += branchDx;
                    tz += branchDz;
                    if (placeLog(canvas, tx, yy, tz, trunkState, trunks)) {
                        ey = yy + 1;
                        hasEy = true;
                    }
                }
                yo++;
            }
            if (hasEy) attachments.add(new int[]{tx, ey, tz, 0, 0});
        }
        return attachments;
    }

    /** CherryTrunkPlacer (26.2): straight stem plus 1-3 curving branches. */
    private List<int[]> cherryTrunk(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random,
                                    int treeHeight, int ox, int oy, int oz, Block trunkState, Set<Pos> trunks) {
        JsonObject tp = config.getAsJsonObject("trunk_placer");
        placeBelowTrunkBlock(canvas, config, ox, oy - 1, oz);
        // branch_start_offset_from_top is a bare UniformInt (no dispatch "type" field)
        JsonObject bStart = tp.getAsJsonObject("branch_start_offset_from_top");
        int bsMin = bStart.get("min_inclusive").getAsInt();
        int bsMax = bStart.get("max_inclusive").getAsInt();
        int firstBranchOffset = Math.max(0, treeHeight - 1 + (random.nextInt(bsMax - bsMin + 1) + bsMin));
        // secondBranchStartOffsetFromTop = UniformInt.of(min, max - 1)
        int secondBranchOffset = Math.max(0, treeHeight - 1 + (random.nextInt((bsMax - 1) - bsMin + 1) + bsMin));
        if (secondBranchOffset >= firstBranchOffset) secondBranchOffset++;
        int branchCount = VFeature.sampleInt(tp.get("branch_count"), random);
        boolean hasMiddleBranch = branchCount == 3;
        boolean hasBothSideBranches = branchCount >= 2;
        int trunkHeight;
        if (hasMiddleBranch) {
            trunkHeight = treeHeight;
        } else if (hasBothSideBranches) {
            trunkHeight = Math.max(firstBranchOffset, secondBranchOffset) + 1;
        } else {
            trunkHeight = firstBranchOffset + 1;
        }
        for (int y = 0; y < trunkHeight; y++) {
            placeLog(canvas, ox, oy + y, oz, trunkState, trunks);
        }
        List<int[]> attachments = new ArrayList<>();
        if (hasMiddleBranch) attachments.add(new int[]{ox, oy + trunkHeight, oz, 0, 0});
        int treeDirIdx = random.nextInt(4);
        attachments.add(cherryBranch(canvas, tp, random, treeHeight, ox, oy, oz, trunkState, trunks,
                treeDirIdx, firstBranchOffset, firstBranchOffset < trunkHeight - 1));
        if (hasBothSideBranches) {
            attachments.add(cherryBranch(canvas, tp, random, treeHeight, ox, oy, oz, trunkState, trunks,
                    (treeDirIdx + 2) % 4, secondBranchOffset, secondBranchOffset < trunkHeight - 1));
        }
        return attachments;
    }

    /** CherryTrunkPlacer.generateBranch: horizontal run then a Manhattan-walk toward the branch end. */
    private int[] cherryBranch(VFeature.Canvas canvas, JsonObject tp, VFeature.XWorldgenRandom random,
                               int treeHeight, int ox, int oy, int oz, Block trunkState, Set<Pos> trunks,
                               int branchDirIdx, int offsetFromOrigin, boolean middleContinuesUpwards) {
        int bdx = HDIR[branchDirIdx][0], bdz = HDIR[branchDirIdx][1];
        String sidewaysAxis = hAxis(branchDirIdx);
        Block sideways = logWithAxis(trunkState, sidewaysAxis);
        int lx = ox, ly = oy + offsetFromOrigin, lz = oz;
        int branchEndOffset = treeHeight - 1 + VFeature.sampleInt(tp.get("branch_end_offset_from_top"), random);
        boolean extend = middleContinuesUpwards || branchEndOffset < offsetFromOrigin;
        int distanceToTrunk = VFeature.sampleInt(tp.get("branch_horizontal_length"), random) + (extend ? 1 : 0);
        int ex = ox + bdx * distanceToTrunk, eyEnd = oy + branchEndOffset, ez = oz + bdz * distanceToTrunk;
        int stepsHorizontally = extend ? 2 : 1;
        for (int i = 0; i < stepsHorizontally; i++) {
            lx += bdx;
            lz += bdz;
            placeLog(canvas, lx, ly, lz, sideways, trunks);
        }
        int vDir = eyEnd > ly ? 1 : -1;
        while (true) {
            int distance = Math.abs(lx - ex) + Math.abs(ly - eyEnd) + Math.abs(lz - ez);
            if (distance == 0) return new int[]{ex, eyEnd + 1, ez, 0, 0}; // branchEndPos.above()
            float chanceToGrowVertically = (float) Math.abs(eyEnd - ly) / distance;
            boolean growVertically = random.nextFloat() < chanceToGrowVertically;
            if (growVertically) {
                ly += vDir;
                placeLog(canvas, lx, ly, lz, trunkState, trunks);
            } else {
                lx += bdx;
                lz += bdz;
                placeLog(canvas, lx, ly, lz, sideways, trunks);
            }
        }
    }

    /** MegaJungleTrunkPlacer (26.2): GiantTrunkPlacer base plus radial log branches. */
    private List<int[]> megaJungleTrunk(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random,
                                        int treeHeight, int ox, int oy, int oz, Block trunkState, Set<Pos> trunks) {
        List<int[]> attachments = new ArrayList<>(
                giantTrunk(canvas, config, treeHeight, ox, oy, oz, trunkState, trunks));
        for (int branchHeight = treeHeight - 2 - random.nextInt(4);
             branchHeight > treeHeight / 2;
             branchHeight -= 2 + random.nextInt(4)) {
            float angle = random.nextFloat() * (float) (Math.PI * 2);
            int bx = 0, bz = 0;
            for (int b = 0; b < 5; b++) {
                bx = (int) (1.5F + VCarver.cos(angle) * b);
                bz = (int) (1.5F + VCarver.sin(angle) * b);
                placeLog(canvas, ox + bx, oy + branchHeight - 3 + b / 2, oz + bz, trunkState, trunks);
            }
            attachments.add(new int[]{ox + bx, oy + branchHeight, oz + bz, -2, 0});
        }
        return attachments;
    }

    /** BendingTrunkPlacer (26.2): vertical stem that bends horizontally near the top. */
    private List<int[]> bendingTrunk(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random,
                                     int treeHeight, int ox, int oy, int oz, Block trunkState, Set<Pos> trunks) {
        JsonObject tp = config.getAsJsonObject("trunk_placer");
        int minHeightForLeaves = tp.has("min_height_for_leaves") ? tp.get("min_height_for_leaves").getAsInt() : 1;
        int dirIdx = random.nextInt(4);
        int ddx = HDIR[dirIdx][0], ddz = HDIR[dirIdx][1];
        int logHeight = treeHeight - 1;
        int cx = ox, cy = oy, cz = oz;
        placeBelowTrunkBlock(canvas, config, ox, oy - 1, oz);
        List<int[]> foliagePoints = new ArrayList<>();
        for (int i = 0; i <= logHeight; i++) {
            if (i + 1 >= logHeight + random.nextInt(2)) {
                cx += ddx;
                cz += ddz;
            }
            if (validTreePos(canvas, cx, cy, cz)) placeLog(canvas, cx, cy, cz, trunkState, trunks);
            if (i >= minHeightForLeaves) foliagePoints.add(new int[]{cx, cy, cz, 0, 0});
            cy++;
        }
        int dirLength = VFeature.sampleInt(tp.get("bend_length"), random);
        for (int i = 0; i <= dirLength; i++) {
            if (validTreePos(canvas, cx, cy, cz)) placeLog(canvas, cx, cy, cz, trunkState, trunks);
            foliagePoints.add(new int[]{cx, cy, cz, 0, 0});
            cx += ddx;
            cz += ddz;
        }
        return foliagePoints;
    }

    private boolean makeLimb(VFeature.Canvas canvas, VFeature.XWorldgenRandom random,
                             int sx, int sy, int sz, int ex, int ey, int ez,
                             boolean doPlace, Block trunkState, Set<Pos> trunks) {
        if (!doPlace && sx == ex && sy == ey && sz == ez) return true;
        int dx = ex - sx, dy = ey - sy, dz = ez - sz;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        float fx = (float) dx / steps;
        float fy = (float) dy / steps;
        float fz = (float) dz / steps;
        for (int i = 0; i <= steps; i++) {
            int px = sx + VNoise.floor(0.5F + i * fx);
            int py = sy + VNoise.floor(0.5F + i * fy);
            int pz = sz + VNoise.floor(0.5F + i * fz);
            if (doPlace) {
                // log axis by dominant horizontal delta
                int xdiff = Math.abs(px - sx), zdiff = Math.abs(pz - sz);
                int maxdiff = Math.max(xdiff, zdiff);
                String axis = maxdiff > 0 ? (xdiff == maxdiff ? "x" : "z") : "y";
                Block log = trunkState.properties().containsKey("axis")
                        ? trunkState.withProperty("axis", axis) : trunkState;
                placeLog(canvas, px, py, pz, log, trunks);
            } else if (!isFree(canvas, px, py, pz)) {
                return false;
            }
        }
        return true;
    }

    private static float fancyTreeShape(int height, int y) {
        if (y < height * 0.3F) return -1.0F;
        float radius = height / 2.0F;
        float adjacent = radius - y;
        float distance = (float) Math.sqrt(radius * radius - adjacent * adjacent);
        if (adjacent == 0.0F) distance = radius;
        else if (Math.abs(adjacent) >= radius) return 0.0F;
        return distance * 0.5F;
    }

    // ================================================================== foliage placers

    private void createFoliage(VFeature.Canvas canvas, JsonObject placer, String type,
                               VFeature.XWorldgenRandom random, int treeHeight, int[] attachment,
                               int foliageHeight, int leafRadius, Block foliageState, Set<Pos> foliage) {
        int offset = VFeature.sampleInt(placer.get("offset"), random);
        int ax = attachment[0], ay = attachment[1], az = attachment[2], radiusOffset = attachment[3];
        boolean doubleTrunk = attachment.length > 4 && attachment[4] == 1;
        switch (type) {
            case "blob_foliage_placer" -> {
                for (int yo = offset; yo >= offset - foliageHeight; yo--) {
                    int r = Math.max(leafRadius + radiusOffset - 1 - yo / 2, 0);
                    placeLeavesRow(canvas, random, placer, ax, ay, az, r, yo, foliageState, foliage, type, doubleTrunk);
                }
            }
            case "fancy_foliage_placer" -> {
                for (int yo = offset; yo >= offset - foliageHeight; yo--) {
                    int r = leafRadius + (yo != offset && yo != offset - foliageHeight ? 1 : 0);
                    placeLeavesRow(canvas, random, placer, ax, ay, az, r, yo, foliageState, foliage, type, doubleTrunk);
                }
            }
            case "spruce_foliage_placer" -> {
                int currentRadius = random.nextInt(2);
                int maxRadius = 1;
                int minRadius = 0;
                for (int yo = offset; yo >= -foliageHeight; yo--) {
                    placeLeavesRow(canvas, random, placer, ax, ay, az, currentRadius, yo, foliageState, foliage, type, doubleTrunk);
                    if (currentRadius >= maxRadius) {
                        currentRadius = minRadius;
                        minRadius = 1;
                        maxRadius = Math.min(maxRadius + 1, leafRadius + radiusOffset);
                    } else {
                        currentRadius++;
                    }
                }
            }
            case "pine_foliage_placer" -> {
                int currentRadius = 0;
                for (int yo = offset; yo >= offset - foliageHeight; yo--) {
                    placeLeavesRow(canvas, random, placer, ax, ay, az, currentRadius, yo, foliageState, foliage, type, doubleTrunk);
                    if (currentRadius >= 1 && yo == offset - foliageHeight + 1) {
                        currentRadius--;
                    } else if (currentRadius < leafRadius + radiusOffset) {
                        currentRadius++;
                    }
                }
            }
            case "mega_pine_foliage_placer" -> {
                int prevRadius = 0;
                for (int yy = ay - foliageHeight + offset; yy <= ay + offset; yy++) {
                    int yoLocal = ay - yy;
                    int smoothRadius = leafRadius + radiusOffset
                            + VNoise.floor((float) yoLocal / foliageHeight * 3.5F);
                    int jaggedRadius = (yoLocal > 0 && smoothRadius == prevRadius && (yy & 1) == 0)
                            ? smoothRadius + 1 : smoothRadius;
                    placeLeavesRow(canvas, random, placer, ax, yy, az, jaggedRadius, 0, foliageState, foliage, type, doubleTrunk);
                    prevRadius = smoothRadius;
                }
            }
            case "dark_oak_foliage_placer" -> {
                // DarkOakFoliagePlacer.createFoliage: pos = attachment.above(offset)
                int py0 = ay + offset;
                if (doubleTrunk) {
                    placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius + 2, -1, foliageState, foliage, type, true);
                    placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius + 3, 0, foliageState, foliage, type, true);
                    placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius + 2, 1, foliageState, foliage, type, true);
                    if (random.nextBoolean()) {
                        placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius, 2, foliageState, foliage, type, true);
                    }
                } else {
                    placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius + 2, -1, foliageState, foliage, type, false);
                    placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius + 1, 0, foliageState, foliage, type, false);
                }
            }
            case "acacia_foliage_placer" -> {
                // AcaciaFoliagePlacer.createFoliage: pos = attachment.above(offset)
                int py0 = ay + offset;
                placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius + radiusOffset, -1 - foliageHeight, foliageState, foliage, type, doubleTrunk);
                placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius - 1, -foliageHeight, foliageState, foliage, type, doubleTrunk);
                placeLeavesRow(canvas, random, placer, ax, py0, az, leafRadius + radiusOffset - 1, 0, foliageState, foliage, type, doubleTrunk);
            }
            case "cherry_foliage_placer" -> {
                // CherryFoliagePlacer.createFoliage: pos = attachment.above(offset)
                int py0 = ay + offset;
                int currentRadius = leafRadius + radiusOffset - 1;
                placeLeavesRow(canvas, random, placer, ax, py0, az, currentRadius - 2, foliageHeight - 3, foliageState, foliage, type, doubleTrunk);
                placeLeavesRow(canvas, random, placer, ax, py0, az, currentRadius - 1, foliageHeight - 4, foliageState, foliage, type, doubleTrunk);
                for (int y = foliageHeight - 5; y >= 0; y--) {
                    placeLeavesRow(canvas, random, placer, ax, py0, az, currentRadius, y, foliageState, foliage, type, doubleTrunk);
                }
                placeLeavesRowHanging(canvas, random, placer, ax, py0, az, currentRadius, -1, foliageState, foliage, type, doubleTrunk);
                placeLeavesRowHanging(canvas, random, placer, ax, py0, az, currentRadius - 1, -2, foliageState, foliage, type, doubleTrunk);
            }
            case "bush_foliage_placer" -> {
                // BushFoliagePlacer.createFoliage: origin = attachment.pos() (no offset applied)
                for (int yo = offset; yo >= offset - foliageHeight; yo--) {
                    int r = leafRadius + radiusOffset - 1 - yo;
                    placeLeavesRow(canvas, random, placer, ax, ay, az, r, yo, foliageState, foliage, type, doubleTrunk);
                }
            }
            case "jungle_foliage_placer" -> {
                // MegaJungleFoliagePlacer.createFoliage
                int leafHeight = doubleTrunk ? foliageHeight : 1 + random.nextInt(2);
                for (int yo = offset; yo >= offset - leafHeight; yo--) {
                    int r = leafRadius + radiusOffset + 1 - yo;
                    placeLeavesRow(canvas, random, placer, ax, ay, az, r, yo, foliageState, foliage, type, doubleTrunk);
                }
            }
            case "random_spread_foliage_placer" -> {
                // RandomSpreadFoliagePlacer.createFoliage: origin = attachment.pos()
                int attempts = placer.get("leaf_placement_attempts").getAsInt();
                for (int i = 0; i < attempts; i++) {
                    int dx = random.nextInt(leafRadius) - random.nextInt(leafRadius);
                    int dy = random.nextInt(foliageHeight) - random.nextInt(foliageHeight);
                    int dz = random.nextInt(leafRadius) - random.nextInt(leafRadius);
                    tryPlaceLeaf(canvas, ax + dx, ay + dy, az + dz, foliageState, foliage);
                }
            }
            default -> { }
        }
    }

    private void placeLeavesRow(VFeature.Canvas canvas, VFeature.XWorldgenRandom random, JsonObject placer,
                                int ax, int ay, int az, int currentRadius, int yo,
                                Block foliageState, Set<Pos> foliage, String placerType, boolean doubleTrunk) {
        int off = doubleTrunk ? 1 : 0;
        for (int dx = -currentRadius; dx <= currentRadius + off; dx++) {
            for (int dz = -currentRadius; dz <= currentRadius + off; dz++) {
                if (shouldSkipLocationSigned(random, placer, dx, yo, dz, currentRadius, placerType, doubleTrunk)) continue;
                int px = ax + dx, py = ay + yo, pz = az + dz;
                tryPlaceLeaf(canvas, px, py, pz, foliageState, foliage);
            }
        }
    }

    /**
     * CherryFoliagePlacer.placeLeavesRowWithHangingLeavesBelow: places the row, then walks the
     * ring one block below it hanging leaves down where a leaf sits above (each hang is a
     * distManhattan(<7) gate + nextFloat probability draw — draw order matters for parity).
     */
    private void placeLeavesRowHanging(VFeature.Canvas canvas, VFeature.XWorldgenRandom random, JsonObject placer,
                                       int ax, int ay, int az, int currentRadius, int yRow,
                                       Block foliageState, Set<Pos> foliage, String placerType, boolean doubleTrunk) {
        placeLeavesRow(canvas, random, placer, ax, ay, az, currentRadius, yRow, foliageState, foliage, placerType, doubleTrunk);
        int off = doubleTrunk ? 1 : 0;
        float hangingChance = placer.get("hanging_leaves_chance").getAsFloat();
        float extChance = placer.get("hanging_leaves_extension_chance").getAsFloat();
        int logX = ax, logY = ay - 1, logZ = az; // origin.below()
        for (int alongIdx = 0; alongIdx < 4; alongIdx++) {
            int toIdx = (alongIdx + 1) % 4; // clockwise
            int offsetToEdge = hAxisPositive(toIdx) ? currentRadius + off : currentRadius;
            int px = ax + HDIR[toIdx][0] * offsetToEdge + HDIR[alongIdx][0] * (-currentRadius);
            int py = ay + (yRow - 1);
            int pz = az + HDIR[toIdx][1] * offsetToEdge + HDIR[alongIdx][1] * (-currentRadius);
            int offsetAlongEdge = -currentRadius;
            while (offsetAlongEdge < currentRadius + off) {
                boolean leavesAbove = foliage.contains(new Pos(px, py + 1, pz));
                if (leavesAbove && tryPlaceExtension(canvas, random, hangingChance, logX, logY, logZ, px, py, pz, foliageState, foliage)) {
                    tryPlaceExtension(canvas, random, extChance, logX, logY, logZ, px, py - 1, pz, foliageState, foliage);
                }
                offsetAlongEdge++;
                px += HDIR[alongIdx][0];
                pz += HDIR[alongIdx][1];
            }
        }
    }

    /** FoliagePlacer.tryPlaceExtension: Manhattan<7 gate, then a nextFloat probability draw. */
    private boolean tryPlaceExtension(VFeature.Canvas canvas, VFeature.XWorldgenRandom random, float chance,
                                      int logX, int logY, int logZ, int px, int py, int pz,
                                      Block foliageState, Set<Pos> foliage) {
        if (Math.abs(px - logX) + Math.abs(py - logY) + Math.abs(pz - logZ) >= 7) return false;
        if (random.nextFloat() > chance) return false;
        return tryPlaceLeaf(canvas, px, py, pz, foliageState, foliage);
    }

    /** FoliagePlacer.shouldSkipLocationSigned: base computes min|d|; DarkOak overrides with a signed test. */
    private boolean shouldSkipLocationSigned(VFeature.XWorldgenRandom random, JsonObject placer,
                                             int dx, int y, int dz, int currentRadius, String placerType, boolean doubleTrunk) {
        if (placerType.equals("dark_oak_foliage_placer")) {
            boolean base = y != 0 || !doubleTrunk
                    || (dx != -currentRadius && dx < currentRadius)
                    || (dz != -currentRadius && dz < currentRadius);
            return base ? shouldSkipLocationBase(random, placer, dx, y, dz, currentRadius, placerType, doubleTrunk) : true;
        }
        return shouldSkipLocationBase(random, placer, dx, y, dz, currentRadius, placerType, doubleTrunk);
    }

    private boolean shouldSkipLocationBase(VFeature.XWorldgenRandom random, JsonObject placer,
                                           int dx, int y, int dz, int currentRadius, String placerType, boolean doubleTrunk) {
        int minDx = doubleTrunk ? Math.min(Math.abs(dx), Math.abs(dx - 1)) : Math.abs(dx);
        int minDz = doubleTrunk ? Math.min(Math.abs(dz), Math.abs(dz - 1)) : Math.abs(dz);
        return shouldSkipLocation(random, placer, minDx, y, minDz, currentRadius, placerType, doubleTrunk);
    }

    private boolean shouldSkipLocation(VFeature.XWorldgenRandom random, JsonObject placer, int dx, int y, int dz,
                                       int currentRadius, String placerType, boolean doubleTrunk) {
        return switch (placerType) {
            case "blob_foliage_placer" ->
                    dx == currentRadius && dz == currentRadius && (random.nextInt(2) == 0 || y == 0);
            case "fancy_foliage_placer" -> {
                float fx = dx + 0.5F, fz = dz + 0.5F;
                yield fx * fx + fz * fz > (float) (currentRadius * currentRadius);
            }
            case "spruce_foliage_placer", "pine_foliage_placer" ->
                    dx == currentRadius && dz == currentRadius && currentRadius > 0;
            case "mega_pine_foliage_placer" ->
                    dx + dz >= 7 || dx * dx + dz * dz > currentRadius * currentRadius;
            case "bush_foliage_placer" ->
                    dx == currentRadius && dz == currentRadius && random.nextInt(2) == 0;
            case "jungle_foliage_placer" ->
                    dx + dz >= 7 || dx * dx + dz * dz > currentRadius * currentRadius;
            case "acacia_foliage_placer" ->
                    y == 0 ? (dx > 1 || dz > 1) && dx != 0 && dz != 0
                           : dx == currentRadius && dz == currentRadius && currentRadius > 0;
            case "dark_oak_foliage_placer" -> {
                if (y == -1 && !doubleTrunk) yield dx == currentRadius && dz == currentRadius;
                yield y == 1 ? dx + dz > currentRadius * 2 - 2 : false;
            }
            case "cherry_foliage_placer" -> {
                float wideBottom = placer.get("wide_bottom_layer_hole_chance").getAsFloat();
                float cornerHole = placer.get("corner_hole_chance").getAsFloat();
                if (y == -1 && (dx == currentRadius || dz == currentRadius) && random.nextFloat() < wideBottom) {
                    yield true;
                }
                boolean corner = dx == currentRadius && dz == currentRadius;
                boolean wide = currentRadius > 2;
                yield wide
                        ? corner || dx + dz > currentRadius * 2 - 2 && random.nextFloat() < cornerHole
                        : corner && random.nextFloat() < cornerHole;
            }
            default -> false;
        };
    }

    /** FoliagePlacer.tryPlaceLeaf: persistent leaves are never overwritten, and a leaf placed
     * into a water source gets waterlogged=true (vanilla's isFluidAtPosition source check). */
    private boolean tryPlaceLeaf(VFeature.Canvas canvas, int x, int y, int z, Block foliageState, Set<Pos> foliage) {
        Block existing = canvas.get(x, y, z);
        if (existing != null && "true".equals(existing.getProperty("persistent"))) return false;
        if (!validTreePos(canvas, x, y, z)) return false;
        Block state = foliageState;
        if (state.getProperty("waterlogged") != null
                && existing != null && existing.name().equals("minecraft:water")
                && "0".equals(existing.getProperty("level"))) {
            state = state.withProperty("waterlogged", "true");
        }
        canvas.set(x, y, z, state);
        foliage.add(new Pos(x, y, z));
        return true;
    }

    // ================================================================== fallen tree

    /** Plane.HORIZONTAL faces order {NORTH, EAST, SOUTH, WEST} as (dx,dz). */
    private static final int[][] HORIZONTAL = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * FallenTreeFeature — exact random-draw parity (this feature is a rare
     * selector branch, so any draw mismatch desyncs every later tree in the
     * count-loop): stump + stump decorators, then direction, logLength, start
     * offset, and — only when the whole log fits — the log decorators.
     */
    boolean placeFallen(VFeature.Canvas canvas, JsonObject config, VFeature.XWorldgenRandom random,
                        int ox, int oy, int oz) {
        Block trunkY = VSurface.parseBlockState(
                config.getAsJsonObject("trunk_provider").getAsJsonObject("state"));
        // placeStump: unconditional set at origin, then stump decorators
        canvas.set(ox, oy, oz, trunkY);
        java.util.LinkedHashSet<Pos> stump = new java.util.LinkedHashSet<>();
        stump.add(new Pos(ox, oy, oz));
        decorateFallenLogs(canvas, random, stump, config.getAsJsonArray("stump_decorators"));

        int di = random.nextInt(4);
        int[] dir = HORIZONTAL[di];
        int logLength = VFeature.sampleInt(config.get("log_length"), random) - 2;
        int off = 2 + random.nextInt(2);
        int lx = ox + dir[0] * off, ly = oy, lz = oz + dir[1] * off;
        // setGroundHeightForFallenLogStartPos: up 1, then down up to 6 until mayPlaceOn
        ly += 1;
        for (int i = 0; i < 6; i++) {
            if (fallenMayPlaceOn(canvas, lx, ly, lz)) break;
            ly--;
        }
        if (!canPlaceEntireFallenLog(canvas, logLength, lx, ly, lz, dir)) return true;

        // sideways axis for the fallen log
        String axis = dir[0] != 0 ? "x" : "z";
        Block sideways = trunkY.properties().containsKey("axis") ? trunkY.withProperty("axis", axis) : trunkY;
        java.util.LinkedHashSet<Pos> logs = new java.util.LinkedHashSet<>();
        int cx = lx, cy = ly, cz = lz;
        for (int i = 0; i < logLength; i++) {
            canvas.set(cx, cy, cz, sideways);
            logs.add(new Pos(cx, cy, cz));
            cx += dir[0];
            cz += dir[1];
        }
        decorateFallenLogs(canvas, random, logs, config.getAsJsonArray("log_decorators"));
        return true;
    }

    /** VegetationBlock-ish floor + solid-ground test (TreeFeature.validTreePos && isOverSolidGround). */
    private boolean fallenMayPlaceOn(VFeature.Canvas canvas, int x, int y, int z) {
        return validTreePos(canvas, x, y, z) && fallenSolidBelow(canvas, x, y, z);
    }

    private boolean fallenSolidBelow(VFeature.Canvas canvas, int x, int y, int z) {
        Block below = canvas.get(x, y - 1, z);
        if (below == null) return false;
        String n = below.name();
        if (n.equals("minecraft:water") || n.equals("minecraft:lava")) return false;
        // isFaceSturdy(UP): approximate as a non-replaceable, non-leaf, non-plant full block
        if (host.tag("minecraft:replaceable_by_trees").contains(n)) return false;
        if (n.endsWith("_leaves") || n.endsWith("_sapling") || n.endsWith("_log")) return false;
        return true;
    }

    private boolean canPlaceEntireFallenLog(VFeature.Canvas canvas, int logLength, int lx, int ly, int lz, int[] dir) {
        int gap = 0;
        int cx = lx, cz = lz;
        for (int i = 0; i < logLength; i++) {
            if (!validTreePos(canvas, cx, ly, cz)) return false;
            if (!fallenSolidBelow(canvas, cx, ly, cz)) {
                if (++gap > 2) return false;
            } else {
                gap = 0;
            }
            cx += dir[0];
            cz += dir[1];
        }
        return true;
    }

    private void decorateFallenLogs(VFeature.Canvas canvas, VFeature.XWorldgenRandom random,
                                    java.util.LinkedHashSet<Pos> logs, JsonArray decorators) {
        if (decorators == null) return;
        for (JsonElement d : decorators) {
            JsonObject dec = d.getAsJsonObject();
            switch (path(dec.get("type").getAsString())) {
                case "trunk_vine" -> trunkVine(canvas, random, logs);
                case "attached_to_logs" -> attachedToLogs(canvas, random, logs, dec);
                default -> { }
            }
        }
    }

    /** TrunkVineDecorator: 4 nextInt(3) per log (west, east, north, south), in HashSet order. */
    private void trunkVine(VFeature.Canvas canvas, VFeature.XWorldgenRandom random,
                           java.util.LinkedHashSet<Pos> logs) {
        for (Pos pos : hashOrder(logs)) {
            if (random.nextInt(3) > 0) placeVine(canvas, pos.x - 1, pos.y, pos.z, "east");
            if (random.nextInt(3) > 0) placeVine(canvas, pos.x + 1, pos.y, pos.z, "west");
            if (random.nextInt(3) > 0) placeVine(canvas, pos.x, pos.y, pos.z - 1, "south");
            if (random.nextInt(3) > 0) placeVine(canvas, pos.x, pos.y, pos.z + 1, "north");
        }
    }

    private void placeVine(VFeature.Canvas canvas, int x, int y, int z, String side) {
        if (canvas.get(x, y, z) == null) {
            canvas.set(x, y, z, Block.VINE.withProperty(side, "true"));
        }
    }

    /** AttachedToLogsDecorator: shuffle logs, then per log nextInt(dirs)+nextFloat (+weighted state on hit). */
    private void attachedToLogs(VFeature.Canvas canvas, VFeature.XWorldgenRandom random,
                                java.util.LinkedHashSet<Pos> logs, JsonObject dec) {
        JsonArray directions = dec.getAsJsonArray("directions");
        float probability = dec.get("probability").getAsFloat();
        JsonObject provider = dec.getAsJsonObject("block_provider");
        List<Pos> shuffled = new ArrayList<>(hashOrder(logs));
        for (int i = shuffled.size(); i > 1; i--) {
            int j = random.nextInt(i);
            Pos tmp = shuffled.get(j);
            shuffled.set(j, shuffled.get(i - 1));
            shuffled.set(i - 1, tmp);
        }
        for (Pos log : shuffled) {
            int di = random.nextInt(directions.size());
            int[] d = dirDelta(directions.get(di).getAsString());
            int px = log.x + d[0], py = log.y + d[1], pz = log.z + d[2];
            if (random.nextFloat() <= probability && canvas.get(px, py, pz) == null) {
                Block state = weightedState(provider, random);
                if (state != null) canvas.set(px, py, pz, state);
            }
        }
    }

    private static int[] dirDelta(String name) {
        return switch (name) {
            case "up" -> new int[]{0, 1, 0};
            case "down" -> new int[]{0, -1, 0};
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "west" -> new int[]{-1, 0, 0};
            case "east" -> new int[]{1, 0, 0};
            default -> new int[]{0, 1, 0};
        };
    }

    /** WeightedStateProvider.getState: one nextInt(totalWeight). */
    private Block weightedState(JsonObject provider, VFeature.XWorldgenRandom random) {
        JsonArray entries = provider.getAsJsonArray("entries");
        int total = 0;
        for (JsonElement e : entries) total += e.getAsJsonObject().get("weight").getAsInt();
        int r = random.nextInt(total);
        for (JsonElement e : entries) {
            JsonObject entry = e.getAsJsonObject();
            r -= entry.get("weight").getAsInt();
            if (r < 0) return VSurface.parseBlockState(entry.getAsJsonObject("data"));
        }
        return null;
    }

    /** java.util.HashSet iteration order (vanilla Pos.hashCode) for decorator loops. */
    private static List<Pos> hashOrder(java.util.LinkedHashSet<Pos> insertionOrdered) {
        java.util.HashSet<Pos> set = new java.util.HashSet<>(insertionOrdered);
        return new ArrayList<>(set);
    }

    // ================================================================== decorators

    private void beehive(VFeature.Canvas canvas, JsonObject dec, VFeature.XWorldgenRandom random,
                         List<Pos> logs, List<Pos> leaves, Set<Pos> decorations) {
        float probability = dec.get("probability").getAsFloat();
        if (logs.isEmpty()) return;
        if (random.nextFloat() >= probability) return;
        int hiveY = !leaves.isEmpty()
                ? Math.max(leaves.get(0).y - 1, logs.get(0).y + 1)
                : Math.min(logs.get(0).y + 1 + random.nextInt(3), logs.get(logs.size() - 1).y);
        // SPAWN_DIRECTIONS: horizontal minus SOUTH.opposite (NORTH); plane order: south, west, north, east -> filtered: south, west, east
        int[][] dirs = {{0, 1}, {-1, 0}, {1, 0}}; // south, west, east as (dx, dz)
        List<Pos> placements = new ArrayList<>();
        for (Pos log : logs) {
            if (log.y == hiveY) {
                for (int[] d : dirs) placements.add(new Pos(log.x + d[0], log.y, log.z + d[1]));
            }
        }
        if (placements.isEmpty()) return;
        // Util.shuffle
        for (int i = placements.size(); i > 1; i--) {
            int swapTo = random.nextInt(i);
            Pos tmp = placements.get(swapTo);
            placements.set(swapTo, placements.get(i - 1));
            placements.set(i - 1, tmp);
        }
        for (Pos pos : placements) {
            if (canvas.get(pos.x, pos.y, pos.z) == null && canvas.get(pos.x, pos.y, pos.z + 1) == null) {
                canvas.set(pos.x, pos.y, pos.z, Block.BEE_NEST.withProperty("facing", "south"));
                decorations.add(pos);
                int numBees = 2 + random.nextInt(2);
                for (int i = 0; i < numBees; i++) {
                    random.nextInt(599); // Occupant.create ticks-in-hive draw
                }
                break;
            }
        }
    }

    /**
     * PlaceOnGroundDecorator (leaf litter around forest trees): tries × [3
     * nextIntBetweenInclusive over the trunk-base bounding box + a weighted
     * state draw when the spot is open ground]. Exact draw parity here is what
     * keeps every later tree in the count-loop aligned with vanilla.
     */
    private void placeOnGround(VFeature.Canvas canvas, JsonObject dec, VFeature.XWorldgenRandom random,
                               List<Pos> logs, Set<Pos> decorations) {
        if (logs.isEmpty()) return;
        // PlaceOnGroundDecorator.CODEC defaults, omitted from 26.2's serialized data
        int radius = dec.has("radius") ? dec.get("radius").getAsInt() : 2;
        int height = dec.has("height") ? dec.get("height").getAsInt() : 1;
        int tries = dec.has("tries") ? dec.get("tries").getAsInt() : 128;
        JsonObject provider = dec.getAsJsonObject("block_state_provider");
        int minY = logs.get(0).y; // logsSorted is ascending by Y
        int minX = logs.get(0).x, maxX = minX, minZ = logs.get(0).z, maxZ = minZ;
        for (Pos p : logs) {
            if (p.y == minY) {
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minZ = Math.min(minZ, p.z);
                maxZ = Math.max(maxZ, p.z);
            }
        }
        int bMinX = minX - radius, bMaxX = maxX + radius;
        int bMinY = minY - height, bMaxY = minY + height;
        int bMinZ = minZ - radius, bMaxZ = maxZ + radius;
        for (int i = 0; i < tries; i++) {
            int x = random.nextIntBetweenInclusive(bMinX, bMaxX);
            int y = random.nextIntBetweenInclusive(bMinY, bMaxY);
            int z = random.nextIntBetweenInclusive(bMinZ, bMaxZ);
            // attemptToPlaceBlockAbove: air (or vine) above, solid-render below, and
            // the ground surface (MOTION_BLOCKING_NO_LEAVES ~ ocean_floor) at/below abovePos
            if (canvas.get(x, y + 1, z) == null
                    && isSolidRenderApprox(canvas.get(x, y, z))
                    && canvas.oceanFloorHeight(x, z) <= y + 1) {
                Block state = weightedState(provider, random);
                if (state != null) {
                    canvas.set(x, y + 1, z, state);
                    decorations.add(new Pos(x, y + 1, z));
                }
            }
        }
    }

    /** BlockStateBase.isSolidRender approximation: a full opaque ground block. */
    private boolean isSolidRenderApprox(Block b) {
        if (b == null) return false;
        String n = b.name();
        if (n.equals("minecraft:water") || n.equals("minecraft:lava")) return false;
        if (n.endsWith("_leaves") || n.equals("minecraft:leaf_litter")) return false;
        return !host.tag("minecraft:replaceable_by_trees").contains(n);
    }

    private void alterGround(VFeature.Canvas canvas, JsonObject dec, VFeature.XWorldgenRandom random,
                             List<Pos> logs, Set<Pos> decorations) {
        Block provider = ruleState(dec.getAsJsonObject("provider"), canvas, 0, 0, 0);
        if (provider == null || logs.isEmpty()) return;
        int minY = logs.get(0).y;
        for (Pos pos : logs) {
            if (pos.y != minY) continue;
            placeCircle(canvas, provider, pos.x - 1, pos.y, pos.z - 1, decorations);
            placeCircle(canvas, provider, pos.x + 2, pos.y, pos.z - 1, decorations);
            placeCircle(canvas, provider, pos.x - 1, pos.y, pos.z + 2, decorations);
            placeCircle(canvas, provider, pos.x + 2, pos.y, pos.z + 2, decorations);
            for (int i = 0; i < 5; i++) {
                int placement = random.nextInt(64);
                int xx = placement % 8;
                int zz = placement / 8;
                if (xx == 0 || xx == 7 || zz == 0 || zz == 7) {
                    placeCircle(canvas, provider, pos.x - 3 + xx, pos.y, pos.z - 3 + zz, decorations);
                }
            }
        }
    }

    private void placeCircle(VFeature.Canvas canvas, Block provider, int x, int y, int z, Set<Pos> decorations) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 || Math.abs(dz) != 2) {
                    placeBlockAt(canvas, provider, x + dx, y, z + dz, decorations);
                }
            }
        }
    }

    private void placeBlockAt(VFeature.Canvas canvas, Block provider, int x, int y, int z, Set<Pos> decorations) {
        for (int dy = 2; dy >= -3; dy--) {
            int py = y + dy;
            Block current = canvas.get(x, py, z);
            // vanilla AlterGroundDecorator.placeBlockAt: provider rule = replace grass/dirt (podzol provider rule-less)
            if (current != null && (current.name().equals("minecraft:grass_block") || current.name().equals("minecraft:dirt"))) {
                canvas.set(x, py, z, provider);
                decorations.add(new Pos(x, py, z));
                break;
            }
            if (current != null && dy < 0) break;
        }
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
