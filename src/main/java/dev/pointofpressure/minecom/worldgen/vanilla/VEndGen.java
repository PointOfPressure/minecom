package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The End dimension generator. Reuses the vanilla density-function engine
 * ({@link VDensity}) driven by {@code noise_settings_end.json}: the end-island
 * simplex terrain (verified bit-exact) plus the shared {@code end/sloped_cheese}
 * graph, evaluated on the End's 8x4x8 noise cells. Solid where final density &gt; 0
 * (end_stone), air elsewhere — the floating islands of The End.
 *
 * <p>Biomes, the end-stone surface nuances, and features (chorus, obsidian spikes,
 * the exit portal/gateway) are layered on separately; this class is the terrain base.
 */
public final class VEndGen implements Generator {

    static final int MIN_Y = 0;
    static final int HEIGHT = 128;   // end.json noise height

    private final VDensity.DF finalDensity;
    private final VSurface.Simplex islandNoise;   // TheEndBiomeSource erosion = cache_2d(end_islands)

    public VEndGen(long seed) {
        Gson gson = new Gson();
        VSurface.LegacyRandom ir = new VSurface.LegacyRandom(seed);
        for (int i = 0; i < 17292; i++) ir.next(32);
        this.islandNoise = new VSurface.Simplex(ir);
        JsonObject dens = read(gson, "/vanilla/worldgen_density.json");
        JsonObject noise = read(gson, "/vanilla/worldgen_noise.json");
        JsonObject settings = read(gson, "/vanilla/noise_settings_end.json");

        Map<String, JsonElement> named = new HashMap<>();
        for (var e : dens.entrySet()) named.put(e.getKey(), e.getValue());
        Map<String, JsonObject> noises = new HashMap<>();
        for (var e : noise.entrySet()) noises.put(e.getKey(), e.getValue().getAsJsonObject());

        VDensity.Builder builder = new VDensity.Builder(named, noises, seed);
        builder.setCellSize(8, 4);   // End: size_horizontal 2 -> 8, size_vertical 1 -> 4
        JsonObject router = settings.getAsJsonObject("noise_router");
        this.finalDensity = builder.build(router.get("final_density"));
    }

    @Override
    public void generate(GenerationUnit unit) {
        var start = unit.absoluteStart();
        int baseX = start.blockX();
        int baseZ = start.blockZ();
        int minY = start.blockY();
        int maxY = minY + unit.size().blockY();
        var mod = unit.modifier();

        int lo = Math.max(minY, MIN_Y);
        int hi = Math.min(maxY, MIN_Y + HEIGHT);

        VDensity.setCellMode(true);
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int wx = baseX + x, wz = baseZ + z;
                    for (int y = lo; y < hi; y++) {
                        if (finalDensity.compute(wx, y, wz) > 0.0) {
                            mod.setBlock(wx, y, wz, Block.END_STONE);
                        }
                    }
                }
            }
        } finally {
            VDensity.setCellMode(false);
        }

        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                RegistryKey<Biome> biome = biomeKey(baseX + (qx << 2), baseZ + (qz << 2));
                for (int y = Math.max(lo, MIN_Y); y < hi; y += 4) {
                    mod.setBiome(baseX + (qx << 2), y, baseZ + (qz << 2), biome);
                }
            }
        }
    }

    /** TheEndBiomeSource.getNoiseBiome: center=the_end, else by the island-noise erosion value. */
    public String biomeAt(int blockX, int blockZ) {
        int chunkX = blockX >> 4, chunkZ = blockZ >> 4;
        if ((long) chunkX * chunkX + (long) chunkZ * chunkZ <= 4096L) return "minecraft:the_end";
        int wbx = (chunkX * 2 + 1) * 8, wbz = (chunkZ * 2 + 1) * 8;
        double h = (VDensity.endHeightValue(islandNoise, wbx / 8, wbz / 8) - 8.0) / 128.0;
        if (h > 0.25) return "minecraft:end_highlands";
        if (h >= -0.0625) return "minecraft:end_midlands";
        return h < -0.21875 ? "minecraft:small_end_islands" : "minecraft:end_barrens";
    }

    private RegistryKey<Biome> biomeKey(int blockX, int blockZ) {
        return switch (biomeAt(blockX, blockZ)) {
            case "minecraft:end_highlands" -> Biome.END_HIGHLANDS;
            case "minecraft:end_midlands" -> Biome.END_MIDLANDS;
            case "minecraft:small_end_islands" -> Biome.SMALL_END_ISLANDS;
            case "minecraft:end_barrens" -> Biome.END_BARRENS;
            default -> Biome.THE_END;
        };
    }

    /** Test hook: solid (density &gt; 0) at a block, in cell mode. */
    public boolean testSolid(int x, int y, int z) {
        VDensity.setCellMode(true);
        try {
            return finalDensity.compute(x, y, z) > 0.0;
        } finally {
            VDensity.setCellMode(false);
        }
    }

    private static JsonObject read(Gson gson, String resource) {
        try (var in = VEndGen.class.getResourceAsStream(resource)) {
            return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new IllegalStateException("Missing " + resource, e);
        }
    }
}
