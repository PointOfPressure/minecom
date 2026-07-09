package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A vanilla structure processor list (rule / protected_blocks / block_rot), applied
 * per placed block. Reimplemented from the algorithm. All processor randomness is
 * position-seeded — {@code RandomSource.create(Mth.getSeed(pos))} — so each block is
 * decided independently by its world coordinates (no shared stream, no ordering).
 */
public final class VProcessors {

    /** Reads the world block at a position (for location/protected predicates). */
    public interface Sink { Block get(int x, int y, int z); }

    private interface Proc {
        /** Return the output block, or null to drop the block. `original` = unrotated template state. */
        Block apply(Block current, Block original, int wx, int wy, int wz, Sink sink);
    }

    private final List<Proc> procs;

    private VProcessors(List<Proc> procs) { this.procs = procs; }

    public Block apply(Block templateState, int wx, int wy, int wz, Sink sink) {
        Block cur = templateState;
        for (Proc p : procs) {
            cur = p.apply(cur, templateState, wx, wy, wz, sink);
            if (cur == null) return null;
        }
        return cur;
    }

    // ----------------------------------------------------------------- loading

    private static final Map<String, VProcessors> CACHE = new HashMap<>();
    private static final Gson GSON = new Gson();

    public static synchronized VProcessors load(String name) {
        VProcessors v = CACHE.get(name);
        if (v == null) { v = read(name); CACHE.put(name, v); }
        return v;
    }

