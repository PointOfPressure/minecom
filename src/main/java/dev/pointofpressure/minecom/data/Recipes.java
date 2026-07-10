package dev.pointofpressure.minecom.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Indexes and matches the real Mojang crafting + smelting recipes.
 * Ingredient JSON forms handled: "minecraft:id", "#minecraft:tag", [list of those],
 * and legacy {"item": id} / {"tag": tag} objects.
 */
public final class Recipes {
    private Recipes() {}

    public record Smelt(ItemStack result, int cookTicks, float xp) {}
    public record Cook(ItemStack result, int cookTicks, float xp) {}

    private record Ingredient(Set<String> allowed) {
        boolean matches(ItemStack stack) {
            return !stack.isAir() && allowed.contains(stack.material().key().asString());
        }
    }

    private record Shaped(int width, int height, Ingredient[] cells, ItemStack result) {}
    private record Shapeless(List<Ingredient> ingredients, ItemStack result) {}

    private static final List<Shaped> SHAPED = new ArrayList<>();
    private static final List<Shapeless> SHAPELESS = new ArrayList<>();
    private static final Map<String, Smelt> SMELTING = new HashMap<>();
    private static final Map<String, Cook> CAMPFIRE = new HashMap<>();
    private static final Map<String, Integer> FUEL = new HashMap<>();

    static void index() {
        SHAPED.clear();
        SHAPELESS.clear();
        SMELTING.clear();
        CAMPFIRE.clear();
        for (Map.Entry<String, JsonElement> e : VanillaData.recipes.entrySet()) {
            JsonObject r = e.getValue().getAsJsonObject();
            switch (VanillaData.path(r.get("type").getAsString())) {
                case "crafting_shaped" -> indexShaped(r);
                case "crafting_shapeless" -> indexShapeless(r);
                case "smelting" -> indexSmelting(r);
                case "campfire_cooking" -> indexCampfireCooking(r);
                default -> { /* stonecutting, smithing, special: not supported */ }
            }
        }
        indexFuels();
    }

    private static ItemStack result(JsonObject recipe) {
        JsonObject res = recipe.getAsJsonObject("result");
        Material mat = Material.fromKey(res.get("id").getAsString());
        if (mat == null) return ItemStack.AIR;
        int count = res.has("count") ? res.get("count").getAsInt() : 1;
        return ItemStack.of(mat, count);
    }

    private static Ingredient ingredient(JsonElement el) {
        Set<String> allowed = new HashSet<>(4);
        collectIngredient(el, allowed);
        return new Ingredient(allowed);
    }

