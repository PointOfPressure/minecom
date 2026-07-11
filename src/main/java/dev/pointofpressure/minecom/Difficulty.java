package dev.pointofpressure.minecom;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World difficulty. The base setting is world-level exactly as in vanilla Java
 * (level.dat {@code Difficulty}; Java has no per-player difficulty — that's a
 * Bedrock concept), persisted in minecom_state.json and switched with /difficulty.
 *
 * Regional ("local") difficulty is DifficultyInstance.calculateDifficulty
 * verbatim: {@code base.id * (0.75 + global + local)}, where global ramps
 * 0..0.25 as world time crosses 72k..1512k ticks (in-game days 3-63), and local
 * adds chunk-inhabited time (0..1 over 3.6M ticks, x0.75 unless Hard) plus a
 * moon-phase bonus clamped to the global ramp, all halved on Easy. The special
 * multiplier ((effective-2)/2 clamped to 0..1) gates mob armor/enchant rolls.
 *
 * Chunk inhabited time is tracked in memory only — chunks within 8 chunks of a
 * player accrue 20 ticks per second, matching vanilla's entity-ticking-chunk
 * accrual — and is not persisted (vanilla stores it per chunk in region data;
 * this project's persistence layer has no per-chunk custom fields yet).
 */
public enum Difficulty {
    PEACEFUL, EASY, NORMAL, HARD;

    private static volatile Difficulty current = NORMAL;

    /** DifficultyInstance.MOON_BRIGHTNESS_PER_PHASE, indexed by (dayTime/24000) % 8. */
    private static final float[] MOON_BRIGHTNESS = {1.0F, 0.75F, 0.5F, 0.25F, 0.0F, 0.25F, 0.5F, 0.75F};

    private static final int INHABITED_RADIUS_CHUNKS = 8;
    private static final Map<Instance, ConcurrentHashMap<Long, Long>> INHABITED = new ConcurrentHashMap<>();

    public static Difficulty current() {
        return current;
    }

    public static void set(Difficulty difficulty) {
        current = difficulty;
    }

    public static boolean isPeaceful() {
        return current == PEACEFUL;
    }

    /** Player.hurtServer: difficulty scaling for damage sources that scale with it. */
    public static float scalePlayerDamage(float amount) {
        return switch (current) {
            case PEACEFUL -> 0.0F;
            case EASY -> Math.min(amount / 2.0F + 1.0F, amount);
            case NORMAL -> amount;
            case HARD -> amount * 3.0F / 2.0F;
        };
    }

    /** DifficultyInstance.getEffectiveDifficulty at a position (regional difficulty). */
    public static float effectiveAt(Instance instance, Point pos) {
        long dayTime = instance.getTime();
        return effective(current, dayTime, inhabitedTicks(instance, pos), moonBrightness(dayTime));
    }

    /** DifficultyInstance.calculateDifficulty, verbatim. */
    public static float effective(Difficulty base, long dayTime, long inhabitedTicks, float moonBrightness) {
        if (base == PEACEFUL) return 0.0F;
        boolean hard = base == HARD;
        float global = clamp((dayTime - 72000.0F) / 1440000.0F, 0.0F, 1.0F) * 0.25F;
        float scale = 0.75F + global;
        float local = clamp(inhabitedTicks / 3600000.0F, 0.0F, 1.0F) * (hard ? 1.0F : 0.75F);
        local += clamp(moonBrightness * 0.25F, 0.0F, global);
        if (base == EASY) local *= 0.5F;
        return base.ordinal() * (scale + local);
    }

    /** DifficultyInstance.getSpecialMultiplier: 0 below effective 2, 1 above 4, linear between. */
    public static float specialMultiplierAt(Instance instance, Point pos) {
        float effective = effectiveAt(instance, pos);
        if (effective < 2.0F) return 0.0F;
        return effective > 4.0F ? 1.0F : (effective - 2.0F) / 2.0F;
    }

    public static float moonBrightness(long dayTime) {
        return MOON_BRIGHTNESS[(int) (dayTime / 24000L % 8L + 8L) % 8];
    }

    // ------------------------------------------------------------------ inhabited time

    /** Accrue inhabited time for chunks near players, every second. */
    public static void startTracking() {
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            for (Instance instance : MinecraftServer.getInstanceManager().getInstances()) {
                var players = instance.getPlayers();
                if (players.isEmpty()) continue;
                var map = INHABITED.computeIfAbsent(instance, i -> new ConcurrentHashMap<>());
                for (Player p : players) {
                    int pcx = p.getPosition().chunkX(), pcz = p.getPosition().chunkZ();
                    for (int dz = -INHABITED_RADIUS_CHUNKS; dz <= INHABITED_RADIUS_CHUNKS; dz++) {
                        for (int dx = -INHABITED_RADIUS_CHUNKS; dx <= INHABITED_RADIUS_CHUNKS; dx++) {
                            map.merge(pack(pcx + dx, pcz + dz), 20L, Long::sum);
                        }
                    }
                }
            }
        }).repeat(TaskSchedule.tick(20)).schedule();
    }

    public static long inhabitedTicks(Instance instance, Point pos) {
        var map = INHABITED.get(instance);
        if (map == null) return 0;
        return map.getOrDefault(pack(pos.chunkX(), pos.chunkZ()), 0L);
    }

    /** Force a chunk's inhabited time (tests, or seeding from imported worlds). */
    public static void setInhabitedTicks(Instance instance, Point pos, long ticks) {
        INHABITED.computeIfAbsent(instance, i -> new ConcurrentHashMap<>())
                .put(pack(pos.chunkX(), pos.chunkZ()), ticks);
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }
}
