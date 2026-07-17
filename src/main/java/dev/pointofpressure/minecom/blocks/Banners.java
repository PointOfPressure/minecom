package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import net.minestom.server.color.DyeColor;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.banner.BannerPattern;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.BannerPatterns;
import net.minestom.server.registry.RegistryKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Banners, ported from decompiled BannerBlockEntity + the two crafting-grid special recipes
 * that operate on them, {@code BannerDuplicateRecipe} and {@code ShieldDecorationRecipe} (both
 * freshly decompiled for 26.2; recipes.json already bundles their data-driven parameters as
 * {@code *_banner_duplicate} / {@code shield_decoration}). Placed patterns ride on
 * {@code DataComponents.BANNER_PATTERNS} — Minestom's own component, mirroring vanilla's
 * {@code BannerPatternLayers} closely enough to reuse directly — captured from the placed
 * item at place time (this project has no generic block-entity/item-component bridge, so each
 * subsystem captures what it needs itself, same pattern as DecoratedPot's held item).
 * {@code matchSpecial}/{@code consumeSpecial} are called from {@link Crafting} ahead of the
 * generic shaped/shapeless matcher: duplication needs exactly one patterned + one blank banner
 * of the SAME base color (blank consumed, the patterned source itself is NOT — real vanilla's
 * {@code getRemainingItems} returns a copy of it, the one asymmetric-consumption special
 * recipe in this project, hence the dedicated {@code consumeSpecial} rather than the uniform
 * "consume 1 from every filled slot" path); shield decoration needs exactly one (patterned or
 * blank) banner + one pattern-free shield, both fully consumed, producing a shield carrying
 * the banner's base color + pattern layers.
 *
 * Simplifications (AUDIT): ~~the Loom UI/mechanic itself~~ **done 2026-07-17 (Sonnet 5, Tier 3
 * batch 6, {@code blocks/Loom.java}, new file)** — see that file's own doc. No custom name
 * (Nameable), no wall-vs-ground attachment-type distinction beyond the block's own placement
 * (already generic block behavior), no explosion/fire interactions beyond the generic
 * block-break path.
 */
public final class Banners {
    private Banners() {}

    private static final Map<String, BannerPatterns> BANNERS = new ConcurrentHashMap<>();
    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        Persist.register(persistence());
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (!isBanner(e.getBlock())) return;
            ItemStack held = e.getPlayer().getItemInHand(e.getHand());
            BannerPatterns patterns = held.get(DataComponents.BANNER_PATTERNS);
            if (patterns != null && !patterns.layers().isEmpty()) {
                BANNERS.put(Persist.posKey(e.getBlockPosition()), patterns);
            }
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            if (isBanner(e.getBlock())) BANNERS.remove(Persist.posKey(e.getBlockPosition()));
        });
    }

    public static boolean isBanner(Block block) {
        String key = block.key().value();
        return key.endsWith("_banner") || key.endsWith("_wall_banner");
    }

    /** AbstractBannerBlock.getColor: the base color is baked into the block id itself. */
    public static DyeColor baseColorOf(Block block) {
        String key = block.key().value();
        String colorName = key.replace("_wall_banner", "").replace("_banner", "");
        try {
            return DyeColor.valueOf(colorName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DyeColor.WHITE;
        }
    }

    public static BannerPatterns patternsOf(Point pos) {
        return BANNERS.getOrDefault(Persist.posKey(pos), new BannerPatterns(List.of()));
    }

    /** Test/loot hook: set a placed banner's patterns directly (standing in for "however a
     * patterned banner was obtained" — see the class doc's Loom-UI simplification note). */
    public static void setPatternsForTest(Point pos, BannerPatterns patterns) {
        BANNERS.put(Persist.posKey(pos), patterns);
    }

    static void onBlockRemoved(Instance in, Point pos, DyeColor baseColor) {
        BannerPatterns patterns = BANNERS.remove(Persist.posKey(pos));
        ItemStack drop = bannerItem(baseColor, patterns);
        BlockRules.dropAt(in, pos, drop);
    }

    private static ItemStack bannerItem(DyeColor color, BannerPatterns patterns) {
        Material mat = Material.fromKey("minecraft:" + color.name().toLowerCase(java.util.Locale.ROOT) + "_banner");
        ItemStack stack = ItemStack.of(mat != null ? mat : Material.WHITE_BANNER);
        if (patterns != null && !patterns.layers().isEmpty()) stack = stack.with(DataComponents.BANNER_PATTERNS, patterns);
        return stack;
    }

    // ------------------------------------------------------------------ crafting-special recipes

    /** BannerDuplicateRecipe.matches + ShieldDecorationRecipe.matches, tried in that order. */
    public static ItemStack matchSpecial(ItemStack[] grid) {
        ItemStack duplicate = matchDuplicate(grid);
        if (duplicate != null) return duplicate;
        return matchShieldDecoration(grid);
    }

    /** BannerDuplicateRecipe: exactly 2 filled slots, both banners of the same base color, one
     * with 1-6 pattern layers (the source) and one with none (the target, becomes the copy). */
    private static ItemStack matchDuplicate(ItemStack[] grid) {
        ItemStack source = null, target = null;
        DyeColor color = null;
        int filled = 0;
        for (ItemStack s : grid) {
            if (s == null || s.isAir()) continue;
            filled++;
            if (!(s.material().key().asString().endsWith("_banner"))) return null;
            DyeColor c = bannerMaterialColor(s.material());
            if (c == null) return null;
            if (color == null) color = c;
            else if (color != c) return null;
            BannerPatterns patterns = s.get(DataComponents.BANNER_PATTERNS);
            int count = patterns == null ? 0 : patterns.layers().size();
            if (count > 6) return null;
            if (count > 0) {
                if (source != null) return null;
                source = s;
            } else {
                if (target != null) return null;
                target = s;
            }
        }
        if (filled != 2 || source == null || target == null) return null;
        BannerPatterns patterns = source.get(DataComponents.BANNER_PATTERNS);
        return target.withAmount(1).with(DataComponents.BANNER_PATTERNS, patterns);
    }

    /** ShieldDecorationRecipe: exactly 2 filled slots, one banner (any), one pattern-free
     * shield; result = a shield carrying the banner's base color + pattern layers. */
    private static ItemStack matchShieldDecoration(ItemStack[] grid) {
        ItemStack banner = null, shield = null;
        int filled = 0;
        for (ItemStack s : grid) {
            if (s == null || s.isAir()) continue;
            filled++;
            if (s.material().key().asString().endsWith("_banner")) {
                if (banner != null) return null;
                banner = s;
            } else if (s.material() == Material.SHIELD) {
                BannerPatterns existing = s.get(DataComponents.BANNER_PATTERNS);
                if (existing != null && !existing.layers().isEmpty()) return null;
                if (shield != null) return null;
                shield = s;
            } else {
                return null;
            }
        }
        if (filled != 2 || banner == null || shield == null) return null;
        DyeColor color = bannerMaterialColor(banner.material());
        BannerPatterns patterns = banner.get(DataComponents.BANNER_PATTERNS);
        ItemStack result = shield.withAmount(1).with(DataComponents.BASE_COLOR, color);
        if (patterns != null) result = result.with(DataComponents.BANNER_PATTERNS, patterns);
        return result;
    }

    private static DyeColor bannerMaterialColor(Material mat) {
        String key = mat.key().value();
        if (!key.endsWith("_banner")) return null;
        try {
            return DyeColor.valueOf(key.replace("_banner", "").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Asymmetric consumption for the duplicate recipe (source banner survives); the shield
     * decoration recipe consumes both normally, so callers only need this for duplication. */
    public static boolean isDuplicateRecipe(ItemStack[] grid) {
        return matchDuplicate(grid) != null;
    }

    public static void consumeDuplicate(ItemStack[] grid, java.util.function.BiConsumer<Integer, ItemStack> setSlot) {
        for (int i = 0; i < grid.length; i++) {
            ItemStack s = grid[i];
            if (s == null || s.isAir()) continue;
            BannerPatterns patterns = s.get(DataComponents.BANNER_PATTERNS);
            int count = patterns == null ? 0 : patterns.layers().size();
            // the source (has patterns) survives at quantity 1; the blank target is fully consumed
            setSlot.accept(i, count > 0 ? s.withAmount(1) : ItemStack.AIR);
        }
    }

    /** Look up a registered pattern by its vanilla path (e.g. "creeper"). BannerPattern is
     * itself a Holder&lt;BannerPattern&gt; (Holder.Direct), so this satisfies Layer's type. */
    public static BannerPattern patternByName(String name) {
        RegistryKey<BannerPattern> key = RegistryKey.unsafeOf(name);
        return net.minestom.server.MinecraftServer.process().bannerPattern().get(key);
    }

    // ------------------------------------------------------------------ persistence

    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "banner";
            }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, JsonObject> out) {
                BANNERS.forEach((key, patterns) -> {
                    JsonObject o = new JsonObject();
                    JsonArray arr = new JsonArray();
                    for (BannerPatterns.Layer layer : patterns.layers()) {
                        JsonObject lo = new JsonObject();
                        // Holder<BannerPattern> in practice is always the Holder.Direct
                        // BannerPattern itself here (every layer is built via patternByName,
                        // never a lazy RegistryKey reference) — assetId doubles as a stable id.
                        String id = layer.pattern() instanceof BannerPattern bp
                                ? bp.assetId().asString() : "minecraft:base";
                        lo.addProperty("pattern", id);
                        lo.addProperty("color", layer.color().name());
                        arr.add(lo);
                    }
                    o.add("layers", arr);
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                List<BannerPatterns.Layer> layers = new ArrayList<>();
                for (var el : data.getAsJsonArray("layers")) {
                    JsonObject lo = el.getAsJsonObject();
                    BannerPattern pattern = patternByName(
                            dev.pointofpressure.minecom.data.VanillaData.path(lo.get("pattern").getAsString()));
                    if (pattern == null) continue;
                    layers.add(new BannerPatterns.Layer(pattern, DyeColor.valueOf(lo.get("color").getAsString())));
                }
                if (!layers.isEmpty()) BANNERS.put(Persist.posKey(pos), new BannerPatterns(layers));
            }

            @Override
            public void wipe() {
                BANNERS.clear();
            }
        };
    }
}
