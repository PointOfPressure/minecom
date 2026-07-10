package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The vanilla terrain shaper: evaluates the real overworld final_density graph
 * (bit-exact interpreter, NoiseChunk cell semantics) and the NoiseBasedAquifer
 * (underground water/lava pockets + barrier stone). Surface rules, carvers and
 * features are the remaining phases; this generator is the bit-parity testbed.
 */
public final class VanillaGen implements Generator {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private final VDensity.DF finalDensity;
    private final VAquifer.Context aquiferContext;
    private final VBiome biomes;
    private final VSurface surface;
    private final VCarver carver;
    private final VFeature features;
    private final VVeins veins;
    private final VStructureManager structures;
    private final VConcentricRings strongholds;
    private final int seaLevel;
    private final long seed;

    /** Undecorated (shape+surface+carve) chunk cache for cross-chunk decoration reads. */
    private final Map<Long, VSurface.ChunkData> chunkCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, VSurface.ChunkData> eldest) {
                    return size() > 512;
                }
            });

    public VanillaGen(long seed) {
        this.seed = seed;
        Gson gson = new Gson();
        JsonObject dens = read(gson, "/vanilla/worldgen_density.json");
        JsonObject noise = read(gson, "/vanilla/worldgen_noise.json");
        JsonObject settings = read(gson, "/vanilla/noise_settings_overworld.json");
        Map<String, JsonElement> named = new HashMap<>();
        for (var e : dens.entrySet()) named.put(e.getKey(), e.getValue());
        Map<String, JsonObject> noises = new HashMap<>();
        for (var e : noise.entrySet()) noises.put(e.getKey(), e.getValue().getAsJsonObject());
        VDensity.Builder builder = new VDensity.Builder(named, noises, seed);
        JsonObject router = settings.getAsJsonObject("noise_router");
        this.finalDensity = builder.build(router.get("final_density"));
        this.seaLevel = settings.get("sea_level").getAsInt();
        this.aquiferContext = new VAquifer.Context(builder, router, seaLevel);
        this.biomes = new VBiome(builder, router);
        this.surface = new VSurface(builder, aquiferContext, biomes,
                settings.get("surface_rule"), seed, seaLevel);
        this.carver = new VCarver(this, surface, seed);
        this.features = new VFeature(biomes, surface, seed);
        this.veins = new VVeins(builder, router);
        this.structures = new VStructureManager(seed, biomes, (x, z) -> topBlock(x, z)[0] + 1,
                (x, z) -> oceanFloorBlock(x, z)[0] + 1, surface, this::substanceAt, seaLevel);
        this.strongholds = new VConcentricRings(seed, biomes,
                VStructureManager.loadBiomes("stronghold_biased_to"), 32, 3, 128);
    }

    /** Ring-placed stronghold positions (bit-exact ConcentricRingsStructurePlacement port). */
    public VConcentricRings strongholds() {
        return strongholds;
    }

    public int seaLevel() {
        return seaLevel;
    }

    public long seed() {
        return seed;
    }

    /** Random-spread + jigsaw/single-template structure placement layer. */
    public VStructureManager structures() {
        return structures;
    }

    /** Cached undecorated chunk (shape + surface + carve). */
    public VSurface.ChunkData cachedData(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        VSurface.ChunkData data = chunkCache.get(key);
        if (data == null) {
            data = generateData(chunkX, chunkZ);
            chunkCache.put(key, data);
        }
        return data;
    }

    /**
     * Decorated output for one chunk: copy of the undecorated chunk plus every
     * write that this chunk and its 8 neighbors' decorations make into it. Each
     * neighbor is decorated on a scratch overlay (its own writes visible to
     * itself) and only writes landing in the target chunk are exported.
     */
    public VSurface.ChunkData decoratedData(int chunkX, int chunkZ) {
        VSurface.ChunkData base = cachedData(chunkX, chunkZ);
        VSurface.ChunkData out = new VSurface.ChunkData();
        System.arraycopy(base.blocks, 0, out.blocks, 0, base.blocks.length);
        System.arraycopy(base.heights, 0, out.heights, 0, base.heights.length);

        // structures: place every intersecting piece's blocks clipped to this chunk
        structures.placeInChunk(chunkX, chunkZ, new StructureCanvas(out, chunkX, chunkZ));

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int sourceX = chunkX + dx, sourceZ = chunkZ + dz;
                OverlayCanvas overlay = new OverlayCanvas();
                features.decorate(overlay, sourceX, sourceZ);
                for (var e : overlay.writes.entrySet()) {
                    long pos = e.getKey();
                    int x = (int) (pos >> 38);
                    int y = (int) (pos << 52 >> 52);
                    int z = (int) (pos << 26 >> 38);
                    if ((x >> 4) == chunkX && (z >> 4) == chunkZ) {
                        out.set(x & 15, y, z & 15, e.getValue() == OverlayCanvas.AIR_SENTINEL ? null : e.getValue());
                    }
                }
            }
        }
        return out;
    }

    /** Canvas over one target chunk's data for structure placement (world coords). */
    private final class StructureCanvas implements VStructureGen.Canvas {
        private final VSurface.ChunkData out;
        private final int cx, cz;
        StructureCanvas(VSurface.ChunkData out, int cx, int cz) { this.out = out; this.cx = cx; this.cz = cz; }

        @Override
        public Block get(int x, int y, int z) {
            if ((x >> 4) == cx && (z >> 4) == cz) return out.get(x & 15, y, z & 15);
            return cachedData(x >> 4, z >> 4).get(x & 15, y, z & 15); // neighbour base terrain (protected-blocks reads)
        }

        @Override
        public void set(int x, int y, int z, Block block) {
            if ((x >> 4) == cx && (z >> 4) == cz) {
                out.set(x & 15, y, z & 15, block == Block.AIR ? null : block);
            }
        }
    }

    /** Read-through canvas over the chunk cache with an in-memory write overlay. */
    private final class OverlayCanvas implements VFeature.Canvas {
        static final Block AIR_SENTINEL = Block.STRUCTURE_VOID;
        final Map<Long, Block> writes = new HashMap<>();
        final Map<Long, int[]> heightCache = new HashMap<>();

        private static long pack(int x, int y, int z) {
            return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
        }

        @Override
        public Block get(int x, int y, int z) {
            Block w = writes.get(pack(x, y, z));
            if (w != null) return w == AIR_SENTINEL ? null : w;
            return cachedData(x >> 4, z >> 4).get(x & 15, y, z & 15);
        }

        @Override
        public void set(int x, int y, int z, Block block) {
            writes.put(pack(x, y, z), block == null ? AIR_SENTINEL : block);
            heightCache.remove(((long) x << 32) | (z & 0xFFFFFFFFL));
        }

        private int[] heights(int x, int z) {
            return heightCache.computeIfAbsent(((long) x << 32) | (z & 0xFFFFFFFFL), k -> {
                int ocean = MIN_Y - 1, world = MIN_Y - 1;
                for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) {
                    Block b = get(x, y, z);
                    if (b == null) continue;
                    if (world == MIN_Y - 1) world = y;
                    if (b != Block.WATER && b != Block.LAVA) {
                        ocean = y;
                        break;
                    }
                }
                return new int[]{ocean + 1, world + 1};
            });
        }

        @Override
        public int oceanFloorHeight(int x, int z) {
            return heights(x, z)[0];
        }

        @Override
        public int worldSurfaceHeight(int x, int z) {
            return heights(x, z)[1];
        }
    }

    /** The multi-noise biome source (quart resolution). */
    public VBiome biomes() {
        return biomes;
    }

    /** The surface-rule engine. */
    public VSurface surface() {
        return surface;
    }

    /** Full pre-carver chunk generation: shape + aquifer + surface rules. */
    public VSurface.ChunkData generateData(int chunkX, int chunkZ) {
        VSurface.ChunkData data = new VSurface.ChunkData();
        VAquifer aquifer = aquiferFor(chunkX, chunkZ);
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        VDensity.setCellMode(true);
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int wx = baseX + x, wz = baseZ + z;
                    for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                        double density = finalDensity.compute(wx, y, wz);
                        switch (aquifer.computeSubstance(wx, y, wz, density)) {
                            case VAquifer.STONE -> {
                                Block vein = veins.veinAt(wx, y, wz);
                                data.set(x, y, z, vein != null ? vein : Block.STONE);
                            }
                            case VAquifer.WATER -> data.set(x, y, z, Block.WATER);
                            case VAquifer.LAVA -> data.set(x, y, z, Block.LAVA);
                            default -> { }
                        }
                    }
                }
            }
            surface.buildSurface(data, chunkX, chunkZ);
            carver.carve(data, chunkX, chunkZ, aquifer);
        } finally {
            VDensity.setCellMode(false);
        }
        return data;
    }

    private static JsonObject read(Gson gson, String resource) {
        try (var in = VanillaGen.class.getResourceAsStream(resource)) {
            return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new IllegalStateException("Missing " + resource, e);
        }
    }

    @Override
    public void generate(GenerationUnit unit) {
        var start = unit.absoluteStart();
        int baseX = start.blockX();
        int baseZ = start.blockZ();
        int minY = start.blockY();
        int maxY = minY + unit.size().blockY();
        var mod = unit.modifier();

        VSurface.ChunkData data = decoratedData(baseX >> 4, baseZ >> 4);
        int lo = Math.max(minY, MIN_Y), hi = Math.min(maxY, MIN_Y + HEIGHT);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = lo; y < hi; y++) {
                    Block block = data.get(x, y, z);
                    if (block != null) mod.setBlock(baseX + x, y, baseZ + z, block);
                }
            }
        }

        // per-quart multi-noise biomes (client colors + spawning rules)
        VDensity.setCellMode(false);
        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int y = lo; y < hi; y += 4) {
                    String biome = biomes.biomeAt((baseX >> 2) + qx, y >> 2, (baseZ >> 2) + qz);
                    mod.setBiome(baseX + (qx << 2), y, baseZ + (qz << 2),
                            net.minestom.server.registry.RegistryKey.unsafeOf(biome));
                }
            }
        }
    }

    /** Per-chunk aquifer instance (grid caches are chunk-local, like vanilla). */
    public VAquifer aquiferFor(int chunkX, int chunkZ) {
        return new VAquifer(aquiferContext, chunkX, chunkZ, MIN_Y, HEIGHT);
    }

    /** Solid-mask sample for the parity diff. */
    public boolean solidAt(int x, int y, int z) {
        return finalDensity.compute(x, y, z) > 0;
    }

    /** Raw final density for bit-exact parity probes. */
    public double densityAt(int x, int y, int z) {
        return finalDensity.compute(x, y, z);
    }

    /** Approximate surface (find_top_surface router function), no per-block work. */
    public int preliminarySurface(int x, int z) {
        return aquiferContext.preliminarySurfaceLevel(x, z);
    }

    /** Exact top non-air block: returns {y, substance} (VAquifer codes), or {MIN_Y-1, AIR}. */
    public int[] topBlock(int x, int z) {
        VAquifer aquifer = aquiferFor(x >> 4, z >> 4);
        VDensity.setCellMode(true);
        try {
            for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) {
                int sub = aquifer.computeSubstance(x, y, z, finalDensity.compute(x, y, z));
                if (sub != VAquifer.AIR) return new int[]{y, sub};
            }
        } finally {
            VDensity.setCellMode(false);
        }
        return new int[]{MIN_Y - 1, VAquifer.AIR};
    }

    /**
     * OCEAN_FLOOR_WG-equivalent: exact top SOLID block (continues past AIR/WATER/LAVA,
     * unlike topBlock/WORLD_SURFACE_WG which stops at any non-air substance including
     * water). Returns {y, substance}, or {MIN_Y-1, AIR}.
     */
    public int[] oceanFloorBlock(int x, int z) {
        VAquifer aquifer = aquiferFor(x >> 4, z >> 4);
        VDensity.setCellMode(true);
        try {
            for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) {
                int sub = aquifer.computeSubstance(x, y, z, finalDensity.compute(x, y, z));
                if (sub != VAquifer.AIR && sub != VAquifer.WATER && sub != VAquifer.LAVA) return new int[]{y, sub};
            }
        } finally {
            VDensity.setCellMode(false);
        }
        return new int[]{MIN_Y - 1, VAquifer.AIR};
    }

    /** Raw substance (VAquifer code: STONE/AIR/WATER/LAVA) at an exact position, no scan. */
    public int substanceAt(int x, int y, int z) {
        VAquifer aquifer = aquiferFor(x >> 4, z >> 4);
        VDensity.setCellMode(true);
        try {
            return aquifer.computeSubstance(x, y, z, finalDensity.compute(x, y, z));
        } finally {
            VDensity.setCellMode(false);
        }
    }
}
