package dev.pointofpressure.minecom.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the real Mojang 26.1.2 data files (bundled from the official server jar)
 * and answers tag membership queries. Tag values may reference other tags with a
 * leading '#'; resolution is recursive and cached.
 */
public final class VanillaData {
    private VanillaData() {}

    static JsonObject recipes;
    static JsonObject lootBlocks;
    static JsonObject lootEntities;
    static JsonObject lootGameplay;
    static JsonObject lootTrial;
    static JsonObject lootChests;
    static JsonObject trialSpawnerConfigs;
    private static JsonObject tagsItem;
    private static JsonObject tagsBlock;
    private static JsonObject tagsDamage;
    private static JsonObject tagsEnchantment;
    static JsonObject enchantments;
    static JsonObject itemEnchantability;
    static JsonObject itemRepairable;
    private static JsonObject blockMapColors;

    private static final Map<String, Set<String>> ITEM_TAG_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> BLOCK_TAG_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> DAMAGE_TAG_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> ENCHANTMENT_TAG_CACHE = new ConcurrentHashMap<>();
    private static boolean loaded;

    public static synchronized void load() {
        if (loaded) return;
        recipes = read("/vanilla/recipes.json");
        lootBlocks = read("/vanilla/loot_blocks.json");
        lootEntities = read("/vanilla/loot_entities.json");
        lootGameplay = read("/vanilla/loot_gameplay.json");
        lootTrial = read("/vanilla/loot_trial.json");
        lootChests = read("/vanilla/loot_chests.json");
        trialSpawnerConfigs = read("/vanilla/trial_spawner.json");
        tagsItem = read("/vanilla/tags_item.json");
        tagsBlock = read("/vanilla/tags_block.json");
        tagsDamage = read("/vanilla/tags_damage_type.json");
        tagsEnchantment = read("/vanilla/tags_enchantment.json");
        enchantments = read("/vanilla/enchantment.json");
        itemEnchantability = read("/vanilla/item_enchantability.json");
        itemRepairable = read("/vanilla/item_repairable.json");
        blockMapColors = read("/vanilla/block_map_colors.json");
        Recipes.index();
        Enchants.index();
        loaded = true;
    }

    private static JsonObject read(String resource) {
        try (var in = VanillaData.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing bundled resource " + resource);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed loading " + resource, e);
        }
    }

    /** Strip "minecraft:" / "#" prefixes down to the bare path. */
    public static String path(String id) {
        if (id.startsWith("#")) id = id.substring(1);
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** All concrete member ids ("minecraft:x") of a tag, nested tags resolved. */
    static Set<String> resolveTag(JsonObject tags, String tag, Map<String, Set<String>> cache) {
        String key = path(tag);
        Set<String> cached = cache.get(key);
        if (cached != null) return cached;
        Set<String> out = new HashSet<>();
        JsonElement def = tags.get(key);
        if (def != null) {
            for (JsonElement v : def.getAsJsonObject().getAsJsonArray("values")) {
                String s = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
                if (s.startsWith("#")) out.addAll(resolveTag(tags, s, cache));
                else out.add(s);
            }
        }
        cache.put(key, out);
        return out;
    }

    static Set<String> itemTag(String tag) {
        return resolveTag(tagsItem, tag, ITEM_TAG_CACHE);
    }

    /** Public: also used by VanillaMobs.enderman() (ENDERMAN_HOLDABLE, decompile-verified). */
    public static Set<String> blockTag(String tag) {
        return resolveTag(tagsBlock, tag, BLOCK_TAG_CACHE);
    }

    public static boolean itemHasTag(Material m, String tag) {
        return m != null && itemTag(tag).contains(m.key().asString());
    }

    /** item_repairable.json[material] — the item tag its raw repair material must belong to, or null. */
    public static String itemRepairTag(Material m) {
        if (m == null) return null;
        var v = itemRepairable.get(m.key().asString());
        return v == null ? null : v.getAsString();
    }

    public static boolean blockHasTag(Block b, String tag) {
        return b != null && blockTag(tag).contains(b.key().asString());
    }

    /** block_map_colors.json[blockId] -> the MapColors enum-constant name, or null (NONE). */
    public static String blockMapColorName(String blockId) {
        JsonElement v = blockMapColors.get(blockId);
        return v == null ? null : v.getAsString();
    }

    /** Damage-type tag membership by damage type id, e.g. damageTypeHasTag("minecraft:fall", "bypasses_armor"). */
    public static boolean damageTypeHasTag(String damageTypeId, String tag) {
        return resolveTag(tagsDamage, tag, DAMAGE_TAG_CACHE).contains(damageTypeId);
    }

    static Set<String> enchantmentTag(String tag) {
        return resolveTag(tagsEnchantment, tag, ENCHANTMENT_TAG_CACHE);
    }

    /** Trial spawner config registry entry by id path, e.g. "trial_chamber/melee/zombie/normal". */
    public static JsonObject trialSpawnerConfig(String idPath) {
        JsonElement config = trialSpawnerConfigs.get(path(idPath));
        return config == null ? null : config.getAsJsonObject();
    }
}
