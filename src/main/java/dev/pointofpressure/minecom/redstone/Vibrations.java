package dev.pointofpressure.minecom.redstone;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sculk vibration system: sensors and calibrated sensors reacting to world
 * events. Ported from decompiled VibrationSystem / SculkSensorBlock /
 * CalibratedSculkSensorBlockEntity (26.1.2): the full 40-event frequency
 * table, vibrations traveling 1 block/gt, arrival power
 * max(1, 15 - floor(15/radius * distance)), sensor radius 8 (active 30gt),
 * calibrated radius 16 (active 10gt, back-face signal filters to one
 * frequency, 0 accepts all), 10gt cooldown phase after activity, comparators
 * reading the last vibration frequency while active, and wool blocking the
 * path (straight-line sample — vanilla occlusion-probe geometry simplified;
 * see AUDIT.md). Emission sites are tapped where minecom already models the
 * event: block place/break, note blocks, TNT fuses, explosions, lightning,
 * projectiles landing, a 5gt movement sweep for steps (sneaking players
 * emit nothing), container open/close (chest/trapped_chest/ender_chest/
 * barrel/hopper/furnace family/shulker box/brewing stand/dispenser+dropper/
 * crafter — decompile-verified against ContainerOpenersCounter's own
 * 0-&gt;1/1-&gt;0 opener-count transition), door/trapdoor/fence-gate open/
 * close, eating, and drinking a potion.
 * Sculk shriekers are vibration listeners too (radius 8, player-caused events
 * only): a heard vibration shrieks for 90gt. Warning levels follow
 * WardenSpawnTracker (decompiled): only can_summon=true shriekers warn (and
 * never in Peaceful, or with a warden within 48 blocks); the warning is
 * shared across players within 16 blocks (max+1, copied to all), increases
 * are rate-limited to one per 200gt per player, and each 12000 quiet ticks
 * decays one level. When a warning shriek ends (SculkShriekerBlock's 90gt
 * scheduled tick -> tryRespond), players within 40 get Darkness 260gt and
 * warning 4 summons a warden (burrow-up emerge via WardenMob.trySummon);
 * lower levels play the warning-proximity reply sound at a random ±10 offset.
 * Wardens themselves listen to every vibration within 16 blocks
 * (WardenMob.hearVibration).
 * Not modeled (AUDIT.md): amethyst resonance, player-direct equip (only
 * a dispenser equipping a mob is wired), swim/splash/flap emissions,
 * waterlogged silencing, per-event Context entity checks, the
 * spawn_wardens gamerule (no gamerule system).
 */
public final class Vibrations {
    private Vibrations() {}

    private static final Set<Long> SENSORS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> SHRIEKERS = ConcurrentHashMap.newKeySet();
    private static final Map<Long, Integer> LAST_FREQUENCY = new ConcurrentHashMap<>();
    private static final Map<Integer, Point> LAST_POSITIONS = new ConcurrentHashMap<>();
    /** Per-player warden warning: [level, lastIncreaseAge, lastDecayAge] (WardenSpawnTracker). */
    private static final Map<java.util.UUID, int[]> WARNINGS = new ConcurrentHashMap<>();
    private static int age;

    private static final int MAX_WARNING_LEVEL = 4;
    private static final int WARNING_DECAY_INTERVAL = 12000; // -1 level per 10 quiet minutes
    private static final int WARNING_INCREASE_COOLDOWN = 200;
    private static final double WARNING_SHARE_RADIUS = 16.0; // players pooled per shriek
    private static final int NEARBY_WARDEN_RADIUS = 48;

