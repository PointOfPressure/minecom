package dev.pointofpressure.minecom.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Evaluates Mojang loot tables (blocks + entities). No enchantment system exists,
 * so enchantment-gated predicates (silk touch, fortune counts) evaluate as
 * enchantment-absent; unknown conditions evaluate false so alternatives fall
 * through to their unenchanted branch, exactly like an unenchanted vanilla tool.
 */
public final class LootTables {
    private LootTables() {}

    private static final Random RANDOM = new Random();

    /** Evaluation context: the tool used and (for block tables) the actual block state. */
    record Ctx(ItemStack tool, Block block) {}

    public static List<ItemStack> blockDrops(Block block, ItemStack tool) {
        JsonElement table = VanillaData.lootBlocks.get(block.key().value());
        if (table == null) return List.of();
        return evaluate(table.getAsJsonObject(), new Ctx(tool, block));
    }

    public static List<ItemStack> entityDrops(EntityType type) {
        return entityDrops(type, ItemStack.AIR);
    }

    /** Entity drops with the killer's weapon (looting applies). */
    public static List<ItemStack> entityDrops(EntityType type, ItemStack weapon) {
        JsonElement table = VanillaData.lootEntities.get(type.key().value());
        if (table == null) return List.of();
        return evaluate(table.getAsJsonObject(), new Ctx(weapon == null ? ItemStack.AIR : weapon, null));
    }

    /** Gameplay tables: fishing, cat gifts, bartering... by path e.g. "fishing". */
    public static List<ItemStack> gameplay(String name, ItemStack tool) {
        JsonElement table = VanillaData.lootGameplay.get(name);
        if (table == null) return List.of();
        return evaluate(table.getAsJsonObject(), new Ctx(tool == null ? ItemStack.AIR : tool, null));
    }

    /** Trial-chamber tables by id path, e.g. "spawners/trial_chamber/consumables". */
    public static List<ItemStack> trial(String idPath) {
        JsonElement table = VanillaData.lootTrial.get(VanillaData.path(idPath));
        if (table == null) return List.of();
        return evaluate(table.getAsJsonObject(), new Ctx(ItemStack.AIR, null));
    }

    private static List<ItemStack> evaluate(JsonObject table, Ctx ctx) {
        List<ItemStack> out = new ArrayList<>(2);
        JsonArray pools = table.getAsJsonArray("pools");
        if (pools == null) return out;
        for (JsonElement poolEl : pools) {
            JsonObject pool = poolEl.getAsJsonObject();
            if (!conditions(pool.getAsJsonArray("conditions"), ctx)) continue;
            int rolls = sampleCount(pool.get("rolls"), 1);
            for (int i = 0; i < rolls; i++) {
                rollPool(pool.getAsJsonArray("entries"), ctx, out);
            }
        }
        return out;
    }

    /** One pool roll: among entries whose conditions pass, pick one weighted by `weight` (default 1). */
    private static void rollPool(JsonArray entries, Ctx ctx, List<ItemStack> out) {
        if (entries == null || entries.isEmpty()) return;
        List<JsonObject> candidates = new ArrayList<>(entries.size());
        int totalWeight = 0;
        for (JsonElement el : entries) {
            JsonObject entry = el.getAsJsonObject();
            if (!conditions(entry.getAsJsonArray("conditions"), ctx)) continue;
            candidates.add(entry);
            totalWeight += entry.has("weight") ? entry.get("weight").getAsInt() : 1;
        }
        if (candidates.isEmpty()) return;
        int pick = RANDOM.nextInt(totalWeight);
        for (JsonObject entry : candidates) {
            pick -= entry.has("weight") ? entry.get("weight").getAsInt() : 1;
            if (pick < 0) {
                processEntry(entry, ctx, out);
                return;
            }
        }
    }

