package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interpreter for vanilla's density-function JSON graphs (the data files that
 * define terrain since 1.18). Formulas ported from the decompiled reference.
 * Cache/interpolation markers evaluate at full resolution in this first pass —
 * per-block exact, whereas vanilla trilerps between cell corners (phase 2).
 */
public final class VDensity {
    private VDensity() {}

    public interface DF {
        double compute(int x, int y, int z);
    }

    // ------------------------------------------------------------------ build context

    public static final class Builder {
        private final Map<String, JsonElement> namedFunctions;
        private final Map<String, JsonObject> noiseParams;
        private final XRandom.Positional random;
        private final long seed;
        private final Map<String, VNoise.Normal> noiseCache = new ConcurrentHashMap<>();
        private final Map<String, DF> namedCache = new HashMap<>();
        private BlendedNoisePort blended;
        private VSurface.Simplex endIslandNoise;
        // noise-cell size in blocks (size_horizontal*4 x size_vertical*4); overworld defaults.
        int cellWidth = 4;
        int cellHeight = 8;

        /** Set the dimension's noise-cell block size before building the router (End = 8,4). */
        public void setCellSize(int cellWidth, int cellHeight) {
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
        }

        public Builder(Map<String, JsonElement> namedFunctions,
                       Map<String, JsonObject> noiseParams, long seed) {
            this.namedFunctions = namedFunctions;
            this.noiseParams = noiseParams;
            this.seed = seed;
            this.random = new XRandom(seed).forkPositional();
        }

        /** RandomState.getOrCreateNoise: instance per noise id, seeded fromHashOf(id). */
        VNoise.Normal noise(String id) {
            String full = id.contains(":") ? id : "minecraft:" + id;
            return noiseCache.computeIfAbsent(full, key -> {
                JsonObject params = noiseParams.get(VDensity.path(key));
                if (params == null) throw new IllegalStateException("Unknown noise " + key);
                int firstOctave = params.get("firstOctave").getAsInt();
                JsonArray ampsJson = params.getAsJsonArray("amplitudes");
                Double[] amps = new Double[ampsJson.size()];
                for (int i = 0; i < amps.length; i++) amps[i] = ampsJson.get(i).getAsDouble();
                return new VNoise.Normal(random.fromHashOf(key), firstOctave, List.of(amps));
            });
        }

        BlendedNoisePort blendedNoise(double xzScale, double yScale, double xzFactor,
                                      double yFactor, double smear) {
            if (blended == null) {
                blended = new BlendedNoisePort(random.fromHashOf("minecraft:terrain"),
                        xzScale, yScale, xzFactor, yFactor, smear);
            }
            return blended;
        }

        public DF named(String name) {
            String key = path(name);
            DF cached = namedCache.get(key);
            if (cached != null) return cached;
            JsonElement json = namedFunctions.get(key);
            if (json == null) throw new IllegalStateException("Unknown density function " + name);
            DF built = parse(json, this);
            namedCache.put(key, built);
            return built;
        }

        public DF build(JsonElement json) {
            return parse(json, this);
        }

        /** The seed-level positional random (vanilla RandomState.random) — for aquifer/ore forks. */
        public XRandom.Positional seedRandom() {
            return random;
        }

        /** Raw JSON of a named density function (for callers that need graph shape, e.g. VBiome). */
        JsonElement namedFunctionJson(String name) {
            return namedFunctions.get(path(name));
        }
    }

    static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ------------------------------------------------------------------ parser

