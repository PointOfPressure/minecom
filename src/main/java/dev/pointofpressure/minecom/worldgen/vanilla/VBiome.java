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
    private final Node root;
    private final ThreadLocal<Leaf> lastResult = new ThreadLocal<>();

    public VBiome(VDensity.Builder builder, JsonObject noiseRouter) {
        this.temperature = builder.build(noiseRouter.get("temperature"));
        this.humidity = builder.build(noiseRouter.get("vegetation"));
        this.continentalness = builder.build(noiseRouter.get("continents"));
        this.erosion = builder.build(noiseRouter.get("erosion"));
        this.depth = builder.build(noiseRouter.get("depth"));
        this.weirdness = builder.build(noiseRouter.get("ridges"));

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

    /** Biome id at quart coords (block >> 2): Climate.Sampler.sample + RTree search. */
    public String biomeAt(int quartX, int quartY, int quartZ) {
        int blockX = quartX << 2, blockY = quartY << 2, blockZ = quartZ << 2;
        boolean was = VDensity.cellMode();
        VDensity.cellModeRaw(false);
        long[] target = new long[7];
        try {
            target[0] = quantize(temperature.compute(blockX, blockY, blockZ));
            target[1] = quantize(humidity.compute(blockX, blockY, blockZ));
            target[2] = quantize(continentalness.compute(blockX, blockY, blockZ));
            target[3] = quantize(erosion.compute(blockX, blockY, blockZ));
            target[4] = quantize(depth.compute(blockX, blockY, blockZ));
            target[5] = quantize(weirdness.compute(blockX, blockY, blockZ));
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