    private static VProcessors read(String name) {
        String path = "/vanilla/processor_list/" + name.substring(name.indexOf(':') + 1) + ".json";
        try (InputStream in = VProcessors.class.getResourceAsStream(path)) {
            if (in == null) return new VProcessors(List.of());
            JsonObject root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            List<Proc> procs = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("processors")) {
                Proc p = parseProc(el.getAsJsonObject());
                if (p != null) procs.add(p);
            }
            return new VProcessors(procs);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load processor_list " + path, e);
        }
    }

    private static Proc parseProc(JsonObject o) {
        String type = o.get("processor_type").getAsString();
        switch (type) {
            case "minecraft:rule" -> {
                List<Rule> rules = new ArrayList<>();
                for (JsonElement r : o.getAsJsonArray("rules")) rules.add(parseRule(r.getAsJsonObject()));
                return (cur, orig, wx, wy, wz, sink) -> {
                    VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz));
                    Block world = sink.get(wx, wy, wz);
                    for (Rule rule : rules) {
                        if (rule.input.test(cur, rng) && rule.loc.test(world, rng)
                                && (rule.pos == null || rule.pos.test(wx, wy, wz))) {
                            return rule.output;
                        }
                    }
                    return cur;
                };
            }
            case "minecraft:protected_blocks" -> {
                Set<String> tag = resolveTag(o.get("value").getAsString());
                return (cur, orig, wx, wy, wz, sink) -> tag.contains(id(sink.get(wx, wy, wz))) ? null : cur;
            }
            case "minecraft:block_rot" -> {
                float integrity = o.get("integrity").getAsFloat();
                Set<String> rottable = o.has("rottable_blocks") ? resolveTag(o.get("rottable_blocks").getAsString()) : null;
                return (cur, orig, wx, wy, wz, sink) -> {
                    boolean rotCandidate = rottable == null || rottable.contains(id(orig));
                    if (!rotCandidate) return cur;
                    VSurface.LegacyRandom rng = new VSurface.LegacyRandom(XRandom.blockSeed(wx, wy, wz));
                    return rng.nextFloat() <= integrity ? cur : null;
                };
            }
            case "minecraft:block_ignore", "minecraft:jigsaw_replacement", "minecraft:gravity",
                 "minecraft:blackstone_replace", "minecraft:lava_submerged_block", "minecraft:capped" -> {
                // block_ignore/jigsaw handled by the placement driver; others unused by the modelled
                // structures (capped wraps a delegate — TODO if a modelled list needs it).
                return null;
            }
            default -> throw new IllegalStateException("unsupported processor " + type);
        }
    }

    // ----------------------------------------------------------------- rules

    private record Rule(Predicate input, Predicate loc, PosPredicate pos, Block output) {}

    private interface Predicate { boolean test(Block state, VSurface.LegacyRandom rng); }
    private interface PosPredicate { boolean test(int x, int y, int z); }

    private static Rule parseRule(JsonObject r) {
        Predicate input = parsePredicate(r.getAsJsonObject("input_predicate"));
        Predicate loc = parsePredicate(r.getAsJsonObject("location_predicate"));
        PosPredicate pos = r.has("position_predicate") ? parsePosPredicate(r.getAsJsonObject("position_predicate")) : null;
        Block output = parseBlock(r.getAsJsonObject("output_state"));
        return new Rule(input, loc, pos, output);
    }

    private static Predicate parsePredicate(JsonObject p) {
        String type = p.get("predicate_type").getAsString();
        switch (type) {
            case "minecraft:always_true" -> { return (s, rng) -> true; }
            case "minecraft:random_block_match" -> {
                String block = p.get("block").getAsString();
                float prob = p.get("probability").getAsFloat();
                return (s, rng) -> id(s).equals(block) && rng.nextFloat() < prob;   // draw only when block matches
            }
            case "minecraft:block_match" -> {
                String block = p.get("block").getAsString();
                return (s, rng) -> id(s).equals(block);
            }
            case "minecraft:blockstate_match" -> {
                Block want = parseBlock(p.getAsJsonObject("block_state"));
                return (s, rng) -> s.compare(want, Block.Comparator.STATE);
            }
            case "minecraft:tag_match" -> {
                Set<String> tag = resolveTag(p.get("tag").getAsString());
                return (s, rng) -> tag.contains(id(s));
            }
            case "minecraft:random_blockstate_match" -> {
                Block want = parseBlock(p.getAsJsonObject("block_state"));
                float prob = p.get("probability").getAsFloat();
                return (s, rng) -> s.compare(want, Block.Comparator.STATE) && rng.nextFloat() < prob;
            }
            default -> throw new IllegalStateException("unsupported predicate " + type);
        }
    }

    private static PosPredicate parsePosPredicate(JsonObject p) {
        String type = p.get("predicate_type").getAsString();
        if (type.equals("minecraft:axis_aligned_linear_pos")) {
            // rare (1 use); a deterministic position ramp — approximate as always true so it never
            // silently drops blocks. TODO exact if a modelled structure depends on it.
            return (x, y, z) -> true;
        }
        return (x, y, z) -> true;
    }

    // ----------------------------------------------------------------- helpers

    static String id(Block b) { return b == null ? "minecraft:air" : b.key().asString(); }

    static Block parseBlock(JsonObject state) {
        String name = state.get("Name").getAsString();
        Block b = Block.fromKey(name);
        if (b == null) b = Block.AIR;
        if (state.has("Properties")) {
            Map<String, String> props = new HashMap<>();
            for (var e : state.getAsJsonObject("Properties").entrySet()) props.put(e.getKey(), e.getValue().getAsString());
            b = b.withProperties(props);
        }
        return b;
    }

    // ----------------------------------------------------------------- block tags (standalone)

    private static JsonObject blockTags;
    private static final Map<String, Set<String>> TAG_CACHE = new HashMap<>();

    static synchronized Set<String> resolveTag(String id) {
        if (!id.startsWith("#")) return Set.of(id);   // a plain block id
        String key = id.substring(1);
        Set<String> cached = TAG_CACHE.get(key);
        if (cached != null) return cached;
        if (blockTags == null) {
            try (InputStream in = VProcessors.class.getResourceAsStream("/vanilla/tags_block.json")) {
                blockTags = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            } catch (Exception e) { throw new IllegalStateException("tags_block.json", e); }
        }
        Set<String> out = new HashSet<>();
        TAG_CACHE.put(key, out); // guard against cycles
        String plain = key.substring(key.indexOf(':') + 1);
        JsonObject entry = blockTags.has(plain) ? blockTags.getAsJsonObject(plain)
                : blockTags.has(key) ? blockTags.getAsJsonObject(key) : null;
        if (entry != null) {
            for (JsonElement e : entry.getAsJsonArray("values")) {
                String v = e.getAsString();
                if (v.startsWith("#")) out.addAll(resolveTag(v));
                else out.add(v);
            }
        }
        return out;
    }
}
