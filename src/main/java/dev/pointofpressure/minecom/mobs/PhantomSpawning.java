package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.Difficulty;
import dev.pointofpressure.minecom.TickPipeline;
import dev.pointofpressure.minecom.survival.Lightning;
import dev.pointofpressure.minecom.worldgen.WorldGen;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phantom natural spawning, decompile-verified against {@code PhantomSpawner}/{@code Phantom}
 * (26.2). A per-world countdown (60-119 real seconds, re-rolled after every attempt whether or
 * not anything actually spawns) checks every non-spectator player who is at/above sea level and
 * can see the sky (this spawner only ever runs on the overworld here, so real vanilla's
 * "no sky light -> skip the sky-visibility check" branch for dimensions like the Nether never
 * applies), rolls the real regional-difficulty gate ({@code DifficultyInstance.isHarderThan},
 * already ported as {@link Difficulty#effectiveAt}), then the real insomnia roll. That roll
 * reads a player's own "ticks since last real sleep" (real vanilla's {@code Stats.
 * TIME_SINCE_REST} stat) — this project has no general player-stats system, so it's tracked ad
 * hoc here instead, reset by {@code Beds.interact} on a successful sleep: {@code
 * random(ticksSinceRest) >= 72000} is mathematically impossible before 72000 ticks (an in-game
 * hour) awake and increasingly likely afterward. On a pass, 1 to (the difficulty's real ordinal
 * + 1) phantoms spawn 20-34 blocks above the player, each at real vanilla's fixed size 0 (6
 * damage) — {@code Phantom.finalizeSpawn} unconditionally resets size to 0 on every natural
 * spawn in this decompile. AUDIT.md previously assumed phantom size scales with insomnia; this
 * decompile shows that's no longer true in this version, so the existing size-0-only mob stats
 * were already correct and needed no change here.
 */
public final class PhantomSpawning {
    private PhantomSpawning() {}

    private static final Random RANDOM = new Random();
    private static final Map<UUID, Integer> TICKS_SINCE_REST = new ConcurrentHashMap<>();
    private static int nextTick;

    public static void start(Instance overworld) {
        TickPipeline.register(TickPipeline.MOB_SPAWNING, "phantomSpawning", () -> tick(overworld));
    }

    /** Beds.interact calls this after a successful sleep — real vanilla resets
     *  {@code Stats.TIME_SINCE_REST} to 0 on waking. */
    public static void resetTicksSinceRest(Player player) {
        TICKS_SINCE_REST.put(player.getUuid(), 0);
    }

    /** Test-only: jam a specific "ticks since rest" value instead of waiting for it to
     *  accumulate tick-by-tick (mirrors VNaturalSpawner.spawnTick's test-callable seam). */
    public static void setTicksSinceRestForTest(Player player, int ticks) {
        TICKS_SINCE_REST.put(player.getUuid(), ticks);
    }

    /** Test-only: force the next {@link #tick} call to perform a spawn attempt immediately
     *  instead of waiting out the real 60-119s countdown. */
    public static void forceAttemptForTest() {
        nextTick = 0;
    }

    /** Test-only direct entry point into the per-tick logic. */
    public static void tickForTest(Instance instance) {
        tick(instance);
    }

    private static void tick(Instance instance) {
        for (Player p : instance.getPlayers()) {
            TICKS_SINCE_REST.merge(p.getUuid(), 1, Integer::sum);
        }

        if (--nextTick > 0) return;
        nextTick = (60 + RANDOM.nextInt(60)) * 20; // PhantomSpawner: (60 + random(60)) seconds

        long dayTime = Math.floorMod(instance.getTime(), 24000L);
        // Level.getSkyDarken() >= 5 collapses to "night or a thunderstorm" absent a tracked
        // per-tick sky-darkening value in this project.
        if (!(dayTime > 13000 && dayTime < 23000) && !Lightning.isThundering(instance)) return;

        for (Player player : instance.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) continue;
            Pos pos = player.getPosition();
            if (pos.y() < WorldGen.SEA_LEVEL || !Lightning.canSeeSky(instance, pos)) continue;

            float difficulty = Difficulty.effectiveAt(instance, pos);
            if (!(difficulty > RANDOM.nextFloat() * 3.0f)) continue; // DifficultyInstance.isHarderThan

            int ticksSinceRest = Math.max(1, TICKS_SINCE_REST.getOrDefault(player.getUuid(), 0));
            if (RANDOM.nextInt(ticksSinceRest) < 72000) continue;

            Pos spawnAt = new Pos(
                    Math.floor(pos.x()) + (-10 + RANDOM.nextInt(21)) + 0.5,
                    Math.floor(pos.y()) + 20 + RANDOM.nextInt(15),
                    Math.floor(pos.z()) + (-10 + RANDOM.nextInt(21)) + 0.5);
            if (!spawnValid(instance, spawnAt)) continue;

            int groupSize = 1 + RANDOM.nextInt(Difficulty.current().ordinal() + 1);
            for (int i = 0; i < groupSize; i++) {
                Mobs.spawn("phantom", instance, spawnAt);
            }
        }
    }

    /** NaturalSpawner.isValidEmptySpawnBlock, approximated (this project's established
     *  coarse-solidity pattern elsewhere): the phantom's own body space must be air. */
    private static boolean spawnValid(Instance instance, Pos at) {
        return instance.getBlock(at).isAir() && instance.getBlock(at.add(0, 1, 0)).isAir();
    }
}