    private static void collectIngredient(JsonElement el, Set<String> allowed) {
        if (el == null) return;
        if (el.isJsonArray()) {
            for (JsonElement sub : el.getAsJsonArray()) collectIngredient(sub, allowed);
        } else if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("item")) collectIngredient(o.get("item"), allowed);
            if (o.has("tag")) allowed.addAll(VanillaData.itemTag(o.get("tag").getAsString()));
        } else {
            String s = el.getAsString();
            if (s.startsWith("#")) allowed.addAll(VanillaData.itemTag(s));
            else allowed.add(s.contains(":") ? s : "minecraft:" + s);
        }
    }

    private static void indexShaped(JsonObject r) {
        ItemStack result = result(r);
        if (result.isAir()) return;
        JsonArray pattern = r.getAsJsonArray("pattern");
        JsonObject key = r.getAsJsonObject("key");
        int height = pattern.size();
        int width = 0;
        for (JsonElement row : pattern) width = Math.max(width, row.getAsString().length());
        Ingredient[] cells = new Ingredient[width * height];
        for (int y = 0; y < height; y++) {
            String row = pattern.get(y).getAsString();
            for (int x = 0; x < width; x++) {
                char c = x < row.length() ? row.charAt(x) : ' ';
                cells[y * width + x] = c == ' ' ? null : ingredient(key.get(String.valueOf(c)));
            }
        }
        SHAPED.add(new Shaped(width, height, cells, result));
    }

    private static void indexShapeless(JsonObject r) {
        ItemStack result = result(r);
        if (result.isAir()) return;
        List<Ingredient> ingredients = new ArrayList<>();
        for (JsonElement el : r.getAsJsonArray("ingredients")) ingredients.add(ingredient(el));
        SHAPELESS.add(new Shapeless(ingredients, result));
    }

    private static void indexSmelting(JsonObject r) {
        ItemStack result = result(r);
        if (result.isAir()) return;
        int time = r.has("cookingtime") ? r.get("cookingtime").getAsInt() : 200;
        float xp = r.has("experience") ? r.get("experience").getAsFloat() : 0f;
        Ingredient in = ingredient(r.get("ingredient"));
        for (String id : in.allowed()) SMELTING.put(id, new Smelt(result, time, xp));
    }

    private static void indexCampfireCooking(JsonObject r) {
        ItemStack result = result(r);
        if (result.isAir()) return;
        int time = r.has("cookingtime") ? r.get("cookingtime").getAsInt() : 600;
        float xp = r.has("experience") ? r.get("experience").getAsFloat() : 0f;
        Ingredient in = ingredient(r.get("ingredient"));
        for (String id : in.allowed()) CAMPFIRE.put(id, new Cook(result, time, xp));
    }

    /** Match a row-major width×width crafting grid; returns result or AIR. */
    public static ItemStack matchCrafting(ItemStack[] grid, int width) {
        int height = grid.length / width;
        for (Shaped s : SHAPED) {
            if (s.width() <= width && s.height() <= height && matchShaped(s, grid, width, height)) {
                return s.result();
            }
        }
        outer:
        for (Shapeless s : SHAPELESS) {
            int nonEmpty = 0;
            for (ItemStack stack : grid) if (!stack.isAir()) nonEmpty++;
            if (nonEmpty != s.ingredients().size()) continue;
            boolean[] used = new boolean[grid.length];
            for (Ingredient ing : s.ingredients()) {
                boolean found = false;
                for (int i = 0; i < grid.length; i++) {
                    if (!used[i] && ing.matches(grid[i])) {
                        used[i] = true;
                        found = true;
                        break;
                    }
                }
                if (!found) continue outer;
            }
            return s.result();
        }
        return ItemStack.AIR;
    }

    private static boolean matchShaped(Shaped s, ItemStack[] grid, int gridW, int gridH) {
        for (int oy = 0; oy <= gridH - s.height(); oy++) {
            for (int ox = 0; ox <= gridW - s.width(); ox++) {
                if (matchAt(s, grid, gridW, gridH, ox, oy, false)) return true;
                if (matchAt(s, grid, gridW, gridH, ox, oy, true)) return true;
            }
        }
        return false;
    }

    private static boolean matchAt(Shaped s, ItemStack[] grid, int gridW, int gridH, int ox, int oy, boolean mirror) {
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                int px = gx - ox, py = gy - oy;
                Ingredient cell = null;
                if (px >= 0 && px < s.width() && py >= 0 && py < s.height()) {
                    int col = mirror ? s.width() - 1 - px : px;
                    cell = s.cells()[py * s.width() + col];
                }
                ItemStack stack = grid[gy * gridW + gx];
                if (cell == null) {
                    if (!stack.isAir()) return false;
                } else if (!cell.matches(stack)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static Smelt smelt(Material input) {
        return input == null ? null : SMELTING.get(input.key().asString());
    }

    public static Cook campfireCook(Material input) {
        return input == null ? null : CAMPFIRE.get(input.key().asString());
    }

    public static boolean isFuel(Material m) {
        return fuelTicks(m) > 0;
    }

    /** Vanilla burn durations (ticks) for the common fuels. */
    public static int fuelTicks(Material m) {
        if (m == null) return 0;
        Integer exact = FUEL.get(m.key().asString());
        if (exact != null) return exact;
        String path = m.key().value();
        if (VanillaData.itemHasTag(m, "planks") || VanillaData.itemHasTag(m, "logs")
                || path.endsWith("_wood") || path.endsWith("_hyphae")) return 300;
        if (VanillaData.itemHasTag(m, "saplings")) return 100;
        if (VanillaData.itemHasTag(m, "wool")) return 100;
        if (VanillaData.itemHasTag(m, "wooden_slabs")) return 150;
        if (path.contains("boat") || path.equals("crafting_table") || path.equals("chest")
                || path.equals("bookshelf") || path.endsWith("_fence") || path.endsWith("_fence_gate")
                || path.endsWith("_stairs") && path.contains("oak")) return 300;
        return 0;
    }

    private static void indexFuels() {
        FUEL.put("minecraft:coal", 1600);
        FUEL.put("minecraft:charcoal", 1600);
        FUEL.put("minecraft:coal_block", 16000);
        FUEL.put("minecraft:blaze_rod", 2400);
        FUEL.put("minecraft:lava_bucket", 20000);
        FUEL.put("minecraft:stick", 100);
        FUEL.put("minecraft:bamboo", 50);
        FUEL.put("minecraft:dried_kelp_block", 4001);
    }

    static int shapedCount() { return SHAPED.size(); }
    static int shapelessCount() { return SHAPELESS.size(); }
    static int smeltingCount() { return SMELTING.size(); }
    static int campfireCount() { return CAMPFIRE.size(); }
}
