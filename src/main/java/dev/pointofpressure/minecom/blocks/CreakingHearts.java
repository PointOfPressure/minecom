package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.mobs.ai.CreakingMob;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creaking Heart block entity, ported from decompiled CreakingHeartBlock /
 * CreakingHeartBlockEntity / CreakingHeartState (26.1.2). State machine per
 * 20+rand(5)-tick check: UPROOTED without matching-axis pale-oak logs on
 * both sides, else AWAKE at night (the CREAKING_ACTIVE day-timeline
 * attribute, modeled as 13000-23000 like this project's other night checks)
 * or DORMANT by day. An awake heart with a player within 32 spawns ONE
 * creaking protector (5 attempts, ±16 xz / ±8 y, on-solid-not-leaves) and
 * links it; the link drops (teardown) when day comes, the creaking strays
 * past 34 blocks, or a player stands inside it 5+ ticks. Damage to the
 * protector redirects here: a 100gt hurt call plays interpolated
 * heart-crack sounds every 10gt and, while AWAKE, grows 2-3 resin clumps
 * (breadth-first over pale-oak logs, depth 2 / 64 nodes, multiface
 * accumulation, waterlogged on source water). Breaking the heart kills the
 * protector with death effects; NATURAL=true hearts (worldgen) pop 20-24
 * XP. Comparator reads 15 - floor(clamp(dist,0,32)/32*15). Session-scoped
 * like TrialChambers (hearts re-track on placement, not on world reload —
 * AUDIT.md); hurt-call particles are not sent (no particle idiom).
 */
public final class CreakingHearts {
    private CreakingHearts() {}

    private static final int PLAYER_DETECTION_RANGE = 32;
    private static final int DISTANCE_TOO_FAR = 34;
    private static final int HURT_CALL_TICKS = 100;
    private static final int HURT_CALL_INTERVAL = 10;
    private static final int RESIN_MAX_DEPTH = 2;
    private static final int RESIN_MAX_COUNT = 64;

    private static final class Heart {
        CreakingMob protector;
        int ticker;
        int emitter;
        Point emitterTarget;
    }

    private static final Map<Long, Heart> HEARTS = new ConcurrentHashMap<>();
    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (e.getBlock().key().value().equals("creaking_heart")) {
                track(e.getBlockPosition());
            }
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            if (!e.getBlock().key().value().equals("creaking_heart")) return;
            Heart heart = HEARTS.remove(pack(e.getBlockPosition()));
            if (heart != null && heart.protector != null) {
                heart.protector.deathEffects();
            }
            if ("true".equals(e.getBlock().getProperty("natural"))
                    && e.getPlayer().getGameMode() != net.minestom.server.entity.GameMode.CREATIVE) {
                dev.pointofpressure.minecom.survival.Experience.orb(e.getInstance(),
                        e.getBlockPosition().add(0.5, 0.5, 0.5),
                        ThreadLocalRandom.current().nextInt(20, 25));
            }
        });
        // Creaking.hurtServer: the heart-bound protector redirects damage here
        events.addListener(EntityDamageEvent.class, e -> {
            if (e.getEntity().getEntityType() != EntityType.CREAKING) return;
            CreakingMob creaking = CreakingMob.of(e.getEntity());
            if (creaking != null && creaking.handleHurt(e.getDamage().getAttacker())) {
                e.setCancelled(true);
            }
        });
        MinecraftServer.getSchedulerManager().buildTask(CreakingHearts::tick)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    /** Track a heart placed by tests or world code. */
    public static void track(Point pos) {
        HEARTS.computeIfAbsent(pack(pos), k -> new Heart());
    }

    /** The protector check CreakingMob polls every tick. */
    public static boolean isProtector(Point heartPos, CreakingMob creaking) {
        Heart heart = HEARTS.get(pack(heartPos));
        return heart != null && heart.protector == creaking;
    }

    /** SculkShrieker-style comparator read: distance-scaled 15..1, 0 unlinked. */
    public static int comparatorSignal(Point pos) {
        Heart heart = HEARTS.get(pack(pos));
        if (heart == null || heart.protector == null || heart.protector.entity().isRemoved()) return 0;
        double distance = heart.protector.entity().getPosition()
                .distance(new Vec(pos.blockX() + 0.5, pos.blockY(), pos.blockZ() + 0.5));
        return 15 - (int) Math.floor(Math.clamp(distance, 0.0, 32.0) / 32.0 * 15.0);
    }

    // ------------------------------------------------------------------ tick

    private static void tick() {
        if (instance == null || HEARTS.isEmpty()) return;
        for (Map.Entry<Long, Heart> entry : HEARTS.entrySet()) {
            Point pos = unpack(entry.getKey());
            Heart heart = entry.getValue();
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            Block block = instance.getBlock(pos);
            if (!block.key().value().equals("creaking_heart")) {
                HEARTS.remove(entry.getKey());
                if (heart.protector != null) heart.protector.tearDown();
                continue;
            }
            tickEmitter(pos, heart);
            if (heart.ticker-- >= 0) continue;
            heart.ticker = 20 + ThreadLocalRandom.current().nextInt(5);
            block = updateState(pos, block);
            if ("uprooted".equals(block.getProperty("creaking_heart_state"))) continue;

            if (heart.protector == null) {
                if ("awake".equals(block.getProperty("creaking_heart_state"))
                        && dev.pointofpressure.minecom.Difficulty.current()
                                != dev.pointofpressure.minecom.Difficulty.PEACEFUL
                        && playerWithin(pos, PLAYER_DETECTION_RANGE)) {
                    spawnProtector(pos, heart);
                }
            } else {
                CreakingMob creaking = heart.protector;
                if (creaking.entity().isRemoved()) {
                    heart.protector = null;
                } else if (isDay() || tooFar(pos, creaking) || creaking.playerIsStuckInYou()) {
                    creaking.tearDown();
                    heart.protector = null;
                }
            }
        }
    }

    /** UPROOTED without its logs; else AWAKE at night, DORMANT by day. */
    private static Block updateState(Point pos, Block block) {
        String state;
        if (!hasRequiredLogs(pos, block)) {
            state = "uprooted";
        } else {
            state = isDay() ? "dormant" : "awake";
        }
        if (!state.equals(block.getProperty("creaking_heart_state"))) {
            block = block.withProperty("creaking_heart_state", state);
            instance.setBlock(pos, block);
        }
        return block;
    }

    /** Matching-axis pale-oak logs on BOTH sides along the heart's AXIS. */
    private static boolean hasRequiredLogs(Point pos, Block block) {
        String axis = block.getProperty("axis");
        if (axis == null) axis = "y";
        int dx = axis.equals("x") ? 1 : 0;
        int dy = axis.equals("y") ? 1 : 0;
        int dz = axis.equals("z") ? 1 : 0;
        return isPaleOakLog(instance.getBlock(pos.add(dx, dy, dz)), axis)
                && isPaleOakLog(instance.getBlock(pos.add(-dx, -dy, -dz)), axis);
    }

    private static boolean isPaleOakLog(Block block, String axis) {
        String key = block.key().value();
        if (!key.equals("pale_oak_log") && !key.equals("pale_oak_wood")
                && !key.equals("stripped_pale_oak_log") && !key.equals("stripped_pale_oak_wood")) return false;
        return axis.equals(block.getProperty("axis"));
    }

    /** SpawnUtil 5 attempts, ±16 xz, walk down from +8, solid-not-leaves floor. */
    private static void spawnProtector(Point pos, Heart heart) {
        var rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 5; attempt++) {
            int x = pos.blockX() + rng.nextInt(-16, 17);
            int z = pos.blockZ() + rng.nextInt(-16, 17);
            for (int y = pos.blockY() + 8; y >= pos.blockY() - 8; y--) {
                if (!instance.isChunkLoaded(x >> 4, z >> 4)) break;
                Block floor = instance.getBlock(x, y - 1, z);
                if (!floor.isSolid() || floor.key().value().endsWith("_leaves")) continue;
                if (instance.getBlock(x, y, z).isSolid() || instance.getBlock(x, y + 1, z).isSolid()
                        || instance.getBlock(x, y + 2, z).isSolid()) break;
                var creakingEntity = CreakingMob.spawn(instance,
                        new net.minestom.server.coordinate.Pos(x + 0.5, y, z + 0.5), pos);
                heart.protector = CreakingMob.of(creakingEntity);
                sound(pos, SoundEvent.ENTITY_CREAKING_SPAWN, 1f);
                sound(pos, SoundEvent.BLOCK_CREAKING_HEART_SPAWN, 1f);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ hurt call

    /**
     * CreakingHeartBlockEntity.creakingHurt: start the 100gt emitter (no
     * restart while one runs); while AWAKE also grow 2-3 resin clumps.
     */
    public static void creakingHurt(Point heartPos) {
        Heart heart = HEARTS.get(pack(heartPos));
        if (heart == null || heart.protector == null || heart.emitter > 0) return;
        Block block = instance.getBlock(heartPos);
        if ("awake".equals(block.getProperty("creaking_heart_state"))) {
            int clumps = ThreadLocalRandom.current().nextInt(2, 4);
            for (int i = 0; i < clumps; i++) {
                Point placed = spreadResin(heartPos);
                if (placed != null) sound(placed, SoundEvent.BLOCK_RESIN_PLACE, 1f);
            }
        }
        heart.emitter = HURT_CALL_TICKS;
        heart.emitterTarget = heart.protector.entity().getPosition();
    }

    /** Sounds every 10gt sliding from the creaking toward the heart, rising volume. */
    private static void tickEmitter(Point pos, Heart heart) {
        if (heart.emitter <= 0) return;
        if (heart.emitter % HURT_CALL_INTERVAL == 0 && heart.emitterTarget != null) {
            if (heart.protector != null && !heart.protector.entity().isRemoved()) {
                heart.emitterTarget = heart.protector.entity().getPosition();
            }
            double progress = 0.2 + 0.8 * (HURT_CALL_TICKS - heart.emitter) / 100.0;
            Vec heartCenter = new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
            Vec at = heartCenter.sub(heart.emitterTarget).mul(progress)
                    .add(heart.emitterTarget.x(), heart.emitterTarget.y(), heart.emitterTarget.z());
            float volume = heart.emitter / 2f / 100f + 0.5f;
            sound(at, SoundEvent.BLOCK_CREAKING_HEART_HURT, volume);
        }
        heart.emitter--;
    }

    /**
     * CreakingHeartBlockEntity.spreadResin: breadth-first through pale-oak
     * logs (depth 2, 64 nodes); the first free face adjacent to a log gets a
     * resin clump face pointing back at it. Returns the position, or null.
     */
    private static Point spreadResin(Point heartPos) {
        var rng = ThreadLocalRandom.current();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(heartPos);
        visited.add(pack(heartPos));
        int depth = 0, count = 0;
        List<Point> frontier = new ArrayList<>(queue);
        while (depth <= RESIN_MAX_DEPTH && !frontier.isEmpty() && count < RESIN_MAX_COUNT) {
            List<Point> next = new ArrayList<>();
            for (Point node : frontier) {
                count++;
                Block nodeBlock = instance.getBlock(node);
                boolean isLog = isAnyPaleOakLog(nodeBlock) || node.equals(heartPos);
                if (isLog && !node.equals(heartPos)) {
                    Point placed = tryPlaceResin(node, rng);
                    if (placed != null) return placed;
                }
                for (int[] d : shuffledDirections(rng)) {
                    Point neighbor = node.add(d[0], d[1], d[2]);
                    if (!visited.add(pack(neighbor))) continue;
                    if (isAnyPaleOakLog(instance.getBlock(neighbor))) next.add(neighbor);
                }
            }
            frontier = next;
            depth++;
        }
        return null;
    }

    private static Point tryPlaceResin(Point log, ThreadLocalRandom rng) {
        for (int[] d : shuffledDirections(rng)) {
            Point facePos = log.add(d[0], d[1], d[2]);
            Block at = instance.getBlock(facePos);
            String faceToLog = faceName(-d[0], -d[1], -d[2]);
            if (at.isAir()) {
                instance.setBlock(facePos, Block.RESIN_CLUMP.withProperty(faceToLog, "true"));
                return facePos;
            }
            if (at.key().value().equals("water")) {
                instance.setBlock(facePos, Block.RESIN_CLUMP
                        .withProperty(faceToLog, "true").withProperty("waterlogged", "true"));
                return facePos;
            }
            if (at.key().value().equals("resin_clump") && !"true".equals(at.getProperty(faceToLog))) {
                instance.setBlock(facePos, at.withProperty(faceToLog, "true"));
                return facePos;
            }
        }
        return null;
    }

    private static boolean isAnyPaleOakLog(Block block) {
        String key = block.key().value();
        return key.equals("pale_oak_log") || key.equals("pale_oak_wood")
                || key.equals("stripped_pale_oak_log") || key.equals("stripped_pale_oak_wood");
    }

    private static int[][] shuffledDirections(ThreadLocalRandom rng) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int i = dirs.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int[] tmp = dirs[i];
            dirs[i] = dirs[j];
            dirs[j] = tmp;
        }
        return dirs;
    }

    private static String faceName(int dx, int dy, int dz) {
        if (dx > 0) return "east";
        if (dx < 0) return "west";
        if (dy > 0) return "up";
        if (dy < 0) return "down";
        return dz > 0 ? "south" : "north";
    }

    // ------------------------------------------------------------------ misc

    private static boolean playerWithin(Point pos, double range) {
        Vec center = new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        return instance.getPlayers().stream().anyMatch(p ->
                p.getGameMode() != net.minestom.server.entity.GameMode.SPECTATOR
                        && p.getPosition().distance(center) <= range);
    }

    private static boolean tooFar(Point pos, CreakingMob creaking) {
        return creaking.entity().getPosition()
                .distance(new Vec(pos.blockX() + 0.5, pos.blockY(), pos.blockZ() + 0.5)) > DISTANCE_TOO_FAR;
    }

    /** CREAKING_ACTIVE day-timeline attribute, modeled as the standard night window. */
    private static boolean isDay() {
        long t = Math.floorMod(instance.getTime(), 24000L);
        return t < 13000 || t > 23000;
    }

    private static void sound(Point at, SoundEvent event, float volume) {
        instance.playSound(Sound.sound(event, Sound.Source.BLOCK, volume, 1f),
                at.blockX() + 0.5, at.blockY() + 0.5, at.blockZ() + 0.5);
    }

    private static long pack(Point p) {
        return ((long) (p.blockX() & 0x3FFFFFF) << 38) | ((long) (p.blockY() & 0xFFF) << 26)
                | (p.blockZ() & 0x3FFFFFF);
    }

    private static Vec unpack(long k) {
        return new Vec((int) (k >> 38), (int) (k << 26 >> 52), (int) (k << 38 >> 38));
    }
}
