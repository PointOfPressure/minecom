package dev.pointofpressure.minecom.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed map-color table, ported verbatim from decompiled {@code MapColor}/{@code ARGB} (26.2) —
 * the 62 base colors (id 0-61, {@code NONE}=0) with their 24-bit RGB, and the 4-way brightness
 * shade ({@code LOW}/{@code NORMAL}/{@code HIGH}/{@code LOWEST}) each map pixel is rendered at.
 * {@code calculateARGB} reproduces {@code MapColor.calculateARGBColor}: opaque the base RGB,
 * then {@code ARGB.scaleRGB(color, int scale)} — each channel {@code channel*scale/255},
 * clamped 0-255 — with {@code scale} = the brightness's integer modifier (180/220/255/135).
 * {@code getPackedId}/{@code colorFromPackedId} mirror the real vanilla map byte encoding
 * ({@code id << 2 | brightness}) used to pack a 128x128 map's pixel buffer into one byte each.
 * Which block gets which named color lives in {@code block_map_colors.json}
 * (scripts/extract_map_colors.py), not here — this class is purely the id/RGB/brightness table
 * itself, independent of any block.
 */
public final class MapColors {
    private MapColors() {}

    public record Color(int id, String name, int rgb) {}

    public enum Brightness {
        LOW(0, 180), NORMAL(1, 220), HIGH(2, 255), LOWEST(3, 135);

        public final int id;
        public final int modifier;

        Brightness(int id, int modifier) {
            this.id = id;
            this.modifier = modifier;
        }

        private static final Brightness[] VALUES = {LOW, NORMAL, HIGH, LOWEST};

        public static Brightness byId(int id) {
            return VALUES[id];
        }
    }

    private static final Color[] BY_ID = new Color[64];
    private static final Map<String, Color> BY_NAME = new LinkedHashMap<>();

    private static Color def(int id, String name, int rgb) {
        Color c = new Color(id, name, rgb);
        BY_ID[id] = c;
        BY_NAME.put(name, c);
        return c;
    }