    static DF parse(JsonElement json, Builder ctx) {
        if (json.isJsonPrimitive()) {
            if (json.getAsJsonPrimitive().isNumber()) {
                double value = json.getAsDouble();
                return (x, y, z) -> value;
            }
            return ctx.named(json.getAsString()); // reference to a named function
        }
        JsonObject o = json.getAsJsonObject();
        String type = path(o.get("type").getAsString());
        return switch (type) {
            case "constant" -> {
                double v = o.get("argument").getAsDouble();
                yield (x, y, z) -> v;
            }
            case "add" -> {
                DF a = parse(o.get("argument1"), ctx);
                DF b = parse(o.get("argument2"), ctx);
                yield (x, y, z) -> a.compute(x, y, z) + b.compute(x, y, z);
            }
            case "mul" -> {
                DF a = parse(o.get("argument1"), ctx);
                DF b = parse(o.get("argument2"), ctx);
                yield (x, y, z) -> {
                    double av = a.compute(x, y, z);
                    return av == 0 ? 0 : av * b.compute(x, y, z);
                };
            }
            case "min" -> {
                DF a = parse(o.get("argument1"), ctx);
                DF b = parse(o.get("argument2"), ctx);
                yield (x, y, z) -> Math.min(a.compute(x, y, z), b.compute(x, y, z));
            }
            case "max" -> {
                DF a = parse(o.get("argument1"), ctx);
                DF b = parse(o.get("argument2"), ctx);
                yield (x, y, z) -> Math.max(a.compute(x, y, z), b.compute(x, y, z));
            }
            case "abs" -> {
                DF a = parse(o.get("argument"), ctx);
                yield (x, y, z) -> Math.abs(a.compute(x, y, z));
            }
            case "square" -> {
                DF a = parse(o.get("argument"), ctx);
                yield (x, y, z) -> {
                    double v = a.compute(x, y, z);
                    return v * v;
                };
            }
            case "cube" -> {
                DF a = parse(o.get("argument"), ctx);
                yield (x, y, z) -> {
                    double v = a.compute(x, y, z);
                    return v * v * v;
                };
            }
            case "half_negative" -> {
                DF a = parse(o.get("argument"), ctx);
                yield (x, y, z) -> {
                    double v = a.compute(x, y, z);
                    return v > 0 ? v : v * 0.5;
                };
            }
            case "quarter_negative" -> {
                DF a = parse(o.get("argument"), ctx);
                yield (x, y, z) -> {
                    double v = a.compute(x, y, z);
                    return v > 0 ? v : v * 0.25;
                };
            }
            case "invert" -> {
                DF a = parse(o.get("argument"), ctx);
                yield (x, y, z) -> 1.0 / a.compute(x, y, z);
            }
            case "squeeze" -> {
                DF a = parse(o.get("argument"), ctx);
                yield (x, y, z) -> {
                    double c = clamp(a.compute(x, y, z), -1, 1);
                    return c / 2.0 - c * c * c / 24.0;
                };
            }
            case "clamp" -> {
                DF input = parse(o.get("input"), ctx);
                double min = o.get("min").getAsDouble();
                double max = o.get("max").getAsDouble();
                yield (x, y, z) -> clamp(input.compute(x, y, z), min, max);
            }
            case "y_clamped_gradient" -> {
                double fromY = o.get("from_y").getAsDouble();
                double toY = o.get("to_y").getAsDouble();
                double fromValue = o.get("from_value").getAsDouble();
                double toValue = o.get("to_value").getAsDouble();
                yield (x, y, z) -> clampedMap(y, fromY, toY, fromValue, toValue);
            }
            case "range_choice" -> {
                DF input = parse(o.get("input"), ctx);
                double min = o.get("min_inclusive").getAsDouble();
                double max = o.get("max_exclusive").getAsDouble();
                DF inRange = parse(o.get("when_in_range"), ctx);
                DF outRange = parse(o.get("when_out_of_range"), ctx);
                yield (x, y, z) -> {
                    double v = input.compute(x, y, z);
                    return v >= min && v < max ? inRange.compute(x, y, z) : outRange.compute(x, y, z);
                };
            }
            case "noise" -> {
                VNoise.Normal noise = ctx.noise(o.get("noise").getAsString());
                double xz = o.get("xz_scale").getAsDouble();
                double ys = o.get("y_scale").getAsDouble();
                yield (x, y, z) -> noise.getValue(x * xz, y * ys, z * xz);
            }
            case "shifted_noise" -> {
                VNoise.Normal noise = ctx.noise(o.get("noise").getAsString());
                double xz = o.get("xz_scale").getAsDouble();
                double ys = o.get("y_scale").getAsDouble();
                DF sx = parse(o.get("shift_x"), ctx);
                DF sy = parse(o.get("shift_y"), ctx);
                DF sz = parse(o.get("shift_z"), ctx);
                yield (x, y, z) -> noise.getValue(
                        x * xz + sx.compute(x, y, z),
                        y * ys + sy.compute(x, y, z),
                        z * xz + sz.compute(x, y, z));
            }
            case "shift_a" -> {
                VNoise.Normal noise = ctx.noise(o.get("argument").getAsString());
                yield (x, y, z) -> noise.getValue(x * 0.25, 0, z * 0.25) * 4.0;
            }
            case "shift_b" -> {
                VNoise.Normal noise = ctx.noise(o.get("argument").getAsString());
                yield (x, y, z) -> noise.getValue(z * 0.25, x * 0.25, 0) * 4.0;
            }
            case "weird_scaled_sampler" -> {
                DF input = parse(o.get("input"), ctx);
                VNoise.Normal noise = ctx.noise(o.get("noise").getAsString());
                boolean type1 = path(o.get("rarity_value_mapper").getAsString()).equals("type_1");
                yield (x, y, z) -> {
                    double rarity = type1
                            ? spaghettiRarity3D(input.compute(x, y, z))
                            : spaghettiRarity2D(input.compute(x, y, z));
                    return rarity * Math.abs(noise.getValue(x / rarity, y / rarity, z / rarity));
                };
            }
            case "interval_select" -> {
                // DensityFunctions.IntervalSelect (26.2): input picks functions[i] for the
                // first i with input < thresholds[i], else the last function. thresholds is
                // validated ascending with size == functions.size() - 1.
                DF input = parse(o.get("input"), ctx);
                var thresholdArr = o.get("thresholds").getAsJsonArray();
                double[] thresholds = new double[thresholdArr.size()];
                for (int i = 0; i < thresholds.length; i++) thresholds[i] = thresholdArr.get(i).getAsDouble();
                var functionArr = o.get("functions").getAsJsonArray();
                DF[] functions = new DF[functionArr.size()];
                for (int i = 0; i < functions.length; i++) functions[i] = parse(functionArr.get(i), ctx);
                yield (x, y, z) -> {
                    double v = input.compute(x, y, z);
                    for (int i = 0; i < thresholds.length; i++) {
                        if (v < thresholds[i]) return functions[i].compute(x, y, z);
                    }
                    return functions[functions.length - 1].compute(x, y, z);
                };
            }
            case "spline" -> buildSpline(o.get("spline"), ctx);
            case "old_blended_noise" -> {
                BlendedNoisePort noise = ctx.blendedNoise(
                        o.get("xz_scale").getAsDouble(), o.get("y_scale").getAsDouble(),
                        o.get("xz_factor").getAsDouble(), o.get("y_factor").getAsDouble(),
                        o.get("smear_scale_multiplier").getAsDouble());
                yield noise::compute;
            }
            // markers: pass-through in graph mode, vanilla cell semantics in chunk mode
            case "interpolated" -> new Interpolated(parse(o.get("argument"), ctx), ctx.cellWidth, ctx.cellHeight);
            case "flat_cache" -> new FlatCacheNode(parse(o.get("argument"), ctx));
            case "cache_2d" -> new Cache2D(parse(o.get("argument"), ctx));
            case "cache_once", "cache_all_in_cell" -> new CacheOnce(parse(o.get("argument"), ctx));
            case "blend_density" -> parse(o.get("argument"), ctx);
            case "blend_alpha" -> (x, y, z) -> 1.0;   // empty blender
            case "blend_offset" -> (x, y, z) -> 0.0;  // empty blender
            // real vanilla never serializes a "beardifier" node into the JSON graph — NoiseChunk
            // wraps final_density with DensityFunctions.add(finalDensity, BeardifierMarker.INSTANCE)
            // in code, so the real carve is applied in VanillaGen.generateData instead.
            case "beardifier" -> (x, y, z) -> 0.0;
            case "find_top_surface" -> {
                // vanilla scans down by cell_height until density > 0; the subtree is
                // evaluated through SinglePointContext, which bypasses all NoiseChunk
                // caching — so cell mode must be off for the inner evaluations.
                DF density = parse(o.get("density"), ctx);
                DF upperBound = parse(o.get("upper_bound"), ctx);
                int lowerBound = o.get("lower_bound").getAsInt();
                int cellHeight = o.get("cell_height").getAsInt();
                yield (x, y, z) -> {
                    boolean was = cellMode();
                    cellModeRaw(false);
                    try {
                        int topY = VNoise.floor(upperBound.compute(x, y, z) / cellHeight) * cellHeight;
                        if (topY <= lowerBound) return lowerBound;
                        for (int blockY = topY; blockY >= lowerBound; blockY -= cellHeight) {
                            if (density.compute(x, blockY, z) > 0.0) return blockY;
                        }
                        return lowerBound;
                    } finally {
                        cellModeRaw(was);
                    }
                };
            }
            case "end_islands" -> {
                // DensityFunctions.EndIslandDensityFunction: island simplex noise seeded
                // LegacyRandomSource(seed).consumeCount(17292) then SimplexNoise(random).
                if (ctx.endIslandNoise == null) {
                    VSurface.LegacyRandom r = new VSurface.LegacyRandom(ctx.seed);
                    for (int i = 0; i < 17292; i++) r.next(32);   // RandomSource.consumeCount(17292)
                    ctx.endIslandNoise = new VSurface.Simplex(r);
                }
                VSurface.Simplex noise = ctx.endIslandNoise;
                yield (x, y, z) -> (endHeightValue(noise, x / 8, z / 8) - 8.0) / 128.0;
            }
            default -> throw new IllegalStateException("Unhandled density function type " + type);
        };
    }

