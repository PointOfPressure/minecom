package dev.pointofpressure.minecom;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vanilla 26.2 gamerule registry and value store, ported from
 * {@code net.minecraft.world.level.gamerules.GameRules} (26.2 renamed every
 * rule to a snake_case identifier: {@code doDaylightCycle} is now
 * {@code advance_time}, {@code randomTickSpeed} is {@code random_tick_speed},
 * etc.). Registration order, defaults and integer bounds match the decompile
 * line-for-line; {@code max_minecart_speed} is omitted because it is gated on
 * the {@code minecart_improvements} experimental feature flag, which vanilla
 * release worlds don't enable.
 *
 * <p>Values are served from a concurrent map (read on tick/command threads,
 * written by {@code /gamerule} and {@link Persist} load). Consumers poll
 * {@link #getBool}/{@link #getInt} at use-time, mirroring vanilla's
 * per-access {@code level.getGameRules().get(...)} pattern. Rules with no
 * consumer yet are stored + settable + persisted but behaviourally inert;
 * the wired set is tracked in docs/AUDIT.md.
 */
public final class GameRules {
    private GameRules() {}

    /** One registered rule: boolean when {@code bool}, else int in [min, max]. */
    public record Rule(String id, boolean bool, boolean defBool, int defInt, int min, int max) {}

    private static final LinkedHashMap<String, Rule> RULES = new LinkedHashMap<>();
    private static final ConcurrentHashMap<String, Object> VALUES = new ConcurrentHashMap<>();

    private static void bool(String id, boolean def) {
        RULES.put(id, new Rule(id, true, def, 0, 0, 0));
    }

    private static void integer(String id, int def, int min) {
        integer(id, def, min, Integer.MAX_VALUE);
    }

    private static void integer(String id, int def, int min, int max) {
        RULES.put(id, new Rule(id, false, false, def, min, max));
    }

    static {
        // GameRules (26.2) registration order; DEBUG_WORLD_RECREATE is false in release,
        // so advance_time/advance_weather default true.
        bool("advance_time", true);
        bool("advance_weather", true);
        bool("allow_entering_nether_using_portals", true);
        bool("block_drops", true);
        bool("block_explosion_drop_decay", true);
        bool("command_blocks_work", true);
        bool("command_block_output", true);
        bool("drowning_damage", true);
        bool("elytra_movement_check", true);
        bool("ender_pearls_vanish_on_death", true);
        bool("entity_drops", true);
        bool("fall_damage", true);
        bool("fire_damage", true);
        integer("fire_spread_radius_around_player", 128, -1);
        bool("forgive_dead_players", true);
        bool("freeze_damage", true);
        bool("global_sound_events", true);
        bool("immediate_respawn", false);
        bool("keep_inventory", false);
        bool("lava_source_conversion", false);
        bool("limited_crafting", false);
        bool("locator_bar", true);
        bool("log_admin_commands", true);
        integer("max_block_modifications", 32768, 1);
        integer("max_command_forks", 65536, 0);
        integer("max_command_sequence_length", 65536, 0);
        integer("max_entity_cramming", 24, 0);
        integer("max_snow_accumulation_height", 1, 0, 8);
        bool("mob_drops", true);
        bool("mob_explosion_drop_decay", true);
        bool("mob_griefing", true);
        bool("natural_health_regeneration", true);
        bool("player_movement_check", true);
        integer("players_nether_portal_creative_delay", 0, 0);
        integer("players_nether_portal_default_delay", 80, 0);
        integer("players_sleeping_percentage", 100, 0);
        bool("projectiles_can_break_blocks", true);
        bool("pvp", true);
        bool("raids", true);
        integer("random_tick_speed", 3, 0);
        bool("reduced_debug_info", false);
        integer("respawn_radius", 10, 0);
        bool("send_command_feedback", true);
        bool("show_advancement_messages", true);
        bool("show_death_messages", true);
        bool("spawner_blocks_work", true);
        bool("spawn_mobs", true);
        bool("spawn_monsters", true);
        bool("spawn_patrols", true);
        bool("spawn_phantoms", true);
        bool("spawn_wandering_traders", true);
        bool("spawn_wardens", true);
        bool("spectators_generate_chunks", true);
        bool("spread_vines", true);
        bool("tnt_explodes", true);
        bool("tnt_explosion_drop_decay", false);
        bool("universal_anger", false);
        bool("water_source_conversion", true);
    }

    /** All registered rules, in vanilla registration order. */
    public static Map<String, Rule> rules() {
        return java.util.Collections.unmodifiableMap(RULES);
    }

    /** Accepts both the bare id and the {@code minecraft:}-qualified form vanilla's command does. */
    public static Rule rule(String id) {
        return RULES.get(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
    }

    public static boolean getBool(String id) {
        Rule rule = required(id, true);
        Object v = VALUES.get(rule.id());
        return v == null ? rule.defBool() : (Boolean) v;
    }

    public static int getInt(String id) {
        Rule rule = required(id, false);
        Object v = VALUES.get(rule.id());
        return v == null ? rule.defInt() : (Integer) v;
    }

    /** Current value rendered the way vanilla's command/level.dat does. */
    public static String getAsString(String id) {
        Rule rule = rule(id);
        if (rule == null) throw new IllegalArgumentException("unknown gamerule " + id);
        return rule.bool() ? String.valueOf(getBool(id)) : String.valueOf(getInt(id));
    }

    /**
     * Parse + validate + store a raw command value. Returns the vanilla command
     * result ({@code b ? 1 : 0} for booleans, the value for ints); throws
     * {@link IllegalArgumentException} with a user-facing message on bad input.
     */
    public static int set(String id, String rawValue) {
        Rule rule = rule(id);
        if (rule == null) throw new IllegalArgumentException("Unknown gamerule: " + id);
        if (rule.bool()) {
            // BoolArgumentType accepts exactly "true"/"false"
            if (!rawValue.equals("true") && !rawValue.equals("false")) {
                throw new IllegalArgumentException("Invalid boolean, expected 'true' or 'false' but found '" + rawValue + "'");
            }
            boolean b = Boolean.parseBoolean(rawValue);
            VALUES.put(rule.id(), b);
            return b ? 1 : 0;
        }
        int i;
        try {
            i = Integer.parseInt(rawValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer '" + rawValue + "'");
        }
        if (i < rule.min() || i > rule.max()) {
            throw new IllegalArgumentException("Integer must not be less than " + rule.min()
                    + (rule.max() != Integer.MAX_VALUE ? " or more than " + rule.max() : "") + ", found " + i);
        }
        VALUES.put(rule.id(), i);
        return i;
    }

    /** Reset every rule to its default (test isolation + fresh-world boot). */
    public static void resetAll() {
        VALUES.clear();
    }

    /** Non-default values only, for the Persist world-state snapshot. */
    public static Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Rule rule : RULES.values()) {
            Object v = VALUES.get(rule.id());
            if (v != null && !v.equals(rule.bool() ? rule.defBool() : rule.defInt())) {
                out.put(rule.id(), String.valueOf(v));
            }
        }
        return out;
    }

    /** Persist load hook: silently skips unknown ids/bad values (forward compatibility). */
    public static void loadSnapshot(Map<String, String> saved) {
        for (var entry : saved.entrySet()) {
            try {
                set(entry.getKey(), entry.getValue());
            } catch (IllegalArgumentException ignored) {
                // an old save naming a rule this build doesn't know keeps the default
            }
        }
    }

    private static Rule required(String id, boolean wantBool) {
        Rule rule = rule(id);
        if (rule == null) throw new IllegalArgumentException("unknown gamerule " + id);
        if (rule.bool() != wantBool) {
            throw new IllegalArgumentException("gamerule " + id + " is " + (rule.bool() ? "boolean" : "integer"));
        }
        return rule;
    }
}
