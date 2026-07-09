package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla structure placement — layer 1: the deterministic "which chunk holds
 * which structure set" grid. Exact port of StructurePlacement /
 * RandomSpreadStructurePlacement / ConcentricRingsStructurePlacement, driven by
 * the same WorldgenRandom(LegacyRandomSource) seeding as vanilla. Biome
 * validation and piece assembly are later layers; this decides candidate chunks.
 */
public final class VStructures {

    // ------------------------------------------------------------------ model

    enum SpreadType { LINEAR, TRIANGULAR }

    /** DEFAULT=probabilityReducer, LEGACY_1=pillager, LEGACY_2=arbitrary-salt float, LEGACY_3=double. */
    enum FreqMethod { DEFAULT, LEGACY_1, LEGACY_2, LEGACY_3 }

    /** A random_spread placement (19 of the 20 vanilla sets). */
    static final class Placement {
        final int salt, spacing, separation;
        final SpreadType spreadType;
        final float frequency;
        final FreqMethod freqMethod;
        final int locateOffsetX, locateOffsetZ;
        final String exclusionSet;   // null unless an exclusion zone is declared
        final int exclusionChunks;

        Placement(int salt, int spacing, int separation, SpreadType spreadType, float frequency,
                  FreqMethod freqMethod, int locateOffsetX, int locateOffsetZ,
                  String exclusionSet, int exclusionChunks) {
            this.salt = salt;
            this.spacing = spacing;
            this.separation = separation;
            this.spreadType = spreadType;
            this.frequency = frequency;
            this.freqMethod = freqMethod;
            this.locateOffsetX = locateOffsetX;
            this.locateOffsetZ = locateOffsetZ;
            this.exclusionSet = exclusionSet;
            this.exclusionChunks = exclusionChunks;
        }
    }

    private static final int HIGHLY_ARBITRARY_RANDOM_SALT = 10387320;

    private final long seed;
    private final Map<String, Placement> sets = new HashMap<>();

    public VStructures(long seed) {
        this.seed = seed;
        load();
    }