    /** Process an entry whose own conditions have already passed. */
    private static void processEntry(JsonObject entry, Ctx ctx, List<ItemStack> out) {
        String type = VanillaData.path(entry.get("type").getAsString());
        switch (type) {
            case "item" -> {
                Material mat = Material.fromKey(entry.get("name").getAsString());
                if (mat == null) return;
                JsonArray functions = entry.getAsJsonArray("functions");
                int count = applyFunctions(functions, 1, ctx);
                if (count <= 0) return;
                out.add(applyItemFunctions(ItemStack.of(mat, count), functions));
            }
            case "alternatives" -> {
                for (JsonElement child : entry.getAsJsonArray("children")) {
                    JsonObject c = child.getAsJsonObject();
                    if (conditions(c.getAsJsonArray("conditions"), ctx)) {
                        processEntry(c, ctx, out);
                        return;
                    }
                }
            }
            case "group", "sequence" -> {
                for (JsonElement child : entry.getAsJsonArray("children")) {
                    JsonObject c = child.getAsJsonObject();
                    if (conditions(c.getAsJsonArray("conditions"), ctx)) {
                        processEntry(c, ctx, out);
                        if (type.equals("sequence")) break;
                    } else if (type.equals("sequence")) {
                        break;
                    }
                }
            }
            case "loot_table" -> { // nested table reference (fishing/fish, trial reward tiers)
                JsonElement value = entry.get("value");
                if (value != null && value.isJsonPrimitive()) {
                    String ref = VanillaData.path(value.getAsString()).replace("gameplay/", "");
                    JsonElement sub = VanillaData.lootGameplay.get(ref);
                    if (sub == null) sub = VanillaData.lootTrial.get(VanillaData.path(value.getAsString()));
                    if (sub != null) out.addAll(evaluate(sub.getAsJsonObject(), ctx));
                }
            }
            default -> { /* dynamic, empty: no drop */ }
        }
    }

    /**
     * Functions that mutate the item itself (identity/components) rather than just its
     * count — a separate pass from applyFunctions() since that one only threads an int.
     */
    private static ItemStack applyItemFunctions(ItemStack stack, JsonArray functions) {
        if (functions == null) return stack;
        for (JsonElement fnEl : functions) {
            JsonObject fn = fnEl.getAsJsonObject();
            switch (VanillaData.path(fn.get("function").getAsString())) {
                case "set_potion" -> {
                    net.minestom.server.potion.PotionType type =
                            net.minestom.server.potion.PotionType.fromKey(fn.get("id").getAsString());
                    if (type != null) {
                        stack = stack.with(b -> b.set(net.minestom.server.component.DataComponents.POTION_CONTENTS,
                                new net.minestom.server.item.component.PotionContents(type)));
                    }
                }
                case "enchant_randomly" -> stack = enchantRandomly(stack, fn);
                default -> { /* count-affecting or no-op functions: handled in applyFunctions/no-op */ }
            }
        }
        return stack;
    }

    /**
     * EnchantRandomlyFunction (decompile-verified): with a single explicit "options"
     * enchantment id (the only form loot tables in this project's data actually use —
     * piglin_bartering's book/iron_boots entries both force soul_speed), rolls a level
     * uniformly between that enchantment's min (1) and max level and applies it,
     * swapping a plain book for an enchanted_book first. The broader form — "options"
     * omitted, meaning "pick any random compatible enchantment from the whole registry"
     * — isn't modeled (falls through unenchanted); no table this project loads uses that
     * form today. Per-enchantment max levels aren't tracked as a registry anywhere in
     * this codebase yet (Enchants.java's enchanting-table pools hardcode them per
     * item-type the same way), so this hardcodes the one level this function has
     * actually needed so far, matching data/minecraft/enchantment/soul_speed.json's
     * max_level: 3.
     */
    private static ItemStack enchantRandomly(ItemStack stack, JsonObject fn) {
        if (!fn.has("options") || !fn.get("options").isJsonPrimitive()) return stack;
        String enchantId = VanillaData.path(fn.get("options").getAsString());
        var key = Enchants.byName(enchantId);
        if (key == null) return stack;
        int maxLevel = switch (enchantId) {
            case "soul_speed" -> 3;
            default -> 1;
        };
        int level = 1 + RANDOM.nextInt(maxLevel);
        if (stack.material() == Material.BOOK) stack = ItemStack.of(Material.ENCHANTED_BOOK, stack.amount());
        return Enchants.with(stack, key, level);
    }