    /** EndIslandDensityFunction.getHeightValue — central island + scattered outer islands. */
    static float endHeightValue(VSurface.Simplex islandNoise, int sectionX, int sectionZ) {
        int chunkX = sectionX / 2;
        int chunkZ = sectionZ / 2;
        int subX = sectionX % 2;
        int subZ = sectionZ % 2;
        float doffs = 100.0F - (float) Math.sqrt((double) (sectionX * sectionX + sectionZ * sectionZ)) * 8.0F;
        doffs = clampF(doffs, -100.0F, 80.0F);
        for (int xo = -12; xo <= 12; xo++) {
            for (int zo = -12; zo <= 12; zo++) {
                long tcx = chunkX + xo;
                long tcz = chunkZ + zo;
                if (tcx * tcx + tcz * tcz > 4096L && islandNoise.getValue(tcx, tcz) < -0.9F) {
                    float islandSize = (Math.abs((float) tcx) * 3439.0F + Math.abs((float) tcz) * 147.0F) % 13.0F + 9.0F;
                    float xd = subX - xo * 2;
                    float zd = subZ - zo * 2;
                    float nd = 100.0F - (float) Math.sqrt((double) (xd * xd + zd * zd)) * islandSize;
                    nd = clampF(nd, -100.0F, 80.0F);
                    doffs = Math.max(doffs, nd);
                }
            }
        }
        return doffs;
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** End island terrain density at a block column (verified bit-exact vs vanilla EndIslandDensityFunction). */
    public static double testEndDensity(long seed, int blockX, int blockZ) {
        VSurface.LegacyRandom r = new VSurface.LegacyRandom(seed);
        for (int i = 0; i < 17292; i++) r.next(32);
        VSurface.Simplex noise = new VSurface.Simplex(r);
        return (endHeightValue(noise, blockX / 8, blockZ / 8) - 8.0) / 128.0;
    }

    // ------------------------------------------------------------------ cell mode (NoiseChunk semantics)

    /** Chunk generation enables this per thread; probes leave it off for pure-graph evaluation. */
    private static final ThreadLocal<Boolean> CELL_MODE = ThreadLocal.withInitial(() -> false);

    public static void setCellMode(boolean enabled) {
        CELL_MODE.set(enabled);
        if (enabled) GENERATION_EPOCH.set(GENERATION_EPOCH.get() + 1);
    }

    /** Current cell-mode flag (for temporary suspension, e.g. find_top_surface). */
    public static boolean cellMode() {
        return CELL_MODE.get();
    }

    /** Toggle cell mode WITHOUT bumping the epoch — restores mid-chunk without invalidating caches. */
    public static void cellModeRaw(boolean enabled) {
        CELL_MODE.set(enabled);
    }

    /** Bumped per chunk so node-local caches invalidate without clearing maps. */
    private static final ThreadLocal<Long> GENERATION_EPOCH = ThreadLocal.withInitial(() -> 0L);

    private static long pack(int a, int b, int c) {
        return ((long) (a & 0x3FFFFFF) << 38) | ((long) (b & 0xFFF) << 26) | (c & 0x3FFFFFF);
    }

    /**
     * Primitive open-addressing long->double map for the per-epoch marker caches. Pure storage
     * swap for the previous HashMap&lt;Long, Double&gt;: same keys, same values, no boxing, and
     * an fmix64-spread hash so packed coords can't degenerate buckets into tree bins.
     */
    static final class LongDoubleCache {
        long epoch = Long.MIN_VALUE;
        private long[] keys;
        private double[] vals;
        private byte[] used;
        private int mask, size;

        LongDoubleCache(int initialCapacity) {
            alloc(Integer.highestOneBit(Math.max(initialCapacity, 64) * 2 - 1)); // pow2 >= initialCapacity
        }

        private void alloc(int cap) {
            keys = new long[cap];
            vals = new double[cap];
            used = new byte[cap];
            mask = cap - 1;
            size = 0;
        }

        void clear() {
            java.util.Arrays.fill(used, (byte) 0);
            size = 0;
        }

        private static int spread(long k) {
            k ^= k >>> 33;
            k *= 0xFF51AFD7ED558CCDL;
            k ^= k >>> 33;
            k *= 0xC4CEB9FE1A85EC53L;
            k ^= k >>> 33;
            return (int) k;
        }

        /** Slot index if present, else ~insertionSlot (for insertAt). */
        int find(long key) {
            int i = spread(key) & mask;
            while (used[i] != 0) {
                if (keys[i] == key) return i;
                i = (i + 1) & mask;
            }
            return ~i;
        }

        double valueAt(int slot) {
            return vals[slot];
        }

        void insertAt(int slot, long key, double value) {
            used[slot] = 1;
            keys[slot] = key;
            vals[slot] = value;
            if (++size > (mask + 1) * 7 / 10) grow();
        }

        private void grow() {
            long[] oldKeys = keys;
            double[] oldVals = vals;
            byte[] oldUsed = used;
            alloc((mask + 1) << 1);
            for (int i = 0; i < oldUsed.length; i++) {
                if (oldUsed[i] != 0) {
                    int slot = find(oldKeys[i]);
                    used[~slot] = 1;
                    keys[~slot] = oldKeys[i];
                    vals[~slot] = oldVals[i];
                    size++;
                }
            }
        }
    }

    /**
     * Vanilla NoiseChunk.NoiseInterpolator: trilerp of the wrapped graph at cell corners.
     * Cell size = (size_horizontal*4) x (size_vertical*4) x (size_horizontal*4) — 4x8x4 for
     * the overworld, 8x4x8 for the End.
     */
    static final class Interpolated implements DF {
        private final DF wrapped;
        private final int cw, ch;   // cell width (horizontal), cell height (vertical)
        private final ThreadLocal<LongDoubleCache> corners =
                ThreadLocal.withInitial(() -> new LongDoubleCache(2048));
        private final ThreadLocal<CellMemo> lastCell = ThreadLocal.withInitial(CellMemo::new);

        /** The 8 corner values of the most recently touched cell — consecutive per-block
         *  calls stay inside one cell for cw*ch*cw blocks, skipping the map lookups. */
        private static final class CellMemo {
            long epoch = Long.MIN_VALUE;
            int x0 = Integer.MIN_VALUE, y0 = Integer.MIN_VALUE, z0 = Integer.MIN_VALUE;
            double c000, c100, c010, c110, c001, c101, c011, c111;
        }

        Interpolated(DF wrapped, int cellWidth, int cellHeight) {
            this.wrapped = wrapped;
            this.cw = cellWidth;
            this.ch = cellHeight;
        }

        @Override
        public double compute(int x, int y, int z) {
            if (!CELL_MODE.get()) return wrapped.compute(x, y, z);
            LongDoubleCache cache = corners.get();
            long ep = GENERATION_EPOCH.get();
            if (cache.epoch != ep) {
                cache.clear();
                cache.epoch = ep;
            }
            int x0 = Math.floorDiv(x, cw) * cw, y0 = Math.floorDiv(y, ch) * ch, z0 = Math.floorDiv(z, cw) * cw;
            double fx = Math.floorMod(x, cw) / (double) cw;
            double fy = Math.floorMod(y, ch) / (double) ch;
            double fz = Math.floorMod(z, cw) / (double) cw;
            CellMemo memo = lastCell.get();
            if (memo.epoch != ep || memo.x0 != x0 || memo.y0 != y0 || memo.z0 != z0) {
                memo.c000 = corner(cache, x0, y0, z0);
                memo.c100 = corner(cache, x0 + cw, y0, z0);
                memo.c010 = corner(cache, x0, y0 + ch, z0);
                memo.c110 = corner(cache, x0 + cw, y0 + ch, z0);
                memo.c001 = corner(cache, x0, y0, z0 + cw);
                memo.c101 = corner(cache, x0 + cw, y0, z0 + cw);
                memo.c011 = corner(cache, x0, y0 + ch, z0 + cw);
                memo.c111 = corner(cache, x0 + cw, y0 + ch, z0 + cw);
                memo.epoch = ep;
                memo.x0 = x0;
                memo.y0 = y0;
                memo.z0 = z0;
            }
            // vanilla per-block path is Mth.lerp3 (CacheAllInCell fill): X, then Y, then Z
            double x00 = memo.c000 + fx * (memo.c100 - memo.c000);
            double x10 = memo.c010 + fx * (memo.c110 - memo.c010);
            double x01 = memo.c001 + fx * (memo.c101 - memo.c001);
            double x11 = memo.c011 + fx * (memo.c111 - memo.c011);
            double xy0 = x00 + fy * (x10 - x00);
            double xy1 = x01 + fy * (x11 - x01);
            return xy0 + fz * (xy1 - xy0);
        }

        private double corner(LongDoubleCache cache, int x, int y, int z) {
            long key = pack(Math.floorDiv(x, cw), Math.floorDiv(y, ch), Math.floorDiv(z, cw));
            int slot = cache.find(key);
            if (slot >= 0) return cache.valueAt(slot);
            double value = wrapped.compute(x, y, z);
            cache.insertAt(~slot, key, value);
            return value;
        }
    }

    /** Vanilla NoiseChunk.FlatCache: per-quart (4x4 column) value sampled at (quartX<<2, 0, quartZ<<2). */
    static final class FlatCacheNode implements DF {
        private final DF wrapped;
        private final ThreadLocal<LongDoubleCache> cache =
                ThreadLocal.withInitial(() -> new LongDoubleCache(256));

        FlatCacheNode(DF wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public double compute(int x, int y, int z) {
            if (!CELL_MODE.get()) return wrapped.compute(x, y, z);
            LongDoubleCache map = cache.get();
            long ep = GENERATION_EPOCH.get();
            if (map.epoch != ep) {
                map.clear();
                map.epoch = ep;
            }
            int quartX = x >> 2, quartZ = z >> 2;
            long key = pack(quartX, 0, quartZ);
            int slot = map.find(key);
            if (slot >= 0) return map.valueAt(slot);
            double value = wrapped.compute(quartX << 2, 0, quartZ << 2);
            map.insertAt(~slot, key, value);
            return value;
        }
    }

    /** Vanilla NoiseChunk.Cache2D: memoizes the last column. */
    static final class Cache2D implements DF {
        private final DF wrapped;
        private final ThreadLocal<long[]> last = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE});
        private final ThreadLocal<double[]> value = ThreadLocal.withInitial(() -> new double[1]);

        Cache2D(DF wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public double compute(int x, int y, int z) {
            if (!CELL_MODE.get()) return wrapped.compute(x, y, z);
            long key = pack(x, 0, z);
            if (last.get()[0] == key) return value.get()[0];
            double v = wrapped.compute(x, y, z);
            last.get()[0] = key;
            value.get()[0] = v;
            return v;
        }
    }

    /** Vanilla NoiseChunk.CacheOnce: memoizes the last exact position. */
    static final class CacheOnce implements DF {
        private final DF wrapped;
        private final ThreadLocal<long[]> last = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE});
        private final ThreadLocal<double[]> value = ThreadLocal.withInitial(() -> new double[1]);