    public static final Color NONE = def(0, "NONE", 0);
    public static final Color GRASS = def(1, "GRASS", 8368696);
    public static final Color SAND = def(2, "SAND", 16247203);
    public static final Color WOOL = def(3, "WOOL", 13092807);
    public static final Color FIRE = def(4, "FIRE", 16711680);
    public static final Color ICE = def(5, "ICE", 10526975);
    public static final Color METAL = def(6, "METAL", 10987431);
    public static final Color PLANT = def(7, "PLANT", 31744);
    public static final Color SNOW = def(8, "SNOW", 16777215);
    public static final Color CLAY = def(9, "CLAY", 10791096);
    public static final Color DIRT = def(10, "DIRT", 9923917);
    public static final Color STONE = def(11, "STONE", 7368816);
    public static final Color WATER = def(12, "WATER", 4210943);
    public static final Color WOOD = def(13, "WOOD", 9402184);
    public static final Color QUARTZ = def(14, "QUARTZ", 16776437);
    public static final Color COLOR_ORANGE = def(15, "COLOR_ORANGE", 14188339);
    public static final Color COLOR_MAGENTA = def(16, "COLOR_MAGENTA", 11685080);
    public static final Color COLOR_LIGHT_BLUE = def(17, "COLOR_LIGHT_BLUE", 6724056);
    public static final Color COLOR_YELLOW = def(18, "COLOR_YELLOW", 15066419);
    public static final Color COLOR_LIGHT_GREEN = def(19, "COLOR_LIGHT_GREEN", 8375321);
    public static final Color COLOR_PINK = def(20, "COLOR_PINK", 15892389);
    public static final Color COLOR_GRAY = def(21, "COLOR_GRAY", 5000268);
    public static final Color COLOR_LIGHT_GRAY = def(22, "COLOR_LIGHT_GRAY", 10066329);
    public static final Color COLOR_CYAN = def(23, "COLOR_CYAN", 5013401);
    public static final Color COLOR_PURPLE = def(24, "COLOR_PURPLE", 8339378);
    public static final Color COLOR_BLUE = def(25, "COLOR_BLUE", 3361970);
    public static final Color COLOR_BROWN = def(26, "COLOR_BROWN", 6704179);
    public static final Color COLOR_GREEN = def(27, "COLOR_GREEN", 6717235);
    public static final Color COLOR_RED = def(28, "COLOR_RED", 10040115);
    public static final Color COLOR_BLACK = def(29, "COLOR_BLACK", 1644825);
    public static final Color GOLD = def(30, "GOLD", 16445005);
    public static final Color DIAMOND = def(31, "DIAMOND", 6085589);
    public static final Color LAPIS = def(32, "LAPIS", 4882687);
    public static final Color EMERALD = def(33, "EMERALD", 55610);
    public static final Color PODZOL = def(34, "PODZOL", 8476209);
    public static final Color NETHER = def(35, "NETHER", 7340544);
    public static final Color TERRACOTTA_WHITE = def(36, "TERRACOTTA_WHITE", 13742497);
    public static final Color TERRACOTTA_ORANGE = def(37, "TERRACOTTA_ORANGE", 10441252);
    public static final Color TERRACOTTA_MAGENTA = def(38, "TERRACOTTA_MAGENTA", 9787244);
    public static final Color TERRACOTTA_LIGHT_BLUE = def(39, "TERRACOTTA_LIGHT_BLUE", 7367818);
    public static final Color TERRACOTTA_YELLOW = def(40, "TERRACOTTA_YELLOW", 12223780);
    public static final Color TERRACOTTA_LIGHT_GREEN = def(41, "TERRACOTTA_LIGHT_GREEN", 6780213);
    public static final Color TERRACOTTA_PINK = def(42, "TERRACOTTA_PINK", 10505550);
    public static final Color TERRACOTTA_GRAY = def(43, "TERRACOTTA_GRAY", 3746083);
    public static final Color TERRACOTTA_LIGHT_GRAY = def(44, "TERRACOTTA_LIGHT_GRAY", 8874850);
    public static final Color TERRACOTTA_CYAN = def(45, "TERRACOTTA_CYAN", 5725276);
    public static final Color TERRACOTTA_PURPLE = def(46, "TERRACOTTA_PURPLE", 8014168);
    public static final Color TERRACOTTA_BLUE = def(47, "TERRACOTTA_BLUE", 4996700);
    public static final Color TERRACOTTA_BROWN = def(48, "TERRACOTTA_BROWN", 4993571);
    public static final Color TERRACOTTA_GREEN = def(49, "TERRACOTTA_GREEN", 5001770);
    public static final Color TERRACOTTA_RED = def(50, "TERRACOTTA_RED", 9321518);
    public static final Color TERRACOTTA_BLACK = def(51, "TERRACOTTA_BLACK", 2430480);
    public static final Color CRIMSON_NYLIUM = def(52, "CRIMSON_NYLIUM", 12398641);
    public static final Color CRIMSON_STEM = def(53, "CRIMSON_STEM", 9715553);
    public static final Color CRIMSON_HYPHAE = def(54, "CRIMSON_HYPHAE", 6035741);
    public static final Color WARPED_NYLIUM = def(55, "WARPED_NYLIUM", 1474182);
    public static final Color WARPED_STEM = def(56, "WARPED_STEM", 3837580);
    public static final Color WARPED_HYPHAE = def(57, "WARPED_HYPHAE", 5647422);
    public static final Color WARPED_WART_BLOCK = def(58, "WARPED_WART_BLOCK", 1356933);
    public static final Color DEEPSLATE = def(59, "DEEPSLATE", 6579300);
    public static final Color RAW_IRON = def(60, "RAW_IRON", 14200723);
    public static final Color GLOW_LICHEN = def(61, "GLOW_LICHEN", 8365974);

    public static Color byId(int id) {
        Color c = (id >= 0 && id < BY_ID.length) ? BY_ID[id] : null;
        return c == null ? NONE : c;
    }

    /** block_map_colors.json stores the enum-constant name (e.g. "STONE"); missing = NONE. */
    public static Color byName(String name) {
        Color c = name == null ? null : BY_NAME.get(name);
        return c == null ? NONE : c;
    }

    /** MapColor.calculateARGBColor: opaque base RGB scaled per-channel by the brightness. */
    public static int calculateARGB(Color color, Brightness brightness) {
        if (color == NONE) return 0;
        int opaque = 0xFF000000 | (color.rgb & 0xFFFFFF);
        int r = clamp(red(opaque) * brightness.modifier / 255);
        int g = clamp(green(opaque) * brightness.modifier / 255);
        int b = clamp(blue(opaque) * brightness.modifier / 255);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static int red(int argb) {
        return argb >> 16 & 0xFF;
    }

    private static int green(int argb) {
        return argb >> 8 & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** MapColor.getPackedId: the single byte stored per map pixel. */
    public static byte getPackedId(Color color, Brightness brightness) {
        return (byte) (color.id << 2 | brightness.id & 3);
    }

    /** MapColor.getColorFromPackedId: decode a stored pixel byte back to its ARGB. */
    public static int colorFromPackedId(int packedId) {
        int val = packedId & 0xFF;
        return calculateARGB(byId(val >> 2), Brightness.byId(val & 3));
    }
}
