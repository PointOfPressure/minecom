package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Vanilla multi-noise biome source: samples the six climate density functions
 * at quart resolution, quantizes to vanilla's fixed-point space, and searches
 * the overworld parameter list (7593 entries, dumped verbatim from the
 * official jar) with an exact port of Climate.RTree — the tree build order,
 * pruning traversal and warm-start ThreadLocal all affect which biome wins
 * exact-tie boundary points, so brute-force argmin is NOT equivalent.
 */
public final class VBiome {
    private final VDensity.DF temperature, humidity, continentalness, erosion, depth, weirdness;
    // depth = add(y_clamped_gradient, <2D offset stack>) in the overworld data; the gradient is the
    // only y-dependent part, so the expensive offset half can be cached per column (see biomeAt).
    // Both null when the data's depth graph doesn't match that shape (then depth runs uncached).
    private final VDensity.DF depthGradient, depthRest;
    private final Node root;
    private final ThreadLocal<Leaf> lastResult = new ThreadLocal<>();

    /** Cached per-column climate state: the five y-independent quantized params + depth's 2D half. */
    private static final class ColumnClimate {
        long temperature, humidity, continentalness, erosion, weirdness;
        double depthRest;
    }

    private static final int COLUMN_CACHE_MAX = 4096;
    private final ThreadLocal<java.util.LinkedHashMap<Long, ColumnClimate>> columnCache =
            ThreadLocal.withInitial(() -> new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, ColumnClimate> eldest) {
                    return size() > COLUMN_CACHE_MAX;
                }
            });

    public VBiome(VDensity.Builder builder, JsonObject noiseRouter) {
        this.temperature = builder.build(noiseRouter.get("temperature"));
        this.humidity = builder.build(noiseRouter.get("vegetation"));
        this.continentalness = builder.build(noiseRouter.get("continents"));
        this.erosion = builder.build(noiseRouter.get("erosion"));
        this.depth = builder.build(noiseRouter.get("depth"));
        this.weirdness = builder.build(noiseRouter.get("ridges"));

        // Try to split depth into y-only gradient + y-independent rest for column caching.
        com.google.gson.JsonElement depthJson = resolveRefs(builder, noiseRouter.get("depth"));
        VDensity.DF gradient = null, rest = null;
        if (depthJson != null && depthJson.isJsonObject()) {
            JsonObject o = depthJson.getAsJsonObject();
            if (VDensity.path(o.get("type").getAsString()).equals("add")) {
                com.google.gson.JsonElement a1 = o.get("argument1"), a2 = o.get("argument2");
                com.google.gson.JsonElement r1 = resolveRefs(builder, a1), r2 = resolveRefs(builder, a2);
                if (isYGradient(r1)) {
                    gradient = builder.build(a1);
                    rest = builder.build(a2);
                } else if (isYGradient(r2)) {
                    gradient = builder.build(a2);
                    rest = builder.build(a1);
                }
            }
        }
        this.depthGradient = gradient;
        this.depthRest = rest;
        verifyColumnCacheAssumptions();

        JsonArray list;
        try (var in = VBiome.class.getResourceAsStream("/vanilla/biome_parameters_overworld.json")) {
            list = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
        } catch (Exception e) {
            throw new IllegalStateException("Missing biome parameters bundle", e);
        }
        List<Node> leaves = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            JsonArray e = list.get(i).getAsJsonArray();
            long[] space = new long[14]; // 7 parameters as min/max pairs
            for (int j = 0; j < 12; j++) space[j] = e.get(j).getAsLong();
            long offset = e.get(12).getAsLong();
            space[12] = offset;
            space[13] = offset;
            leaves.add(new Leaf(space, e.get(13).getAsString()));
        }
        this.root = build(leaves);
    }

    // ------------------------------------------------------------------ sampling

    /** Climate.quantizeCoord: float multiply then truncate. */
    private static long quantize(double value) {
        return (long) ((float) value * 10000.0F);
    }

    /** Follow string refs (named function ids) to the defining JSON; null if unresolvable. */
    private static com.google.gson.JsonElement resolveRefs(VDensity.Builder builder,
                                                           com.google.gson.JsonElement json) {
        int hops = 0;
        while (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()
                && hops++ < 16) {
            json = builder.namedFunctionJson(json.getAsString());
        }
        return json;
    }

    private static boolean isYGradient(com.google.gson.JsonElement json) {
        return json != null && json.isJsonObject() && json.getAsJsonObject().has("type")
                && VDensity.path(json.getAsJsonObject().get("type").getAsString()).equals("y_clamped_gradient");
    }

    /**
     * The column cache assumes the five non-depth climate functions are y-independent and that
     * depth == depthGradient + depthRest with depthRest y-independent. Those hold for the shipped
     * overworld data (y_scale=0 / constant shift_y everywhere); verify BITWISE at fixed probe
     * points so any future data change fails loudly at boot instead of silently corrupting biomes.
     */
    private void verifyColumnCacheAssumptions() {
        boolean was = VDensity.cellMode();
        VDensity.cellModeRaw(false);
        try {
            verifyColumnCacheProbes();
        } finally {
            VDensity.cellModeRaw(was);
        }
    }

    private void verifyColumnCacheProbes() {
        int[][] xzProbes = {{0, 0}, {4, -4}, {-64, 64}, {1024, -4096}, {-100000, 100000}};
        int[] yProbes = {-64, 0, 156, 316};
        for (int[] xz : xzProbes) {
            int x = xz[0], z = xz[1];
            double t0 = temperature.compute(x, yProbes[0], z);
            double h0 = humidity.compute(x, yProbes[0], z);
            double c0 = continentalness.compute(x, yProbes[0], z);
            double e0 = erosion.compute(x, yProbes[0], z);
            double w0 = weirdness.compute(x, yProbes[0], z);
            Double r0 = depthRest == null ? null : depthRest.compute(x, yProbes[0], z);
            for (int y : yProbes) {
                if (Double.doubleToLongBits(temperature.compute(x, y, z)) != Double.doubleToLongBits(t0)
                        || Double.doubleToLongBits(humidity.compute(x, y, z)) != Double.doubleToLongBits(h0)
                        || Double.doubleToLongBits(continentalness.compute(x, y, z)) != Double.doubleToLongBits(c0)
                        || Double.doubleToLongBits(erosion.compute(x, y, z)) != Double.doubleToLongBits(e0)
                        || Double.doubleToLongBits(weirdness.compute(x, y, z)) != Double.doubleToLongBits(w0)) {
                    throw new IllegalStateException("VBiome column cache: climate function is y-dependent at ("
                            + x + "," + y + "," + z + ") — worldgen data changed; remove the column cache");
                }
                if (r0 != null) {
                    double recomposed = depthGradient.compute(x, y, z) + depthRest.compute(x, y, z);
                    if (Double.doubleToLongBits(depthRest.compute(x, y, z)) != Double.doubleToLongBits(r0)
                            || Double.doubleToLongBits(recomposed) != Double.doubleToLongBits(depth.compute(x, y, z))) {
                        throw new IllegalStateException("VBiome column cache: depth decomposition mismatch at ("
                                + x + "," + y + "," + z + ") — worldgen data changed; remove the column cache");
                    }
                }
            }
        }
    }

    /** Murmur3 fmix64 — bijective, so packed column keys stay unique but hash well. */
    private static long mixColumnKey(long k) {
        k ^= k >>> 33;
        k *= 0xFF51AFD7ED558CCDL;
        k ^= k >>> 33;
        k *= 0xC4CEB9FE1A85EC53L;
        k ^= k >>> 33;
        return k;
    }

    /** Diagnostic: the raw (unquantized) 6 climate parameters at a block position. */
    public double[] rawParamsAt(int blockX, int blockY, int blockZ) {
        boolean was = VDensity.cellMode();
        VDensity.cellModeRaw(false);
        try {
            return new double[]{
                    temperature.compute(blockX, blockY, blockZ), humidity.compute(blockX, blockY, blockZ),
                    continentalness.compute(blockX, blockY, blockZ), erosion.compute(blockX, blockY, blockZ),
                    depth.compute(blockX, blockY, blockZ), weirdness.compute(blockX, blockY, blockZ)};
        } finally {
            VDensity.cellModeRaw(was);
        }
    }

    /**
     * Biome id at quart coords (block >> 2): Climate.Sampler.sample + RTree search.
     *
     * The five y-independent climate params (and depth's y-independent half, when the data's
     * depth graph decomposes) are cached per column — verified bit-identical to direct
     * evaluation at construction time — so a column's y-sweep pays the full climate graphs
     * once instead of per quart. The RTree search still runs once per call, preserving the
     * warm-start sequence exactly (it decides exact-tie boundary points).
     */
    public String biomeAt(int quartX, int quartY, int quartZ) {
        int blockX = quartX << 2, blockY = quartY << 2, blockZ = quartZ << 2;
        boolean was = VDensity.cellMode();
        VDensity.cellModeRaw(false);
        long[] target = new long[7];
        try {
            long colKey = mixColumnKey(((long) quartX << 32) ^ (quartZ & 0xFFFFFFFFL));
            var cache = columnCache.get();
            ColumnClimate col = cache.get(colKey);
            if (col == null) {
                col = new ColumnClimate();
                col.temperature = quantize(temperature.compute(blockX, blockY, blockZ));
                col.humidity = quantize(humidity.compute(blockX, blockY, blockZ));
                col.continentalness = quantize(continentalness.compute(blockX, blockY, blockZ));
                col.erosion = quantize(erosion.compute(blockX, blockY, blockZ));
                col.weirdness = quantize(weirdness.compute(blockX, blockY, blockZ));
                if (depthRest != null) col.depthRest = depthRest.compute(blockX, blockY, blockZ);
                cache.put(colKey, col);
            }
            target[0] = col.temperature;
            target[1] = col.humidity;
            target[2] = col.continentalness;
            target[3] = col.erosion;
            target[4] = depthRest != null
                    ? quantize(depthGradient.compute(blockX, blockY, blockZ) + col.depthRest)
                    : quantize(depth.compute(blockX, blockY, blockZ));
            target[5] = col.weirdness;
            target[6] = 0L;
        } finally {
            VDensity.cellModeRaw(was);
        }
        Leaf leaf = root.search(target, lastResult.get());
        lastResult.set(leaf);
        return leaf.biome;
    }

    // ------------------------------------------------------------------ RTree (exact port)

    /** Parameter.distance(long) over the packed [min,max] space. */
    private static long paramDistance(long min, long max, long target) {
        long above = target - max;
        long below = min - target;
        return above > 0L ? above : Math.max(below, 0L);
    }

    private abstract static sealed class Node permits Leaf, SubTree {
        final long[] space; // 7 params: [min0,max0, min1,max1, ...]

        Node(long[] space) {
            this.space = space;
        }

        long distance(long[] target) {
            long distance = 0L;
            for (int i = 0; i < 7; i++) {
                long d = paramDistance(space[i * 2], space[i * 2 + 1], target[i]);
                distance += d * d;
            }
            return distance;
        }

        abstract Leaf search(long[] target, Leaf candidate);
    }

    private static final class Leaf extends Node {
        final String biome;

        Leaf(long[] space, String biome) {
            super(space);
            this.biome = biome;
        }

        @Override
        Leaf search(long[] target, Leaf candidate) {
            return this;
        }
    }

    private static final class SubTree extends Node {
        final Node[] children;

        SubTree(List<Node> children) {
            super(buildParameterSpace(children));
            this.children = children.toArray(new Node[0]);
        }

        @Override
        Leaf search(long[] target, Leaf candidate) {
            long minDistance = candidate == null ? Long.MAX_VALUE : candidate.distance(target);
            Leaf closestLeaf = candidate;
            for (Node child : children) {
                long childDistance = child.distance(target);
                if (minDistance > childDistance) {
                    Leaf leaf = child.search(target, closestLeaf);
                    long leafDistance = child == leaf ? childDistance : leaf.distance(target);
                    if (minDistance > leafDistance) {
                        minDistance = leafDistance;
                        closestLeaf = leaf;
                    }
                }
            }
            return closestLeaf;
        }
    }

    private static long[] buildParameterSpace(List<Node> children) {
        long[] bounds = new long[14];
        Arrays.fill(bounds, Long.MIN_VALUE);
        boolean first = true;
        for (Node child : children) {
            for (int d = 0; d < 7; d++) {
                if (first) {
                    bounds[d * 2] = child.space[d * 2];
                    bounds[d * 2 + 1] = child.space[d * 2 + 1];
                } else {
                    bounds[d * 2] = Math.min(bounds[d * 2], child.space[d * 2]);
                    bounds[d * 2 + 1] = Math.max(bounds[d * 2 + 1], child.space[d * 2 + 1]);
                }
            }
            first = false;
        }
        return bounds;
    }

    private static Node build(List<Node> children) {
        if (children.size() == 1) return children.get(0);
        if (children.size() <= 6) {
            children.sort(Comparator.comparingLong(node -> {
                long totalMagnitude = 0L;
                for (int d = 0; d < 7; d++) {
                    totalMagnitude += Math.abs((node.space[d * 2] + node.space[d * 2 + 1]) / 2L);
                }
                return totalMagnitude;
            }));
            return new SubTree(children);
        }
        long minCost = Long.MAX_VALUE;
        int minDimension = -1;
        List<SubTree> minBuckets = null;
        for (int d = 0; d < 7; d++) {
            sort(children, d, false);
            List<SubTree> buckets = bucketize(children);
            long totalCost = 0L;
            for (SubTree bucket : buckets) totalCost += cost(bucket.space);
            if (minCost > totalCost) {
                minCost = totalCost;
                minDimension = d;
                minBuckets = buckets;
            }
        }
        List<Node> bucketNodes = new ArrayList<>(minBuckets);
        sort(bucketNodes, minDimension, true);
        List<Node> built = new ArrayList<>(bucketNodes.size());
        for (Node bucket : bucketNodes) {
            built.add(build(new ArrayList<>(Arrays.asList(((SubTree) bucket).children))));
        }
        return new SubTree(built);
    }

    private static void sort(List<? extends Node> children, int dimension, boolean absolute) {
        Comparator<Node> comparator = comparator(dimension, absolute);
        for (int d = 1; d < 7; d++) {
            comparator = comparator.thenComparing(comparator((dimension + d) % 7, absolute));
        }
        children.sort(comparator);
    }

    private static Comparator<Node> comparator(int dimension, boolean absolute) {
        return Comparator.comparingLong(node -> {
            long center = (node.space[dimension * 2] + node.space[dimension * 2 + 1]) / 2L;
            return absolute ? Math.abs(center) : center;
        });
    }

    private static List<SubTree> bucketize(List<? extends Node> nodes) {
        List<SubTree> buckets = new ArrayList<>();
        List<Node> children = new ArrayList<>();
        int expectedChildrenCount = (int) Math.pow(6.0, Math.floor(Math.log(nodes.size() - 0.01) / Math.log(6.0)));
        for (Node child : nodes) {
            children.add(child);
            if (children.size() >= expectedChildrenCount) {
                buckets.add(new SubTree(children));
                children = new ArrayList<>();
            }
        }
        if (!children.isEmpty()) buckets.add(new SubTree(children));
        return buckets;
    }

    private static long cost(long[] space) {
        long result = 0L;
        for (int d = 0; d < 7; d++) {
            result += Math.abs(space[d * 2 + 1] - space[d * 2]);
        }
        return result;
    }
}