    private static int applyFunctions(JsonArray functions, int count, Ctx ctx) {
        if (functions == null) return count;
        for (JsonElement fnEl : functions) {
            JsonObject fn = fnEl.getAsJsonObject();
            switch (VanillaData.path(fn.get("function").getAsString())) {
                case "set_count" -> {
                    int sampled = sampleCount(fn.get("count"), 1);
                    boolean add = fn.has("add") && fn.get("add").getAsBoolean();
                    count = add ? count + sampled : sampled;
                }
                case "limit_count" -> {
                    JsonObject limit = fn.getAsJsonObject("limit");
                    if (limit.has("min")) count = Math.max(count, limit.get("min").getAsInt());
                    if (limit.has("max")) count = Math.min(count, limit.get("max").getAsInt());
                }
                case "apply_bonus" -> { // fortune formulas
                    int level = Enchants.level(ctx.tool(), fn.get("enchantment").getAsString());
                    if (level <= 0) break;
                    String formula = VanillaData.path(fn.get("formula").getAsString());
                    if (formula.equals("ore_drops")) {
                        count *= Math.max(1, RANDOM.nextInt(level + 2));
                    } else if (formula.equals("uniform_bonus_count")) {
                        int mult = fn.has("parameters")
                                ? fn.getAsJsonObject("parameters").get("bonusMultiplier").getAsInt() : 1;
                        count += RANDOM.nextInt(level * mult + 1);
                    }
                }
                case "enchanted_count_increase" -> { // looting
                    int level = Enchants.level(ctx.tool(), fn.has("enchantment")
                            ? fn.get("enchantment").getAsString() : "looting");
                    if (level > 0) count += RANDOM.nextInt(level + 1);
                }
                default -> { /* explosion_decay, copy_*: no-op */ }
            }
        }
        return count;
    }

    /** Number providers: plain number, {type:constant,value}, {type:uniform,min,max}, {type:binomial,n,p}. */
    private static int sampleCount(JsonElement el, int fallback) {
        if (el == null) return fallback;
        if (el.isJsonPrimitive()) return (int) Math.round(el.getAsDouble());
        JsonObject o = el.getAsJsonObject();
        String type = o.has("type") ? VanillaData.path(o.get("type").getAsString()) : "uniform";
        return switch (type) {
            case "constant" -> (int) Math.round(o.get("value").getAsDouble());
            case "uniform" -> {
                int min = (int) Math.round(numberOf(o.get("min"), 0));
                int max = (int) Math.round(numberOf(o.get("max"), min));
                yield min + (max > min ? RANDOM.nextInt(max - min + 1) : 0);
            }
            case "binomial" -> {
                int n = (int) Math.round(numberOf(o.get("n"), 0));
                double p = numberOf(o.get("p"), 0.5);
                int c = 0;
                for (int i = 0; i < n; i++) if (RANDOM.nextDouble() < p) c++;
                yield c;
            }
            default -> fallback;
        };
    }

    private static double numberOf(JsonElement el, double fallback) {
        if (el == null) return fallback;
        if (el.isJsonPrimitive()) return el.getAsDouble();
        return sampleCount(el, (int) fallback);
    }

    private static boolean conditions(JsonArray conds, Ctx ctx) {
        if (conds == null) return true;
        for (JsonElement c : conds) {
            if (!condition(c.getAsJsonObject(), ctx)) return false;
        }
        return true;
    }