    private void load() {
        Gson gson = new Gson();
        try (InputStream in = VStructures.class.getResourceAsStream("/vanilla/structure_sets.json")) {
            JsonObject root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            for (String name : root.keySet()) {
                JsonObject p = root.getAsJsonObject(name).getAsJsonObject("placement");
                String type = p.get("type").getAsString();
                if (!type.equals("minecraft:random_spread")) continue; // concentric_rings handled separately
                int salt = p.get("salt").getAsInt();
                int spacing = p.get("spacing").getAsInt();
                int separation = p.get("separation").getAsInt();
                SpreadType spread = SpreadType.LINEAR;
                if (p.has("spread_type")) {
                    spread = p.get("spread_type").getAsString().endsWith("triangular")
                            ? SpreadType.TRIANGULAR : SpreadType.LINEAR;
                }
                float freq = p.has("frequency") ? p.get("frequency").getAsFloat() : 1.0F;
                FreqMethod fm = FreqMethod.DEFAULT;
                if (p.has("frequency_reduction_method")) {
                    fm = switch (p.get("frequency_reduction_method").getAsString()) {
                        case "legacy_type_1" -> FreqMethod.LEGACY_1;
                        case "legacy_type_2" -> FreqMethod.LEGACY_2;
                        case "legacy_type_3" -> FreqMethod.LEGACY_3;
                        default -> FreqMethod.DEFAULT;
                    };
                }
                int offX = 0, offZ = 0;
                if (p.has("locate_offset")) {
                    var arr = p.getAsJsonArray("locate_offset");
                    offX = arr.get(0).getAsInt();
                    offZ = arr.get(2).getAsInt();
                }
                String exclSet = null;
                int exclChunks = 0;
                if (p.has("exclusion_zone")) {
                    JsonObject ez = p.getAsJsonObject("exclusion_zone");
                    exclSet = ez.get("other_set").getAsString();
                    exclChunks = ez.get("chunk_count").getAsInt();
                }
                sets.put(name, new Placement(salt, spacing, separation, spread, freq, fm,
                        offX, offZ, exclSet, exclChunks));
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load structure_sets.json", e);
        }
    }

    Placement placement(String set) {
        return sets.get(set);
    }

    // ------------------------------------------------------------------ random_spread grid

    /** RandomSpreadStructurePlacement.getPotentialStructureChunk → [gridChunkX, gridChunkZ]. */
    int[] getPotentialStructureChunk(Placement p, int sourceX, int sourceZ) {
        int spacedGridX = Math.floorDiv(sourceX, p.spacing);
        int spacedGridZ = Math.floorDiv(sourceZ, p.spacing);
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureWithSalt(seed, spacedGridX, spacedGridZ, p.salt);
        int limit = p.spacing - p.separation;
        int spreadX = evaluateSpread(p.spreadType, random, limit);
        int spreadZ = evaluateSpread(p.spreadType, random, limit);
        return new int[]{spacedGridX * p.spacing + spreadX, spacedGridZ * p.spacing + spreadZ};
    }

    private static int evaluateSpread(SpreadType type, VSurface.LegacyRandom random, int limit) {
        return switch (type) {
            case LINEAR -> random.nextInt(limit);
            case TRIANGULAR -> (random.nextInt(limit) + random.nextInt(limit)) / 2;
        };
    }

    private boolean isPlacementChunk(Placement p, int sourceX, int sourceZ) {
        int[] c = getPotentialStructureChunk(p, sourceX, sourceZ);
        return c[0] == sourceX && c[1] == sourceZ;
    }

    // ------------------------------------------------------------------ frequency reducers

    private boolean applyAdditionalChunkRestrictions(Placement p, int sourceX, int sourceZ) {
        if (!(p.frequency < 1.0F)) return true;
        return switch (p.freqMethod) {
            case DEFAULT -> probabilityReducer(p.salt, sourceX, sourceZ, p.frequency);
            case LEGACY_1 -> legacyPillagerReducer(sourceX, sourceZ, p.frequency);
            case LEGACY_2 -> legacyArbitrarySaltReducer(sourceX, sourceZ, p.frequency);
            case LEGACY_3 -> legacyDoubleReducer(sourceX, sourceZ, p.frequency);
        };
    }

    private boolean probabilityReducer(int salt, int sourceX, int sourceZ, float probability) {
        VSurface.LegacyRandom r = new VSurface.LegacyRandom(0L);
        r.setLargeFeatureWithSalt(seed, sourceX, sourceZ, salt);
        return r.nextFloat() < probability;
    }

    private boolean legacyArbitrarySaltReducer(int sourceX, int sourceZ, float probability) {
        VSurface.LegacyRandom r = new VSurface.LegacyRandom(0L);
        r.setLargeFeatureWithSalt(seed, sourceX, sourceZ, HIGHLY_ARBITRARY_RANDOM_SALT);
        return r.nextFloat() < probability;
    }

    private boolean legacyDoubleReducer(int sourceX, int sourceZ, float probability) {
        VSurface.LegacyRandom r = new VSurface.LegacyRandom(0L);
        r.setLargeFeatureSeed(seed, sourceX, sourceZ);
        return r.nextDouble() < probability;
    }

    private boolean legacyPillagerReducer(int sourceX, int sourceZ, float probability) {
        int cx = sourceX >> 4;
        int cz = sourceZ >> 4;
        VSurface.LegacyRandom r = new VSurface.LegacyRandom(((long) (cx ^ cz << 4)) ^ seed);
        r.next(32); // vanilla: random.nextInt() — a full unbounded 32-bit draw
        return r.nextInt((int) (1.0F / probability)) == 0;
    }

    // ------------------------------------------------------------------ public query

    /**
     * True if the given chunk is a candidate placement chunk for the named structure
     * set (grid hit + frequency reduction + exclusion zone). Biome validity is a
     * later layer, so this is "vanilla would attempt this structure set here".
     */
    public boolean isStructureChunk(String set, int chunkX, int chunkZ) {
        Placement p = sets.get(set);
        if (p == null) return false;
        return isPlacementChunk(p, chunkX, chunkZ)
                && applyAdditionalChunkRestrictions(p, chunkX, chunkZ)
                && applyInteractions(p, chunkX, chunkZ);
    }

    private boolean applyInteractions(Placement p, int sourceX, int sourceZ) {
        if (p.exclusionSet == null) return true;
        Placement other = sets.get(p.exclusionSet);
        if (other == null) return true;
        int n = p.exclusionChunks;
        for (int dz = -n; dz <= n; dz++) {
            for (int dx = -n; dx <= n; dx++) {
                if (isPlacementChunk(other, sourceX + dx, sourceZ + dz)) return false;
            }
        }
        return true;
    }
}