    /** VibrationSystem.VIBRATION_FREQUENCY_FOR_EVENT, keyed by event name. */
    private static final Map<String, Integer> FREQ = Map.ofEntries(
            Map.entry("step", 1), Map.entry("swim", 1), Map.entry("flap", 1),
            Map.entry("projectile_land", 2), Map.entry("hit_ground", 2), Map.entry("splash", 2),
            Map.entry("item_interact_finish", 3), Map.entry("projectile_shoot", 3),
            Map.entry("instrument_play", 3),
            Map.entry("entity_action", 4), Map.entry("elytra_glide", 4), Map.entry("unequip", 4),
            Map.entry("entity_dismount", 5), Map.entry("equip", 5),
            Map.entry("entity_interact", 6), Map.entry("shear", 6), Map.entry("entity_mount", 6),
            Map.entry("entity_damage", 7),
            Map.entry("drink", 8), Map.entry("eat", 8),
            Map.entry("container_close", 9), Map.entry("block_close", 9),
            Map.entry("block_deactivate", 9), Map.entry("block_detach", 9),
            Map.entry("container_open", 10), Map.entry("block_open", 10),
            Map.entry("block_activate", 10), Map.entry("block_attach", 10),
            Map.entry("prime_fuse", 10), Map.entry("note_block_play", 10),
            Map.entry("block_change", 11),
            Map.entry("block_destroy", 12), Map.entry("fluid_pickup", 12),
            Map.entry("block_place", 13), Map.entry("fluid_place", 13),
            Map.entry("entity_place", 14), Map.entry("lightning_strike", 14),
            Map.entry("teleport", 14),
            Map.entry("entity_die", 15), Map.entry("explode", 15));

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            String key = e.getBlock().key().value();
            if (key.endsWith("sculk_sensor")) SENSORS.add(pack(e.getBlockPosition()));
            if (key.equals("sculk_shrieker")) SHRIEKERS.add(pack(e.getBlockPosition()));
            emit("block_place", e.getBlockPosition(), e.getPlayer());
        });
        events.addListener(PlayerBlockBreakEvent.class,
                e -> emit("block_destroy", e.getBlockPosition(), e.getPlayer()));
        dev.pointofpressure.minecom.Persist.register(persistence());
    }

    /** Sensor/shrieker tracked-position persistence (docs/PERSISTENCE.md). */
    private static dev.pointofpressure.minecom.StateAdapter persistence() {
        return new dev.pointofpressure.minecom.StateAdapter() {
            @Override
            public String kind() {
                return "sculk_pos";
            }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, com.google.gson.JsonObject> out) {
                for (long key : SENSORS) {
                    var o = new com.google.gson.JsonObject();
                    o.addProperty("t", "sensor");
                    out.accept(unpack(key), o);
                }
                for (long key : SHRIEKERS) {
                    var o = new com.google.gson.JsonObject();
                    o.addProperty("t", "shrieker");
                    out.accept(unpack(key), o);
                }
            }

            @Override
            public void restore(Instance in, Point pos, com.google.gson.JsonObject data) {
                if ("sensor".equals(data.get("t").getAsString())) trackSensor(pos);
                else trackShrieker(pos);
            }

            @Override
            public void wipe() {
                SENSORS.clear();
                SHRIEKERS.clear();
            }
        };
    }

    /** Track sensors loaded from a saved world (or placed by tests). */
    public static void trackSensor(Point pos) {
        SENSORS.add(pack(pos));
    }

    /** Track shriekers loaded from a saved world (or placed by tests). */
    public static void trackShrieker(Point pos) {
        SHRIEKERS.add(pack(pos));
    }

    /**
     * Emit a game event at a position. Sensors in range and inactive schedule
     * an activation after the vibration's 1-block-per-gt travel time.
     */
    public static void emit(String event, Point sourcePos, Entity source) {
        Instance instance = Redstone.instance();
        boolean wardens = dev.pointofpressure.minecom.mobs.ai.WardenMob.anyListening();
        if (instance == null || (SENSORS.isEmpty() && SHRIEKERS.isEmpty() && !wardens)) return;
        int frequency = FREQ.getOrDefault(event, 0);
        if (frequency == 0) return;
        if (source instanceof Player p && p.isSneaking() && frequency == 1) return; // silent steps
        Vec origin = new Vec(sourcePos.x(), sourcePos.y(), sourcePos.z());
        if (wardens) dev.pointofpressure.minecom.mobs.ai.WardenMob.hearVibration(event, origin, source);

        for (long key : SENSORS) {
            Point pos = unpack(key);
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            Block sensor = instance.getBlock(pos);
            String sk = sensor.key().value();
            if (!sk.endsWith("sculk_sensor")) {
                SENSORS.remove(key);
                continue;
            }
            if (!"inactive".equals(sensor.getProperty("sculk_sensor_phase"))) continue;
            boolean calibrated = sk.equals("calibrated_sculk_sensor");
            int radius = calibrated ? 16 : 8;
            Vec center = new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
            double distance = center.distance(origin);
            if (distance > radius) continue;
            if (calibrated) {
                // filter = signal into the back face; 0 accepts everything
                Vec back = Redstone.facingVec(sensor.getProperty("facing")).mul(-1);
                int filter = Redstone.inputPower(pos, back);
                if (filter != 0 && filter != frequency) continue;
            }
            if (woolOccludes(instance, center, origin)) continue;

            int delay = (int) Math.max(1, Math.ceil(distance));
            int power = Math.max(1, 15 - (int) Math.floor(15.0 / radius * distance));
            int activeTicks = calibrated ? 10 : 30;
            Redstone.schedule(delay, () -> activate(instance, pos, power, frequency, activeTicks));
        }

        // shriekers listen too, but only to player-caused vibrations
        if (source instanceof Player player && !SHRIEKERS.isEmpty()) {
            for (long key : SHRIEKERS) {
                Point pos = unpack(key);
                if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
                Block shrieker = instance.getBlock(pos);
                if (!shrieker.key().value().equals("sculk_shrieker")) {
                    SHRIEKERS.remove(key);
                    continue;
                }
                if ("true".equals(shrieker.getProperty("shrieking"))) continue;
                Vec center = new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
                double distance = center.distance(origin);
                if (distance > 8 || woolOccludes(instance, center, origin)) continue;
                int delay = (int) Math.max(1, Math.ceil(distance));
                Redstone.schedule(delay, () -> shriek(instance, pos, player));
            }
        }
    }

    /**
     * SculkShriekerBlockEntity.tryShriek: warn first (only can_summon=true
     * shriekers, see tryToWarn), then shriek for 90gt; the block's scheduled
     * tick clears SHRIEKING and responds (darkness, reply sound or warden).
     */
    private static void shriek(Instance instance, Point pos, Player cause) {
        Block shrieker = instance.getBlock(pos);
        if (!shrieker.key().value().equals("sculk_shrieker")) return;
        if ("true".equals(shrieker.getProperty("shrieking"))) return;
        boolean canRespond = "true".equals(shrieker.getProperty("can_summon"))
                && dev.pointofpressure.minecom.Difficulty.current()
                        != dev.pointofpressure.minecom.Difficulty.PEACEFUL;
        Vec center = new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        int warningLevel = canRespond ? tryToWarn(instance, center, cause) : 0;
        if (canRespond && warningLevel == 0) return; // warn failed (cooldown/warden near): no shriek
        instance.setBlock(pos, shrieker.withProperty("shrieking", "true"));
        Redstone.schedule(90, () -> {
            Block now = instance.getBlock(pos);
            if (now.key().value().equals("sculk_shrieker")
                    && "true".equals(now.getProperty("shrieking"))) {
                instance.setBlock(pos, now.withProperty("shrieking", "false"));
                tryRespond(instance, pos, center, warningLevel);
            }
        });
    }

    /**
     * WardenSpawnTracker.tryWarn: no warning with a warden within 48 blocks or
     * any pooled player on the 200gt increase cooldown; otherwise the highest
     * warning among players within 16 (plus the causer) increases by one and is
     * copied to all of them. Returns the new level, or 0 if warning failed.
     */
    private static int tryToWarn(Instance instance, Vec center, Player cause) {
        if (dev.pointofpressure.minecom.mobs.ai.WardenMob.anyWithin(instance, center, NEARBY_WARDEN_RADIUS)) return 0;
        java.util.List<Player> pooled = new java.util.ArrayList<>();
        for (Player p : instance.getPlayers()) {
            if (p == cause || (p.getGameMode() != net.minestom.server.entity.GameMode.SPECTATOR
                    && p.getPosition().distance(center) <= WARNING_SHARE_RADIUS && !p.isDead())) {
                pooled.add(p);
            }
        }
        int highest = 0;
        for (Player p : pooled) {
            int[] w = warningOf(p.getUuid());
            if (age - w[1] < WARNING_INCREASE_COOLDOWN && w[1] != 0) return 0; // on cooldown
            highest = Math.max(highest, w[0]);
        }
        int level = Math.min(MAX_WARNING_LEVEL, highest + 1);
        for (Player p : pooled) {
            int[] w = warningOf(p.getUuid());
            w[0] = level;
            w[1] = age;
            w[2] = age;
        }
        return level;
    }

    /** Current warning entry with the 12000gt-per-level quiet decay applied. */
    private static int[] warningOf(java.util.UUID player) {
        int[] w = WARNINGS.computeIfAbsent(player, k -> new int[]{0, 0, 0});
        int elapsed = age - w[2];
        if (elapsed >= WARNING_DECAY_INTERVAL && w[0] > 0) {
            w[0] = Math.max(0, w[0] - elapsed / WARNING_DECAY_INTERVAL);
            w[2] = age;
        }
        return w;
    }

    /** Test hook: pin a player's warning level (playtest drives warning 4 directly). */
    public static void setWarningLevel(java.util.UUID player, int level) {
        WARNINGS.put(player, new int[]{level, 0, age});
    }

    /**
     * SculkShriekerBlockEntity.tryRespond: after a warning shriek, Darkness
     * (260gt) within 40 blocks; warning 4 summons a warden (burrow-up emerge
     * within ±5 xz / ±6 y), lower levels answer with the proximity sound at a
     * random ±10 block offset.
     */
    private static void tryRespond(Instance instance, Point pos, Vec center, int warningLevel) {
        if (warningLevel <= 0) return;
        boolean summoned = warningLevel >= MAX_WARNING_LEVEL
                && dev.pointofpressure.minecom.mobs.ai.WardenMob.trySummon(instance, pos) != null;
        if (!summoned) {
            var sound = switch (warningLevel) {
                case 1 -> net.minestom.server.sound.SoundEvent.ENTITY_WARDEN_NEARBY_CLOSE;
                case 2 -> net.minestom.server.sound.SoundEvent.ENTITY_WARDEN_NEARBY_CLOSER;
                case 3 -> net.minestom.server.sound.SoundEvent.ENTITY_WARDEN_NEARBY_CLOSEST;
                default -> net.minestom.server.sound.SoundEvent.ENTITY_WARDEN_LISTENING_ANGRY;
            };
            var rng = java.util.concurrent.ThreadLocalRandom.current();
            instance.playSound(net.kyori.adventure.sound.Sound.sound(sound,
                            net.kyori.adventure.sound.Sound.Source.HOSTILE, 5f, 1f),
                    pos.blockX() + rng.nextInt(-10, 11),
                    pos.blockY() + rng.nextInt(-10, 11),
                    pos.blockZ() + rng.nextInt(-10, 11));
        }
        dev.pointofpressure.minecom.mobs.ai.WardenMob.applyDarknessAround(instance, center, 40);
    }

    private static void activate(Instance instance, Point pos, int power, int frequency, int activeTicks) {
        Block sensor = instance.getBlock(pos);
        if (!sensor.key().value().endsWith("sculk_sensor")) return;
        if (!"inactive".equals(sensor.getProperty("sculk_sensor_phase"))) return;
        LAST_FREQUENCY.put(pack(pos), frequency);
        instance.setBlock(pos, sensor
                .withProperty("sculk_sensor_phase", "active")
                .withProperty("power", String.valueOf(power)));
        Redstone.neighborsChanged(pos);
        Redstone.schedule(activeTicks, () -> {
            Block now = instance.getBlock(pos);
            if (!now.key().value().endsWith("sculk_sensor")) return;
            instance.setBlock(pos, now
                    .withProperty("sculk_sensor_phase", "cooldown")
                    .withProperty("power", "0"));
            Redstone.neighborsChanged(pos);
            Redstone.schedule(10, () -> {
                Block later = instance.getBlock(pos);
                if (!later.key().value().endsWith("sculk_sensor")) return;
                if ("cooldown".equals(later.getProperty("sculk_sensor_phase"))) {
                    instance.setBlock(pos, later.withProperty("sculk_sensor_phase", "inactive"));
                }
            });
        });
    }

    /** Straight-line wool check, sampled every half block. Public: wardens use it too. */
    public static boolean woolOccludes(Instance instance, Vec from, Vec to) {
        double distance = from.distance(to);
        int steps = (int) Math.ceil(distance * 2);
        for (int i = 1; i < steps; i++) {
            Vec at = from.add(to.sub(from).mul((double) i / steps));
            if (instance.getBlock(at).key().value().endsWith("_wool")) return true;
        }
        return false;
    }

    /** 5gt sweep: entities that moved since the last sweep emit STEP vibrations. */
    static void tickSteps() {
        Instance instance = Redstone.instance();
        age += 5;
        if (instance == null || (SENSORS.isEmpty() && SHRIEKERS.isEmpty())) return;
        for (Entity entity : instance.getEntities()) {
            if (entity instanceof ItemEntity || entity.isRemoved()) continue;
            // Warden.dampensVibrations: a warden's own movement emits nothing
            if (entity.getEntityType() == net.minestom.server.entity.EntityType.WARDEN) continue;
            Point now = entity.getPosition();
            Point before = LAST_POSITIONS.put(entity.getEntityId(),
                    new Vec(now.x(), now.y(), now.z()));
            if (before == null) continue;
            double moved = Math.abs(now.x() - before.x()) + Math.abs(now.z() - before.z());
            if (moved > 0.1) emit("step", now, entity);
        }
    }

    /** SculkSensorBlock.getAnalogOutputSignal: last frequency while active, else 0. */
    static int comparatorOutput(Point pos, Block sensor) {
        if (!"active".equals(sensor.getProperty("sculk_sensor_phase"))) return 0;
        return LAST_FREQUENCY.getOrDefault(pack(pos), 0);
    }

    private static long pack(Point p) {
        return ((long) (p.blockX() & 0x3FFFFFF) << 38) | ((long) (p.blockY() & 0xFFF) << 26)
                | (p.blockZ() & 0x3FFFFFF);
    }

    private static Vec unpack(long k) {
        return new Vec((int) (k >> 38), (int) (k << 26 >> 52), (int) (k << 38 >> 38));
    }
}