        CacheOnce(DF wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public double compute(int x, int y, int z) {
            if (!CELL_MODE.get()) return wrapped.compute(x, y, z);
            long key = pack(x, y, z);
            if (last.get()[0] == key) return value.get()[0];
            double v = wrapped.compute(x, y, z);
            last.get()[0] = key;
            value.get()[0] = v;
            return v;
        }
    }

    // ------------------------------------------------------------------ spline

    private record SplinePoint(float location, Spline value, float derivative) {}

    private interface Spline {
        float apply(int x, int y, int z);
    }

    private static DF buildSpline(JsonElement json, Builder ctx) {
        Spline spline = parseSpline(json, ctx);
        return (x, y, z) -> spline.apply(x, y, z);
    }

    private static Spline parseSpline(JsonElement json, Builder ctx) {
        if (json.isJsonPrimitive()) {
            float constant = json.getAsFloat();
            return (x, y, z) -> constant;
        }
        JsonObject o = json.getAsJsonObject();
        DF coordinate = parse(o.get("coordinate"), ctx);
        JsonArray pointsJson = o.getAsJsonArray("points");
        int n = pointsJson.size();
        float[] locations = new float[n];
        float[] derivatives = new float[n];
        Spline[] values = new Spline[n];
        for (int i = 0; i < n; i++) {
            JsonObject point = pointsJson.get(i).getAsJsonObject();
            locations[i] = point.get("location").getAsFloat();
            derivatives[i] = point.get("derivative").getAsFloat();
            values[i] = parseSpline(point.get("value"), ctx);
        }
        return (x, y, z) -> {
            float input = (float) coordinate.compute(x, y, z);
            int start = findIntervalStart(locations, input);
            int last = n - 1;
            if (start < 0) {
                return linearExtend(input, locations, values[0].apply(x, y, z), derivatives, 0);
            }
            if (start == last) {
                return linearExtend(input, locations, values[last].apply(x, y, z), derivatives, last);
            }
            float x1 = locations[start];
            float x2 = locations[start + 1];
            float t = (input - x1) / (x2 - x1);
            float y1 = values[start].apply(x, y, z);
            float y2 = values[start + 1].apply(x, y, z);
            float d1 = derivatives[start];
            float d2 = derivatives[start + 1];
            float a = d1 * (x2 - x1) - (y2 - y1);
            float b = -d2 * (x2 - x1) + (y2 - y1);
            return lerp(t, y1, y2) + t * (1.0F - t) * lerp(t, a, b);
        };
    }

