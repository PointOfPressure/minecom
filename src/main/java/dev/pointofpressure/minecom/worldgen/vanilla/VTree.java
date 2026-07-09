package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vanilla TreeFeature: trunk placers (straight, fancy), foliage placers (blob,
 * fancy, spruce, pine), two/three-layer feature sizes, and the beehive +
 * alter_ground decorators — with vanilla's exact random-draw order, including
 * the HashSet-iteration-then-sort ordering of decorator position lists (our
 * Pos record reproduces BlockPos.hashCode so java.util.HashSet iterates
 * identically).
 */
final class VTree {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

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
        if (!trunkType.equals("straight_trunk_placer") && !trunkType.equals("fancy_trunk_placer")
                && !trunkType.equals("giant_trunk_placer")) return false;
        if (!Set.of("blob_foliage_placer", "fancy_foliage_placer", "spruce_foliage_placer",
                "pine_foliage_placer", "mega_pine_foliage_placer").contains(foliageType)) return false;

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
            default -> foliagePlacer.get("height").getAsInt(); // blob/fancy: constant, no draw
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

        if (trunkType.equals("straight_trunk_placer")) {
            attachments = straightTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
        } else if (trunkType.equals("giant_trunk_placer")) {
            attachments = giantTrunk(canvas, config, clippedHeight, ox, oy, oz, trunkState, trunks);
        } else {
            attachments = fancyTrunk(canvas, config, random, clippedHeight, ox, oy, oz, trunkState, trunks);
        }

        for (int[] att : attachments) {
            createFoliage(canvas, foliagePlacer, foliageType, random, treeHeight,
                    att, foliageHeight, leafRadius, foliageState, foliage);
        }

        if (trunks.isEmpty() && foliage.isEmpty()) return false;

        // decorators
        JsonArray decorators = config.getAsJsonArray("decorators");
        if (decorators != null && !decorators.isEmpty()) {
            List<Pos> logsSorted = hashOrderedSorted(trunks);
            List<Pos> leavesSorted = hashOrderedSorted(foliage);
            for (JsonElement d : decorators) {
                JsonObject dec = d.getAsJsonObject();
                switch (path(dec.get("type").getAsString())) {
                    case "beehive" -> beehive(canvas, dec, random, logsSorted, leavesSorted);
                    case "alter_ground" -> alterGround(canvas, dec, random, logsSorted);
                    case "place_on_ground" -> placeOnGround(canvas, dec, random, logsSorted);
                    default -> { }
                }
            }
        }
        return true;
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
                    placeLeavesRow(canvas, random, ax, ay, az, r, yo, foliageState, foliage, type, doubleTrunk);
                }
            }
            case "fancy_foliage_placer" -> {
                for (int yo = offset; yo >= offset - foliageHeight; yo--) {
                    int r = leafRadius + (yo != offset && yo != offset - foliageHeight ? 1 : 0);
                    placeLeavesRow(canvas, random, ax, ay, az, r, yo, foliageState, foliage, type, doubleTrunk);
                }
            }
            case "spruce_foliage_placer" -> {
                int currentRadius = random.nextInt(2);
                int maxRadius = 1;
                int minRadius = 0;
                for (int yo = offset; yo >= -foliageHeight; yo--) {
                    placeLeavesRow(canvas, random, ax, ay, az, currentRadius, yo, foliageState, foliage, type, doubleTrunk);
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
                    placeLeavesRow(canvas, random, ax, ay, az, currentRadius, yo, foliageState, foliage, type, doubleTrunk);
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
                    placeLeavesRow(canvas, random, ax, yy, az, jaggedRadius, 0, foliageState, foliage, type, doubleTrunk);
                    prevRadius = smoothRadius;
                }
            }
            default -> { }
        }
    }

    private void placeLeavesRow(VFeature.Canvas canvas, VFeature.XWorldgenRandom random,
                                int ax, int ay, int az, int currentRadius, int yo,
                                Block foliageState, Set<Pos> foliage, String placerType, boolean doubleTrunk) {
        int off = doubleTrunk ? 1 : 0;
        for (int dx = -currentRadius; dx <= currentRadius + off; dx++) {
            for (int dz = -currentRadius; dz <= currentRadius + off; dz++) {
                int minDx = doubleTrunk ? Math.min(Math.abs(dx), Math.abs(dx - 1)) : Math.abs(dx);
                int minDz = doubleTrunk ? Math.min(Math.abs(dz), Math.abs(dz - 1)) : Math.abs(dz);
                if (shouldSkipLocation(random, minDx, yo, minDz, currentRadius, placerType)) continue;
                int px = ax + dx, py = ay + yo, pz = az + dz;
                tryPlaceLeaf(canvas, px, py, pz, foliageState, foliage);
            }
        }
    }

    private boolean shouldSkipLocation(VFeature.XWorldgenRandom random, int dx, int y, int dz,
                                       int currentRadius, String placerType) {
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
            default -> false;
        };
    }

    private void tryPlaceLeaf(VFeature.Canvas canvas, int x, int y, int z, Block foliageState, Set<Pos> foliage) {
        if (!validTreePos(canvas, x, y, z)) return;
        canvas.set(x, y, z, foliageState);
        foliage.add(new Pos(x, y, z));
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
                         List<Pos> logs, List<Pos> leaves) {
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
    private void placeOnGround(VFeature.Canvas canvas, JsonObject dec, VFeature.XWorldgenRandom random, List<Pos> logs) {
        if (logs.isEmpty()) return;
        int radius = dec.get("radius").getAsInt();
        int height = dec.get("height").getAsInt();
        int tries = dec.get("tries").getAsInt();
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
                if (state != null) canvas.set(x, y + 1, z, state);
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

    private void alterGround(VFeature.Canvas canvas, JsonObject dec, VFeature.XWorldgenRandom random, List<Pos> logs) {
        Block provider = ruleState(dec.getAsJsonObject("provider"), canvas, 0, 0, 0);
        if (provider == null || logs.isEmpty()) return;
        int minY = logs.get(0).y;
        for (Pos pos : logs) {
            if (pos.y != minY) continue;
            placeCircle(canvas, provider, pos.x - 1, pos.y, pos.z - 1);
            placeCircle(canvas, provider, pos.x + 2, pos.y, pos.z - 1);
            placeCircle(canvas, provider, pos.x - 1, pos.y, pos.z + 2);
            placeCircle(canvas, provider, pos.x + 2, pos.y, pos.z + 2);
            for (int i = 0; i < 5; i++) {
                int placement = random.nextInt(64);
                int xx = placement % 8;
                int zz = placement / 8;
                if (xx == 0 || xx == 7 || zz == 0 || zz == 7) {
                    placeCircle(canvas, provider, pos.x - 3 + xx, pos.y, pos.z - 3 + zz);
                }
            }
        }
    }

    private void placeCircle(VFeature.Canvas canvas, Block provider, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 || Math.abs(dz) != 2) {
                    placeBlockAt(canvas, provider, x + dx, y, z + dz);
                }
            }
        }
    }

    private void placeBlockAt(VFeature.Canvas canvas, Block provider, int x, int y, int z) {
        for (int dy = 2; dy >= -3; dy--) {
            int py = y + dy;
            Block current = canvas.get(x, py, z);
            // vanilla AlterGroundDecorator.placeBlockAt: provider rule = replace grass/dirt (podzol provider rule-less)
            if (current != null && (current.name().equals("minecraft:grass_block") || current.name().equals("minecraft:dirt"))) {
                canvas.set(x, py, z, provider);
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
