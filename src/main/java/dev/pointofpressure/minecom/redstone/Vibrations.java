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
 * event: block place/break, note blocks, doors, TNT fuses, explosions,
 * lightning, projectiles landing, and a 5gt movement sweep for steps
 * (sneaking players emit nothing).
 * Not modeled (AUDIT.md): sculk shriekers/warden, resonance via amethyst
 * blocks, container open/close and eat/drink/equip-class entity events,
 * waterlogged silencing, per-event Context entity checks.
 */
public final class Vibrations {
    private Vibrations() {}

    private static final Set<Long> SENSORS = ConcurrentHashMap.newKeySet();
    private static final Map<Long, Integer> LAST_FREQUENCY = new ConcurrentHashMap<>();
    private static final Map<Integer, Point> LAST_POSITIONS = new ConcurrentHashMap<>();

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
            emit("block_place", e.getBlockPosition(), e.getPlayer());
        });
        events.addListener(PlayerBlockBreakEvent.class,
                e -> emit("block_destroy", e.getBlockPosition(), e.getPlayer()));
    }

    /** Track sensors loaded from a saved world (or placed by tests). */
    public static void trackSensor(Point pos) {
        SENSORS.add(pack(pos));
    }

    /**
     * Emit a game event at a position. Sensors in range and inactive schedule
     * an activation after the vibration's 1-block-per-gt travel time.
     */
    public static void emit(String event, Point sourcePos, Entity source) {
        Instance instance = Redstone.instance();
        if (instance == null || SENSORS.isEmpty()) return;
        int frequency = FREQ.getOrDefault(event, 0);
        if (frequency == 0) return;
        if (source instanceof Player p && p.isSneaking() && frequency == 1) return; // silent steps
        Vec origin = new Vec(sourcePos.x(), sourcePos.y(), sourcePos.z());

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

    /** Straight-line wool check, sampled every half block. */
    private static boolean woolOccludes(Instance instance, Vec from, Vec to) {
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
        if (instance == null || SENSORS.isEmpty()) return;
        for (Entity entity : instance.getEntities()) {
            if (entity instanceof ItemEntity || entity.isRemoved()) continue;
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