    private static int findIntervalStart(float[] locations, float input) {
        int low = 0, high = locations.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (input < locations[mid]) high = mid;
            else low = mid + 1;
        }
        return low - 1;
    }

    private static float linearExtend(float input, float[] locations, float value,
                                      float[] derivatives, int index) {
        float derivative = derivatives[index];
        return derivative == 0 ? value : value + derivative * (input - locations[index]);
    }

    // ------------------------------------------------------------------ old blended noise

    /** BlendedNoise port: legacy 16+16+8 octave stacks, min/max limit blend by main noise. */
    public static final class BlendedNoisePort {
        private final LegacyPerlin minLimit;
        private final LegacyPerlin maxLimit;
        private final LegacyPerlin main;
        private final double xzMultiplier;
        private final double yMultiplier;
        private final double xzFactor;
        private final double yFactor;
        private final double smearScaleMultiplier;

        BlendedNoisePort(XRandom random, double xzScale, double yScale,
                         double xzFactor, double yFactor, double smearScaleMultiplier) {
            this.minLimit = new LegacyPerlin(random, 16);
            this.maxLimit = new LegacyPerlin(random, 16);
            this.main = new LegacyPerlin(random, 8);
            this.xzMultiplier = 684.412 * xzScale;
            this.yMultiplier = 684.412 * yScale;
            this.xzFactor = xzFactor;
            this.yFactor = yFactor;
            this.smearScaleMultiplier = smearScaleMultiplier;
        }

        public double compute(int blockX, int blockY, int blockZ) {
            double limitX = blockX * xzMultiplier;
            double limitY = blockY * yMultiplier;
            double limitZ = blockZ * xzMultiplier;
            double mainX = limitX / xzFactor;
            double mainY = limitY / yFactor;
            double mainZ = limitZ / xzFactor;
            double limitSmear = yMultiplier * smearScaleMultiplier;
            double mainSmear = limitSmear / yFactor;
            double blendMin = 0;
            double blendMax = 0;
            double mainValue = 0;
            double pow = 1.0;
            for (int i = 0; i < 8; i++) {
                VNoise.Improved noise = main.octave(i);
                if (noise != null) {
                    mainValue += noise.noise(VNoise.wrap(mainX * pow), VNoise.wrap(mainY * pow),
                            VNoise.wrap(mainZ * pow), mainSmear * pow, mainY * pow) / pow;
                }
                pow /= 2.0;
            }
            double factor = (mainValue / 10.0 + 1.0) / 2.0;
            boolean isMax = factor >= 1.0;
            boolean isMin = factor <= 0.0;
            pow = 1.0;
            for (int i = 0; i < 16; i++) {
                double wx = VNoise.wrap(limitX * pow);
                double wy = VNoise.wrap(limitY * pow);
                double wz = VNoise.wrap(limitZ * pow);
                double smear = limitSmear * pow;
                if (!isMax) {
                    VNoise.Improved noise = minLimit.octave(i);
                    if (noise != null) blendMin += noise.noise(wx, wy, wz, smear, limitY * pow) / pow;
                }
                if (!isMin) {
                    VNoise.Improved noise = maxLimit.octave(i);
                    if (noise != null) blendMax += noise.noise(wx, wy, wz, smear, limitY * pow) / pow;
                }
                pow /= 2.0;
            }
            return clampedLerp(blendMin / 512.0, blendMax / 512.0, clamp(factor, 0, 1)) / 128.0;
        }
    }

    /** Legacy PerlinNoise creation (useNewInitialization=false): sequential octaves from one source. */
    static final class LegacyPerlin {
        private final VNoise.Improved[] levels;

        LegacyPerlin(XRandom random, int octaves) {
            // octave set rangeClosed(-N+1, 0): zeroOctaveIndex = octaves-1 built first, then descending
            levels = new VNoise.Improved[octaves];
            levels[octaves - 1] = new VNoise.Improved(random);
            for (int i = octaves - 2; i >= 0; i--) {
                levels[i] = new VNoise.Improved(random);
            }
        }

        /** getOctaveNoise(i) = levels[length - 1 - i]. */
        VNoise.Improved octave(int i) {
            return levels[levels.length - 1 - i];
        }
    }

    // ------------------------------------------------------------------ rarity + math

    static double spaghettiRarity3D(double factor) {
        if (factor < -0.5) return 0.75;
        if (factor < 0.0) return 1.0;
        return factor < 0.5 ? 1.5 : 2.0;
    }

    static double spaghettiRarity2D(double factor) {
        if (factor < -0.75) return 0.5;
        if (factor < -0.5) return 0.75;
        if (factor < 0.5) return 1.0;
        return factor < 0.75 ? 2.0 : 3.0;
    }

    static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    static double clampedLerp(double from, double to, double t) {
        if (t < 0) return from;
        return t > 1 ? to : from + t * (to - from);
    }

    static double clampedMap(double value, double fromMin, double fromMax, double toMin, double toMax) {
        return clampedLerp(toMin, toMax, (value - fromMin) / (fromMax - fromMin));
    }
}