    private static boolean condition(JsonObject cond, Ctx ctx) {
        return switch (VanillaData.path(cond.get("condition").getAsString())) {
            case "survives_explosion", "killed_by_player", "location_check",
                 "damage_source_properties" -> true;
            case "block_state_property" -> blockStateMatches(cond, ctx.block());
            case "match_tool" -> matchTool(cond.getAsJsonObject("predicate"), ctx.tool());
            case "table_bonus" -> {
                int level = Enchants.level(ctx.tool(), cond.get("enchantment").getAsString());
                JsonArray chances = cond.getAsJsonArray("chances");
                yield RANDOM.nextDouble() < chances.get(Math.min(level, chances.size() - 1)).getAsDouble();
            }
            case "random_chance" -> RANDOM.nextDouble() < numberOf(cond.get("chance"), 0);
            case "random_chance_with_enchanted_bonus" -> {
                double chance = numberOf(cond.get("unenchanted_chance"), 0);
                int looting = Enchants.level(ctx.tool(), "looting");
                if (looting > 0 && cond.get("enchanted_chance") instanceof JsonObject ec
                        && ec.has("base")) {
                    double per = ec.has("per_level_above_first")
                            ? ec.get("per_level_above_first").getAsDouble() : 0.01;
                    chance = ec.get("base").getAsDouble() + per * (looting - 1);
                }
                yield RANDOM.nextDouble() < chance;
            }
            case "inverted" -> !condition(cond.getAsJsonObject("term"), ctx);
            case "any_of" -> {
                for (JsonElement t : cond.getAsJsonArray("terms")) {
                    if (condition(t.getAsJsonObject(), ctx)) yield true;
                }
                yield false;
            }
            case "all_of" -> {
                for (JsonElement t : cond.getAsJsonArray("terms")) {
                    if (!condition(t.getAsJsonObject(), ctx)) yield false;
                }
                yield true;
            }
            case "entity_properties" -> {
                JsonObject predicate = cond.getAsJsonObject("predicate");
                yield predicate == null || !predicate.has("vehicle");
            }
            default -> false; // unknown (incl. enchantment-based): behave like unenchanted vanilla
        };
    }

    /** {"properties":{"age":"7", ...}} compared against the actual block state. */
    private static boolean blockStateMatches(JsonObject cond, Block block) {
        if (block == null) return true;
        JsonObject props = cond.getAsJsonObject("properties");
        if (props == null) return true;
        for (Map.Entry<String, JsonElement> e : props.entrySet()) {
            String actual = block.getProperty(e.getKey());
            if (actual == null) return false;
            JsonElement expected = e.getValue();
            if (expected.isJsonObject()) {
                JsonObject range = expected.getAsJsonObject();
                try {
                    int v = Integer.parseInt(actual);
                    if (range.has("min") && v < range.get("min").getAsInt()) return false;
                    if (range.has("max") && v > range.get("max").getAsInt()) return false;
                } catch (NumberFormatException ex) {
                    return false;
                }
            } else if (!expected.getAsString().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    /** Item predicate: items list/tag plus enchantment sub-predicates against the real tool. */
    private static boolean matchTool(JsonObject predicate, ItemStack tool) {
        if (predicate == null) return true;
        if (predicate.has("predicates")) {
            JsonObject preds = predicate.getAsJsonObject("predicates");
            JsonElement ench = preds.get("minecraft:enchantments");
            if (ench == null) return false; // unknown sub-predicate type
            for (JsonElement reqEl : ench.getAsJsonArray()) {
                JsonObject req = reqEl.getAsJsonObject();
                JsonElement ids = req.get("enchantments");
                int min = req.has("levels") && req.get("levels").isJsonObject()
                        && req.getAsJsonObject("levels").has("min")
                        ? req.getAsJsonObject("levels").get("min").getAsInt() : 1;
                boolean any = false;
                if (ids.isJsonArray()) {
                    for (JsonElement id : ids.getAsJsonArray()) {
                        if (Enchants.level(tool, id.getAsString()) >= min) any = true;
                    }
                } else {
                    any = Enchants.level(tool, ids.getAsString()) >= min;
                }
                if (!any) return false;
            }
            return true;
        }
        JsonElement items = predicate.get("items");
        if (items == null) return true;
        if (tool == null || tool.isAir()) return false;
        String toolKey = tool.material().key().asString();
        if (items.isJsonArray()) {
            for (JsonElement it : items.getAsJsonArray()) {
                if (matchesItemSpec(it.getAsString(), toolKey)) return true;
            }
            return false;
        }
        return matchesItemSpec(items.getAsString(), toolKey);
    }

    private static boolean matchesItemSpec(String spec, String toolKey) {
        if (spec.startsWith("#")) return VanillaData.itemTag(spec).contains(toolKey);
        String normalized = spec.contains(":") ? spec : "minecraft:" + spec;
        return normalized.equals(toolKey);
    }
}
